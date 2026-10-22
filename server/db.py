# -*- coding: utf-8 -*-
"""SQLite 数据层。

ThreadingHTTPServer 每请求一个线程，sqlite3 连接不能跨线程共用，
所以用 threading.local 按线程持有。连接断了重试一次（NAS 上偶发 I/O 抖动）。
"""
import json
import os
import sqlite3
import threading
import time

from .config import DB_PATH, SEED_FILE
from .log import log

_INIT_LOCK = threading.Lock()
_TLS = threading.local()

SCHEMA = """
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS history(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL, key TEXT NOT NULL,
  title TEXT, cover TEXT, source TEXT, ep TEXT, url TEXT,
  position REAL DEFAULT 0, duration REAL DEFAULT 0,
  plays INTEGER DEFAULT 1, genre TEXT, year TEXT, updated_at INTEGER,
  UNIQUE(kind, key));
CREATE INDEX IF NOT EXISTS ix_hist_t ON history(updated_at DESC);

CREATE TABLE IF NOT EXISTS fav(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL, key TEXT NOT NULL,
  title TEXT, cover TEXT, payload TEXT, added_at INTEGER,
  UNIQUE(kind, key));
CREATE INDEX IF NOT EXISTS ix_fav_t ON fav(added_at DESC);

CREATE TABLE IF NOT EXISTS sources(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL,               -- cms | live | magnet | music
  name TEXT NOT NULL,
  url TEXT,
  enabled INTEGER DEFAULT 1,
  pri INTEGER DEFAULT 5,
  meta TEXT,                        -- JSON: type/key/tag/...
  added_at INTEGER,
  UNIQUE(kind, name));

CREATE TABLE IF NOT EXISTS prefs(k TEXT PRIMARY KEY, v TEXT);

CREATE TABLE IF NOT EXISTS searches(
  kw TEXT PRIMARY KEY, cnt INTEGER DEFAULT 1, last_at INTEGER);

CREATE TABLE IF NOT EXISTS live(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  grp TEXT, name TEXT NOT NULL, url TEXT NOT NULL,
  src TEXT, added_at INTEGER,
  UNIQUE(name, url));

CREATE TABLE IF NOT EXISTS cache(
  k TEXT PRIMARY KEY, val TEXT, ts INTEGER);

-- 家庭账户：一个 admin 管配置，其余家人各自一份记录与收藏。
-- 只做 PIN，不做密码体系——内网自用，够用且省事。
CREATE TABLE IF NOT EXISTS profiles(
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  avatar TEXT DEFAULT '',
  role TEXT NOT NULL DEFAULT 'member',
  pin TEXT DEFAULT '',
  pin_lookup TEXT,
  created_at INTEGER,
  last_seen INTEGER);

-- 登录会话：token 存库，NAS 重启后不用重新登录
CREATE TABLE IF NOT EXISTS sessions(
  token TEXT PRIMARY KEY,
  profile TEXT NOT NULL,
  created_at INTEGER,
  expires_at INTEGER,
  ua TEXT);
"""

# 老库平滑升级：sources 早期只有 kind/name/url/enabled/pri/meta
_MIGRATIONS = [
    ("type", "ALTER TABLE sources ADD COLUMN type INTEGER DEFAULT 1"),
    ("cat", "ALTER TABLE sources ADD COLUMN cat TEXT"),
    ("status", "ALTER TABLE sources ADD COLUMN status TEXT"),
    ("checked_at", "ALTER TABLE sources ADD COLUMN checked_at INTEGER"),
    ("api_count", "ALTER TABLE sources ADD COLUMN api_count INTEGER DEFAULT 0"),
    # 直播频道探活：NULL 未测 / 1 可播 / 0 不可播
    ("live_alive", "ALTER TABLE live ADD COLUMN alive INTEGER"),
    ("live_probed", "ALTER TABLE live ADD COLUMN probed_at INTEGER"),
    # 连续失效次数：每天自动巡检时累加，达到阈值才清理，避免一次抽风就删源
    ("live_dead_hits", "ALTER TABLE live ADD COLUMN dead_hits INTEGER DEFAULT 0"),
    # 台标地址（M3U 的 tvg-logo）：有就直接显示，省得全是空白格子
    ("live_logo", "ALTER TABLE live ADD COLUMN logo TEXT"),
]


def _rebuild_profile_scope(con):
    """给 history / fav 加 profile 并把唯一键改成 (profile, kind, key)。

    SQLite 不支持删表级约束，只能建新表 → 拷数据 → 删旧表 → 改名。
    存量数据统一归到 admin，升级不丢记录。
    """
    cols = {r[1] for r in con.execute("PRAGMA table_info(history)")}
    if "profile" not in cols:
        con.executescript("""
        CREATE TABLE history_v2(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          profile TEXT NOT NULL DEFAULT 'admin',
          kind TEXT NOT NULL, key TEXT NOT NULL,
          title TEXT, cover TEXT, source TEXT, ep TEXT, url TEXT,
          position REAL DEFAULT 0, duration REAL DEFAULT 0,
          plays INTEGER DEFAULT 1, genre TEXT, year TEXT, updated_at INTEGER,
          UNIQUE(profile, kind, key));
        INSERT INTO history_v2(id,profile,kind,key,title,cover,source,ep,url,
                               position,duration,plays,genre,year,updated_at)
          SELECT id,'admin',kind,key,title,cover,source,ep,url,
                 position,duration,plays,genre,year,updated_at FROM history;
        DROP TABLE history;
        ALTER TABLE history_v2 RENAME TO history;
        """)
        log("数据迁移：history 增加 profile 维度（存量归 admin）")

    cols = {r[1] for r in con.execute("PRAGMA table_info(fav)")}
    if "profile" not in cols:
        con.executescript("""
        CREATE TABLE fav_v2(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          profile TEXT NOT NULL DEFAULT 'admin',
          kind TEXT NOT NULL, key TEXT NOT NULL,
          title TEXT, cover TEXT, payload TEXT, added_at INTEGER,
          UNIQUE(profile, kind, key));
        INSERT INTO fav_v2(id,profile,kind,key,title,cover,payload,added_at)
          SELECT id,'admin',kind,key,title,cover,payload,added_at FROM fav;
        DROP TABLE fav;
        ALTER TABLE fav_v2 RENAME TO fav;
        """)
        log("数据迁移：fav 增加 profile 维度（存量归 admin）")

    # profiles：PIN 查找列（老库补列）＋ 唯一索引（PIN 不允许重复）
    pcols = {r[1] for r in con.execute("PRAGMA table_info(profiles)")}
    if pcols and "pin_lookup" not in pcols:
        con.execute("ALTER TABLE profiles ADD COLUMN pin_lookup TEXT")
    con.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_profiles_pin ON profiles(pin_lookup)")

    con.execute("CREATE INDEX IF NOT EXISTS ix_hist_p ON history(profile, updated_at DESC)")
    con.execute("CREATE INDEX IF NOT EXISTS ix_fav_p ON fav(profile, added_at DESC)")
    con.commit()


def db_init():
    d = os.path.dirname(DB_PATH)
    if d:
        os.makedirs(d, exist_ok=True)
    con = sqlite3.connect(DB_PATH, timeout=15, check_same_thread=False)
    con.executescript(SCHEMA)
    con.commit()
    for _col, ddl in _MIGRATIONS:
        try:
            con.execute(ddl)
        except Exception:
            pass                      # 已经存在这一列
    con.commit()
    try:
        _rebuild_profile_scope(con)
    except Exception as e:
        log("profile 迁移失败：%s" % str(e)[:120])
    return con


def db():
    con = getattr(_TLS, "con", None)
    if con is None:
        con = db_init()
        _TLS.con = con
    return con


def dbrun(sql, args=(), fetch=False):
    for attempt in range(2):
        try:
            con = db()
            cur = con.execute(sql, args)
            rows = cur.fetchall() if fetch else None
            con.commit()
            return rows
        except Exception as e:
            _TLS.con = None
            if attempt == 0:
                time.sleep(0.08)
            else:
                # 以前这里完全静默：库出问题时页面只显示"没有内容"，无从排查
                log("SQL 失败：%s | %s" % (str(e)[:80],
                                       " ".join(sql.split())[:90]))
    return [] if fetch else None


# ── 偏好 ──────────────────────────────────────────────────────
def pref_read(key, default=None):
    rows = dbrun("SELECT v FROM prefs WHERE k=?", (key,), fetch=True)
    if not rows:
        return default
    return rows[0][0]


def pref_write(key, value):
    dbrun("INSERT OR REPLACE INTO prefs(k,v) VALUES(?,?)", (key, str(value)))


def pref_json(key, default=None):
    import json
    raw = pref_read(key)
    if not raw:
        return default
    try:
        return json.loads(raw)
    except Exception:
        return default


def pref_set_json(key, value):
    import json
    pref_write(key, json.dumps(value, ensure_ascii=False))


# ── 观看历史 / 收藏 / 搜索 ────────────────────────────────────
def note_search(kw):
    k = (kw or "").strip()
    if not k:
        return
    dbrun("""INSERT INTO searches(kw,cnt,last_at) VALUES(?,1,?)
             ON CONFLICT(kw) DO UPDATE SET cnt=cnt+1, last_at=excluded.last_at""",
          (k, int(time.time())))


def touch_history(kind, key, profile="admin", **kw):
    now = int(time.time())
    dbrun("""INSERT INTO history(profile,kind,key,title,cover,source,ep,url,position,duration,
             genre,year,updated_at)
             VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
             ON CONFLICT(profile,kind,key) DO UPDATE SET
               title=COALESCE(excluded.title,history.title),
               cover=COALESCE(excluded.cover,history.cover),
               source=COALESCE(excluded.source,history.source),
               ep=COALESCE(excluded.ep,history.ep),
               url=COALESCE(excluded.url,history.url),
               position=excluded.position, duration=excluded.duration,
               genre=COALESCE(excluded.genre,history.genre),
               year=COALESCE(excluded.year,history.year),
               plays=history.plays+1, updated_at=excluded.updated_at""",
          (profile, kind, key, kw.get("title"), kw.get("cover"), kw.get("source"),
           kw.get("ep"), kw.get("url"), float(kw.get("position") or 0),
           float(kw.get("duration") or 0), kw.get("genre"), kw.get("year"), now))


def toggle_fav(kind, key, title="", cover="", payload="", profile="admin"):
    if dbrun("SELECT id FROM fav WHERE profile=? AND kind=? AND key=?",
             (profile, kind, key), fetch=True):
        dbrun("DELETE FROM fav WHERE profile=? AND kind=? AND key=?",
              (profile, kind, key))
        return False
    dbrun("""INSERT INTO fav(profile,kind,key,title,cover,payload,added_at)
             VALUES(?,?,?,?,?,?,?)""",
          (profile, kind, key, title, cover, payload, int(time.time())))
    return True


# ── 片源 ──────────────────────────────────────────────────────
def sources_list(kind=None, enabled_only=False):
    import json
    sql = "SELECT id,kind,name,url,enabled,pri,meta,type,cat,status,checked_at,api_count FROM sources"
    where, args = [], []
    if kind:
        where.append("kind=?")
        args.append(kind)
    if enabled_only:
        where.append("enabled=1")
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " ORDER BY pri, name"
    out = []
    for r in dbrun(sql, tuple(args), fetch=True) or []:
        meta = {}
        try:
            meta = json.loads(r[6] or "{}")
        except Exception:
            pass
        out.append({
            "id": r[0], "kind": r[1], "name": r[2], "url": r[3],
            "enabled": bool(r[4]), "pri": r[5], "meta": meta,
            "type": r[7] if r[7] is not None else meta.get("type", 1),
            "cat": r[8], "status": r[9], "checked_at": r[10],
            "api_count": r[11] or 0,
        })
    return out


def source_get(sid):
    for s in sources_list():
        if s["id"] == sid:
            return s
    return None


def source_upsert(kind, name, url, type_=1, pri=5, cat=None, meta=None, enabled=1):
    import json
    meta_s = json.dumps(meta or {"type": type_}, ensure_ascii=False)
    dbrun("""INSERT INTO sources(kind,name,url,enabled,pri,meta,type,cat,added_at)
             VALUES(?,?,?,?,?,?,?,?,?)
             ON CONFLICT(kind,name) DO UPDATE SET
               url=excluded.url, enabled=excluded.enabled,
               meta=excluded.meta, type=excluded.type, cat=excluded.cat""",
          (kind, name, url, enabled, pri, meta_s, type_, cat, int(time.time())))
    rows = dbrun("SELECT id FROM sources WHERE kind=? AND name=?", (kind, name), fetch=True)
    return rows[0][0] if rows else None


def source_delete(sid):
    dbrun("DELETE FROM sources WHERE id=?", (sid,))


def seed_sources():
    """首启播种：**只认本地 seed 文件**，仓库里没有任何内置源。

    文件格式与 /api/config/bundle 完全一致（由 tools/export-seed.py 生成），
    放在 data/seed.json（.gitignore 已忽略）——换个环境把这份文件带过去，
    片源、直播频道、音乐接口会一次性装回去。

    没有这个文件就什么也不写：新装环境是空壳，等使用者自己配置。
    """
    if not os.path.exists(SEED_FILE):
        return
    try:
        with open(SEED_FILE, "r", encoding="utf-8") as f:
            seed = json.load(f)
    except Exception as e:
        log("seed 读取失败 %s：%s" % (SEED_FILE, str(e)[:120]))
        return

    # 片源：只在表里没有 cms 时播种，用户删光了也不回填
    if not dbrun("SELECT COUNT(*) FROM sources WHERE kind='cms'", fetch=True)[0][0]:
        n = 0
        for s in seed.get("sources") or []:
            api = (s.get("api") or "").strip()
            if not api.startswith("http"):
                continue
            source_upsert("cms", s.get("name") or api, api,
                          type_=int(s.get("type") or 1),
                          pri=int(s.get("pri") or 5), cat=s.get("cat"))
            n += 1
        if n:
            log("已从 seed 写入 %d 个片源" % n)

    lv = seed.get("live") or {}

    # 直播源（每日汇总 & 手动刷新用）
    if not dbrun("SELECT COUNT(*) FROM sources WHERE kind='live'", fetch=True)[0][0]:
        n = 0
        for feed in lv.get("feeds") or []:
            u = (feed.get("url") or "").strip()
            if not u.startswith("http"):
                continue
            source_upsert("live", feed.get("name") or u, u, type_=0, pri=5)
            n += 1
        if n:
            log("已从 seed 写入 %d 个直播源" % n)

    # 频道表：带上就用（首启就有台可看），之后由手动/每日刷新维护
    if not dbrun("SELECT COUNT(*) FROM live", fetch=True)[0][0]:
        rows = []
        for it in lv.get("items") or []:
            grp, name = it.get("group") or "", it.get("name") or ""
            for u in it.get("urls") or []:
                if u:
                    rows.append((grp, name, u, "seed", int(time.time())))
        for r in rows:
            dbrun("INSERT OR IGNORE INTO live(grp,name,url,src,added_at) "
                  "VALUES(?,?,?,?,?)", r)
        if rows:
            log("已从 seed 写入 %d 条直播地址" % len(rows))

    # 每日汇总的候选源清单
    if lv.get("seeds") and not pref_json("live.seeds"):
        pref_set_json("live.seeds", lv["seeds"])

    # 在线音乐接口
    mu = seed.get("music") or {}
    if (mu.get("api") or "").strip() and not pref_read("music.api"):
        pref_write("music.api", mu["api"])
        pref_write("music.referer", mu.get("referer") or "")

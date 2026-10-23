# -*- coding: utf-8 -*-
"""家庭账户：单管理员 + PIN 登录。

**当前是休眠实现**：两端的登录入口已经移除（`static/app/core/auth.js` 只剩空壳，
原生端没有登录页），所以 `NO_AUTH=0` 只会让人进不去。代码先留着是为了
「哪天要恢复多账户」时不用重写 PIN 哈希与会话逻辑；真要启用必须先把 UI 补回来。
日常部署保持 `NO_AUTH=1`（默认值）。


为什么不是完整账号体系：
  * 内网自用，没有注册、找回密码、邮箱验证这些需求；
  * 只用 PIN，登录一步到位，电视上用遥控器也能输；
  * 但管理员要管配置（导入导出、片源、家人账户），所以角色只分 admin / member 两级。

PIN 用 PBKDF2 存哈希，不存明文；会话 token 落库，NAS 重启不用重新登录。
"""
import hashlib
import hmac
import os
import re
import secrets
import time

from ..db import dbrun

ITER = 120000
TOKEN_TTL = 30 * 86400          # 内网设备，一个月免登录
MAX_PROFILES = 12
# PIN 就是身份：纯 6 位数字，且全库唯一。登录时直接按 PIN 匹配账户，不选人。
PIN_RE = re.compile(r"^\d{6}$")
DEFAULT_ADMIN_NAME = "admin"
DEFAULT_ADMIN_PIN = "123456"
DEFAULT_ADMIN_ID = "admin"          # 与历史数据迁移时的 profile 值对齐
MAX_FAILS = 8                       # 连续错这么多次就歇 5 分钟
FAIL_WINDOW = 300


class AuthError(Exception):
    def __init__(self, msg, code=400):
        super().__init__(msg)
        self.msg = msg
        self.code = code


# ── PIN ───────────────────────────────────────────────
def hash_pin(pin):
    if not pin:
        return ""
    salt = os.urandom(16)
    dk = hashlib.pbkdf2_hmac("sha256", pin.encode("utf-8"), salt, ITER)
    return "pbkdf2$%d$%s$%s" % (ITER, salt.hex(), dk.hex())


def _pepper():
    """安装级 pepper：让 pin_lookup 不能拿彩虹表直接反查。"""
    row = dbrun("SELECT v FROM prefs WHERE k='auth.pepper'", fetch=True)
    if row:
        return row[0][0]
    import secrets as _s
    v = _s.token_hex(16)
    dbrun("INSERT OR REPLACE INTO prefs(k,v) VALUES('auth.pepper',?)", (v,))
    return v


def pin_lookup(pin):
    """PIN 的索引值：登录时一条 SQL 就能定位账户，也用来保证不重复。"""
    if not pin:
        return None
    return hmac.new(_pepper().encode(), pin.encode(), hashlib.sha256).hexdigest()


def verify_pin(pin, stored):
    if not stored:
        return True                  # 没设 PIN 就是免密账户
    try:
        algo, it, salt_hex, want = stored.split("$")
        if algo != "pbkdf2":
            return False
        dk = hashlib.pbkdf2_hmac("sha256", (pin or "").encode("utf-8"),
                                 bytes.fromhex(salt_hex), int(it))
        return hmac.compare_digest(dk.hex(), want)
    except Exception:
        return False


# ── 账户 ─────────────────────────────────────────────
def _row(r):
    return {"id": r[0], "name": r[1], "avatar": r[2] or "", "role": r[3],
            "hasPin": bool(r[4]), "createdAt": r[5] or 0, "lastSeen": r[6] or 0}


def list_profiles():
    rows = dbrun("""SELECT id,name,avatar,role,pin,created_at,last_seen
                    FROM profiles ORDER BY role='admin' DESC, created_at""", fetch=True) or []
    return [_row(r) for r in rows]


def get_profile(pid):
    for p in list_profiles():
        if p["id"] == pid:
            return p
    return None


def count_admin(exclude=None):
    rows = dbrun("SELECT id FROM profiles WHERE role='admin'", fetch=True) or []
    return sum(1 for r in rows if r[0] != exclude)


def initialized():
    return bool(dbrun("SELECT id FROM profiles LIMIT 1", fetch=True))


def _new_id():
    return "p" + secrets.token_hex(4)


def _clean_name(name):
    name = (name or "").strip()
    if not name:
        raise AuthError("名字不能为空")
    if len(name) > 16:
        name = name[:16]
    return name


def pin_owner(pin, exclude=None):
    """这个 PIN 已经被谁占了？用于唯一性校验与登录定位。"""
    lk = pin_lookup(pin)
    if not lk:
        return None
    row = dbrun("SELECT id,name FROM profiles WHERE pin_lookup=? AND id<>?",
                (lk, exclude or ""), fetch=True)
    return (row[0][1] if row else None)


def create_profile(name, pin, role="member", avatar="", pid=None):
    name = _clean_name(name)
    role = "admin" if role == "admin" else "member"
    if not pin or not PIN_RE.match(pin):
        raise AuthError("PIN 必须是 6 位数字")
    n = dbrun("SELECT COUNT(*) FROM profiles", fetch=True)[0][0]
    if n >= MAX_PROFILES:
        raise AuthError("最多 %d 个账户" % MAX_PROFILES)
    if dbrun("SELECT id FROM profiles WHERE name=?", (name,), fetch=True):
        raise AuthError("已经有叫「%s」的账户了" % name)
    owner = pin_owner(pin)
    if owner:
        raise AuthError("这个 PIN 已经被「%s」用了，换一个" % owner)
    pid = pid or _new_id()
    now = int(time.time())
    try:
        dbrun("""INSERT INTO profiles(id,name,avatar,role,pin,pin_lookup,created_at,last_seen)
                 VALUES(?,?,?,?,?,?,?,0)""",
              (pid, name, avatar or "", role, hash_pin(pin), pin_lookup(pin), now))
    except Exception as e:
        # 并发下唯一索引会抛 IntegrityError，别把 500 甩给用户
        if "UNIQUE" in str(e).upper():
            raise AuthError("这个 PIN 或名字已经被占用了，换一个")
        raise
    return get_profile(pid)


def update_profile(pid, name=None, pin=None, role=None, avatar=None):
    p = get_profile(pid)
    if not p:
        raise AuthError("账户不存在", 404)
    sets, args = [], []
    if name is not None:
        new_name = _clean_name(name)
        dup = dbrun("SELECT id FROM profiles WHERE name=? AND id<>?", (new_name, pid),
                    fetch=True)
        if dup:
            raise AuthError("已经有叫「%s」的账户了" % new_name)
        sets.append("name=?")
        args.append(new_name)
    if avatar is not None:
        sets.append("avatar=?")
        args.append(str(avatar)[:8])
    if role is not None:
        role = "admin" if role == "admin" else "member"
        if p["role"] == "admin" and role != "admin":
            raise AuthError("管理员账户不能降级，请直接新建一个家人账户")
        sets.append("role=?")
        args.append(role)
    if pin is not None:
        if not pin or not PIN_RE.match(pin):
            raise AuthError("PIN 必须是 6 位数字")
        owner = pin_owner(pin, exclude=pid)
        if owner:
            raise AuthError("这个 PIN 已经被「%s」用了，换一个" % owner)
        sets.append("pin=?")
        sets.append("pin_lookup=?")
        args.extend([hash_pin(pin), pin_lookup(pin)])
        # 改 PIN 必须踢掉旧会话：否则别人拿到旧 token 还能一直用
        dbrun("DELETE FROM sessions WHERE profile=?", (pid,))
        if pid == DEFAULT_ADMIN_ID:
            _set_flag("auth.default_pin", "0")
    if not sets:
        return p
    args.append(pid)
    dbrun("UPDATE profiles SET %s WHERE id=?" % ",".join(sets), tuple(args))
    return get_profile(pid)


def delete_profile(pid):
    p = get_profile(pid)
    if not p:
        raise AuthError("账户不存在", 404)
    # 管理员账户不允许删除（改角色也删不掉最后那个，这里是硬规则）
    if p["role"] == "admin":
        raise AuthError("管理员账户不能删除")
    dbrun("DELETE FROM profiles WHERE id=?", (pid,))
    dbrun("DELETE FROM history WHERE profile=?", (pid,))
    dbrun("DELETE FROM fav WHERE profile=?", (pid,))
    dbrun("DELETE FROM sessions WHERE profile=?", (pid,))
    return True



# ── 登录节流（内网也可能有好奇的邻居）────────────────
_fails = {}          # ip -> {"n": 次数, "until": 解封时间}


def _check_throttle(ip=""):
    import time as _t
    st = _fails.get(ip or "-")
    if not st:
        return
    if st["until"] and _t.time() < st["until"]:
        left = int(st["until"] - _t.time())
        raise AuthError("PIN 错太多次了，%d 秒后再试" % left, 429)
    if st["until"] and _t.time() >= st["until"]:
        _fails.pop(ip or "-", None)


def _note_fail(ip=""):
    import time as _t
    k = ip or "-"
    st = _fails.setdefault(k, {"n": 0, "until": 0})
    st["n"] += 1
    if st["n"] >= MAX_FAILS:
        st["until"] = _t.time() + FAIL_WINDOW
        st["n"] = 0
    if len(_fails) > 200:                       # 防止被刷爆内存
        now = _t.time()
        for key in [x for x, v in _fails.items() if v["until"] and v["until"] < now]:
            _fails.pop(key, None)


def _reset_fail(ip=""):
    _fails.pop(ip or "-", None)


# ── 会话 ─────────────────────────────────────────────
def issue_token(pid, ua=""):
    tok = secrets.token_hex(24)
    now = int(time.time())
    dbrun("""INSERT INTO sessions(token,profile,created_at,expires_at,ua)
             VALUES(?,?,?,?,?)""", (tok, pid, now, now + TOKEN_TTL, (ua or "")[:120]))
    dbrun("UPDATE profiles SET last_seen=? WHERE id=?", (now, pid))
    dbrun("DELETE FROM sessions WHERE expires_at<?", (now,))
    return tok


def resolve(token):
    if not token:
        return None
    rows = dbrun("""SELECT s.profile,s.expires_at,p.name,p.role,p.avatar
                    FROM sessions s JOIN profiles p ON p.id=s.profile
                    WHERE s.token=?""", (token,), fetch=True) or []
    if not rows:
        return None
    pid, exp, name, role, avatar = rows[0]
    if int(exp or 0) < int(time.time()):
        dbrun("DELETE FROM sessions WHERE token=?", (token,))
        return None
    return {"id": pid, "name": name, "role": role, "avatar": avatar or ""}


def logout(token):
    if token:
        dbrun("DELETE FROM sessions WHERE token=?", (token,))


def _set_flag(k, v):
    try:
        dbrun("INSERT OR REPLACE INTO prefs(k,v) VALUES(?,?)", (k, v))
    except Exception:
        pass


def _get_flag(k, default=""):
    row = dbrun("SELECT v FROM prefs WHERE k=?", (k,), fetch=True)
    return row[0][0] if row else default


def migrate_pins():
    """老库升级：把没有 pin_lookup 的账户补上索引。

    已存在的 PIN 无法从哈希反推，所以：
      * 默认管理员 → 重置为 123456（并标记待修改）
      * 其它账户 → 清空 PIN，等管理员在「家庭账户」里重设
    """
    rows = dbrun("""SELECT id,name FROM profiles
                    WHERE pin_lookup IS NULL OR pin_lookup=''""", fetch=True) or []
    for pid, name in rows:
        if pid == DEFAULT_ADMIN_ID:
            dbrun("UPDATE profiles SET pin=?, pin_lookup=? WHERE id=?",
                  (hash_pin(DEFAULT_ADMIN_PIN), pin_lookup(DEFAULT_ADMIN_PIN), pid))
            _set_flag("auth.default_pin", "1")
        else:
            dbrun("UPDATE profiles SET pin='', pin_lookup=NULL WHERE id=?", (pid,))
    if rows:
        dbrun("DELETE FROM sessions")
    return [r[0] for r in rows]


def ensure_default_admin():
    """首次启动直接建好 admin / adnas，省掉引导页。

    内网自用图省事；界面上会提示「还在用默认 PIN」，改过之后提示消失。
    """
    if initialized():
        return None
    try:
        p = create_profile(DEFAULT_ADMIN_NAME, DEFAULT_ADMIN_PIN,
                           role="admin", pid=DEFAULT_ADMIN_ID)
        _set_flag("auth.default_pin", "1")
        return p
    except AuthError:
        return None


def state(me=None):
    # 未登录只回答「初不初始化」，账户名单属于隐私，登录后才给
    return {"initialized": initialized(),
            "me": me,
            "profiles": list_profiles() if me else [],
            "needsPinChange": (_get_flag("auth.default_pin") == "1") if me else False}


def setup(name, pin):
    """初始化（一般走 ensure_default_admin，这里留给手动重置）。"""
    if initialized():
        raise AuthError("已经初始化过了", 409)
    p = create_profile(name or DEFAULT_ADMIN_NAME, pin, role="admin",
                       pid=DEFAULT_ADMIN_ID)
    return p, issue_token(p["id"])


def login(pin, ip=""):
    """PIN 即身份：直接按 PIN 找账户，找不到就是错。"""
    pin = (pin or "").strip()
    if not pin:
        raise AuthError("请输入 PIN")
    _check_throttle(ip)
    row = dbrun("""SELECT id,pin FROM profiles WHERE pin_lookup=?""",
                (pin_lookup(pin),), fetch=True)
    if not row or not verify_pin(pin, row[0][1]):
        _note_fail(ip)
        raise AuthError("PIN 不对", 401)
    _reset_fail(ip)
    pid = row[0][0]
    return get_profile(pid), issue_token(pid)


# ── 电视专用账户 ───────────────────────────────────────────────
TV_PIN = "999999"


def tv_profile():
    """电视端免输入用的固定账户：没有就建一个。

    为什么不直接复用管理员的 PIN：
      * 电视是全家人共用的，观看记录不该记到某个人头上；
      * 管理员 PIN 往往是家里最敏感的那个，不该固定写进电视里。

    注意：这是内网便利优先的设计，999999 是公开的。想更严格就在设置里
    改掉这个账户的 PIN，电视端会自动回退到输 PIN 的界面。
    """
    row = dbrun("SELECT * FROM profiles WHERE pin_lookup=?",
                (pin_lookup(TV_PIN),), fetch=True)
    if row:
        return _row(row[0])
    try:
        return create_profile("电视", TV_PIN, role="member")
    except AuthError:
        # PIN 被别的账户占了：不抢，让电视退回手动登录
        return None

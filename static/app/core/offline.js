// 离线缓存：把成功的 GET 响应存进 IndexedDB，NAS 不可达时拿出来用。
//
// 为什么用 IndexedDB 而不是 localStorage：
//   /api/home 一页就有几十 KB，localStorage 只有 5MB 且同步阻塞。
//
// 策略是 stale-while-revalidate 的简化版：
//   先请求网络 → 成功就更新缓存并返回
//              → 失败（断网/503）就回落到缓存，并标记离线

const DB = 'proom-offline';
const STORE = 'kv';
const VERSION = 1;

let offline = false;
let dbp = null;

function openDB() {
  if (dbp) return dbp;
  dbp = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbp;
}

function tx(mode, fn) {
  return openDB().then(db => new Promise((resolve, reject) => {
    const t = db.transaction(STORE, mode);
    const store = t.objectStore(STORE);
    let out;
    try { out = fn(store); } catch (e) { reject(e); return; }
    t.oncomplete = () => resolve(out && out.result !== undefined ? out.result : out);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  }));
}

export function save(key, data) {
  const rec = { at: Date.now(), data };
  return tx('readwrite', s => s.put(rec, key)).catch(() => {});
}

export function load(key, maxAgeMs = 7 * 86400 * 1000) {
  return tx('readonly', s => s.get(key))
    .then(rec => {
      if (!rec) return null;
      if (Date.now() - rec.at > maxAgeMs) return null;
      return rec.data;
    })
    .catch(() => null);
}

export function clear() {
  return tx('readwrite', s => s.clear()).catch(() => {});
}

export function isOffline() { return offline; }

export function setOffline(v) {
  if (offline === v) return;
  offline = v;
  window.dispatchEvent(new CustomEvent(v ? 'app:offline' : 'app:online'));
}

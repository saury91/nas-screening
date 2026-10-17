// 客户端缓存：内存 + localStorage，用来「先出内容，再静默刷新」
// 用 localStorage 而不是 sessionStorage：关掉标签页/重启浏览器后第一次进来也能秒开。
// 只存展示必需字段，避免把 eps 这类大数组塞进 5MB 配额里。
const PREFIX = 'pr.v1.';
const MAX_KEYS = 18;          // 缓存条目上限，超了按时间淘汰最旧的
const mem = new Map();

export function cacheGet(key, maxAge = 15 * 60 * 1000) {
  const hit = mem.get(key);
  if (hit) return hit.v;
  try {
    const raw = localStorage.getItem(PREFIX + key);
    if (!raw) return null;
    const o = JSON.parse(raw);
    if (!o || Date.now() - (o.t || 0) > maxAge) {
      localStorage.removeItem(PREFIX + key);
      return null;
    }
    mem.set(key, o);
    return o.v;
  } catch (e) {
    return null;
  }
}

export function cacheSet(key, value) {
  const o = { t: Date.now(), v: value };
  mem.set(key, o);
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(o));
    prune();
  } catch (e) {
    // 配额满了就只留内存副本，不影响功能
  }
}

export function cacheDrop(prefix = '') {
  for (const k of [...mem.keys()]) {
    if (k.startsWith(prefix)) mem.delete(k);
  }
  try {
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const k = localStorage.key(i) || '';
      if (k.startsWith(PREFIX + prefix)) localStorage.removeItem(k);
    }
  } catch (e) { /* ignore */ }
}

// 卡片只需要这些字段
export function slimItem(it) {
  const { eps, sources, ...rest } = it || {};
  return rest;
}

function prune() {
  try {
    const items = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i) || '';
      if (!k.startsWith(PREFIX)) continue;
      let t = 0;
      try { t = JSON.parse(localStorage.getItem(k)).t || 0; } catch (e) { t = 0; }
      items.push({ k, t });
    }
    if (items.length <= MAX_KEYS) return;
    items.sort((a, b) => a.t - b.t);
    items.slice(0, items.length - MAX_KEYS).forEach(x => localStorage.removeItem(x.k));
  } catch (e) { /* ignore */ }
}

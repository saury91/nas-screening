// 跨页面共享的一点点状态：收藏集合 + 首页缓存 + 事件总线
import { api } from './api.js';

const listeners = new Map();
export function on(evt, fn) {
  (listeners.get(evt) || listeners.set(evt, new Set()).get(evt)).add(fn);
  return () => listeners.get(evt).delete(fn);
}
export function emit(evt, data) {
  (listeners.get(evt) || []).forEach(fn => { try { fn(data); } catch (e) { console.warn(e); } });
}

export const store = {
  favs: new Set(),
  home: null,
  route: null,
};

export async function syncFavs() {
  try {
    const d = await api.library('fav');
    store.favs = new Set((d.items || []).map(x => x.key));
  } catch (e) { /* 静默：收藏不是关键路径 */ }
  return store.favs;
}

export function isFav(key) { return store.favs.has(key); }

export async function toggleFav(item) {
  const on = await api.fav({
    kind: 'online', key: item.name, title: item.name,
    cover: item.pic || '', payload: JSON.stringify({ year: item.year || '' }),
  });
  if (on.fav) store.favs.add(item.name);
  else store.favs.delete(item.name);
  emit('fav:change', { key: item.name, fav: on.fav });
  return on.fav;
}

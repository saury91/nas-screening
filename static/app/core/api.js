// 接口封装：统一错误处理，页面里只关心数据
import { getToken, needLogin } from './auth.js';
import { load as cacheLoad, save as cacheSave, setOffline, indexTitles } from './offline.js';
import * as agg from './aggregate.js';

// NAS 不可用时，用本地配置包自己去问采集站
async function tryAggregate(path) {
  try {
    const u = new URL(path, location.origin);
    if (u.pathname === '/api/search') {
      return await agg.search(u.searchParams.get('q') || '');
    }
    // 搜索页实际用的是 /api/online（在线聚合搜索），离线时同样交给客户端
    if (u.pathname === '/api/online') {
      const r = await agg.search(u.searchParams.get('kw') || '');
      if (r) r.items = r.items.map(x => ({ ...x, site_count: x.count || 1 }));
      return r;
    }
    if (u.pathname === '/api/detail') {
      return await agg.detail(u.searchParams.get('n') || '', u.searchParams.get('y') || '');
    }
    if (u.pathname === '/api/browse') {
      return await agg.listPage(Number(u.searchParams.get('pg') || 1));
    }
  } catch (e) { /* 聚合失败就当没有 */ }
  return null;
}


// 只缓存幂等的 GET；带 token 的接口也缓存，离线时靠它把界面撑起来
function cacheable(path, method) {
  return method === 'GET' && !path.startsWith('/api/proxy');
}

// 后台刷新：拿到新数据就更新缓存，并通知页面重绘一次
const refreshing = new Set();
async function revalidate(path, init) {
  if (refreshing.has(path)) return;
  refreshing.add(path);
  try {
    const res = await fetch(path, init);
    if (!res.ok) return;
    const ct = res.headers.get('Content-Type') || '';
    const data = ct.includes('json') ? await res.json() : await res.text();
    if (!data || typeof data !== 'object') return;
    const before = await cacheLoad(path);
    const next = JSON.stringify(data);
    const same = before && JSON.stringify(before) === next;
    cacheSave(path, data);
    if (Array.isArray(data.items)) indexTitles(data.items);
    // 数据真的变了才通知页面重绘，否则会白白跳一下滚动位置
    if (!same) window.dispatchEvent(new CustomEvent('app:fresh', { detail: { path } }));
  } catch (e) { /* 拉不到就算了，缓存继续用 */ }
  finally { refreshing.delete(path); setTimeout(() => refreshing.delete(path), 1000); }
}

async function request(path, { method = 'GET', body, signal } = {}) {
  const init = { method, headers: {}, signal };
  const tk = getToken();
  if (tk) init.headers['X-Token'] = tk;
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }

  // 先用缓存把界面画出来，网络在后台跑 —— 启动不再等任何请求
  if (cacheable(path, method)) {
    const cached = await cacheLoad(path);
    if (cached) {
      revalidate(path, init);
      return cached;
    }
  }

  let res;
  try {
    res = await fetch(path, init);
  } catch (e) {
    // 连不上 NAS：交给上层显示「离线模式」横幅，同时让页面用缓存数据继续渲染
    setOffline(true);
    if (cacheable(path, method)) {
      const live = await tryAggregate(path);
      if (live) return live;
      const cached = await cacheLoad(path);
      if (cached) {
        setOffline(true);
        return cached;
      }
    }
    throw new Error('暂时连不上服务器，先显示本地缓存');
  }
  setOffline(false);
  let data = null;
  const ct = res.headers.get('Content-Type') || '';
  try {
    data = ct.includes('json') ? await res.json() : await res.text();
  } catch (e) { data = null; }
  if (!res.ok) {
    // 本地服务用 503 表示"NAS 不可达"：先让客户端自己聚合，再退到缓存
    if ((res.status === 503 || res.status === 502) && cacheable(path, method)) {
      const live = await tryAggregate(path);
      if (live) { setOffline(true); return live; }
      const cached = await cacheLoad(path);
      if (cached) {
        setOffline(true);
        return cached;
      }
    }
    if (res.status === 401) needLogin();       // 会话过期 → 弹登录门
    const msg = (data && (data.msg || data.error)) || `请求失败（${res.status}）`;
    const err = new Error(msg);
    err.status = res.status;
    err.code = data && data.code;
    throw err;
  }
  if (cacheable(path, method) && data && typeof data === 'object') {
    cacheSave(path, data);
    // 顺手把片名收进本地索引：离线时详情页能画出海报和简介
    if (Array.isArray(data.items)) indexTitles(data.items);
    if (Array.isArray(data.rails)) data.rails.forEach(r => indexTitles(r && r.items));
  }
  return data;
}

export const api = {
  get: (p, q) => request(p + (q ? (p.includes('?') ? '&' : '?') + new URLSearchParams(q) : '')),
  post: (p, body) => request(p, { method: 'POST', body: body || {} }),

  status: () => request('/api/status'),
  home: () => request('/api/home'),
  recommend: fresh => request('/api/recommend' + (fresh ? '?fresh=1' : '')),
  suggest: () => request('/api/suggest'),
  browse: (q, signal) => request('/api/browse?' + new URLSearchParams(q), { signal }),
  quality: u => request('/api/quality?u=' + encodeURIComponent(u)),
  detail: (n, y, fresh) => request('/api/detail?' + new URLSearchParams({ n, y: y || '', ...(fresh ? { fresh: '1' } : {}) })),
  online: kw => request('/api/online?kw=' + encodeURIComponent(kw)),
  magnets: kw => request('/api/magnets?kw=' + encodeURIComponent(kw)),
  live: (g, kw) => request('/api/live?' + new URLSearchParams({ ...(g ? { g } : {}), ...(kw ? { kw } : {}) })),
  checkLive: g => request('/api/live/check', { method: 'POST', body: { group: g || '' } }),
  refreshLive: (check) => request('/api/live/refresh',
    { method: 'POST', body: check ? { check: true } : {} }),
  liveAuto: () => request('/api/live/auto'),
  collectLive: () => request('/api/live/collect', { method: 'POST', body: {} }),
  setLiveSeeds: (seeds) => request('/api/live/seeds', { method: 'POST', body: { seeds } }),
  setLiveAuto: (enabled) => request('/api/live/auto', { method: 'POST', body: { enabled } }),
  addLive: (name, url) => request('/api/live/add', { method: 'POST', body: { name, url } }),
  sources: kind => request('/api/sources' + (kind ? '?kind=' + kind : '')),
  importConfig: payload => request('/api/sources/import', { method: 'POST', body: payload }),
  addSource: s => request('/api/sources', { method: 'POST', body: s }),
  toggleSource: (id, enabled) => request('/api/sources/toggle', { method: 'POST', body: { id, enabled } }),
  delSource: id => request('/api/sources/delete', { method: 'POST', body: { id } }),
  priSource: (id, pri) => request('/api/sources/pri', { method: 'POST', body: { id, pri } }),
  catSource: (id, cat) => request('/api/sources/cat', { method: 'POST', body: { id, cat } }),
  testSource: id => request('/api/sources/test', { method: 'POST', body: { id } }),
  discoverSources: () => request('/api/sources/discover'),
  runDiscover: () => request('/api/sources/discover', { method: 'POST', body: {} }),
  library: kind => request('/api/library?kind=' + kind),
  configImport: o => request('/api/config/import', { method: 'POST', body: o }),
  fav: o => request('/api/fav', { method: 'POST', body: o }),
  history: o => request('/api/history', { method: 'POST', body: o }),
  tasks: () => request('/api/tasks'),
  torrent: (hash, act) => request('/api/torrent', { method: 'POST', body: { hash, act } }),
  addTorrent: (magnet, category) => request('/api/add', { method: 'POST', body: { magnet, category } }),

  musicConfig: () => request('/api/music/config'),
  musicHome: (limit) => request('/api/music/home' + (limit ? '?limit=' + limit : '')),
  musicAlbum: id => request('/api/music/album?id=' + encodeURIComponent(id)),
  musicSearch: kw => request('/api/music/search?kw=' + encodeURIComponent(kw)),
  musicOnline: kw => request('/api/music/online?kw=' + encodeURIComponent(kw)),
  musicOnlineHome: (fresh) => request('/api/music/online/home' + (fresh ? '?fresh=1' : '')),
  musicPlCats: () => request('/api/music/playlists/cats'),
  musicPlRecommend: (limit) => request('/api/music/playlists/recommend?limit=' + (limit || 14)),
  musicPlaylists: (cat, limit, fresh) => request('/api/music/playlists?cat=' + encodeURIComponent(cat)
    + '&limit=' + (limit || 24) + (fresh ? '&fresh=1' : '')),
  musicPlaylist: (id) => request('/api/music/playlist?id=' + encodeURIComponent(id)),
  musicOnlineUrl: (id, src) => request('/api/music/online/url?id=' + encodeURIComponent(id)
    + (src ? '&src=' + encodeURIComponent(src) : '')),
  musicStream: id => request('/api/music/stream?id=' + encodeURIComponent(id)),
  musicLyrics: (id, src, title, artist) => request('/api/music/lyrics?id=' + encodeURIComponent(id)
    + (src ? '&src=' + encodeURIComponent(src) : '')
    + (title ? '&title=' + encodeURIComponent(title) : '')
    + (artist ? '&artist=' + encodeURIComponent(artist) : '')),
  saveMusic: cfg => request('/api/music/config', { method: 'POST', body: cfg }),
  testMusic: cfg => request('/api/music/test', { method: 'POST', body: cfg }),
};

// 接口封装：统一错误处理，页面里只关心数据
async function request(path, { method = 'GET', body, signal } = {}) {
  const init = { method, headers: {}, signal };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(path, init);
  } catch (e) {
    throw new Error('网络断了，检查一下 NAS 是否在线');
  }
  let data = null;
  const ct = res.headers.get('Content-Type') || '';
  try {
    data = ct.includes('json') ? await res.json() : await res.text();
  } catch (e) { data = null; }
  if (!res.ok) {
    const msg = (data && (data.msg || data.error)) || `请求失败（${res.status}）`;
    const err = new Error(msg);
    err.status = res.status;
    throw err;
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
  refreshLive: () => request('/api/live/refresh', { method: 'POST', body: {} }),
  addLive: (name, url) => request('/api/live/add', { method: 'POST', body: { name, url } }),
  sources: kind => request('/api/sources' + (kind ? '?kind=' + kind : '')),
  importConfig: payload => request('/api/sources/import', { method: 'POST', body: payload }),
  addSource: s => request('/api/sources', { method: 'POST', body: s }),
  toggleSource: (id, enabled) => request('/api/sources/toggle', { method: 'POST', body: { id, enabled } }),
  delSource: id => request('/api/sources/delete', { method: 'POST', body: { id } }),
  priSource: (id, pri) => request('/api/sources/pri', { method: 'POST', body: { id, pri } }),
  catSource: (id, cat) => request('/api/sources/cat', { method: 'POST', body: { id, cat } }),
  testSource: id => request('/api/sources/test', { method: 'POST', body: { id } }),
  library: kind => request('/api/library?kind=' + kind),
  fav: o => request('/api/fav', { method: 'POST', body: o }),
  history: o => request('/api/history', { method: 'POST', body: o }),
  tasks: () => request('/api/tasks'),
  torrent: (hash, act) => request('/api/torrent', { method: 'POST', body: { hash, act } }),
  addTorrent: (magnet, category) => request('/api/add', { method: 'POST', body: { magnet, category } }),

  musicConfig: () => request('/api/music/config'),
  musicHome: () => request('/api/music/home'),
  musicAlbum: id => request('/api/music/album?id=' + encodeURIComponent(id)),
  musicSearch: kw => request('/api/music/search?kw=' + encodeURIComponent(kw)),
  musicStream: id => request('/api/music/stream?id=' + encodeURIComponent(id)),
  saveMusic: cfg => request('/api/music/config', { method: 'POST', body: cfg }),
  testMusic: cfg => request('/api/music/test', { method: 'POST', body: cfg }),
};

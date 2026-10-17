// 极简 hash 路由：一个路径 = 一个页面，浏览器前进后退、直接分享链接都成立
let handler = null;
let lastPath = '';

export function parseRoute() {
  const raw = (location.hash || '').replace(/^#/, '') || '/home';
  const [path, qs] = raw.split('?');
  const seg = path.split('/').filter(Boolean);
  const query = {};
  new URLSearchParams(qs || '').forEach((v, k) => { query[k] = v; });
  return { path: '/' + seg.join('/'), seg, query, raw };
}

export function go(path, { replace = false } = {}) {
  const target = '#' + (path.startsWith('/') ? path : '/' + path);
  if (replace) location.replace(target);
  else location.hash = target;
}

export function isActive(path) {
  return parseRoute().path === path;
}

export function onRoute(fn) { handler = fn; }

function fire() {
  const r = parseRoute();
  // 同一路径（只变了 query）也重新渲染，由页面自己决定要不要复用缓存
  lastPath = r.path;
  handler && handler(r);
}

export function startRouter() {
  window.addEventListener('hashchange', fire);
  if (!location.hash) location.replace('#/home');
  fire();
}

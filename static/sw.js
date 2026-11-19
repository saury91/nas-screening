/* 放映室 service worker
 *
 * 目标只有两个：
 *   1. 让浏览器认「可安装」（需 HTTPS；局域网明文 HTTP 下浏览器不注册 SW，
 *      此时仍可用「添加到主屏幕」，界面至少靠图标能立起来）；
 *   2. NAS 关机时，界面壳子（HTML/CSS/JS）还能打开，再由 offline.js 用
 *      IndexedDB 里的数据填补内容。
 *
 * 刻意不碰的东西：
 *   - /api/proxy：视频分片走 Range，缓存既浪费空间又会拖慢首帧；
 *   - 其它 /api/*：接口缓存由 offline.js 统一管，两处缓存会互相打架；
 *   - 非 GET 请求：写操作永远直连。
 */
const VERSION = 'v1';
const SHELL = 'pr-shell-' + VERSION;

// 只预缓存「打开界面必须有的」文件。用逐个 add 而不是 addAll：
// 任何一个文件 404 都不该让整个 SW 装不上。
const SHELL_FILES = [
  './',
  'index.html',
  'static/manifest.webmanifest',
  'static/favicon.svg',
  'static/icon-192.png',
  'static/icon-512.png',
  'static/app/main.js',
  'static/app/core/api.js',
  'static/app/core/base.js',
  'static/app/core/cache.js',
  'static/app/core/offline.js',
  'static/app/core/dom.js',
  'static/app/core/focus.js',
  'static/app/core/platform.js',
  'static/app/core/router.js',
  'static/app/core/store.js',
  'static/app/core/aggregate.js',
  'static/app/components/ui.js',
  'static/app/components/nav.js',
  'static/app/components/hero.js',
  'static/app/pages/home.js',
  'static/app/pages/browse.js',
  'static/app/pages/search.js',
  'static/app/styles/base.css',
  'static/app/styles/home.css',
  'static/app/styles/pages.css',
  'static/app/styles/fonts.css'
];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(SHELL)
      .then(c => Promise.all(SHELL_FILES.map(u =>
        c.add(new Request(u, { cache: 'reload' })).catch(() => {}))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys
        .filter(k => k.startsWith('pr-shell-') && k !== SHELL)
        .map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  // 视频/音频分片与所有接口都不插手
  if (url.pathname.startsWith('/api/')) return;
  if (req.headers.has('range')) return;

  // 页面导航：先走网络，NAS 关机时回落到缓存的 index.html
  if (req.mode === 'navigate') {
    e.respondWith((async () => {
      try {
        return await fetch(req);
      } catch (err) {
        const shell = await caches.match('index.html', { ignoreSearch: true })
          || await caches.match('./');
        return shell || Response.error();
      }
    })());
    return;
  }

  // 静态资源：先给缓存、后台顺手更新（stale-while-revalidate）
  if (url.pathname.startsWith('/static/')) {
    e.respondWith((async () => {
      const cache = await caches.open(SHELL);
      // ?v=... 只是版本号，命中时忽略查询串
      const hit = await cache.match(req, { ignoreSearch: true });
      const net = fetch(req).then(res => {
        if (res && res.ok) cache.put(req, res.clone()).catch(() => {});
        return res;
      }).catch(() => null);
      return hit || (await net) || Response.error();
    })());
  }
});

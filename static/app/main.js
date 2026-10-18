// 应用入口：装配路由、导航、焦点系统，负责页面切换
import { $, toast } from './core/dom.js';
import { api } from './core/api.js';
import { go, onRoute, parseRoute, startRouter } from './core/router.js';
import { initFocus, clearFocus, focusFirst } from './core/focus.js';
import { syncFavs, store } from './core/store.js';
import { initNav, markActive, openSearch, closeSearch, searchVisible } from './components/nav.js';
import { initMusicPlayer } from './components/musicplayer.js';
import { startReceiver } from './core/cast.js';

import { fetchState, showGate, hideGate, onAuthLost, currentUser, autoTvLogin } from './core/auth.js';

import * as home from './pages/home.js';
import * as browse from './pages/browse.js';
import * as detail from './pages/detail.js';
import * as search from './pages/search.js';
import * as live from './pages/live.js';
import * as music from './pages/music.js';
import * as settings from './pages/settings.js';

const ROUTES = {
  home, browse, detail, search, live, music, settings,
};
const PAGES = {
  '/home': 'home',
  '/browse': 'browse',
  '/title': 'detail',
  '/search': 'search',
  '/live': 'live',
  '/music': 'music',
  '/settings': 'settings',
};

const view = $('#view');
let lastKeyAt = 0;
document.addEventListener('keydown', () => { lastKeyAt = Date.now(); }, true);
let active = null;
let rendering = 0;
let currentRouteKey = '';
let flipFrom = null;                 // 卡片 → 详情 的共享元素起点
const scrollMem = new Map();         // 每条路由的滚动位置，返回时还原
const reduceMotion = !!(window.matchMedia &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches);

function playFlip(from) {
  const target = view.querySelector('.detail-poster');
  if (!target || !from || !from.width) return;
  const r = target.getBoundingClientRect();
  if (!r.width || !r.height) return;
  const dx = from.left - r.left;
  const dy = from.top - r.top;
  const sx = from.width / r.width;
  const sy = from.height / r.height;
  try {
    target.animate([
      { transform: `translate(${dx}px,${dy}px) scale(${sx},${sy})`, opacity: .55 },
      { transform: 'none', opacity: 1 },
    ], { duration: 460, easing: 'cubic-bezier(.22,1,.36,1)' });
  } catch (e) { /* 不支持 Web Animations 就跳过 */ }
}

function resolve(route) {
  if (route.path === '/' || route.path === '') return { name: 'home', module: home };
  const name = PAGES['/' + route.seg[0]];
  return name ? { name, module: ROUTES[name] } : { name: 'home', module: home };
}

async function renderPage(route) {
  const token = ++rendering;
  const { name, module } = resolve(route);
  const routeKey = route.raw || route.path;

  // 离开当前页时记下滚动位置；返回时再放回去
  if (currentRouteKey) scrollMem.set(currentRouteKey, window.scrollY);
  currentRouteKey = routeKey;

  active && active.destroy && active.destroy();
  active = null;
  clearFocus();
  document.body.dataset.hero = '0';

  // 不做「先变透明再清空」的淡出：快网速下那 100ms 空白就是用户看到的闪屏
  view.innerHTML = '';
  view.scrollTop = 0;
  window.scrollTo(0, 0);

  markActive(route.path);
  restoreScroll(scrollMem.get(routeKey) || 0);
  try {
    await module.render(view, route);
    if (token !== rendering) return;
    active = module;
    document.title = titleOf(route);
    if (name === 'detail' && flipFrom && !reduceMotion) playFlip(flipFrom);
    flipFrom = null;
    // 焦点落到新页面的第一个可交互元素（键盘切页时）
    if (Date.now() - lastKeyAt < 1800) {
      const first = view.querySelector('[data-focus]');
      if (first) focusFirst(first.parentElement || view);
    }
  } catch (e) {
    console.error(e);
    view.innerHTML = `<div class="page"><div class="empty">
      <h3>页面出错了</h3><p>${String(e && e.message || e)}</p>
      <button class="btn" onclick="location.reload()">刷新</button></div></div>`;
  }
}

// 返回列表时把滚动位置还回去。内容可能是缓存先出、实时数据后到，
// 所以不能只滚一次：高度不够时持续重试，最多 2.5 秒。
function restoreScroll(y) {
  if (!y) return;
  const t0 = performance.now();
  const tick = () => {
    window.scrollTo(0, y);
    if (Math.abs(window.scrollY - y) > 4 && performance.now() - t0 < 2500) {
      requestAnimationFrame(tick);
    }
  };
  requestAnimationFrame(tick);
}

function titleOf(route) {
  if (route.seg[0] === 'title' && route.query.n) return `${route.query.n} · 放映室`;
  if (route.seg[0] === 'browse') {
    const map = { movie: '电影', series: '剧集', anime: '动漫', variety: '综艺', doc: '纪录片' };
    return `${map[route.seg[1]] || '片库'} · 放映室`;
  }
  return '放映室 · 私人放映';
}

// 应用级委托：任何带 data-open 的元素都当作链接
document.addEventListener('click', e => {
  const t = e.target.closest('[data-open]');
  if (!t) return;
  e.preventDefault();
  // 记下卡片位置，详情页渲染完从这儿"长"出来
  const card = t.closest('.card') || t;
  const r = card.getBoundingClientRect();
  if (r.width > 40 && r.height > 40) {
    flipFrom = { left: r.left, top: r.top, width: r.width, height: r.height };
  }
  // 浮层里点卡片：先把浮层收掉，否则新页面会被锁在背后
  if (searchVisible()) closeSearch();
  go(String(t.dataset.open).replace(/^#/, ''));
});

// Esc 兜底：没有浮层/弹窗吃掉时，返回上一页
document.addEventListener('app:escape', e => {
  setTimeout(() => {
    if (e.detail && e.detail.handled) return;
    if ($('#modal-root').children.length) return;
    if (!location.hash || location.hash === '#/home') return;
    // 直接打开的深链接没有上一页，别把用户顶出站点
    if (history.length > 1) history.back();
    else go('/home');
  }, 0);
});

// 播放结束后刷新首页的继续观看
window.addEventListener('player:closed', () => {
  if (parseRoute().path === '/home') {
    const page = active;
    if (page && page.render) page.render(view, parseRoute());
  }
});

/** NAS 连不上：页面本身是本地来的，所以这里只说明情况并给重试 */
function showOfflineGate() {
  const box = document.querySelector('#auth-gate');
  if (!box) return;
  box.hidden = false;
  document.body.classList.add('gated');
  box.innerHTML = `<div class="gate-card gate-solo">
    <div class="gate-brand">
      <div class="gate-logo">${'▶'}</div>
      <div>
        <h2>连不上 NAS</h2>
        <p class="hint">放映室的服务没有响应。检查 NAS 是否开机、网络是否正常。</p>
      </div>
    </div>
    <button class="btn primary" style="margin-top:18px" onclick="location.reload()">重新连接</button>
  </div>`;
}

async function boot() {
  // 离线提示：只弹一条 toast，几秒后自动消失。
  // 之前做成顶部横幅，正好压住导航条，想点"电影/剧集"都点不到。
  let lastOfflineToast = 0;
  const showOffline = () => {
    const now = Date.now();
    if (now - lastOfflineToast < 20000) return;   // 20 秒内不重复弹
    lastOfflineToast = now;
    toast('离线模式 · 显示缓存内容');
  };
  window.addEventListener('app:offline', showOffline);

  // 内网也要个门：没登录就不装配页面，避免满屏 401
  try {
    const st = await fetchState();
    // 本地服务告诉我们 NAS 不可达。
    // 有缓存的登录态就继续渲染（界面能用），否则给明确的离线页
    if (st && st.offline) {
      if (st.me) {
        hideGate();
        showOffline();
        // 离线时别恢复到上次的详情页——那页的数据多半没缓存，会显示"没找到"
        if (location.hash && !location.hash.startsWith('#/home')) location.hash = '#/home';
      } else {
        showOfflineGate();
        return;
      }
    }
    if (!st.initialized || !st.me) {
      // 电视端：先用固定账户自动登录，省得每次开机都要按遥控器输 PIN
      const isTv = /ProomTV\//.test(navigator.userAgent) ||
        document.documentElement.classList.contains('tv');
      if (isTv && st.initialized && await autoTvLogin()) {
        location.reload();
        return;
      }
      showGate();
      return;
    }
    hideGate();
  } catch (e) {
    showOfflineGate();
    return;
  }
  onAuthLost(() => showGate());
  initNav();
  // 本地有 token 但 Cookie 可能丢了（WebView 重启/清缓存）：
  // 让服务端补发一次，视频分片和封面图才带得上认证
  api.post('/api/auth/touch', {}).catch(() => {});
  // 遥控器菜单键：播放器开着就切「选集/线路」面板，否则打开地址设置
  window.addEventListener('app:menu', () => {
    if (window.__proomMenu && window.__proomMenu()) return;
    try { window.ProomNative?.openSetup?.(); } catch (e) {}
  });

  // 电视端把自己注册成投屏接收端；收到指令就用同一套播放器播
  // NAS 中转与局域网直连共用：拿到 {item, si, ei, position} 就起播
  const playCast = (cmd) => {
    import('/static/app/components/player.js').then(({ openPlayer }) => {
      const item = cmd.item && cmd.item.sources && cmd.item.sources.length
        ? cmd.item
        : { name: cmd.title || '投屏播放',
            sources: [{ site: '投屏', count: 1, eps: [{ name: '正片', url: cmd.url }] }] };
      openPlayer(item, {
        si: cmd.si || 0,
        ei: cmd.ei || 0,
        position: Math.floor(cmd.position || 0),
        isLive: !!cmd.isLive,
      });
    }).catch(() => {});
  };

  // 局域网直连（手机 → 电视 App，不经过 NAS）
  window.__proomCastPlay = (json) => {
    try { playCast(JSON.parse(json)); } catch (e) { /* 忽略坏包 */ }
  };

  startReceiver(playCast);
  initFocus();
  initMusicPlayer();
  onRoute(renderPage);
  startRouter();
  syncFavs();
  // 提前把搜索热词拿好，按 / 时零等待
  fetch('/api/suggest').catch(() => {});
  // 键盘任意方向键后，给个可见的焦点提示
  document.addEventListener('keydown', e => {
    if (e.key.startsWith('Arrow') && !document.querySelector('.focused')) {
      const first = view.querySelector('[data-focus]');
      if (first) {
        // 交给焦点系统；这里只在首个元素上补一个初始落点
        first.dispatchEvent(new PointerEvent('pointerover', { bubbles: true }));
      }
    }
  });
}

boot();

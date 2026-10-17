// 应用入口：装配路由、导航、焦点系统，负责页面切换
import { $ } from './core/dom.js';
import { go, onRoute, parseRoute, startRouter } from './core/router.js';
import { initFocus, clearFocus, focusFirst } from './core/focus.js';
import { syncFavs, store } from './core/store.js';
import { initNav, markActive, openSearch, closeSearch, searchVisible } from './components/nav.js';
import { initMusicPlayer } from './components/musicplayer.js';

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

function resolve(route) {
  if (route.path === '/' || route.path === '') return { name: 'home', module: home };
  const name = PAGES['/' + route.seg[0]];
  return name ? { name, module: ROUTES[name] } : { name: 'home', module: home };
}

async function renderPage(route) {
  const token = ++rendering;
  const { name, module } = resolve(route);

  active && active.destroy && active.destroy();
  active = null;
  clearFocus();
  document.body.dataset.hero = '0';

  // 不做「先变透明再清空」的淡出：快网速下那 100ms 空白就是用户看到的闪屏
  view.innerHTML = '';
  view.scrollTop = 0;
  window.scrollTo(0, 0);

  markActive(route.path);
  try {
    await module.render(view, route);
    if (token !== rendering) return;
    active = module;
    document.title = titleOf(route);
    // 用键盘切页时，焦点直接落到新页面的第一个可交互元素
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
  // 浮层里点卡片：先把浮层收掉，否则新页面会被锁在背后
  if (searchVisible()) closeSearch();
  go(String(t.dataset.open).replace(/^#/, ''));
});

// Esc 兜底：没有浮层/弹窗吃掉时，返回上一页
document.addEventListener('app:escape', e => {
  setTimeout(() => {
    if (e.detail && e.detail.handled) return;
    if ($('#modal-root').children.length) return;
    if (location.hash && location.hash !== '#/home') history.back();
  }, 0);
});

// 播放结束后刷新首页的继续观看
window.addEventListener('player:closed', () => {
  if (parseRoute().path === '/home') {
    const page = active;
    if (page && page.render) page.render(view, parseRoute());
  }
});

function boot() {
  initNav();
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

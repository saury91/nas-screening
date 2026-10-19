// 顶栏：标签导航 + 滚动收起 + 全局搜索浮层
import { $, $$, esc, icon, debounce, toast } from '../core/dom.js';
import { isApp } from '../core/platform.js';
import { api } from '../core/api.js';
import { go, parseRoute } from '../core/router.js';
import { setFocus, clearFocus } from '../core/focus.js';
import { titleLink } from './ui.js';

const TABS = [
  ['/home', '首页'],
  ['/browse/movie', '电影'],
  ['/browse/series', '剧集'],
  ['/browse/anime', '动漫'],
  ['/browse/variety', '综艺'],
  ['/browse/doc', '纪录片'],
  ['/live', '直播'],
  ['/music', '音乐'],
];

let searchOpen = false;
let lastScroll = 0;


export function initNav() {
  const tabs = $('#tabs');
  tabs.innerHTML = TABS.map(([href, label]) =>
    `<a href="#${href}" data-focus data-tab="${href}">${label}</a>`).join('');

  tabs.addEventListener('click', e => {
    const a = e.target.closest('a');
    if (!a) return;
    // hash 变化由 router 接管；这里只做滚动复位
    window.scrollTo(0, 0);
  });

  // 不能直接把 openSearch 当监听器：它会把 click 事件当 prefill，
// 于是 input.value 变成 "[object PointerEvent]"，还会被记进搜索历史
// App 是消费端：管理入口整体移除（片源/直播源/下载/备份都在 PC 上做）
if (isApp()) $$('[data-admin-only]').forEach(el => el.remove());

$('#btn-search')?.addEventListener('click', () => openSearch());
  $('#search-close')?.addEventListener('click', closeSearch);
  $('#searchlayer')?.addEventListener('click', e => {
    if (e.target.id === 'searchlayer') closeSearch();
  });
  // 焦点陷阱：Tab 在浮层里循环，不会跑到背后的页面上
  $('#searchlayer')?.addEventListener('keydown', e => {
    if (e.key !== 'Tab' || !searchOpen) return;
    const f = Array.from($('#searchlayer').querySelectorAll(
      'a[href],button,input,[data-focus]')).filter(el => el.offsetParent !== null);
    if (!f.length) return;
    const first = f[0], last = f[f.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault(); last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault(); first.focus();
    }
  });

  const input = $('#search-input');
  // 只在这里 debounce 一次；以前外层 380ms 套内层 480ms，实际要等 860ms 才发请求
  input.addEventListener('input', debounce(() => runSearch(input.value.trim()), 300));
  input.addEventListener('keydown', e => {
    if (e.key === 'Enter' && input.value.trim()) {
      go('/search?q=' + encodeURIComponent(input.value.trim()));
      closeSearch();
    }
    if (e.key === 'ArrowDown') {
      const first = $('#search-body [data-focus]');
      if (first) { e.preventDefault(); setFocus(first); }
    }
  });

  document.addEventListener('app:escape', e => {
    if (!searchOpen || (e.detail && e.detail.handled)) return;
    closeSearch();
    if (e.detail) e.detail.handled = true;
  });

  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
}

export function markActive(path) {
  $$('#tabs a').forEach(a => {
    const tab = a.dataset.tab;
    const on = path === tab || (tab === '/home' && path === '/');
    a.classList.toggle('on', on);
  });
  // 路由切换后重算一次：否则首屏会因为「还没有 active 标签」被误判成 solid
  onScroll();
}

function onScroll() {
  const bar = $('#topbar');
  const y = window.scrollY;
  // 只在滚动后加实底：首屏压在 Hero 上时保持通透，别把画面遮住
  bar.classList.toggle('solid', y > 24);
  if (y > 240 && y > lastScroll + 8) bar.classList.add('hide');
  else if (y < lastScroll - 6 || y < 120) bar.classList.remove('hide');
  lastScroll = y;
}

// ── 搜索 ────────────────────────────────────────────────────
export async function openSearch(prefill = '') {
  if (typeof prefill !== 'string') prefill = '';
  const layer = $('#searchlayer');
  layer.hidden = false;
  document.body.classList.add('locked');
  searchOpen = true;
  const input = $('#search-input');
  input.value = prefill;
  setTimeout(() => input.focus(), 60);

  const body = $('#search-body');
  body.innerHTML = `<div class="skl" style="height:120px;border-radius:14px"></div>`;
  try {
    const d = await api.suggest();
    const chips = (arr, cls) => arr.map(k =>
      `<button data-q="${esc(k)}" data-focus>${esc(k)}</button>`).join('');
    body.innerHTML = `
      ${d.recent && d.recent.length ? `<div class="search-section"><h5>最近搜索</h5>
        <div class="search-chips">${chips(d.recent)}</div></div>` : ''}
      ${d.hot && d.hot.length ? `<div class="search-section"><h5>大家在找</h5>
        <div class="search-chips">${chips(d.hot)}</div></div>` : ''}
      <p class="search-empty">输入片名后回车看全部结果，↑↓ 可以移动焦点。</p>`;
  } catch (e) {
    body.innerHTML = `<p class="search-empty">${esc(e.message)}</p>`;
  }

  if (prefill) runSearch(prefill);
}

export function closeSearch() {
  if (!searchOpen) return;
  const layer = $('#searchlayer');
  const hadFocus = layer.contains(document.activeElement);
  layer.hidden = true;
  document.body.classList.remove('locked');
  searchOpen = false;
  clearFocus();
  // 关闭后把焦点还给触发按钮，键盘用户不会丢失位置
  if (hadFocus) $('#btn-search')?.focus();
}

export function searchVisible() { return searchOpen; }

// 浮层内点击热门词 / 结果
document.addEventListener('click', e => {
  const q = e.target.closest('#searchlayer [data-q]');
  if (q) {
    const v = q.dataset.q;
    const input = $('#search-input');
    input.value = v;
    runSearch(v);
    input.focus();
  }
});

const runSearch = async kw => {
  const body = $('#search-body');
  if (!kw || kw.length < 1) return;
  body.innerHTML = `<div class="skl" style="height:200px;border-radius:14px"></div>`;
  try {
    const d = await api.online(kw);
    const items = (d.items || []).slice(0, 12);
    if (!items.length) {
      body.innerHTML = `<p class="search-empty">没有找到「${esc(kw)}」。换个片名试试，或去片源管理看看源是不是挂了。</p>`;
      return;
    }
    body.innerHTML = `<div class="search-section"><h5>搜索结果 · ${items.length}</h5>
      <div class="search-results">${items.map(it => `
        <a class="sr-card" href="${titleLink(it.name, it.year)}" data-focus
           data-open="${titleLink(it.name, it.year)}" aria-label="${esc(it.name)}">
          ${it.pic ? `<img src="${esc(it.pic)}" alt="" loading="lazy" decoding="async" onerror="this.remove()">`
                   : `<div class="ph">${esc(it.name.slice(0, 1))}</div>`}
          <h6>${esc(it.name)}</h6>
          <p>${esc([it.year, it.site_count + ' 源'].filter(Boolean).join(' · '))}</p>
        </a>`).join('')}</div></div>
      <p class="search-empty">回车查看完整结果页</p>`;
    $('#search-body [data-focus]')?.classList.remove('focused');
  } catch (e) {
    body.innerHTML = `<p class="search-empty">${esc(e.message)}</p>`;
  }
};

// 全局快捷键
document.addEventListener('keydown', e => {
  if (e.key === '/' && !searchOpen && !/INPUT|TEXTAREA/.test(document.activeElement.tagName)) {
    e.preventDefault();
    openSearch();
  }
});

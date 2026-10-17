// 分类浏览：年份/地区/片源筛选 + 无限滚动加载
import { api } from '../core/api.js';
import { esc, icon } from '../core/dom.js';
import { posterCard, emptyState } from '../components/ui.js';
import { cacheGet, cacheSet, slimItem } from '../core/cache.js';

const SLUG = { movie: '电影', series: '剧集', anime: '动漫', variety: '综艺', doc: '纪录片' };
const AREAS = ['内地', '中国香港', '中国台湾', '美国', '日本', '韩国', '英国'];
const DESC = {
  movie: '院线与网络电影，按更新顺序',
  series: '连续剧、短剧与海外剧集',
  anime: '番剧、剧场版与国漫',
  variety: '综艺、真人秀与访谈',
  doc: '纪录片与人文自然',
};

let st = null;
let io = null;
let phNodes = [];          // 正在加载的骨架占位，跟着网格流走
const B_TTL = 10 * 60 * 1000;

const bkey = () => `browse|${st.cat}|${st.year}|${st.area}|${st.src}`;
const sigOf = items => [items.length, items[0] && items[0].name,
                        items[items.length - 1] && items[items.length - 1].name].join('|');

function hydrate(cached) {
  st.items = cached.items || [];
  st.seen = new Set(st.items.map(itemKey));
  st.pg = cached.pg || 2;
  st.more = !!cached.more;
  st.sig = sigOf(st.items);
}

export function destroy() {
  io && io.disconnect();
  io = null;
  st && st.ctrl && st.ctrl.abort();
  st = null;
  phNodes = [];
}

// 骨架卡直接插进真实网格：会从最后一个卡片的下一个格子开始，不会另起一行
function addSkeletons(root, n = 8) {
  removeSkeletons();
  const box = root.querySelector('#list .grid');
  if (!box) return;
  for (let i = 0; i < n; i++) {
    const wrap = document.createElement('div');
    wrap.className = 'card-wrap load-ph-wrap';
    wrap.innerHTML = `<div class="skl load-ph"></div>
      <div class="skl skl-line" style="width:74%"></div>
      <div class="skl skl-line" style="width:46%"></div>`;
    box.appendChild(wrap);
    phNodes.push(wrap);
  }
}

function removeSkeletons() {
  phNodes.forEach(n => n.remove());
  phNodes = [];
}

// 跨站同名不同写法（空格、全半角、尾部年份）归一到同一个 key，避免重复卡片
function normName(s) {
  return String(s || '').toLowerCase()
    .replace(/[\s·:：\-—_·]+/g, '')
    .replace(/[（(【\[].*?[）)】\]]/g, '')
    .replace(/(19|20)\d{2}$/, '');
}
const itemKey = x => normName(x.name) + '|' + String(x.year || '');

export async function render(root, ctx) {
  const slug = ctx.seg[1] || 'movie';
  const cat = SLUG[slug] || '电影';
  const years = Array.from({ length: 8 }, (_, i) => String(new Date().getFullYear() - i));
  st = { cat, slug, year: '', area: '', src: '', pg: 1, items: [], seen: new Set(),
         more: false, loading: false, ctrl: null, sig: '' };

  root.innerHTML = `<div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">片库</div>
        <h1 class="page-title">${esc(cat)}</h1>
      </div>
      <p class="page-sub">${esc(DESC[slug] || '')}</p>
    </div>
    <div class="filterbar" id="filters">
      <div class="filter-row"><span class="filter-label">年份</span>
        <div class="chips" data-group="year">
          <button class="chip on" data-value="" data-focus>全部</button>
          ${years.map(y => `<button class="chip" data-value="${y}" data-focus>${y}</button>`).join('')}
        </div>
      </div>
      <div class="filter-row"><span class="filter-label">地区</span>
        <div class="chips" data-group="area">
          <button class="chip on" data-value="" data-focus>全部</button>
          ${AREAS.map(a => `<button class="chip" data-value="${a}" data-focus>${esc(a)}</button>`).join('')}
        </div>
        <div class="source-select">
          <span class="filter-label" style="width:auto">片源</span>
          <select class="input" id="src-filter"><option value="">全部片源</option></select>
        </div>
      </div>
    </div>
    <p class="result-hint" id="hint">正在向片源要数据…</p>
    <div id="list"></div>
    <div id="load-slot"></div>
    <div id="sentinel" style="height:1px"></div>
  </div>`;

  // 上次看过的同一分类，先直接铺出来，再静默更新
  const cached = cacheGet(bkey(), B_TTL);
  if (cached && cached.items && cached.items.length) {
    hydrate(cached);
    paintList(root);
    root.querySelector('#hint').textContent = `已载入 ${st.items.length} 部 · 正在更新…`;
  }

  await loadSources(root);
  bind(root);
  watchScroll(root);
  if (cached && cached.items && cached.items.length) {
    await fetchPage(root, { replace: true, silent: true });
  } else {
    await fetchPage(root, { replace: true, first: true });
  }
}

async function loadSources(root) {
  try {
    const d = await api.sources('cms');
    const sel = root.querySelector('#src-filter');
    (d.items || []).forEach(s => {
      const o = document.createElement('option');
      o.value = s.name; o.textContent = s.name;
      sel.appendChild(o);
    });
    sel.addEventListener('change', async () => {
      st.src = sel.value;
      resetFilters(root);
    });
  } catch (e) { /* 片源列表拿不到不影响浏览 */ }
}

function bind(root) {
  root.querySelector('#filters').addEventListener('click', e => {
    const b = e.target.closest('.chips .chip');
    if (!b) return;
    const group = b.closest('[data-group]')?.dataset.group;
    if (!group) return;
    st[group] = b.dataset.value;
    b.parentElement.querySelectorAll('.chip').forEach(c => c.classList.toggle('on', c === b));
    resetFilters(root);
  });

  // 兜底：不支持 IntersectionObserver 时仍可点按钮加载
  root.querySelector('#load-slot').addEventListener('click', e => {
    if (e.target.closest('#more')) fetchPage(root, { replace: false });
  });
}

async function resetFilters(root) {
  // 上一次请求可能还在飞：取消掉，否则新的筛选条件会被 loading 守卫吞掉
  if (st.ctrl) st.ctrl.abort();
  st.loading = false;
  st.pg = 1;
  st.seen = new Set();
  st.more = false;
  st.sig = '';
  root.querySelector('#load-slot').innerHTML = '';
  removeSkeletons();

  const cached = cacheGet(bkey(), B_TTL);
  if (cached && cached.items && cached.items.length) {
    hydrate(cached);
    paintList(root);
    root.querySelector('#hint').innerHTML = `<span class="spin-sm"></span> 正在更新…`;
    await fetchPage(root, { replace: true, silent: true });
  } else {
    await fetchPage(root, { replace: true });
  }
}

function watchScroll(root) {
  io && io.disconnect();
  const sentinel = root.querySelector('#sentinel');
  if (!sentinel || !('IntersectionObserver' in window)) {
    showFallbackButton(root);
    return;
  }
  io = new IntersectionObserver(entries => {
    if (!st || st.loading || !st.more) return;
    if (entries.some(en => en.isIntersecting)) fetchPage(root, { replace: false });
  }, { rootMargin: '600px 0px' });
  io.observe(sentinel);
}

function showFallbackButton(root) {
  if (!st.more) return;
  root.querySelector('#load-slot').innerHTML =
    `<div class="load-more"><button class="btn" id="more" data-focus>${icon('plus')}加载更多</button></div>`;
}

async function fetchPage(root, { replace = false, first = false, silent = false } = {}) {
  if (st.loading) return;
  st.loading = true;
  const ctrl = new AbortController();
  st.ctrl = ctrl;
  const list = root.querySelector('#list');
  const slot = root.querySelector('#load-slot');
  const hint = root.querySelector('#hint');

  if (first) {
    list.innerHTML = `<div class="grid">${Array(12).fill(
      '<div class="skl skl-card" style="width:100%"></div>').join('')}</div>`;
  } else if (replace) {
    if (silent) {
      hint.innerHTML = '<span class="spin-sm"></span> 正在更新…';
    } else {
      // 没有缓存可用：保留旧内容，只压暗 + 顶部进度，避免整页闪一下
      list.classList.add('is-busy');
      hint.innerHTML = '<span class="spin-sm"></span> 正在筛选…';
    }
  } else {
    addSkeletons(root, 8);
    slot.innerHTML = `<p class="load-status"><span class="spin-sm"></span>正在加载下一页…</p>`;
  }

  try {
    const d = await api.browse({ cat: st.cat, year: st.year, area: st.area,
                                 pg: st.pg, src: st.src || '' }, ctrl.signal);
    const items = d.items || [];
    const oldSig = st.sig;
    if (replace) { st.items = []; st.seen = new Set(); }
    const added = [];
    items.forEach(x => {
      const k = itemKey(x);
      if (!st.seen.has(k)) { st.seen.add(k); st.items.push(x); added.push(x); }
    });
    st.more = !!d.more;
    st.pg += 1;
    st.sig = sigOf(st.items);
    removeSkeletons();
    // 追加时只插新卡片，不整块重建：否则已加载的封面会一起闪一下
    if (replace && silent && oldSig === st.sig) {
      // 缓存和实时数据一致，不重绘
    } else if (replace || first) {
      paintList(root);
    } else {
      appendCards(root, added);
    }
    // 只缓存第一页，回访时秒出；后续翻页不落缓存，避免越滚越大
    if (replace) {
      cacheSet(bkey(), { items: st.items.map(slimItem), pg: st.pg, more: st.more });
    }
    slot.innerHTML = st.more ? '' : (st.items.length > 12
      ? `<p class="load-end">已经到底了 · 共 ${st.items.length} 部</p>` : '');
    if (!('IntersectionObserver' in window)) showFallbackButton(root);
    hint.textContent = st.items.length
      ? `已载入 ${st.items.length} 部 · ${d.sites || 0} 个片源参与${st.src ? ' · 只看 ' + st.src : ''}`
      : '这个筛选条件下暂时没货，换个年份或地区试试';
  } catch (e) {
    removeSkeletons();
    if (ctrl.signal.aborted) return;          // 主动取消，不弹错误
    if (!st.items.length) {
      list.innerHTML = emptyState({
        iconName: 'wifi', title: '加载失败', desc: e.message,
        action: '<button class="btn" onclick="location.reload()">重试</button>',
      });
      hint.textContent = '';
    } else if (!replace) {
      slot.innerHTML = `<div class="load-more">
        <span class="load-end">加载失败：${esc(e.message)}</span>
        <button class="btn sm" id="more" data-focus>重试</button></div>`;
    }
  } finally {
    removeSkeletons();
    if (st.ctrl === ctrl) {
      st.loading = false;
      list.classList.remove('is-busy');
    }
  }
}

function cardHTML(x) {
  return posterCard(x, { sub: [x.year, x.type, x.site].filter(Boolean).join(' · ') });
}

function appendCards(root, added) {
  if (!added.length) return;
  const box = root.querySelector('#list .grid');
  if (!box) { paintList(root); return; }
  box.insertAdjacentHTML('beforeend', added.map(cardHTML).join(''));
}

function paintList(root) {
  const box = root.querySelector('#list');
  if (!st.items.length) {
    box.innerHTML = emptyState({
      iconName: 'film', title: '没有匹配的片子',
      desc: '片源的分类 ID 各不相同，偶尔会有源漏掉。换个条件，或直接搜片名更准。',
    });
    return;
  }
  box.innerHTML = `<div class="grid">${st.items.map(cardHTML).join('')}</div>`;
}

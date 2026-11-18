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
let rootRef = null;        // 当前页面的根节点，滚动监听只认它
let scrollTimer = null;
// 与后端片库缓存对齐（半小时）：列表本来就更新很慢，10 分钟就过期等于
// 每次回访都要重新聚合一遍
const B_TTL = 30 * 60 * 1000;
let prefetching = false;

const bkey = () => `browse|${st.cat}|${st.year}|${st.area}|${st.src}`;
const sigOf = items => [items.length, items[0] && items[0].name,
                        items[items.length - 1] && items[items.length - 1].name].join('|');

function hydrate(cached) {
  st.items = cached.items || [];
  st.seen = new Set(st.items.map(itemKey));
  // 缓存只负责"先画出来"。页码必须从 1 重新要——
  // 以前把 st.pg 恢复成 2，回访时会直接拉第 2 页，第一页永远被跳过。
  st.pg = 1;
  st.more = !!cached.more;
  st.sig = sigOf(st.items);
}

export function destroy() {
  io && io.disconnect();
  io = null;
  st && st.ctrl && st.ctrl.abort();
  st = null;
  phNodes = [];
  rootRef = null;
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
const itemKey = x => normName(x.name) + '|' + String(x.year || '').trim();

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

  // 片源下拉不挡首屏：晚几十毫秒出来没人看得出来，挡着就是白等
  loadSources(root);
  bind(root);
  watchScroll(root);
  if (cached && cached.items && cached.items.length) {
    await fetchPage(root, { replace: true, silent: true });
  } else {
    await fetchPage(root, { replace: true, first: true });
  }
  prefetchOthers();
}

// 切标签要等一次聚合，很慢。用户在这一页看片的时候，空闲时间把其它几个
// 分类的第一页顺手取回来：等真点过去，缓存已经在 IndexedDB 里，直接出画面。
// 串行 + 空闲调度：不跟当前页抢带宽，也不让后端同时算五个分类。
function prefetchOthers() {
  if (prefetching) return;
  const mine = st && st.cat;
  const cats = Object.values(SLUG).filter(c => c !== mine);
  if (!cats.length) return;
  prefetching = true;
  const idle = window.requestIdleCallback || (fn => setTimeout(fn, 600));
  const mySt = st;
  const step = i => {
    // 页面切走了就别再取：白耗流量，还会把旧页面的状态写回来
    if (!st || st !== mySt || i >= cats.length) { prefetching = false; return; }
    if (navigator.onLine === false) { prefetching = false; return; }
    api.browse({ cat: cats[i], year: '', area: '', pg: 1, src: '' })
      .catch(() => {})
      .finally(() => idle(() => step(i + 1)));
  };
  idle(() => step(0));
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
  root.querySelector('#filters')?.addEventListener('click', e => {
    const b = e.target.closest('.chips .chip');
    if (!b) return;
    const group = b.closest('[data-group]')?.dataset.group;
    if (!group) return;
    st[group] = b.dataset.value;
    b.parentElement.querySelectorAll('.chip').forEach(c => c.classList.toggle('on', c === b));
    resetFilters(root);
  });

  // 兜底：不支持 IntersectionObserver 时仍可点按钮加载
  root.querySelector('#load-slot')?.addEventListener('click', e => {
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

  // 先在本地筛一遍：手上这 60 条里就有符合的，立刻铺出来给个反应，
  // 精确结果（其它页、其它源）随后静默替换。
  // 不然点个「2024」也要等一次全站聚合，用户只会觉得"点了没反应"。
  if (st.items.length && (st.year || st.area)) {
    const local = st.items.filter(x =>
      (!st.year || String(x.year || '').startsWith(st.year)) &&
      (!st.area || String(x.area || '').includes(st.area)));
    if (local.length) {
      st.items = local;
      paintList(root);
      root.querySelector('#hint').innerHTML = '<span class="spin-sm"></span> 正在筛选…';
      await fetchPage(root, { replace: true, silent: true });
      return;
    }
  }

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

// 滚动加载：不用 IntersectionObserver。
// 那个方案在「sentinel 一直留在视口里」时只会触发一次回调，
// 筛选后结果很短就再也不加载了；改成滚动 + 每次请求结束主动检查一次，
// 行为是确定的：只要底部进入视野、还有下一页，就继续要。
function maybeLoad() {
  const root = rootRef;
  if (!st || !root || !root.isConnected || st.loading || !st.more) return;
  const sentinel = root.querySelector('#sentinel');
  if (!sentinel) return;
  if (sentinel.getBoundingClientRect().top < window.innerHeight + 400) {
    fetchPage(root, { replace: false });
  }
}

function onScroll() {
  if (scrollTimer) return;
  scrollTimer = setTimeout(() => {
    scrollTimer = null;
    maybeLoad();
  }, 140);
}

function watchScroll(root) {
  rootRef = root;
  if (!watchScroll.bound) {
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll, { passive: true });
    watchScroll.bound = true;
  }
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
  // 页面可能已经被切走（异步返回时 DOM 已换），这时什么都别写
  if (!list || !slot || !hint) { st.loading = false; return; }

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
    // 这一页没有任何新条目（接口没货，或全是前面已经见过的）：
    // 继续往后翻只会拿到同一批，直接收手，避免无限重复。
    if (!items.length || (!replace && !added.length)) st.more = false;
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
      cacheSet(bkey(), { items: st.items.map(slimItem), pg: 1, more: st.more });
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
      // 如果底部还在视野里（内容还不够高），接着加载下一页
      setTimeout(maybeLoad, 160);
    }
  }
}

function cardHTML(x) {
  return posterCard(x, { sub: [x.year, x.type, x.site].filter(Boolean).join(' · '),
                         key: itemKey(x) });
}

function appendCards(root, added) {
  if (!added.length) return;
  const box = root.querySelector('#list .grid');
  if (!box) { paintList(root); return; }
  // 最后一道闸：DOM 里已经有这个 key 就不插。
  // 状态层的 st.seen 只要漏一条（比如接口两次返回的写法不一致），
  // 这里也不会出现两张一样的卡。
  const have = new Set([...box.querySelectorAll('.card-wrap')]
    .map(n => n.dataset.key).filter(Boolean));
  const html = added.filter(x => !have.has(itemKey(x))).map(cardHTML).join('');
  if (html) box.insertAdjacentHTML('beforeend', html);
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
  // 渲染前再按 key 收一遍：即使前面的去重有漏，也不会画出重复卡片
  const seen = new Set();
  st.items = st.items.filter(x => {
    const k = itemKey(x);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  box.innerHTML = `<div class="grid">${st.items.map(cardHTML).join('')}</div>`;
}

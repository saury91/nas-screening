// 首页：Hero 轮播 + 继续观看 + 分类货架 + 快捷入口
import { api } from '../core/api.js';
import { esc, icon } from '../core/dom.js';
import { store } from '../core/store.js';
import { cacheGet, cacheSet, slimItem } from '../core/cache.js';
import { heroHTML, mountHero } from '../components/hero.js';
import { shelf, posterCard, continueCard, emptyState } from '../components/ui.js';

let heroCleanup = null;
let pollTimer = null;

const HKEY = 'home';
const HOME_TTL = 15 * 60 * 1000;

function slimHome(d) {
  if (!d || !d.rec) return d;
  const rec = d.rec;
  return {
    ...d,
    rec: {
      ...rec,
      cats: (rec.cats || []).map(c => ({ ...c, items: (c.items || []).map(slimItem) })),
      latest: (rec.latest || []).map(slimItem),
      liked: (rec.liked || []).map(slimItem),
    },
  };
}

/* 用「推荐时间 + 历史/收藏数量」判断新数据有没有实质变化，
   一样就不重绘，避免从缓存切到实时数据时整页闪一下 */
function sig(d) {
  if (!d) return '';
  const h = d.history || [];
  return [d.rec && d.rec.at || 0, h.length, h[0] && h[0].at || 0,
          (d.fav || []).length].join('|');
}

const CAT_SLUG = { 电影: 'movie', 剧集: 'series', 动漫: 'anime', 综艺: 'variety', 纪录片: 'doc' };

export function destroy() {
  heroCleanup && heroCleanup();
  heroCleanup = null;
  clearTimeout(pollTimer);
}

function heroItems(rec) {
  const pool = [];
  (rec.cats || []).forEach(c => (c.items || []).forEach(x => pool.push(x)));
  (rec.liked || []).forEach(x => pool.push(x));
  (rec.latest || []).forEach(x => pool.push(x));
  const seen = new Set(), out = [];
  for (const it of pool) {
    if (!it.pic || seen.has(it.name)) continue;
    seen.add(it.name);
    out.push(it);
    if (out.length >= 6) break;
  }
  return out;
}

export async function render(root) {
  document.body.dataset.hero = '1';

  // 有上次的首页就直接画出来，网络请求在后台跑
  const cached = cacheGet(HKEY, HOME_TTL);
  if (cached) {
    store.home = cached;
    store.favs = new Set((cached.fav || []).map(x => x.key));
    paint(root, cached);
  } else {
    root.innerHTML = `${heroHTML([])}
      <div class="skl" style="height:24px;width:180px;margin:30px var(--pad) 0;border-radius:8px"></div>
      <div class="skl-grid">${Array(7).fill('<div class="skl skl-card"></div>').join('')}</div>`;
  }

  let d;
  try {
    d = await api.home();
  } catch (e) {
    if (!cached) {
      root.innerHTML = emptyState({
        iconName: 'wifi', title: '读不到首页数据', desc: e.message,
        action: '<button class="btn" onclick="location.reload()">重试</button>',
      });
    }
    return;
  }
  store.home = d;
  store.favs = new Set((d.fav || []).map(x => x.key));
  if (!cached || sig(cached) !== sig(d)) paint(root, d);
  cacheSet(HKEY, slimHome(d));
  if (!d.rec || d.rec.loading || !(d.rec.cats || []).length) scheduleRefresh(root);
}

function scheduleRefresh(root) {
  clearTimeout(pollTimer);
  pollTimer = setTimeout(async () => {
    try {
      const rec = await api.recommend();
      if (store.home) store.home.rec = rec;
      const main = root.querySelector('.page');
      if (main && rec && (rec.cats || []).length) {
        paint(root, store.home);
        cacheSet(HKEY, slimHome(store.home));
      } else if (rec && rec.loading) scheduleRefresh(root);
    } catch (e) { /* 后台刷新失败不打扰用户 */ }
  }, 2500);
}

function paint(root, d) {
  const rec = d.rec || {};
  const items = heroItems(rec);
  const hist = (d.history || []).filter(x => x.kind === 'online' && x.title);
  const stat = d.stat || {};

  const quick = `<section class="section">
    <div class="section-head"><h2 class="section-title">去哪儿</h2>
      <span class="section-sub">直播、音乐、片源都在这里</span></div>
    <div class="quick-grid">
      <a class="quick live" href="#/live" data-focus>
        <h4>直播</h4><p>${stat.live ? stat.live + ' 个频道，按分组浏览，点开就看' : '还没有频道，去设置里导入一个 M3U 源'}</p>
        <span class="q-ico">${icon('tv')}</span></a>
      <a class="quick music" href="#/music" data-focus>
        <h4>音乐</h4><p>接你的 Navidrome，或直接扫 NAS 上的音乐目录</p>
        <span class="q-ico">${icon('music')}</span></a>
      <a class="quick sources" href="#/settings/sources" data-focus>
        <h4>片源管理</h4><p>${stat.sites || 0} 个片源在跑，支持 TVBox 配置一键导入</p>
        <span class="q-ico">${icon('link')}</span></a>
    </div>
  </section>`;

  const cont = hist.length ? `<section class="section">
    <div class="section-head"><h2 class="section-title">继续观看</h2>
      <span class="section-sub">从上次停下的地方接着看</span>
      <a class="more" href="#/settings/history" data-focus>全部记录${icon('chevron')}</a></div>
    <div class="rail continue-rail">${hist.slice(0, 10).map(continueCard).join('')}</div>
  </section>` : '';

  const tastes = (rec.taste || []).length ? `<div class="taste-row" style="margin-top:18px">
    ${rec.taste.slice(0, 6).map(t => `<a class="chip" data-focus
      href="#/search?q=${encodeURIComponent(t)}">${esc(t)}</a>`).join('')}
  </div>` : '';

  const catShelves = (rec.cats || []).map(c => shelf({
    title: c.cat, sub: `${c.count || c.items.length} 部`,
    items: (c.items || []).slice(0, 14),
    more: CAT_SLUG[c.cat] ? `#/browse/${CAT_SLUG[c.cat]}` : '',
  })).join('');

  const latest = (rec.latest || []).length && !(rec.cats || []).length ? shelf({
    title: '最近更新', items: rec.latest.slice(0, 14),
  }) : '';

  const liked = (rec.liked || []).length ? shelf({
    title: '按你的口味', sub: '根据搜索与观看记录挑的', items: rec.liked.slice(0, 14),
  }) : '';

  const nothing = !items.length && !catShelves && !latest;

  root.innerHTML = `<div class="page" style="padding:0">
    ${heroHTML(items)}
    ${cont}
    ${quick}
    ${nothing ? `<div style="padding:40px var(--pad)">
      ${emptyState({
        iconName: 'film', title: '片库还在预热',
        desc: '首次启动要去十几个采集站拉数据，大约十几秒。点开片源管理可以先看看源的状态。',
        action: '<a class="btn" href="#/settings/sources" data-focus>去片源管理</a>',
      })}</div>` : ''}
    ${tastes}
    ${catShelves}
    ${latest}
    ${liked}
  </div>`;

  heroCleanup && heroCleanup();
  heroCleanup = mountHero(root, items);
}

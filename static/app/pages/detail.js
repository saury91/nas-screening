// 详情页：聚合线路 + 选集 + 收藏，进入即可播
import { api } from '../core/api.js';
import { esc, icon, toast } from '../core/dom.js';
import { isFav, syncFavs, toggleFav } from '../core/store.js';
import { openPlayer } from '../components/player.js';
import { emptyState, imgTag, posterCard } from '../components/ui.js';

let hit = null;
let userPicked = false;   // 用户手动点过线路后，就不再自动切到最高画质
let si = 0;

export function destroy() { hit = null; }

function tintAmbient(pic) {
  document.documentElement.style.setProperty(
    '--ambient-poster',
    pic ? `url("${String(pic).replace(/["'\\\n\r]/g, '')}")` : 'none');
}

// 返回：优先回上一页，直接打开的深链接就回首页。骨架期也绑，页面一出现就能退
function bindBack(root) {
  const b = root.querySelector('#btn-back');
  if (b) b.onclick = () => {
    if (history.length > 1) history.back();
    else location.hash = '#/home';
  };
}

export async function render(root, ctx) {
  const name = ctx.query.n || '';
  const year = ctx.query.y || '';
  if (!name) {
    root.innerHTML = emptyState({ iconName: 'film', title: '缺片名', desc: '从搜索或片库点进来才有内容。' });
    return;
  }

  // 骨架屏和真实详情页同构：返回按钮、hero 底纹、海报/标题/操作区/简介、
  // 下面的线路面板都占好位，加载完不会整页跳一下。之前只有四个灰条，
  // 又恰好露出上一页那张暖色海报的全局环境光，看起来像块脏橘布。
  tintAmbient('');   // 骨架阶段没有封面，先清掉上一页残留的环境光
  root.innerHTML = `<div class="page bleed" style="padding:0">
    <button class="back-pill" id="btn-back" data-focus aria-label="返回上一页">
      ${icon('back')}<span>返回</span>
    </button>
    <section class="detail-hero is-skeleton">
      <div class="hero-bg"></div>
      <div class="hero-veil"></div>
      <div class="detail-body">
        <div class="detail-poster skl"></div>
        <div class="detail-main">
          <div class="skl skl-ph" style="width:124px;height:11px;margin:0 0 14px"></div>
          <div class="skl skl-ph" style="width:58%;height:clamp(30px,4.6vw,54px);margin-bottom:16px"></div>
          <div class="skl skl-ph" style="width:min(420px,52%);height:13px;margin-bottom:20px"></div>
          <div style="display:flex;gap:10px;flex-wrap:wrap;margin-bottom:20px">
            <div class="skl skl-ph" style="width:132px;height:44px;border-radius:999px"></div>
            <div class="skl skl-ph" style="width:104px;height:44px;border-radius:999px"></div>
            <div class="skl skl-ph" style="width:118px;height:44px;border-radius:999px"></div>
          </div>
          <div class="skl skl-ph" style="width:min(760px,88%);height:12px;margin-bottom:10px"></div>
          <div class="skl skl-ph" style="width:min(680px,74%);height:12px"></div>
        </div>
      </div>
    </section>
    <div class="detail-panels">
      <section class="panel play-panel">
        <div class="panel-head"><h3>播放线路</h3></div>
        <div class="skl-chips">
          ${Array(4).fill('<div class="skl skl-ph" style="width:104px;height:36px;border-radius:10px"></div>').join('')}
        </div>
        <p class="skl-hint">正在向十几个片源要线路，慢的会自动跳过…</p>
      </section>
    </div></div>`;

  bindBack(root);

  let d;
  try {
    d = await api.detail(name, year);
  } catch (e) {
    // 离线拿不到线路/集数，但本地索引里还有海报和简介：先把这一页画出来，
    // 而不是甩一句"没找到"（用户会以为片子丢了）
    let t = null;
    try {
      const m = await import('../core/offline.js');
      t = await m.getTitle(name);
    } catch (_) { /* 索引读不到就走原来的兜底 */ }
    if (t) {
      root.innerHTML = offlineDetail(t, e.message);
      root.querySelector('#od-retry')?.addEventListener('click', () => location.reload());
      return;
    }
    const down = e.code === 'sources_down' || e.status === 503;
    root.innerHTML = `<div class="page">${emptyState({
      iconName: 'wifi',
      title: down ? '片源都没响应' : '没找到这部片子',
      desc: down ? '十几个采集站这次都没回话，多半是网络或源站抽风，过会儿再试。' : e.message,
      action: down
        ? `<button class="btn" id="d-retry" data-focus>重试</button>`
        : `<a class="btn" href="#/search?q=${encodeURIComponent(name)}" data-focus>搜搜看</a>`,
    })}</div>`;
    root.querySelector('#d-retry')?.addEventListener('click', () => location.reload());
    return;
  }
  hit = d;
  si = 0;
  await syncFavs();
  paint(root, d, ctx);
}

// 离线详情：只有本地索引里的基本信息，明确告诉用户缺什么
function offlineDetail(t, msg) {
  const meta = [t.year, t.area, t.genre].filter(Boolean).join(' · ');
  return `<div class="page">
    <div class="offline-detail">
      ${t.pic ? `<img class="od-pic" src="${esc(t.pic)}" alt="">` : '<div class="od-pic od-pic-empty"></div>'}
      <div class="od-main">
        <h2>${esc(t.name)}</h2>
        ${meta ? `<p class="od-meta">${esc(meta)}</p>` : ''}
        ${t.desc ? `<p class="od-desc">${esc(t.desc)}</p>` : ''}
        <p class="od-note">线路和集数暂时取不到，联网后会自动补全。</p>
        <div class="od-actions">
          <button class="btn primary" id="od-retry" data-focus>重新连接</button>
          <a class="btn ghost" href="#/home">回首页</a>
        </div>
        ${msg ? `<p class="od-msg">${esc(msg)}</p>` : ''}
      </div>
    </div>
  </div>`;
}

// ── 右侧影片信息 ─────────────────────────────────────────────
function infoRows(d) {
  const rows = [];
  const add = (k, v) => { if (v) rows.push([k, v]); };
  add('评分', d.score && parseFloat(d.score) > 0 ? d.score : '');
  add('年份', d.year);
  add('地区', d.area);
  add('语言', d.lang);
  add('类型', d.type);
  add('集数', d.total && +d.total > 1 ? d.total + ' 集' : '');
  add('线路', d.sources.length + ' 条');
  add('导演', d.director);
  add('主演', d.actor);
  return rows.map(([k, v]) =>
    `<div><dt>${k}</dt><dd>${esc(v)}</dd></div>`).join('');
}

// ── 相关推荐 ─────────────────────────────────────────────────
const CAT_SLUG = { 电影: 'movie', 剧集: 'series', 动漫: 'anime',
                   综艺: 'variety', 纪录片: 'doc' };

// 和聚合层同一套归类规则：先判动画/纪录/综艺，再按「X片」判电影，最后才看「剧」
function catOfType(t) {
  const s = String(t || '');
  if (/动漫|动画|番剧/.test(s)) return '动漫';
  if (/纪录|记录/.test(s)) return '纪录片';
  if (/综艺/.test(s)) return '综艺';
  if (/片$/.test(s)) return '电影';
  if (/剧/.test(s)) return '剧集';
  if (/电影/.test(s)) return '电影';
  return '电影';
}

const plainName = s => String(s || '').replace(/\s+/g, '').toLowerCase();

async function loadSimilar(root, d) {
  const box = root.querySelector('#similar');
  if (!box) return;
  const cat = catOfType(d.type);
  box.innerHTML = `<div class="section-head"><h2 class="section-title">相关推荐</h2>
    <span class="section-sub">同类作品</span></div>
    <div class="rail">${Array(8).fill('<div class="skl skl-card"></div>').join('')}</div>`;
  try {
    const r = await api.browse({ cat, pg: 1 });
    if (!root.isConnected) return;
    const items = (r.items || [])
      .filter(x => plainName(x.name) !== plainName(d.name))
      .slice(0, 14);
    if (!items.length) { box.innerHTML = ''; return; }
    box.innerHTML = `<div class="section-head">
      <h2 class="section-title">相关推荐</h2>
      <span class="section-sub">${esc(cat)} · 同类作品</span>
      <a class="more" href="#/browse/${CAT_SLUG[cat] || 'movie'}" data-focus>查看全部${icon('chevron')}</a>
    </div>
    <div class="rail">${items.map(x => posterCard(x, {
      sub: [x.year, x.type, x.site].filter(Boolean).join(' · '),
    })).join('')}</div>`;
  } catch (e) {
    box.innerHTML = '';
  }
}

function metaLine(d) {
  return [
    d.score && parseFloat(d.score) > 0 ? `<span class="score">${esc(d.score)}</span>` : '',
    d.year ? `<span>${esc(d.year)}</span>` : '',
    d.area ? `<span>${esc(d.area)}</span>` : '',
    d.lang ? `<span>${esc(d.lang)}</span>` : '',
    d.type ? `<span>${esc(d.type)}</span>` : '',
    d.total ? `<span>${esc(d.total)} 集</span>` : '',
    d.site_count ? `<span class="dot">·</span><span>${d.site_count} 条线路</span>` : '',
  ].filter(Boolean).join('<span class="dot">·</span>');
}

function people(d) {
  const out = [];
  if (d.director) out.push(`导演 <b style="color:var(--dim);font-weight:500">${esc(d.director)}</b>`);
  if (d.actor) out.push(`主演 <b style="color:var(--dim);font-weight:500">${esc(d.actor)}</b>`);
  return out.join('　');
}

function paint(root, d, ctx) {
  userPicked = false;
  document.body.dataset.hero = '1';   // 详情页有全幅背景，不要顶栏那层留白
  tintAmbient(d.pic);
  // 上次看这部片时用的线路，优先沿用（比"最高画质"更贴近用户习惯）
  let start = 0;
  try {
    const remembered = localStorage.getItem('pr.line.' + d.name);
    const k = remembered ? d.sources.findIndex(s => s.site === remembered) : -1;
    if (k > 0) { start = k; userPicked = true; }
  } catch (e) { /* 隐私模式下 localStorage 不可用 */ }
  si = start;
  const fav = isFav(d.name);
  root.innerHTML = `<div class="page bleed" style="padding:0">
    <button class="back-pill" id="btn-back" data-focus aria-label="返回上一页">
      ${icon('back')}<span>返回</span>
    </button>
    <section class="detail-hero">
      <div class="hero-bg" style="${d.pic ? `background-image:url('${esc(d.pic)}')` : ''}"></div>
      <div class="hero-veil"></div>
      <div class="detail-body">
        <div class="detail-poster">${imgTag(d.pic, d.name, '', true)}</div>
        <div class="detail-main">
          <div class="eyebrow">来自 ${esc((d.sources[0] || {}).site || '聚合片库')}</div>
          <h1 class="detail-title">${esc(d.name)}</h1>
          <div class="detail-meta">${metaLine(d)}</div>
          ${people(d) ? `<p class="detail-meta" style="margin-bottom:14px">${people(d)}</p>` : ''}
          <div class="detail-actions">
            <button class="btn primary" id="btn-play" data-focus>${icon('play')}立即播放</button>
            <button class="btn" id="btn-fav" data-focus>${icon(fav ? 'check' : 'plus')}${fav ? '已收藏' : '收藏'}</button>
            <a class="btn ghost" href="#/settings/sources" data-focus>${icon('link')}片源管理</a>
          </div>
          ${d.desc ? `<p class="detail-desc" id="desc">${esc(d.desc)}</p>
            <button class="desc-toggle" id="desc-toggle">展开简介</button>` : ''}
        </div>
      </div>
    </section>
    <div class="detail-panels">
      <section class="panel play-panel">
        <div class="panel-head">
          <h3>播放线路</h3>
          <span class="panel-sub" id="panel-sub">${d.sources.length} 条线路可选</span>
        </div>
        <div class="src-chips" id="lines">
          ${d.sources.map((s, i) => `<button class="src-chip ${i === start ? 'on' : ''}"
            data-si="${i}" data-focus title="${esc(s.remarks || '')}">
            <b>${esc(s.site)}</b>${s.q ? `<u>${esc(s.q)}</u>` : ''}<i>${s.count} 集</i>
          </button>`).join('')}
        </div>
        <div class="ep-block">
          <div class="ep-head"><h4>选集</h4><span id="ep-count"></span></div>
          <div class="ep-grid" id="eps"></div>
        </div>
      </section>
      <aside class="panel info-panel">
        <h3>影片信息</h3>
        <dl class="info-list">${infoRows(d)}</dl>
      </aside>
    </div>
    <section class="section similar-sec" id="similar"></section>
  </div>`;

  const toggle = root.querySelector('#desc-toggle');
  if (toggle) toggle.onclick = () => {
    const p = root.querySelector('#desc');
    const open = p.classList.toggle('open');
    toggle.textContent = open ? '收起' : '展开简介';
  };

  bindBack(root);

  root.querySelector('#btn-play').onclick = () => play(ctx);
  root.querySelector('#btn-fav').onclick = async () => {
    try {
      const on = await toggleFav(d);
      const b = root.querySelector('#btn-fav');
      b.innerHTML = `${icon(on ? 'check' : 'plus')}${on ? '已收藏' : '收藏'}`;
      toast(on ? '已加入收藏，可在设置里查看' : '已取消收藏');
    } catch (e) { toast(e.message, 'err'); }
  };

  root.querySelector('#lines')?.addEventListener('click', e => {
    const b = e.target.closest('[data-si]');
    if (!b) return;
    userPicked = true;
    si = +b.dataset.si;
    root.querySelectorAll('#lines .src-chip').forEach(x => x.classList.toggle('on', x === b));
    paintEps(root);
  });

  paintEps(root);
  probeQualities(root, d);
  // 相关推荐后加载，不挡首屏
  loadSimilar(root, d);

  // 从「继续观看」或「立即观看」进来时直接起播
  if (ctx.query.play === '1' || ctx.query.ep || ctx.query.t) {
    setTimeout(() => play(ctx), 80);
  }
}

// 备注里通常只写「HD中字」，分不出 720/1080。很多源的 m3u8 主列表带真实分辨率，
// 这里挑前 8 条线路并发探一下，回来就把 chip 上的标签换成实测值。
const probedUrls = new Map();

async function probeQualities(root, d) {
  const list = (d.sources || []).slice(0, 8);
  const queue = list.map((_, i) => i);

  const work = async () => {
    while (queue.length) {
      if (!root.isConnected) return;
      const i = queue.shift();
      const src = d.sources[i];
      const url = src && src.eps && src.eps[0] ? src.eps[0].url : '';
      if (!url) continue;
      let r = probedUrls.get(url);
      if (!r) {
        try {
          r = await api.quality(url);
        } catch (e) {
          continue;
        }
        probedUrls.set(url, r);
        if (probedUrls.size > 300) probedUrls.clear();
      }
      if (!r || !r.q || !root.isConnected) continue;
      src.q = r.q;
      const chip = root.querySelector(`.src-chip[data-si="${i}"]`);
      if (!chip) continue;
      let u = chip.querySelector('u');
      if (!u) {
        u = document.createElement('u');
        const b = chip.querySelector('b');
        if (b) b.after(u); else chip.prepend(u);
      }
      u.textContent = r.q;
      if (r.res) chip.title = `${src.remarks || ''} ${r.res}`.trim();
    }
  };

  await Promise.all([work(), work(), work(), work()]);
  resortLines(root);
}

const Q_RANK = { '4K': 0, '1080P': 1, '720P': 2, 'HD': 3, '480P': 4 };

// 探测出来的画质重排一次：高清线路排前面，「立即播放」默认就走最高画质
function resortLines(root) {
  const box = root.querySelector('#lines');
  if (!box || !root.isConnected) return;
  const chips = Array.from(box.children);
  const rankOf = el => {
    const u = el.querySelector('u');
    return Q_RANK[(u && u.textContent) || ''] ?? 9;
  };
  const sorted = chips.slice().sort((a, b) => rankOf(a) - rankOf(b));
  const changed = sorted.some((el, i) => el !== chips[i]);
  if (!changed) return;
  sorted.forEach(el => box.appendChild(el));
  // 用户还没手动选过线路时，跟着把选中项切到最高画质那条
  if (!userPicked) {
    const first = sorted[0];
    box.querySelectorAll('.src-chip').forEach(x => x.classList.toggle('on', x === first));
    si = +first.dataset.si;
    paintEps(root);
  }
}

function paintEps(root) {
  const src = hit.sources[si];
  // 集数多的剧集才并排；电影这种 1 集的保持单栏，避免右边留一大块空白
  const panel = root.querySelector('.play-panel');
  if (panel) panel.classList.toggle('many-eps', (src.eps || []).length >= 8);
  root.querySelector('#ep-count').textContent = `共 ${src.count} 集 · 来自 ${src.site}`;
  const sub = root.querySelector('#panel-sub');
  if (sub) sub.textContent = `当前：${src.site}${src.remarks ? ' · ' + src.remarks : ''}`;
  root.querySelector('#eps').innerHTML = src.eps.map((e, i) =>
    `<button class="ep" data-ei="${i}" data-focus>${esc(e.name)}</button>`).join('');
  root.querySelector('#eps').onclick = e => {
    const b = e.target.closest('[data-ei]');
    if (b) play({}, +b.dataset.ei);
  };
}

function play(ctx, forceEi) {
  const q = (ctx && ctx.query) || {};
  const src = hit.sources[si];
  let ei = forceEi;
  if (ei === undefined) {
    const qep = q.ep;
    if (qep) {
      const found = src.eps.findIndex(e => e.name === qep);
      ei = found >= 0 ? found : 0;
    } else {
      ei = 0;
    }
  }
  const pos = Number(q.t || 0);
  openPlayer(hit, { si, ei, position: pos, key: hit.name });
}

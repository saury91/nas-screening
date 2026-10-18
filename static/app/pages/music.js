// 音乐：专辑墙 / 歌手 / 专辑详情 + 播放队列
import { api } from '../core/api.js';
import { esc, icon, toast, fmtClock, debounce } from '../core/dom.js';
import { playAlbum, playQueue, nowPlayingTrack } from '../components/musicplayer.js';
import { emptyState } from '../components/ui.js';
import { go } from '../core/router.js';

let home = null;
let kw = '';
let found = null;      // 服务端搜索结果（专辑 + 单曲）
let seq = 0;
let runSearchRef = null;
let favListener = null;

export function destroy() {
  home = null; kw = ''; found = null;
  if (favListener) { document.removeEventListener('app:music-fav', favListener); favListener = null; }
}

export async function render(root, ctx) {
  const isAlbum = ctx.seg[1] === 'album';
  if (isAlbum) return renderAlbum(root, ctx.query.id);
  const isPlaylist = ctx.seg[1] === 'playlist';
  if (isPlaylist) return renderPlaylist(root, ctx.query.id);

  root.innerHTML = `<div class="page music-page">
    <div class="page-head">
      <div><div class="eyebrow">音乐</div><h1 class="page-title">曲库</h1></div>
      <p class="page-sub" id="sum">正在连接音乐库…</p>
      <span class="spacer"></span>
      <label class="msearch">
        ${icon('search', 'si')}
        <input id="m-kw" placeholder="搜索歌曲 / 歌手" autocomplete="off" spellcheck="false">
        <button class="msearch-x" id="m-kw-x" hidden title="清空">${icon('close')}</button>
      </label>
    </div>
    <section class="section mresults" id="m-results" hidden>
      <div class="section-head shelf-head">
        <div class="shelf-meta">
          <h2 class="section-title" id="m-res-title">搜索结果</h2>
          <span class="section-sub" id="m-res-tip"></span>
        </div>
        <span class="spacer"></span>
        <button class="btn sm ghost" id="m-res-back" data-focus>${icon('back')}返回推荐</button>
      </div>
      <div id="m-online-list"></div>
    </section>
    <div id="m-fav"></div>
    <div id="m-online-home"></div>
    <div id="m-cats"></div>
    <div id="mbody"><div class="album-grid">${Array(8).fill('<div class="skl" style="aspect-ratio:1;border-radius:14px"></div>').join('')}</div></div>
  </div>`;

  // 一个输入框既搜本地曲库，也搜在线音源：不再单独占一张卡片
  const kwInput = root.querySelector('#m-kw');
  const kwClear = root.querySelector('#m-kw-x');
  const resultsEl = root.querySelector('#m-results');
  const listEl = root.querySelector('#m-online-list');
  const tipEl = root.querySelector('#m-res-tip');
  const titleEl = root.querySelector('#m-res-title');
  const shelvesEl = root.querySelector('#m-online-home');
  const favEl = root.querySelector('#m-fav');
  let onlineTracks = [];
  let onlineSeq = 0;

  const showResults = on => {
    resultsEl.hidden = !on;
    shelvesEl.style.display = on ? 'none' : '';
    favEl.style.display = on ? 'none' : '';
    const cats = root.querySelector('#m-cats');
    if (cats) cats.style.display = on ? 'none' : '';
    kwClear.hidden = !on;
  };

  const paintOnline = () => {
    if (!onlineTracks.length) {
      listEl.innerHTML = `<p class="hint" style="margin:0 0 4px">在线音源没有找到，换个关键词试试</p>`;
      return;
    }
    listEl.innerHTML = `<div class="tracklist">${onlineTracks.map((t, i) => `
      <div class="track has-art" data-focus tabindex="0" role="button" data-oi="${i}"
           aria-label="播放 ${esc(t.title)}">
        <span class="t-no">${i + 1}</span>
        <span class="t-art">${t.cover
          ? `<img src="${esc(t.cover)}" alt="" loading="lazy" onerror="this.remove()">`
          : '<i></i>'}</span>
        <span class="t-name">${esc(t.title)}</span>
        <span class="t-artist">${esc(t.artist || '')}</span>
        <span class="t-dur">${t.duration ? fmtClock(t.duration) : '—'}</span>
      </div>`).join('')}</div>`;
  };

  // 服务端搜索：以前只在前 24 张专辑里做前端过滤，曲库一大就等于搜不到
  const doSearch = debounce(async () => {
    const k = kw;
    const token = ++seq;
    if (!k) {
      found = null;
      onlineTracks = [];
      showResults(false);
      listEl.innerHTML = '';
      paint(root);
      return;
    }
    showResults(true);
    titleEl.textContent = `搜索「${k}」`;
    tipEl.textContent = '搜索中…';
    listEl.innerHTML = '';

    // 本地曲库（没配置就跳过）
    if (home && home.configured) {
      try {
        const d = await api.musicSearch(k);
        if (token === seq) found = d;
      } catch (e) {
        if (token === seq) found = null;
      }
    } else {
      found = null;
    }
    paint(root);

    // 在线音源
    if (k.length >= 2) {
      const oToken = ++onlineSeq;
      try {
        const d = await api.musicOnline(k);
        if (oToken !== onlineSeq || token !== seq) return;
        onlineTracks = d.items || [];
        paintOnline();
        tipEl.textContent = onlineTracks.length ? `在线 ${onlineTracks.length} 首 · 标了时长的多为完整版` : '';
      } catch (e) {
        if (oToken !== onlineSeq) return;
        onlineTracks = [];
        paintOnline();
        tipEl.textContent = '';
      }
    }
  }, 350);
  runSearchRef = doSearch;

  kwInput.addEventListener('input', () => {
    kw = kwInput.value.trim();
    kwClear.hidden = !kw;
    doSearch();
  });
  kwInput.addEventListener('keydown', e => {
    if (e.key === 'Enter') { e.preventDefault(); doSearch(); }
    if (e.key === 'Escape' && kw) { e.stopPropagation(); clearKw(); }
  });
  const clearKw = () => {
    kw = '';
    kwInput.value = '';
    onlineTracks = [];
    showResults(false);
    listEl.innerHTML = '';
    paint(root);
    kwInput.focus();
  };
  kwClear.onclick = clearKw;
  root.querySelector('#m-res-back').onclick = clearKw;

  listEl.onclick = e => {
    const row = e.target.closest('[data-oi]');
    if (!row || !onlineTracks.length) return;
    const i = +row.dataset.oi;
    playQueue(onlineTracks, i);
    toast('开始播放 ' + onlineTracks[i].title);
  };

  // 推荐歌单：封面卡，点进去才是曲目列表
  // （以前把每个歌单摊成 10 首歌，一屏 60 首，既看不出是歌单也没法整单收藏）
  const oHome = root.querySelector('#m-online-home');
  let recPls = [];
  const plCard = (p, i, attr) => `
    <div class="pl-card" data-focus tabindex="0" role="button" ${attr}="${i}"
         aria-label="打开歌单 ${esc(p.name)}">
      <div class="pl-art">
        ${p.cover ? `<img src="${esc(p.cover)}" alt="" loading="lazy" onerror="this.remove()">` : '<i></i>'}
        <span class="pl-play">${icon('play')}</span>
      </div>
      <p class="pl-name">${esc(p.name)}</p>
      <p class="pl-meta">${p.play ? fmtPlay(p.play) + ' 次播放' : (p.count ? p.count + ' 首' : '')}</p>
    </div>`;

  const renderOnlineHome = async () => {
    try {
      const d = await api.musicPlRecommend(14);
      recPls = d.items || [];
    } catch (e) { recPls = []; }
    oHome.innerHTML = recPls.length ? `
      <section class="section" style="margin:20px 0 0">
        <div class="section-head shelf-head">
          <div class="shelf-meta">
            <h2 class="section-title">推荐歌单</h2>
            <span class="section-sub">编辑挑的，点开就是整张歌单</span>
          </div>
        </div>
        <div class="pl-rail">${recPls.map((p, i) => plCard(p, i, 'data-rec')).join('')}</div>
      </section>` : '';
  };
  oHome.onclick = e => {
    const card = e.target.closest('[data-rec]');
    if (!card) return;
    const p = recPls[+card.dataset.rec];
    if (p) go('/music/playlist?id=' + encodeURIComponent(p.id));
  };
  renderOnlineHome();
  renderCategories(root);

  // 我的收藏：在播放器里点心心，这里就会出现
  let favItems = [];
  const favBox = root.querySelector('#m-fav');
  const songCard = (t, i, act) => `
    <div class="song-card" data-focus tabindex="0" role="button"
         data-${act}="${i}" aria-label="播放 ${esc(t.title)}">
      <div class="sc-art">
        ${t.cover ? `<img src="${esc(t.cover)}" alt="" loading="lazy" onerror="this.remove()">` : ''}
        <span class="sc-play">${icon('play')}</span>
        ${act === 'fi' ? `<button class="sc-del" data-fdel="${i}" title="取消收藏">${icon('close')}</button>` : ''}
      </div>
      <p class="sc-title">${esc(t.title)}</p>
      <p class="sc-sub">${esc(t.artist || '')}</p>
    </div>`;
  const renderFav = async () => {
    try {
      const d = await api.library('music');
      favItems = d.items || [];
    } catch (e) { favItems = []; }
    if (!favItems.length) { favBox.innerHTML = ''; return; }
    favBox.innerHTML = `
      <section class="section" style="margin:18px 0 0">
        <div class="section-head shelf-head">
          <div class="shelf-meta">
            <h2 class="section-title">我的收藏</h2>
            <span class="section-sub">${favItems.length} 首 · 保存在本机</span>
          </div>
          <span class="spacer"></span>
          <button class="btn sm ghost" id="m-fav-all" data-focus>${icon('play')}全部播放</button>
        </div>
        <div class="song-rail">${favItems.map((t, i) => songCard(t, i, 'fi')).join('')}</div>
      </section>`;
    favBox.querySelector('#m-fav-all').onclick = () => {
      playQueue(favItems, 0);
      toast(`播放收藏 ${favItems.length} 首`);
    };
    favBox.onclick = async e => {
      const del = e.target.closest('[data-fdel]');
      if (del) {
        e.stopPropagation();
        const t = favItems[Number(del.dataset.fdel)];
        if (!t) return;
        await api.fav({ kind: 'music', key: String(t.id), title: t.title, cover: t.cover || '' });
        renderFav();
        return;
      }
      const card = e.target.closest('[data-fi]');
      if (!card) return;
      playQueue(favItems, Number(card.dataset.fi) || 0);
    };
  };
  renderFav();
  // 播放器里点心心时，这里跟着更新
  if (favListener) document.removeEventListener('app:music-fav', favListener);
  favListener = () => renderFav();
  document.addEventListener('app:music-fav', favListener);

  try {
    home = await api.musicHome(200);
  } catch (e) {
    root.querySelector('#mbody').innerHTML = emptyState({
      iconName: 'music', title: '音乐库读不到', desc: e.message,
      action: '<a class="btn" href="#/settings/music" data-focus>去配置</a>',
    });
    return;
  }
  paint(root);
}

function paint(root) {
  const body = root.querySelector('#mbody');
  const sum = root.querySelector('#sum');
  if (!home || !home.configured) {
    if (sum) sum.textContent = kw ? '在线音源搜索' : '在线歌单推荐 · 下面可直接播放，无需配置';
    // 搜索框现在也搜在线音源，任何时候都得留着
    // 搜索态下把「想听自己的歌」那块收起来，别挤在结果下面
    if (kw) { body.innerHTML = ''; return; }
    body.innerHTML = `<div class="card-block">
      <h4>想听自己的歌？</h4>
      <p class="hint" style="margin:6px 0 12px">接一个 Subsonic 服务（Navidrome / Airsonic），
        或直接扫 NAS 上的音乐目录。上面的搜索不需要配置也能用。</p>
      <a class="btn sm" href="#/settings/music" data-focus>去启用音乐库</a>
    </div>`;
    return;
  }
  if (home.error) {
    if (sum) sum.textContent = '出错了';
    body.innerHTML = emptyState({
      iconName: 'music', title: '音乐库暂时不可用', desc: home.error,
      action: '<a class="btn" href="#/settings/music" data-focus>检查配置</a>',
    });
    return;
  }

  const albums = kw ? ((found && found.albums) || []) : (home.albums || []);
  const tracks = kw ? ((found && found.tracks) || []) : [];
  if (sum) {
    sum.textContent = kw
      ? `${albums.length} 张专辑 · ${tracks.length} 首歌`
      : `${home.total || (home.albums || []).length} 张专辑 · ` +
        (home.provider === 'local' ? '本地目录' : 'Subsonic');
  }

  body.innerHTML = `
    ${kw ? '' : `<div class="music-hero">
      <div class="eyebrow">${home.provider === 'local' ? '本地曲库' : 'SUBSONIC'}</div>
      <h1>最近加入</h1>
      <p>${esc(home.root || '')}${home.provider === 'local' ? '' : ' · 由你的音乐服务提供'}</p>
    </div>`}
    ${albums.length ? `<div class="album-grid">${albums.map(a => `
      <div class="album" data-focus tabindex="0" role="button"
           aria-label="打开专辑 ${esc(a.title)}" data-album="${esc(a.id)}">
        <div class="album-art">
          <div class="ph">${esc((a.title || '?').slice(0, 1))}</div>
          ${a.cover ? `<img src="${esc(a.cover)}" alt="" loading="lazy" decoding="async"
            onerror="this.remove()">` : ''}
          <span class="a-play">${icon('play')}</span>
        </div>
        <p class="album-title">${esc(a.title)}</p>
        <p class="album-artist">${esc(a.artist || '')}${a.year ? ' · ' + esc(a.year) : ''}</p>
      </div>`).join('')}</div>` :
      emptyState({
        iconName: 'music',
        title: kw ? '没有找到专辑' : '曲库还是空的',
        desc: kw ? '换个关键词，或试试搜歌手名。' : '确认目录里真的有音频文件，或检查 Subsonic 是否可访问。' })}
    ${tracks.length ? `<section class="section" style="margin-left:0">
      <div class="section-head" style="margin-left:0"><h2 class="section-title">歌曲</h2></div>
      <div class="tracklist">${tracks.map((t, i) => `
        <div class="track" data-focus tabindex="0" role="button"
             aria-label="播放 ${esc(t.title)}" data-track="${i}">
          <span class="t-no">${t.track || i + 1}</span>
          <span class="t-name">${esc(t.title)}</span>
          <span class="t-artist">${esc(t.artist || '')}</span>
          <span class="t-dur">${t.duration ? fmtClock(t.duration) : '—'}</span>
        </div>`).join('')}</div>
    </section>` : ''}
    ${(home.artists || []).length && !kw ? `<section class="section" style="margin-left:0">
      <div class="section-head" style="margin-left:0"><h2 class="section-title">歌手</h2></div>
      <div class="artist-grid">${home.artists.map(a => `
        <div class="artist" data-focus tabindex="0" role="button"
             aria-label="筛选 ${esc(a.name)}" data-artist="${esc(a.name)}">
          <div class="av">${esc((a.name || '?').slice(0, 1))}</div>
          <div><div class="an">${esc(a.name)}</div><div class="ac">${a.count} 张专辑</div></div>
        </div>`).join('')}</div>
    </section>` : ''}`;

  body.onclick = e => {
    const alb = e.target.closest('[data-album]');
    if (alb) { go('/music/album?id=' + encodeURIComponent(alb.dataset.album)); return; }
    const tr = e.target.closest('[data-track]');
    if (tr && tracks.length) {
      playQueue(tracks, +tr.dataset.track || 0);
      toast('开始播放 ' + tracks[+tr.dataset.track || 0].title);
      return;
    }
    const art = e.target.closest('[data-artist]');
    if (art) {
      const input = root.querySelector('#m-kw');
      input.value = art.dataset.artist;
      kw = art.dataset.artist;
      runSearchRef && runSearchRef();
    }
  };
}

// ── 分类歌单 ─────────────────────────────────────────────
// 分类树是「组 → 子类」两层（主题/语种/风格/场景/情感），
// 一屏铺 5 行 chip 太占地方，做成「组标签 + 当前组的子类」两行。
function fmtPlay(n) {
  n = Number(n) || 0;
  if (n >= 1e8) return (n / 1e8).toFixed(1).replace(/\.0$/, '') + ' 亿';
  if (n >= 1e4) return (n / 1e4).toFixed(1).replace(/\.0$/, '') + ' 万';
  return String(n);
}

let catState = null;   // 页面级记忆：返回时不至于跳回默认分类

async function renderCategories(root) {
  const box = root.querySelector('#m-cats');
  if (!box) return;
  let groups = [];
  try {
    groups = (await api.musicPlCats()).groups || [];
  } catch (e) { groups = []; }
  if (!groups.length) { box.innerHTML = ''; return; }

  let gi = 0, si = 0;
  if (catState) {
    gi = Math.max(0, groups.findIndex(g => g.name === catState.g));
    const subs = groups[gi] ? groups[gi].subs : [];
    si = Math.max(0, subs.findIndex(s => s.name === catState.s));
  } else {
    // 默认落在「语种 · 华语」，大众向且一定存在；没有就退到第一组第一项
    const g = groups.findIndex(x => x.name === '语种');
    if (g >= 0) {
      gi = g;
      const k = groups[g].subs.findIndex(s => s.name === '华语');
      if (k >= 0) si = k;
    }
  }

  box.innerHTML = `
    <section class="section" style="margin:20px 0 0">
      <div class="section-head shelf-head">
        <div class="shelf-meta">
          <h2 class="section-title">分类歌单</h2>
          <span class="section-sub">主题 · 语种 · 风格 · 场景 · 情感</span>
        </div>
      </div>
      <div class="cat-tabs" id="cat-tabs"></div>
      <div class="cat-chips" id="cat-chips"></div>
      <div class="pl-grid" id="cat-grid"></div>
    </section>`;

  const tabsEl = box.querySelector('#cat-tabs');
  const chipsEl = box.querySelector('#cat-chips');
  const gridEl = box.querySelector('#cat-grid');
  let items = [], token = 0;

  const paintTabs = () => {
    tabsEl.innerHTML = groups.map((g, i) =>
      `<button class="cat-tab${i === gi ? ' on' : ''}" data-gi="${i}" data-focus>${esc(g.name)}</button>`
    ).join('');
    chipsEl.innerHTML = (groups[gi].subs || []).map((s, i) =>
      `<button class="cat-chip${i === si ? ' on' : ''}" data-si="${i}" data-focus>${esc(s.name)}</button>`
    ).join('');
  };

  const paintGrid = () => {
    if (!items.length) {
      gridEl.innerHTML = '<p class="hint" style="margin:8px 0">这个分类暂时没有歌单，换一个试试</p>';
      return;
    }
    gridEl.innerHTML = items.map((p, i) => `
      <div class="pl-card" data-focus tabindex="0" role="button" data-pi="${i}"
           aria-label="打开歌单 ${esc(p.name)}">
        <div class="pl-art">
          ${p.cover ? `<img src="${esc(p.cover)}" alt="" loading="lazy" onerror="this.remove()">` : '<i></i>'}
          <span class="pl-play">${icon('play')}</span>
        </div>
        <p class="pl-name">${esc(p.name)}</p>
        <p class="pl-meta">${p.play ? fmtPlay(p.play) + ' 次播放' : (p.count ? p.count + ' 首' : '')}</p>
      </div>`).join('');
  };

  const load = async () => {
    const g = groups[gi], sub = (g.subs || [])[si];
    if (!sub) return;
    catState = { g: g.name, s: sub.name };
    const t = ++token;
    gridEl.innerHTML = Array(12).fill('<div class="skl" style="aspect-ratio:1;border-radius:14px"></div>').join('');
    try {
      const d = await api.musicPlaylists(sub.id, 24);
      if (t !== token) return;
      items = d.items || [];
    } catch (e) {
      if (t !== token) return;
      items = [];
    }
    paintGrid();
  };

  paintTabs();
  load();

  box.onclick = e => {
    const tab = e.target.closest('[data-gi]');
    if (tab) {
      const i = +tab.dataset.gi;
      if (i === gi) return;
      gi = i; si = 0;
      paintTabs();
      load();
      return;
    }
    const chip = e.target.closest('[data-si]');
    if (chip) {
      const i = +chip.dataset.si;
      if (i === si) return;
      si = i;
      paintTabs();
      load();
      return;
    }
    const card = e.target.closest('[data-pi]');
    if (card) {
      const p = items[+card.dataset.pi];
      if (p) go('/music/playlist?id=' + encodeURIComponent(p.id));
    }
  };
}

// ── 歌单详情 ─────────────────────────────────────────────
async function renderPlaylist(root, id) {
  root.innerHTML = `<div class="page">
    <div class="skl" style="height:34px;width:120px;border-radius:8px;margin-bottom:22px"></div>
    <div class="pl-hero">
      <div class="skl" style="width:200px;aspect-ratio:1;border-radius:18px"></div>
      <div style="flex:1"><div class="skl skl-line" style="height:30px;width:46%"></div>
        <div class="skl skl-line" style="width:26%"></div>
        <div class="skl skl-line" style="width:60%"></div></div>
    </div>`;
  let p;
  try {
    p = await api.musicPlaylist(id);
  } catch (e) {
    root.innerHTML = `<div class="page">${emptyState({
      iconName: 'music', title: '歌单打不开', desc: e.message,
      action: '<a class="btn" href="#/music" data-focus>回曲库</a>',
    })}</div>`;
    return;
  }
  paintPlaylist(root, p);
}

function paintPlaylist(root, p) {
  const cur = nowPlayingTrack();
  const tracks = p.tracks || [];
  // 上游歌单不返回时长；全都没有时就不占那一列，省得右边空一条
  const anyDur = tracks.some(t => t.duration);
  const desc = (p.desc || '').replace(/\s+/g, ' ').trim();
  root.innerHTML = `<div class="page music-page">
    <div class="page-head" style="margin-bottom:8px">
      <a class="btn sm ghost" href="#/music" data-focus>${icon('back')}曲库</a>
    </div>
    <div class="pl-hero">
      <div class="pl-hero-art">${p.cover
        ? `<img src="${esc(p.cover)}" alt="" onerror="this.remove()">`
        : `<div class="ph">${esc((p.name || '?').slice(0, 1))}</div>`}</div>
      <div class="pl-hero-info">
        <div class="eyebrow">歌单</div>
        <h1>${esc(p.name)}</h1>
        <p class="dim pl-sub">${tracks.length} 首${p.play ? ' · ' + fmtPlay(p.play) + ' 次播放' : ''}${
          p.creator ? ' · by ' + esc(p.creator) : ''}</p>
        ${desc ? `<p class="pl-desc">${esc(desc)}</p>` : ''}
        <div class="row" style="gap:10px;margin-top:16px">
          <button class="btn primary" id="pl-play" data-focus ${tracks.length ? '' : 'disabled'}>
            ${icon('play')}播放全部</button>
        </div>
      </div>
    </div>
    <section class="section" style="margin:24px 0 0">
      <div class="section-head" style="margin-left:0">
        <h2 class="section-title">曲目</h2>
        <span class="section-sub">${tracks.length} 首</span>
      </div>
      ${tracks.length ? `<div class="tracklist pl-tracks${anyDur ? '' : ' no-dur'}">${tracks.map((t, i) => `
        <div class="track${cur && String(cur.id) === String(t.id) ? ' on' : ''}"
             data-focus tabindex="0" role="button" data-pti="${i}"
             aria-label="播放 ${esc(t.title)}">
          <span class="t-no">${i + 1}</span>
          <span class="t-name">${esc(t.title)}</span>
          <span class="t-artist">${esc(t.artist || '')}</span>
          ${anyDur ? `<span class="t-dur">${t.duration ? fmtClock(t.duration) : ''}</span>` : ''}
        </div>`).join('')}</div>`
        : '<p class="hint">这个歌单里没有可播放的曲目</p>'}
    </section>
  </div>`;

  const playAll = root.querySelector('#pl-play');
  if (playAll) {
    playAll.onclick = () => {
      playQueue(tracks, 0);
      toast(`播放「${p.name}」共 ${tracks.length} 首`);
    };
  }
  root.onclick = e => {
    const row = e.target.closest('[data-pti]');
    if (!row) return;
    const i = +row.dataset.pti;
    if (!tracks[i]) return;
    playQueue(tracks, i);
    toast('开始播放 ' + tracks[i].title);
  };
}

async function renderAlbum(root, id) {
  root.innerHTML = `<div class="page">
    <div class="skl" style="height:40px;width:160px;border-radius:8px;margin-bottom:24px"></div>
    <div class="album-detail">
      <div class="skl" style="width:240px;aspect-ratio:1;border-radius:20px"></div>
      <div style="flex:1"><div class="skl skl-line" style="height:34px;width:50%"></div>
        <div class="skl skl-line" style="width:30%"></div></div>
    </div>`;
  let a;
  try {
    a = await api.musicAlbum(id);
  } catch (e) {
    root.innerHTML = `<div class="page">${emptyState({
      iconName: 'music', title: '专辑打不开', desc: e.message,
      action: '<a class="btn" href="#/music" data-focus>回曲库</a>',
    })}</div>`;
    return;
  }
  paintAlbum(root, a);
}

function paintAlbum(root, a) {
  const playing = nowPlayingTrack();
  root.innerHTML = `<div class="page">
    <div class="page-head" style="margin-bottom:10px">
      <a class="btn sm ghost" href="#/music" data-focus>${icon('back')}曲库</a>
    </div>
    <div class="album-detail">
      <div class="art">${a.cover ? `<img src="${esc(a.cover)}" alt="" style="width:100%;height:100%;object-fit:cover">`
        : `<div class="ph">${esc((a.title || '?').slice(0, 1))}</div>`}</div>
      <div style="min-width:0">
        <div class="eyebrow">专辑</div>
        <h1>${esc(a.title)}</h1>
        <p class="dim" style="margin:0 0 18px">${esc(a.artist || '')}${a.year ? ' · ' + esc(a.year) : ''}
          · ${(a.tracks || []).length} 首</p>
        <div class="row" style="gap:10px">
          <button class="btn primary" id="play-all" data-focus>${icon('play')}播放全部</button>
          <button class="btn" id="shuffle" data-focus>随机播放</button>
        </div>
      </div>
    </div>
    <div class="tracklist">
      ${(a.tracks || []).map((t, i) => `
        <div class="track ${playing && playing.id === t.id ? 'on' : ''}" data-focus data-ei="${i}">
          <span class="t-no">${t.track || i + 1}</span>
          <span class="t-name">${esc(t.title)}</span>
          <span class="t-artist">${esc(t.artist || '')}</span>
          <span class="t-dur">${t.duration ? fmtClock(t.duration) : '—'}</span>
        </div>`).join('')}
    </div>
  </div>`;

  root.querySelector('#play-all').onclick = () => {
    playAlbum(a, 0);
    toast('开始播放《' + a.title + '》');
  };
  root.querySelector('#shuffle').onclick = () => {
    playAlbum(a, Math.floor(Math.random() * a.tracks.length));
    toast('随机播放');
  };
  root.querySelector('.tracklist').onclick = e => {
    const t = e.target.closest('[data-ei]');
    if (!t) return;
    playAlbum(a, +t.dataset.ei);
  };
}

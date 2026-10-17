// 音乐：专辑墙 / 歌手 / 专辑详情 + 播放队列
import { api } from '../core/api.js';
import { esc, icon, toast, fmtClock, debounce } from '../core/dom.js';
import { playAlbum, nowPlayingTrack } from '../components/musicplayer.js';
import { emptyState } from '../components/ui.js';
import { go } from '../core/router.js';

let home = null;
let kw = '';

export function destroy() { home = null; kw = ''; }

export async function render(root, ctx) {
  const isAlbum = ctx.seg[1] === 'album';
  if (isAlbum) return renderAlbum(root, ctx.query.id);

  root.innerHTML = `<div class="page">
    <div class="page-head">
      <div><div class="eyebrow">音乐</div><h1 class="page-title">曲库</h1></div>
      <p class="page-sub" id="sum">正在连接音乐库…</p>
      <span class="spacer"></span>
      <input class="input" id="m-kw" placeholder="搜专辑或歌手" style="width:200px;height:38px">
    </div>
    <div id="mbody"><div class="album-grid">${Array(8).fill('<div class="skl" style="aspect-ratio:1;border-radius:14px"></div>').join('')}</div></div>
  </div>`;

  root.querySelector('#m-kw').addEventListener('input', debounce(e => {
    kw = e.target.value.trim();
    paint(root);
  }, 260));

  try {
    home = await api.musicHome();
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
    if (sum) sum.textContent = '未启用';
    body.innerHTML = emptyState({
      iconName: 'music', title: '还没接上音乐库',
      desc: home && home.msg ? home.msg : '设置里可以接 Navidrome（Subsonic 兼容）或直接扫 NAS 音乐目录。',
      action: '<a class="btn primary" href="#/settings/music" data-focus>去启用音乐</a>',
    });
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

  let albums = home.albums || [];
  if (kw) {
    const k = kw.toLowerCase();
    albums = albums.filter(a => (a.title || '').toLowerCase().includes(k) ||
                                 (a.artist || '').toLowerCase().includes(k));
  }
  if (sum) sum.textContent = `${home.total || (home.albums || []).length} 张专辑 · ` +
    (home.provider === 'local' ? '本地目录' : 'Subsonic');

  body.innerHTML = `
    <div class="music-hero">
      <div class="eyebrow">${home.provider === 'local' ? '本地曲库' : 'SUBSONIC'}</div>
      <h1>${esc(kw ? `“${kw}” 的结果` : '最近加入')}</h1>
      <p>${esc(home.root || '')}${home.provider === 'local' ? '' : ' · 由你的音乐服务提供'}</p>
    </div>
    ${albums.length ? `<div class="album-grid">${albums.map(a => `
      <div class="album" data-focus data-album="${esc(a.id)}">
        <div class="album-art">${a.cover ? `<img src="${esc(a.cover)}" alt="" loading="lazy">` :
          `<div class="ph">${esc((a.title || '?').slice(0, 1))}</div>`}
          <span class="a-play">${icon('play')}</span>
        </div>
        <p class="album-title">${esc(a.title)}</p>
        <p class="album-artist">${esc(a.artist || '')}${a.year ? ' · ' + esc(a.year) : ''}</p>
      </div>`).join('')}</div>` :
      emptyState({ iconName: 'music', title: '没有找到专辑', desc: '换个关键词，或确认目录里真的有音频文件。' })}
    ${(home.artists || []).length && !kw ? `<section class="section" style="margin-left:0">
      <div class="section-head" style="margin-left:0"><h2 class="section-title">歌手</h2></div>
      <div class="artist-grid">${home.artists.map(a => `
        <div class="artist" data-focus data-artist="${esc(a.name)}">
          <div class="av">${esc((a.name || '?').slice(0, 1))}</div>
          <div><div class="an">${esc(a.name)}</div><div class="ac">${a.count} 张专辑</div></div>
        </div>`).join('')}</div>
    </section>` : ''}`;

  body.onclick = e => {
    const alb = e.target.closest('[data-album]');
    if (alb) { go('/music/album?id=' + encodeURIComponent(alb.dataset.album)); return; }
    const art = e.target.closest('[data-artist]');
    if (art) {
      const input = root.querySelector('#m-kw');
      input.value = art.dataset.artist;
      kw = art.dataset.artist;
      paint(root);
    }
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

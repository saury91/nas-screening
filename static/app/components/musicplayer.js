// 音乐播放器：底部迷你条 + 全屏 Now Playing，队列/进度/媒体键
import { $, esc, icon, toast, fmtClock } from '../core/dom.js';
import { api } from '../core/api.js';
import { setFocus, clearFocus } from '../core/focus.js';

const audio = () => $('#audio');
let queue = [];
let idx = -1;
let playing = false;
let loading = false;
let npOpen = false;

function track() { return queue[idx] || null; }

function artHTML(t, cls = '') {
  if (t && t.cover) return `<img src="${esc(t.cover)}" alt="" class="${cls}">`;
  return `<div class="ph">${esc(((t && (t.album || t.title)) || '♪').slice(0, 1))}</div>`;
}

export function nowPlayingTrack() { return track(); }

export function playQueue(tracks, start = 0) {
  if (!tracks || !tracks.length) return;
  queue = tracks.slice();
  idx = Math.max(0, Math.min(start, queue.length - 1));
  load(true);
}

export function playAlbum(album, start = 0) {
  if (!album || !album.tracks || !album.tracks.length) {
    toast('这张专辑里没有可播放的音轨', 'err');
    return;
  }
  playQueue(album.tracks, start);
}

function load(autoplay) {
  const t = track();
  if (!t) return;
  loading = true;
  render();
  const src = t.provider === 'local'
    ? '/api/music/file?id=' + encodeURIComponent(t.id)
    : null;
  const use = async () => {
    try {
      let url = src;
      if (!url) {
        const d = await api.musicStream(t.id);
        url = d.url;
      }
      const a = audio();
      a.src = url;
      if (autoplay) {
        await a.play().catch(() => {
          playing = false;
          toast('浏览器拦了自动播放，点一下播放键', 'err');
        });
      }
      playing = !a.paused;
    } catch (e) {
      toast('这首放不了：' + e.message, 'err');
      playing = false;
    } finally {
      loading = false;
      render();
      updateMediaSession();
    }
  };
  use();
}

function toggle() {
  const a = audio();
  if (!track()) return;
  if (!a.src) { load(true); return; }
  if (a.paused) {
    a.play().then(() => { playing = true; render(); }).catch(() => {});
  } else {
    a.pause(); playing = false; render();
  }
}

function step(d) {
  if (!queue.length) return;
  idx = (idx + d + queue.length) % queue.length;
  load(true);
}

export function initMusicPlayer() {
  const a = audio();
  a.addEventListener('timeupdate', () => updateProgress());
  a.addEventListener('loadedmetadata', () => { updateProgress(); render(); });
  a.addEventListener('ended', () => step(1));
  a.addEventListener('play', () => { playing = true; render(); });
  a.addEventListener('pause', () => { playing = false; render(); });

  document.addEventListener('keydown', e => {
    if (e.code === 'Space' && npOpen) { e.preventDefault(); toggle(); }
    if (e.key === 'Escape' && npOpen) { e.preventDefault(); closeNowPlaying(); }
  });

  document.addEventListener('app:escape', e => {
    if (!npOpen || (e.detail && e.detail.handled)) return;
    closeNowPlaying();
    if (e.detail) e.detail.handled = true;
  });

  render();
}

function updateMediaSession() {
  const t = track();
  if (!t || !window.navigator.mediaSession) return;
  try {
    navigator.mediaSession.metadata = new window.MediaMetadata({
      title: t.title, artist: t.artist, album: t.album || '',
      artwork: t.cover ? [{ src: t.cover, sizes: '300x300' }] : [],
    });
    navigator.mediaSession.setActionHandler('play', () => toggle());
    navigator.mediaSession.setActionHandler('pause', () => toggle());
    navigator.mediaSession.setActionHandler('previoustrack', () => step(-1));
    navigator.mediaSession.setActionHandler('nexttrack', () => step(1));
  } catch (e) { /* 部分浏览器不支持，忽略 */ }
}

function updateProgress() {
  const a = audio();
  const pct = a.duration ? (a.currentTime / a.duration) * 100 : 0;
  const bar = $('#mp-progress i');
  if (bar) bar.style.width = pct + '%';
  const npbar = $('#np-progress i');
  if (npbar) npbar.style.width = pct + '%';
  const cur = $('#np-cur'), dur = $('#np-dur');
  if (cur) cur.textContent = fmtClock(a.currentTime);
  if (dur) dur.textContent = fmtClock(a.duration);
  const mt = $('#mp-time');
  if (mt) mt.textContent = fmtClock(a.currentTime);
}

function render() {
  const bar = $('#miniplayer');
  const t = track();
  if (!t) { bar.hidden = true; document.body.classList.remove('has-mini'); return; }
  bar.hidden = false;
  document.body.classList.add('has-mini');
  bar.innerHTML = `
    <div class="mp-art" id="mp-open">${artHTML(t)}</div>
    <div class="mp-info" id="mp-info">
      <div class="mp-title">${esc(t.title)}</div>
      <div class="mp-artist">${esc(t.artist || '')}${t.album ? ' · ' + esc(t.album) : ''}</div>
    </div>
    <div class="mp-bar" id="mp-progress"><i></i></div>
    <span class="pill mono" id="mp-time" style="border:0;background:none">0:00</span>
    <div class="mp-actions">
      <button class="icon-btn" id="mp-prev" data-focus title="上一首">${icon('prev')}</button>
      <button class="icon-btn" id="mp-play" data-focus title="播放/暂停">${icon(playing ? 'pause' : 'play')}</button>
      <button class="icon-btn" id="mp-next" data-focus title="下一首">${icon('next')}</button>
      <button class="icon-btn" id="mp-close" data-focus title="收起">${icon('close')}</button>
    </div>`;
  $('#mp-play').onclick = toggle;
  $('#mp-prev').onclick = () => step(-1);
  $('#mp-next').onclick = () => step(1);
  $('#mp-close').onclick = () => { audio().pause(); queue = []; idx = -1; render(); };
  $('#mp-open').onclick = openNowPlaying;
  $('#mp-info').onclick = openNowPlaying;
  $('#mp-progress').onclick = seekByEvent;
  updateProgress();
}

function seekByEvent(e) {
  const a = audio();
  if (!a.duration) return;
  const r = e.currentTarget.getBoundingClientRect();
  a.currentTime = ((e.clientX - r.left) / r.width) * a.duration;
}

function openNowPlaying() {
  const t = track();
  if (!t) return;
  npOpen = true;
  document.body.classList.add('locked');
  const root = $('#miniplayer');
  const box = document.createElement('div');
  box.className = 'now-playing';
  box.id = 'np';
  box.innerHTML = `
    <div class="np-bg" style="${t.cover ? `background-image:url('${esc(t.cover)}')` : ''}"></div>
    <div class="np-inner">
      <div class="np-art">${artHTML(t)}</div>
      <div class="np-side">
        <div class="eyebrow">正在播放</div>
        <h2>${esc(t.title)}</h2>
        <div class="np-artist">${esc(t.artist || '')}${t.album ? ' · ' + esc(t.album) : ''}</div>
        <div class="np-progress" id="np-progress"><i></i></div>
        <div class="np-time"><span id="np-cur">0:00</span><span id="np-dur">0:00</span></div>
        <div class="np-controls">
          <button class="icon-btn" id="np-prev" data-focus>${icon('prev')}</button>
          <button class="big" id="np-play" data-focus>${icon(playing ? 'pause' : 'play')}</button>
          <button class="icon-btn" id="np-next" data-focus>${icon('next')}</button>
          <button class="icon-btn" id="np-min" data-focus style="margin-left:auto">${icon('close')}</button>
        </div>
      </div>
    </div>`;
  document.body.appendChild(box);
  box.setAttribute('data-focus-scope', '');
  $('#np-play').onclick = () => { toggle(); $('#np-play').innerHTML = icon(playing ? 'pause' : 'play'); };
  $('#np-prev').onclick = () => step(-1);
  $('#np-next').onclick = () => step(1);
  $('#np-min').onclick = closeNowPlaying;
  $('#np-progress').onclick = seekByEvent;
  updateProgress();
  setFocus($('#np-play'), false);
}

function closeNowPlaying() {
  npOpen = false;
  $('#np')?.remove();
  // 迷你条仍然存在，回到页面不需要锁滚动
  document.body.classList.remove('locked');
  clearFocus();
}

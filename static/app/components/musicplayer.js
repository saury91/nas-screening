// 音乐播放器：底部迷你条 + 全屏 Now Playing
// 覆盖：进度拖动、播放模式、队列、收藏、歌词、旋转封面、10 段均衡器
import { $, esc, icon, toast, fmtClock } from '../core/dom.js';
import { api } from '../core/api.js';
import { setFocus, clearFocus } from '../core/focus.js';

const audio = () => $('#audio');

let queue = [];
let idx = -1;
let playing = false;
let loading = false;
let blocked = false;           // 被浏览器拦了自动播放，等下一次点击
let npOpen = false;
let dragging = false;          // 拖动进度条时不接受 timeupdate 反写
let lyr = { id: '', lines: [], at: -1 };
let mode = localStorage.getItem('mp.mode') || 'order';   // order | one | shuffle
// 注意：localStorage 里没有这个键时 getItem 返回 null，Number(null) === 0，
// 后面 0 又是合法音量，结果就是「新用户第一次播放永远静音」。
// 必须区分「没存过」和「存了 0」。
const _savedVol = localStorage.getItem('mp.vol');
let vol = (_savedVol === null || _savedVol === '') ? 0.9 : Number(_savedVol);
if (!(vol >= 0 && vol <= 1)) vol = 0.9;
let muted = false;
let favSet = new Set();
let queueOpen = false;
let eqOpen = false;
let eqPreset = localStorage.getItem('mp.eq') || '平直';
let eqGains = [];              // 自定义增益（dB），来自预设或手动拖动
let loudness = localStorage.getItem('mp.loud') === '1';
let ctx = null, preamp = null, comp = null, filters = [];
let lastPick = -1;             // 随机模式避免连续重复

const MODES = [
  ['order', 'repeat', '顺序播放'],
  ['one', 'repeatOne', '单曲循环'],
  ['shuffle', 'shuffle', '随机播放'],
];

// ── 均衡器（Web Audio，无第三方依赖）──────────────────────────
const EQ_BANDS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
// 预设取自常见的 10 段均衡器曲线（各段增益 dB）
const EQ_PRESETS = {
  '平直': [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  '流行': [-1, 0, 2, 3.5, 3.5, 2, 0, -1, -1.5, -2],
  '摇滚': [5, 4, 2.5, 0, -1, -0.5, 1.5, 3, 4.5, 5],
  '爵士': [3.5, 2.5, 1, 2, -1, -1.5, 0, 1.5, 3, 4],
  '古典': [4.5, 3.5, 2, 0, -1, -1, 0, 2, 3.5, 4.5],
  '人声': [-2.5, -3, -2, 0.5, 3, 4, 4, 2, 0, -1],
  '低音': [7, 6, 4.5, 2.5, 0.5, 0, 0, 0, 0, 0],
  '夜听': [2.5, 1.5, 0, -1, -2, -1.5, 0, 1, 1.5, 2],
};
// 上次选的预设要在启动时还原，否则面板显示「平直」实际却是别的曲线
eqGains = (EQ_PRESETS[eqPreset] || EQ_PRESETS['平直']).slice();

function ensureGraph() {
  if (ctx) {
    if (ctx.state === 'suspended') ctx.resume().catch(() => {});
    return ctx;
  }
  const AC = window.AudioContext || window.webkitAudioContext;
  if (!AC) return null;
  // 非手势上下文里建出来的 AudioContext 一定是 suspended，
  // 这时候接了 MediaElementSource 就等于把声音掐了。宁可这次先不生效。
  if (navigator.userActivation && !navigator.userActivation.isActive) return null;
  try {
    const a = audio();
    ctx = new AC();
    const src = ctx.createMediaElementSource(a);
    preamp = ctx.createGain();
    filters = EQ_BANDS.map(f => {
      const b = ctx.createBiquadFilter();
      b.type = 'peaking';
      b.frequency.value = f;
      b.Q.value = 1.1;
      b.gain.value = 0;
      return b;
    });
    // src → preamp → 10 段 → 压缩（响度均衡）→ 输出
    let node = src.connect(preamp);
    filters.forEach(f => { node.connect(f); node = f; });
    comp = ctx.createDynamicsCompressor();
    node.connect(comp);
    comp.connect(ctx.destination);
    applyLoudness();
    // 建完就 resume：一旦接了 MediaElementSource，声音只能从这条图里出来，
    // context 停在 suspended 就等于静音。
    ctx.resume?.().catch(() => {});
    applyEq();
  } catch (e) {
    ctx = null;      // 建不起来就退回纯 <audio>，不影响播放
  }
  return ctx;
}

function applyEq() {
  if (!ctx) return;
  const g = eqGains.length === EQ_BANDS.length ? eqGains : EQ_PRESETS['平直'];
  filters.forEach((f, i) => f.gain.setTargetAtTime(Number(g[i]) || 0, ctx.currentTime, 0.02));
}

// 响度均衡：不同平台音量差得离谱，用一个温和的压缩器把响的压一点、轻的抬一点。
// 关掉时把阈值放到 0dB、ratio=1，等于旁路，不用拆图。
function applyLoudness() {
  if (!ctx || !comp) return;
  const t = ctx.currentTime;
  comp.threshold.setTargetAtTime(loudness ? -26 : 0, t, 0.03);
  comp.knee.setTargetAtTime(loudness ? 24 : 0, t, 0.03);
  comp.ratio.setTargetAtTime(loudness ? 4 : 1, t, 0.03);
  comp.attack.setTargetAtTime(loudness ? 0.006 : 0.003, t, 0.03);
  comp.release.setTargetAtTime(loudness ? 0.22 : 0.25, t, 0.03);
}

function toggleLoudness() {
  loudness = !loudness;
  localStorage.setItem('mp.loud', loudness ? '1' : '0');
  ensureGraph();
  applyLoudness();
  renderEq();
}

function usePreset(name) {
  eqPreset = name;
  eqGains = (EQ_PRESETS[name] || EQ_PRESETS['平直']).slice();
  localStorage.setItem('mp.eq', name);
  ensureGraph();       // 点预设是明确手势，这里建图才不会挂起
  applyEq();
  renderEq();
}

// ── 工具 ──────────────────────────────────────────────────
function track() { return queue[idx] || null; }
function isFav(t) { return !!t && favSet.has(String(t.id)); }

function artHTML(t, cls = '') {
  if (t && t.cover) return `<img src="${esc(t.cover)}" alt="" class="${cls}">`;
  return `<div class="ph">${esc(((t && (t.album || t.title)) || '♪').slice(0, 1))}</div>`;
}

function total(t) {
  const a = audio();
  // 以真实音频时长为准：搜索结果的标称时长对试听片段是错的（标 4:16 实际 11s），
  // 拿它算进度会让拖动条直接跳到结尾。
  if (a && isFinite(a.duration) && a.duration > 0) return a.duration;
  return (t && t.duration) || 0;
}

export function nowPlayingTrack() { return track(); }

export function playQueue(tracks, start = 0) {
  if (!tracks || !tracks.length) return;
  queue = tracks.slice();
  idx = Math.max(0, Math.min(start, queue.length - 1));
  lastPick = idx;
  load(true);
}

export function playAlbum(album, start = 0) {
  if (!album || !album.tracks || !album.tracks.length) {
    toast('这张专辑里没有可播放的音轨', 'err');
    return;
  }
  playQueue(album.tracks, start);
}

export async function refreshFavorites() {
  try {
    const d = await api.library('music');
    favSet = new Set((d.items || []).map(t => String(t.id)));
  } catch (e) {
    favSet = new Set();
  }
  render();
  renderNowPlaying();
}

// ── 播放 ──────────────────────────────────────────────────
// ── 播放：应付浏览器的自动播放策略 ────────────────────────────
// 1) 正常 play()
// 2) 被拦（NotAllowedError）时先静音起播、再解除静音——Chrome 允许静音自动播放
// 3) 还不行就挂起，等用户下一次点击/按键再续上（不弹那种会消失的 toast 就算了，
//    迷你条上会留一个「点一下继续播放」的提示）
async function tryPlay(a) {
  if (!a || !a.src) return false;
  try {
    await a.play();
    blocked = false;
    return true;
  } catch (e) {
    const name = (e && e.name) || '';
    if (name === 'NotAllowedError' || name === 'SecurityError') {
      try {
        a.muted = true;
        await a.play();
        a.muted = muted;          // 恢复用户设定的静音状态
        blocked = false;
        return true;
      } catch (_) {
        a.muted = muted;
      }
      blocked = true;
      armUnlock();
      return false;
    }
    blocked = false;
    return false;
  }
}

let unlockArmed = false;
function armUnlock() {
  if (unlockArmed) return;
  unlockArmed = true;
  const kick = () => {
    unlockArmed = false;
    document.removeEventListener('pointerdown', kick, true);
    document.removeEventListener('keydown', kick, true);
    if (ctx && ctx.state !== 'running') ctx.resume().catch(() => {});
    const a = audio();
    if (!blocked || !a || !a.src) return;
    a.play().then(() => {
      blocked = false;
      playing = true;
      render();
      renderNowPlaying();
    }).catch(() => {});
  };
  document.addEventListener('pointerdown', kick, true);
  document.addEventListener('keydown', kick, true);
}

function load(autoplay) {
  const t = track();
  if (!t) return;
  loading = true;
  blocked = false;
  lyr = { id: '', lines: [], at: -1 };
  render();
  renderNowPlaying();

  const src = t.provider === 'local'
    ? '/api/music/file?id=' + encodeURIComponent(t.id)
    : (t.url || '');

  const use = async () => {
    try {
      let url = src;
      if (!url) {
        // 在线音源要现取一次播放地址（地址带时效）
        const d = (t.provider === 'online' || t.provider === 'netease')
          ? await api.musicOnlineUrl(t.id, t.provider)
          : await api.musicStream(t.id);
        url = d.url;
      }
      const a = audio();
      a.src = url;
      // 注意：这里**不要**建音频图。createMediaElementSource 一旦调用，
      // 声音就只从 AudioContext 走；而 Chrome 的自动播放策略会把
      // 非手势上下文创建的 AudioContext 挂起，结果就是「时间在走、没有声音」。
      // 音频图只在用户点开音效面板时才建（那是明确的点击手势）。
      if (autoplay) {
        const ok = await tryPlay(a);
        if (!ok) {
          playing = false;
          toast('浏览器拦了自动播放，点一下播放键', 'err');
          render();
        }
      }
      playing = !a.paused;
    } catch (e) {
      toast('这首放不了：' + e.message, 'err');
      playing = false;
    } finally {
      loading = false;
      render();
      renderNowPlaying();
      updateMediaSession();
      loadLyrics(t);
    }
  };
  use();
}

function toggle() {
  const a = audio();
  if (!track()) return;
  if (!a.src) { load(true); return; }
  if (a.paused) {
    tryPlay(a).then(ok => {
      playing = ok && !a.paused;
      render();
      renderNowPlaying();
    });
  } else {
    a.pause(); playing = false; render();
  }
}

function pickNext(d) {
  if (!queue.length) return -1;
  if (mode === 'shuffle' && queue.length > 1) {
    let n = idx;
    for (let i = 0; i < 8 && n === idx; i++) n = Math.floor(Math.random() * queue.length);
    return n;
  }
  return (idx + d + queue.length) % queue.length;
}

function step(d) {
  if (!queue.length) return;
  idx = pickNext(d);
  lastPick = idx;
  load(true);
}

function onEnded() {
  if (mode === 'one') {
    const a = audio();
    a.currentTime = 0;
    a.play().catch(() => {});
    return;
  }
  step(1);
}

function seek(ratio) {
  const a = audio();
  const d = total(track());
  if (!d || !isFinite(d)) return;
  a.currentTime = Math.max(0, Math.min(d - 0.15, ratio * d));
  paintProgress();
}

// 进度条：点击 + 拖动（Pointer 事件，鼠标/触屏/触控笔通用）
function bindSeek(el) {
  if (!el) return;
  const ratioOf = e => {
    const r = el.getBoundingClientRect();
    return Math.min(1, Math.max(0, (e.clientX - r.left) / r.width));
  };
  el.addEventListener('pointerdown', e => {
    if (e.button) return;
    e.preventDefault();
    try { el.setPointerCapture?.(e.pointerId); } catch (_) { /* 合成事件没有真实 pointerId */ }
    el.classList.add('grab');
    dragging = true;
    seek(ratioOf(e));
    const move = ev => { if (dragging) seek(ratioOf(ev)); };
    const up = ev => {
      if (!dragging) return;
      seek(ratioOf(ev));
      dragging = false;
      el.classList.remove('grab');
      el.removeEventListener('pointermove', move);
      el.removeEventListener('pointerup', up);
      el.removeEventListener('pointercancel', up);
    };
    el.addEventListener('pointermove', move);
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', up);
  });
}

// ── 歌词 ──────────────────────────────────────────────────
async function loadLyrics(t) {
  if (!t) return;
  const key = String(t.id);
  if (lyr.id === key) return;
  lyr = { id: key, lines: [], at: -1 };
  renderLyrics();
  if (t.provider === 'local') { renderLyrics(); return; }
  try {
    const d = await api.musicLyrics(t.id, t.provider === 'netease' ? 'netease' : t.provider,
                                    t.title, t.artist);
    if (lyr.id !== key) return;      // 期间切歌了
    lyr.lines = d.lines || [];
  } catch (e) {
    lyr.lines = [];
  }
  renderLyrics();
  syncLyrics(audio().currentTime, true);
}

function activeLine(sec) {
  const L = lyr.lines;
  if (!L.length) return -1;
  let lo = 0, hi = L.length - 1, ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (L[mid].t <= sec + 0.12) { ans = mid; lo = mid + 1; } else { hi = mid - 1; }
  }
  return ans;
}

function syncLyrics(sec, force) {
  if (!npOpen || !lyr.lines.length) return;
  const i = activeLine(sec);
  if (i === lyr.at && !force) return;
  lyr.at = i;
  const box = $('#np-lyrics');
  if (!box) return;
  const rows = box.querySelectorAll('.ly-line');
  rows.forEach((r, n) => r.classList.toggle('on', n === i));
  const cur = rows[i];
  if (cur && !dragging && !box.dataset.userScroll) {
    box.scrollTo({ top: cur.offsetTop - box.clientHeight / 2 + cur.offsetHeight / 2, behavior: 'smooth' });
  }
}

function renderLyrics() {
  const box = $('#np-lyrics');
  if (!box) return;
  if (!lyr.lines.length) {
    box.innerHTML = `<p class="ly-empty">${lyr.id ? '这首歌没有歌词' : '歌词加载中…'}</p>`;
    return;
  }
  box.innerHTML = lyr.lines.map((l, i) => `
    <p class="ly-line${i === lyr.at ? ' on' : ''}" data-lt="${l.t}">${esc(l.text).replace(/\n/g, '<br>')}</p>`).join('');
  box.querySelectorAll('.ly-line').forEach(el => {
    el.onclick = () => {
      const a = audio();
      a.currentTime = Number(el.dataset.lt) || 0;
      paintProgress();
    };
  });
  syncLyrics(audio().currentTime, true);
}

// ── 收藏 ──────────────────────────────────────────────────
async function toggleFav() {
  const t = track();
  if (!t) return;
  const on = !isFav(t);
  try {
    await api.fav({
      kind: 'music', key: String(t.id), title: t.title,
      cover: t.cover || '', payload: JSON.stringify(t),
    });
    if (on) favSet.add(String(t.id)); else favSet.delete(String(t.id));
    toast(on ? '已加入收藏' : '已取消收藏');
    render();
    renderNowPlaying();
    // 通知页面把「我的收藏」货架重画一遍
    document.dispatchEvent(new CustomEvent('app:music-fav'));
  } catch (e) {
    toast('收藏失败：' + e.message, 'err');
  }
}

// ── 初始化 ────────────────────────────────────────────────
export function initMusicPlayer() {
  const a = audio();
  a.volume = vol;
  a.addEventListener('timeupdate', () => {
    paintProgress();
    syncLyrics(a.currentTime);
  });
  a.addEventListener('loadedmetadata', () => {
    paintProgress();
    render();
    renderNowPlaying();
    const t = track();
    if (t && t.provider === 'online' && t.duration && a.duration &&
        a.duration < t.duration * 0.8) {
      toast('这首只有试听片段，换一首试试', 'err');
    }
  });
  a.addEventListener('ended', onEnded);
  a.addEventListener('play', () => {
    if (ctx && ctx.state === 'suspended') ctx.resume().catch(() => {});
    blocked = false;
    playing = true; render(); renderNowPlaying();
    fxStart();
  });
  a.addEventListener('pause', () => { playing = false; render(); renderNowPlaying(); });
  a.addEventListener('waiting', () => { loading = true; render(); });
  a.addEventListener('playing', () => { loading = false; render(); });

  document.addEventListener('keydown', e => {
    if (!npOpen) return;
    if (e.code === 'Space') { e.preventDefault(); toggle(); }
    if (e.key === 'Escape') { e.preventDefault(); closeNowPlaying(); }
    // 方向键被焦点导航占着（遥控器要用来选按钮），快进用 Shift+左右，不抢键
    if (e.shiftKey && (e.key === 'ArrowRight' || e.key === 'ArrowLeft')) {
      e.preventDefault();
      const d = total(track()) || 0;
      if (d) seek(Math.min(1, Math.max(0, (a.currentTime + (e.key === 'ArrowRight' ? 5 : -5)) / d)));
    }
  });

  document.addEventListener('app:escape', e => {
    if (!npOpen || (e.detail && e.detail.handled)) return;
    closeNowPlaying();
    if (e.detail) e.detail.handled = true;
  });

  refreshFavorites();
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

function paintProgress() {
  const a = audio();
  const d = total(track());
  const ratio = d && isFinite(d) ? Math.min(1, a.currentTime / d) : 0;
  const pct = (ratio * 100).toFixed(2) + '%';
  ['#mp-progress i', '#np-progress i'].forEach(sel => {
    const el = $(sel);
    if (el) el.style.width = pct;
  });
  const mt = $('#mp-time');
  if (mt) mt.textContent = fmtClock(a.currentTime) + (d ? ' / ' + fmtClock(d) : '');
  const cur = $('#np-cur'), dur = $('#np-dur');
  if (cur) cur.textContent = fmtClock(a.currentTime);
  if (dur) dur.textContent = d ? fmtClock(d) : '--:--';
}

// ── 进度条粒子：播放时从播放头飘出音符与星光 ──────────────
// 纯 canvas，不引第三方。播放中才生成；暂停后已生成的会飘完自然消失。
const FX_GLYPHS = ['♪', '♫', '♩'];   // 只留音符一族，星星混进去会显得杂
const FX_PAD_X = 44;                  // 画布比进度条左右各宽 44px，给粒子横向漂移留余量
// 字形先画到离屏 canvas（带辉光），运行时只做 drawImage。
// 直接在每帧给每个粒子设 shadowBlur，阴影是高消耗的逐像素模糊，
// 粒子一多就掉帧——实测 90 粒子时出过 380ms 的长帧，看起来就是「卡一下」。
const FX_SPRITES = {};
function fxSprite(g) {
  if (FX_SPRITES[g]) return FX_SPRITES[g];
  // 画布要明显大于字形：辉光要留够半径，否则会被精灵边界裁成硬边方块
  const S = 192;
  const c = document.createElement('canvas');
  c.width = c.height = S;
  const x = c.getContext('2d');
  x.textAlign = 'center';
  x.textBaseline = 'middle';
  x.font = '90px "Noto Sans SC", system-ui, sans-serif';
  x.shadowColor = 'rgba(255,207,122,.8)';
  x.shadowBlur = 18;
  x.fillStyle = '#ffd894';
  x.fillText(g, S / 2, S / 2);
  x.shadowBlur = 0;
  x.fillText(g, S / 2, S / 2);
  FX_SPRITES[g] = c;
  return c;
}
for (const g of FX_GLYPHS) { try { fxSprite(g); } catch (e) { /* 生成失败就退化 */ } }
const FX_REDUCE = window.matchMedia
  ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
let fx = null;

function fxEnsure() {
  const bar = document.getElementById('np-progress');
  const cv = bar && bar.querySelector('#np-fx');
  if (!cv || !cv.getContext) return null;
  if (fx && fx.cv === cv) return fx;
  fx = { cv, cx: cv.getContext('2d'), parts: [], raf: 0, last: 0, w: 0, h: 0, acc: 0 };
  return fx;
}

function fxSize(f) {
  const dpr = Math.min(1.5, window.devicePixelRatio || 1);   // 省填充率，2x 看不出差别
  const w = f.cv.clientWidth, h = f.cv.clientHeight;
  if (!w || !h) return false;
  if (w !== f.w || h !== f.h) {
    f.w = w; f.h = h;
    f.cv.width = Math.round(w * dpr);
    f.cv.height = Math.round(h * dpr);
    f.cx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  return true;
}

function fxSpawn(f) {
  const a = audio();
  const d = total(track());
  const ratio = d ? Math.min(1, a.currentTime / d) : 0;
  const barW = Math.max(40, f.w - FX_PAD_X * 2);
  f.parts.push({
    x: FX_PAD_X + ratio * barW + (Math.random() - 0.5) * 36,
    y: f.h - 2 + (Math.random() - 0.5) * 6,
    vx: (Math.random() - 0.35) * 46,          // 略微偏右，像被风带走
    vy: -(46 + Math.random() * 78),
    t: 0,
    ttl: 1.5 + Math.random() * 1.2,
    // 偶尔来一颗大一点的星，别让整片看起来一样大
    size: Math.random() < 0.14 ? 16 + Math.random() * 10 : 9 + Math.random() * 8,
    rot: (Math.random() - 0.5) * 0.7,
    spin: (Math.random() - 0.5) * 1.6,
    g: FX_GLYPHS[Math.floor(Math.random() * FX_GLYPHS.length)],
  });
  if (f.parts.length > 64) f.parts.splice(0, f.parts.length - 64);
}

function fxLoop(ts) {
  const f = fx;
  if (!f) return;
  if (!npOpen) { f.raf = 0; return; }            // 播放页关了，停帧
  if (!fxSize(f)) { f.raf = requestAnimationFrame(fxLoop); return; }

  const dt = f.last ? Math.min(0.05, (ts - f.last) / 1000) : 0.016;
  f.last = ts;

  if (playing && !(FX_REDUCE && FX_REDUCE.matches)) {
    f.acc += dt;
    while (f.acc >= 0.075) { f.acc -= 0.075; fxSpawn(f); }
  }

  f.cx.clearRect(0, 0, f.w, f.h);
  for (let i = f.parts.length - 1; i >= 0; i--) {
    const p = f.parts[i];
    p.t += dt;
    if (p.t >= p.ttl) { f.parts.splice(i, 1); continue; }
    p.x += p.vx * dt;
    p.y += p.vy * dt;
    p.vy *= 1 - 0.55 * dt;                       // 上升逐渐减速
    p.vx += Math.sin(p.t * 2.6 + p.rot * 4) * 12 * dt;  // 左右轻摆
    p.rot += p.spin * dt;
    const k = p.t / p.ttl;
    let alpha = Math.sin(Math.PI * Math.min(1, k * 1.1));
    // 贴近画布边界时线性淡出，否则会在边缘形成一条硬切线
    const fadeTop = Math.min(1, p.y / 18);
    const fadeX = Math.min(1, Math.min(p.x, f.w - p.x) / 18);
    alpha *= Math.max(0, Math.min(fadeTop, fadeX));
    const spr = FX_SPRITES[p.g];
    f.cx.save();
    f.cx.translate(p.x, p.y);
    f.cx.rotate(p.rot);
    f.cx.globalAlpha = Math.max(0, alpha) * 0.95;
    if (spr) {
      const s = p.size * 3.1;       // 192px 精灵里字形约占 90px，3.1 倍 ≈ 视觉字号×1.45
      f.cx.drawImage(spr, -s / 2, -s / 2, s, s);
    } else {
      f.cx.fillStyle = '#ffcf7a';
      f.cx.font = p.size + 'px system-ui, sans-serif';
      f.cx.textAlign = 'center';
      f.cx.textBaseline = 'middle';
      f.cx.fillText(p.g, 0, 0);
    }
    f.cx.restore();
  }

  if (npOpen && (playing || f.parts.length)) {
    f.raf = requestAnimationFrame(fxLoop);
  } else {
    f.raf = 0; f.last = 0;
  }
}

function fxSeed(f, n) {
  for (let i = 0; i < n; i++) {
    fxSpawn(f);
    const p = f.parts[f.parts.length - 1];
    if (!p) continue;
    // 把新粒子往前推一段，看上去是「本来就在飘」，而不是从 0 开始长
    const age = Math.random() * p.ttl * 0.7;
    p.t = age;
    p.y += p.vy * age;
    p.x += p.vx * age;
  }
}

function fxStart() {
  if (!npOpen) return;
  const f = fxEnsure();
  if (!f) return;
  if (f.raf) return;
  f.last = 0;
  if (f.parts.length < 8) fxSeed(f, 8);
  f.raf = requestAnimationFrame(fxLoop);
}

function fxStop() {
  if (fx && fx.raf) cancelAnimationFrame(fx.raf);
  if (fx) { fx.raf = 0; fx.last = 0; fx.parts.length = 0; }
  fx = null;
}

function modeBtn() {
  const m = MODES.find(x => x[0] === mode) || MODES[0];
  return `<button class="icon-btn" data-act="mode" data-focus title="${m[2]}">${icon(m[1])}</button>`;
}

// ── 迷你条 ────────────────────────────────────────────────
function render() {
  const bar = $('#miniplayer');
  const t = track();
  if (!bar) return;
  if (!t) {
    bar.hidden = true;
    document.body.classList.remove('has-mini');
    closeNowPlaying();
    return;
  }
  bar.hidden = false;
  document.body.classList.add('has-mini');
  bar.innerHTML = `
    <div class="mp-art" data-act="open" title="打开播放页">
      ${artHTML(t)}<span class="mp-expand">${icon('expand')}</span>
    </div>
    <div class="mp-info" data-act="open" title="打开播放页">
      <div class="mp-title">${esc(t.title)}</div>
      <div class="mp-artist">${esc(t.artist || '')}${t.album ? ' · ' + esc(t.album) : ''}</div>
    </div>
    <div class="mp-mid">
      <div class="mp-bar" id="mp-progress" title="点击或拖动调整进度"><i></i></div>
      <span class="mp-time" id="mp-time">0:00${total(t) ? ' / ' + fmtClock(total(t)) : ''}</span>
      ${blocked ? '<span class="mp-hint">浏览器拦了自动播放，点 ▶ 继续</span>' : ''}
    </div>
    <div class="mp-actions">
      ${modeBtn()}
      <button class="icon-btn ${isFav(t) ? 'on' : ''}" data-act="fav" data-focus
              title="${isFav(t) ? '取消收藏' : '收藏'}">${icon('heart')}</button>
      <button class="icon-btn" data-act="queue" data-focus title="播放列表">${icon('queue')}</button>
      <span class="mp-sep"></span>
      <button class="icon-btn" data-act="prev" data-focus title="上一首">${icon('prev')}</button>
      <button class="icon-btn play${blocked ? ' need-play' : ''}" data-act="toggle" data-focus
              title="${blocked ? '点一下继续播放' : (playing ? '暂停' : '播放')}">${icon(playing ? 'pause' : 'play')}</button>
      <button class="icon-btn" data-act="next" data-focus title="下一首">${icon('next')}</button>
      <span class="mp-sep"></span>
      <button class="icon-btn" data-act="eq" data-focus title="音效">${icon('eq')}</button>
      <button class="icon-btn" data-act="close" data-focus title="停止并收起">${icon('close')}</button>
    </div>`;
  bar.onclick = e => {
    const b = e.target.closest('[data-act]');
    if (!b) {
      // 除按钮和进度条以外的任意位置都进播放页（以前只有封面和歌名能点，很难发现）
      if (e.target.closest('.mp-actions') || e.target.closest('#mp-progress')) return;
      return openNowPlaying();
    }
    const act = b.dataset.act;
    if (act === 'open') return openNowPlaying();
    if (act === 'toggle') return toggle();
    if (act === 'prev') return step(-1);
    if (act === 'next') return step(1);
    if (act === 'fav') return toggleFav();
    if (act === 'mode') return cycleMode();
    if (act === 'queue') return openNowPlaying('queue');
    if (act === 'eq') return openNowPlaying('eq');
    if (act === 'close') return stopAll();
  };
  bindSeek($('#mp-progress'));
  paintProgress();
}

function cycleMode() {
  const i = MODES.findIndex(m => m[0] === mode);
  mode = MODES[(i + 1) % MODES.length][0];
  localStorage.setItem('mp.mode', mode);
  toast('播放模式：' + (MODES.find(m => m[0] === mode) || MODES[0])[2]);
  render();
  renderNowPlaying();
}

function stopAll() {
  const a = audio();
  a.pause();
  a.removeAttribute('src');
  a.load();
  queue = [];
  idx = -1;
  playing = false;
  blocked = false;
  closeNowPlaying();
  render();
}

// ── 全屏 Now Playing ──────────────────────────────────────
function openNowPlaying(panel) {
  const t = track();
  if (!t) return;
  if (npOpen) {
    if (panel) showPanel(panel);
    return;
  }
  npOpen = true;
  document.body.classList.add('locked');
  const box = document.createElement('div');
  box.className = 'now-playing';
  box.id = 'np';
  box.setAttribute('data-focus-scope', '');
  box.innerHTML = `
    <div class="np-bg" style="${t.cover ? `background-image:url('${esc(t.cover)}')` : ''}"></div>
    <div class="np-wrap">
      <button class="np-close" data-act="min" data-focus title="收起播放页">
        ${icon('chevronDown')}<span>收起</span>
      </button>
      <div class="np-main">
        <div class="np-grid">
          <div class="np-left">
            <div class="np-disc${playing ? ' spin' : ''}" id="np-disc">
              <div class="np-disc-art">${artHTML(t)}</div>
              <span class="np-hole"></span>
            </div>
            <div class="np-meta">
              <h2 id="np-title">${esc(t.title)}</h2>
              <p class="np-artist">${esc(t.artist || '')}${t.album ? ' · ' + esc(t.album) : ''}</p>
            </div>
          </div>
          <div class="np-right">
            <div class="np-lyrics" id="np-lyrics"></div>
          </div>
        </div>
        <div class="np-foot">
          <div class="np-progress" id="np-progress" title="点击或拖动调整进度">
            <canvas class="np-fx" id="np-fx" aria-hidden="true"></canvas><i></i>
          </div>
          <div class="np-time"><span id="np-cur">0:00</span><span id="np-dur">--:--</span></div>
          <div class="np-controls">
            <button class="icon-btn" data-act="mode" data-focus title="播放模式">${icon(MODES.find(m => m[0] === mode)[1])}</button>
            <button class="icon-btn" data-act="prev" data-focus title="上一首">${icon('prev')}</button>
            <button class="big" data-act="toggle" data-focus>${icon(playing ? 'pause' : 'play')}</button>
            <button class="icon-btn" data-act="next" data-focus title="下一首">${icon('next')}</button>
            <button class="icon-btn ${isFav(t) ? 'on' : ''}" data-act="fav" data-focus title="收藏">${icon('heart')}</button>
            <div class="np-vol">
              <button class="icon-btn" data-act="mute" data-focus title="静音">${icon((muted || vol === 0) ? 'volumeMute' : 'volume')}</button>
              <input type="range" id="np-vol" min="0" max="100" value="${Math.round(vol * 100)}"
                     aria-label="音量">
            </div>
            <button class="icon-btn" data-act="eq" data-focus title="音效">${icon('eq')}</button>
            <button class="icon-btn" data-act="queue" data-focus title="播放列表">${icon('queue')}</button>
          </div>
        </div>
      </div>
      <aside class="np-side" id="np-side"></aside>
    </div>`;
  document.body.appendChild(box);

  box.onclick = e => {
    const b = e.target.closest('[data-act]');
    if (!b) return;
    const act = b.dataset.act;
    if (act === 'min') return closeNowPlaying();
    if (act === 'toggle') return toggle();
    if (act === 'prev') return step(-1);
    if (act === 'next') return step(1);
    if (act === 'fav') return toggleFav();
    if (act === 'mode') return cycleMode();
    if (act === 'queue') return showPanel('queue');
    if (act === 'eq') return showPanel('eq');
    if (act === 'mute') {
      muted = !muted;
      audio().muted = muted;
      if (preamp) preamp.gain.value = muted ? 0 : 1;
      return renderNowPlaying();
    }
  };
  const volEl = box.querySelector('#np-vol');
  volEl.addEventListener('input', () => {
    vol = Number(volEl.value) / 100;
    localStorage.setItem('mp.vol', String(vol));
    audio().volume = vol;
    muted = false;
    audio().muted = false;
    if (preamp) preamp.gain.value = 1;
  });

  bindSeek($('#np-progress'));
  renderNowPlaying();
  renderLyrics();
  paintProgress();
  // 宽屏把队列常驻成右栏（参考图那种「封面｜歌词｜队列」），窄屏才收成抽屉
  if (panel) showPanel(panel);
  else if (window.innerWidth >= 1100) showPanel('queue');
  if (playing) fxStart();
  setFocus(box.querySelector('[data-act="toggle"]'), false);
}

function renderNowPlaying() {
  const box = $('#np');
  if (!box) return;
  const t = track();
  if (!t) return;
  const title = box.querySelector('#np-title');
  const artist = box.querySelector('.np-artist');
  if (title) title.textContent = t.title;
  if (artist) artist.textContent = (t.artist || '') + (t.album ? ' · ' + t.album : '');
  const art = box.querySelector('.np-disc-art');
  if (art) art.innerHTML = artHTML(t);
  const bg = box.querySelector('.np-bg');
  if (bg) bg.style.backgroundImage = t.cover ? `url('${esc(t.cover)}')` : 'none';
  const disc = box.querySelector('#np-disc');
  if (disc) disc.classList.toggle('spin', playing && !loading);
  const play = box.querySelector('[data-act="toggle"]');
  if (play) {
    play.innerHTML = icon(playing ? 'pause' : 'play');
    play.classList.toggle('need-play', blocked);
  }
  const modeB = box.querySelector('.np-controls [data-act="mode"]');
  if (modeB) modeB.innerHTML = icon(MODES.find(m => m[0] === mode)[1]);
  const favB = box.querySelector('.np-controls [data-act="fav"]');
  if (favB) favB.classList.toggle('on', isFav(t));
  const mb = $('#miniplayer [data-act="fav"]');
  if (mb) mb.classList.toggle('on', isFav(t));
  if (queueOpen) renderPanelBody();
  paintProgress();
}

function showPanel(kind) {
  const side = $('#np-side');
  if (!side) return;
  if (!kind) { closePanel(); return; }
  if (queueOpen === true && kind === 'queue') { closePanel(); return; }
  if (eqOpen === true && kind === 'eq') { closePanel(); return; }
  queueOpen = kind === 'queue';
  eqOpen = kind === 'eq';
  // 打开音效面板是点击手势，在这里把图建起来，之前存的预设才会真正生效
  if (eqOpen) ensureGraph();
  side.className = 'np-side open';
  renderPanelBody();
}

function closePanel() {
  queueOpen = false;
  eqOpen = false;
  const side = $('#np-side');
  if (side) { side.className = 'np-side'; side.innerHTML = ''; }
}

function renderPanelBody() {
  const side = $('#np-side');
  if (!side) return;
  if (queueOpen) return renderQueue();
  if (eqOpen) return renderEq();
}

function renderQueue() {
  const side = $('#np-side');
  if (!side) return;
  const a = audio();
  side.innerHTML = `
    <div class="np-side-head">
      <h3>播放列表 <span class="dim">${queue.length} 首</span></h3>
      <button class="icon-btn" data-pact="close" data-focus title="收起">${icon('close')}</button>
    </div>
    <div class="np-side-body">
      ${queue.map((t, i) => `
        <div class="q-row${i === idx ? ' on' : ''}" data-qi="${i}">
          <span class="q-no">${i === idx || !playing ? '<i class="q-wave"></i>' : i + 1}</span>
          <div class="q-meta">
            <div class="q-title">${esc(t.title)}</div>
            <div class="q-artist">${esc(t.artist || '')}</div>
          </div>
          <span class="q-dur">${i === idx && isFinite(a.duration) ? fmtClock(a.duration)
            : (t.duration ? fmtClock(t.duration) : '')}</span>
          <button class="icon-btn q-del" data-qdel="${i}" title="从列表移除">${icon('close')}</button>
        </div>`).join('')}
    </div>`;
  side.querySelector('[data-pact="close"]').onclick = closePanel;
  side.querySelectorAll('.q-row').forEach(row => {
    row.onclick = e => {
      if (e.target.closest('[data-qdel]')) return;
      const i = Number(row.dataset.qi);
      if (i === idx) return toggle();
      idx = i;
      load(true);
    };
  });
  side.querySelectorAll('[data-qdel]').forEach(b => {
    b.onclick = e => {
      e.stopPropagation();
      const i = Number(b.dataset.qdel);
      queue.splice(i, 1);
      if (i < idx) idx--;
      else if (i === idx) {
        if (!queue.length) return stopAll();
        idx = Math.min(idx, queue.length - 1);
        load(playing);
        return;
      }
      renderQueue();
      render();
    };
  });
}

function renderEq() {
  const side = $('#np-side');
  if (!side) return;
  const gains = eqGains.length === EQ_BANDS.length ? eqGains : EQ_PRESETS['平直'];
  side.innerHTML = `
    <div class="np-side-head">
      <h3>音效 <span class="dim">10 段均衡</span></h3>
      <button class="icon-btn" data-pact="close" data-focus title="收起">${icon('close')}</button>
    </div>
    <div class="np-side-body">
      <div class="eq-presets">
        ${Object.keys(EQ_PRESETS).map(n => `
          <button class="eq-chip${n === eqPreset ? ' on' : ''}" data-eqp="${esc(n)}"
                  data-focus>${esc(n)}</button>`).join('')}
      </div>
      <button class="eq-switch${loudness ? ' on' : ''}" data-loud data-focus>
        <span class="sw-dot"></span>
        <span class="sw-label">响度均衡</span>
        <span class="sw-hint">${loudness ? '已开启 · 小声的歌也听得清' : '关闭'}</span>
      </button>
      <div class="eq-bands">
        ${EQ_BANDS.map((f, i) => `
          <label class="eq-band">
            <input type="range" min="-12" max="12" step="1" value="${gains[i]}"
                   data-eqi="${i}" aria-label="${f}Hz">
            <span class="eq-val" id="eqv-${i}">${gains[i] > 0 ? '+' : ''}${gains[i]}</span>
            <span class="eq-hz">${f >= 1000 ? (f / 1000) + 'k' : f}</span>
          </label>`).join('')}
      </div>
      <p class="hint" style="margin:14px 0 0;line-height:1.7">
        均衡器由浏览器原生 Web Audio 实现，不依赖任何第三方库；
        调整会立即生效，并记住上次选择。
      </p>
    </div>`;
  side.querySelector('[data-pact="close"]').onclick = closePanel;
  side.querySelector('[data-loud]').onclick = toggleLoudness;
  side.querySelectorAll('[data-eqp]').forEach(b => {
    b.onclick = () => usePreset(b.dataset.eqp);
  });
  side.querySelectorAll('[data-eqi]').forEach(inp => {
    inp.oninput = () => {
      const i = Number(inp.dataset.eqi);
      if (eqGains.length !== EQ_BANDS.length) eqGains = gains.slice();
      eqGains[i] = Number(inp.value);
      eqPreset = '自定义';
      localStorage.setItem('mp.eq', eqPreset);
      const lab = side.querySelector('#eqv-' + i);
      if (lab) lab.textContent = (eqGains[i] > 0 ? '+' : '') + eqGains[i];
      ensureGraph();
      applyEq();
    };
  });
}

function closeNowPlaying() {
  if (!npOpen) return;
  npOpen = false;
  fxStop();
  document.body.classList.remove('locked');
  $('#np')?.remove();
  queueOpen = false;
  clearFocus();
}

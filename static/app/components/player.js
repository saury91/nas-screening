// 全屏播放器：ArtPlayer + hls.js
// 交互统一收进播放器控制栏：上一集/下一集/线路选集；画面只保留缓冲状态（进度 + 网速）。
import { $, esc, icon, toast, fmtSpeed } from '../core/dom.js';
import { api } from '../core/api.js';
import { openCastSheet } from '../core/cast.js';
import { getToken } from '../core/auth.js';
import { setFocus, clearFocus } from '../core/focus.js';

let art = null;
let state = null;      // { item, si, ei, isLive }
let saveTimer = null;
let hudTimer = null;
let lastSaved = 0;
let speedBps = 0;
let fragPct = 0;
// 只有「真的在等数据」时才允许缓冲提示出现。hls 在正常播放时会持续预载
// 后面的分片，如果加载事件里直接 showBuffer，提示就会一直挂在画面上。
let waitState = false;
let booting = true;

const layer = () => $('#playerlayer');

// ArtPlayer + hls.js 合计 540KB。以前放在 index.html 里同步加载，
// 首屏白等它们解析完；现在只有真的进播放器才拉。
let libsPromise = null;
function ensureLibs() {
  if (window.Artplayer) return Promise.resolve();
  if (libsPromise) return libsPromise;
  const load = src => new Promise((res, rej) => {
    const s = document.createElement('script');
    s.src = src;
    s.async = true;
    s.onload = () => res();
    s.onerror = () => rej(new Error(src));
    document.head.appendChild(s);
  });
  libsPromise = load('/static/artplayer.js')
    .then(() => (window.Hls ? null : load('/static/hls.js')))
    .catch(e => { libsPromise = null; throw e; });
  return libsPromise;
}

let triedLines = new Set();
// 卡死看门狗：hls.js 只在「明确报错」时才触发 error，
// 有些线路是 TCP 连着但一直不给数据 —— 播放器就会永远停在缓冲。
let stallTimer = null;
let stallAt = 0;
let stallPos = 0;

function stopStallWatch() {
  clearInterval(stallTimer);
  stallTimer = null;
}

function startStallWatch() {
  stopStallWatch();
  stallAt = Date.now();
  stallPos = 0;
  stallTimer = setInterval(() => {
    const v = art && art.video;
    if (!state || !v) return;
    if (v.paused) { stallAt = Date.now(); stallPos = v.currentTime; return; }
    // 有进展就续命
    if (Math.abs(v.currentTime - stallPos) > 0.2) {
      stallPos = v.currentTime;
      stallAt = Date.now();
      return;
    }
    // 连续 12 秒没动过：换线
    if (Date.now() - stallAt < 12000) return;
    stallAt = Date.now();
    if (switching) return;
    toast('这条线路没有响应，自动换一条');
    if (!autoNextLine()) {
      showError('这条线路播不动', '换一条线路，或者过一会儿再试。');
    }
  }, 2000);
}

function rememberLine() {
  if (!state) return;
  try {
    localStorage.setItem('pr.line.' + state.item.name, currentSource().site);
  } catch (e) { /* 隐私模式忽略 */ }
}

export function playerOpen() { return state !== null; }

// App 壳（手机/电视）本身就是全屏，不需要"全屏"这个动作；只有浏览器才需要
function inApp() {
  try { return !!window.ProomNative || !!window.ProomTV; } catch (e) { return false; }
}

export function openPlayer(item, { si = 0, ei = 0, position = 0, isLive = false, key = '' } = {}) {
  if (!item || !item.sources || !item.sources.length) {
    toast('没有可播放的线路', 'err');
    return;
  }
  // 正在小窗里播同一集：把画面收回来就行，不重新起播
  const el0 = layer();
  if (el0 && el0.classList.contains('pip-out') && state &&
      item.name === state.item.name && si === state.si && ei === state.ei) {
    el0.classList.remove('pip-out');
    document.body.classList.add('locked');
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
    return;
  }
  closePlayer(true);
  state = { item, si: Math.min(si, item.sources.length - 1), ei, isLive,
            position, key: key || item.name };
  speedBps = 0;
  fragPct = 0;

  const src = currentSource();
  const ep = src.eps[Math.min(ei, src.eps.length - 1)] || src.eps[0];

  layer().hidden = false;
  document.body.classList.add('locked');
  layer().innerHTML = `
    <div class="pl-stage">
      ${item.pic ? `<div class="pl-poster" id="pl-poster"
        style="background-image:url('${esc(item.pic)}')"></div>` : ''}
      <div id="art-host"></div>

      <div class="pl-buffer" id="pl-buffer" hidden>
        <div class="pl-dots"><b></b><b></b><b></b></div>
        <div class="pl-buffer-label">
          <span id="pl-buffer-text">缓冲中</span>
          <em id="pl-buffer-pct"></em>
          <i id="pl-buffer-speed"></i>
        </div>
      </div>

      <div class="pl-top" id="pl-top">
        <div class="pl-title">
          <h2>${esc(item.name)}${ep && !isLive ? ` · ${esc(ep.name)}` : ''}</h2>
          <p>${esc(src.site || '')}${item.year ? ` · ${esc(item.year)}` : ''}${isLive ? ' · 直播' : ''}<span class="pl-res" id="pl-res"></span></p>
        </div>
        <div class="pl-tools">
          ${isTvDevice() ? '' : `<button class="icon-btn" id="pl-cast" title="投屏到电视" data-focus>${icon('tv')}</button>`}
          <button class="icon-btn" id="pl-close" title="关闭（Esc）" data-focus>${icon('close')}</button>
        </div>
      </div>

      <aside class="pl-side" id="pl-side">
        <div class="pl-side-head">
          <h3>播放设置</h3>
          <button class="icon-btn" id="pl-side-close" data-focus title="收起到右侧">${icon('chevron')}</button>
        </div>
        <div class="pl-side-body" id="pl-side-body">${sideHTML()}</div>
      </aside>
    </div>`;
  bindUI();
  // 手机端：进播放器就横屏全屏（电视本来就是横屏，浏览器没有这个桥）
  try { window.ProomNative?.setLandscape?.(true); } catch (e) {}
  pingChrome();
  setTimeout(() => {
    if (!state) return;
    if ($('#pl-side')?.classList.contains('open')) return;
    $('#pl-top')?.classList.add('hide');
  }, 2600);
  // 返回键：播放器开着就关播放器，别退到上一个页面
  window.__proomMenu = () => {
    if (!state) return false;
    toggleSide(!$('#pl-side').classList.contains('open'));
    pingChrome();
    return true;
  };
  window.__proomBack = () => {
    if (!state) return false;
    if ($('#pl-side')?.classList.contains('open')) { toggleSide(false); return true; }
    closePlayer();
    return true;
  };
  const castBtn = layer().querySelector('#pl-cast');
  if (castBtn) {
    castBtn.onclick = () => {
      const info = castInfo();
      if (!info) { toast('还没有可投的内容', 'err'); return; }
      openCastSheet(info);
    };
  }
  waitState = true;
  booting = true;
  triedLines = new Set();
  showBuffer('缓冲中');
  const url0 = ep ? ep.url : '';
  ensureLibs()
    .then(() => { if (state) mountArt(url0, position); })
    .catch(() => showError('播放器没加载出来', '检查 /static/artplayer.js 与 hls.js 是否可访问。'));
}

function currentSource() { return state.item.sources[state.si]; }

// 电视端不显示投屏按钮（它本身就是接收端）
function isTvDevice() {
  return /ProomTV\//.test(navigator.userAgent) ||
    document.documentElement.classList.contains('tv');
}

/** 投屏用：当前在播的地址、标题、进度 */
export function castInfo() {
  if (!state) return null;
  const src = currentSource();
  const ep = src && src.eps ? (src.eps[state.ei] || src.eps[0]) : null;
  if (!ep || !ep.url) return null;
  // 整部片的线路与集数一起带过去：否则电视那边只能看到一个"单集投屏"，
  // 没法换源、也没法换集
  return {
    url: ep.url,
    title: state.item.name + (ep.name && !state.isLive ? ' · ' + ep.name : ''),
    position: art && art.currentTime ? Math.floor(art.currentTime) : Math.floor(state.position || 0),
    si: state.si,
    ei: state.ei,
    isLive: !!state.isLive,
    item: {
      name: state.item.name,
      pic: state.item.pic || '',
      year: state.item.year || '',
      desc: state.item.desc || '',
      sources: (state.item.sources || []).map(src2 => ({
        site: src2.site || '',
        q: src2.q || '',
        count: (src2.eps || []).length,
        eps: (src2.eps || []).map(e => ({ name: e.name || '', url: e.url || '' })),
      })),
    },
  };
}

// 只改高亮和顶部那句「正在播放」，不动 DOM —— 重建会把选集的滚动位置和焦点一起清掉
function syncSide() {
  const body = $('#pl-side-body');
  if (!body) return;
  const src = currentSource();
  const ep = src.eps[state.ei];
  body.querySelectorAll('.line-chip').forEach((b, i) =>
    b.classList.toggle('on', i === state.si));
  body.querySelectorAll('.ep').forEach((b, i) =>
    b.classList.toggle('on', i === state.ei));
  const site = body.querySelector('.pl-now b');
  if (site) site.textContent = src.site;
  const now = body.querySelector('.pl-now span');
  if (now) now.textContent = `${src.eps.length} 集 · 正在播放 ${ep ? ep.name : '—'}`;
}

function sideHTML() {
  const it = state.item;
  const src = currentSource();
  const ep = src.eps[state.ei];
  return `
    <div class="pl-now">
      <div><b>${esc(src.site)}</b><span>${src.eps.length} 集 · 正在播放 ${esc(ep ? ep.name : '—')}</span></div>
    </div>
    <div class="blk">
      <h5>播放线路 <span class="count">${it.sources.length}</span></h5>
      <div class="line-chips">${it.sources.map((s, i) => `
        <button class="line-chip ${i === state.si ? 'on' : ''}" data-si="${i}" data-focus
          title="${esc(s.remarks || '')}">
          <b>${esc(s.site)}</b>${s.q ? `<u>${esc(s.q)}</u>` : ''}<i>${s.count || (s.eps ? s.eps.length : '')} 集</i>
        </button>`).join('')}</div>
    </div>
    ${src.eps.length > 1 ? `<div class="blk">
      <h5>选集 <span class="count">${src.eps.length}</span></h5>
      <div class="ep-grid">${src.eps.map((e, i) => `
        <button class="ep ${i === state.ei ? 'on' : ''}" data-ei="${i}" data-focus>${esc(e.name)}</button>`).join('')}</div>
    </div>` : ''}`;
}

function bindUI() {
  $('#pl-close').onclick = () => closePlayer();
  $('#pl-side-close').onclick = () => toggleSide(false);

  const side = $('#pl-side-body');
  side.onclick = e => {
    const line = e.target.closest('[data-si]');
    if (line) { switchLine(+line.dataset.si); }
    else {
      const ep = e.target.closest('[data-ei]');
      if (ep) switchEp(+ep.dataset.ei);
    }
    // 鼠标点完就把焦点环收掉：否则移开鼠标还亮着，看着像"选中了这一集"
    if (e.detail) clearFocus();
  };

  $('.pl-stage')?.addEventListener('pointerdown', pingChrome, true);
  document.addEventListener('keydown', onKeys, true);
  document.addEventListener('keyup', (e) => {
    if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') stopSeekHold();
  }, true);
  document.addEventListener('blur', () => stopSeekHold());
  document.addEventListener('mousemove', pingChrome);
  document.addEventListener('app:escape', onEscape);
}

function onEscape(e) {
  if (e.detail && e.detail.handled) return;
  // 原生全屏里先退全屏，不要直接把播放器关了
  if (fsElement()) {
    toggleFullscreen();
  } else {
    closePlayer();
  }
  if (e.detail) e.detail.handled = true;
}

function onKeys(e) {
  if (!state) return;
  // 电视遥控器没有 mousemove：任何一次按键都当成"用户还在操作"
  pingChrome();
  if (e.key === 'Escape') {
    e.stopPropagation();
    if (fsElement()) toggleFullscreen();
    else closePlayer();
    return;
  }
  const tag = document.activeElement.tagName;
  if (/INPUT|TEXTAREA/.test(tag)) return;

  if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); art && art.toggle(); }
  if (e.key.toLowerCase() === 'f') { e.preventDefault(); toggleFullscreen(); }
  if (e.key === 'PageUp') { e.preventDefault(); step(-1); }
  if (e.key === 'PageDown') { e.preventDefault(); step(1); }

  // 侧栏打开时方向键归焦点系统：想选线路时不该在拖进度
  const panelOpen = $('#pl-side')?.classList.contains('open');
  if (panelOpen && ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) return;

  if (e.key === 'ArrowUp') { e.preventDefault(); }                    // 叫出控制层
  if (e.key === 'ArrowDown') { e.preventDefault(); toggleSide(true); }
  if (e.key === 'ArrowLeft') { e.preventDefault(); startSeekHold(-1); }
  if (e.key === 'ArrowRight') { e.preventDefault(); startSeekHold(1); }
}

// ── 快进/快退（遥控器长按左右键）────────────────────────────
const SEEK_STEP = 10;        // 单步 10 秒
const SEEK_HOLD_DELAY = 420; // 按住多久开始连发
const SEEK_REPEAT = 200;     // 连发间隔

let seekTimer = null, seekRepeat = null, seekHide = null, seekAcc = 0;

function seekBy(delta) {
  if (!art) return;
  const dur = Number.isFinite(art.duration) ? art.duration : 0;
  let t = (art.currentTime || 0) + delta;
  t = Math.max(0, dur ? Math.min(dur - 0.4, t) : t);
  art.currentTime = t;
  seekAcc += delta;
  showSeekOsd(seekAcc, t);
  pingChrome();
}

let lastSeekAt = 0;
function startSeekHold(dir) {
  if (!art) return;
  // 连续按（遥控器连发）算同一次操作，累计显示「+30 秒」而不是每次重置
  const now = Date.now();
  if (now - lastSeekAt > 420) seekAcc = 0;
  lastSeekAt = now;
  seekBy(dir * SEEK_STEP);
  clearTimeout(seekTimer);
  clearInterval(seekRepeat);
  seekTimer = setTimeout(() => {
    seekRepeat = setInterval(() => seekBy(dir * SEEK_STEP), SEEK_REPEAT);
  }, SEEK_HOLD_DELAY);
}

function stopSeekHold() {
  clearTimeout(seekTimer);
  clearInterval(seekRepeat);
  seekTimer = seekRepeat = null;
  clearTimeout(seekHide);
  seekHide = setTimeout(() => {
    const el = $('.pl-seek');
    if (el) el.classList.remove('on');
  }, 1600);
}

function fmtClock(sec) {
  const s = Math.max(0, Math.floor(sec || 0));
  const m = Math.floor(s / 60);
  const h = Math.floor(m / 60);
  const mm = h ? String(m % 60).padStart(2, '0') : String(m);
  const ss = String(s % 60).padStart(2, '0');
  return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

function showSeekOsd(acc, target) {
  const host = $('.pl-stage');
  if (!host) return;
  let el = host.querySelector('.pl-seek');
  if (!el) {
    el = document.createElement('div');
    el.className = 'pl-seek';
    host.appendChild(el);
  }
  const back = acc < 0;
  el.innerHTML = `<b>${back ? '◀◀' : '▶▶'} ${Math.abs(acc)} 秒</b>`
    + `<span>${fmtClock(art ? art.currentTime : 0)}</span>`;
  el.classList.add('on', back ? 'back' : 'fwd');
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.remove('on'), 1600);
}

function pingChrome() {
  if (!state) return;
  const top = $('#pl-top');
  if (!top) return;
  top.classList.remove('hide');
  clearTimeout(pingChrome.t);
  pingChrome.t = setTimeout(() => {
    if ($('#pl-side')?.classList.contains('open')) return;
    // 暂停状态别收：用户停下来了，正想找按钮
    if (art && art.video && art.video.paused) return;
    top.classList.add('hide');
  }, 2600);
}

function toggleSide(force) {
  const side = $('#pl-side');
  const open = force === undefined ? !side.classList.contains('open') : force;
  side.classList.toggle('open', open);
  const stage = $('.pl-stage');
  if (stage) stage.classList.toggle('side-open', open);
  if (open) {
    $('#pl-side-body').innerHTML = sideHTML();
    setTimeout(() => {
      const b = $('#pl-side-body [data-focus]');
      if (b) setFocus(b, false);
    }, 60);
  } else {
    clearFocus();
  }
}

function step(dir) {
  if (state.isLive) return;
  const src = currentSource();
  const next = state.ei + dir;
  if (next < 0 || next >= src.eps.length) {
    toast(dir > 0 ? '已经是最后一集' : '已经是第一集');
    return;
  }
  switchEp(next);
}

// 播完自动接下一集：追剧时最常用的一次点击，不该让用户自己找
function onEnded() {
  if (!state || state.isLive || switching) return;
  const src = currentSource();
  if (state.ei + 1 >= src.eps.length) return;
  const nxt = src.eps[state.ei + 1];
  toast('即将播放 · ' + (nxt.name || '下一集'));
  setTimeout(() => {
    if (!state || state.isLive) return;
    if (state.ei + 1 < currentSource().eps.length) switchEp(state.ei + 1);
  }, 1200);
}

// 线路挂了自动往下一条试，用户不用自己回到详情页换源
let fallbackBusy = false;
function autoNextLine() {
  if (!state || fallbackBusy) return false;
  const n = state.item.sources.length;
  if (n < 2) return false;
  let next = -1;
  for (let i = 1; i <= n; i++) {
    const k = (state.si + i) % n;
    if (!triedLines.has(k)) { next = k; break; }
  }
  if (next < 0) return false;
  triedLines.add(next);
  fallbackBusy = true;
  const keepEp = state.ei;
  setTimeout(() => {
    fallbackBusy = false;
    if (!state) return;
    toast('这条线路播不动，自动换一条');
    switchLine(next, keepEp);
  }, 700);
  return true;
}

function switchEp(ei) {
  const src = currentSource();
  const next = Math.max(0, Math.min(ei, src.eps.length - 1));
  if (next === state.ei) return;
  // 先落库上一集，再改 state.ei —— 否则最后几秒的进度会被写到新一集上
  saveProgress(true);
  state.ei = next;
  state.position = 0;
  const ep = src.eps[next];
  syncSide();
  playUrl(ep.url, 0);
  const t = $('.pl-title h2');
  if (t) t.textContent = `${state.item.name} · ${ep.name}`;
}

function switchLine(si, keepEp = false) {
  if (si === state.si) return;
  const body = $('#pl-side-body');
  const scrolled = body ? body.scrollTop : 0;
  const prevEi = state.ei;
  saveProgress(true);
  state.si = si;
  const src = currentSource();
  // 自动降级时保留当前集；手动换线路仍从第 1 集开始
  state.ei = keepEp ? Math.min(prevEi, src.eps.length - 1) : 0;
  state.position = 0;
  const ep = src.eps[state.ei] || src.eps[0];
  if (body) {
    body.innerHTML = sideHTML();
    body.scrollTop = scrolled;
  }
  playUrl(ep.url, 0);
  rememberLine();
  toast('已切换到 ' + src.site);
}

// ── 缓冲状态（画面里唯一保留的浮层）────────────────────────
// 缓冲状态：三个依次跳起的波点，进度用文字表达。
function setProgress(pct) {
  const el = $('#pl-buffer');
  if (!el) return;
  const p = Math.max(0, Math.min(100, pct || 0));
  el.classList.toggle('indeterminate', !p);
  const pctEl = $('#pl-buffer-pct');
  if (pctEl) pctEl.textContent = p ? Math.round(p) + '%' : '';
}

function showBuffer(text) {
  if (!waitState && !booting) return;
  const el = $('#pl-buffer');
  if (!el) return;
  clearTimeout(hudTimer);
  el.hidden = false;
  if (text) $('#pl-buffer-text').textContent = text;
  const sp = $('#pl-buffer-speed');
  if (sp) sp.textContent = speedBps ? fmtSpeed(speedBps) : '';
  setProgress(fragPct);
}

function hideBufferSoon(ms = 500) {
  clearTimeout(hudTimer);
  hudTimer = setTimeout(() => {
    const el = $('#pl-buffer');
    if (el) el.hidden = true;
  }, ms);
}

function settleBuffer() {
  waitState = false;
  booting = false;
  hideBufferSoon(240);
}

function refreshBuffer() {
  if (!art || !art.video) return;
  // 就绪/播放中都不显示，只有真正等待数据时才由 waiting 事件唤出
  if (art.video.paused && waitState) return;
  settleBuffer();
}

// ── 播放器 ────────────────────────────────────────────────

// 直播地址不一定带 .m3u8（有的走 ?channelId= 之类），
// 只要不是明确的 mp4/flv，就按 HLS 处理，否则这些频道会直接黑屏。
function isHlsUrl(url) {
  if (/\.m3u8(\?|$)/i.test(url)) return true;
  if (/\.(mp4|flv|mkv|webm)(\?|$)/i.test(url)) return false;
  return !!(state && state.isLive);
}

function mountArt(url, position) {
  const host = $('#art-host');
  if (!url || !window.Artplayer) {
    showError('播放器没加载出来', '检查一下静态资源 /static/artplayer.js 是否可访问。');
    return;
  }
  applyClickGesture();
  const isM3u8 = isHlsUrl(url);
  // 走同源代理：采集站几乎都不带 CORS 头，直连必被浏览器拦
  const src = isM3u8 ? '/api/proxy?u=' + encodeURIComponent(url) : url;
  // 透明 1x1：Artplayer 不传 poster 时会用自带的「灰底大播放键」占位图，
  // 那个白底跟暗色界面完全不搭，缓冲/换集时尤其明显
  const BLANK = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
  const options = {
    container: host,
    url: src,
    poster: BLANK,
    type: isM3u8 ? 'm3u8' : '',
    autoplay: true,
    volume: 0.85,
    theme: '#ffcf7a',
    lang: 'zh-cn',
    setting: true,
    playbackRate: true,
    aspectRatio: true,
    // 用整层播放器做全屏：这样标题、侧栏在原生全屏里也还在
    fullscreen: false,
    fullscreenWeb: false,
    miniProgressBar: false,
    autoOrientation: true,
    airplay: true,
    pip: false,
    mutex: false,
    customType: {
      m3u8(video, u, player) {
        if (window.Hls && window.Hls.isSupported()) {
          if (player.hls) player.hls.destroy();
          const hls = new window.Hls({
            maxBufferLength: 40,
            maxMaxBufferLength: 90,
            fragLoadingMaxRetry: 4,
            // hls.js 的请求不走 api.js，不会自动带 X-Token；
            // Cookie 万一没有，这里兜住（WebView 里很常见）
            xhrSetup: (xhr) => {
              const tk = getToken();
              if (tk) xhr.setRequestHeader('X-Token', tk);
            },
          });
          hls.loadSource(u);
          hls.attachMedia(video);
          wireHls(hls, player);
          player.hls = hls;
          player.on('destroy', () => hls.destroy());
        } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
          video.src = u;
        }
      },
    },
  };
  try {
    art = new window.Artplayer(options);
  } catch (e) {
    showError('播放器初始化失败', String(e.message || e));
    return;
  }
  addControls();
  wirePip();
  wireClickGesture(host);

  // 真实分辨率：videoHeight 是解码出来的实际值，比备注里写的靠谱
  const showRes = () => {
    const v = art && art.video;
    const w = (v && v.videoWidth) || 0;
    const h = (v && v.videoHeight) || 0;
    const badge = $('#pl-res');
    if (!badge) return;
    // 和探测接口同一套口径：按像素总数分档（1080x608 这类宽银幕不会被判成 480P）
    const px = w * h;
    const q = px >= 6000000 ? '4K'
      : px >= 1400000 ? '1080P'
      : px >= 550000 ? '720P'
      : px >= 200000 ? '480P' : '';
    badge.textContent = q || (h ? `${w}×${h}` : '');
  };
  art.on('video:loadedmetadata', showRes);
  art.on('video:resize', showRes);

  art.on('ready', () => {
    // 音量与倍速跨集/跨片记忆
    try {
      const v = parseFloat(localStorage.getItem('pr.volume'));
      if (!isNaN(v) && v >= 0 && v <= 1) art.volume = v;
      const rate = parseFloat(localStorage.getItem('pr.rate'));
      if (!isNaN(rate) && rate >= 0.5 && rate <= 2) art.video.playbackRate = rate;
    } catch (e) { /* ignore */ }
    // 直播不要主动 seek：把 currentTime 归零会让播放器回到缓冲区头部，
    // 每次重新 ready 都跳一次，看起来就是一直在缓冲。
    if (!state.isLive && position > 4) art.currentTime = position;
    saveTimer = setInterval(saveProgress, 6000);
    startStallWatch();
    refreshBuffer();
    autoplayFallback();
  });
  art.on('video:volumechange', () => {
    try { localStorage.setItem('pr.volume', String(art.volume)); } catch (e) {}
  });
  art.video && art.video.addEventListener('ratechange', () => {
    try { localStorage.setItem('pr.rate', String(art.video.playbackRate)); } catch (e) {}
  });
  art.on('video:ended', onEnded);
  // 只在真的卡住/定位时显示缓冲状态；正常播放时不打扰画面
  const onWait = () => { waitState = true; showBuffer('缓冲中'); };
  art.on('video:waiting', onWait);
  art.on('video:stalled', () => { waitState = true; showBuffer('网络较慢，正在等数据'); });
  art.on('video:playing', () => {
    if (state) rememberLine();   // 能播了才记线路
    endSwitch();
    settleBuffer();
    hideCenter();
    // 画面出来了才让 video 显形，并把背后的模糊海报收掉
    $('.pl-stage')?.classList.add('playing');
    const poster = $('#pl-poster');
    if (poster) poster.classList.add('gone');
  });
  art.on('video:pause', () => { if (!switching && !art.video.seeking) settleBuffer(); });
  art.on('video:canplay', () => { if (!art.video.paused) { endSwitch(); settleBuffer(); } });
  art.video && art.video.addEventListener('seeked', settleBuffer);
  art.on('error', () => {
    showError('播放失败', '片源可能已经失效，换条线路试试。');
    autoNextLine();
  });
  art.on('destroy', () => clearInterval(saveTimer));
}

// 没有用户手势时浏览器会拦掉有声自动播放，这里退一步先静音播起来，
// 免得用户进播放页看到的是一个大大的「播放」按钮。
function autoplayFallback() {
  const v = art && art.video;
  if (!v) return;
  setTimeout(() => {
    // 已经播起来（或用户主动暂停过）就不再干预
    if (!art || !booting || !v.paused) return;
    const p = v.play();
    if (!p || !p.catch) return;
    p.catch(() => {
      if (!art) return;
      v.muted = true;
      const p2 = v.play();
      if (p2 && p2.catch) {
        p2.then(() => {
          if (art) toast('已静音播放，点音量键开启声音');
        }).catch(() => {});
      }
    });
  }, 400);
}

function wireHls(hls, player) {
  const H = window.Hls;
  hls.on(H.Events.FRAG_LOADING, () => {
    fragPct = 0;
    // 不主动唤出，只在已经卡住时把进度归零
    if (waitState) { setProgress(0); showBuffer('缓冲中'); }
  });
  hls.on(H.Events.FRAG_LOAD_PROGRESS, (_, d) => {
    const st = d.frag && d.frag.stats;
    if (!st) return;
    if (st.total > 0) {
      fragPct = Math.min(99, Math.round(st.loaded / st.total * 100));
    }
    if (st.loading && st.loading.start && st.loaded) {
      const sec = (performance.now() - st.loading.start) / 1000;
      if (sec > 0.25) speedBps = st.loaded / sec;
    }
    // 正常播放时的预载不刷新 UI
    if (!waitState && !booting) return;
    if (st.total > 0) { setProgress(fragPct); showBuffer('缓冲中'); }
    const sp = $('#pl-buffer-speed');
    if (sp) sp.textContent = speedBps ? fmtSpeed(speedBps) : '';
  });
  hls.on(H.Events.FRAG_LOADED, (_, d) => {
    const st = d.frag && d.frag.stats;
    if (st && st.loading && st.loading.end > st.loading.start && st.loaded) {
      speedBps = st.loaded / ((st.loading.end - st.loading.start) / 1000);
    }
    fragPct = 100;
    setProgress(100);
    if (player.video && !player.video.paused) settleBuffer();
  });
  hls.on(H.Events.ERROR, (_, data) => {
    if (data.fatal) {
      showError('这条线路播不动', '可以换一条线路，或稍后再试。');
      autoNextLine();
    }
  });
}

// 控制栏：上一集 / 下一集 / 线路与选集
function addControls() {
  if (!art || !art.controls || !art.controls.add) return;
  const add = (name, html, tooltip, click) => {
    try {
      art.controls.add({ name, position: 'right', html, tooltip, click });
    } catch (e) { /* 老版本 ArtPlayer 没有这个接口时静默跳过 */ }
  };
  if (!state.isLive) {
    add('prev-ep', `<span class="ap-ctl">${icon('prev')}</span>`, '上一集（PgUp）', () => step(-1));
    add('next-ep', `<span class="ap-ctl">${icon('next')}</span>`, '下一集（PgDn）', () => step(1));
  }
  add('ep-list', `<span class="ap-ctl">${icon('list')}</span>`, '线路与选集（↓）', () => toggleSide(true));
  add('pip', `<span class="ap-ctl">${icon('pip')}</span>`, '画中画', togglePip);
  // App 里已经是全屏了，这个按钮只会让人困惑
  if (!inApp()) {
    add('fullscreen', `<span class="ap-ctl">${icon('expand')}</span>`, '全屏（F）', toggleFullscreen);
  }
}

// ── 画中画 ────────────────────────────────────────────────
// 不用 ArtPlayer 自带的：它在进入 PiP 后不会管播放状态，
// 有些浏览器进入瞬间会把视频停住，小窗就定格了。
let pipWasPlaying = false;

function markPip(on) {
  const btn = document.querySelector('.art-control-pip');
  if (btn) btn.classList.toggle('on', on);
}

// 进入小窗后把整层播放器淡出（不是 display:none —— 那会让浏览器退出画中画），
// 视频仍在渲染，用户回到原来的页面继续浏览。
function restorePipOut() {
  const el = layer();
  if (!el) return;
  el.classList.remove('pip-out');
  if (state) document.body.classList.add('locked');
}

function enterPipOut() {
  const el = layer();
  if (!el) return;
  el.classList.add('pip-out');
  document.body.classList.remove('locked');
  const side = $('#pl-side');
  if (side) side.classList.remove('open');
  const stage = $('.pl-stage');
  if (stage) stage.classList.remove('side-open');
  toast('画中画播放中，可继续浏览');
}

function nudgePipPlaying() {
  const v = art && art.video;
  if (!v || !pipWasPlaying) return;
  // 进入小窗的头 1.5 秒内守一下播放状态：
  // 有些浏览器在这一刻会把视频停住，小窗就成了定格画面。
  // 之后不再干预，用户在小窗里点暂停是主动行为。
  [260, 1200].forEach(ms => setTimeout(() => {
    if (!document.pictureInPictureElement || !v.paused) return;
    const p = v.play();
    if (p && p.catch) p.catch(() => {});
  }, ms));
}

async function togglePip() {
  const v = art && art.video;
  if (!v) return;
  try {
    if (document.pictureInPictureElement) {
      await document.exitPictureInPicture();
      return;
    }
    pipWasPlaying = !v.paused;
    if (document.pictureInPictureEnabled && v.requestPictureInPicture) {
      await v.requestPictureInPicture();
    } else if (v.webkitSupportsPresentationMode &&
               v.webkitSupportsPresentationMode('picture-in-picture')) {
      v.webkitSetPresentationMode('picture-in-picture');
    } else {
      toast('这个浏览器不支持画中画');
      return;
    }
    enterPipOut();
    nudgePipPlaying();
  } catch (e) {
    // 手势过期或被浏览器拒绝：给个轻提示，不打断播放
    if (e && e.name === 'NotAllowedError') toast('请再点一次画中画按钮');
  }
}

function wirePip() {
  const v = art && art.video;
  if (!v) return;
  v.addEventListener('enterpictureinpicture', () => { markPip(true); nudgePipPlaying(); });
  v.addEventListener('leavepictureinpicture', () => {
    markPip(false);
    const el = layer();
    if (!el || !el.classList.contains('pip-out')) return;
    // 有两种退出方式：小窗上的「返回标签页」（视频继续播）和关闭小窗（Chrome 会暂停）。
    // 前者把画面收回来，后者才真正关掉播放器。
    setTimeout(() => {
      if (!state) return;
      if (v.paused) closePlayer(true);
      else restorePipOut();
    }, 260);
  });
  // Safari 的 presentation mode 没有对应事件，用轮询兜一下状态
  v.addEventListener('webkitpresentationmodechanged', () =>
    markPip(v.webkitPresentationMode === 'picture-in-picture'));
}

function fsElement() {
  return document.fullscreenElement || document.webkitFullscreenElement || null;
}

function iosVideoFullscreen() {
  const v = art && art.video;
  if (v && v.webkitEnterFullscreen) {
    try { v.webkitEnterFullscreen(); } catch (e) { /* 忽略 */ }
  }
}

function toggleFullscreen() {
  const el = layer();
  if (fsElement()) {
    const exit = document.exitFullscreen || document.webkitExitFullscreen;
    if (exit) { try { Promise.resolve(exit.call(document)).catch(() => {}); } catch (e) {} }
    return;
  }
  const req = el.requestFullscreen || el.webkitRequestFullscreen;
  if (req) {
    try {
      const ret = req.call(el);
      if (ret && ret.catch) ret.catch(iosVideoFullscreen);
    } catch (e) {
      iosVideoFullscreen();
    }
    return;
  }
  iosVideoFullscreen();
}

// 中央状态图标：只有用户主动点击画面时才出现。
// ArtPlayer 自带的 mask 在「刚进页面/缓冲」时也会显示，所以关掉自己画。
function flashCenter(kind) {
  const host = $('.pl-stage');
  if (!host) return;
  let el = host.querySelector('.pl-flash');
  if (!el) {
    el = document.createElement('div');
    el.className = 'pl-flash';
    host.appendChild(el);
  }
  el.innerHTML = `<span>${icon(kind)}</span>`;
  el.classList.add('on');
  clearTimeout(el._t);
  // 继续播放：闪一下就收；暂停：保持到恢复播放
  if (kind === 'pause') el._t = setTimeout(() => el.classList.remove('on'), 520);
}

function hideCenter() {
  const el = $('.pl-stage .pl-flash');
  if (el) {
    clearTimeout(el._t);
    el.classList.remove('on');
  }
}

// 画面点击：单击立即暂停/播放（不等待），双击时把第一下撤销掉再全屏。
// 这样单击没有延迟，双击也不会留下「暂停」的中间状态。
const DBL_WINDOW = 300;
let lastClickAt = 0;
let undoClick = null;
let clickResetTimer = null;
let flashTimer = null;
let switching = false;      // 正在换集/换线路：此期间的 pause/playing 不能拿来判断缓冲
let switchTimer = null;

function wireClickGesture(host) {
  if (!host) return;
  host.addEventListener('click', e => {
    const v = art && art.video;
    if (!v || e.target !== v) return;          // 控制栏、浮层不接管
    e.stopPropagation();
    const now = Date.now();
    if (now - lastClickAt < DBL_WINDOW && undoClick) {
      clearTimeout(flashTimer);
      undoClick();
      hideCenter();
      undoClick = null;
      lastClickAt = 0;
      clearTimeout(clickResetTimer);
      if (!inApp()) toggleFullscreen();
      return;
    }
    const wasPlaying = !v.paused;
    art.toggle();
    // 延后再显示：双击的第二下会把它取消掉，画面上不会闪任何东西。
    // 200ms 足够覆盖绝大多数双击间隔，单机时这个延迟反而让反馈更柔和。
    clearTimeout(flashTimer);
    flashTimer = setTimeout(() => flashCenter(wasPlaying ? 'play' : 'pause'), 200);
    // 记住怎么还原，双击时用
    undoClick = () => {
      if (wasPlaying) {
        const p = v.play();
        if (p && p.catch) p.catch(() => {});
      } else {
        v.pause();
      }
    };
    lastClickAt = now;
    clearTimeout(clickResetTimer);
    clickResetTimer = setTimeout(() => { undoClick = null; }, DBL_WINDOW + 20);
  }, true);
}

// 统一桌面与移动端的点击语义：单击 = 播放/暂停，双击 = 全屏。
// ArtPlayer 默认在移动端是「单击没反应、双击暂停」，而且双击的第一下
// 已经翻转了一次播放状态 —— 所以双击时要把它还原，否则会顺带暂停。
function applyClickGesture() {
  const A = window.Artplayer;
  if (!A) return;
  A.MOBILE_CLICK_PLAY = true;      // 移动端单击也切换播放
  A.MOBILE_DBCLICK_PLAY = false;   // 双击不再切换播放
  A.DBCLICK_FULLSCREEN = false;    // 内置全屏关掉，用整层全屏
}

function playUrl(url, position) {
  const stale = $('.pl-err');
  if (stale) stale.remove();
  if (!art) { mountArt(url, position); return; }
  fragPct = 0;
  speedBps = 0;
  waitState = true;
  booting = true;
  switching = true;
  // 换源瞬间旧进度条还挂着上一集的时间，先把它藏起来，等新视频能播再露出来
  const player = $('.art-video-player');
  if (player) player.classList.add('switching');
  clearTimeout(switchTimer);
  switchTimer = setTimeout(endSwitch, 9000);   // 兜底：卡住也要恢复 UI
  showBuffer('缓冲中');
  const isM3u8 = isHlsUrl(url);
  const src = isM3u8 ? '/api/proxy?u=' + encodeURIComponent(url) : url;
  try {
    art.type = isM3u8 ? 'm3u8' : '';
    art.switchUrl(src);
    if (position > 4) art.currentTime = position;
  } catch (e) {
    destroyArt();
    mountArt(url, position);
  }
}

function endSwitch() {
  switching = false;
  clearTimeout(switchTimer);
  const player = $('.art-video-player');
  if (player) player.classList.remove('switching');
}

function destroyArt() {
  endSwitch();
  stopStallWatch();
  if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
  try { art && art.destroy(false); } catch (e) {}
  art = null;
  waitState = false;
  booting = true;
  clearInterval(saveTimer);
  clearTimeout(hudTimer);
}

function showError(title, desc) {
  const host = $('.pl-stage');
  if (!host) return;
  endSwitch();
  const buf = $('#pl-buffer');
  if (buf) buf.hidden = true;
  // 停掉 ArtPlayer 自带的加载动画与音频，避免错误页上还转着圈
  try { art && art.destroy(false); } catch (e) {}
  art = null;
  clearInterval(saveTimer);
  host.querySelector('.pl-err')?.remove();
  const box = document.createElement('div');
  box.className = 'pl-err';
  box.innerHTML = `<div><h3>${esc(title)}</h3><p>${esc(desc)}</p>
    <div style="display:flex;gap:10px;justify-content:center">
      <button class="btn primary" id="pl-retry">重试</button>
      <button class="btn" id="pl-pick">换线路</button>
    </div></div>`;
  host.appendChild(box);
  $('#pl-retry').onclick = () => {
    box.remove();
    const ep = currentSource().eps[state.ei];
    mountArt(ep.url, 0);
  };
  $('#pl-pick').onclick = () => { box.remove(); toggleSide(true); };
}

async function saveProgress(force = false) {
  if (!state || state.isLive || !art) return;
  const t = art.currentTime || 0;
  const d = art.duration || 0;
  if (!d || t < 3) return;
  const now = Date.now();
  if (!force && now - lastSaved < 5000) return;
  lastSaved = now;
  const src = currentSource();
  const ep = src.eps[state.ei] || {};
  try {
    await api.history({
      kind: 'online', key: state.key, title: state.item.name,
      cover: state.item.pic || '', source: src.site, ep: ep.name || '',
      url: ep.url || '', position: Math.floor(t), duration: Math.floor(d),
      genre: state.item.type || '', year: state.item.year || '',
    });
  } catch (e) { /* 进度存不上不影响播放 */ }
}

export function closePlayer(silent = false) {
  if (!state) return;
  // 退出播放器还原竖屏（手机）
  try { window.ProomNative?.setLandscape?.(false); } catch (e) {}
  const el = layer();
  // 先摘掉 pip-out，避免 exitPictureInPicture 触发的 leave 事件又调回这里
  if (el) el.classList.remove('pip-out');
  clearTimeout(clickResetTimer);
  clickResetTimer = null;
  clearTimeout(flashTimer);
  undoClick = null;
  if (document.pictureInPictureElement) document.exitPictureInPicture().catch(() => {});
  if (fsElement()) toggleFullscreen();
  saveProgress(true);
  destroyArt();
  document.removeEventListener('keydown', onKeys, true);
  document.removeEventListener('mousemove', pingChrome);
  document.removeEventListener('app:escape', onEscape);
  clearTimeout(pingChrome.t);
  layer().hidden = true;
  layer().innerHTML = '';
  document.body.classList.remove('locked');
  clearFocus();
  const wasLive = state.isLive;
  state = null;
  triedLines = new Set();
  if (!silent) window.dispatchEvent(new CustomEvent('player:closed', { detail: { live: wasLive } }));
}

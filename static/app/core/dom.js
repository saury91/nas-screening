// 通用 DOM / 格式化 / 弹层工具
export const $ = (s, r = document) => r.querySelector(s);
export const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

export function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

export function el(html) {
  const t = document.createElement('template');
  t.innerHTML = html.trim();
  return t.content.firstElementChild;
}

export const sleep = ms => new Promise(r => setTimeout(r, ms));

export function debounce(fn, wait = 300) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), wait); };
}

// ── 格式化 ────────────────────────────────────────────────
export function fmtSize(n) {
  if (!n || n < 0) return '—';
  if (n >= 1 << 30) return (n / (1 << 30)).toFixed(2) + ' GB';
  if (n >= 1 << 20) return (n / (1 << 20)).toFixed(0) + ' MB';
  if (n >= 1 << 10) return (n / (1 << 10)).toFixed(0) + ' KB';
  return n + ' B';
}
export function fmtSpeed(n) {
  if (!n) return '0 KB/s';
  if (n >= 1 << 20) return (n / (1 << 20)).toFixed(1) + ' MB/s';
  return (n / (1 << 10)).toFixed(0) + ' KB/s';
}
export function fmtClock(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const h = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60), s = sec % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
           : `${m}:${String(s).padStart(2, '0')}`;
}
export function fmtDur(sec) {
  if (!sec) return '';
  sec = Math.floor(sec);
  if (sec < 60) return sec + ' 秒';
  if (sec < 3600) return Math.round(sec / 60) + ' 分钟';
  return (sec / 3600).toFixed(1) + ' 小时';
}
export function timeAgo(ts) {
  if (!ts) return '';
  const d = Date.now() / 1000 - ts;
  if (d < 60) return '刚刚';
  if (d < 3600) return Math.floor(d / 60) + ' 分钟前';
  if (d < 86400) return Math.floor(d / 3600) + ' 小时前';
  if (d < 2592000) return Math.floor(d / 86400) + ' 天前';
  return new Date(ts * 1000).toLocaleDateString('zh-CN');
}

// ── 图标 ──────────────────────────────────────────────────
const ICONS = {
  play: '<path d="M8 5v14l11-7z" fill="currentColor" stroke="none"/>',
  pause: '<path d="M7 5h4v14H7zM13 5h4v14h-4z" fill="currentColor" stroke="none"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  check: '<path d="M20 6L9 17l-5-5"/>',
  heart: '<path d="M20.8 4.6a5.5 5.5 0 00-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 00-7.8 7.8l8.8 8.8 8.8-8.8a5.5 5.5 0 000-7.8z"/>',
  download: '<path d="M12 3v12m0 0l-4-4m4 4l4-4M5 21h14"/>',
  upload: '<path d="M12 16V4m0 0L8 8m4-4l4 4"/><path d="M4 15v3a2 2 0 002 2h12a2 2 0 002-2v-3"/>',
  chevron: '<path d="M9 6l6 6-6 6"/>',
  chevronDown: '<path d="M6 9l6 6 6-6"/>',
  back: '<path d="M15 18l-6-6 6-6"/>',
  close: '<path d="M18 6L6 18M6 6l12 12"/>',
  list: '<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>',
  refresh: '<path d="M21 12a9 9 0 11-3-6.7M21 3v6h-6"/>',
  trash: '<path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14"/>',
  settings: '<circle cx="12" cy="12" r="3.2"/><path d="M19.4 15a1.7 1.7 0 00.34 1.87l.06.06a2 2 0 11-2.83 2.83l-.06-.06A1.7 1.7 0 0015 19.4a1.7 1.7 0 00-1 1.55V21a2 2 0 11-4 0v-.09A1.7 1.7 0 009 19.4a1.7 1.7 0 00-1.87.34l-.06.06a2 2 0 11-2.83-2.83l.06-.06A1.7 1.7 0 004.6 15a1.7 1.7 0 00-1.55-1H3a2 2 0 110-4h.09A1.7 1.7 0 004.6 9a1.7 1.7 0 00-.34-1.87l-.06-.06a2 2 0 112.83-2.83l.06.06A1.7 1.7 0 009 4.6a1.7 1.7 0 001-1.55V3a2 2 0 114 0v.09A1.7 1.7 0 0015 4.6a1.7 1.7 0 001.87-.34l.06-.06a2 2 0 112.83 2.83l-.06.06A1.7 1.7 0 0019.4 9a1.7 1.7 0 001.55 1H21a2 2 0 110 4h-.09a1.7 1.7 0 00-1.51 1z"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.6-3.6"/>',
  tv: '<rect x="2.5" y="5" width="19" height="13" rx="2.5"/><path d="M8 22h8M12 18v4"/>',
  music: '<circle cx="6.5" cy="17.5" r="2.5"/><circle cx="17.5" cy="15" r="2.5"/><path d="M9 17.5V6l11-2v11"/>',
  film: '<rect x="2.5" y="4" width="19" height="16" rx="2.5"/><path d="M7 4v16M17 4v16M2.5 9h4.5M2.5 15h4.5M17 9h4.5M17 15h4.5"/>',
  wifi: '<path d="M5 12.5a10 10 0 0114 0M8.5 16a5.5 5.5 0 017 0M12 19.5h.01"/>',
  star: '<path d="M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
  link: '<path d="M10 13a5 5 0 007 0l2-2a5 5 0 00-7-7l-1 1M14 11a5 5 0 00-7 0l-2 2a5 5 0 007 7l1-1"/>',
  arrowup: '<path d="M12 19V5M5 12l7-7 7 7"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  folder: '<path d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2z"/>',
  skip: '<path d="M5 5l8.6 7L5 19z" fill="currentColor" stroke="none"/><rect x="15.4" y="5" width="3" height="14" rx="1.4" fill="currentColor" stroke="none"/>',
  prev: '<path d="M19 5l-8.6 7L19 19z" fill="currentColor" stroke="none"/><rect x="5.6" y="5" width="3" height="14" rx="1.4" fill="currentColor" stroke="none"/>',
  next: '<path d="M5 5l8.6 7L5 19z" fill="currentColor" stroke="none"/><rect x="15.4" y="5" width="3" height="14" rx="1.4" fill="currentColor" stroke="none"/>',
  volume: '<path d="M11 5L6 9H3v6h3l5 4zM16 9a4 4 0 010 6M19 6a8 8 0 010 12"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  expand: '<path d="M8 3H3v5M16 3h5v5M21 16v5h-5M3 16v5h5"/>',
  pip: '<rect x="3" y="4" width="18" height="16" rx="2.5"/><rect x="12" y="12" width="7" height="6" rx="1.5" fill="currentColor" stroke="none"/>',
  repeat: '<path d="M17 2l4 4-4 4"/><path d="M3 11V9a4 4 0 014-4h14"/><path d="M7 22l-4-4 4-4"/><path d="M21 13v2a4 4 0 01-4 4H3"/>',
  repeatOne: '<path d="M17 2l4 4-4 4"/><path d="M3 11V9a4 4 0 014-4h14"/><path d="M7 22l-4-4 4-4"/><path d="M21 13v2a4 4 0 01-4 4H3"/><path d="M11.2 10.4l1.4-.9V15"/>',
  shuffle: '<path d="M16 3h5v5"/><path d="M4 20L21 3.2"/><path d="M21 16v5h-5"/><path d="M15.2 15.2L21 21"/><path d="M4 4l5.2 5.2"/>',
  lyrics: '<path d="M4 5.5h16v10.5H9l-5 4z"/><path d="M8 9.5h8M8 12.5h5"/>',
  eq: '<path d="M4 21v-6M4 11V3M12 21v-9M12 8V3M20 21v-4M20 13V3"/><path d="M1.5 15h5M9.5 8h5M17.5 17h5"/>',
  volumeMute: '<path d="M11 5L6 9H3v6h3l5 4z"/><path d="M17 9.5l4 5M21 9.5l-4 5"/>',
  queue: '<path d="M4 6h11M4 12h11M4 18h7"/><path d="M17 12v7"/><circle cx="15" cy="19" r="2"/><path d="M17 12l4-1v7"/><circle cx="19" cy="18" r="2"/>',
};
export function icon(name, cls = '') {
  const body = ICONS[name] || ICONS.info;
  return `<svg viewBox="0 0 24 24" class="${cls}" fill="none" stroke="currentColor"
    stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;
}

// ── Toast（排队，不互相覆盖）──────────────────────────────
let toastTimer = null;
const toastQueue = [];
let toastBusy = false;

function pumpToast() {
  const t = $('#toast');
  if (!t) { toastQueue.length = 0; toastBusy = false; return; }
  if (!toastQueue.length) { toastBusy = false; return; }
  toastBusy = true;
  const [msg, kind] = toastQueue.shift();
  t.textContent = msg;
  t.className = 'toast on ' + kind;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    t.className = 'toast ' + kind;
    toastTimer = setTimeout(pumpToast, 180);
  }, 2200);
}

export function toast(msg, kind = '') {
  if (!msg) return;
  // 连续操作（比如连点收藏）时把消息排起来，而不是后一条顶掉前一条
  if (toastQueue.length > 3) toastQueue.shift();
  toastQueue.push([msg, kind]);
  if (!toastBusy) pumpToast();
}

// ── 弹窗 ──────────────────────────────────────────────────
export function dialog({ title = '', body = '', ok = '确定', cancel = '取消' } = {}) {
  return new Promise(resolve => {
    const root = $('#modal-root');
    const mask = el(`<div class="modal-mask">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>${esc(title)}</h3>
        <p>${esc(body)}</p>
        <div class="modal-actions">
          ${cancel ? `<button class="btn ghost" data-x="0">${esc(cancel)}</button>` : ''}
          <button class="btn primary" data-x="1">${esc(ok)}</button>
        </div>
      </div></div>`);
    root.appendChild(mask);
    const onKey = e => {
      if (e.key === 'Escape') { e.stopPropagation(); close(false); }
      if (e.key === 'Enter') { e.stopPropagation(); close(true); }
    };
    const close = v => {
      document.removeEventListener('keydown', onKey, true);
      mask.remove();
      resolve(v);
    };
    mask.addEventListener('click', e => {
      const b = e.target.closest('[data-x]');
      if (b) return close(b.dataset.x === '1');
      if (e.target === mask) close(false);
    });
    document.addEventListener('keydown', onKey, true);
    setTimeout(() => mask.querySelector('[data-x="1"]')?.focus(), 30);
  });
}

export function confirmBox(title, body, ok = '确定') {
  return dialog({ title, body, ok });
}

// ── 骨架 ──────────────────────────────────────────────────
export function skeletonRail(n = 7) {
  return `<div class="skl-grid">${Array.from({ length: n }, () =>
    `<div class="skl skl-card"></div>`).join('')}</div>`;
}
export function skeletonGrid(n = 12) {
  return `<div class="grid">${Array.from({ length: n }, () =>
    `<div class="skl skl-card" style="width:100%"></div>`).join('')}</div>`;
}

// 投屏：手机/电脑把「播这个地址」发给局域网里的电视。
// 电视端自己就是接收器（同一套网页），不做 DLNA/Cast。
import { $, esc, icon, toast } from './dom.js';
import { getToken } from './auth.js';

const ID_KEY = 'pr.dev';
const isTv = () => /ProomTV\//.test(navigator.userAgent) ||
  document.documentElement.classList.contains('tv');
const isPhone = () => /Proom(Phone|Mobile)\//.test(navigator.userAgent);

function deviceId() {
  // 原生给了稳定 id 就用它：电视端每次冷启动都会清 WebView 存储，
  // 靠 localStorage 生成的话投屏目标会变成一个"新设备"
  try {
    const nid = window.ProomNative?.deviceId?.();
    if (nid) return nid;
  } catch (e) { /* 浏览器里没有这个桥 */ }
  try {
    let id = localStorage.getItem(ID_KEY);
    if (!id) {
      id = 'd' + Math.random().toString(36).slice(2, 10);
      localStorage.setItem(ID_KEY, id);
    }
    return id;
  } catch (e) {
    return 'd' + Math.random().toString(36).slice(2, 10);
  }
}

async function post(path, body) {
  const res = await fetch(path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(getToken() ? { 'X-Token': getToken() } : {}),
    },
    body: JSON.stringify(body || {}),
  });
  const d = await res.json().catch(() => ({}));
  if (!res.ok || d.ok === false) throw new Error(d.msg || '投屏失败');
  return d;
}

async function getJSON(path) {
  const res = await fetch(path, {
    headers: getToken() ? { 'X-Token': getToken() } : {},
  });
  const d = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(d.msg || '请求失败');
  return d;
}

function deviceName() {
  if (isTv()) return '电视';
  if (isPhone()) return '手机';
  return '电脑';
}

/** 电视端：心跳 + 取指令；收到就播 */
export function startReceiver(onPlay) {
  if (!isTv()) return;
  const id = deviceId();

  const beat = () => {
    post('/api/cast/register', { id, name: deviceName(), kind: 'tv' }).catch(() => {});
  };
  const poll = async () => {
    try {
      const d = await getJSON('/api/cast/poll?id=' + encodeURIComponent(id));
      for (const c of (d.commands || [])) {
        if (c.kind === 'play' && c.url) onPlay(c);
      }
    } catch (e) { /* 网络抖一下就算了，下一轮再试 */ }
  };

  beat();
  setInterval(beat, 15000);
  setInterval(poll, 3000);
}

export async function listTargets() {
  const id = deviceId();
  const d = await getJSON('/api/cast/targets?id=' + encodeURIComponent(id));
  return (d.items || []).filter(x => x.kind === 'tv');
}

export async function castTo(target, info) {
  // 把「整部片 + 当前线路/集数/进度」一起给电视，电视那边才能换源换集
  return post('/api/cast/send', { target, ...info });
}

/** 手机/电脑：选一台电视投过去 */
export async function openCastSheet(info) {
  const box = $('#cast-sheet');
  if (!box) return;
  box.hidden = false;
  box.innerHTML = `<div class="cast-card">
    <div class="cast-head">
      <h3>投屏到</h3>
      <button class="icon-btn" id="cast-close" data-focus title="关闭">${icon('close')}</button>
    </div>
    <div class="cast-body" id="cast-body"><p class="hint">正在查找局域网里的电视…</p></div>
  </div>`;
  box.querySelector('#cast-close').onclick = closeCast;

  const body = box.querySelector('#cast-body');
  let items = [];
  // 面板开着的时候每 2 秒刷一次：电视刚打开也能自己冒出来，不用重开面板
  clearInterval(castTimer);
  castTimer = setInterval(() => {
    if (box.hidden) { clearInterval(castTimer); return; }
    renderTargets(body, info).catch(() => {});
  }, 2000);
  try {
    items = await listTargets();
  } catch (e) {
    body.innerHTML = `<p class="hint">拿不到设备列表：${esc(e.message)}</p>`;
    return;
  }

  renderTargets(body, info, items);
}

let castTimer = null;

/** 渲染设备列表（面板定时刷新也走这里） */
async function renderTargets(body, info, preloaded) {
  let items = preloaded;
  if (!items) {
    try { items = await listTargets(); } catch (e) {
      body.innerHTML = `<p class="hint">拿不到设备列表：${esc(e.message)}</p>`;
      return;
    }
  }
  if (!items.length) {
    body.innerHTML = `<p class="hint">没找到在线的电视。<br>
      电视上打开放映室（保持前台）就会出现在这里。</p>`;
    return;
  }
  // 列表没变就不重建，避免每 2 秒闪一下、焦点被打断
  const sig = items.map(x => x.id).join(',');
  if (body.dataset.sig === sig) return;
  body.dataset.sig = sig;

  body.innerHTML = items.map((d, i) => `
    <button class="cast-item" data-i="${i}" data-focus>
      <span class="cast-ico">${icon('tv')}</span>
      <span class="cast-name">${esc(d.name)}</span>
      <span class="cast-state">在线</span>
    </button>`).join('');

  body.querySelectorAll('[data-i]').forEach(btn => {
    btn.onclick = async () => {
      const d = items[+btn.dataset.i];
      btn.disabled = true;
      try {
        await castTo(d.id, info);
        closeCast();
        toast(`已投到「${d.name}」`);
      } catch (e) {
        toast(e.message, 'err');
        btn.disabled = false;
      }
    };
  });
}

export function closeCast() {
  clearInterval(castTimer);
  const box = $('#cast-sheet');
  if (box) { box.hidden = true; box.innerHTML = ''; }
}

export { isTv, isPhone };

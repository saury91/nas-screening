// 投屏：手机/电脑把「播这个地址」发给局域网里的电视。
// 电视端自己就是接收器（同一套网页），不做 DLNA/Cast。
import { $, esc, icon, toast } from './dom.js';
import { getToken } from './auth.js';
import { isTv, isPhone } from './platform.js';

const ID_KEY = 'pr.dev';

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

/** 局域网直连的电视（不需要 NAS）：回调式，原生发现完调 __proomCastResult */
export function discoverLocal(timeoutMs = 2500) {
  return new Promise((resolve) => {
    if (!window.ProomNative?.castDiscover) { resolve([]); return; }
    let done = false;
    const finish = (arr) => { if (!done) { done = true; resolve(arr || []); } };
    window.__proomCastResult = (json) => {
      try { finish(JSON.parse(json)); } catch (e) { finish([]); }
    };
    try { window.ProomNative.castDiscover(); } catch (e) { finish([]); }
    setTimeout(() => finish([]), timeoutMs);
  });
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
    <div class="cast-body" id="cast-body">
      <div id="cast-nas"><p class="hint">正在查找局域网里的电视…</p></div>
    </div>
  </div>`;
  box.querySelector('#cast-close').onclick = closeCast;

  const body = box.querySelector('#cast-body');
  const nasBox = box.querySelector('#cast-nas');
  localSig = null;                // 每次开面板都重新发现局域网
  window.__hasLocalCast = false;

  // NAS 只是「设备名录」之一，它不在不该让整个投屏失效：
  // 手机和电视本来就活在同一个局域网里，UDP 广播不需要经过 NAS。
  let items = null;
  let nasOk = true;
  try {
    items = await listTargets();
  } catch (e) {
    nasOk = false;
    nasBox.innerHTML = '';
  }

  if (nasOk) renderTargets(nasBox, info, items);
  renderLocal(body, info, nasOk);

  // 面板开着时定时刷新：NAS 在线就刷名录，离线就只刷局域网发现
  clearInterval(castTimer);
  castTimer = setInterval(() => {
    if (box.hidden) { clearInterval(castTimer); return; }
    if (nasOk) renderTargets(nasBox, info).catch(() => {});
    else renderLocal(body, info, false).catch(() => {});
  }, nasOk ? 2000 : 3000);
}

/** 局域网直连：不经过 NAS，NAS 关机也能投 */
let localSig = null;

async function renderLocal(body, info, nasOk = true) {
  const tvs = await discoverLocal(nasOk ? 2500 : 1800);
  window.__hasLocalCast = tvs.length > 0;

  // 空结果也要走一遍：首次「一台都没找到」时必须给提示，不能被去重吞掉
  const sig = tvs.length ? tvs.map(t => t.url).join(',') : '-';
  if (sig === localSig) return;          // 没变化就别重建，免得焦点被打断
  localSig = sig;

  let box = body.querySelector('.cast-local');
  if (!tvs.length) {
    if (box) box.remove();
    if (!nasOk) showOfflineHint(body);
    return;
  }
  if (!box) {
    box = document.createElement('div');
    box.className = 'cast-local';
    body.appendChild(box);
  }
  // 已经有直连设备了：把上面那句"没找到在线的电视"收掉，免得看着像失败
  const hint = body.querySelector('#cast-nas .hint');
  if (hint) hint.remove();

  box.innerHTML = '<div class="cast-group">直连电视<span>· 不需要 NAS</span></div>' +
    tvs.map((t, i) => `
      <button class="cast-item" data-li="${i}" data-focus>
        <span class="cast-ico">${icon('tv')}</span>
        <span class="cast-name">${esc(t.name)}</span>
        <span class="cast-state">直连</span>
      </button>`).join('');

  box.querySelectorAll('[data-li]').forEach(btn => {
    btn.onclick = async () => {
      const t = tvs[+btn.dataset.li];
      btn.disabled = true;
      try {
        const ok = window.ProomNative?.castPlay?.(t.url, JSON.stringify(info));
        if (ok) { closeCast(); toast(`已投到「${t.name}」`); }
        else { toast('电视没响应，检查它是否开着放映室', 'err'); btn.disabled = false; }
      } catch (e) {
        toast('投屏失败', 'err');
        btn.disabled = false;
      }
    };
  });
}

let castTimer = null;

// NAS 不在时的兜底文案：手机还有 UDP 这条腿，PC 就真的没辙了
function showOfflineHint(body) {
  const nasBox = body.querySelector('#cast-nas');
  if (!nasBox || nasBox.textContent.trim()) return;
  const hasNativeFinder = !!window.ProomNative?.castDiscover;
  nasBox.innerHTML = hasNativeFinder
    ? `<p class="hint">NAS 不在线，局域网里也没发现电视。<br>
       电视上打开放映室（保持前台）就会出现在这里。</p>`
    : `<p class="hint">NAS 不在线，投屏列表拿不到。<br>
       手机 App 可以不走 NAS 直连电视，网页端不行。</p>`;
}

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
    if (window.__hasLocalCast) { body.innerHTML = ''; return; }
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

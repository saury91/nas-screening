// 登录态：token 存 localStorage，请求统一带 X-Token。
// 首页 / 登录门都在这里，页面里只关心 me 和角色。
import { $, esc, toast } from './dom.js';

const KEY = 'pr.token';
let me = null;          // {id,name,role,avatar}
let profiles = [];
let initialized = false;
let pinIsDefault = false;
let onNeedLogin = null;

export function getToken() {
  try { return localStorage.getItem(KEY) || ''; } catch (e) { return ''; }
}

export function setToken(t) {
  try {
    if (t) localStorage.setItem(KEY, t);
    else localStorage.removeItem(KEY);
  } catch (e) { /* 隐私模式 */ }
}

export function currentUser() { return me; }
export function profileList() { return profiles; }
export function isAdmin() { return !!me && me.role === 'admin'; }
export function isReady() { return initialized; }
export function usingDefaultPin() { return pinIsDefault; }

function readJSON(res) {
  return res.text().then(t => {
    try { return JSON.parse(t); } catch (e) { return {}; }
  });
}

export async function fetchState() {
  const res = await fetch('/api/auth/state', {
    headers: getToken() ? { 'X-Token': getToken() } : {},
  });
  const d = await readJSON(res);
  initialized = !!d.initialized;
  pinIsDefault = !!d.needsPinChange;
  me = d.me || null;
  profiles = d.profiles || [];
  if (!me) setToken('');
  return d;
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
  const d = await readJSON(res);
  if (!res.ok || d.ok === false) {
    const err = new Error(d.msg || '请求失败');
    err.code = d.code;
    err.status = res.status;
    throw err;
  }
  return d;
}

export async function setup(name, pin) {
  const d = await post('/api/auth/setup', { name, pin });
  setToken(d.token);
  me = d.me; profiles = d.profiles || []; initialized = true;
  return d;
}

export async function login(pin) {
  // PIN 即身份：不再传 id
  const d = await post('/api/auth/login', { pin });
  setToken(d.token);
  me = d.me; profiles = d.profiles || [];
  return d;
}

export async function logout() {
  try { await post('/api/auth/logout', {}); } catch (e) { /* 忽略 */ }
  setToken('');
  me = null;
}

export async function refreshProfiles() {
  const res = await fetch('/api/profiles', {
    headers: getToken() ? { 'X-Token': getToken() } : {},
  });
  const d = await readJSON(res);
  if (d.items) profiles = d.items;
  return profiles;
}

export function needLogin() {
  if (onNeedLogin) onNeedLogin();
}

export function onAuthLost(fn) { onNeedLogin = fn; }

// ── 登录门 ────────────────────────────────────────────
// PIN 就是身份：不选账户，直接输 6 位数字，后端按 PIN 匹配到人。
export function showGate() {
  const box = $('#auth-gate');
  if (!box) return;
  box.hidden = false;
  document.body.classList.add('gated');

  if (!initialized) {
    box.innerHTML = `<div class="gate-card">
      <div class="gate-brand">
        <div class="gate-logo">${'▶'}</div>
        <div><h2>先建一个管理员</h2>
          <p class="hint">6 位数字 PIN，登录就靠它。</p></div>
      </div>
      <div class="gate-form">
        <label class="gate-field"><span>管理员名字</span>
          <input class="input" id="gate-name" maxlength="16" placeholder="例如：老爸" autocomplete="off"></label>
        <label class="gate-field"><span>PIN（6 位数字）</span>
          <input class="input pin-input" id="gate-pin" inputmode="numeric" maxlength="6"
                 placeholder="••••••" autocomplete="new-password"></label>
        <label class="gate-field"><span>再输一次</span>
          <input class="input pin-input" id="gate-pin2" inputmode="numeric" maxlength="6"
                 placeholder="••••••" autocomplete="new-password"></label>
        <p class="hint" id="gate-tip"></p>
        <button class="btn primary" id="gate-go" data-focus>创建并进入</button>
      </div>
    </div>`;
    bindSetup(box);
    return;
  }

  box.innerHTML = `<div class="gate-card gate-solo">
    <div class="gate-brand">
      <div class="gate-logo">${'▶'}</div>
      <div><h2>输入 PIN</h2><p class="hint">6 位数字，输完自动进入</p></div>
    </div>
    <input class="input pin-input pin-big" id="gate-pin" inputmode="numeric" maxlength="6"
           placeholder="••••••" autocomplete="off" aria-label="6 位 PIN">
    <p class="hint gate-err" id="gate-tip"></p>
  </div>`;
  bindPinGate(box);
}

function bindSetup(box) {
  const go = box.querySelector('#gate-go');
  const tip = box.querySelector('#gate-tip');
  const submit = async () => {
    const name = box.querySelector('#gate-name').value.trim();
    const pin = box.querySelector('#gate-pin').value.trim();
    const pin2 = box.querySelector('#gate-pin2').value.trim();
    if (!name) { tip.textContent = '填个名字'; return; }
    if (!/^\d{6}$/.test(pin)) { tip.textContent = 'PIN 必须是 6 位数字'; return; }
    if (pin !== pin2) { tip.textContent = '两次 PIN 不一致'; return; }
    go.disabled = true;
    try {
      await setup(name, pin);
      hideGate();
      location.reload();
    } catch (e) {
      tip.textContent = e.message;
      go.disabled = false;
    }
  };
  go.onclick = submit;
  box.querySelectorAll('input').forEach(i => {
    i.addEventListener('keydown', e => { if (e.key === 'Enter') submit(); });
  });
  setTimeout(() => box.querySelector('#gate-name')?.focus(), 60);
}

function bindPinGate(box) {
  const pinEl = box.querySelector('#gate-pin');
  const tip = box.querySelector('#gate-tip');
  let busy = false;

  const submit = async () => {
    const pin = pinEl.value.trim();
    if (pin.length !== 6 || busy) return;
    busy = true;
    pinEl.classList.add('loading');
    try {
      await login(pin);
      hideGate();
      location.reload();
    } catch (e) {
      tip.textContent = e.message;
      pinEl.value = '';
      pinEl.classList.remove('loading');
      pinEl.focus();
      busy = false;
    }
  };

  pinEl.addEventListener('input', () => {
    // 只收数字，满 6 位自动提交——电视遥控器上少按一次确认
    pinEl.value = pinEl.value.replace(/\D/g, '').slice(0, 6);
    tip.textContent = '';
    if (pinEl.value.length === 6) submit();
  });
  pinEl.addEventListener('keydown', e => {
    if (e.key === 'Enter') submit();
  });
  setTimeout(() => pinEl.focus(), 80);
}

export function hideGate() {
  const box = $('#auth-gate');
  if (box) { box.hidden = true; box.innerHTML = ''; }
  document.body.classList.remove('gated');
}

// ── 切换账户（已登录时）：输另一个人的 PIN 就换过去 ──────────
export function openSwitcher() {
  const box = $('#auth-gate');
  if (!box) return;
  box.hidden = false;
  document.body.classList.add('gated');
  box.innerHTML = `<div class="gate-card gate-solo">
    <div class="gate-brand">
      <div class="gate-logo">${esc((me && (me.avatar || me.name) || '?').slice(0, 1))}</div>
      <div><h2>${esc(me ? me.name : '')}</h2>
        <p class="hint">${me && me.role === 'admin' ? '管理员' : '家人'}
          · 输别人的 PIN 可以直接切过去</p></div>
    </div>
    <input class="input pin-input pin-big" id="sw-pin" inputmode="numeric" maxlength="6"
           placeholder="••••••" autocomplete="off" aria-label="切换账户 PIN">
    <p class="hint gate-err" id="sw-tip"></p>
    <div class="row" style="gap:10px;margin-top:14px">
      <button class="btn ghost" id="gate-cancel" data-focus>关闭</button>
      <button class="btn" id="gate-logout" data-focus>退出登录</button>
    </div>
  </div>`;

  const pinEl = box.querySelector('#sw-pin');
  const tip = box.querySelector('#sw-tip');
  let busy = false;

  box.querySelector('#gate-cancel').onclick = hideGate;
  box.querySelector('#gate-logout').onclick = async () => {
    await logout();
    location.reload();
  };

  const swap = async () => {
    const pin = pinEl.value.trim();
    if (pin.length !== 6 || busy) return;
    busy = true;
    try {
      await login(pin);
      location.reload();
    } catch (e) {
      tip.textContent = e.message;
      pinEl.value = '';
      pinEl.focus();
      busy = false;
    }
  };
  pinEl.addEventListener('input', () => {
    pinEl.value = pinEl.value.replace(/\D/g, '').slice(0, 6);
    tip.textContent = '';
    if (pinEl.value.length === 6) swap();
  });
  pinEl.addEventListener('keydown', e => { if (e.key === 'Enter') swap(); });
  setTimeout(() => pinEl.focus(), 80);
}

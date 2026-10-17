// 本地自检：真实鼠标点击画中画按钮，观察 PiP 内视频是否继续播放（不参与部署）
// 用法: CDP_PORT=9350 node tools/pip-test.mjs "<页面url>"
import fs from 'node:fs';

const url = process.argv[2];
const PORT = process.env.CDP_PORT || '9222';
const VW = Number(process.env.VW || 1080);
const VH = Number(process.env.VH || 620);

const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const page = targets.find(t => t.type === 'page');
if (!page) throw new Error('没有可用的 page target');

const ws = new WebSocket(page.webSocketDebuggerUrl);
const pending = new Map();
let seq = 0;
const logs = [];

ws.onmessage = ev => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
  }
  if (msg.method === 'Runtime.exceptionThrown') {
    logs.push('EXCEPTION: ' + (msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text));
  }
  if (msg.method === 'Log.entryAdded' && msg.params.entry.level === 'error') {
    logs.push('LOG: ' + msg.params.entry.text);
  }
};
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

function send(method, params = {}) {
  const id = ++seq;
  ws.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function evalJs(expr) {
  const r = await send('Runtime.evaluate', {
    expression: expr, returnByValue: true, awaitPromise: true, userGesture: true,
  });
  return r.result.value;
}
async function clickAt(x, y) {
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'none' });
  await sleep(80);
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
  await sleep(40);
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
}

await send('Page.enable');
await send('Runtime.enable');
await send('Network.enable');
await send('Network.setCacheDisabled', { cacheDisabled: true });
await send('Log.enable');
await send('Emulation.setDeviceMetricsOverride', {
  width: VW, height: VH, deviceScaleFactor: 1, mobile: false,
});
await send('Page.navigate', { url });
await sleep(600);
await send('Page.reload', { ignoreCache: true });
await sleep(9000);

// 控制栏会因鼠标静止而淡出，先晃一下
await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: VW / 2, y: VH / 2, button: 'none' });
await sleep(500);

const rect = await evalJs(`(() => {
  const b = document.querySelector('.art-control-pip');
  if (!b) return null;
  const r = b.getBoundingClientRect();
  return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
})()`);
console.log('PIP 按钮:', JSON.stringify(rect));

const MODE = process.env.MODE || 'normal';
if (MODE === 'paused') {
  await evalJs(`document.querySelector('video').pause(), 1`);
  await sleep(400);
}

const before = await evalJs(`(() => {
  const v = document.querySelector('video');
  return { paused: v.paused, t: Math.round(v.currentTime), pip: !!document.pictureInPictureElement };
})()`);

// closebtn 场景不需要先进画中画
if (rect && MODE !== 'closebtn') await clickAt(rect.x, rect.y);
await sleep(1500);

const after = await evalJs(`(() => {
  const v = document.querySelector('video');
  return {
    paused: v.paused, t: Math.round(v.currentTime),
    pip: !!document.pictureInPictureElement,
    pipEl: document.pictureInPictureElement === v,
    display: getComputedStyle(v).display,
    state: document.querySelector('.art-video-player')?.className || '',
    pipBtnOn: document.querySelector('.art-control-pip')?.classList.contains('on') || false,
    pipOut: document.querySelector('#playerlayer').classList.contains('pip-out'),
    layerOpacity: getComputedStyle(document.querySelector('#playerlayer')).opacity,
    bodyLocked: document.body.classList.contains('locked'),
    canScroll: getComputedStyle(document.body).overflow,
    layerPE: getComputedStyle(document.querySelector('#playerlayer')).pointerEvents,
    hitTop: (() => { const e = document.elementFromPoint(600, 120); return e ? e.tagName + '.' + (e.className || '').toString().slice(0, 40) : null; })(),
    hitMid: (() => { const e = document.elementFromPoint(600, 260); return e ? e.tagName + '.' + (e.className || '').toString().slice(0, 40) : null; })(),
    hitNav: (() => { const e = document.elementFromPoint(230, 30); return e ? e.tagName + '.' + (e.className || '').toString().slice(0, 40) : null; })(),
  };
})()`);
await sleep(2500);
const later = await evalJs(`(() => {
  const v = document.querySelector('video');
  return { paused: v.paused, t: Math.round(v.currentTime), pip: !!document.pictureInPictureElement };
})()`);

if (MODE === 'close') {
  // 模拟用户点完 PiP 直接按 Esc 关播放器
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 });
  await sleep(900);
  const closed = await evalJs(`({
    pip: !!document.pictureInPictureElement,
    playerHidden: document.querySelector('#playerlayer').hidden,
    videoAlive: !!document.querySelector('video'),
    route: location.hash,
  })`);
  console.log('AFTER-ESC:', JSON.stringify(closed, null, 1));
}

if (MODE === 'closebtn') {
  const r = await evalJs(`(() => {
    const b = document.querySelector('#pl-close');
    const q = b.getBoundingClientRect();
    return { x: Math.round(q.x + q.width / 2), y: Math.round(q.y + q.height / 2) };
  })()`);
  console.log('BEFORE-CLICK:', JSON.stringify(await evalJs(`(() => {
    const b = document.querySelector('#pl-close');
    const q = b.getBoundingClientRect();
    const hit = document.elementFromPoint(Math.round(q.x + q.width / 2), Math.round(q.y + q.height / 2));
    return {
      locked: document.body.classList.contains('locked'),
      hidden: document.querySelector('#playerlayer').hidden,
      topCls: document.querySelector('#pl-top').className,
      hit: hit ? hit.tagName + '.' + String(hit.className).slice(0, 30) : null,
      hash: location.hash,
    };
  })()`)));
  await clickAt(r.x, r.y);
  await sleep(800);
  const st = await evalJs(`({
    hidden: document.querySelector('#playerlayer').hidden,
    videoAlive: !!document.querySelector('video'),
    locked: document.body.classList.contains('locked'),
    hash: location.hash,
    innerLen: document.querySelector('#playerlayer').innerHTML.length,
  })`);
  console.log('AFTER-CLOSEBTN:', JSON.stringify(st));
}

if (MODE === 'pipclose') {
  // 模拟关闭小窗：Chrome 会先暂停视频再退出画中画
  await evalJs(`(() => { const v = document.querySelector('video'); v.pause(); return 1; })()`);
  await sleep(200);
  await evalJs(`document.exitPictureInPicture().catch(() => {}), 1`);
  await sleep(900);
  const closed = await evalJs(`({
    pip: !!document.pictureInPictureElement,
    playerHidden: document.querySelector('#playerlayer').hidden,
    videoAlive: !!document.querySelector('video'),
    bodyLocked: document.body.classList.contains('locked'),
  })`);
  console.log('AFTER-PIPCLOSE:', JSON.stringify(closed, null, 1));
}

if (MODE === 'backtotab') {
  // 模拟小窗上的「返回标签页」：exitPictureInPicture 且视频不暂停
  await evalJs(`document.exitPictureInPicture().catch(() => {}), 1`);
  await sleep(900);
  const back = await evalJs(`({
    pip: !!document.pictureInPictureElement,
    pipOut: document.querySelector('#playerlayer').classList.contains('pip-out'),
    opacity: getComputedStyle(document.querySelector('#playerlayer')).opacity,
    paused: document.querySelector('video')?.paused,
    t: Math.round(document.querySelector('video')?.currentTime || 0),
    bodyLocked: document.body.classList.contains('locked'),
  })`);
  console.log('AFTER-BACK:', JSON.stringify(back, null, 1));
}

if (MODE === 'navigate') {
  // 小窗播着，点顶部导航：页面应该正常跳转
  const nav = await evalJs(`(() => {
    const a = document.querySelector('#tabs a[data-tab="/home"]');
    if (!a) return null;
    const r = a.getBoundingClientRect();
    return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
  })()`);
  console.log('NAV RECT:', JSON.stringify(nav));
  await evalJs(`window.__clicks = []; document.addEventListener('click', e => {
    window.__clicks.push((e.target.tagName || '') + '.' + String(e.target.className || '').slice(0, 30));
  }, true); 1`);
  if (nav) await clickAt(nav.x, nav.y);
  await sleep(1400);
  const nav2 = await evalJs(`({ hash: location.hash, pip: !!document.pictureInPictureElement,
    pipOut: document.querySelector('#playerlayer')?.classList.contains('pip-out'),
    clicks: window.__clicks,
    atPoint: (() => { const e = document.elementFromPoint(${nav ? nav.x : 0}, ${nav ? nav.y : 0}); return e ? e.tagName + '.' + String(e.className).slice(0, 40) : null; })(),
    layerPE: getComputedStyle(document.querySelector('#playerlayer')).pointerEvents })`);
  console.log('AFTER-NAV:', JSON.stringify(nav2, null, 1));
}

if (MODE === 'restore') {
  // 小窗播着，用户回到详情页再点「立即播放」：应该收回大屏继续播
  const btn = await evalJs(`(() => {
    const b = document.querySelector('#btn-play');
    if (!b) return null;
    const r = b.getBoundingClientRect();
    return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
  })()`);
  if (btn) await clickAt(btn.x, btn.y);
  await sleep(1400);
  const back = await evalJs(`({
    pip: !!document.pictureInPictureElement,
    pipOut: document.querySelector('#playerlayer').classList.contains('pip-out'),
    opacity: getComputedStyle(document.querySelector('#playerlayer')).opacity,
    paused: document.querySelector('video')?.paused,
    t: Math.round(document.querySelector('video')?.currentTime || 0),
  })`);
  console.log('AFTER-RESTORE:', JSON.stringify(back, null, 1));
}

console.log('BEFORE:', JSON.stringify(before, null, 1));
console.log('AFTER :', JSON.stringify(after, null, 1));
console.log('LATER :', JSON.stringify(later, null, 1));
if (logs.length) console.log('CONSOLE:\n' + logs.join('\n'));

const shot = await send('Page.captureScreenshot', { format: 'png' });
fs.writeFileSync('/tmp/pip-shot.png', Buffer.from(shot.data, 'base64'));
console.log('SHOT: /tmp/pip-shot.png');

// 收尾：退出 PiP
try { await evalJs(`document.exitPictureInPicture && document.exitPictureInPicture().catch(() => {})`); } catch (e) {}
ws.close();

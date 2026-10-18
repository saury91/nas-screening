// 本地自检：真实鼠标点击播放（不参与部署）
// 用法: CDP_PORT=9333 node tools/play-test.mjs "<url>" [等待毫秒]
import fs from 'node:fs';

const url = process.argv[2] || 'http://127.0.0.1:8899/#/music';
const PORT = process.env.CDP_PORT || '9222';
const W = Number(process.env.VW || 1440);
const H = Number(process.env.VH || 900);
const OUT = process.env.OUT || '';

const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const page = targets.find(t => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
const pending = new Map();
let seq = 0;
const logs = [];
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    const { resolve, reject } = pending.get(m.id);
    pending.delete(m.id);
    m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result);
  }
  if (m.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(m.params.type)) {
    logs.push(m.params.type + ': ' + m.params.args.map(a => a.value || a.description || '').join(' '));
  }
  if (m.method === 'Runtime.exceptionThrown') {
    logs.push('EXCEPTION: ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text));
  }
  if (m.method === 'Log.entryAdded' && m.params.entry.level !== 'verbose') {
    logs.push('LOG[' + m.params.entry.level + ']: ' + m.params.entry.text);
  }
};
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });

function send(method, params = {}) {
  const id = ++seq;
  ws.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
const evalJs = async expr => (await send('Runtime.evaluate', {
  expression: expr, returnByValue: true, awaitPromise: true,
})).result.value;

await send('Page.enable');
await send('Runtime.enable');
await send('Network.enable');
await send('Network.setCacheDisabled', { cacheDisabled: true });
await send('Log.enable');
await send('Emulation.setDeviceMetricsOverride', {
  width: W, height: H, deviceScaleFactor: 1, mobile: false,
});
await send('Page.navigate', { url });
await sleep(Number(process.env.WAIT || 4000));

// 找到第一首歌卡片，算出屏幕坐标
const pt = await evalJs(`(() => {
  const c = document.querySelector('#m-online-home .song-card') || document.querySelector('.song-card');
  if (!c) return null;
  const r = c.getBoundingClientRect();
  return [Math.round(r.left + r.width / 2), Math.round(r.top + r.height / 2), c.querySelector('.sc-title')?.textContent || ''];
})()`);
console.log('CARD:', JSON.stringify(pt));

if (pt) {
  const [x, y] = pt;
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'none' });
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
  await sleep(4500);
}

const state = await evalJs(`(async () => {
  const a = document.querySelector('#audio');
  const mp = document.querySelector('#miniplayer');
  return {
    title: mp && !mp.hidden ? mp.querySelector('.mp-title').textContent : null,
    paused: a ? a.paused : null,
    muted: a ? a.muted : null,
    cur: a ? +a.currentTime.toFixed(1) : null,
    dur: a ? +(a.duration || 0).toFixed(1) : null,
    blocked: !!document.querySelector('#miniplayer .mp-hint'),
    hint: (document.querySelector('.mp-hint') || {}).textContent || '',
  };
})()`);
console.log('STATE:', JSON.stringify(state, null, 2));
if (logs.length) console.log('CONSOLE:\n' + logs.join('\n'));

if (OUT) {
  const shot = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(OUT, Buffer.from(shot.data, 'base64'));
  console.log('SHOT:', OUT);
}
ws.close();

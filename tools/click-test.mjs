// 本地自检：单击/双击画面的行为（不参与部署）
// 用法: CDP_PORT=9350 node tools/click-test.mjs "<页面url>"
import fs from 'node:fs';

const url = process.argv[2];
const PORT = process.env.CDP_PORT || '9222';
const VW = Number(process.env.VW || 1080);
const VH = Number(process.env.VH || 620);

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
  if (m.method === 'Runtime.exceptionThrown') {
    logs.push('EXCEPTION: ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text));
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
  expression: expr, returnByValue: true, awaitPromise: true, userGesture: true,
})).result.value;

async function tap(x, y) {
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, button: 'none' });
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
  await sleep(30);
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
}

await send('Page.enable');
await send('Runtime.enable');
await send('Network.enable');
await send('Network.setCacheDisabled', { cacheDisabled: true });
await send('Log.enable');
await send('Emulation.setDeviceMetricsOverride', { width: VW, height: VH, deviceScaleFactor: 1, mobile: false });
await send('Page.navigate', { url });
await sleep(600);
await send('Page.reload', { ignoreCache: true });
await sleep(10000);

const cx = Math.round(VW / 2), cy = Math.round(VH / 2) - 40;
const probe = () => evalJs(`(() => {
  const v = document.querySelector('video');
  const p = document.querySelector('.art-video-player');
  return {
    paused: v.paused,
    t: Math.round(v.currentTime),
    mask: p ? p.className.includes('art-mask-show') : null,
    fs: !!document.fullscreenElement,
    flash: (() => {
      const f = document.querySelector('.pl-flash');
      if (!f) return null;
      const p = f.querySelector('path');
      return { on: f.classList.contains('on'), d: p ? p.getAttribute('d').slice(0, 12) : '' };
    })(),
  };
})()`);

console.log('START:', JSON.stringify(await probe()));

// 双击：两次点击间隔 90ms，应只进全屏、不暂停、不闪 mask
await tap(cx, cy);
await sleep(90);
await tap(cx, cy);
await sleep(160);
const during = await probe();
await sleep(1200);
const afterDbl = await probe();
console.log('DBL+160ms:', JSON.stringify(during));
console.log('DBL+1.3s :', JSON.stringify(afterDbl));

// 全屏状态下再双击：应退出全屏，播放状态不变
await tap(cx, cy);
await sleep(90);
await tap(cx, cy);
await sleep(700);
console.log('DBL-EXIT:', JSON.stringify(await probe()));

// 单击：应暂停
await tap(cx, cy);
await sleep(500);
const afterSingle = await probe();
await sleep(900);
console.log('PAUSED+1.4s:', JSON.stringify(await probe()));
console.log('SINGLE+500ms:', JSON.stringify(afterSingle));

// 再单击：应恢复播放
await tap(cx, cy);
await sleep(500);
console.log('SINGLE+500ms:', JSON.stringify(await probe()));

if (logs.length) console.log('CONSOLE:\n' + logs.join('\n'));
const shot = await send('Page.captureScreenshot', { format: 'png' });
fs.writeFileSync('/tmp/click-shot.png', Buffer.from(shot.data, 'base64'));
console.log('SHOT: /tmp/click-shot.png');
ws.close();

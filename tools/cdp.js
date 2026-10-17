// 本地视觉/交互自检工具（不参与部署）：通过 CDP 驱动无头 Chrome
// 用法: node tools/cdp.js <url> <out.png> [js表达式] [等待毫秒]
import fs from 'node:fs';

const [, , url, out, expr = '1', wait = '3500'] = process.argv;
const PORT = process.env.CDP_PORT || '9222';

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
  if (msg.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(msg.params.type)) {
    logs.push(msg.params.type + ': ' + msg.params.args.map(a => a.value || a.description || '').join(' '));
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

await send('Page.enable');
await send('Runtime.enable');
await send('Network.enable');
await send('Network.setCacheDisabled', { cacheDisabled: true });
await send('Log.enable');
await send('Emulation.setDeviceMetricsOverride', {
  width: Number(process.env.VW || 1440), height: Number(process.env.VH || 900),
  deviceScaleFactor: 1, mobile: false,
});
await send('Input.dispatchMouseEvent', {
  type: 'mouseMoved',
  x: (process.env.HOVER || '').split(',')[0] ? Number(process.env.HOVER.split(',')[0]) : 1,
  y: (process.env.HOVER || '').split(',')[1] ? Number(process.env.HOVER.split(',')[1]) : 1,
  button: 'none',
});
await send('Page.navigate', { url });
await new Promise(r => setTimeout(r, 300));
await send('Page.reload', { ignoreCache: true });
await new Promise(r => setTimeout(r, Number(wait)));

if (process.env.HOVER) {
  const [hx, hy] = process.env.HOVER.split(',').map(Number);
  await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: hx, y: hy, button: 'none' });
  await new Promise(r => setTimeout(r, 500));
}

const res = await send('Runtime.evaluate', {
  expression: expr, returnByValue: true, awaitPromise: true,
  userGesture: true,
});
console.log('EVAL:', JSON.stringify(res.result.value, null, 2));
if (logs.length) console.log('CONSOLE:\n' + logs.join('\n'));

if (out && out !== '-') {
  const clip = process.env.CLIP ? JSON.parse(process.env.CLIP) : null;
  const shot = await send('Page.captureScreenshot', {
    format: 'png', captureBeyondViewport: false,
    ...(clip ? { clip: { ...clip, scale: Number(process.env.CLIP_SCALE || 3) } } : {}),
  });
  fs.writeFileSync(out, Buffer.from(shot.data, 'base64'));
  console.log('SHOT:', out);
}
ws.close();

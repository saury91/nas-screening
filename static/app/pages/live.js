// 直播：分组切换 + 频道搜索 + 全屏播放
import { api } from '../core/api.js';
import { esc, icon, toast, debounce } from '../core/dom.js';
import { openPlayer } from '../components/player.js';
import { emptyState } from '../components/ui.js';

let st = { groups: [], items: [], grp: '', kw: '' };

export function destroy() { st = { groups: [], items: [], grp: '', kw: '' }; }

export async function render(root) {
  root.innerHTML = `<div class="page">
    <div class="page-head">
      <div><div class="eyebrow">直播</div><h1 class="page-title">电视直播</h1></div>
      <p class="page-sub" id="sum">正在读频道表…</p>
      <span class="spacer"></span>
      <div class="row" style="gap:8px">
        <input class="input" id="live-kw" placeholder="搜频道" style="width:170px;height:38px">
        <button class="btn sm" id="live-check" data-focus>${icon('wifi')}检测可用</button>
        <button class="btn sm" id="live-refresh" data-focus>${icon('refresh')}刷新源</button>
      </div>
    </div>
    <div class="chips" id="groups" style="margin-bottom:22px"></div>
    <div id="chlist"></div>
  </div>`;

  root.querySelector('#live-refresh').onclick = async () => {
    const b = root.querySelector('#live-refresh');
    b.disabled = true;
    toast('正在刷新直播源，可能需要十几秒…');
    try {
      const r = await api.refreshLive();
      toast(r.msg || '已刷新', 'ok');
      await load(root, true);
    } catch (e) { toast(e.message, 'err'); }
    b.disabled = false;
  };

  root.querySelector('#live-check').onclick = async () => {
    const b = root.querySelector('#live-check');
    if (b.disabled) return;
    const keep = b.innerHTML;
    b.disabled = true;
    b.innerHTML = '<span class="spin-sm"></span>检测中…';
    try {
      const r = await api.checkLive(st.grp);
      if (!r.checked) toast('这个分组里没有频道', 'err');
      else toast(`测了 ${r.checked} 个：可用 ${r.ok}，失效 ${r.dead}`, r.ok ? 'ok' : 'err');
      await load(root, true);
    } catch (e) {
      toast(e.message, 'err');
    }
    b.innerHTML = keep;
    b.disabled = false;
  };

  root.querySelector('#groups')?.addEventListener('click', e => {
    const b = e.target.closest('[data-g]');
    if (!b) return;
    st.grp = b.dataset.g;
    root.querySelectorAll('#groups .chip').forEach(x => x.classList.toggle('on', x === b));
    paint(root);
  });

  root.querySelector('#live-kw')?.addEventListener('input', debounce(e => {
    st.kw = e.target.value.trim();
    paint(root);
  }, 220));

  await load(root, true);
}

async function load(root, force) {
  if (!force && st.items.length) return paint(root);
  try {
    const d = await api.live();
    st.groups = d.groups || [];
    st.items = d.items || [];
    const sum = root.querySelector('#sum');
    if (sum) sum.textContent = st.items.length
      ? `${st.items.length} 个频道 · ${st.groups.length} 个分组`
      : '还没有频道，去设置里导入直播源';
    renderGroups(root);
    paint(root);
  } catch (e) {
    root.querySelector('#chlist').innerHTML = emptyState({
      iconName: 'tv', title: '频道表读取失败', desc: e.message,
      action: '<button class="btn" onclick="location.reload()">重试</button>',
    });
  }
}

function renderGroups(root) {
  root.querySelector('#groups').innerHTML =
    `<button class="chip ${st.grp === '' ? 'on' : ''}" data-g="" data-focus>全部</button>` +
    st.groups.map(g => `<button class="chip ${st.grp === g.group ? 'on' : ''}"
      data-g="${esc(g.group)}" data-focus>${esc(g.group)}<span style="opacity:.5">${g.count}</span></button>`).join('');
}

function paint(root) {
  try {
    _paint(root);
  } catch (e) {
    // 以前这里一抛错就静默停在上一屏（常见表现：上面写着有 400 多个频道，下面空白）
    console.error(e);
    const box = root.querySelector('#chlist');
    if (box) {
      box.innerHTML = emptyState({
        iconName: 'tv', title: '频道列表渲染出错',
        desc: String((e && e.message) || e),
      });
    }
  }
}

function _paint(root) {
  const box = root.querySelector('#chlist');
  // 分组列表可能刚被「自动汇总」重建过，旧的分组名已经不存在了：
  // 这时要回退到「全部」，否则会永远筛出 0 条
  if (st.grp && !st.groups.some(g => g.group === st.grp)) {
    st.grp = '';
    root.querySelectorAll('#groups .chip').forEach(x => x.classList.toggle('on', !x.dataset.g));
  }
  let items = (st.items || []).filter(x => x && typeof x.name === 'string' && x.name);
  if (st.grp) items = items.filter(x => x.group === st.grp);
  if (st.kw) items = items.filter(x => x.name.includes(st.kw));

  if (!items.length) {
    box.innerHTML = emptyState({
      iconName: 'tv',
      title: st.kw ? '没有这个频道' : (st.grp ? '这个分组里暂时没有频道' : '这里还没有频道'),
      desc: st.kw ? '换个关键词试试。'
        : st.grp ? '换一个分组看看，或点右上角「检测可用」重新测一遍。'
        : '直播源可能没抓成功，去设置 → 直播源里手动导入一个 M3U / TXT。',
      action: (st.kw || st.grp) ? '' : '<a class="btn" href="#/settings/live" data-focus>去导入直播源</a>',
    });
    return;
  }
  // 测过的排前面（可用的最前），没测过的保持原顺序
  const rank = c => (c.alive === 1 ? 0 : c.alive === 0 ? 2 : 1);
  items = items.slice().sort((a, b) => rank(a) - rank(b));

  box.innerHTML = `<div class="live-grid">${items.slice(0, 240).map((c, i) => `
    <button class="ch-card ${c.alive === 0 ? 'dead' : ''}" data-focus data-ch="${i}"
            aria-label="播放 ${esc(c.name)}">
      <span class="ch-glow"></span>
      <span class="row" style="justify-content:space-between">
        <span class="ch-logo">${esc(c.name.slice(0, 1))}</span>
        <span class="tag">${esc((c.urls || []).length > 1 ? (c.urls.length + ' 线路') : 'HD')}</span>
      </span>
      <span>
        <span class="ch-name">${esc(c.name)}</span>
        <span class="ch-sub">${esc(c.group || '未分组')}${
          c.alive === 1 ? '<i class="st ok">可播</i>'
          : c.alive === 0 ? '<i class="st dead">失效</i>' : ''}</span>
      </span>
      <span class="live-play"><span>${icon('play')}</span></span>
    </button>`).join('')}</div>`;

  box.onclick = e => {
    const card = e.target.closest('[data-ch]');
    if (!card) return;
    const c = items[+card.dataset.ch];
    playChannel(c);
  };
}

function playChannel(c) {
  const item = {
    name: c.name, year: '', pic: '',
    sources: [{
      site: '直播', count: (c.urls || []).length,
      eps: (c.urls || []).map((u, i) => ({ name: (c.urls.length > 1 ? '线路 ' + (i + 1) : '直播'), url: u })),
    }],
  };
  openPlayer(item, { si: 0, ei: 0, isLive: true, key: 'live:' + c.name });
}

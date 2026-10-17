// 搜索结果页：影视聚合 / 磁力资源两个标签
import { api } from '../core/api.js';
import { esc, icon, toast, fmtSize } from '../core/dom.js';
import { posterCard, emptyState } from '../components/ui.js';

let st = { kw: '', tab: 'online', online: null, mags: null };

export function destroy() { st = { kw: '', tab: 'online', online: null, mags: null }; }

export async function render(root, ctx) {
  const kw = (ctx.query.q || '').trim();
  // 从详情页「BT 找 4K」跳过来时直接落在磁力标签
  const tab = ctx.query.tab === 'magnet' ? 'magnet' : 'online';
  st = { kw, tab, online: null, mags: null, autoMagnet: tab === 'magnet' };

  root.innerHTML = `<div class="page">
    <div class="page-head">
      <div><div class="eyebrow">搜索</div>
        <h1 class="page-title">${kw ? esc(kw) : '搜点什么'}</h1></div>
      <p class="page-sub" id="sum">正在问 ${'十几个'} 个片源…</p>
      <span class="spacer"></span>
      <button class="btn sm" id="again" data-focus>${icon('refresh')}重新搜索</button>
    </div>
    <div class="tabs-inline" id="tabs">
      <button class="${tab === 'online' ? 'on' : ''}" data-tab="online" data-focus>影视</button>
      <button class="${tab === 'magnet' ? 'on' : ''}" data-tab="magnet" data-focus>磁力下载</button>
    </div>
    <div id="result"></div>
  </div>`;

  if (!kw) {
    root.querySelector('#result').innerHTML = emptyState({
      iconName: 'search', title: '还没输入关键词',
      desc: '按 / 打开搜索，片名、演员、导演都能搜。',
      action: '<button class="btn" data-open-search data-focus>打开搜索</button>',
    });
    root.querySelector('[data-open-search]').onclick = () =>
      document.querySelector('#btn-search').click();
    root.querySelector('#sum').textContent = '';
    return;
  }

  root.querySelector('#tabs').addEventListener('click', e => {
    const b = e.target.closest('[data-tab]');
    if (!b) return;
    st.tab = b.dataset.tab;
    root.querySelectorAll('#tabs button').forEach(x => x.classList.toggle('on', x === b));
    if (st.tab === 'magnet' && !st.mags) loadMagnet(root);
    else load(root);
  });
  root.querySelector('#again').onclick = () => { st.online = null; st.mags = null; load(root, true); };

  if (tab === 'magnet') await loadMagnet(root);
  else await load(root);
}

async function load(root, force) {
  const box = root.querySelector('#result');
  if (st.tab === 'online') {
    if (!st.online || force) {
      box.innerHTML = `<div class="grid">${Array(12).fill('<div class="skl skl-card" style="width:100%"></div>').join('')}</div>`;
      try {
        const d = await api.online(st.kw);
        st.online = d.items || [];
      } catch (e) {
        st.online = [];
        toast(e.message, 'err');
      }
    }
    const sum = root.querySelector('#sum');
    if (sum) sum.textContent = st.online.length
      ? `${st.online.length} 部相关作品，按片名相关度排序` : '没有找到相关作品';
    paint(root);
  } else {
    await loadMagnet(root, force);
  }
}

async function loadMagnet(root, force) {
  const box = root.querySelector('#result');
  if (st.mags && !force) return paint(root);
  box.innerHTML = `<p class="result-hint">正在抓磁力站…这一步可能要几秒</p>
    <div class="skl" style="height:120px;border-radius:14px;margin-bottom:10px"></div>
    <div class="skl" style="height:120px;border-radius:14px"></div>`;
  try {
    const d = await api.magnets(st.kw);
    st.mags = d.items || [];
  } catch (e) {
    st.mags = [];
    toast(e.message, 'err');
  }
  const sum = root.querySelector('#sum');
  if (sum) sum.textContent = st.mags.length ? `${st.mags.length} 个磁力资源` : '磁力站没有返回结果';
  paint(root);
}

function paint(root) {
  const box = root.querySelector('#result');
  if (st.tab === 'online') {
    if (!st.online) return;
    box.innerHTML = st.online.length
      ? `<div class="grid">${st.online.map(x => posterCard(x, {
          sub: [x.year, x.type, x.site_count + ' 源'].filter(Boolean).join(' · '),
        })).join('')}</div>`
      : emptyState({
          iconName: 'search', title: '没有匹配的作品',
          desc: '试试更短的片名，或者到片源管理里看看源是不是需要更新。',
        });
    return;
  }
  if (!st.mags) return;
  box.innerHTML = st.mags.length ? st.mags.map((m, i) => `
    <div class="src-row" style="align-items:flex-start;flex-direction:column;gap:8px">
      <div class="t-name mono" style="font-size:12px;line-height:1.6">${esc(m.title)}</div>
      <div class="row" style="flex-wrap:wrap;gap:6px">
        ${(m.tags || []).map(t => `<span class="tag">${esc(t)}</span>`).join('')}
        ${m.size ? `<span class="pill">${esc(m.size)}</span>` : ''}
        ${m.seeders >= 0 ? `<span class="pill ${m.seeders > 5 ? 'ok' : 'warn'}">做种 ${m.seeders}</span>` : ''}
        <span class="pill">${esc(m.source || '磁力')}</span>
      </div>
      <div class="row" style="width:100%;justify-content:flex-end;gap:8px">
        <button class="btn sm" data-copy="${esc(m.magnet)}" data-focus>复制链接</button>
        <button class="btn sm primary" data-dl="${esc(m.magnet)}" data-focus>${icon('download')}下载</button>
      </div>
    </div>`).join('')
    : emptyState({ iconName: 'download', title: '没有磁力结果', desc: '换个关键词，或在设置里确认 qBittorrent 是否配好。' });

  box.onclick = async e => {
    const dl = e.target.closest('[data-dl]');
    if (dl) {
      dl.disabled = true;
      try {
        const r = await api.addTorrent(dl.dataset.dl, '电影');
        toast(r.ok ? '已提交到 qBittorrent' : (r.msg || '提交失败'), r.ok ? 'ok' : 'err');
      } catch (err) { toast(err.message, 'err'); }
      dl.disabled = false;
      return;
    }
    const cp = e.target.closest('[data-copy]');
    if (cp) {
      try {
        await navigator.clipboard.writeText(cp.dataset.copy);
        toast('磁力链接已复制');
      } catch (err) { toast('复制失败，手动选一下吧', 'err'); }
    }
  };
}

// 设置中心：片源 / 收藏 / 下载 / 播放记录 / 直播源 / 音乐 / 关于
import { api } from '../core/api.js';
import { esc, icon, toast, dialog, timeAgo, fmtSize, fmtSpeed, fmtClock } from '../core/dom.js';
import { emptyState, imgTag } from '../components/ui.js';
import { go } from '../core/router.js';
import { store } from '../core/store.js';
import { cacheDrop } from '../core/cache.js';
import { currentUser, isAdmin, refreshProfiles, usingDefaultPin, logout } from '../core/auth.js';

const NAV = [
  ['sources', '片源管理', 'link'],
  ['favorites', '收藏', 'heart'],
  ['downloads', '下载任务', 'download'],
  ['history', '播放记录', 'clock'],
  ['live', '直播源', 'tv'],
  ['music', '音乐', 'music'],
  ['accounts', '家庭账户', 'heart'],
  ['backup', '配置备份', 'upload'],
  ['about', '关于与自检', 'info'],
];

let timer = null;

export function destroy() { clearInterval(timer); timer = null; }

const ADMIN_TABS = ['sources', 'downloads', 'live', 'accounts', 'backup'];

export async function render(root, ctx) {
  // 家人只要看自己的东西，管理类页面直接不出现（后端也会再挡一次）
  const nav = NAV.filter(([k]) => isAdmin() || !ADMIN_TABS.includes(k));
  const tab = nav.some(n => n[0] === ctx.seg[1]) ? ctx.seg[1] : nav[0][0];
  document.body.dataset.hero = '0';
  root.innerHTML = `<div class="page">
    <div class="page-head">
      <div><div class="eyebrow">设置</div><h1 class="page-title">控制台</h1></div>
      <p class="page-sub">片源、收藏、下载、音乐都在这里，主页只留内容</p>
    </div>
    <div class="settings-layout">
      <aside class="settings-nav">
        ${nav.map(([k, label, ic]) => `<a href="#/settings/${k}" data-focus
          class="${k === tab ? 'on' : ''}">${icon(ic)}${label}</a>`).join('')}
      </aside>
      <section class="settings-panel" id="panel">
        <div class="skl" style="height:180px;border-radius:18px"></div>
      </section>
    </div>
  </div>`;
  const panel = root.querySelector('#panel');
  const fn = { sources: sourcesPanel, favorites: favPanel, downloads: tasksPanel,
               history: historyPanel, live: livePanel, music: musicPanel,
               accounts: accountsPanel, backup: backupPanel, about: aboutPanel }[tab];
  try {
    await fn(panel);
  } catch (e) {
    panel.innerHTML = emptyState({ iconName: 'info', title: '这一页出错了', desc: e.message });
  }
}

// ═══════════ 片源管理 ═══════════
async function sourcesPanel(panel) {
  panel.innerHTML = `<h2>片源管理</h2>
    <p class="lead">支持 TVBox 配置地址一键导入。只接受直连接口（type 0 XML / type 1 JSON），
      依赖 jar、spider 的源导不进来——导入时会逐条告诉你原因，不会假装成功。</p>

    <div class="card-block">
      <h4>导入 TVBox 配置</h4>
      <p class="hint">黏一个配置地址，或者直接把 JSON 贴进来。导入的源立刻可用，不用重启。</p>
      <div class="import-row">
        <input class="input" id="imp-url" placeholder="https://.../tvbox.json 或 api.php/provide/vod/">
        <button class="btn primary" id="imp-btn" data-focus>${icon('link')}导入</button>
        <button class="btn" id="imp-paste" data-focus>粘贴 JSON</button>
      </div>
      <div id="imp-json" hidden style="margin-top:12px">
        <textarea class="input" id="imp-text" placeholder='{"sites":[{"name":"某某资源","api":"https://...","type":1}]}'></textarea>
        <div class="row" style="justify-content:flex-end;margin-top:10px">
          <button class="btn primary" id="imp-json-btn" data-focus>导入这段 JSON</button>
        </div>
      </div>
      <div id="imp-report" style="margin-top:12px"></div>
    </div>

    <div class="card-block">
      <div class="between">
        <div><h4>当前片源</h4><p class="hint" style="margin:0">按优先级排序；关掉的源不参与搜索和浏览。</p></div>
        <button class="btn sm" id="src-reload" data-focus>${icon('refresh')}刷新列表</button>
      </div>
      <div class="src-list" id="src-list" style="margin-top:16px">
        <div class="skl" style="height:64px;border-radius:12px"></div>
      </div>
      <div class="row" style="justify-content:flex-end;margin-top:14px">
        <button class="btn sm" id="add-src-toggle" data-focus>${icon('plus')}手动添加一个</button>
      </div>
      <div class="card-block" style="margin-top:18px">
        <div class="between">
          <div><h4>自动发现片源</h4>
            <p class="hint" style="margin:4px 0 0">从精选目录里读取影视仓配置，逐个发一次真实搜索验证，
              只把能用的加入列表（只增不减，每周最多自动跑一次）。</p></div>
          <button class="btn sm" id="src-discover" data-focus>${icon('search')}立即发现</button>
        </div>
        <p class="hint" id="src-discover-info" style="margin:10px 0 0"></p>
      </div>

      <div id="add-src" hidden style="margin-top:14px">
        <div class="setting-2col">
          <div class="field"><label>名称</label><input class="input" id="ns-name" placeholder="我的资源"></div>
          <div class="field"><label>接口地址</label><input class="input" id="ns-url" placeholder="https://.../api.php/provide/vod/"></div>
          <div class="field"><label>协议</label>
            <select class="input" id="ns-type"><option value="1">JSON（type 1）</option>
              <option value="0">XML（type 0）</option></select></div>
          <div class="field"><label>分类标签</label>
            <select class="input" id="ns-cat">
              ${['综合', '电影', '剧集', '动漫', '综艺', '纪录片'].map(c => `<option>${c}</option>`).join('')}
            </select></div>
        </div>
        <div class="row" style="justify-content:flex-end">
          <button class="btn primary sm" id="ns-save" data-focus>保存</button>
        </div>
      </div>
    </div>`;

  const list = panel.querySelector('#src-list');
  const report = panel.querySelector('#imp-report');

  async function load() {
    const d = await api.sources('cms');
    if (!d.items.length) {
      list.innerHTML = emptyState({
        iconName: 'link', title: '一个片源都没有',
        desc: '搜索和浏览都靠片源。先导入一个 TVBox 配置，或者手动添加一个采集接口。',
      });
      return;
    }
    list.innerHTML = d.items.map(s => `
      <div class="src-row ${s.enabled ? '' : 'off'}" data-id="${s.id}">
        <button class="switch ${s.enabled ? 'on' : ''}" data-act="toggle" aria-label="启用"></button>
        <div class="src-main">
          <div class="src-name">${esc(s.name)}
            <span class="tag">${s.type === 0 ? 'XML' : 'JSON'}</span>
            ${s.cat ? `<span class="tag">${esc(s.cat)}</span>` : ''}
            ${s.status ? `<span class="pill ${s.status === 'ok' ? 'ok' : 'err'}">${s.status === 'ok' ? '连通' : '失败'}</span>` : ''}
          </div>
          <div class="src-url mono">${esc(s.url)}</div>
        </div>
        <div class="src-actions">
          <select class="input" data-act="cat" style="width:92px;height:30px;font-size:11.5px">
            ${['综合', '电影', '剧集', '动漫', '综艺', '纪录片'].map(c =>
              `<option ${c === (s.cat || '综合') ? 'selected' : ''}>${c}</option>`).join('')}
          </select>
          <input class="pri-input" data-act="pri" value="${s.pri || 5}" title="优先级，数字越小越优先">
          <button class="btn sm" data-act="test" data-focus>测试</button>
          <button class="btn sm danger" data-act="del" data-focus>${icon('trash')}</button>
        </div>
      </div>`).join('');
  }

  list.addEventListener('click', async e => {
    const row = e.target.closest('[data-id]');
    if (!row) return;
    const id = +row.dataset.id;
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (!act) return;
    if (act === 'toggle') {
      row.classList.toggle('off');
      const on = !row.classList.contains('off');
      e.target.classList.toggle('on', on);
      await api.toggleSource(id, on);
      cacheDrop('browse|'); cacheDrop('home');
      toast(on ? '已启用' : '已停用');
    }
    if (act === 'test') {
      const btn = e.target.closest('button');
      btn.disabled = true; btn.textContent = '测试中';
      try {
        const r = await api.testSource(id);
        toast(r.ok ? `连通 · ${r.ms}ms · ${r.count} 条` : `失败：${r.msg}`, r.ok ? 'ok' : 'err');
        await load();
      } catch (err) { toast(err.message, 'err'); btn.disabled = false; btn.textContent = '测试'; }
    }
    if (act === 'del') {
      const ok = await dialog({
        title: '删除这个片源？', body: '删掉后搜索和浏览不再使用它。内置源删了不会自动恢复，但可以重新导入。',
        ok: '删除',
      });
      if (ok) {
        await api.delSource(id);
        cacheDrop('browse|'); cacheDrop('home');
        toast('已删除'); await load();
      }
    }
  });

  list.addEventListener('change', async e => {
    const row = e.target.closest('[data-id]');
    if (!row) return;
    const id = +row.dataset.id;
    const act = e.target.dataset.act;
    if (act === 'cat') await api.catSource(id, e.target.value);
    if (act === 'pri') await api.priSource(id, +e.target.value || 5);
  });

  panel.querySelector('#src-reload').onclick = () => load();
  panel.querySelector('#add-src-toggle').onclick = () => {
    const box = panel.querySelector('#add-src');
    box.hidden = !box.hidden;
  };
  panel.querySelector('#ns-save').onclick = async () => {
    const name = panel.querySelector('#ns-name').value.trim();
    const url = panel.querySelector('#ns-url').value.trim();
    if (!name || !url) return toast('名称和地址都要填', 'err');
    await api.addSource({ name, url, type: +panel.querySelector('#ns-type').value,
                          cat: panel.querySelector('#ns-cat').value });
    cacheDrop('browse|'); cacheDrop('home');
    toast('已添加', 'ok');
    panel.querySelector('#add-src').hidden = true;
    await load();
  };

  const doImport = async payload => {
    const btn = panel.querySelector('#imp-btn');
    btn.disabled = true; btn.textContent = '导入中';
    report.innerHTML = `<p class="hint">正在拉取配置并解析…</p>`;
    try {
      const r = await api.importConfig(payload);
      if (!r.ok) throw new Error(r.msg || '导入失败');
      report.innerHTML = `<div class="card-block" style="margin:0;border-color:rgba(126,226,168,.3)">
        <h4 style="color:var(--ok)">${esc(r.msg)}</h4>
        ${r.skipped && r.skipped.length ? `<p class="hint">跳过 ${r.skipped.length} 项：</p>
          <div style="max-height:150px;overflow:auto">
          ${r.skipped.map(s => `<div class="mono" style="color:var(--muted);font-size:11px;padding:3px 0">
            ${esc(s.name)} — ${esc(s.reason)}</div>`).join('')}</div>` : ''}
      </div>`;
      cacheDrop('browse|'); cacheDrop('home');
      await load();
    } catch (e) {
      report.innerHTML = `<div class="card-block" style="margin:0;border-color:rgba(255,107,107,.35)">
        <h4 style="color:var(--danger)">导入没成功</h4>
        <p class="hint" style="margin:0">${esc(e.message)}</p></div>`;
    }
    btn.disabled = false; btn.innerHTML = icon('link') + '导入';
  };

  panel.querySelector('#imp-btn').onclick = () => {
    const url = panel.querySelector('#imp-url').value.trim();
    if (!url) return toast('先填一个配置地址', 'err');
    doImport({ url });
  };
  panel.querySelector('#imp-paste').onclick = () => {
    const box = panel.querySelector('#imp-json');
    box.hidden = !box.hidden;
    if (!box.hidden) panel.querySelector('#imp-text').focus();
  };
  panel.querySelector('#imp-json-btn').onclick = () => {
    const txt = panel.querySelector('#imp-text').value.trim();
    if (!txt) return toast('粘贴一段配置 JSON', 'err');
    doImport({ json: txt });
  };

  await load();

  // 自动发现片源
  const dInfo = panel.querySelector('#src-discover-info');
  const paintDiscover = st => {
    st = st || {};
    if (st.running) {
      dInfo.textContent = `正在拉取配置并逐个验证，约需 1 分钟…（已开始 ${timeAgo(st.since)}）`;
      return;
    }
    const r = st.result || {};
    dInfo.textContent = r.at
      ? `上次：${timeAgo(r.at)} · ${r.configs} 个配置 / ${r.candidates} 个候选 → 可用 ${r.alive} · 新增 ${r.added}（${r.seconds}s）`
      : '还没有执行过；每周自动任务也会跑一次。';
  };
  try { paintDiscover(await api.discoverSources()); } catch (e) { /* 旧库没有这个字段 */ }

  panel.querySelector('#src-discover').onclick = async () => {
    const b = panel.querySelector('#src-discover');
    b.disabled = true;
    try {
      const r = await api.runDiscover();
      toast(r.msg || '已开始发现', 'ok');
      for (let i = 0; i < 90; i++) {
        await new Promise(ok => setTimeout(ok, 2000));
        if (!panel.isConnected) break;
        const st = await api.discoverSources();
        paintDiscover(st);
        if (!st.running) { await load(); break; }
      }
    } catch (e) { toast(e.message, 'err'); }
    b.disabled = false;
  };
}

// ═══════════ 收藏 ═══════════
async function favPanel(panel) {
  panel.innerHTML = `<h2>收藏</h2><p class="lead">收藏单独放在这里，不再和主页内容抢位置。</p>
    <div id="fav-list"><div class="skl" style="height:180px;border-radius:16px"></div></div>`;
  const box = panel.querySelector('#fav-list');
  const d = await api.library('fav');
  const items = d.items || [];
  store.favs = new Set(items.map(x => x.key));
  if (!items.length) {
    box.innerHTML = emptyState({ iconName: 'heart', title: '还没有收藏',
      desc: '在片名详情页点一下收藏，就会出现在这里。' });
    return;
  }
  box.innerHTML = `<div class="grid">${items.map(x => `
    <div class="card-wrap">
      <a class="card" href="#/title?n=${encodeURIComponent(x.key)}${yearOf(x)}" data-focus
         data-open="/title?n=${encodeURIComponent(x.key)}${yearOf(x)}"
         aria-label="${esc(x.title || x.key)}">
        ${imgTag(x.cover, x.title)}
        <span class="card-play"><span>${icon('play')}</span></span>
      </a>
      <div class="card-meta">
        <p class="card-title">${esc(x.title || x.key)}</p>
        <p class="card-sub"><button class="chip" data-unfav="${esc(x.key)}"
          style="height:26px;font-size:11px">取消收藏</button></p>
      </div>
    </div>`).join('')}</div>`;
  box.addEventListener('click', async e => {
    const b = e.target.closest('[data-unfav]');
    if (!b) return;
    await api.fav({ kind: 'online', key: b.dataset.unfav });
    toast('已取消收藏');
    await favPanel(panel);
  });
}

function yearOf(x) {
  try {
    const p = JSON.parse(x.payload || '{}');
    return p.year ? '&y=' + encodeURIComponent(p.year) : '';
  } catch (e) { return ''; }
}

// ═══════════ 下载任务 ═══════════
async function tasksPanel(panel) {
  panel.innerHTML = `<h2>下载任务</h2>
    <p class="lead">接你 NAS 上的 qBittorrent。没配也能用在线播放，这里会告诉你差什么。</p>
    <div class="card-block">
      <div class="between"><h4>qBittorrent</h4>
        <button class="btn sm" id="tk-refresh" data-focus>${icon('refresh')}刷新</button></div>
      <div id="tk-status" class="hint"></div>
      <div id="tk-list"></div>
    </div>`;
  const list = panel.querySelector('#tk-list');
  const status = panel.querySelector('#tk-status');

  async function load() {
    try {
      const d = await api.tasks();
      status.innerHTML = d.ok
        ? `<span class="pill ok">已连接</span>`
        : `<span class="pill err">未连接</span> <span class="mono">${esc(d.msg || '')}</span>`;
      const items = d.items || [];
      if (!items.length) {
        list.innerHTML = emptyState({ iconName: 'download', title: d.ok ? '没有下载任务' : '下载功能不可用',
          desc: d.ok ? '在搜索页的「磁力下载」里挑一个资源提交即可。' : (d.msg || '') });
        return;
      }
      list.innerHTML = items.map(t => `
        <div class="task">
          <div class="t-top">
            <span class="t-name">${esc(t.name)}</span>
            <span class="pill ${t.state.includes('UP') ? 'ok' : ''}">${esc(t.state_cn)}</span>
          </div>
          <div class="t-bar"><i style="width:${t.progress}%"></i></div>
          <div class="t-meta">
            <span>${t.progress}%</span><span>${fmtSize(t.downloaded)} / ${fmtSize(t.size)}</span>
            <span>${fmtSpeed(t.speed)}</span><span>种 ${t.seeds} · 连 ${t.peers}</span>
            ${t.eta > 0 ? `<span>剩 ${fmtClock(t.eta)}</span>` : ''}
            ${t.category ? `<span class="tag">${esc(t.category)}</span>` : ''}
            <span style="margin-left:auto;display:flex;gap:6px">
              <button class="btn sm" data-tk="${t.hash}" data-act="${t.state.includes('DL') && !t.state.includes('paused') ? 'pause' : 'resume'}" data-focus>
                ${t.state.includes('paused') || t.state.includes('UP') ? '继续' : '暂停'}</button>
              <button class="btn sm danger" data-tk="${t.hash}" data-act="delete" data-focus>${icon('trash')}</button>
            </span>
          </div>
        </div>`).join('');
    } catch (e) {
      status.innerHTML = `<span class="pill err">读取失败</span> ${esc(e.message)}`;
    }
  }

  list.addEventListener('click', async e => {
    const b = e.target.closest('[data-tk]');
    if (!b) return;
    if (b.dataset.act === 'delete') {
      const ok = await dialog({ title: '删除任务？', body: '会同时删除已下载的文件。', ok: '删除' });
      if (!ok) return;
    }
    b.disabled = true;
    try {
      const r = await api.torrent(b.dataset.tk, b.dataset.act);
      toast(r.ok ? '已提交' : (r.msg || '操作失败'), r.ok ? 'ok' : 'err');
      await load();
    } catch (err) { toast(err.message, 'err'); b.disabled = false; }
  });

  panel.querySelector('#tk-refresh').onclick = load;
  await load();
  clearInterval(timer);
  timer = setInterval(() => {
    if (!document.hidden && panel.isConnected) load();
  }, 6000);
}

// ═══════════ 播放记录 ═══════════
async function historyPanel(panel) {
  panel.innerHTML = `<h2>播放记录</h2><p class="lead">看过什么、看到哪，都在这。主页的「继续观看」也是从这里取的。</p>
    <div id="h-list"><div class="skl" style="height:90px;border-radius:14px;margin-bottom:10px"></div>
    <div class="skl" style="height:90px;border-radius:14px"></div></div>`;
  const box = panel.querySelector('#h-list');
  const d = await api.library('history');
  const items = (d.items || []).filter(x => x.kind === 'online');
  if (!items.length) {
    box.innerHTML = emptyState({ iconName: 'clock', title: '还没有观看记录',
      desc: '看过的片会自动记进度，下次从停下的地方继续。' });
    return;
  }
  box.innerHTML = items.map(x => {
    const pct = x.duration ? Math.min(100, x.position / x.duration * 100) : 0;
    return `<div class="history-row" data-focus tabindex="0" role="link" aria-label="${esc(x.title || x.key)}" data-open="/title?n=${encodeURIComponent(x.key)}${x.year ? '&y=' + x.year : ''}${x.ep ? '&ep=' + encodeURIComponent(x.ep) : ''}${x.position ? '&t=' + Math.floor(x.position) : ''}">
      ${x.cover ? `<img src="${esc(x.cover)}" alt="" loading="lazy">` : `<div class="ph">${esc((x.title || '?')[0])}</div>`}
      <div class="history-main">
        <h5>${esc(x.title || x.key)}</h5>
        <p>${esc(x.ep || '')}${x.source ? ' · ' + esc(x.source) : ''} · ${timeAgo(x.at)}</p>
        ${pct ? `<div class="hbar"><i style="width:${pct}%"></i></div>` : ''}
      </div>
      <span class="pill">${x.plays || 1} 次</span>
    </div>`;
  }).join('');
}

// ═══════════ 直播源 ═══════════
async function livePanel(panel) {
  panel.innerHTML = `<h2>直播源</h2><p class="lead">M3U / TXT 都行，导入后自动按分组归类。</p>
    <div class="card-block">
      <h4>添加直播源</h4>
      <p class="hint">贴一个能直接访问的 m3u / txt 地址。运营商内网源在普通宽带下打不开。</p>
      <div class="import-row">
        <input class="input" id="lv-name" placeholder="名称" style="max-width:160px">
        <input class="input" id="lv-url" placeholder="https://.../live.m3u">
        <button class="btn primary" id="lv-add" data-focus>${icon('plus')}添加并抓取</button>
      </div>
    </div>
    <div class="card-block">
      <div class="between">
        <div><h4>每日自动维护</h4>
          <p class="hint" style="margin:4px 0 0">每天 <b id="lv-auto-hour">04:00</b> 重新抓取所有启用源，
            检测频道可用性；<b>连续 3 次</b>都测不通的频道会被自动清理。</p></div>
        <button class="switch" id="lv-auto" aria-label="每日自动维护"></button>
      </div>
      <p class="hint" id="lv-auto-info" style="margin:10px 0 0"></p>
      <div class="field" style="margin-top:14px">
        <label>候选源清单（每行一个地址；每天全部拉一遍、合并去重、逐条探活）</label>
        <textarea class="input" id="lv-seeds" placeholder="https://.../result.m3u"></textarea>
      </div>
      <div class="row" style="justify-content:flex-end;gap:8px">
        <button class="btn sm" id="lv-seeds-save" data-focus>保存清单</button>
        <button class="btn sm primary" id="lv-auto-now" data-focus>${icon('refresh')}立即汇总并检测</button>
      </div>
    </div>

    <div class="card-block">
      <div class="between"><h4>已配置的源</h4>
        <button class="btn sm" id="lv-refresh" data-focus>${icon('refresh')}全部刷新</button></div>
      <div class="src-list" id="lv-list" style="margin-top:16px"></div>
    </div>`;

  const list = panel.querySelector('#lv-list');
  async function load() {
    const d = await api.sources('live');
    if (!d.items.length) {
      list.innerHTML = emptyState({ iconName: 'tv', title: '没有直播源', desc: '加一个 M3U 地址就能看。' });
      return;
    }
    list.innerHTML = d.items.map(s => `
      <div class="src-row ${s.enabled ? '' : 'off'}" data-id="${s.id}">
        <button class="switch ${s.enabled ? 'on' : ''}" data-act="toggle"></button>
        <div class="src-main">
          <div class="src-name">${esc(s.name)}
            ${s.api_count ? `<span class="pill ok">${s.api_count} 个频道</span>` : ''}
            ${s.status === 'fail' ? `<span class="pill err">抓取失败</span>` : ''}</div>
          <div class="src-url mono">${esc(s.url)}</div>
        </div>
        <div class="src-actions">
          <button class="btn sm" data-act="del" data-focus>${icon('trash')}</button>
        </div>
      </div>`).join('');
  }

  list.addEventListener('click', async e => {
    const row = e.target.closest('[data-id]');
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (!row || !act) return;
    const id = +row.dataset.id;
    if (act === 'toggle') {
      row.classList.toggle('off');
      e.target.closest('.switch').classList.toggle('on', !row.classList.contains('off'));
      await api.toggleSource(id, !row.classList.contains('off'));
    }
    if (act === 'del') {
      await api.delSource(id);
      toast('已删除该源及其频道');
      await load();
    }
  });

  panel.querySelector('#lv-add').onclick = async () => {
    const name = panel.querySelector('#lv-name').value.trim() || '自定义直播源';
    const url = panel.querySelector('#lv-url').value.trim();
    if (!url) return toast('填一个地址', 'err');
    const btn = panel.querySelector('#lv-add');
    btn.disabled = true; btn.textContent = '抓取中…';
    try {
      const r = await api.addLive(name, url);
      toast(r.msg || '已导入', 'ok');
      await load();
    } catch (e) { toast(e.message, 'err'); }
    btn.disabled = false; btn.innerHTML = icon('plus') + '添加并抓取';
  };

  panel.querySelector('#lv-refresh').onclick = async () => {
    toast('正在刷新所有直播源…');
    try {
      const r = await api.refreshLive();
      toast(r.msg || '完成', 'ok');
      await load();
    } catch (e) { toast(e.message, 'err'); }
  };

  await load();

  // 每日自动维护：开关 + 立即执行
  const sw = panel.querySelector('#lv-auto');
  const info = panel.querySelector('#lv-auto-info');
  const seedBox = panel.querySelector('#lv-seeds');
  const paintAuto = st => {
    st = st || {};
    sw.classList.toggle('on', !!st.enabled);
    const hourEl = panel.querySelector('#lv-auto-hour');
    if (hourEl) hourEl.textContent = String(st.hour ?? 4).padStart(2, '0') + ':00';
    if (seedBox && document.activeElement !== seedBox) {
      seedBox.value = (st.seeds || []).join('\n');
    }
    const r = st.result || {};
    const c = st.collect || {};
    if (st.running) {
      info.textContent = `正在汇总候选源并探活，可能需要几分钟…（已开始 ${timeAgo(st.since)}）`;
    } else if (c.at) {
      info.textContent = `上次汇总：${timeAgo(c.at)} · ${c.seeds || 0} 个源 / ${c.fetched || 0} 条 → `
        + `探测 ${c.probed || 0} · 可用频道 ${c.kept || 0} · 入库 ${c.rows || 0}（${c.seconds || 0}s）`;
    } else if (r.at) {
      info.textContent = `上次：${timeAgo(r.at)} · 抓取 ${r.fetched || 0} · 检测 ${r.checked || 0} · 清理 ${r.pruned || 0}`;
    } else {
      info.textContent = '还没有执行过；开启后会在设定时间自动跑一次。';
    }
  };
  try { paintAuto(await api.liveAuto()); } catch (e) { info.textContent = ''; }

  sw.onclick = async () => {
    const on = !sw.classList.contains('on');
    sw.classList.toggle('on', on);
    try {
      const r = await api.setLiveAuto(on);
      paintAuto(r.status || {});
      toast(on ? '已开启每日自动维护' : '已关闭每日自动维护');
    } catch (e) {
      sw.classList.toggle('on', !on);
      toast(e.message, 'err');
    }
  };

  panel.querySelector('#lv-seeds-save').onclick = async () => {
    const list = (seedBox.value || '').split('\n').map(x => x.trim()).filter(Boolean);
    try {
      const r = await api.setLiveSeeds(list);
      toast(`已保存 ${r.seeds.length} 个候选源`, 'ok');
      paintAuto(await api.liveAuto());
    } catch (e) { toast(e.message, 'err'); }
  };

  panel.querySelector('#lv-auto-now').onclick = async () => {
    const b = panel.querySelector('#lv-auto-now');
    b.disabled = true;
    try {
      const r = await api.collectLive();
      toast(r.msg || '已开始汇总', 'ok');
      // 后台跑，轮询到结束
      for (let i = 0; i < 120; i++) {
        await new Promise(ok => setTimeout(ok, 3000));
        if (!panel.isConnected) break;
        const st = await api.liveAuto();
        paintAuto(st);
        if (!st.running) { await load(); break; }
      }
    } catch (e) { toast(e.message, 'err'); }
    b.disabled = false;
  };
}

// ═══════════ 音乐 ═══════════
async function musicPanel(panel) {
  const d = await api.musicConfig();
  const cfg = d.config || {};
  const sub = cfg.subsonic || {};
  const local = cfg.local || {};
  const provider = cfg.provider || '';

  panel.innerHTML = `<h2>音乐</h2>
    <p class="lead">两条路：接一个 Subsonic 兼容服务（Navidrome / Airsonic / Gonic），
      或者让后端直接扫一块挂载进来的音乐目录。</p>
    <div class="toggle-cards" id="m-prov">
      <div class="toggle-card ${provider === '' ? 'on' : ''}" data-p="" data-focus>
        <b>关闭</b><span>只保留影视和直播，主页不显示音乐入口</span></div>
      <div class="toggle-card ${provider === 'local' ? 'on' : ''}" data-p="local" data-focus>
        <b>本地目录</b><span>无需外部服务，后端扫盘读 ID3 / FLAC 标签</span></div>
      <div class="toggle-card ${provider === 'subsonic' ? 'on' : ''}" data-p="subsonic" data-focus>
        <b>Subsonic</b><span>接你自己的音乐服务器，元数据和封面最完整</span></div>
    </div>

    <div class="card-block" id="m-local" ${provider === 'local' ? '' : 'hidden'}>
      <h4>本地音乐目录</h4>
      <p class="hint">填 NAS 上挂载进容器的路径，比如 /music。目录里按「歌手/专辑/曲目」放效果最好。</p>
      <div class="import-row">
        <input class="input" id="m-dir" placeholder="/music" value="${esc(local.dir || '')}">
        <button class="btn" id="m-dir-scan" data-focus>${icon('refresh')}扫描并预览</button>
      </div>
      <div id="m-scan-result" style="margin-top:10px"></div>
    </div>

    <div class="card-block" id="m-sub" ${provider === 'subsonic' ? '' : 'hidden'}>
      <h4>Subsonic 服务</h4>
      <p class="hint">地址填到根路径，例如 http://192.0.2.10:4533。密码保存在本机 SQLite 里，只在局域网使用。</p>
      <div class="field"><label>服务器地址</label>
        <input class="input" id="m-url" placeholder="http://192.0.2.10:4533" value="${esc(sub.url || '')}"></div>
      <div class="setting-2col">
        <div class="field"><label>用户名</label>
          <input class="input" id="m-user" value="${esc(sub.user || '')}"></div>
        <div class="field"><label>密码</label>
          <input class="input" id="m-pass" type="password" placeholder="${sub.has_pass ? '已保存，留空则不修改' : ''}"></div>
      </div>
      <button class="btn sm" id="m-ping" data-focus>测试连接</button>
      <span id="m-ping-msg" class="hint" style="margin-left:10px"></span>
    </div>

    <div class="row" style="justify-content:flex-end;gap:10px;margin-top:6px">
      <span class="pill ${d.status.ok ? 'ok' : ''}">${esc(d.status.msg || '')}</span>
      <button class="btn primary" id="m-save" data-focus>保存配置</button>
    </div>`;

  let chosen = provider;
  panel.querySelector('#m-prov').addEventListener('click', e => {
    const c = e.target.closest('[data-p]');
    if (!c) return;
    chosen = c.dataset.p;
    panel.querySelectorAll('#m-prov .toggle-card').forEach(x => x.classList.toggle('on', x === c));
    panel.querySelector('#m-local').hidden = chosen !== 'local';
    panel.querySelector('#m-sub').hidden = chosen !== 'subsonic';
  });

  const collect = () => ({
    provider: chosen,
    local: { dir: panel.querySelector('#m-dir')?.value.trim() || '' },
    subsonic: {
      url: panel.querySelector('#m-url')?.value.trim() || '',
      user: panel.querySelector('#m-user')?.value.trim() || '',
      pass: panel.querySelector('#m-pass')?.value || '',
    },
  });

  panel.querySelector('#m-save').onclick = async () => {
    const btn = panel.querySelector('#m-save');
    btn.disabled = true;
    try {
      await api.saveMusic(collect());
      toast('已保存，去音乐页看看', 'ok');
      go('/music');
    } catch (e) { toast(e.message, 'err'); }
    btn.disabled = false;
  };
  panel.querySelector('#m-ping').onclick = async () => {
    const msg = panel.querySelector('#m-ping-msg');
    msg.textContent = '连接中…';
    try {
      const r = await api.testMusic({ provider: 'subsonic', subsonic: collect().subsonic });
      msg.innerHTML = r.ok ? `<span style="color:var(--ok)">连上了 ${esc(r.version || '')}</span>`
                           : `<span style="color:var(--danger)">${esc(r.msg || '连不上')}</span>`;
    } catch (e) { msg.innerHTML = `<span style="color:var(--danger)">${esc(e.message)}</span>`; }
  };
  panel.querySelector('#m-dir-scan').onclick = async () => {
    const box = panel.querySelector('#m-scan-result');
    box.innerHTML = `<p class="hint">正在扫描目录，音乐多的话要等一会儿…</p>`;
    try {
      const r = await api.testMusic({ provider: 'local', dir: panel.querySelector('#m-dir').value.trim() });
      box.innerHTML = r.ok ? `<p class="hint" style="color:var(--ok)">${esc(r.msg)}</p>`
                           : `<p class="hint" style="color:var(--danger)">${esc(r.msg)}</p>`;
    } catch (e) { box.innerHTML = `<p class="hint" style="color:var(--danger)">${esc(e.message)}</p>`; }
  };
}

// ═══════════ 家庭账户 ═══════════
async function accountsPanel(panel) {
  const meUser = currentUser();
  const draw = (items) => {
    panel.innerHTML = `<h2>家庭账户</h2>
      <p class="lead">家里每个人一份观看记录与收藏，互不干扰。管理员能改名字、设 PIN、删账户。</p>
      ${usingDefaultPin() ? `<div class="card-block" style="border-color:rgba(255,207,122,.35);
        background:rgba(255,207,122,.07)">
        <h4 style="margin-bottom:4px">还在用默认口令</h4>
        <p class="hint" style="margin:0">管理员默认 PIN 是 <code>123456</code>，建议点下面的「改 PIN」换一个。</p>
      </div>` : ''}

      <div class="card-block">
        <h4>新增账户</h4>
        <p class="hint">每人一个 6 位数字 PIN，全库不能重复——登录就是靠它认出是谁。</p>
        <div class="row" style="gap:10px;flex-wrap:wrap">
          <input class="input" id="pf-name" placeholder="名字" maxlength="16"
                 style="max-width:180px;height:38px">
          <input class="input pin-input" id="pf-pin" placeholder="PIN（6 位数字）" inputmode="numeric"
                 maxlength="6" style="max-width:180px;height:38px">
          <select class="input" id="pf-role" style="width:120px;height:38px">
            <option value="member">家人</option>
            <option value="admin">管理员</option>
          </select>
          <button class="btn primary" id="pf-add" data-focus>${icon('plus')}添加</button>
        </div>
        <p class="hint" id="pf-tip"></p>
      </div>

      <div class="tracklist">
        ${items.map(p => `
          <div class="track pf-row" data-pid="${esc(p.id)}">
            <span class="pf-av">${esc((p.avatar || p.name || '?').slice(0, 1))}</span>
            <span class="t-name">${esc(p.name)}
              ${p.role === 'admin' ? '<span class="pf-badge">管理员</span>' : ''}
              ${p.id === meUser.id ? '<span class="pf-badge me">当前</span>' : ''}</span>
            <span class="t-artist">${p.hasPin ? '已设 PIN' : '未设 PIN'}</span>
            <span class="pf-acts">
              <button class="btn sm ghost" data-act="rename" data-focus>改名</button>
              <button class="btn sm ghost" data-act="pin" data-focus>${p.hasPin ? '改 PIN' : '设 PIN'}</button>
              ${p.role === 'admin' ? '<span class="hint" style="font-size:11px">不可删除</span>'
                : `<button class="btn sm danger" data-act="del" data-focus>删除</button>`}
            </span>
          </div>`).join('')}
      </div>`;

    const tip = panel.querySelector('#pf-tip');
    const add = async () => {
      const name = panel.querySelector('#pf-name').value.trim();
      const pin = panel.querySelector('#pf-pin').value.trim();
      const role = panel.querySelector('#pf-role').value;
      if (!name) { tip.textContent = '填个名字'; return; }
      if (!/^\d{6}$/.test(pin)) { tip.textContent = 'PIN 必须是 6 位数字'; return; }
      try {
        await api.post('/api/profiles/save', { name, pin, role });
        toast('已添加「' + name + '」');
        refresh();
      } catch (e) { tip.textContent = e.message; }
    };
    panel.querySelector('#pf-add').onclick = add;
    panel.querySelector('#pf-pin').addEventListener('keydown', e => {
      if (e.key === 'Enter') add();
    });

    panel.querySelectorAll('.pf-row').forEach(row => {
      const pid = row.dataset.pid;
      const who = items.find(x => x.id === pid);
      row.querySelectorAll('[data-act]').forEach(b => {
        b.onclick = async () => {
          const act = b.dataset.act;
          if (act === 'rename') {
            const name = prompt('新的名字', who.name);
            if (!name || name.trim() === who.name) return;
            try { await api.post('/api/profiles/save', { id: pid, name: name.trim() }); refresh(); }
            catch (e) { toast(e.message, 'err'); }
            return;
          }
          if (act === 'pin') {
            const pin = prompt('设置新的 6 位数字 PIN', '');
            if (pin === null) return;
            if (!/^\d{6}$/.test(pin.trim())) { toast('PIN 必须是 6 位数字', 'err'); return; }
            try {
              await api.post('/api/profiles/save', { id: pid, pin: pin.trim() });
              if (pid === meUser.id) {
                // 改自己的 PIN 会清掉所有会话，必须重新登录
                toast('PIN 已更新，请用新 PIN 重新登录');
                await logout();
                setTimeout(() => location.reload(), 600);
                return;
              }
              toast('PIN 已更新');
              refresh();
            } catch (e) { toast(e.message, 'err'); }
            return;
          }
          if (act === 'del') {
            const ok = await dialog({
              title: '删除「' + who.name + '」？',
              body: '会同时删除这个账户的观看记录与收藏，不能恢复。',
              ok: '删除',
            });
            if (!ok) return;
            try { await api.post('/api/profiles/delete', { id: pid }); refresh(); }
            catch (e) { toast(e.message, 'err'); }
          }
        };
      });
    });
  };

  const refresh = async () => {
    try { draw(await refreshProfiles()); }
    catch (e) { panel.innerHTML = `<p class="hint" style="color:var(--danger)">${esc(e.message)}</p>`; }
  };
  await refresh();
}

// ═══════════ 配置备份 / 迁移 ═══════════
async function backupPanel(panel) {
  panel.innerHTML = `<h2>配置备份与分享</h2>
    <p class="lead">把片源、直播种子、音乐与界面偏好打包成一个 JSON。
      文件里不含密码、Cookie 之类的凭据；换台机器导入即可还原，也可以直接发给别人。</p>

    <div class="card-block">
      <h4>导出</h4>
      <p class="hint">两种都行：完整版连直播频道一起带走，配置源版只留片源和种子（体积小、更适合分享）。</p>
      <div class="row" style="gap:10px;flex-wrap:wrap">
        <a class="btn primary" href="/api/config/export?channels=1" data-focus>
          ${icon('download')}下载完整配置</a>
        <a class="btn" href="/api/config/export?channels=0" data-focus>只要配置源</a>
      </div>
    </div>

    <div class="card-block">
      <h4>导入</h4>
      <p class="hint">先预览会改什么，确认后才写入。写入前会自动把当前配置备份到
        <code>data/backups/</code>，导错了再导回来即可。</p>
      <div class="row" style="gap:10px;flex-wrap:wrap;align-items:center">
        <label class="btn" data-focus style="cursor:pointer">
          ${icon('upload')}选择配置文件
          <input type="file" id="cfg-file" accept=".json,application/json" hidden>
        </label>
        <select class="input" id="cfg-mode" style="width:210px;height:38px">
          <option value="merge">合并：保留现有，同名更新</option>
          <option value="replace">替换：先清空同类片源</option>
        </select>
        <span class="hint" id="cfg-name"></span>
      </div>
      <div id="cfg-preview" style="margin-top:14px"></div>
    </div>`;

  const fileEl = panel.querySelector('#cfg-file');
  const modeEl = panel.querySelector('#cfg-mode');
  const nameEl = panel.querySelector('#cfg-name');
  const box = panel.querySelector('#cfg-preview');
  let doc = null;

  const line = (label, n, extra) => n
    ? `<li><b>${esc(label)}</b> ${n} 条${extra ? '：' + extra : ''}</li>` : '';

  const renderPlan = (r) => {
    const p = r.plan || {}, s = p.sources || {}, l = p.liveChannels || {}, pr = p.prefs || {};
    const meta = r.meta || {};
    const items = [
      line('片源', s.total, `新增 ${s.add}，更新 ${s.update}，不变 ${s.same}`),
      line('直播频道', l.total, `新增 ${l.add}，已存在 ${l.same}`),
      line('偏好项', pr.total, (pr.keys || []).map(esc).join('、')),
    ].filter(Boolean);
    const warns = (r.warnings || []).length
      ? `<div class="hint" style="margin-top:10px;color:var(--danger)">
           <b>有 ${r.warnings.length} 条被跳过：</b><br>${r.warnings.slice(0, 8).map(esc).join('<br>')}
           ${r.warnings.length > 8 ? '<br>…' : ''}</div>` : '';
    const ver = meta.fromSchema && meta.fromSchema !== meta.schema
      ? `<span class="hint">（已从 v${meta.fromSchema} 升级到 v${meta.schema}）</span>` : '';
    box.innerHTML = `<div class="card-block" style="background:rgba(255,255,255,.03)">
      <h4 style="margin-bottom:6px">预览 ${ver}</h4>
      <ul class="hint" style="margin:6px 0 0;padding-left:18px;line-height:1.9">${items.join('')}</ul>
      ${warns}
      <div class="row" style="gap:10px;margin-top:14px">
        <button class="btn primary" id="cfg-go" data-focus>${icon('check')}确认导入</button>
        <button class="btn ghost" id="cfg-cancel" data-focus>取消</button>
      </div>
    </div>`;
    box.querySelector('#cfg-cancel').onclick = () => {
      doc = null; fileEl.value = ''; nameEl.textContent = ''; box.innerHTML = '';
    };
    box.querySelector('#cfg-go').onclick = async () => {
      const btn = box.querySelector('#cfg-go');
      btn.disabled = true;
      btn.textContent = '写入中…';
      try {
        const res = await api.configImport({ config: doc, mode: modeEl.value });
        const st = res.stats || {};
        const s2 = st.sources || {}, l2 = st.liveChannels || {};
        cacheDrop();
        box.innerHTML = `<div class="card-block">
          <h4 style="color:var(--ok)">导入完成</h4>
          <p class="hint">片源新增 ${s2.add || 0} / 更新 ${s2.update || 0}，
            直播频道新增 ${l2.add || 0}。</p>
          ${res.backup ? `<p class="hint">导入前的备份：<code>${esc(res.backup)}</code></p>` : ''}
          <p class="hint">刷新页面后生效。</p>
        </div>`;
        toast('配置已导入');
      } catch (e) {
        box.innerHTML = `<p class="hint" style="color:var(--danger)">导入失败：${esc(e.message)}</p>`;
        toast('导入失败', 'err');
      }
    };
  };

  fileEl.onchange = async () => {
    const f = fileEl.files && fileEl.files[0];
    if (!f) return;
    nameEl.textContent = f.name;
    try {
      doc = JSON.parse(await f.text());
    } catch (e) {
      doc = null;
      box.innerHTML = `<p class="hint" style="color:var(--danger)">这个文件不是合法 JSON</p>`;
      return;
    }
    box.innerHTML = '<p class="hint">预览中…</p>';
    try {
      const r = await api.configImport({ config: doc, mode: modeEl.value, dryRun: true });
      if (!r.ok) throw new Error(r.msg || '预览失败');
      renderPlan(r);
    } catch (e) {
      box.innerHTML = `<p class="hint" style="color:var(--danger)">${esc(e.message)}</p>`;
    }
  };
  modeEl.onchange = () => { if (doc) fileEl.onchange(); };
}

// ═══════════ 关于 / 自检 ═══════════
async function aboutPanel(panel) {
  panel.innerHTML = `<h2>关于与自检</h2>
    <p class="lead">一页看清服务状态：片源通不通、qBittorrent 配没配、音乐库接没接上。</p>
    <div id="about-body"><div class="skl" style="height:160px;border-radius:16px"></div></div>`;
  const box = panel.querySelector('#about-body');
  let d;
  try { d = await api.status(); }
  catch (e) {
    box.innerHTML = emptyState({ iconName: 'wifi', title: '自检失败', desc: e.message });
    return;
  }
  const music = d.music || {};
  box.innerHTML = `
    <div class="status-grid">
      <div class="stat"><b>${d.sites || 0}</b><span>启用中的片源</span></div>
      <div class="stat"><b>${d.live || 0}</b><span>直播频道</span></div>
      <div class="stat"><b>${d.qbt ? '正常' : '未连接'}</b><span>qBittorrent</span></div>
      <div class="stat"><b>${music.provider ? (music.ok ? '正常' : '异常') : '未启用'}</b><span>音乐库</span></div>
    </div>
    <div class="card-block" style="margin-top:18px">
      <h4>运行状态</h4>
      <div class="about-list">
        <div><span>片源</span><span>${d.sites || 0} 个启用 / 全部在设置里管理</span></div>
        <div><span>直播</span><span>${d.live || 0} 个频道</span></div>
        <div><span>qBittorrent</span><span>${esc(d.qbt_msg || (d.qbt ? '已连接' : '未配置'))}</span></div>
        <div><span>音乐</span><span>${esc(music.msg || '未启用')}</span></div>
        <div><span>前端</span><span>零构建 ES Modules · 空间焦点导航</span></div>
        <div><span>后端</span><span>Python 标准库 · SQLite · 无第三方依赖</span></div>
      </div>
    </div>
    <div class="card-block">
      <h4>数据备份</h4>
      <p class="hint">导出收藏、播放记录、片源与偏好设置。换机前存一份，密码类字段不会被包含。</p>
      <button class="btn sm" id="btn-backup" data-focus>${icon('download')}导出 JSON</button>
    </div>
    <div class="card-block">
      <h4>快捷键</h4>
      <p class="hint">方向键移动焦点 · 回车确认 · Esc 返回 · <kbd>/</kbd> 搜索 · 播放器里空格暂停、← → 快进退、F 全屏。</p>
      <h4 style="margin-top:14px">关于内容</h4>
      <p class="hint" style="margin:0">本项目不存储、不托管任何影视内容，只聚合公开采集接口做展示与播放。
        片源可用性由对应站点决定，仅供个人在自有设备上学习使用。</p>
    </div>`;

  const bk = box.querySelector('#btn-backup');
  if (bk) bk.onclick = async () => {
    try {
      const d = await api.get('/api/backup');
      const blob = new Blob([JSON.stringify(d, null, 2)], { type: 'application/json' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = 'projection-room-backup-' + new Date().toISOString().slice(0, 10) + '.json';
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 4000);
      toast('已导出备份', 'ok');
    } catch (e) { toast(e.message, 'err'); }
  };
}

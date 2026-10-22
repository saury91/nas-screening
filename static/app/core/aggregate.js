// 客户端聚合：NAS 不在时，App 用本地配置包自己去问采集站。
//
// 为什么必须走本地服务：源站不会给 http://127.0.0.1 发 CORS 头，
// 页面里直接 fetch 会被浏览器拦掉。所以统一走 App 的 /__fetch（原生代发）。
//
// 覆盖范围：苹果 CMS（MacCMS）JSON 接口 —— 我们绝大部分采集站都是这个协议。
// XML 接口（type=0）暂时跳过，离线时少几个源，不影响主流程。

import { load, save } from './offline.js';
import { plainText } from './dom.js';

const BUNDLE_KEY = 'config:bundle';
const BUNDLE_TTL = 30 * 86400 * 1000;
const ASK_TIMEOUT = 9000;

export async function getBundle() {
  return load(BUNDLE_KEY, BUNDLE_TTL);
}

export async function syncBundle() {
  try {
    const r = await fetch('/api/config/bundle');
    if (!r.ok) return null;
    const b = await r.json();
    if (b && Array.isArray(b.sources) && b.sources.length) {
      await save(BUNDLE_KEY, b);
      return b;
    }
  } catch (e) { /* 离线：保持旧包 */ }
  return null;
}

/** 代发一个请求，拿 JSON（优先 /__fetch，浏览器里退回直连） */
async function askJson(url) {
  const viaLocal = `/__fetch?u=${encodeURIComponent(url)}`;
  let res = null;
  try {
    res = await fetch(viaLocal, { signal: AbortSignal.timeout(ASK_TIMEOUT) });
  } catch (e) { res = null; }
  if (!res || !res.ok) {
    // 没有本地服务（比如 PC 浏览器）：直接请求，能过 CORS 就过
    try {
      res = await fetch(url, { signal: AbortSignal.timeout(ASK_TIMEOUT) });
    } catch (e) { return null; }
  }
  if (!res.ok) return null;
  const txt = await res.text();
  const body = txt.trim();
  if (!body || body[0] === '<') return null;      // XML 接口不支持
  try { return JSON.parse(body); } catch (e) { return null; }
}

function joinApi(api, params) {
  const sep = api.includes('?') ? '&' : '?';
  return api + sep + params;
}

/** 站点的 ac=detail/list 查询 */
async function querySite(site, params) {
  const d = await askJson(joinApi(site.api, params));
  if (!d) return [];
  const list = Array.isArray(d) ? d : (d.list || d.data || []);
  return Array.isArray(list) ? list.filter(x => x && x.vod_name) : [];
}

/** "线路名1$$$线路名2" + "第1集$url#第2集$url$$$..." → 我们的 sources */
function toSources(v) {
  const from = String(v.vod_play_from || '').split('$$$').filter(Boolean);
  const urls = String(v.vod_play_url || '').split('$$$');
  const out = [];
  from.forEach((site, i) => {
    const eps = String(urls[i] || '')
      .split('#')
      .map((seg, k) => {
        const [name, url] = seg.split('$');
        if (!url) return null;
        return { name: (name || `第${k + 1}集`).trim(), url: url.trim() };
      })
      .filter(Boolean);
    if (eps.length) out.push({ site: site.trim() || '线路', q: '', count: eps.length, eps });
  });
  return out;
}

function toItem(v) {
  return {
    name: String(v.vod_name || '').trim(),
    year: String(v.vod_year || '').trim(),
    pic: String(v.vod_pic || '').trim(),
    type: String(v.vod_type_name || v.type_name || '').trim(),
    remarks: String(v.vod_remarks || '').trim(),
    score: String(v.vod_score || '').trim(),
    // 只剥标签不够：简介里还有 &amp;#039; 这类实体，渲染出来是字面量
    desc: plainText(v.vod_content || v.vod_blurb || ''),
  };
}

/** 搜索：并发问前 N 个源，按片名去重 */
export async function search(kw, { limit = 12 } = {}) {
  const b = await getBundle();
  if (!b || !kw) return null;
  const sites = b.sources.filter(s => s.api).slice(0, limit);
  const packs = await Promise.all(sites.map(async s => {
    const list = await querySite(s, `ac=detail&wd=${encodeURIComponent(kw)}`);
    return list.map(v => ({ v, site: s.name }));
  }));

  const seen = new Map();
  for (const arr of packs) {
    for (const { v } of arr) {
      const it = toItem(v);
      if (!it.name) continue;
      const key = it.name + (it.year || '');
      const prev = seen.get(key);
      if (prev) { prev.count = (prev.count || 1) + 1; continue; }
      seen.set(key, { ...it, count: 1 });
    }
  }
  const items = [...seen.values()].sort((a, b2) => (b2.count || 0) - (a.count || 0));
  return { items, offline: true };
}

/** 详情：按片名问各源，合并线路 */
export async function detail(name, year, { limit = 12 } = {}) {
  const b = await getBundle();
  if (!b || !name) return null;
  const sites = b.sources.filter(s => s.api).slice(0, limit);
  const packs = await Promise.all(sites.map(async s => {
    const list = await querySite(s, `ac=detail&wd=${encodeURIComponent(name)}`);
    return list.map(v => ({ v, site: s.name }));
  }));

  const exact = [];
  for (const arr of packs) {
    for (const { v, site } of arr) {
      const n = String(v.vod_name || '').trim();
      if (!n) continue;
      const y = String(v.vod_year || '').trim();
      // 片名完全相同（年份有就一起比），避免把同名前传/续集混进来
      if (n !== name && !n.startsWith(name)) continue;
      if (year && y && y !== year) continue;
      exact.push({ v, site });
    }
  }
  if (!exact.length) return null;

  const base = toItem(exact[0].v);
  const sources = [];
  const seenSite = new Set();
  for (const { v, site } of exact) {
    for (const s of toSources(v)) {
      const key = s.site + '|' + s.eps.length;
      if (seenSite.has(key)) continue;
      seenSite.add(key);
      sources.push({ ...s, site: s.site === '线路' ? site : s.site });
    }
  }
  if (!sources.length) return null;
  return {
    ...base,
    name,
    year: year || base.year,
    sources,
    offline: true,
  };
}

/** 列表：AppleCMS 的 detail 接口带 pg 即可翻页 */
export async function listPage(page = 1, { limit = 6 } = {}) {
  const b = await getBundle();
  if (!b) return null;
  const sites = b.sources.filter(s => s.api).slice(0, limit);
  const packs = await Promise.all(sites.map(async s => {
    const list = await querySite(s, `ac=detail&pg=${page}`);
    return list.map(v => toItem(v)).filter(x => x.name);
  }));
  const seen = new Map();
  for (const arr of packs) {
    for (const it of arr) {
      const key = it.name + (it.year || '');
      if (seen.has(key)) continue;
      seen.set(key, it);
    }
  }
  return { items: [...seen.values()], pg: page, more: page < 20, offline: true };
}

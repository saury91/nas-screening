// 卡片 / 货架 / 网格：全站统一的视觉单元
import { esc, icon, timeAgo } from '../core/dom.js';

export function imgTag(src, name, cls = '', eager = false) {
  const ph = `<div class="ph fill">${esc((name || '?').slice(0, 1))}</div>`;
  if (!src) return ph;
  // 首屏 Hero 用 eager + 高优先级，列表才懒加载：否则 LCP 图也要排队
  const load = eager ? 'fetchpriority="high" decoding="async"'
                     : 'loading="lazy" decoding="async"';
  return `${ph}<img src="${esc(src)}" alt="" ${load} class="${cls}"
    onerror="this.remove()">`;
}

export function titleLink(name, year) {
  return `#/title?n=${encodeURIComponent(name)}${year ? '&y=' + encodeURIComponent(year) : ''}`;
}

export function posterCard(it, o = {}) {
  const name = it.name || it.title || '未命名';
  const year = it.year || '';
  const sub = o.sub !== undefined ? o.sub : [year, it.type].filter(Boolean).join(' · ');
  const badge = o.badge || (it.site_count > 1 ? `${it.site_count} 源` : '');
  const prog = o.progress;
  const href = o.open || titleLink(name, year);
  // 用 <a href> 而不是 <div>：Tab 能走到、中键能开新标签、读屏能读
  return `<div class="card-wrap grid-enter"${o.key ? ` data-key="${esc(o.key)}"` : ''}>
    <a class="card" href="${href}" data-focus data-open="${href}"
       aria-label="${esc(name)}${year ? '（' + esc(year) + '）' : ''}">
      ${imgTag(it.pic || it.cover, name)}
      ${badge ? `<span class="card-badge">${esc(badge)}</span>` : ''}
      ${it.remarks ? `<span class="card-badge r">${esc(String(it.remarks).slice(0, 10))}</span>` : ''}
      <span class="card-play"><span>${icon('play')}</span></span>
      ${prog ? `<span class="card-progress"><i style="width:${Math.min(100, prog)}%"></i></span>` : ''}
    </a>
    <div class="card-meta">
      <p class="card-title">${esc(name)}</p>
      ${sub ? `<p class="card-sub">${esc(sub)}</p>` : ''}
    </div>
  </div>`;
}

export function continueCard(rec) {
  const pct = rec.duration > 0 ? (rec.position / rec.duration) * 100 : 0;
  const open = `#/title?n=${encodeURIComponent(rec.key)}${rec.year ? '&y=' + rec.year : ''}` +
    (rec.ep ? `&ep=${encodeURIComponent(rec.ep)}` : '') +
    (rec.position ? `&t=${Math.floor(rec.position)}` : '');
  return `<div class="card-wrap grid-enter">
    <a class="card" href="${open}" data-focus data-open="${open}"
       aria-label="继续观看 ${esc(rec.title || rec.key)}${rec.ep ? '（' + esc(rec.ep) + '）' : ''}">
      ${imgTag(rec.cover, rec.title)}
      <span class="card-play"><span>${icon('play')}</span></span>
      ${rec.ep ? `<span class="continue-ep">${esc(rec.ep)}</span>` : ''}
      ${pct ? `<span class="card-progress"><i style="width:${pct}%"></i></span>` : ''}
    </a>
    <div class="card-meta">
      <p class="card-title">${esc(rec.title || rec.key)}</p>
      <p class="card-sub">${pct ? `已看 ${Math.round(pct)}%` : timeAgo(rec.at)}</p>
    </div>
  </div>`;
}

export function shelf({ title, sub = '', items = [], more = '', moreText = '查看全部', render }) {
  if (!items.length) return '';
  const body = render ? items.map(render).join('') : items.map(x => posterCard(x)).join('');
  return `<section class="section">
    <div class="section-head">
      <h2 class="section-title">${esc(title)}</h2>
      ${sub ? `<span class="section-sub">${esc(sub)}</span>` : ''}
      ${more ? `<a class="more" href="${more}" data-focus>${esc(moreText)}${icon('chevron')}</a>` : ''}
    </div>
    <div class="rail">${body}</div>
  </section>`;
}

export function grid(items, render) {
  return `<div class="grid">${items.map(render || (x => posterCard(x))).join('')}</div>`;
}

export function emptyState({ iconName = 'film', title = '这里什么都没有', desc = '', action = '' }) {
  return `<div class="empty">${icon(iconName)}<h3>${esc(title)}</h3>
    ${desc ? `<p>${esc(desc)}</p>` : ''}${action}</div>`;
}

export function pill(text, kind = '') {
  return `<span class="pill ${kind}">${esc(text)}</span>`;
}

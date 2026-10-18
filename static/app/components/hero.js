// 首页 Hero：全幅氛围背景 + 竖版海报，10 秒自动轮播
import { $, esc, icon } from '../core/dom.js';
import { imgTag, titleLink } from './ui.js';

let timer = null;

const reduced = () =>
  !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);

function meta(it) {
  return [
    it.score && parseFloat(it.score) > 0 ? `<span class="score">${esc(it.score)}</span>` : '',
    it.year ? `<span>${esc(it.year)}</span>` : '',
    it.area ? `<span>${esc(it.area)}</span>` : '',
    it.type ? `<span>${esc(it.type)}</span>` : '',
    it.site_count ? `<span class="sep">·</span><span>${it.site_count} 个片源</span>` : '',
  ].filter(Boolean).join('<span class="sep">·</span>');
}

function copy(it) {
  return `<div class="hero-tag"><i class="dot"></i><span>私人放映 · 今日推荐</span></div>
    <h1 class="hero-title">${esc(it.name)}</h1>
    <div class="hero-meta">${meta(it)}</div>
    <p class="hero-desc">${esc(it.desc || '来自聚合片库的高分之作，点开即可播放。')}</p>
    <div class="hero-actions">
      <a class="btn primary" data-focus href="${titleLink(it.name, it.year)}&play=1">${icon('play')}立即观看</a>
      <a class="btn" data-focus href="${titleLink(it.name, it.year)}">${icon('info')}详情</a>
    </div>`;
}

// 注意用单引号：这串要塞进 style="..." 里，双引号会把属性截断
function bgUrl(pic) {
  return `url('${String(pic || '').replace(/['\\\n\r"]/g, '')}')`;
}

export function heroHTML(items) {
  if (!items.length) {
    return `<section class="hero"><div class="hero-skl"><div class="skl" style="width:100%;height:60%"></div></div></section>`;
  }
  const it = items[0];
  return `<section class="hero" id="hero">
    <div class="hero-inner">
      <div class="hero-copy" id="hero-copy">${copy(it)}</div>
      <div class="hero-poster" id="hero-poster">${imgTag(it.pic, it.name, '', true)}</div>
    </div>
    <div class="hero-dots" id="hero-dots">
      <button class="hero-step prev" id="hero-prev" data-focus aria-label="上一个推荐">
        ${icon('chevron')}
      </button>
      <span class="hero-pips">
        ${items.map((_, i) => `<button data-dot="${i}" class="${i === 0 ? 'on' : ''}"
          aria-label="第 ${i + 1} 个推荐"></button>`).join('')}
      </span>
      <button class="hero-step next" id="hero-next" data-focus aria-label="下一个推荐">
        ${icon('chevron')}
      </button>
    </div>
  </section>`;
}

export function mountHero(root, items) {
  const c = $('#hero-copy', root);
  const poster = $('#hero-poster', root);
  const dots = $('#hero-dots', root);
  if (!c || !items.length) return () => {};

  let idx = 0;
  let paused = false;

  const tint = it => {
    document.documentElement.style.setProperty('--ambient-poster', it.pic ? bgUrl(it.pic) : 'none');
  };
  tint(items[0]);

  const show = i => {
    idx = (i + items.length) % items.length;
    const it = items[idx];

    // 背景只有全局氛围光这一层：换片时它跟着淡入新海报的色调
    tint(it);

    c.style.animation = 'none';
    void c.offsetHeight;
    c.innerHTML = copy(it);
    c.style.animation = 'heroIn .7s var(--ease) both';

    if (poster) {
      poster.style.opacity = '0';
      poster.innerHTML = imgTag(it.pic, it.name, '', true);
      requestAnimationFrame(() => { poster.style.opacity = '1'; });
    }
    if (dots) {
      Array.from(dots.querySelectorAll('[data-dot]')).forEach((d, k) =>
        d.classList.toggle('on', k === idx));
    }
    const counter = $('#hero-idx', root);
    if (counter) counter.textContent = `${idx + 1} / ${items.length}`;
  };

  // 手动翻页后重新计时，不会刚点完就被自动切走
  const startTimer = () => {
    clearInterval(timer);
    timer = null;
    // 系统开了「减少动态效果」就不自动换片，只保留手动翻页
    if (reduced()) return;
    timer = setInterval(() => {
      if (!paused && !document.hidden) show(idx + 1);
    }, 10000);
  };

  const prev = $('#hero-prev', root), next = $('#hero-next', root);
  if (prev) prev.onclick = () => { show(idx - 1); startTimer(); };
  if (next) next.onclick = () => { show(idx + 1); startTimer(); };
  startTimer();

  root.addEventListener('pointerenter', () => { paused = true; });
  root.addEventListener('pointerleave', () => { paused = false; });
  root.addEventListener('focusin', () => { paused = true; });

  return () => {
    clearInterval(timer);
    timer = null;
  };
}

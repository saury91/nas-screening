// 首页 Hero：全幅氛围背景 + 竖版海报，10 秒自动轮播
import { $, esc, icon } from '../core/dom.js';
import { imgTag, titleLink } from './ui.js';

let timer = null;

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

export function heroHTML(items) {
  if (!items.length) {
    return `<section class="hero"><div class="hero-skl"><div class="skl" style="width:100%;height:60%"></div></div></section>`;
  }
  const it = items[0];
  return `<section class="hero" id="hero">
    <div class="hero-bg" id="hero-bg" style="${it.pic ? `background-image:url('${esc(it.pic)}')` : ''}"></div>
    <div class="hero-veil"></div><div class="hero-grain"></div>
    <div class="hero-inner">
      <div class="hero-copy" id="hero-copy">${copy(it)}</div>
      <div class="hero-poster" id="hero-poster">${imgTag(it.pic, it.name)}</div>
    </div>
    <button class="hero-arrow prev" id="hero-prev" data-focus aria-label="上一个推荐">
      ${icon('chevron')}
    </button>
    <button class="hero-arrow next" id="hero-next" data-focus aria-label="下一个推荐">
      ${icon('chevron')}
    </button>
    <div class="hero-dots" id="hero-dots">
      ${items.map((_, i) => `<button data-dot="${i}" class="${i === 0 ? 'on' : ''}"
        aria-label="第 ${i + 1} 个推荐"></button>`).join('')}
      <span class="hero-idx" id="hero-idx">1 / ${items.length}</span>
    </div>
  </section>`;
}

export function mountHero(root, items) {
  const bg = $('#hero-bg', root);
  const c = $('#hero-copy', root);
  const poster = $('#hero-poster', root);
  const dots = $('#hero-dots', root);
  if (!bg || !c || items.length < 2) return () => {};

  let idx = 0;
  let paused = false;
  const show = i => {
    idx = (i + items.length) % items.length;
    const it = items[idx];
    if (it.pic) bg.style.backgroundImage = `url('${it.pic.replace(/'/g, '')}')`;
    c.style.animation = 'none';
    c.offsetHeight;
    c.innerHTML = copy(it);
    c.style.animation = 'heroIn .7s var(--ease) both';
    poster.innerHTML = imgTag(it.pic, it.name);
    dots && Array.from(dots.querySelectorAll('[data-dot]')).forEach((d, k) =>
      d.classList.toggle('on', k === idx));
    const counter = $('#hero-idx', root);
    if (counter) counter.textContent = `${idx + 1} / ${items.length}`;
  };

  // 手动翻页后重新计时，不会刚点完就被自动切走
  const startTimer = () => {
    clearInterval(timer);
    timer = setInterval(() => { if (!paused && !document.hidden) show(idx + 1); }, 10000);
  };

  const prev = $('#hero-prev', root), next = $('#hero-next', root);
  if (prev) prev.onclick = () => { show(idx - 1); startTimer(); };
  if (next) next.onclick = () => { show(idx + 1); startTimer(); };
  startTimer();

  root.addEventListener('pointerenter', () => { paused = true; });
  root.addEventListener('pointerleave', () => { paused = false; });
  root.addEventListener('focusin', () => { paused = true; });
  dots && dots.addEventListener('click', e => {
    const b = e.target.closest('[data-dot]');
    if (b) show(+b.dataset.dot);
  });

  return () => {
    clearInterval(timer);
    timer = null;
  };
}

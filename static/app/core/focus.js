// 空间焦点导航：方向键在页面里「找最近的可聚焦元素」，
// 这是遥控器/电视端交互的核心，也顺带解决了键盘可访问性。
const FOCUSABLE = '[data-focus]';

let current = null;
let enabled = true;
let mouseFocus = false;   // 当前焦点是不是鼠标划过来的

function visible(el) {
  if (!el || !el.isConnected) return false;
  const r = el.getBoundingClientRect();
  if (r.width < 2 || r.height < 2) return false;
  const st = getComputedStyle(el);
  return st.visibility !== 'hidden' && st.display !== 'none' && st.opacity !== '0';
}

function scope() {
  const layers = Array.from(document.querySelectorAll('[data-focus-scope]'))
    .filter(l => visible(l) && !l.hidden);
  return layers.length ? layers[layers.length - 1] : document.body;
}

function candidates() {
  return Array.from(scope().querySelectorAll(FOCUSABLE)).filter(visible);
}

export function clearFocus() {
  document.querySelectorAll('.focused').forEach(e => e.classList.remove('focused'));
  current = null;
}

export function setFocus(el, scroll = true) {
  if (!el) return;
  document.querySelectorAll('.focused').forEach(e => {
    if (e !== el) e.classList.remove('focused');
  });
  el.classList.add('focused');
  current = el;
  if (scroll) {
    try {
      el.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' });
    } catch (e) {
      el.scrollIntoView();
    }
  }
}

export function focusFirst(root) {
  const list = Array.from((root || scope()).querySelectorAll(FOCUSABLE)).filter(visible);
  if (list.length) setFocus(list[0], false);
  return list[0];
}

function move(dir) {
  const list = candidates();
  if (!list.length) return;
  if (!current || !current.isConnected) {
    // 还没有焦点：第一次按方向键落在屏幕内最靠上的元素
    setFocus(list[0]);
    return;
  }
  const cr = current.getBoundingClientRect();
  const cx = cr.left + cr.width / 2, cy = cr.top + cr.height / 2;
  let best = null, bestScore = Infinity;

  for (const el of list) {
    if (el === current) continue;
    const r = el.getBoundingClientRect();
    const x = r.left + r.width / 2, y = r.top + r.height / 2;
    const dx = x - cx, dy = y - cy;
    let primary, cross;
    if (dir === 'left') { if (dx > -8) continue; primary = -dx; cross = Math.abs(dy); }
    else if (dir === 'right') { if (dx < 8) continue; primary = dx; cross = Math.abs(dy); }
    else if (dir === 'up') { if (dy > -8) continue; primary = -dy; cross = Math.abs(dx); }
    else { if (dy < 8) continue; primary = dy; cross = Math.abs(dx); }
    // 同一行/列优先，跨行惩罚更重；距离越近越好
    const score = primary + cross * 2.6 + (cross > 4 ? 60 : 0);
    if (score < bestScore) { bestScore = score; best = el; }
  }

  if (best) {
    setFocus(best);
    return;
  }

  // 该方向没有元素：左右到边界时在整页范围内兜底（导航/返回按钮等）
  if (dir === 'up' || dir === 'left') {
    const fallback = list.find(el => {
      const r = el.getBoundingClientRect();
      return dir === 'up' ? r.bottom < cr.top + 2 : r.right < cr.left + 2;
    });
    if (fallback) setFocus(fallback);
  }
}

function isTyping(el) {
  if (!el) return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
}

export function initFocus() {
  document.addEventListener('keydown', e => {
    if (!enabled) return;
    if (e.metaKey || e.ctrlKey || e.altKey) return;

    if (e.key === 'Escape') {
      document.dispatchEvent(new CustomEvent('app:escape', { detail: { handled: false } }));
      return;
    }
    if (isTyping(document.activeElement)) return;

    const map = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' };
    if (map[e.key]) {
      e.preventDefault();
      mouseFocus = false;
      move(map[e.key]);
      return;
    }
    if (e.key === 'Enter' || e.key === ' ') {
      // 遥控器用 current；Tab 进来的情况 current 是空的，退回到 activeElement
      const ae = document.activeElement;
      const el = (current && current.isConnected) ? current
        : (ae && ae.closest ? ae.closest(FOCUSABLE) : null);
      if (!el) return;
      e.preventDefault();
      el.click();
    }
  }, true);

  // 鼠标移入时同步焦点，键盘与鼠标的视觉语言保持一致；
  // 但鼠标离开后要把这个"临时焦点"收掉，否则移开鼠标还亮着，像被选中了一样。
  document.addEventListener('pointerover', e => {
    const t = e.target.closest && e.target.closest(FOCUSABLE);
    if (t && visible(t) && t !== current) {
      mouseFocus = true;
      setFocus(t, false);
    }
  }, { passive: true });

  document.addEventListener('pointerout', e => {
    if (!mouseFocus) return;
    const t = e.target.closest && e.target.closest(FOCUSABLE);
    if (!t || t !== current) return;
    const to = e.relatedTarget;
    if (to && t.contains(to)) return;      // 还在元素内部移动，不处理
    clearFocus();
    mouseFocus = false;
  }, { passive: true });
}

export function setFocusEnabled(on) { enabled = on; }
export function currentFocus() { return current; }

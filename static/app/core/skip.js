/* 片头 / 片尾标记（Web 端）
 *
 * 语义与原生 core 的 SkipRule / SkipMark 保持一致，方便两端行为对得上：
 *   - 按「剧名」记，不按集 —— 同一部剧每集片头长度一致，标一次全局生效；
 *   - 毫秒存储，0 表示关闭；
 *   - 新剧默认片头 90 秒、片尾不提示（和 App 相同的默认值）；
 *   - 只存本地：Web 端每台设备自己调，不占 NAS 配置。
 */
export const INTRO_OPTIONS = [0, 30000, 60000, 90000, 120000, 180000];
export const OUTRO_OPTIONS = [0, 15000, 30000, 60000, 90000];

export const DEFAULT_INTRO = 90000;
export const DEFAULT_OUTRO = 0;

const KEY = 'pr.skip.';

/** 取某部剧的标记；存储坏了就退回默认值，绝不给出离谱时长 */
export function readMark(name) {
  const fallback = { intro: DEFAULT_INTRO, outro: DEFAULT_OUTRO };
  if (!name) return fallback;
  try {
    const raw = localStorage.getItem(KEY + name);
    if (!raw) return fallback;
    const o = JSON.parse(raw);
    const intro = Number(o && o.intro);
    const outro = Number(o && o.outro);
    if (!Number.isFinite(intro) || !Number.isFinite(outro) || intro < 0 || outro < 0) {
      return fallback;
    }
    return { intro, outro };
  } catch (e) {
    return fallback;
  }
}

export function writeMark(name, mark) {
  if (!name) return;
  try {
    if (!mark.intro && !mark.outro) localStorage.removeItem(KEY + name);
    else localStorage.setItem(KEY + name, JSON.stringify({ intro: mark.intro || 0, outro: mark.outro || 0 }));
  } catch (e) { /* 配额满了就只在本次播放内生效 */ }
}

/** 该不该浮出「跳过片头」。position 为 0 时不算：起播瞬间会闪一下 */
export function shouldShowIntro(position, introMs, live) {
  return !live && introMs > 0 && position > 0 && position < introMs / 1000;
}

/** 该不该提示「下一集」。duration 未知（NaN/0）时不提示 */
export function shouldShowOutro(position, duration, outroMs, hasNext, live) {
  if (live || !hasNext || outroMs <= 0) return false;
  if (!(duration > 0) || !(position > 0)) return false;
  if (position >= duration) return false;
  return (duration - position) * 1000 <= outroMs;
}

/** 片尾倒计时：向上取整，至少 1 秒（显示「还剩 0 秒」等于没在倒计时） */
export function remainingSec(position, duration) {
  const left = duration - position;
  if (!(left > 0)) return 0;
  return Math.ceil(left);
}

/** 档位文案，0 一律叫「关闭」 */
export function label(ms) {
  if (!ms) return '关闭';
  const total = Math.round(ms / 1000);
  if (total < 60) return `${total} 秒`;
  const m = Math.floor(total / 60);
  const s = total % 60;
  return s ? `${m} 分 ${s} 秒` : `${m} 分钟`;
}

/** 档位循环：点一下就换到下一档，转完回到 0 */
export function nextOption(options, cur) {
  const i = options.indexOf(cur);
  return options[(i < 0 ? 0 : i + 1) % options.length];
}

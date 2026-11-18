// 页面可能不在站点根上：
// 直接开 8899 时是 "/"，挂在 NAS 面板的 /nas/ 下时是 "/nas/"。
// 所有以 / 开头的接口地址都必须补上这一段，否则会打到面板自己身上。
export const BASE = location.pathname.replace(/\/[^/]*$/, '/');

export function withBase(u) {
  if (typeof u !== 'string' || !u.startsWith('/') || u.startsWith('//')) return u;
  return BASE.replace(/\/$/, '') + u;
}

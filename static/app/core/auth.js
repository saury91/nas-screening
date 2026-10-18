// 账户已移除（见 docs/standalone-arch.md）。
// 这里只保留目前仍被引用的三个导出，避免各页面 import 报错。

export const LOCAL_USER = { id: 'admin', name: '本机', avatar: '', role: 'admin', hasPin: false };

export function getToken() { return ''; }
export function setToken() { /* 无账户 */ }
export function needLogin() { /* 无账户：不会再有 401 */ }
export function isAdmin() { return true; }
export function currentUser() { return LOCAL_USER; }

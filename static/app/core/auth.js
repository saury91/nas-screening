// 账户已移除：这里保留同名导出，避免各页面 import 报错。
// 需要多账户时，恢复服务端 /api/auth 与这个文件即可（见 docs/standalone-arch.md）。

export const LOCAL_USER = { id: 'local', name: '本机', avatar: '', role: 'admin', hasPin: false };

export function getToken() { return ''; }
export function setToken() { /* 无账户 */ }
export function needLogin() { /* 无账户 */ }
export function onAuthLost() { /* 无账户 */ }
export function isAdmin() { return true; }
export function currentUser() { return LOCAL_USER; }
export function refreshProfiles() { return []; }
export function usingDefaultPin() { return false; }
export function logout() { /* 无账户 */ }
export function openSwitcher() { /* 无账户：头像不再切换身份 */ }
export function showGate() { /* 无登录门 */ }
export function hideGate() { /* 无登录门 */ }
export async function autoTvLogin() { return false; }

/** 启动时不再需要问"我登录了吗"：固定返回本机身份 */
export async function fetchState() {
  return { initialized: true, me: LOCAL_USER, profiles: [], needsPinChange: false };
}

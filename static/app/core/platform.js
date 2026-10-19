// 平台判定集中在一处，避免各处自己写正则、慢慢漂移。
//
// 产品分工是硬边界：
//   手机 / 电视 App = 消费端（浏览、播放、投屏），没有管理后台；
//   PC 浏览器      = 管理台（片源、直播源、下载、备份都在这里）。
// 之所以这么切：App 里配片源需要粘地址、看跳过原因、试连通性，
// 这些在遥控器和竖屏上根本没法好好做，硬塞进去只会两边都难用。

export const isApp = () => {
  try {
    if (window.ProomNative || window.ProomTV) return true;
  } catch (e) {}
  return /Proom(TV|Phone|Mobile)\//.test(navigator.userAgent);
};

export const isTv = () => {
  try {
    if (/ProomTV\//.test(navigator.userAgent)) return true;
  } catch (e) {}
  return document.documentElement.classList.contains('tv');
};

export const isPhone = () => /Proom(Phone|Mobile)\//.test(navigator.userAgent);

// PC 浏览器才有的管理能力
export const canManage = () => !isApp();

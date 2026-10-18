package com.projectionroom.tv

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * 暴露给网页的原生能力。名字以 window.ProomNative 出现。
 *
 * 只放"网页做不到"的事：原生播放、读服务器地址。
 * 注意：@JavascriptInterface 的方法都在 WebView 的 JS 线程里执行，
 * 碰 UI 或启动 Activity 要切回主线程。
 */
class NativeBridge(private val act: MainActivity) {

    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun version(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun server(): String = Prefs.server(act)

    /** 登录态：存在原生侧，换页面来源（NAS → 本地服务）也不会掉登录 */
    @JavascriptInterface
    fun getToken(): String = Prefs.token(act)

    @JavascriptInterface
    fun setToken(t: String) = Prefs.setToken(act, t)

    /** 稳定的设备 id（前端拿它做投屏标识） */
    @JavascriptInterface
    fun deviceId(): String = Prefs.deviceId(act)

    @JavascriptInterface
    fun play(url: String, title: String) {
        main.post { act.playNative(url, title) }
    }

    /** 预留：以后投屏接管时，手机可以要求 TV 停止当前播放 */
    @JavascriptInterface
    fun stop() {
        // P0 阶段原生播放是独立 Activity，由它自己的返回键负责退出
    }

    /** 手机播放时切横屏；电视端忽略 */
    @JavascriptInterface
    fun setLandscape(on: Boolean) {
        main.post { act.setPlayerOrientation(on) }
    }

    /** 局域网直连投屏：异步发现，结果回给页面（阻塞 1~2 秒，不能卡 JS 线程） */
    @JavascriptInterface
    fun castDiscover() {
        Thread({
            val json = com.projectionroom.tv.LocalCast.discover()
            act.bridgeEval(
                "window.__proomCastResult && window.__proomCastResult(${org.json.JSONObject.quote(json)})"
            )
        }, "cast-discover").start()
    }

    /** 局域网直连投屏：推过去 */
    @JavascriptInterface
    fun castPlay(url: String, payload: String): Boolean =
        com.projectionroom.tv.LocalCast.cast(url, payload)

    /** 打开「设置 NAS 地址」页（遥控器菜单键在没有播放时触发） */
    @JavascriptInterface
    fun openSetup() {
        main.post {
            act.startActivity(android.content.Intent(act, SetupActivity::class.java))
            act.finish()
        }
    }

    @JavascriptInterface
    fun toast(msg: String) {
        main.post { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show() }
    }
}

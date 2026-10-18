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

    @JavascriptInterface
    fun toast(msg: String) {
        main.post { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show() }
    }
}

package com.projectionroom.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat

/**
 * 电视端主界面：一个全屏 WebView 装现有的前端。
 *
 * 这里只做三件事：加载页面、把原生能力暴露给 JS、处理遥控器。
 * 界面本身完全复用 Web 端，保证和电脑上一致。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var errorBox: View
    private lateinit var errorTitle: TextView
    private lateinit var errorDesc: TextView

    private val server: String
        get() = Prefs.server(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = server
        if (url.isBlank()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        errorBox = findViewById(R.id.error)
        errorTitle = findViewById(R.id.error_title)
        errorDesc = findViewById(R.id.error_desc)
        findViewById<Button>(R.id.error_btn).setOnClickListener { loadHome() }

        // 冷启动：清掉 WebView 里的 localStorage / Cookie，等于每次开机都要重输 PIN
        if (App.freshProcess) {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            App.freshProcess = false
        }

        val major = webViewMajor()
        if (major in 1..54) {
            showUnsupported(major)
            return
        }

        setupWebView()
        loadHome()
        checkUpdate()
    }

    /** 内网自升级：后台问一句 NAS，有新版就弹窗（遥控器可直接操作） */
    private fun checkUpdate() {
        Thread({
            val info = Updater.check(this) ?: return@Thread
            runOnUiThread { askUpdate(info) }
        }, "update-check").start()
    }

    private fun askUpdate(info: Updater.Info) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("发现新版本 ${info.name}")
            .setMessage(info.notes.ifBlank { "更新到 ${info.name}？" })
            .setPositiveButton("更新") { _, _ -> doUpdate(info) }
            .setNegativeButton("以后再说", null)
            .show()
    }

    private fun doUpdate(info: Updater.Info) {
        Toast.makeText(this, "正在下载 ${info.name}…", Toast.LENGTH_SHORT).show()
        Thread({
            try {
                val apk = Updater.download(this, info)
                runOnUiThread {
                    if (!Updater.install(this, apk)) {
                        Toast.makeText(this, "请在系统设置里允许安装未知应用后重试",
                            Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, "update-download").start()
    }

    private fun loadHome() {
        errorBox.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl(server.trimEnd('/') + "/#/home")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.setBackgroundColor(Color.parseColor("#07080B"))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // 电视上没有"用户手势"，允许自动播放
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            // 让前端能识别"这是电视端"，从而切到大屏/遥控器布局
            userAgentString = userAgentString + " ProomTV/" + BuildConfig.VERSION_NAME
        }

        web.addJavascriptInterface(NativeBridge(this), "ProomNative")

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 早点打标记，避免首屏闪一下桌面布局
                web.evaluateJavascript(
                    "(function(){if(document.documentElement)document.documentElement.classList.add('tv')})()",
                    null
                )
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                errorBox.visibility = View.GONE
                injectTvHooks()
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?, errorCode: Int, description: String?, failingUrl: String?
            ) {
                if (Build.VERSION.SDK_INT < 23) showNetError()
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) showNetError()
            }
        }

        web.requestFocus()
        web.isFocusable = true
        web.isFocusableInTouchMode = true
    }

    /**
     * 给页面打两个标记：
     *  - html.tv  —— CSS 可以据此调整电视端间距/字号
     *  - window.ProomTV —— 前端调用原生播放器的稳定入口
     */
    private fun injectTvHooks() {
        val js = """
            (function(){
              // 错误页（chrome-error://）没有 documentElement，别在这里抛异常
              if (!document.documentElement) return;
              document.documentElement.classList.add('tv');
              window.ProomTV = {
                version: '${BuildConfig.VERSION_NAME}',
                webview: ${webViewMajor()},
                play: function(url, title){ window.ProomNative.play(url || '', title || ''); },
                stop: function(){ window.ProomNative.stop(); },
                server: function(){ return window.ProomNative.server(); }
              };
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    /** 系统 WebView 主版本号，用来判断前端能不能跑得动 */
    private fun webViewMajor(): Int {
        val pkg = WebViewCompat.getCurrentWebViewPackage(this) ?: return 0
        val name = pkg.versionName ?: return 0
        return name.split(".").firstOrNull()?.toIntOrNull() ?: 0
    }

    private fun showUnsupported(major: Int) {
        web.visibility = View.GONE
        errorBox.visibility = View.VISIBLE
        errorTitle.text = getString(R.string.unsupported_title)
        errorDesc.text = getString(R.string.unsupported_desc, major)
        // 这种情况重试没用，把按钮换成"重新设置地址"
        findViewById<Button>(R.id.error_btn).apply {
            text = getString(R.string.setup_title)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SetupActivity::class.java))
                finish()
            }
        }
    }

    private fun showNetError() {
        errorBox.visibility = View.VISIBLE
        errorTitle.text = "连不上 NAS"
        errorDesc.text = "检查一下 ${server} 能不能打开，以及电视和 NAS 是不是同一个网络。"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (web.canGoBack()) {
                    web.goBack()
                    return true
                }
            }
            // 遥控器上的菜单键 = 重新设置服务器地址
            KeyEvent.KEYCODE_MENU -> {
                startActivity(Intent(this, SetupActivity::class.java))
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.destroy()
        }
        super.onDestroy()
    }

    /** 供 JS 桥调用 */
    fun playNative(url: String, title: String) {
        if (url.isBlank()) return
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("url", url)
            putExtra("title", title)
        })
    }
}

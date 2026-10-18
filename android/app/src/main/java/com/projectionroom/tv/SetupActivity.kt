package com.projectionroom.tv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 首次启动（或按遥控器菜单键）时确定 NAS 地址。
 *
 * 三条路，按「对遥控器友好程度」排序：
 *   1. UDP 广播自动发现（同网段时 1 秒内搞定，全程不用按键）
 *   2. 手机扫码：电视显示二维码，手机打开后填地址发过来
 *   3. 手动输入（留给调试和没有手机的情况）
 */
class SetupActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var stateText: TextView
    private var pair: PairServer? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        stateText = findViewById(R.id.state)

        // 二维码指向电视自己的配对页：手机扫码后能把地址"发"回电视
        val ip = Discovery.localIp(this)
        val qrView = findViewById<ImageView>(R.id.qr)
        if (ip != null) {
            qrView.setImageBitmap(makeQr("http://$ip:8898/", 480))
            pair = PairServer(::save).also { it.start() }
        } else {
            qrView.visibility = View.GONE
            stateText.text = getString(R.string.setup_waiting)
        }

        // 同时开始自动发现，谁先成功用谁
        Thread({
            val found = Discovery.find()
            ui.post {
                if (!done && found != null) {
                    stateText.text = getString(R.string.setup_found)
                    save(found)
                } else if (!done) {
                    stateText.text = getString(R.string.setup_waiting)
                }
            }
        }, "discovery").start()

        val input = findViewById<EditText>(R.id.url)
        findViewById<Button>(R.id.ok).setOnClickListener {
            val raw = input.text.toString().trim().trimEnd('/')
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                Toast.makeText(this, R.string.setup_bad, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            save(raw)
        }
    }

    /** 三条路最终都汇到这里 */
    private fun save(url: String) {
        if (done) return
        done = true
        Prefs.setServer(this, url)
        ui.post {
            pair?.stop()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    override fun onDestroy() {
        pair?.stop()
        super.onDestroy()
    }
}

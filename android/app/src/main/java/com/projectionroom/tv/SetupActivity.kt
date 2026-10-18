package com.projectionroom.tv

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 首次启动（或者按遥控器菜单键）时填 NAS 地址 */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val input = findViewById<EditText>(R.id.url)
        input.setText(Prefs.server(this))
        input.requestFocus()

        findViewById<Button>(R.id.ok).setOnClickListener {
            val raw = input.text.toString().trim().trimEnd('/')
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                Toast.makeText(this, R.string.setup_bad, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Prefs.setServer(this, raw)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}

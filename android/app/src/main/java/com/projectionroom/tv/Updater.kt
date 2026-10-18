package com.projectionroom.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内网自升级。
 *
 * NAS 上放一份「最新版本」描述（/api/app/latest），App 启动时对一下 versionCode，
 * 有新版就从 NAS 下载、交给系统的安装器。
 *
 * 为什么不用应用商店：这是侧载的应用，电视上也没有商店可上。
 */
object Updater {

    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 60000

    data class Info(val code: Int, val name: String, val size: Long, val notes: String,
                    val sha256: String = "")

    /** 查有没有新版；网络不通就返回 null，不打扰用户 */
    fun check(ctx: Context): Info? {
        val base = Prefs.server(ctx).trimEnd('/')
        if (base.isBlank()) return null
        return try {
            val json = get("$base/api/app/latest?kind=" + BuildConfig.KIND) ?: return null
            val o = JSONObject(json)
            val code = o.optInt("versionCode")
            if (code <= BuildConfig.VERSION_CODE) return null
            Info(code, o.optString("versionName"), o.optLong("size"), o.optString("notes"),
                o.optString("sha256"))
        } catch (_: Exception) {
            null
        }
    }

    /** 下载到 cache/apk/update.apk，返回文件 */
    fun download(ctx: Context, info: Info): File {
        val base = Prefs.server(ctx).trimEnd('/')
        val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
        val out = File(dir, "update.apk")
        val conn = (URL("$base/api/app/download?kind=" + BuildConfig.KIND).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw java.io.IOException("NAS 返回 HTTP $code")
        }
        conn.inputStream.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        conn.disconnect()
        // 下载完整性校验：断流时不会留下一个装不上的半截文件
        if (info.size > 0 && out.length() != info.size) {
            val got = out.length()
            out.delete()
            throw java.io.IOException("下载不完整（$got/${info.size}）")
        }
        // 校验和：NAS 上一份 APK 被换掉/传坏时能立刻发现，而不是装上再闪退
        if (info.sha256.isNotBlank()) {
            val actual = sha256Of(out)
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                out.delete()
                throw java.io.IOException("安装包校验失败，请重新下载")
            }
        }
        return out
    }

    /** 拉起系统安装器；电视上如果没给权限，先去设置页开 */
    fun install(act: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        act.startActivity(intent)
        return true
    }

    private fun sha256Of(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
        }
        return try {
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}

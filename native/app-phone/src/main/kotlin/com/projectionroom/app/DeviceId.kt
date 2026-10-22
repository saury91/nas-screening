package com.projectionroom.app

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * 本机在投屏名录里的稳定 id。
 *
 * 为什么不用随机 UUID 直接存：ANDROID_ID 在「同一用户 + 同一签名」下是稳定的，
 * 重装后不变，NAS 那边的投屏名录不会因为一次覆盖安装就多出一台幽灵设备。
 * 只有个别定制系统会返回空/全 0，这时才退回到自生成 UUID 并落盘。
 */
internal object DeviceId {

    private const val PREFS = "proom_device"
    private const val KEY = "id"

    @Volatile
    private var cached: String? = null

    fun get(ctx: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = sp.getString(KEY, null)?.takeIf { it.isNotBlank() }
            val id = saved ?: androidId(ctx) ?: UUID.randomUUID().toString()
            if (saved == null) sp.edit().putString(KEY, id).apply()
            cached = id
            return id
        }
    }

    private fun androidId(ctx: Context): String? =
        runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
}

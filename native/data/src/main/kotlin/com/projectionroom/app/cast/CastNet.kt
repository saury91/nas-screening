package com.projectionroom.app.cast

import android.content.Context
import android.provider.Settings

/**
 * 接收端的设备 id。
 *
 * 用 ANDROID_ID 而不是随机 UUID：NAS 重启、App 升级后还是同一台电视，
 * 手机那边的「我的电视」不会因为一次重装就变成陌生设备。
 *
 * 抽成文件级函数是因为现在有三条接收链路要用它（DLNA 的 UDN、NAS 名录、
 * 自定义协议的 offer）：每个类各存一份私有实现，迟早改成只改一处。
 */
internal fun castDeviceId(ctx: Context): String {
    val sp = ctx.getSharedPreferences("proom_device", Context.MODE_PRIVATE)
    sp.getString("id", null)?.takeIf { it.isNotBlank() }?.let { return it }
    val raw = runCatching {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
    val id = "tv-" + (raw ?: java.util.UUID.randomUUID().toString())
    sp.edit().putString("id", id).apply()
    return id
}

package com.projectionroom.tv

import android.content.Context
import android.content.res.Configuration
import android.app.UiModeManager
import android.content.pm.PackageManager

/**
 * 这个包同时装手机和电视，行为要分开。
 *
 * 判断依据（任一命中算电视）：
 *   - 系统声明了 Leanback（Android TV 的正式标志）
 *   - UiModeManager 当前是 TELEVISION 模式（部分盒子只声明这个）
 */
object Device {

    fun isTv(ctx: Context): Boolean {
        val pm = ctx.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true

        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

        // 有些盒子既不声明 Leanback 也不报 TELEVISION 模式：
        // 没有触摸屏 + 屏幕够大，按电视处理（平板有触摸，不会被误判）
        val noTouch = !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        val wide = ctx.resources.configuration.smallestScreenWidthDp >= 600
        return noTouch && wide
    }

    /** WebView 的 UA 尾巴：前端靠它决定用哪套布局 */
    fun uaTag(ctx: Context): String = if (isTv(ctx)) "ProomTV/" else "ProomMobile/"
}

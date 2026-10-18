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

    /**
     * 电视还是手机，**在编译期就定了**（见 build.gradle.kts 的 productFlavors）。
     *
     * 以前是运行时猜（Leanback / UiMode / 有没有触摸屏），
     * 同一个包装到不同设备上行为会飘——手机被当成电视、平板被当成手机。
     * 现在 tv / phone 是两个独立的包，判断退化成读一个常量。
     */
    fun isTv(): Boolean = BuildConfig.IS_TV

    /** WebView 的 UA 尾巴：前端靠它决定用哪套布局 */
    fun uaTag(): String = if (BuildConfig.IS_TV) "ProomTV/" else "ProomPhone/"
}

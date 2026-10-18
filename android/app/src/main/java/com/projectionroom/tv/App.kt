package com.projectionroom.tv

import android.app.Application

/**
 * 进程级状态。
 *
 * TV 端的规则是「每次冷启动都要重新输 PIN」，但切后台再回来不该重新登录。
 * 用进程是否新建来区分这两种情况：进程死了 → freshProcess 为 true → 启动时清掉 WebView 存储。
 */
class App : Application() {

    companion object {
        /** 每次进程启动都是 true，被 MainActivity 消费一次后置 false */
        var freshProcess = true
    }

    override fun onCreate() {
        super.onCreate()
        freshProcess = true
    }
}

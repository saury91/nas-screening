package com.projectionroom.shared.cast

import com.projectionroom.shared.net.Http

/**
 * 全局的投屏客户端入口。
 *
 * 为什么用单例而不是一路往下传参：电视端的接收循环活在 `CastService` 里，
 * 手机端的投屏面板活在 `PlayerScreen` 里，两边分属不同模块（data / ui-media），
 * 但它们连的是同一个 NAS、用的是同一个设备 id。硬串参要穿过 5 层 composable。
 *
 * 初始化在壳里做一次（拿到 NAS 地址和设备 id 之后），没初始化就当作
 * 「NAS 投屏不可用」，界面自动只显示局域网设备。
 */
object CastHub {

    private var api: CastApi? = null

    /** 本机在投屏名录里的 id。手机和电视各自一个，用来认「谁在控制」 */
    var selfId: String = ""
        private set

    val ready: Boolean get() = api != null && selfId.isNotBlank()

    fun install(http: Http, baseUrl: String, selfId: String) {
        if (baseUrl.isBlank() || selfId.isBlank()) return
        api = CastApi(http, baseUrl)
        this.selfId = selfId
    }

    fun uninstall() {
        api = null
        selfId = ""
    }

    val client: CastApi? get() = api

    /** 本机名字：电视端注册时报给 NAS，手机端用来判断「这台是不是我」 */
    fun isMine(target: CastApi.Target): Boolean = target.id == selfId
}

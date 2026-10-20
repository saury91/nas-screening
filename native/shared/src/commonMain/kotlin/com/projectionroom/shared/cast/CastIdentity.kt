package com.projectionroom.shared.cast

/**
 * 本进程自己正在提供的接收端身份。
 *
 * 投屏列表必须剔掉"自己"，但**不能按 IP 剔**：同一台设备上可能跑着
 * 两个实例（模拟器联调），手机也常有 Wi-Fi + 热点两个网段 —— 按 IP 过滤
 * 会把这些正常的接收端一起丢掉。UDN 才是唯一身份。
 *
 * 只有真的起了 DMR 的进程才有值；手机端不跑接收端，这里恒为空。
 */
object CastIdentity {
    @Volatile
    var udn: String = ""
}

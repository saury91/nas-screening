package com.projectionroom.app

import kotlinx.coroutines.withTimeoutOrNull

/**
 * NAS 熔断器。
 *
 * 场景：**第一次同步完配置之后，那台 NAS 可能永远不会再开机**。
 * 这时候每次问 NAS 都白等超时 —— 启动同步要等 10 秒，功能没坏但手感全毁。
 *
 * 所以失败一次就短暂拉黑：本进程内 60 秒不再尝试，之后放行一次探探。
 *
 * **只给必要的四类接口用**：同步、投射、更新、配置。资源加载（首页/片库/搜索/
 * 详情/播放/音乐）一律直连源站，不经过这里 —— 见 [NasApi] 的类型注释。
 */
object NasGate {

    @Volatile
    private var downUntil: Long = 0L

    /** 熔断时长：够短，NAS 临时重启也能很快接回来 */
    private const val DOWN_MS = 60_000L

    fun available(): Boolean = System.currentTimeMillis() >= downUntil

    fun markDown() {
        downUntil = System.currentTimeMillis() + DOWN_MS
    }

    fun markUp() {
        downUntil = 0L
    }

    /**
     * 问一次 NAS：不可用/超时/抛异常都返回 null，并顺手熔断。
     *
     * @param timeoutMs 单次预算。默认 2.5 秒 —— 本地已经能出结果，兜底不该让人等。
     */
    suspend fun <T : Any> tryCall(timeoutMs: Long = 2_500, block: suspend () -> T?): T? {
        if (!available()) return null
        val r = runCatching { withTimeoutOrNull(timeoutMs) { block() } }
        val v = r.getOrNull()
        if (v != null) {
            markUp()
            return v
        }
        markDown()
        return null
    }
}

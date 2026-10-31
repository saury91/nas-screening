package com.projectionroom.shared.model

import kotlinx.serialization.Serializable

/**
 * 一条采集源。字段与 NAS 下发的 `config/bundle` 一一对应：
 * `{ name, api, type, cat }`，不额外发明字段，方便两端对拍。
 */
@Serializable
data class Source(
    val name: String,
    val api: String,
    val type: Int = 1,
    val cat: String = "综合",
    /**
     * 该站的分类 ID → 大类映射，例如 `{"20": "电影", "21": "剧集"}`。
     *
     * 各站 type_id 排法完全不同（暴风 t=20 才是电影，别家 t=6 就是），
     * App 自己猜不出来，所以由 NAS 探好后随配置包下发。
     * 为空表示这个源没探到映射 —— 浏览时跳过它，搜索不受影响。
     */
    val cats: Map<String, String> = emptyMap(),
) {
    /** 某个大类在这个源上的所有 type_id */
    fun typeIdsOf(cat: String): List<String> =
        cats.entries.filter { it.value == cat }.map { it.key }
}

@Serializable
data class Episode(val name: String, val url: String)

/** 一部片在某个源上的全部选集。同一部片可以有多条线路（多个源）。 */
@Serializable
data class Line(
    val site: String,
    val remarks: String = "",
    val quality: String = "",
    val episodes: List<Episode> = emptyList(),
) {
    val count: Int get() = episodes.size
}

@Serializable
data class Vod(
    val name: String,
    val year: String = "",
    val pic: String = "",
    val type: String = "",
    val area: String = "",
    val lang: String = "",
    val score: String = "",
    val desc: String = "",
    /** 0~1，只有「继续观看」里的条目有值 */
    val progress: Float = 0f,
    val lines: List<Line> = emptyList(),
) {
    val bestQuality: String get() = lines.minByOrNull { Quality.rank(it.quality) }?.quality.orEmpty()
    val episodeCount: Int get() = lines.maxOfOrNull { it.count } ?: 0
}

/**
 * 画质分档。顺序即优先级，`rank` 越小越好，猜不到给最大值（排最后）。
 * 规则与 NAS 端 `text.guess_quality` 保持一致，避免两边显示对不上。
 */
object Quality {
    private val order = listOf("4K", "1080P", "720P", "HD", "480P")

    private val numeric = listOf(
        "4K" to Regex("(?<![a-z0-9])(4k|2160p?|uhd)(?![a-z0-9])"),
        "1080P" to Regex("(?<![a-z0-9])(1080p?|fhd)(?![a-z0-9])"),
        "720P" to Regex("(?<![a-z0-9])(720p?)(?![a-z0-9])"),
        "480P" to Regex("(?<![a-z0-9])(480p?)(?![a-z0-9])"),
    )

    private val words = listOf(
        "1080P" to listOf("蓝光", "原盘", "remux", "bluray", "bdrip"),
        "HD" to listOf("高清", "hd中字", "hd国语", "高码"),
        "480P" to listOf("标清"),
    )

    fun rank(q: String): Int = order.indexOf(q).let { if (it < 0) order.size else it }

    /**
     * 从备注 / 播放地址 / 站名里认画质，认不出返回空串。
     * 只看 URL 的 path，别拿整条地址匹配 —— 域名里带 `sd` 的会被误判成 480P。
     */
    fun from(vararg texts: String): String {
        val blob = texts.filter { it.isNotBlank() }
            .joinToString(" ") { if (it.startsWith("http")) pathOf(it) else it }
            .lowercase()
        if (blob.isBlank()) return ""
        numeric.firstOrNull { (_, re) -> re.containsMatchIn(blob) }?.let { return it.first }
        words.firstOrNull { (_, ws) -> ws.any { w -> blob.contains(w) } }?.let { return it.first }
        return ""
    }

    private fun pathOf(url: String): String {
        val noScheme = url.substringAfter("://", url)
        val hostEnd = noScheme.indexOf('/')
        return if (hostEnd >= 0) noScheme.substring(hostEnd) else ""
    }
}

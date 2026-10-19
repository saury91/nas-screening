package com.projectionroom.shared.catalog

import com.projectionroom.shared.model.Episode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 苹果 CMS（MacCMS）采集协议 —— 我们接的源里 95% 都是它。
 *
 * 只做三件事：拼搜索地址、拆 JSON、拆播放地址。
 * 全部是纯函数，不需要网络，因此可以被单测完全覆盖。
 */
object MacCms {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 一条未归并的原始条目，字段名沿用采集站的 vod_* 习惯，方便和 NAS 端对拍。 */
    data class Raw(
        val name: String,
        val year: String = "",
        val pic: String = "",
        val type: String = "",
        val remarks: String = "",
        val area: String = "",
        val lang: String = "",
        val score: String = "",
        val desc: String = "",
        val playUrl: String = "",
    )

    fun searchUrl(api: String, keyword: String, page: Int = 1): String {
        val sep = if (api.contains('?')) '&' else '?'
        return "$api${sep}ac=detail&wd=${keyword.encodeQuery()}&pg=$page"
    }

    /**
     * 解析搜索结果。采集站的返回有三种常见形态，全都要吃：
     *   {"code":1,"list":[…]} / {"data":{"list":[…]}} / [ … ]
     */
    fun parseList(text: String): List<Raw> {
        val root = runCatching { json.parseToJsonElement(text.trim()) }.getOrNull() ?: return emptyList()
        val items = when {
            root is JsonArray -> root
            root is JsonObject -> {
                val direct = root["list"]
                val nested = root["data"]?.let { if (it is JsonObject) it["list"] else null }
                (direct ?: nested) as? JsonArray
            }
            else -> null
        } ?: return emptyList()

        return items.mapNotNull { it as? JsonObject }.mapNotNull { obj ->
            val name = obj.str("vod_name")
            if (name.isBlank()) return@mapNotNull null
            Raw(
                name = name,
                year = obj.str("vod_year"),
                pic = obj.str("vod_pic"),
                type = obj.str("type_name"),
                remarks = obj.str("vod_remarks"),
                area = obj.str("vod_area"),
                lang = obj.str("vod_lang"),
                score = obj.str("vod_score"),
                desc = obj.str("vod_blurb").ifBlank { obj.str("vod_content") },
                playUrl = obj.str("vod_play_url"),
            )
        }
    }

    /**
     * 拆播放地址。真实世界的格式很脏：
     *   "第01集$https://a/1.m3u8#第02集$https://a/2.m3u8"
     *   "第01集$https://a/1.m3u8$$$第01集$https://b/1.m3u8"   ← 多线路共用一段
     *   "https://a/1.m3u8#https://a/2.m3u8"                    ← 没有集名
     * 统一按 `#` / `$$$` 切集，段内按第一个 `$` 切「名 / 地址」。
     */
    fun parsePlayUrl(raw: String): List<Episode> {
        if (raw.isBlank()) return emptyList()
        val eps = raw.split("#", "\$\$\$")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { seg ->
                val cut = seg.indexOf('$')
                val name = if (cut >= 0) seg.substring(0, cut).trim() else ""
                val url = if (cut >= 0) seg.substring(cut + 1).trim() else seg
                if (!url.startsWith("http")) null else Episode(name, url)
            }
            .toList()
        // 有些源不给集名，只有一串地址；按顺序补上，界面才不会空着
        return eps.mapIndexed { i, e -> if (e.name.isBlank()) e.copy(name = "第${i + 1}集") else e }
    }

    private fun JsonObject.str(key: String): String {
        val v: JsonElement = this[key] ?: return ""
        return runCatching { v.jsonPrimitive.content }.getOrDefault("").trim()
    }
}

/** 极简 query 编码：只处理不能直接出现在 URL 里的字符，够用且不依赖平台 API。 */
internal fun String.encodeQuery(): String {
    val sb = StringBuilder()
    for (b in this.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch.isLetterOrDigit() && c < 128 || ch in "-._~") sb.append(ch)
        else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
    }
    return sb.toString()
}

private const val HEX = "0123456789ABCDEF"

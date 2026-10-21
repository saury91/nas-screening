package com.projectionroom.shared.catalog

import com.projectionroom.shared.model.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * TVBox 订阅的解析与转换。
 *
 * TVBox 的配置是一个 JSON：`sites`（点播站）+ `lives`（直播源）+ `parses`（解析器）。
 * 我们只用前两个 —— 解析器是给网页嗅探用的，我们的采集源都是 CMS 直连，用不上。
 *
 * 转换原则：**能直接播的才收**。
 *  - `sites` 里 `api` 不是 http 的（`csp_xxx` 这类需要私有解析器）直接丢掉，
 *    收进来也只会在搜索时干等超时；
 *  - `type` 不是 1（点播）的也不是我们认的格式，同样跳过。
 */
@Serializable
data class TvBoxConfig(
    val sites: List<TvBoxSite> = emptyList(),
    val lives: List<TvBoxLive> = emptyList(),
)

@Serializable
data class TvBoxSite(
    val key: String = "",
    val name: String = "",
    val type: Int = 0,
    val api: String = "",
    /** 0 = 不参与搜索。留着字段是为了判断，不影响导入 */
    val searchable: Int = 1,
    val group: String = "",
)

@Serializable
data class TvBoxLive(
    val name: String = "",
    /** 0 = txt 列表，1 = m3u；其它类型是 TVBox 内部的私有格式，跳过 */
    val type: Int = 0,
    /** 可能是多个地址，用 # 分隔 */
    val url: String = "",
)

object TvBox {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): TvBoxConfig? =
        runCatching { json.decodeFromString<TvBoxConfig>(raw) }.getOrNull()

    /**
     * 把 TVBox 的站点转成我们的 [Source]。
     *
     * `cats` 留空：分类映射要连到那个站探一次才知道，
     * 由调用方在后台补（见 [CmsProbe]）。留空意味着「搜索能用、分类浏览跳过」。
     */
    fun sources(cfg: TvBoxConfig, cat: String = "TVBox"): List<Source> =
        cfg.sites
            .filter { it.type == 1 && it.api.startsWith("http", ignoreCase = true) }
            .filter { it.searchable != 0 }
            .mapNotNull { s ->
                val name = s.name.trim().ifBlank { return@mapNotNull null }
                Source(name = name, api = s.api.trim(), type = 1, cat = cat)
            }
            .distinctBy { it.api }

    /** 直播源：name 到 url 列表（TVBox 里常见 `url1#url2` 的备用写法） */
    fun lives(cfg: TvBoxConfig): List<Pair<String, List<String>>> =
        cfg.lives.mapNotNull { l ->
            val urls = l.url.split('#').map { it.trim() }.filter { it.startsWith("http") }
            if (urls.isEmpty()) null else (l.name.ifBlank { "订阅直播" }) to urls
        }
}

/**
 * 直播列表文本解析：`txt` 与 `m3u` 两种常见格式。
 *
 * txt 是 TVBox 自己的方言：
 * ```
 * 央视,#genre#
 * CCTV1,http://a.m3u8#http://b.m3u8
 * ```
 * `#` 分隔同一台的多条线路（我们正好要 urls 列表）。
 */
object LiveList {

    fun parse(raw: String): List<NasApi.LiveChannel> {
        val t = raw.trimStart()
        return if (t.startsWith("#EXTM3U") || t.contains("#EXTINF")) m3u(raw) else txt(raw)
    }

    fun txt(raw: String): List<NasApi.LiveChannel> {
        val out = ArrayList<NasApi.LiveChannel>()
        var group = ""
        raw.lineSequence().forEach { line0 ->
            val line = line0.trim()
            // 「分组名,#genre#」是分组分隔行：以中文开头、以 #genre# 结尾，
            // 必须先认出来，否则会被当成一个没有地址的频道
            if (line.contains("#genre#", ignoreCase = true)) {
                group = line.substringBefore(",").trim()
                return@forEach
            }
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val name = line.substringBefore(',').trim()
            val rest = line.substringAfter(',', "")
            val urls = rest.split('#').map { it.trim() }.filter { it.startsWith("http") }
            if (name.isNotEmpty() && urls.isNotEmpty() && !name.contains("#genre", true)) {
                out += NasApi.LiveChannel(group = group, name = name, urls = urls)
            }
        }
        return out
    }

    fun m3u(raw: String): List<NasApi.LiveChannel> {
        val out = ArrayList<NasApi.LiveChannel>()
        var name = ""
        var group = ""
        raw.lineSequence().forEach { line0 ->
            val line = line0.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    // #EXTINF:-1 tvg-name="CCTV1" group-title="央视",CCTV1
                    group = attr(line, "group-title").ifBlank { attr(line, "tvg-group") }
                    name = line.substringAfterLast(',', "").trim()
                    if (name.isBlank()) name = attr(line, "tvg-name")
                }
                line.startsWith("#") || line.isEmpty() -> Unit
                name.isNotBlank() && line.startsWith("http") -> {
                    out += NasApi.LiveChannel(group = group, name = name, urls = listOf(line))
                    name = ""
                }
            }
        }
        return out
    }

    private fun attr(line: String, key: String): String {
        val i = line.indexOf("$key=\"")
        if (i < 0) return ""
        val start = i + key.length + 2
        val end = line.indexOf('"', start)
        return if (end > start) line.substring(start, end) else ""
    }
}

/**
 * 采集站分类名 → 我们的大类。
 *
 * 和服务端 `catalog._cat()` 同一套规则：两边判定不一致的话，
 * 同一个站在网页端归到「电影」、在 App 里归到「剧集」，浏览结果会对不上。
 */
object Cats {

    val ORDER = listOf("电影", "剧集", "动漫", "综艺", "纪录片")

    /** 界面导航用的 slug，顺序与 [ORDER] 一一对应 */
    private val SLUGS = listOf("movie", "series", "anime", "variety", "doc")

    /**
     * slug → 中文大类。
     *
     * 界面传的是 `movie` / `series`，配置包里 `Source.cats` 的 value 却是中文。
     * 少翻这一步的症状极隐蔽：本地匹配不到任何 type_id → 返回空 → 静默回落到 NAS，
     * 看着"能用"，实际 NAS 一关片库就空（首页走 LocalHome，用的是中文，所以还是好的）。
     */
    val slugToName: Map<String, String> = SLUGS.zip(ORDER).toMap()

    /** slug 或中文都能吃：`movie` → `电影`，`电影` 原样返回 */
    fun nameOf(slug: String): String = slugToName[slug] ?: slug

    fun of(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return ""
        if (t.contains("动漫") || t.contains("动画") || t.contains("番剧")) return "动漫"
        if (t.contains("纪录") || t.contains("记录")) return "纪录片"
        if (t.contains("综艺")) return "综艺"
        // 「剧情片」「动作片」都是电影；先于「剧」判断，否则会被归成剧集
        if (t.endsWith("片")) return "电影"
        if (t.contains("剧")) return "剧集"
        if (t.contains("电影")) return "电影"
        return ""
    }
}

/**
 * 探测一个采集站的分类映射。
 *
 * MacCMS 系站点有三种返回形态，都要认：
 *  - `{"class":[{"type_id":1,"type_name":"电影"}]}`（标准 ac=list）
 *  - `{"list":[{"type_id":11,"type_name":"剧情片"},…]}`（ac=list 直接返回数据列表，
 *    电影天堂这类就是这样 —— 一次响应里顺带就给出了一批 type_id）
 *  - `{"data":[…]}`（部分二开站把 list 改名成 data）
 *
 * 一个都拿不到就返回空表：那个站只剩搜索能用，不能因此让整次导入失败。
 */
object CmsProbe {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun cats(http: com.projectionroom.shared.net.Http, api: String): Map<String, String> {
        val sep = if (api.contains("?")) "&" else "?"
        val raw = runCatching { http.get(api + sep + "ac=list") }.getOrNull() ?: return emptyMap()
        val root = runCatching {
            json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return emptyMap()

        val out = LinkedHashMap<String, String>()

        fun take(o: kotlinx.serialization.json.JsonObject) {
            fun str(vararg keys: String): String? {
                keys.forEach { k ->
                    val v = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (!v.isNullOrBlank()) return v
                }
                return null
            }
            val tid = str("type_id", "id", "typeId")
            val name = str("type_name", "name", "typeName")
            if (tid != null && name != null) {
                val c = Cats.of(name)
                if (c.isNotBlank()) out[tid] = c
            }
        }

        listOf("class", "classes", "list", "data").forEach { key ->
            (root[key] as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
                (el as? kotlinx.serialization.json.JsonObject)?.let(::take)
            }
        }
        return out
    }
}

package com.projectionroom.app

import android.content.Context
import com.projectionroom.shared.catalog.CmsProbe
import com.projectionroom.shared.catalog.LiveList
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.catalog.TvBox
import com.projectionroom.shared.model.Source
import com.projectionroom.shared.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一条第三方订阅。
 *
 * `kind` 只是展示用，实际解析是看内容：以 `{` 开头当 TVBox 配置，
 * 否则当直播列表（txt / m3u）。所以用户填错类型也能用。
 */
@Serializable
data class SubEntry(
    val url: String,
    /** 解析出来的名字（TVBox 配置里没有总名字，就用域名） */
    val name: String = "",
    val at: Long = 0,
    /** 上次解析是否成功；失败时界面上要能看出来，不能装作没事 */
    val ok: Boolean = true,
    val note: String = "",
)

/**
 * NAS 之外的「本地附加配置」。
 *
 * 存在的意义：**没有 NAS 也能用**。
 *  - [sources]：从 TVBox 订阅解析出来的点播站，和 NAS 下发的源合并后一起参与搜索/浏览；
 *  - [live]：从订阅里的直播源解析出来的频道，合并进直播表；
 *  - [subs]：订阅清单本身，刷新时按它重新拉。
 *
 * 全部存本地文件，不往 NAS 写 —— 这是用户自己的东西，不该污染 NAS 那套配置。
 */
@Serializable
data class ExtraConfig(
    val subs: List<SubEntry> = emptyList(),
    val sources: List<Source> = emptyList(),
    val live: List<NasApi.LiveChannel> = emptyList(),
    val updatedAt: Long = 0,
) {
    val isEmpty: Boolean get() = subs.isEmpty() && sources.isEmpty() && live.isEmpty()
}

object ExtraStore {

    private const val FILE = "extra.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun load(ctx: Context): ExtraConfig = runCatching {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) ExtraConfig() else json.decodeFromString<ExtraConfig>(f.readText())
    }.getOrDefault(ExtraConfig())

    fun save(ctx: Context, cfg: ExtraConfig): ExtraConfig {
        runCatching {
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(json.encodeToString(cfg))
            tmp.renameTo(File(ctx.filesDir, FILE))
        }
        return cfg
    }
}

/**
 * 把订阅地址拉下来、解析成源和频道。
 *
 * 每次刷新是全量重算：旧结果先留着（用户正在用），新的算完再整体替换。
 * 一个订阅挂掉不影响其它订阅 —— 逐条 try。
 */
object SubResolver {

    /** 一次刷新最多解析这么多直播源：TVBox 配置里动辄十几条，全拉一遍要分钟级 */
    private const val MAX_LIVE_SOURCES = 6
    /** 分类探测的总预算：探不完的站就只有搜索能用，不能因此卡住整次刷新 */
    private const val PROBE_BUDGET_MS = 15_000L

    suspend fun resolve(http: Http, cfg: ExtraConfig): ExtraConfig {
        if (cfg.subs.isEmpty()) return cfg.copy(sources = emptyList(), live = emptyList())

        val srcs = ArrayList<Source>()
        val chans = ArrayList<NasApi.LiveChannel>()
        val subs = ArrayList<SubEntry>()

        cfg.subs.forEach { sub ->
            val raw = runCatching { http.get(sub.url) }.getOrNull()
            if (raw.isNullOrBlank()) {
                subs += sub.copy(ok = false, note = "拉取失败", at = System.currentTimeMillis())
                return@forEach
            }
            val body = raw.trimStart()
            if (body.startsWith("{")) {
                val tv = TvBox.parse(raw)
                if (tv == null) {
                    subs += sub.copy(ok = false, note = "不是有效的 TVBox 配置", at = System.currentTimeMillis())
                    return@forEach
                }
                val plain = TvBox.sources(tv)
                val probed = probeCats(http, plain)
                srcs += probed
                // 直播源：逐个拉 txt/m3u，最多 MAX_LIVE_SOURCES 条
                var liveCount = 0
                for ((_, urls) in TvBox.lives(tv)) {
                    if (liveCount >= MAX_LIVE_SOURCES) break
                    val u = urls.firstOrNull() ?: continue
                    val text = runCatching { http.get(u) }.getOrNull() ?: continue
                    liveCount += 1
                    chans += LiveList.parse(text)
                }
                subs += sub.copy(
                    ok = true,
                    name = sub.name.ifBlank { hostOf(sub.url) },
                    note = "${probed.size} 个源 · $liveCount 条直播源",
                    at = System.currentTimeMillis(),
                )
            } else {
                // 不是 JSON：当纯直播列表处理（txt / m3u）
                val parsed = LiveList.parse(raw)
                chans += parsed
                subs += sub.copy(
                    ok = parsed.isNotEmpty(),
                    name = sub.name.ifBlank { hostOf(sub.url) },
                    note = if (parsed.isEmpty()) "没有解析出频道" else "${parsed.size} 个频道",
                    at = System.currentTimeMillis(),
                )
            }
        }

        return cfg.copy(
            subs = subs,
            sources = srcs.distinctBy { it.api },
            live = chans.distinctBy { it.name },
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * 给导入的源补分类映射。
     *
     * 没有映射的话这些站只能被搜到、进不了分类浏览 —— 那等于半个残废。
     * 探测是 8 个一批并发，整体给 15 秒预算，探不到的留空即可。
     */
    private suspend fun probeCats(http: Http, srcs: List<Source>): List<Source> {
        if (srcs.isEmpty()) return srcs
        val done = withTimeoutOrNull(PROBE_BUDGET_MS) {
            coroutineScope {
                srcs.chunked(8).flatMap { chunk ->
                    chunk.map { s -> async { s.copy(cats = CmsProbe.cats(http, s.api)) } }.awaitAll()
                }
            }
        }
        return done ?: srcs
    }

    private fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore('/').take(40)
}

/**
 * 订阅来的频道并进直播表。
 *
 * 同名台不当成两条：同一个 CCTV1 在 NAS 和订阅里都有的话，
 * 列表里出现两次才是 bug —— 把订阅的地址并进 urls，多一条备用线路。
 */
fun mergeLive(base: NasApi.LiveFeed, extra: List<NasApi.LiveChannel>): NasApi.LiveFeed {
    if (extra.isEmpty()) return base
    val out = ArrayList<NasApi.LiveChannel>(base.channels.size + extra.size)
    out += base.channels
    extra.forEach { e ->
        val i = out.indexOfFirst { it.name == e.name }
        if (i >= 0) {
            val old = out[i]
            out[i] = old.copy(urls = (old.urls + e.urls).distinct())
        } else {
            out += e.copy(group = e.group.ifBlank { "订阅" })
        }
    }
    // 分组顺序：先保留原顺序，再把订阅带来的新分组接在后面
    val order = LinkedHashSet<String>()
    base.groups.forEach { order += it.name }
    out.forEach { order += it.group }
    val groups = order.map { g -> NasApi.LiveGroup(name = g, count = out.count { it.group == g }) }
    return NasApi.LiveFeed(groups = groups, channels = out)
}

/**
 * 分享用的配置文件。
 *
 * 结构刻意保持「一个 JSON 说清所有事」：拿到这个文件的人不用连 NAS，
 * 导入后片源地址、分类映射、直播表全都在本地了。
 * 里面不含账号、不含播放记录，分享出去不泄露隐私。
 */
@Serializable
data class SharePack(
    val format: String = "proom-config",
    val v: Int = 1,
    val at: Long = 0,
    val bundle: com.projectionroom.shared.catalog.ConfigBundle? = null,
    val extra: ExtraConfig? = null,
) {
    val sourceCount: Int get() = bundle?.sources?.size ?: 0
    val channelCount: Int get() = (bundle?.live?.channels?.size ?: 0) + (extra?.live?.size ?: 0)
}

object ShareConfig {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun encode(bundle: com.projectionroom.shared.catalog.ConfigBundle?, extra: ExtraConfig): String =
        json.encodeToString(
            SharePack(at = System.currentTimeMillis(), bundle = bundle, extra = extra)
        )

    fun decode(raw: String): SharePack? {
        val t = raw.trim()
        // 有人会连「这里是配置：」一起复制进来，从第一个 { 开始截
        val start = t.indexOf('{')
        if (start < 0) return null
        val body = t.substring(start)
        return runCatching { json.decodeFromString<SharePack>(body) }
            .getOrNull()
            ?.takeIf { it.bundle != null || it.extra != null }
    }

    /** 写到 cache/share 下，交给 FileProvider 分享出去 */
    fun writeTemp(ctx: Context, text: String): File {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "放映室配置.json")
        f.writeText(text)
        return f
    }
}

package com.projectionroom.shared.catalog

import com.projectionroom.shared.model.Line

/**
 * 合并采集站重复给出的线路。
 *
 * 实测《多喜一家人》（11 个采集站，抓下来的原始 `vod_play_url`）：
 *
 * - 新浪资源 / 豪华资源：同一条线路给两段 —— `/play/bo2AOzja` 与
 *   `/play/bo2AOzja/index.m3u8`，同一个播放页的两种写法；
 * - 非凡资源 / 天堂资源：`/share/<token>` 与 `/<日期>/<id>/index.m3u8`，
 *   同一个文件的两种地址（跳转页 / 直链）；
 * - 艾旦资源：8 个真的 CDN 镜像（不同域名），外加第 9 段原样抄了非凡的地址。
 *
 * 前两类和第三类里的第 9 段都不是「多一次重试机会」，只是把同一份东西排了多遍：
 * 详情页上就是 20 个几乎一样的「艾旦资源」，看着像重复资源。
 * 这里只删**同一个文件**的重复项，真正的不同 CDN 镜像保留（播不动时还能换）。
 *
 * 判定（任一成立即视为同一条）：
 * 1. 逐集地址归一化后完全相同（去掉 `/index.m3u8`、结尾斜杠）；
 * 2. 同站点 + 同域名 + 同集数 + 同集名 + 同画质 —— 一台机器上的两种写法。
 *
 * 重复时保留能直连的那条（`.m3u8` 直链优先于 `/share/`、`/play/` 跳转页）。
 */
fun dedupeLines(lines: List<Line>): List<Line> {
    if (lines.size < 2) return lines
    val out = ArrayList<Line>(lines.size)
    for (line in lines) {
        val at = out.indexOfFirst { sameSource(it, line) }
        if (at < 0) {
            out.add(line)
        } else if (directScore(line) > directScore(out[at])) {
            // 同一条内容留位置不变，只把地址换成直链那条：顺序是焦点顺序，不能跳
            out[at] = line
        }
    }
    return out
}

/**
 * 站名去掉「·线路N」这类镜像后缀。
 *
 * 采集站把「一个站的多条线路」命名成 `艾旦资源 · 线路2`，后缀只对站内有序号意义；
 * 详情页按站点分组时，站名后面挂一排编号胶囊，比把长后缀重复印 8 遍清楚得多。
 */
fun siteBaseName(site: String): String {
    val raw = site.trim()
    val cut = raw
        .replace(Regex("[·\\-—\\s]*(线路|line)\\s*[0-9０-９一二三四五六七八九十]+\\s*$", RegexOption.IGNORE_CASE), "")
        .trim()
        .trimEnd('·', '-', '—')
        .trim()
    return cut.ifBlank { raw }
}

private fun sameSource(a: Line, b: Line): Boolean {
    if (a.episodes.size != b.episodes.size) return false
    if (a.episodes.isEmpty()) return false
    // 同一批地址：跨站也会撞（艾旦的第 9 段就是非凡那条）
    if (a.episodes.map { normUrl(it.url) } == b.episodes.map { normUrl(it.url) }) return true
    // 同站同域名的两种地址写法：跳转页 + 直链
    return siteBaseName(a.site) == siteBaseName(b.site) &&
        hostOf(a.episodes[0].url) == hostOf(b.episodes[0].url) &&
        a.quality == b.quality &&
        a.episodes.map { it.name } == b.episodes.map { it.name }
}

/** `/play/x` 与 `/play/x/index.m3u8` 是同一个播放页 */
private fun normUrl(url: String): String = url.trim()
    .trimEnd('/')
    .removeSuffix("/index.m3u8")

private fun hostOf(url: String): String {
    val start = url.indexOf("://").let { if (it >= 0) it + 3 else 0 }
    val end = url.indexOf('/', start)
    return if (end < 0) url.substring(start) else url.substring(start, end)
}

/** 直链比跳转页更适合直接喂播放器 */
private fun directScore(line: Line): Int {
    val url = line.episodes.firstOrNull()?.url.orEmpty()
    return if (url.contains(".m3u8")) 2 else 1
}

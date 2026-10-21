package com.projectionroom.shared.catalog

/**
 * 一行歌词。
 *
 * 时间轴来自 LRC 文本，`timeMs` 是该行开始的时间点。
 */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * LRC 解析。
 *
 * 只做播放器需要的那部分：时间标签 + 文本。
 * `[ti:]` `[ar:]` 这类元数据行没有时间标签，会被自然跳过 ——
 * 之前有实现把「[ti:歌名]」当成歌词，界面上就会冒出一行 [ti:xxx]。
 */
object Lrc {

    /**
     * `[mm:ss]` / `[mm:ss.xx]` / `[mm:ss.xxx]`，允许一行里叠多个标签
     * （`[00:12.00][01:20.00]副歌` 是副歌复用的常见写法）。
     * 冒号做毫秒分隔符的野生格式也兼容（`[00:12:34]`）。
     */
    private val TAG = Regex("\\[(\\d{1,3}):([0-5]?\\d)(?:[.:](\\d{1,3}))?]")

    fun parse(raw: String): List<LrcLine> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<LrcLine>(128)

        for (line in raw.lineSequence()) {
            val tags = TAG.findAll(line).toList()
            if (tags.isEmpty()) continue
            // 文本是「最后一个标签之后」的部分；前面的标签都只是时间点
            val text = line.substring(tags.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (t in tags) {
                val ms = timeOf(t) ?: continue
                out += LrcLine(ms, text)
            }
        }

        // 按时间排；同一时间点只留第一条（有些源会把翻译/原文混在一起）
        out.sortBy { it.timeMs }
        val dedup = ArrayList<LrcLine>(out.size)
        for (l in out) {
            if (dedup.lastOrNull()?.timeMs == l.timeMs) continue
            dedup += l
        }
        return dedup
    }

    private fun timeOf(m: MatchResult): Long? {
        val mm = m.groupValues[1].toLongOrNull() ?: return null
        val ss = m.groupValues[2].toLongOrNull() ?: return null
        val frac = m.groupValues[3]
        // 1 位是「十分之一秒」，2 位百分之一，3 位毫秒
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100L
            2 -> frac.toLong() * 10L
            else -> frac.take(3).toLong()
        }
        return mm * 60_000L + ss * 1_000L + ms
    }

    /** 当前时间对应的行号；还没到第一行返回 -1 */
    fun indexAt(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

package com.projectionroom.ui.util

/**
 * 片源（苹果 CMS 系）的 `vod_content` 是富文本，直接进 Text 会看到
 * `&amp;nbsp;影片讲述了...` 这种实体串 —— 大屏上尤其刺眼。
 *
 * 这里只做展示层的清洗：换行标签转空格、其余标签剥掉、常见实体解码。
 * 不做完整的 HTML 解析（不需要，也不该为了简介引入 jsoup）。
 */
fun String.plainText(): String {
    if (isEmpty()) return this
    var s = this
    // 段落/换行标签先转成空格，避免 "第一段第二段" 粘在一起
    s = s.replace(Regex("(?i)<br\\s*/?>"), " ")
    s = s.replace(Regex("(?i)</p\\s*>"), " ")
    s = s.replace(Regex("<[^>]+>"), "")
    // 苹果 CMS 的简介常常是二次转义的（"&amp;#039;"），单趟解不干净，
    // 解到不再变化为止（最多三轮，够用了）
    var pass = 0
    while (pass < 3) {
        val before = s
        pass++
        s = s.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&hellip;", "…")
            .replace("&mdash;", "—")
            .replace("&middot;", "·")
            .replace("&rsquo;", "’")
            .replace("&amp;", "&")
        // 数字实体：&#039; / &#39; / &#x27;。不解码的话大屏上会原样看到 "&#039;25 全球巡演"
        s = Regex("&#(x?)([0-9a-fA-F]+);").replace(s) { m ->
            val code = if (m.groupValues[1].isEmpty()) {
                m.groupValues[2].toIntOrNull()
            } else {
                m.groupValues[2].toIntOrNull(16)
            }
            code?.takeIf { it in 1..0x10FFFF }
                ?.let { String(Character.toChars(it)) }
                ?: m.value
        }
        if (s == before) break
    }
    return s.replace(Regex("[ \\t\\n\\r]+"), " ").trim()
}

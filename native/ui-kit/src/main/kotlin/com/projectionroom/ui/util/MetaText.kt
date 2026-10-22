package com.projectionroom.ui.util

/**
 * 片名下面那行「年份 · 类型 · 地区 · 语言」。
 *
 * 苹果 CMS 的源在字段缺失时给的是 "0" / "0.0" / ""，直接 join 出来就是
 * 「0 · 电影片」这种半截文案 —— 大屏上片名和副标题是挨着的，一眼就能看见。
 * 所以这里统一把空值和占位零挑掉。
 */
fun metaLine(vararg parts: String, sep: String = " · "): String =
    parts
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "0" && it != "0.0" && it != "无" }
        .joinToString(sep)

package com.projectionroom.ui.screen

/**
 * 「我的」页用的轻量模型。
 *
 * 为什么不直接用 `MusicItem` / `LiveItem`：那两个在 androidMain（要读文件、
 * 要 Context），而 `MineScreen` 在 commonMain —— 桌面预览也要能渲染这一页。
 * 所以壳负责把本地库映射成这两个类型再传进来。
 */
data class MineChannel(
    val name: String,
    val group: String = "",
    val urls: List<String> = emptyList(),
)

data class MineTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val cover: String = "",
)

/**
 * 「我的歌单」里的一张卡：自建的、或者收藏来的在线歌单。
 *
 * [online] 决定点开之后去哪：自建歌单在本地就有曲目，直接进本地列表页；
 * 在线歌单要重新拉一次详情（`Screen.Playlist`）。
 */
data class MineShelf(
    val id: String,
    val name: String,
    val cover: String = "",
    val count: Int = 0,
    val online: Boolean = false,
)

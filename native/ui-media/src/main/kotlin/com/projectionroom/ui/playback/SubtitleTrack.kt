package com.projectionroom.ui.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/**
 * 一条可切换的轨道（内嵌字幕或音轨）。
 *
 * [group] 来自当前 `Tracks` 快照，切轨的 override 必须引用它，
 * 所以每次选完都重新枚举，不要久缓存（自适应码流会换组）。
 */
data class TrackOption(
    val type: Int,
    val group: Tracks.Group,
    val index: Int,
    val label: String,
    val selected: Boolean,
)

/**
 * 内嵌字幕 / 音轨的枚举与切换。纯逻辑、不碰 Compose，面板与单测都能直接用。
 *
 * 播放器其实早就把内嵌轨解出来了（`DefaultRenderersFactory` 自带 TextRenderer），
 * 缺的只是一个能看见它们的入口。
 */
object TrackPicker {

    fun textTracks(player: Player): List<TrackOption> = collect(player, C.TRACK_TYPE_TEXT)

    fun audioTracks(player: Player): List<TrackOption> = collect(player, C.TRACK_TYPE_AUDIO)

    /** 关闭字幕：必须同时清 override，否则下次放开时旧 override 会让它自己回来 */
    fun disableText(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /** 选一条内嵌字幕；null 等价于「关闭」 */
    fun selectText(player: Player, option: TrackOption?) {
        if (option == null) {
            disableText(player)
            return
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .addOverride(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
            .build()
    }

    /**
     * 选音轨；null = 交回自动。
     *
     * 音轨不做 `setTrackTypeDisabled`：关掉音轨只会让人以为片子没声音。
     */
    fun selectAudio(player: Player, option: TrackOption?) {
        val b = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        if (option != null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .addOverride(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
        }
        player.trackSelectionParameters = b.build()
    }

    /** 自动挑一条字幕：优先中文，其次第一条 */
    fun preferredText(tracks: List<TrackOption>): TrackOption? =
        tracks.firstOrNull { looksChinese(it.label) } ?: tracks.firstOrNull()

    /** 外挂字幕的 MIME；null 表示扩展名不支持 */
    fun subtitleMime(nameOrUrl: String): String? {
        val n = nameOrUrl.substringBefore('?').lowercase()
        return when {
            n.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            n.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            n.endsWith(".ass") || n.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            n.endsWith(".ttml") || n.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> null
        }
    }

    /** 外挂字幕配置；`SELECTION_FLAG_DEFAULT` 让它挂上即生效 */
    fun sideSubtitle(uri: Uri, mime: String, label: String): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLabel(label.ifBlank { "外挂字幕" })
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

    private fun collect(player: Player, type: Int): List<TrackOption> {
        val out = mutableListOf<TrackOption>()
        player.currentTracks.groups.forEach { g ->
            if (g.type != type || !g.isSupported) return@forEach
            for (i in 0 until g.length) {
                out += TrackOption(
                    type = type,
                    group = g,
                    index = i,
                    label = labelOf(g.getTrackFormat(i), type, out.size),
                    selected = g.isTrackSelected(i),
                )
            }
        }
        return out
    }

    private fun labelOf(f: Format, type: Int, ordinal: Int): String {
        val name = f.label?.takeIf { it.isNotBlank() }
        if (name != null) return name
        val lang = languageName(f.language)
        if (lang != null) return lang
        return if (type == C.TRACK_TYPE_AUDIO) "音轨 ${ordinal + 1}" else "字幕 ${ordinal + 1}"
    }

    private fun languageName(code: String?): String? = when (code?.lowercase()) {
        null, "", "und" -> null
        "zh", "chi", "zho", "cmn", "zh-cn", "zh-hans" -> "中文"
        "zh-tw", "zh-hant" -> "繁体中文"
        "en", "eng" -> "英语"
        "ja", "jpn" -> "日语"
        "ko", "kor" -> "韩语"
        else -> code.uppercase()
    }

    private fun looksChinese(label: String): Boolean {
        val l = label.lowercase()
        return l.contains("中") || l.contains("简") || l.contains("繁") ||
            l.contains("zh") || l.contains("chs") || l.contains("cht") || l.contains("chi")
    }
}

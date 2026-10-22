package com.projectionroom.ui.playback

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.projectionroom.ui.screen.DecoderMode

/**
 * 播放器的构造入口。
 *
 * 原来播放页里是一行 `ExoPlayer.Builder(context).build()`，解码器完全交给系统：
 * 一律硬解优先，硬解花屏 / 黑屏时没有任何退路，也看不出到底用的哪颗解码器。
 * 这里按 [DecoderMode] 造两个东西：
 *
 *  · [ModeCodecSelector] —— 决定候选解码器列表（硬解 / 软解 / 全部）
 *  · decoderFallback —— 自动档下让 ExoPlayer 在硬解初始化失败时自己往下试
 *
 * 注意「软解」这一档走的是系统自带的 `c2.android.*` / `OMX.google.*` 软件解码器，
 * 不需要额外 so：1080p 勉强够用，4K HEVC 会明显掉帧 —— 真正要流畅 4K 软解只能
 * 上 ffmpeg/IJK 那种带 NDK 库的内核，那是另一件事（见 README 的播放内核说明）。
 */
object PlaybackEngine {

    fun build(context: Context, mode: DecoderMode): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            // 硬解档位下不允许回退：选了「硬解」就是要验证硬解能不能跑，
            // 悄悄回落到软件解码会让人以为硬解生效了，反而更难排查
            .setEnableDecoderFallback(mode != DecoderMode.Hardware)
            .setMediaCodecSelector(ModeCodecSelector(mode))
        return ExoPlayer.Builder(context, renderers).build()
    }
}

/**
 * 按档位过滤候选解码器。
 *
 * 只对视频生效：音频总共就几百 kbps，强制走软件 AAC 没有任何收益，
 * 只会多占一点 CPU。
 */
private class ModeCodecSelector(
    private val mode: DecoderMode,
) : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val all = MediaCodecSelector.DEFAULT
            .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (mode == DecoderMode.Auto || !mimeType.startsWith("video/")) return all
        // 过滤后为空说明这台设备只有一种解码器：宁可照原来的播，也别把片子直接判死
        return when (mode) {
            DecoderMode.Hardware -> all.filter { it.hardwareAccelerated }.ifEmpty { all }
            DecoderMode.Software -> all.filter { !it.hardwareAccelerated }.ifEmpty { all }
            DecoderMode.Auto -> all
        }
    }
}

/** 「播放信息」面板要显示的一屏数字 */
data class MediaStats(
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    /** 视频轨标称码率，bps；HLS 分段偶尔给 -1 */
    val bitrate: Int = 0,
    val codecs: String = "",
    val decoderName: String = "",
    /** true = 硬件解码器；null = 系统太老问不出来 */
    val hardware: Boolean? = null,
    val dropped: Int = 0,
    val rendered: Int = 0,
    /** 当前位置往前还有多少已缓冲的时长 */
    val bufferMs: Long = 0L,
    /** 本次播放累计下载字节 */
    val loadedBytes: Long = 0L,
    /** 最近一秒的下载速度，B/s */
    val speedBps: Long = 0L,
    /** ExoPlayer 的带宽估计，bps */
    val bandwidthBps: Long = 0L,
)

/**
 * 收集排查卡顿要看的那些数。
 *
 * 分两部分：事件型（解码器初始化、码流参数变化、下载完成）由 [AnalyticsListener]
 * 推过来；时变型（已缓冲时长、丢帧、瞬时速度）靠外部每秒调一次 [tick] 采样 ——
 * 下载字节只能靠累计值做差分，事件本身没有「此刻多少」的语义。
 */
class MediaStatsTracker(
    private val onUpdate: (MediaStats) -> Unit,
) : AnalyticsListener {

    private var stats = MediaStats()
    private var totalBytes = 0L
    private var lastBytes = 0L
    private var lastAtMs = 0L

    override fun onLoadCompleted(
        eventTime: EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        totalBytes += loadEventInfo.bytesLoaded
        val f = mediaLoadData.trackFormat
        stats = if (f == null) {
            stats.copy(loadedBytes = totalBytes)
        } else {
            stats.copy(
                loadedBytes = totalBytes,
                bitrate = if (f.bitrate > 0) f.bitrate else stats.bitrate,
                width = if (f.width > 0) f.width else stats.width,
                height = if (f.height > 0) f.height else stats.height,
                fps = if (f.frameRate > 0) f.frameRate else stats.fps,
            )
        }
        onUpdate(stats)
    }

    override fun onBandwidthEstimate(
        eventTime: EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        stats = stats.copy(bandwidthBps = bitrateEstimate)
        onUpdate(stats)
    }

    override fun onVideoDecoderInitialized(
        eventTime: EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        stats = stats.copy(decoderName = decoderName, hardware = isHardwareDecoder(decoderName))
        onUpdate(stats)
    }

    override fun onVideoInputFormatChanged(
        eventTime: EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        stats = stats.copy(
            width = if (format.width > 0) format.width else stats.width,
            height = if (format.height > 0) format.height else stats.height,
            fps = if (format.frameRate > 0) format.frameRate else stats.fps,
            bitrate = if (format.bitrate > 0) format.bitrate else stats.bitrate,
            codecs = format.codecs ?: format.sampleMimeType.orEmpty(),
        )
        onUpdate(stats)
    }

    /** 每秒调一次：算瞬时下载速度，顺便刷新时长类指标 */
    fun tick(player: Player) {
        val now = SystemClock.elapsedRealtime()
        val dt = now - lastAtMs
        if (lastAtMs > 0 && dt > 0) {
            val delta = (totalBytes - lastBytes).coerceAtLeast(0L)
            stats = stats.copy(speedBps = delta * 1000L / dt)
        }
        lastAtMs = now
        lastBytes = totalBytes

        val counters = (player as? ExoPlayer)?.videoDecoderCounters
        stats = stats.copy(
            bufferMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L),
            loadedBytes = totalBytes,
            dropped = counters?.droppedBufferCount ?: stats.dropped,
            rendered = counters?.renderedOutputBufferCount ?: stats.rendered,
        )
        onUpdate(stats)
    }
}

/**
 * 这颗解码器是不是硬解。
 *
 * API 29 之后系统能直接回答；更老的机器只能按名字猜（高通的 OMX.qcom、
 * 三星的 OMX.Exynos 是硬解，google/android 前缀是 AOSP 软解）。
 */
private fun isHardwareDecoder(name: String): Boolean? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .firstOrNull { it.name == name }
            ?.isHardwareAccelerated
    } else {
        val n = name.lowercase()
        when {
            n.startsWith("omx.google") || n.startsWith("c2.android") -> false
            n.startsWith("omx.qcom") || n.startsWith("omx.exynos") ||
                n.startsWith("omx.mtk") || n.startsWith("c2.qti") ||
                n.startsWith("c2.exynos") || n.startsWith("c2.mtk") -> true
            else -> null
        }
    }
}.getOrNull()

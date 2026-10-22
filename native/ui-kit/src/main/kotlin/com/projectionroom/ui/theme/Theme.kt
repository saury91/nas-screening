package com.projectionroom.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 设计 token 全部对着现有 Web 版的 CSS 变量抄的，一个值都不是拍脑袋定的：
 * 换 UI 实现，不换视觉语言。
 */
@Immutable
object ProomColor {
    val bg = Color(0xFF07080B)          // --bg
    val bgSoft = Color(0xFF0C0E13)      // --bg-soft
    val card = Color(0xFF12141A)        // .card 底色（海报没加载出来时的垫背）

    // 三层 surface：靠明度分层，不靠描边
    val surface = Color(0x0EFFFFFF)     // rgba(255,255,255,.055)
    val surface2 = Color(0x17FFFFFF)    // rgba(255,255,255,.09)
    val line = Color(0x16FFFFFF)        // rgba(255,255,255,.085)
    val lineStrong = Color(0x29FFFFFF)  // rgba(255,255,255,.16)

    val text = Color(0xFFF4F5F7)
    val dim = Color(0xFFB3B8C3)
    val muted = Color(0xFF8A919E)

    val accent = Color(0xFFFFCF7A)
    val accent2 = Color(0xFFFF9F5A)
    val accentSoft = Color(0x24FFCF7A)  // rgba(255,207,122,.14)

    /**
     * 投屏通道的冷色强调。
     *
     * 暖金留给「内容」——封面、评分、收藏；播放控制属于「设备」，用青色。
     * 手机遥控页和电视控制条共用这一组值，同一个动作的两个入口才不会像
     * 两个产品；同时也把遥控器从整屏的暖金里拎出来。
     */
    val cast = Color(0xFF33C1DA)
    val castSoft = Color(0xFF96EEF9)
    val castInk = Color(0xFF06222A)
    val castGlow = Color(0x5733C1DA)    // rgba(51,193,218,.34)

    val danger = Color(0xFFFF6B6B)
    val ok = Color(0xFF7EE2A8)
}

/**
 * 字阶。Web 版用的是 Inter + Noto Sans SC，移动端改用系统字体：
 * 系统字在原生渲染下笔画更实，也省掉几 MB 字体文件。
 *
 * **电视上这些属性会自动换成 [TvType] 的那一套**（见 [ProomRoot] 提供的 LocalProomTv）：
 * 调用点统一写 `ProomType.body`，手机拿 14sp、电视拿 18sp，页面代码里不再出现
 * `if (isTv) 18.sp else 14.sp` 这种各写各的分支 —— 之前就是那么写的，
 * 结果是 12 个电视页面出现了 6 种正文字号、3 种边距。
 *
 * 之所以做成 @Composable getter：字阶是随主题走的，和字号有关的一切都应该只有
 * 一个出口；代价是它不能在非 composable 上下文（remember 计算块、顶层常量）里读。
 */
@Immutable
object ProomType {
    private val phoneDisplay = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, lineHeight = 46.sp)
    private val phoneHeadline = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
    private val phoneTitle = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
    private val phoneBody = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    private val phoneCardTitle = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp)
    private val phoneMeta = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp)
    private val phoneLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp)
    private val phoneEyebrow = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.4.sp)

    /** 全屏页主标题 / Hero 大标题 */
    val display: TextStyle @Composable @ReadOnlyComposable get() = if (LocalProomTv.current) TvType.display else phoneDisplay

    /** 页面标题、区块标题 */
    val headline: TextStyle @Composable @ReadOnlyComposable get() = if (LocalProomTv.current) TvType.headline else phoneHeadline

    /** 次级标题 */
    val title: TextStyle @Composable @ReadOnlyComposable get() = if (LocalProomTv.current) TvType.title else phoneTitle

    /** 正文：简介、说明、表单值 */
    val body: TextStyle @Composable @ReadOnlyComposable get() = if (LocalProomTv.current) TvType.body else phoneBody

    /** 卡片标题（重量级比正文轻一档） */
    val cardTitle: TextStyle @Composable @ReadOnlyComposable get() = if (LocalProomTv.current) TvType.cardTitle else phoneCardTitle

    /** 次要信息：年份 / 时长 / 副标题 */
    val meta: TextStyle @Composable @ReadOnlyComposable get() = if (LocalProomTv.current) TvType.meta else phoneMeta

    /** 最弱一级：序号、角标、时间码 */
    val label: TextStyle @Composable @ReadOnlyComposable get() = if (LocalProomTv.current) TvType.label else phoneLabel

    /** 全大写小标（“歌单”“正在播放”那类 eyebrow） */
    val eyebrow: TextStyle @Composable @ReadOnlyComposable get() = if (LocalProomTv.current) TvType.eyebrow else phoneEyebrow
}

/**
 * 电视字阶（10-foot UI）。
 *
 * 画布按 960×540dp 算（density 2 + 1080p，Android TV 的基准分辨率），视距 3 米。
 * 依据（Google/Apple TV 设计与可读性经验值）：正文不小于画布宽度的 2%（≈18sp），
 * 标题 3%（≈30sp），最小一级（序号/时间码）不低于 1.35%（≈13sp）。
 *
 * 层级固定成 1.35 左右的等比阶梯，页面里**不允许**再出现裸 sp：
 * 40 主标题 / 30 页面标题 / 24 次级标题 / 18 正文 / 17 卡片标题 / 15 次要信息 / 13 最弱一级。
 */
object TvType {
    val display = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, lineHeight = 48.sp)
    val headline = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 38.sp)
    val title = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 31.sp)
    val body = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, lineHeight = 26.sp)
    val cardTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp)
    val meta = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    val label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
    val eyebrow = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
}

/**
 * 电视安全区。55~100 寸的屏普遍有 2%~5% 过扫描，内容压到边会被切掉：
 * 960×540dp 画布下左右留 48dp（5%）、上下留 27dp（5%）。
 */
object TvLayout {
    val insetX = 48.dp
    val insetY = 27.dp

    /** 遥控器可点目标下限。10-foot UI 里所有能聚焦的东西都不该小于这个尺寸 */
    val minTouch = 48.dp
}

/**
 * 逐点平移的字号，专给视频播放器控制层这类「必须按像素微调」的地方。
 *
 * 它不做等比缩放（11sp×1.4=15sp 仍然偏小，34sp×1.4=48sp 又没必要），
 * 而是把手机的档位钳到电视的可读下限：≤11.5→14、≤13.5→16、≤15.5→18、
 * ≤17.5→20、≤22→24，再大的（配对码那种）按 1.35 倍放。
 */
@Composable
@ReadOnlyComposable
fun ProomSp(phone: TextUnit): TextUnit {
    if (!LocalProomTv.current) return phone
    val v = phone.value
    return when {
        v <= 11.5f -> 14.sp
        v <= 13.5f -> 16.sp
        v <= 15.5f -> 18.sp
        v <= 17.5f -> 20.sp
        v <= 22f -> 24.sp
        else -> (v * 1.35f).sp
    }
}

@Immutable
object ProomShape {
    val lg = 22.dp      // --r-lg
    val md = 14.dp      // --r-md
    val sm = 10.dp      // --r-sm
}

object ProomMotion {
    /** 和 Web 版同一条曲线：起步快、收尾软，观感才是「同一种动效」。 */
    val ease = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    const val short = 180
    const val mid = 320

    /** TV 焦点放大倍数：1.045 是 Web 版卡片 hover 的数值，遥控器上要再大一点才看得清。 */
    const val focusScaleTv = 1.06f
    const val focusScalePhone = 1.00f
}

/** 间距不用枚举，直接用 4dp 的倍数；这里只放常用档位，避免到处 magic number。 */
object ProomSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 14.dp   // .rail gap
    val lg = 20.dp
    val xl = 26.dp   // .rail padding
    val xxl = 40.dp
}

val LocalProomTv = staticCompositionLocalOf { false }

private val scheme = darkColorScheme(
    primary = ProomColor.accent,
    onPrimary = Color(0xFF1B1206),
    background = ProomColor.bg,
    onBackground = ProomColor.text,
    surface = ProomColor.card,
    onSurface = ProomColor.text,
    surfaceVariant = ProomColor.surface,
    onSurfaceVariant = ProomColor.dim,
    outline = ProomColor.line,
    error = ProomColor.danger,
)

@Composable
fun ProomTheme(isTv: Boolean = false, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalProomTv provides isTv) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
    }
}


/**
 * 整体 UI 缩放（只对电视生效）。
 *
 * Android 电视的「逻辑画布」差异很大：1080p 和 4K 通常都是 960×540dp（density 翻倍），
 * 但有些盒子把 4K 屏的 density 设成 320，逻辑尺寸就变成 1920×1080dp ——
 * 这时按 dp 排的 UI 会显得又小又散。
 *
 * 做法是按 960×540dp 为基准整体缩 density：大画布自动放大成「等效 960dp」，
 * 布局比例不变，字号也跟着变大（Density 的 density 一经改变，sp 一起放大）。
 *
 * 手机绝对不能套这一层：手机的逻辑宽度只有 390~430dp，
 * 任何 scale 都会把版式算成平板宽度、字变小，而且横竖屏切换时整页比例会跳。
 */
@Composable
fun ProomRoot(isTv: Boolean = true, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(ProomColor.bg)
    ) {
        val d = LocalDensity.current
        if (!isTv) {
            ProomTheme(isTv = false, content = content)
            return@BoxWithConstraints
        }
        // 下界放到 0.7：有些盒子/模拟器把 density 调歪，逻辑画布比 960×540 还窄，
        // 只允许放大就会让 Hero 这类「按高度排满」的版式溢出被裁掉。
        val scale = minOf(maxWidth.value / 960f, maxHeight.value / 540f).coerceIn(0.7f, 2f)
        androidx.compose.runtime.CompositionLocalProvider(
            LocalDensity provides Density(d.density * scale, d.fontScale)
        ) {
            ProomTheme(isTv = true, content = content)
        }
    }
}

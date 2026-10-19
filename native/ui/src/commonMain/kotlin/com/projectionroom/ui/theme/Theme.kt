package com.projectionroom.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

    val danger = Color(0xFFFF6B6B)
    val ok = Color(0xFF7EE2A8)
}

/**
 * 字阶。Web 版用的是 Inter + Noto Sans SC，移动端改用系统字体：
 * 系统字在原生渲染下笔画更实，也省掉几 MB 字体文件。
 */
@Immutable
object ProomType {
    val display = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, lineHeight = 46.sp)
    val headline = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
    val title = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
    val body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp)
    val cardTitle = TextStyle(fontSize = 13.5.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp)
    val meta = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 17.sp)
    val label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp)
    val eyebrow = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.4.sp)

    val font: FontFamily = FontFamily.Default
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

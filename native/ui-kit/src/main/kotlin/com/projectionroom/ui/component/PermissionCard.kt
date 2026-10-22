package com.projectionroom.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 权限说明卡：在系统弹窗之前，先用自己的 UI 把「为什么」讲清楚。
 *
 * 为什么需要这一层：Android 的运行时权限确认框是 `com.android.permissioncontroller`
 * 画的系统窗口，**任何三方库、任何主题都改不了它的样式**。能做的只有两件事：
 *
 * 1. 先给一张和 App 风格一致的卡（就是这个），用户读完点「允许」再调起系统框；
 * 2. 用户点过拒绝、系统不再弹框之后，同一张卡改成「去系统设置」，别让用户卡死。
 *
 * 这么做的另一个好处是时机：通知、相机都不必在冷启动第一秒就弹，等用户真的要用了再说。
 *
 * @param primary 主按钮文案，如「允许使用相机」「去系统设置」
 * @param note 主按钮上方的补充说明，用于「已被拒绝」这类状态
 */
@Composable
fun PermissionCard(
    title: String,
    message: String,
    primary: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (Color) -> Unit)? = null,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
    note: String? = null,
) {
    Column(
        modifier
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(ProomShape.lg))
            .background(ProomColor.card)
            .border(1.dp, ProomColor.line, RoundedCornerShape(ProomShape.lg))
            .padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(ProomColor.accentSoft),
                contentAlignment = Alignment.Center,
            ) { icon(ProomColor.accent) }
            Spacer(Modifier.height(ProomSpace.md))
        }
        Text(
            title,
            style = ProomType.title,
            color = ProomColor.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(ProomSpace.sm))
        Text(
            message,
            style = ProomType.body,
            color = ProomColor.dim,
            textAlign = TextAlign.Center,
        )
        if (note != null) {
            Spacer(Modifier.height(ProomSpace.sm))
            Text(
                note,
                style = ProomType.meta,
                color = ProomColor.muted,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(ProomSpace.lg))
        // 电视上靠遥控器选，必须给出焦点指示：只靠系统默认的高亮层
        // 在深色卡上几乎看不见，用户不知道自己停在哪一行
        var primaryFocused by remember { mutableStateOf(false) }
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(ProomShape.md))
                .background(ProomColor.accent)
                .then(
                    if (primaryFocused) {
                        Modifier.border(2.dp, Color.White, RoundedCornerShape(ProomShape.md))
                    } else {
                        Modifier
                    }
                )
                .onFocusChanged { primaryFocused = it.isFocused }
                .clickable(onClick = onPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                primary,
                color = Color(0xFF1B1206),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (secondary != null && onSecondary != null) {
            Spacer(Modifier.height(ProomSpace.xs))
            var secondaryFocused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(ProomShape.md))
                    .background(
                        if (secondaryFocused) Color.White.copy(alpha = 0.10f) else Color.Transparent
                    )
                    .onFocusChanged { secondaryFocused = it.isFocused }
                    .clickable(onClick = onSecondary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    secondary,
                    color = if (secondaryFocused) ProomColor.text else ProomColor.dim,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** 相机：机身 + 顶部取景凸起 + 镜头。和项目里其他图标一样是手绘，不引图标库。 */
@Composable
fun CameraGlyph(tint: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.075f
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.06f, w * 0.28f),
            size = Size(w * 0.88f, w * 0.48f),
            cornerRadius = CornerRadius(w * 0.13f, w * 0.13f),
            style = Stroke(sw),
        )
        drawLine(tint, Offset(w * 0.33f, w * 0.28f), Offset(w * 0.38f, w * 0.17f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.38f, w * 0.17f), Offset(w * 0.62f, w * 0.17f), sw, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.62f, w * 0.17f), Offset(w * 0.67f, w * 0.28f), sw, StrokeCap.Round)
        drawCircle(
            tint,
            radius = w * 0.14f,
            center = Offset(w * 0.5f, w * 0.52f),
            style = Stroke(sw),
        )
    }
}

/** 通知：钟身 + 底沿 + 下方一颗挂点。 */
@Composable
fun BellGlyph(tint: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.08f
        drawCircle(tint, radius = w * 0.055f, center = Offset(w * 0.5f, w * 0.14f))
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.22f, w * 0.2f),
            size = Size(w * 0.56f, w * 0.46f),
            cornerRadius = CornerRadius(w * 0.22f, w * 0.22f),
            style = Stroke(sw),
        )
        drawLine(tint, Offset(w * 0.12f, w * 0.68f), Offset(w * 0.88f, w * 0.68f), sw, StrokeCap.Round)
        drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.5f, w * 0.83f))
    }
}

package com.projectionroom.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import androidx.compose.runtime.saveable.rememberSaveable
import com.projectionroom.ui.component.CameraGlyph
import com.projectionroom.ui.component.PermissionCard
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import java.util.concurrent.Executors

/**
 * 手机端：扫电视上的配对二维码。
 *
 * 相机预览用 CameraX（比自己封 Camera2 少一半代码），解码头用 zxing 的
 * `PlanarYUVLuminanceSource` 直接吃 YUV 帧，不用先转 Bitmap —— 每帧转图会掉到个位数 FPS。
 *
 * 扫码失败要有出路：电视就在旁边，手动输 IP + 四位配对码也该能用，
 * 所以底部留了「手动输入」。
 */
@Composable
fun ScanPairScreen(
    /** 扫到的原始文本（proom://pair?... / proom://cast?...），由壳解析 */
    onScanned: (String) -> Unit,
    /** 手动输入：地址（ip:port）+ 配对码 */
    onManual: (String, String) -> Unit,
    onBack: () -> Unit,
    /**
     * 投屏码模式：扫的是「电视在哪儿」而不是「配置包」。
     *
     * 同一台相机、同一套解码，只是少了「四位配对码」那个字段 ——
     * 投屏只认地址，多问一个数字纯属拦路。
     */
    castMode: Boolean = false,
    /** 投屏码手动输入：只有地址 */
    onManualCast: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // 系统权限框是 permissioncontroller 画的，样式改不了；能改的是「什么时候弹、弹之前说什么」。
    // 所以进页面不再直接拉系统框，先给一张自己的说明卡，用户点了「允许」再走系统框
    var refused by rememberSaveable { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        hasCamera = ok
        // 回调为 false 有两种：用户点了拒绝，或系统已不再弹框（永久拒绝）
        refused = !ok
    }
    // 系统不再弹框之后（永久拒绝 / 连拒两次），只能去设置里打开
    val activity = remember(ctx) { ctx.findActivity() }
    val blocked = !hasCamera && refused &&
        activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false

    // 从系统设置返回时必须重读权限：用户可能刚把相机打开，
    // 不重读的话卡片会一直挂在屏幕上
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasCamera = ContextCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }

    var manual by remember { mutableStateOf(false) }
    var handled by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            CameraPreview(
                onDecoded = { text ->
                    // 一帧一帧地回调，不加锁会连续触发好几次
                    if (!handled) {
                        handled = true
                        onScanned(text)
                    }
                },
            )
        }

        // 取景框：四个角就够了，整圈描边会挡住二维码
        if (hasCamera) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(240.dp)
                    .border(2.dp, ProomColor.accent.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TabIcons.Back, "返回", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Text(
                if (castMode) "扫电视上的投屏码" else "扫电视上的二维码",
                color = Color.White,
                fontSize = 17.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        // 没授权时不画取景提示和底部小按钮：说明卡自己带着「允许」和「手动输入」，
        // 两套入口并排只会让人不知道该点哪个
        if (hasCamera) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (castMode) "电视：设置 → 显示配对二维码" else "把电视屏幕上的二维码放进取景框",
                    style = ProomType.meta,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(ProomSpace.md))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable { manual = true }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (castMode) "手动输入地址" else "手动输入",
                        style = ProomType.cardTitle,
                        color = Color.White,
                    )
                }
            }
        } else {
            PermissionCard(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                title = "扫码要用相机",
                message = "只在你扫电视上的二维码时打开相机，画面不会保存，也不会上传。",
                icon = { tint -> CameraGlyph(tint, 26.dp) },
                primary = if (blocked) "去系统设置打开" else "允许使用相机",
                note = if (blocked) "相机权限已被拒绝，系统不会再弹窗了" else null,
                onPrimary = {
                    if (blocked) {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", ctx.packageName, null),
                                )
                            )
                        }
                    } else {
                        askCamera.launch(Manifest.permission.CAMERA)
                    }
                },
                secondary = if (castMode) "手动输入地址" else "手动输入",
                onSecondary = { manual = true },
            )
        }

        if (manual) {
            ManualDialog(
                castMode = castMode,
                onDismiss = { manual = false },
                onConfirm = { addr, code ->
                    manual = false
                    onManual(addr, code)
                },
                onConfirmCast = { addr ->
                    manual = false
                    onManualCast(addr)
                },
            )
        }
    }
}

/* ───────────────────────── 相机 ───────────────────────── */

@Composable
private fun CameraPreview(onDecoded: (String) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var view by remember { mutableStateOf<PreviewView?>(null) }

    DisposableEffect(Unit) {
        onDispose { runCatching { executor.shutdown() } }
    }

    AndroidView(
        factory = { c ->
            PreviewView(c).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                view = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )

    val pv = view
    LaunchedEffect(pv) {
        val previewView = pv ?: return@LaunchedEffect
        val provider = runCatching {
            ProcessCameraProvider.getInstance(ctx).let { f ->
                // get() 会阻塞，放在主线程会卡首帧；这里等它回调
                val latch = java.util.concurrent.CountDownLatch(1)
                var p: ProcessCameraProvider? = null
                f.addListener({
                    p = runCatching { f.get() }.getOrNull()
                    latch.countDown()
                }, ContextCompat.getMainExecutor(ctx))
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                p
            }
        }.getOrNull() ?: return@LaunchedEffect

        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor, QrAnalyzer(onDecoded))

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
            )
        }
    }
}

/**
 * 帧分析：每 400ms 解一次。
 *
 * 每帧都解会把 CPU 吃满（1080p 一帧亮度图 2MB），而人把二维码对准取景框
 * 至少要停留半秒，400ms 一次完全够。
 */
private class QrAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private var lastAt = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAt < 400) return
            lastAt = now
            val media = image.image ?: return
            val plane = media.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            // dataWidth 用 rowStride：有的机型每行末尾有对齐填充，按 width 读会歪
            val source = PlanarYUVLuminanceSource(
                data, plane.rowStride, media.height, 0, 0, media.width, media.height, false,
            )
            val rotated = when (image.imageInfo.rotationDegrees) {
                90 -> source.rotateCounterClockwise()
                180 -> source.rotateCounterClockwise().rotateCounterClockwise()
                270 -> source.rotateCounterClockwise()
                    .rotateCounterClockwise()
                    .rotateCounterClockwise()
                else -> source
            }
            val text = runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(rotated))).text
            }.getOrNull()
            if (!text.isNullOrBlank()) onFound(text)
        } finally {
            image.close()
        }
    }
}

/* ───────────────────────── 手动输入 ───────────────────────── */

@Composable
private fun ManualDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
    castMode: Boolean = false,
    onConfirmCast: (String) -> Unit = {},
) {
    var addr by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ProomShape.lg))
                .background(ProomColor.bgSoft)
                .padding(18.dp),
        ) {
            Text(
                if (castMode) "手动输入电视地址" else "手动输入",
                style = ProomType.title,
                color = ProomColor.text,
            )
            Text(
                if (castMode) "电视屏幕上的那行地址，端口不写就是 49521"
                else "电视屏幕上显示着地址和四位配对码",
                style = ProomType.label,
                color = ProomColor.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Field(if (castMode) "192.0.2.20:49521" else "192.0.2.20:8898", addr) { addr = it }
            if (!castMode) {
                Spacer(Modifier.height(10.dp))
                Field("配对码 4 位数字", code) {
                    if (it.length <= 4 && it.all { c -> c.isDigit() }) code = it
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                ActionText("取消", ProomColor.muted, onDismiss)
                Spacer(Modifier.width(8.dp))
                val ready = if (castMode) addr.isNotBlank() else addr.isNotBlank() && code.length == 4
                ActionText("连接", if (ready) ProomColor.accent else ProomColor.muted) {
                    if (!ready) return@ActionText
                    if (castMode) onConfirmCast(addr.trim()) else onConfirm(addr.trim(), code)
                }
            }
        }
    }
}

@Composable
private fun Field(hint: String, value: String, onValue: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
        textStyle = TextStyle(fontSize = 14.sp, color = ProomColor.text),
        cursorBrush = SolidColor(ProomColor.accent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProomShape.sm))
            .background(ProomColor.surface)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(hint, style = ProomType.meta, color = ProomColor.muted)
                }
                inner()
            }
        },
    )
}

@Composable
private fun ActionText(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        style = ProomType.cardTitle,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(ProomShape.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 从 Compose 的 Context 里挖出 Activity：判断「系统还会不会弹权限框」需要它 */
private fun Context.findActivity(): android.app.Activity? {
    var c: Context = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

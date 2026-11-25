package clauderc.app.pair

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import clauderc.app.ui.components.LocalMorphClose
import clauderc.app.ui.components.MorphOverlay
import clauderc.app.ui.components.PillButton
import clauderc.app.ui.components.PillStyle
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.MonoFamily
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DecoderFactory
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.delay

/** The scan page from the mockup: black, a 240dp viewfinder, and a sheet for every failure. */
@Composable
fun PairScreen(
    state: PairUiState,
    onScanned: (String) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit = {},
    scanner: @Composable (Modifier, (String) -> Unit) -> Unit = { m, cb -> QrScanner(m, cb) },
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val failed = state !is PairUiState.Scanning && state !is PairUiState.Pairing && state !is PairUiState.Paired && state !is PairUiState.Known
    // The camera opens once the page has slid in: opening it during the slide made the page stutter.
    var cameraReady by remember { mutableStateOf(false) }
    val cameraDelay = Clauderc.motion.durM.toLong()
    LaunchedEffect(Unit) {
        delay(cameraDelay)
        cameraReady = true
    }
    Box(Modifier.fillMaxSize().background(c.camera)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(start = sp.page, end = sp.page, top = sp.sp8, bottom = sp.sp6),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "扫描配对二维码",
                modifier = Modifier.padding(top = sp.sp4),
                color = c.onCamera,
                fontSize = Clauderc.fontSizes.headline,
                fontWeight = FontWeight.Light,
                letterSpacing = (-0.01).em,
            )
            Viewfinder(Modifier.padding(top = sp.sp6 + sp.sp6), scanning = state is PairUiState.Scanning) { m ->
                if (cameraReady && (state is PairUiState.Scanning || state is PairUiState.Pairing)) scanner(m, onScanned)
            }
            Row(
                Modifier.padding(top = sp.sp6 + sp.sp6).alpha(0.5f),
                horizontalArrangement = Arrangement.spacedBy(sp.sp2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    PairUiState.Pairing, PairUiState.Paired, is PairUiState.Known -> {
                        Spinner(size = 12.dp, color = c.onCamera)
                        Text("正在配对", color = c.onCamera, fontSize = Clauderc.fontSizes.caption)
                    }
                    PairUiState.Scanning -> Text("二维码 5 分钟内有效，只能用一次", color = c.onCamera, fontSize = Clauderc.fontSizes.caption)
                    else -> Unit
                }
            }
        }
        SymbolButton(
            Symbols.ArrowBack,
            "返回",
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(start = sp.sp1, top = sp.sp1),
            tint = c.onCamera,
        )
        AnimatedVisibility(
            visible = failed,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = Motion.panelIn,
            exit = Motion.panelOut,
        ) {
            FailureSheet(state, onRescan)
        }
    }
}

/**
 * 浮墨's morphing floating QR scanner card (demo.html `A.openPair` for Ink):
 * blooms directly out of the tapped scan orb / settings row and hosts the real ZXing camera viewfinder
 * + inline failure/retry state inside a floating 30dp ink card.
 */
@Composable
fun PairMorphPanel(
    state: PairUiState,
    onScanned: (String) -> Unit,
    onRescan: () -> Unit,
    onDismiss: () -> Unit,
    anchor: Rect? = null,
    fromTop: Boolean = false,
    scanner: @Composable (Modifier, (String) -> Unit) -> Unit = { m, cb -> QrScanner(m, cb) },
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val failed = state !is PairUiState.Scanning && state !is PairUiState.Pairing && state !is PairUiState.Paired && state !is PairUiState.Known
    var cameraReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(320)
        cameraReady = true
    }
    MorphOverlay(onDismiss = onDismiss, anchor = anchor, fromTop = fromTop, tag = "inkPairMorph") {
        val close = LocalMorphClose.current
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("扫描配对二维码", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.fg)
                SymbolButton(Symbols.Close, "关闭", onClick = { close?.invoke(null) ?: onDismiss() }, small = true)
            }
            Box(
                Modifier
                    .size(236.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(c.camera),
                contentAlignment = Alignment.Center,
            ) {
                Viewfinder(Modifier, scanning = state is PairUiState.Scanning, boxSize = 220.dp) { m ->
                    if (cameraReady && (state is PairUiState.Scanning || state is PairUiState.Pairing)) scanner(m, onScanned)
                }
            }
            if (!failed) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(sp.sp2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (state) {
                        PairUiState.Pairing, PairUiState.Paired, is PairUiState.Known -> {
                            Spinner(size = 12.dp, color = c.fg2)
                            Text("正在配对", color = c.fg2, fontSize = Clauderc.fontSizes.caption)
                        }
                        PairUiState.Scanning -> Text("二维码 5 分钟内有效，只能用一次", color = c.fg3, fontSize = Clauderc.fontSizes.caption)
                        PairUiState.Expired, PairUiState.Used, PairUiState.Redeemed, is PairUiState.Insecure, PairUiState.RateLimited, is PairUiState.Failed -> Unit
                    }
                }
            } else {
                FailureInline(state, onRescan)
            }
        }
    }
}

@Composable
private fun FailureInline(state: PairUiState, onRescan: () -> Unit) {
    val c = Clauderc.colors
    val (title, body) = failureCopy(state)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface2)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = c.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            buildAnnotatedString {
                body.forEachIndexed { i, part ->
                    if (i % 2 == 1) withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, color = c.fg)) { append(part) } else append(part)
                }
            },
            color = c.fg2,
            fontSize = Clauderc.fontSizes.label,
            lineHeight = 1.55.em,
        )
        PillButton("重新扫描", onClick = onRescan, style = PillStyle.Ink, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

private fun failureCopy(state: PairUiState): Pair<String, List<String>> = when (state) {
    PairUiState.Expired -> "二维码已过期" to listOf("生成已超过 5 分钟，在目标机上重新生成一个。")
    PairUiState.Used -> "二维码无效" to listOf("它可能已经被用过，每个二维码只能用一次。")
    PairUiState.Redeemed -> "二维码已经用过" to listOf("请在目标机上重新生成一个。")
    is PairUiState.Insecure -> "地址没有加密" to listOf("二维码里的地址是 ", state.address, "。\n只能连接 ", "https://", " 或 ", "wss://", " 地址。")
    PairUiState.RateLimited -> "尝试太频繁" to listOf("1 分钟内失败次数过多，稍后再扫。")
    is PairUiState.Failed -> "配对失败" to listOf(state.message)
    else -> "" to emptyList()
}

@Composable
private fun FailureSheet(state: PairUiState, onRescan: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val (title, body) = when (state) {
        PairUiState.Expired -> "二维码已过期" to listOf("生成已超过 5 分钟，在目标机上重新生成一个。")
        PairUiState.Used -> "二维码无效" to listOf("它可能已经被用过，每个二维码只能用一次。")
        PairUiState.Redeemed -> "二维码已经用过" to listOf("请在目标机上重新生成一个。")
        is PairUiState.Insecure -> "地址没有加密" to listOf("二维码里的地址是 ", state.address, "。\n只能连接 ", "https://", " 或 ", "wss://", " 地址。")
        PairUiState.RateLimited -> "尝试太频繁" to listOf("1 分钟内失败次数过多，稍后再扫。")
        is PairUiState.Failed -> "配对失败" to listOf(state.message)
        else -> "" to emptyList()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = Clauderc.radii.lg, topEnd = Clauderc.radii.lg))
            .background(c.sheet)
            .navigationBarsPadding()
            .padding(sp.page),
        verticalArrangement = Arrangement.spacedBy(sp.sp3),
    ) {
        Text(title, color = c.fg, fontSize = Clauderc.fontSizes.headline)
        Text(
            buildAnnotatedString {
                // Odd parts are addresses, shown in mono.
                body.forEachIndexed { i, part ->
                    if (i % 2 == 1) withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, color = c.fg)) { append(part) } else append(part)
                }
            },
            color = c.fg2,
            fontSize = Clauderc.fontSizes.label,
            lineHeight = 1.65.em,
        )
        PillButton(
            "重新扫描",
            onClick = onRescan,
            style = PillStyle.Ink,
            modifier = Modifier.fillMaxWidth().padding(top = sp.sp3),
        )
    }
}

/** .vf: four 28dp corner brackets and a scan line moving down 220dp and back, 3.6s each way. */
@Composable
private fun Viewfinder(
    modifier: Modifier,
    scanning: Boolean,
    boxSize: androidx.compose.ui.unit.Dp = 240.dp,
    preview: @Composable (Modifier) -> Unit,
) {
    val c = Clauderc.colors
    val t = rememberInfiniteTransition(label = "scan")
    val travel = (boxSize.value - 20f).coerceAtLeast(120f)
    val y by t.animateFloat(0f, travel, Motion.scanLine, label = "y")
    Box(modifier.size(boxSize).testTag("viewfinder")) {
        preview(Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(10.dp)))
        Canvas(Modifier.fillMaxSize()) {
            val arm = 28.dp.toPx()
            val r = 14.dp.toPx()
            val w = 2.dp.toPx()
            val s = size.width
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                val p = Path().apply {
                    moveTo(x, y + dy * arm)
                    lineTo(x, y + dy * r)
                    quadraticTo(x, y, x + dx * r, y)
                    lineTo(x + dx * arm, y)
                }
                drawPath(p, c.onCamera, style = Stroke(w))
            }
            val h = w / 2
            corner(h, h, 1f, 1f)
            corner(s - h, h, -1f, 1f)
            corner(h, s - h, 1f, -1f)
            corner(s - h, s - h, -1f, -1f)
        }
        if (scanning) {
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .offset(y = 10.dp + y.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .alpha(0.55f)
                    .background(c.onCamera),
            )
        }
    }
}

/**
 * QR codes only, tried as drawn and inverted on alternate frames: older agents drew the
 * code in the terminal's own colors, which comes out light-on-dark on a light theme.
 */
internal fun qrDecoderFactory(): DecoderFactory =
    DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE), null, null, Intents.Scan.MIXED_SCAN)

/** A zxing camera preview that reports every decoded QR code; asks for the camera permission first. */
@Composable
fun QrScanner(modifier: Modifier, onScanned: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Box(modifier.clickable { ask.launch(Manifest.permission.CAMERA) }, contentAlignment = Alignment.Center) {
            Text("点这里允许使用相机", color = Clauderc.colors.onCamera.copy(alpha = 0.7f), fontSize = Clauderc.fontSizes.label)
        }
        DisposableEffect(Unit) {
            ask.launch(Manifest.permission.CAMERA)
            onDispose { }
        }
        return
    }
    val latest by rememberUpdatedState(onScanned)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember {
        BarcodeView(context).apply {
            decoderFactory = qrDecoderFactory()
            decodeContinuous { result -> result.text?.let { latest(it) } }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.resume()
                Lifecycle.Event.ON_PAUSE -> view.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
        onDispose {
            lifecycle.removeObserver(observer)
            view.pause()
        }
    }
    AndroidView({ view }, modifier)
}

package clauderc.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercColors
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.page
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The full-screen loading art of each place that waits for data (the demo's 第四轮 loading layer, as picked). */
enum class LoadingArt {
    /** Starting up and reaching the relay: a dot runs back and forth between a phone and a computer. */
    Link,

    /** Opening a chat: empty bubbles pop up from the bottom, left and right, a light sweeping across. */
    Skeleton,

    /** The session list's first read: three cards stacked, the top one sliding off to the bottom. */
    Deal,

    /** git's first read: a timeline line draws down, nodes popping and a line of text growing beside each. */
    Trace,

    /** Opening the terminal: monospace lines type out, a block cursor blinking at the end. */
    Type,

    /** Opening a subagent: a grid of blocks lights up and dims at random. */
    Blocks,
}

/** Loads shorter than this show no art: it would only flash. */
const val LOADING_DELAY_MILLIS = 300L

/** With 「总是播放加载动画」 on, a place's first load keeps its art up at least this long, however soon the data comes. */
const val FIRST_LOAD_HOLD_MILLIS = 1500L

/** The places that have shown their first load since the app started; one per [LoadingArt], each place having its own. */
class LoadingFirsts {
    private val played = mutableSetOf<LoadingArt>()

    /** True the first time [art]'s place asks, false after. */
    @Synchronized fun claim(art: LoadingArt): Boolean = played.add(art)
}

/**
 * How the loading covers behave: with [always] on (设置 · 动画 · 总是播放加载动画) each place's first load
 * since the app started is covered at once and held [FIRST_LOAD_HOLD_MILLIS]; after that, and with it
 * off, only loads past [LOADING_DELAY_MILLIS] are.
 */
class LoadingPolicy(val always: Boolean = false, private val firsts: LoadingFirsts = LoadingFirsts()) {
    /** Whether this load of [art]'s place is its first one to play; only counts while [always] is on. */
    fun first(art: LoadingArt): Boolean = always && firsts.claim(art)
}

val LocalLoadingPolicy = staticCompositionLocalOf { LoadingPolicy() }
private const val COVER_IN_MILLIS = 240
private const val COVER_OUT_MILLIS = 380

/**
 * Lays [art] over [content] once [loading] has lasted [LOADING_DELAY_MILLIS], [top] down from the top
 * so the bar above stays, and keeps the content out of sight while it loads; a place's first load
 * under [LocalLoadingPolicy]'s 「总是播放加载动画」 is covered at once and for [FIRST_LOAD_HOLD_MILLIS] at
 * least. When loading ends the art fades out growing to 1.12x and the content rises 14dp into place; a
 * load that ended before the art came up just shows the content. [splash] paints the page under the
 * art, for the cover over the whole app. [label] goes under the art, [title] into its text where it has some.
 */
@Composable
fun LoadingCover(
    loading: Boolean,
    art: LoadingArt,
    modifier: Modifier = Modifier,
    top: Dp = 0.dp,
    label: String = "",
    title: String = "",
    splash: Boolean = false,
    content: @Composable () -> Unit,
) {
    val m = Clauderc.motion
    val policy = LocalLoadingPolicy.current
    var covered by remember { mutableStateOf(false) }
    val cover = remember { Animatable(0f) }
    // Starts hidden when loading, so the page does not flash for the frame before the effect runs.
    val rise = remember { Animatable(if (loading) 0f else 1f) }
    // The frame time a first load's art stays up until.
    var holdUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(loading) {
        if (loading) {
            rise.snapTo(0f)
            if (policy.first(art)) holdUntil = withFrameMillis { it } + FIRST_LOAD_HOLD_MILLIS else delay(LOADING_DELAY_MILLIS)
            covered = true
            cover.animateTo(1f, tween(COVER_IN_MILLIS, easing = m.easeEmph))
        } else if (covered) {
            val left = holdUntil - withFrameMillis { it }
            if (left > 0) delay(left)
            launch { rise.animateTo(1f, tween(m.durM, delayMillis = 120, easing = m.easeEmph)) }
            cover.animateTo(0f, tween(COVER_OUT_MILLIS, easing = m.easeEmphIn))
            covered = false
        } else {
            rise.snapTo(1f)
        }
    }
    Box(modifier) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = rise.value
                translationY = (1f - rise.value) * 14.dp.toPx()
            },
        ) { content() }
        if (covered) {
            val leaving = !loading
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = top)
                    .then(if (splash) Modifier.page() else Modifier)
                    .graphicsLayer { alpha = cover.value }
                    .testTag("loadingCover")
                    .semantics { contentDescription = label.ifEmpty { "加载中" } },
                contentAlignment = Alignment.Center,
            ) {
                // Leaving, the art grows to 1.12x as it fades (ld-zoom).
                val grow = Modifier.graphicsLayer {
                    val out = if (leaving) 1f - cover.value else 0f
                    scaleX = 1f + 0.12f * out
                    scaleY = scaleX
                }
                LoadingArtwork(art, grow, label, title)
            }
        }
    }
}

/** [art] with its label, animating off its own frame clock; only its canvas redraws. */
@Composable
fun LoadingArtwork(art: LoadingArt, modifier: Modifier = Modifier, label: String = "", title: String = "") {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var start = -1L
        while (true) {
            withInfiniteAnimationFrameMillis {
                if (start < 0) start = it
                clock.longValue = it - start
            }
        }
    }
    val look = artLook()
    when (art) {
        LoadingArt.Skeleton -> SkeletonArt(modifier.fillMaxSize(), look) { clock.longValue }
        LoadingArt.Type -> TypeArt(modifier.fillMaxSize(), look, title) { clock.longValue }
        else -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp5)) {
            when (art) {
                LoadingArt.Link -> Canvas(Modifier.size(240.dp, 96.dp)) { drawLink(look, clock.longValue) }
                LoadingArt.Deal -> Canvas(Modifier.size(210.dp, 150.dp)) { drawDeal(look, clock.longValue) }
                LoadingArt.Trace -> Canvas(Modifier.size(240.dp, 300.dp)) { drawTrace(look, clock.longValue) }
                else -> Canvas(Modifier.size(18.dp * 10 + 6.dp * 9, 18.dp * 6 + 6.dp * 5)) { drawBlocks(look, clock.longValue) }
            }
            if (label.isNotEmpty()) Text(label, fontSize = Clauderc.fontSizes.label, color = Clauderc.colors.fg2, letterSpacing = 0.01.em)
        }
    }
}

/**
 * The colours of the art (--l1 .. --ls): single ink on 清玻 and 格栅; on 贴纸 the accent with two
 * sticker colours, white cards outlined in ink.
 */
internal class ArtLook(val c: ClaudercColors, val sticker: Boolean) {
    val l1 = if (sticker) c.accent else c.fg
    val l2 = if (sticker) c.hue2 else c.fg2
    val l3 = if (sticker) c.hue3 else c.fg3
    val ls = if (sticker) c.panel else c.surface
    val line = if (sticker) c.ink else c.fg
}

@Composable
private fun artLook() = ArtLook(Clauderc.colors, Clauderc.style == UiStyle.Sticker)

// ---------- timing helpers ----------

private val Emph = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
private val Spring = CubicBezierEasing(0.34f, 1.3f, 0.64f, 1f)
private val InOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** Where [millis] falls in a [period] loop, 0..1, shifted by [delay] (negative starts it part way in). */
private fun phase(millis: Long, period: Int, delay: Long = 0): Float = ((millis - delay).mod(period.toLong())).toFloat() / period

/**
 * A CSS keyframe track: [stops] are (offset, value) pairs in order; between two stops the value moves
 * on [easing], held before the first and after the last.
 */
private fun track(p: Float, easing: Easing, vararg stops: Pair<Float, Float>): Float {
    if (p <= stops.first().first) return stops.first().second
    for (i in 1 until stops.size) {
        val (a, va) = stops[i - 1]
        val (b, vb) = stops[i]
        if (p <= b) return if (b == a) vb else va + (vb - va) * easing.transform((p - a) / (b - a))
    }
    return stops.last().second
}

// ---------- 连线 ----------

private fun DrawScope.drawLink(l: ArtLook, ms: Long) {
    val u = 1.dp.toPx()
    val stroke = (if (l.sticker) 2.6f else 2.4f) * u
    // lk-bump: 3.2s on the spring curve, the computer half a loop after the phone; the box squashes from its bottom.
    fun bump(delay: Long): Triple<Float, Float, Float> {
        val p = phase(ms, 3200, delay)
        val sx = track(p, Spring, 0f to 1f, 0.04f to 1f, 0.09f to 1.1f, 0.16f to 0.95f, 0.24f to 1f)
        val sy = track(p, Spring, 0f to 1f, 0.04f to 1f, 0.09f to 0.88f, 0.16f to 1.06f, 0.24f to 1f)
        val dy = track(p, Spring, 0f to 0f, 0.09f to 0f, 0.16f to -4f, 0.24f to 0f)
        return Triple(sx, sy, dy)
    }
    fun device(x: Float, y: Float, w: Float, h: Float, r: Float, delay: Long, extra: DrawScope.() -> Unit) {
        val (sx, sy, dy) = bump(delay)
        val pivot = Offset((x + w / 2) * u, (y + h) * u)
        translate(top = dy * u) {
            scale(sx, sy, pivot) {
                drawRoundRect(l.ls, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u))
                drawRoundRect(l.line, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u), style = Stroke(stroke, join = StrokeJoin.Round))
                extra()
            }
        }
    }
    device(10f, 14f, 38f, 66f, 10f, 0) {
        drawLine(l.line, Offset(24 * u, 70 * u), Offset(34 * u, 70 * u), 2.4f * u, StrokeCap.Round)
    }
    // lk-line: dashes 2 on 9 off, flowing 11 to the right every 0.9s.
    val flow = phase(ms, 900) * 11f
    drawLine(
        l.c.fg3, Offset(62 * u, 48 * u), Offset(178 * u, 48 * u), 2.4f * u, StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2 * u, 9 * u), -flow * u),
    )
    device(182f, 20f, 50f, 38f, 9f, 1600) {
        drawLine(l.line, Offset(199 * u, 76 * u), Offset(215 * u, 76 * u), 2.4f * u, StrokeCap.Round)
        drawLine(l.line, Offset(207 * u, 58 * u), Offset(207 * u, 76 * u), 2.4f * u, StrokeCap.Round)
        // 贴纸's computer smiles.
        if (l.sticker) {
            drawCircle(l.line, 2.2f * u, Offset(200 * u, 36 * u))
            drawCircle(l.line, 2.2f * u, Offset(214 * u, 36 * u))
            val mouth = Path().apply {
                moveTo(202.5f * u, 43.5f * u)
                quadraticTo(207f * u, 47.1f * u, 211.5f * u, 43.5f * u)
            }
            drawPath(mouth, l.line, style = Stroke(2 * u, cap = StrokeCap.Round))
        }
    }
    // lk-dot: 1.6s each way on the emphasized curve, back and forth.
    val leg = (ms.mod(3200L)).toFloat() / 1600f
    val x = if (leg < 1f) Emph.transform(leg) else Emph.transform(2f - leg)
    drawCircle(l.l1, 6 * u, Offset((62 + 112 * x) * u, 48 * u))
}

// ---------- 骨架气泡 ----------

/** The demo's SKEL: side, width share, height. */
private val Bubbles = listOf(Triple(true, 0.58f, 40), Triple(false, 0.76f, 62), Triple(false, 0.44f, 40), Triple(true, 0.36f, 40), Triple(false, 0.82f, 86), Triple(false, 0.30f, 40))

@Composable
private fun SkeletonArt(modifier: Modifier, l: ArtLook, ms: () -> Long) {
    Canvas(modifier) {
        val u = 1.dp.toPx()
        val t = ms()
        val left = 16 * u
        val width = size.width - 32 * u
        var bottom = size.height - 24 * u
        // Laid from the bottom up, the last bubble lowest.
        Bubbles.withIndex().reversed().forEach { (i, b) ->
            val (right, share, h) = b
            val w = width * share
            val x = if (right) left + width - w else left
            val y = bottom - h * u
            bottom = y - 8 * u
            // sk-pop: 420ms on the spring curve, 70ms apart; up 14 and from 92%.
            val pop = ((t - i * 70L) / 420f).coerceIn(0f, 1f)
            val e = Spring.transform(pop)
            val alpha = pop.coerceIn(0f, 1f)
            val s = 0.92f + 0.08f * e
            val r = 20 * u
            val tail = 8 * u
            val box = RoundRect(x, y, x + w, y + h * u, CornerRadius(r), CornerRadius(r), if (right) CornerRadius(tail) else CornerRadius(r), if (right) CornerRadius(r) else CornerRadius(tail))
            val shape = Path().apply { addRoundRect(box) }
            translate(top = (1f - e) * 14 * u) {
                scale(s, s, Offset(x + w / 2, y + h * u / 2)) {
                    val fill = if (l.sticker && right) l.c.accent.copy(alpha = 0.32f) else l.ls
                    if (l.sticker) {
                        clipPath(shape, androidx.compose.ui.graphics.ClipOp.Difference) { translate(top = 4 * u) { drawPath(shape, l.c.ink.copy(alpha = alpha)) } }
                    }
                    drawPath(shape, fill.copy(alpha = fill.alpha * alpha))
                    // sk-sweep: a light band 1.4s across, 90ms apart.
                    val sweep = InOut.transform(phase(t, 1400, i * 90L))
                    val band = if (l.sticker) Color.White.copy(alpha = 0.55f) else l.c.bg.copy(alpha = 0.7f)
                    clipPath(shape) {
                        val dx = (-1f + 2f * sweep) * w
                        drawRect(
                            Brush.linearGradient(
                                0.3f to Color.Transparent, 0.5f to band.copy(alpha = band.alpha * alpha), 0.7f to Color.Transparent,
                                start = Offset(x + dx, y), end = Offset(x + dx + w, y + w * 0.18f),
                            ),
                            Offset(x, y), Size(w, h * u),
                        )
                    }
                    if (l.sticker) drawPath(shape, l.c.ink.copy(alpha = alpha), style = Stroke(2 * u))
                }
            }
        }
    }
}

// ---------- 发牌 ----------

private fun DrawScope.drawDeal(l: ArtLook, ms: Long) {
    val u = 1.dp.toPx()
    val w = size.width
    val h = size.height
    class Card(val i: Int, val p: Float) {
        val dy = track(p, Emph, 0f to 16f, 0.33f to 8f, 0.66f to 0f, 0.83f to -14f, 1f to 16f)
        val dx = track(p, Emph, 0f to 0f, 0.66f to 0f, 0.83f to 0.64f, 1f to 0f)
        val s = track(p, Emph, 0f to 0.9f, 0.33f to 0.95f, 0.66f to 1f, 0.83f to 1f, 1f to 0.9f)
        val rot = track(p, Emph, 0f to 0f, 0.66f to 0f, 0.83f to 10f, 1f to 0f)
        val alpha = track(p, Emph, 0f to 0.6f, 0.33f to 0.85f, 0.66f to 1f, 0.83f to 0f, 1f to 0.6f)
        // dl-cycle's z-index: 1, 2, 3 as it comes up, 0 once it has slid off.
        val z = when {
            p < 0.33f -> 1
            p < 0.66f -> 2
            p < 0.84f -> 3
            else -> 0
        }
    }
    val cards = (0 until 3).map { Card(it, phase(ms, 2400, -800L * it)) }.sortedBy { it.z }
    for (card in cards) {
        val pivot = Offset(w / 2, h / 2)
        translate(left = card.dx * w, top = card.dy * u) {
            rotate(card.rot, pivot) {
                scale(card.s, card.s, pivot) {
                    val box = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(22 * u))) }
                    val a = card.alpha
                    if (l.sticker) {
                        clipPath(box, androidx.compose.ui.graphics.ClipOp.Difference) { translate(top = 4 * u) { drawPath(box, l.c.ink.copy(alpha = a)) } }
                        drawPath(box, l.ls.copy(alpha = l.ls.alpha * a))
                        drawPath(box, l.c.ink.copy(alpha = a), style = Stroke(4 * u))
                    } else {
                        // box-shadow: 0 14px 30px -18px rgba(0, 0, 0, 0.35): a box 18 in, 14 down, blurred 30.
                        softDrop(18 * u, 32 * u, w - 18 * u, h - 4 * u, 4 * u, 26 * u, Color.Black.copy(alpha = 0.35f * a))
                        drawPath(box, l.ls.copy(alpha = a))
                        drawPath(box, l.c.line.copy(alpha = a), style = Stroke(1 * u))
                    }
                    // A title bar and three lines.
                    val head = if (l.sticker) l.c.hue(card.i).copy(alpha = 0.6f * a) else l.c.surface2.copy(alpha = l.c.surface2.alpha * a)
                    drawRoundRect(head, Offset(20 * u, 26 * u), Size(w * 0.44f, 10 * u), CornerRadius(5 * u))
                    for (k in 0 until 3) {
                        drawRoundRect(l.c.surface2.copy(alpha = l.c.surface2.alpha * a), Offset(20 * u, (48 + 22 * k) * u), Size(w * 0.64f, 10 * u), CornerRadius(5 * u))
                    }
                }
            }
        }
    }
}

private val dropPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
private var dropBlur = -1f

/** A CSS soft box-shadow: the round rect [left]..[bottom] of corner [r], blurred [blur] in [color]. */
private fun DrawScope.softDrop(left: Float, top: Float, right: Float, bottom: Float, r: Float, blur: Float, color: Color) {
    if (dropBlur != blur) {
        dropPaint.maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
        dropBlur = blur
    }
    dropPaint.color = color.toArgb()
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(left, top, right, bottom, r, r, dropPaint) }
}

// ---------- 描线 ----------

/** The timeline's nodes, and when the line's head reaches each, as shares of the 2.4s loop. */
private val TraceNodes = listOf(Offset(40f, 44f) to 0.065f, Offset(40f, 96f) to 0.205f, Offset(76f, 150f) to 0.398f, Offset(40f, 206f) to 0.501f, Offset(40f, 262f) to 0.651f)
private val TraceBranch by lazy { PathParser().parsePathString("M40 104C40 128 76 118 76 142V170C76 196 40 186 40 210").toPath() }

private fun DrawScope.drawTrace(l: ArtLook, ms: Long) {
    val u = 1.dp.toPx()
    val p = phase(ms, 2400)
    // Everything fades together over the last 15% of the loop.
    val fade = track(p, androidx.compose.animation.core.LinearEasing, 0.85f to 1f, 1f to 0f)
    val mainColor = if (l.sticker) l.l1 else l.c.fg
    val branchColor = if (l.sticker) l.l2 else l.c.fg3
    scale(u, u, Offset.Zero) {
        // tr-draw: the main line, top to bottom over 0-70%, linear.
        val drawn = track(p, androidx.compose.animation.core.LinearEasing, 0f to 0f, 0.7f to 1f)
        if (drawn > 0f) drawLine(mainColor.copy(alpha = fade), Offset(40f, 20f), Offset(40f, 20f + 260f * drawn), 3f, StrokeCap.Round)
        // tr-draw2: the branch, from 22.6% to 60.3%.
        val branch = track(p, androidx.compose.animation.core.LinearEasing, 0.226f to 0f, 0.603f to 1f)
        if (branch > 0f) {
            val measure = PathMeasure().apply { setPath(TraceBranch, false) }
            val part = Path()
            measure.getSegment(0f, measure.length * branch, part, true)
            drawPath(part, branchColor.copy(alpha = fade), style = Stroke(3f, cap = StrokeCap.Round))
        }
        TraceNodes.forEach { (n, at) ->
            // tr-grow: the text bar grows from its left over 8% from the arrival.
            val grow = track(p, EaseOut, at to 0f, at + 0.08f to 1f)
            if (grow > 0f) {
                val bw = if (n.x > 40f) 96f else 120f
                drawRoundRect(l.c.surface2.copy(alpha = l.c.surface2.alpha * fade), Offset(n.x + 20f, n.y - 5f), Size(bw * grow, 10f), CornerRadius(5f))
            }
            // tr-pop: the node pops to 1.35x 4% after the arrival and settles 5% later.
            val s = track(p, EaseOut, at to 0f, at + 0.04f to 1.35f, at + 0.09f to 1f)
            if (s > 0f) {
                drawCircle(l.c.bg.copy(alpha = fade), 7f * s, n)
                drawCircle(mainColor.copy(alpha = fade), 7f * s, n, style = Stroke(3f * s))
            }
        }
    }
}

// ---------- 光标打字 ----------

@Composable
private fun TypeArt(modifier: Modifier, l: ArtLook, title: String, ms: () -> Long) {
    val measurer = rememberTextMeasurer()
    val c = Clauderc.colors
    val style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, color = c.fg)
    // The demo's three lines: text, when it starts in seconds, and how many steps it types out in.
    val lines = remember(title, l.l1, c.fg) {
        listOf(
            Triple(buildAnnotatedString { withStyle(SpanStyle(color = l.l1, fontWeight = FontWeight.Medium)) { append("$") }; append(" clauderc term") }, 100L, 16),
            Triple(buildAnnotatedString { append("› 接入会话 $title") }, 750L, 14),
            Triple(buildAnnotatedString { append("› 读取屏幕 40 × 120 ") }, 1400L, 16),
        )
    }
    val layouts = remember(lines, measurer) { lines.map { measurer.measure(it.first, style) } }
    Canvas(modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
        val t = ms()
        val lineHeight = 13.sp.toPx() * 1.9f
        lines.forEachIndexed { i, (_, start, steps) ->
            val layout = layouts[i]
            // ty-clip: revealed left to right in [steps] even steps over 0.55s.
            val p = ((t - start) / 550f).coerceIn(0f, 1f)
            val shown = kotlin.math.ceil(p * steps).toFloat() / steps
            if (shown <= 0f) return@forEachIndexed
            val y = i * lineHeight + (lineHeight - layout.size.height) / 2
            clipRectWidth(layout.size.width * shown) { drawText(layout, topLeft = Offset(0f, y)) }
            // The block cursor after the last line blinks every second, on for the first half.
            if (i == lines.lastIndex && (t / 500L) % 2L == 0L) {
                val cw = 13.sp.toPx() * 0.62f
                val ch = 13.sp.toPx() * 1.15f
                clipRectWidth(layout.size.width * shown + cw) {
                    drawRect(l.l1, Offset(layout.size.width.toFloat(), i * lineHeight + (lineHeight - ch) / 2 + 13.sp.toPx() * 0.1f), Size(cw, ch))
                }
            }
        }
    }
}

private inline fun DrawScope.clipRectWidth(width: Float, block: DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.canvas.clipRect(0f, 0f, width, size.height)
    block()
    drawContext.canvas.restore()
}

// ---------- 方块填格 ----------

private fun DrawScope.drawBlocks(l: ArtLook, ms: Long) {
    val u = 1.dp.toPx()
    val cell = 18 * u
    val gap = 6 * u
    for (i in 0 until 60) {
        // bk-blink: 1.6s, each cell started part way in by ((i * 37) mod 23) / 23 of a loop.
        val start = -((i * 37) % 23) * 1600L / 23
        val p = phase(ms, 1600, start)
        val a = track(p, InOut, 0f to 0.08f, 0.4f to 1f, 0.7f to 0.35f, 1f to 0.08f)
        val s = track(p, InOut, 0f to 0.8f, 0.4f to 1f, 1f to 0.8f)
        val x = (i % 10) * (cell + gap)
        val y = (i / 10) * (cell + gap)
        val fill = if (l.sticker) l.c.hue((i * 7) % 5) else l.c.fg
        val r = (if (l.sticker) 6 else 4) * u
        scale(s, s, Offset(x + cell / 2, y + cell / 2)) {
            drawRoundRect(fill.copy(alpha = a), Offset(x, y), Size(cell, cell), CornerRadius(r))
            if (l.sticker) drawRoundRect(l.c.ink.copy(alpha = a), Offset(x + 0.75f * u, y + 0.75f * u), Size(cell - 1.5f * u, cell - 1.5f * u), CornerRadius(r - 0.75f * u), style = Stroke(1.5f * u))
        }
    }
}

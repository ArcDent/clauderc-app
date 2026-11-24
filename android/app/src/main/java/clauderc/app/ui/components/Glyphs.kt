package clauderc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.styled
import kotlin.math.PI
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.stickerPushed
import androidx.compose.foundation.interaction.InteractionSource
import clauderc.app.ui.theme.LocalLoopClock
import clauderc.app.ui.theme.Motion

/** The mockup's status symbols: dot, spinning ring, hollow ring, dash. */
enum class Glyph { Need, Work, Idle, Stop, On }

/**
 * A session's or relay's status. 清玻: the mockup's dot, spinning ring, hollow ring and dash. 贴纸:
 * the same drawn in ink, a bigger dot ringed in ink and a dashed ring. 格栅: terminal characters,
 * a blinking ●, Claude Code's turning star, ○ and –.
 */
@Composable
fun StatusGlyph(glyph: Glyph, modifier: Modifier = Modifier) {
    val c = Clauderc.colors
    val m = Clauderc.motion
    val ui = Clauderc.style
    val label = when (glyph) {
        Glyph.Need -> "待确认"
        Glyph.Work -> "工作中"
        Glyph.Idle -> "空闲"
        Glyph.Stop -> "已停止"
        Glyph.On -> "在线"
    }
    if (ui == UiStyle.Grid) {
        CliGlyph(glyph, modifier.semantics { contentDescription = label })
        return
    }
    val sticker = ui == UiStyle.Sticker
    val base = modifier.size(10.dp).semantics { contentDescription = label }
    when (glyph) {
        Glyph.Need -> {
            // .g-need: a dot with a beacon that grows to 2.8x and fades over 70% of 2.4s. The beacon
            // only scales and fades its own layer, so the loop never redraws the page around it.
            val clock = LocalLoopClock.current
            clock.Use()
            val r = if (sticker) 5.5.dp else 4.dp
            Box(base) {
                Canvas(
                    Modifier.matchParentSize().graphicsLayer {
                        val p = (clock.millis % Motion.BEACON_MILLIS) / Motion.BEACON_MILLIS.toFloat()
                        val q = m.easeEmph.transform((p / 0.7f).coerceAtMost(1f))
                        scaleX = 1 + 1.8f * q
                        scaleY = scaleX
                        alpha = 1 - q
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                ) { drawCircle(c.accent.copy(alpha = 0.5f), radius = 4.dp.toPx()) }
                Canvas(Modifier.matchParentSize()) {
                    // 贴纸 rings the dot in ink 1.5dp wide.
                    if (sticker) drawCircle(c.ink, radius = (r + 1.5.dp).toPx())
                    drawCircle(c.accent, radius = r.toPx())
                }
            }
        }
        Glyph.Work -> if (sticker) SpinningRing(base, c.fg, stroke = 2.dp, dashed = true, overflow = 0.5.dp) else SpinningRing(base, c.fg2)
        Glyph.Idle -> Canvas(base) {
            val w = (if (sticker) 2.dp else 1.5.dp).toPx()
            drawCircle(if (sticker) c.fg else c.fg3, radius = (if (sticker) 5.dp else 4.dp).toPx() - w / 2, style = Stroke(w))
        }
        Glyph.Stop -> Canvas(base) {
            val sz = if (sticker) Size(10.dp.toPx(), 2.5.dp.toPx()) else Size(9.dp.toPx(), 1.5.dp.toPx())
            drawRoundRect(if (sticker) c.fg else c.fg3, Offset((size.width - sz.width) / 2, (size.height - sz.height) / 2), sz, CornerRadius(1.dp.toPx()))
        }
        Glyph.On -> Canvas(base) { drawCircle(c.fg, radius = 3.dp.toPx()) }
    }
}

/** .spin: a ring with its right quarter missing, turning every 0.9s; 贴纸 dashes it, 格栅 turns a braille dot pattern. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = LocalContentColor.current) {
    val label = modifier.semantics { contentDescription = "加载中" }
    when (Clauderc.style) {
        UiStyle.Grid -> CliSpinner(label, color)
        UiStyle.Sticker -> SpinningRing(label.size(size), color, stroke = 2.dp, dashed = true)
        UiStyle.Clear -> SpinningRing(label.size(size), color)
    }
}

/**
 * The ring is drawn once; each step only turns its layer, so nothing around it is redrawn. The angle
 * comes off the shared [LoopClock], which steps about 30 times a second for every loop at once.
 * A [dashed] ring is whole, cut into even dashes like a CSS dashed border; [overflow] lets it reach
 * that far past the box on every side.
 */
@Composable
private fun SpinningRing(modifier: Modifier, color: Color, stroke: Dp = 1.5.dp, dashed: Boolean = false, overflow: Dp = 0.dp) {
    val clock = LocalLoopClock.current
    clock.Use()
    Canvas(modifier.graphicsLayer { rotationZ = (clock.millis % Motion.SPIN_MILLIS) * 360f / Motion.SPIN_MILLIS }) {
        val w = stroke.toPx()
        val o = overflow.toPx()
        val box = Size(size.width + 2 * o - w, size.height + 2 * o - w)
        val dash = if (dashed) {
            // About one dash and gap per 2.2 stroke widths, evened out so the ring closes on a gap.
            val around = PI.toFloat() * box.width
            val count = (around / (w * 2.2f)).roundToInt().coerceAtLeast(4)
            val period = around / count
            PathEffect.dashPathEffect(floatArrayOf(period * 0.6f, period * 0.4f))
        } else {
            null
        }
        drawArc(
            color = color,
            startAngle = if (dashed) 0f else 45f,
            sweepAngle = if (dashed) 360f else 270f,
            useCenter = false,
            topLeft = Offset(w / 2 - o, w / 2 - o),
            size = box,
            style = Stroke(w, pathEffect = dash),
        )
    }
}

/** 格栅's terminal characters, frame by frame as the demo's cli-spin, cli-braille and cli-blink keyframes. */
object CliGlyphs {
    const val STAR_MILLIS = 1200
    const val BRAILLE_MILLIS = 800
    const val BLINK_MILLIS = 1200
    val STAR = listOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢")
    val BRAILLE = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

    /** The 「工作中」 star [millis] into the loop: ten steps of 120ms. */
    fun star(millis: Long): String = STAR[step(millis, STAR_MILLIS)]

    /** The spinner's braille pattern: ten steps of 80ms. */
    fun braille(millis: Long): String = BRAILLE[step(millis, BRAILLE_MILLIS)]

    /** The 「等你」 dot's strength: full for the first 61% of each 1.2s, then a quarter. */
    fun blink(millis: Long): Float = if (millis.mod(BLINK_MILLIS) < BLINK_MILLIS * 61 / 100) 1f else 0.25f

    private fun step(millis: Long, period: Int) = (millis.mod(period) * 10 / period).toInt()
}

/** One of 格栅's status characters in a 14 x 16dp cell; the animated ones redraw only their own canvas. */
@Composable
private fun CliGlyph(glyph: Glyph, modifier: Modifier) {
    val c = Clauderc.colors
    val clock = LocalLoopClock.current
    if (glyph == Glyph.Need || glyph == Glyph.Work) clock.Use()
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = if (glyph == Glyph.On) 9.sp else 13.sp)
    val chars = when (glyph) {
        Glyph.Need, Glyph.On -> listOf("●")
        Glyph.Work -> CliGlyphs.STAR.distinct()
        Glyph.Idle -> listOf("○")
        Glyph.Stop -> listOf("–")
    }
    val layouts = remember(measurer, style, chars) { chars.associateWith { measurer.measure(it, style) } }
    val color = when (glyph) {
        Glyph.Need -> c.accent
        Glyph.Work, Glyph.On -> c.fg
        else -> c.fg3
    }
    Canvas(modifier.size(14.dp, 16.dp)) {
        val ch = if (glyph == Glyph.Work) CliGlyphs.star(clock.millis) else chars[0]
        val layout = layouts.getValue(ch)
        val alpha = if (glyph == Glyph.Need) CliGlyphs.blink(clock.millis) else 1f
        drawText(layout, color = color, topLeft = Offset((size.width - layout.size.width) / 2, (size.height - layout.size.height) / 2), alpha = alpha)
    }
}

/** 格栅's spinner: the braille dots of a terminal, in a 14 x 16dp cell. */
@Composable
private fun CliSpinner(modifier: Modifier, color: Color) {
    val clock = LocalLoopClock.current
    clock.Use()
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    val layouts = remember(measurer) { CliGlyphs.BRAILLE.associateWith { measurer.measure(it, style) } }
    Canvas(modifier.size(14.dp, 16.dp)) {
        val layout = layouts.getValue(CliGlyphs.braille(clock.millis))
        drawText(layout, color = color, topLeft = Offset((size.width - layout.size.width) / 2, (size.height - layout.size.height) / 2))
    }
}

/**
 * The icon that leads a row such as a machine's. 贴纸: a round sticker in the [index]th sticker
 * colour, outlined in ink and stuck on a little askew, every other one leaning the other way; an
 * [off] one turns grey and faint. While the row's [press] holds it turns further, to -18° or 17°, and
 * shrinks to 90%. 格栅: the bare icon at 20dp. 清玻: the bare icon.
 */
@Composable
fun RowIcon(icon: ImageVector, index: Int, off: Boolean, tint: Color, modifier: Modifier = Modifier, press: InteractionSource? = null) {
    val c = Clauderc.colors
    when (Clauderc.style) {
        UiStyle.Sticker -> {
            val fill = if (off) c.surface2.compositeOver(c.card) else c.hue(index)
            val pushed = press?.let { stickerPushed(it) }
            val (rest, held) = if (index % 2 == 1) 6f to 17f else -7f to -18f
            Box(
                modifier
                    .size(44.dp)
                    .graphicsLayer {
                        val p = pushed?.value ?: 0f
                        rotationZ = rest + (held - rest) * p
                        scaleX = 1f - 0.1f * p
                        scaleY = scaleX
                        alpha = if (off) 0.55f else 1f
                    }
                    .sticker(fill, c.ink, 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon.styled, contentDescription = null, tint = StickerOnYellow, modifier = Modifier.size(24.dp))
            }
        }
        UiStyle.Grid -> Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(20.dp))
        UiStyle.Clear -> Icon(icon, contentDescription = null, tint = tint, modifier = modifier)
    }
}

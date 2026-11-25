package clauderc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.LocalLoopClock
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.styled

/** The mockup's status symbols: dot, spinning ring, hollow ring, dash. */
enum class Glyph { Need, Work, Idle, Stop, On }

/**
 * A session's or relay's status:
 * - 印版: crisp editorial/terminal glyphs (blinking ■/●, turning Claude star, ○, –).
 * - 浮墨 / 素卡 / 清玻: accent pulsing dot, spinning ring, hollow ring, dash, green/fg online dot.
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
    if (ui == UiStyle.Plate) {
        CliGlyph(glyph, modifier.semantics { contentDescription = label })
        return
    }
    val base = modifier.size(10.dp).semantics { contentDescription = label }
    when (glyph) {
        Glyph.Need -> {
            val clock = LocalLoopClock.current
            clock.Use()
            val r = 4.dp
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
                    drawCircle(c.accent, radius = r.toPx())
                }
            }
        }
        Glyph.Work -> SpinningRing(base, c.fg2)
        Glyph.Idle -> Canvas(base) {
            val w = 1.5.dp.toPx()
            drawCircle(c.fg3, radius = 4.dp.toPx() - w / 2, style = Stroke(w))
        }
        Glyph.Stop -> Canvas(base) {
            val sz = Size(9.dp.toPx(), 1.5.dp.toPx())
            drawRoundRect(c.fg3, Offset((size.width - sz.width) / 2, (size.height - sz.height) / 2), sz, CornerRadius(1.dp.toPx()))
        }
        Glyph.On -> Canvas(base) {
            val onlineColor = if (c.ok.alpha > 0f && ui != UiStyle.Clear) c.ok else c.fg
            drawCircle(onlineColor, radius = 3.5.dp.toPx())
        }
    }
}

/** .spin: a turning ring (or braille dots in 印版). */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = LocalContentColor.current) {
    val label = modifier.semantics { contentDescription = "加载中" }
    when (Clauderc.style) {
        UiStyle.Plate -> CliSpinner(label, color)
        else -> SpinningRing(label.size(size), color)
    }
}

@Composable
private fun SpinningRing(modifier: Modifier, color: Color, stroke: Dp = 1.5.dp) {
    val clock = LocalLoopClock.current
    clock.Use()
    Canvas(modifier.graphicsLayer { rotationZ = (clock.millis % Motion.SPIN_MILLIS) * 360f / Motion.SPIN_MILLIS }) {
        val w = stroke.toPx()
        val box = Size(size.width - w, size.height - w)
        drawArc(
            color = color,
            startAngle = 45f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(w / 2, w / 2),
            size = box,
            style = Stroke(w),
        )
    }
}

/** 印版's terminal characters, frame by frame. */
object CliGlyphs {
    const val STAR_MILLIS = 1200
    const val BRAILLE_MILLIS = 800
    const val BLINK_MILLIS = 1200
    val STAR = listOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢")
    val BRAILLE = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

    fun star(millis: Long): String = STAR[step(millis, STAR_MILLIS)]
    fun braille(millis: Long): String = BRAILLE[step(millis, BRAILLE_MILLIS)]
    fun blink(millis: Long): Float = if (millis.mod(BLINK_MILLIS) < BLINK_MILLIS * 61 / 100) 1f else 0.25f

    private fun step(millis: Long, period: Int) = (millis.mod(period) * 10 / period).toInt()
}

@Composable
private fun CliGlyph(glyph: Glyph, modifier: Modifier) {
    val c = Clauderc.colors
    val clock = LocalLoopClock.current
    if (glyph == Glyph.Need || glyph == Glyph.Work) clock.Use()
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = if (glyph == Glyph.On) 9.sp else 13.sp)
    val chars = when (glyph) {
        Glyph.Need, Glyph.On -> listOf("■")
        Glyph.Work -> CliGlyphs.STAR.distinct()
        Glyph.Idle -> listOf("□")
        Glyph.Stop -> listOf("–")
    }
    val layouts = remember(measurer, style, chars) { chars.associateWith { measurer.measure(it, style) } }
    val color = when (glyph) {
        Glyph.Need -> c.fg
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

/** The icon that leads a row. */
@Composable
fun RowIcon(icon: ImageVector, index: Int, off: Boolean, tint: Color, modifier: Modifier = Modifier, press: InteractionSource? = null) {
    val size = if (Clauderc.style == UiStyle.Plate) 20.dp else 22.dp
    Icon(icon.styled, contentDescription = null, tint = if (off) Clauderc.colors.fg3 else tint, modifier = modifier.size(size))
}

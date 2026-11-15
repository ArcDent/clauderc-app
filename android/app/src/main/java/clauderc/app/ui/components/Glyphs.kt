package clauderc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import clauderc.app.ui.theme.LocalLoopClock
import clauderc.app.ui.theme.Motion

/** The mockup's status symbols: dot, spinning ring, hollow ring, dash. */
enum class Glyph { Need, Work, Idle, Stop, On }

@Composable
fun StatusGlyph(glyph: Glyph, modifier: Modifier = Modifier) {
    val c = Clauderc.colors
    val m = Clauderc.motion
    val label = when (glyph) {
        Glyph.Need -> "待确认"
        Glyph.Work -> "工作中"
        Glyph.Idle -> "空闲"
        Glyph.Stop -> "已停止"
        Glyph.On -> "在线"
    }
    val base = modifier.size(10.dp).semantics { contentDescription = label }
    when (glyph) {
        Glyph.Need -> {
            // .g-need: a dot with a beacon that grows to 2.8x and fades over 70% of 2.4s. The beacon
            // only scales and fades its own layer, so the loop never redraws the page around it.
            val clock = LocalLoopClock.current
            clock.Use()
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
                Canvas(Modifier.matchParentSize()) { drawCircle(c.accent, radius = 4.dp.toPx()) }
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
        Glyph.On -> Canvas(base) { drawCircle(c.fg, radius = 3.dp.toPx()) }
    }
}

/** .spin: a ring with its right quarter missing, turning every 0.9s. */
@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = LocalContentColor.current) {
    SpinningRing(modifier.size(size).semantics { contentDescription = "加载中" }, color)
}

/**
 * The ring is drawn once; each step only turns its layer, so nothing around it is redrawn. The angle
 * comes off the shared [LoopClock], which steps about 30 times a second for every loop at once.
 */
@Composable
private fun SpinningRing(modifier: Modifier, color: Color) {
    val clock = LocalLoopClock.current
    clock.Use()
    Canvas(modifier.graphicsLayer { rotationZ = (clock.millis % Motion.SPIN_MILLIS) * 360f / Motion.SPIN_MILLIS }) {
        drawRing(color, 1.5.dp.toPx(), gap = true)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRing(color: Color, stroke: Float, gap: Boolean) {
    val inset = stroke / 2
    drawArc(
        color = color,
        startAngle = if (gap) 45f else 0f,
        sweepAngle = if (gap) 270f else 360f,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(size.width - stroke, size.height - stroke),
        style = Stroke(stroke),
    )
}

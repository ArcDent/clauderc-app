package clauderc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.pressProgress
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Card corner radius shared by 素卡 and 浮墨 grouped cards. */
val CardCorner = 24.dp

/** 印版's heavy editorial rule over a list or masthead. */
val PlateRule = 2.dp

/**
 * 素卡's card surface: clean rounded fill with no border or heavy shadow.
 * [top] and [bottom] square off the meeting edges of adjacent rows in a grouped list.
 */
fun Modifier.tileSurface(fill: Color, corner: Dp = CardCorner, top: Boolean = true, bottom: Boolean = true): Modifier {
    val shape = RoundedCornerShape(
        topStart = if (top) corner else 0.dp,
        topEnd = if (top) corner else 0.dp,
        bottomStart = if (bottom) corner else 0.dp,
        bottomEnd = if (bottom) corner else 0.dp,
    )
    return clip(shape).background(fill)
}

/**
 * 浮墨's floating card/pill surface: solid dark/light fill with a 1dp hairline edge.
 */
fun Modifier.inkSurface(fill: Color, edge: Color, corner: Dp = CardCorner, top: Boolean = true, bottom: Boolean = true): Modifier {
    val shape = RoundedCornerShape(
        topStart = if (top) corner else 0.dp,
        topEnd = if (top) corner else 0.dp,
        bottomStart = if (bottom) corner else 0.dp,
        bottomEnd = if (bottom) corner else 0.dp,
    )
    return clip(shape).background(fill).then(
        if (top && bottom) Modifier.border(1.dp, edge, shape)
        else Modifier.drawBehind {
            val s = 1.dp.toPx()
            val topR = if (top) corner.toPx() else 0f
            val botR = if (bottom) corner.toPx() else 0f
            val rr = RoundRect(
                rect = Rect(s / 2f, s / 2f, size.width - s / 2f, size.height - s / 2f),
                topLeft = CornerRadius(topR, topR),
                topRight = CornerRadius(topR, topR),
                bottomRight = CornerRadius(botR, botR),
                bottomLeft = CornerRadius(botR, botR),
            )
            val outline = Path().apply { addRoundRect(rr) }
            val clipTop = if (top) 0f else s
            val clipBottom = if (bottom) size.height else size.height - s
            clipPath(Path().apply { addRect(Rect(0f, clipTop, size.width, clipBottom)) }) {
                drawPath(outline, edge, style = Stroke(s))
            }
        },
    )
}

/** Direction of 印版's mechanical ink sweep fill on press (demo.html). */
enum class PlateSweep { LeftToRight, RightToLeft, BottomToTop }

/** Draws an expanding ink sweep fill at [progress] (0..1) inside a 印版 control. */
fun Modifier.plateSweepFill(progress: Float, fillColor: Color, direction: PlateSweep): Modifier = drawBehind {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0.001f) return@drawBehind
    when (direction) {
        PlateSweep.LeftToRight -> drawRect(fillColor, Offset.Zero, Size(size.width * p, size.height))
        PlateSweep.RightToLeft -> {
            val w = size.width * p
            drawRect(fillColor, Offset(size.width - w, 0f), Size(w, size.height))
        }
        PlateSweep.BottomToTop -> {
            val h = size.height * p
            drawRect(fillColor, Offset(0f, size.height - h), Size(size.width, h))
        }
    }
}

/** Draws a single solid offset shadow for 印版 cards/badges that collapses cleanly on press. */
fun Modifier.plateHardShadow(color: Color, offsetDp: Dp = 3.dp, press: Float = 0f, flat: Dp = 0.dp): Modifier = drawBehind {
    val o = offsetDp.toPx() + (flat.toPx() - offsetDp.toPx()) * press
    if (o > 0f) {
        drawRect(color, Offset(o, o), size)
    }
}

/**
 * 印版's primary stamp button: square solid fill with a two-layer offset letterpress shadow
 * (`2.5dp paper, 4dp ink`) that collapses cleanly when pressed (demo.html `.pbtn.pri` / `.input .send`).
 */
@Composable
fun Modifier.plateStamp(
    fill: Color,
    paper: Color,
    ink: Color,
    source: InteractionSource? = null,
    enabled: Boolean = true,
): Modifier {
    val progress = if (source != null) pressProgress(source) else null
    return drawBehind {
        val lift = 1f - (progress?.value ?: 0f)
        if (enabled && lift > 0f) {
            val gap = 2.5.dp.toPx() * lift
            val outer = 4.dp.toPx() * lift
            drawRect(ink, Offset(outer, outer), size)
            drawRect(paper, Offset(gap, gap), size)
        }
    }
        .background(fill, RectangleShape)
        .border(1.5.dp, if (enabled) ink else Clauderc.colors.fg3, RectangleShape)
}

/** 印版's outlined square frame (1.5dp ink border, 0dp corners). */
fun Modifier.plateBox(fill: Color, borderColor: Color, borderWidth: Dp = 1.5.dp): Modifier =
    background(fill, RectangleShape).border(borderWidth, borderColor, RectangleShape)

/**
 * 印版's hatched framed quota progress bar (demo.html `.pbar`):
 * 1.5dp solid ink border, diagonal 45° ink hatching in the unfilled area, solid ink fill for [fraction].
 */
fun Modifier.plateHatchedBar(fraction: Float, ink: Color, paper: Color): Modifier = drawWithCache {
    onDrawBehind {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        val f = fraction.coerceIn(0f, 1f)
        drawRect(paper, Offset.Zero, size)
        // Diagonal hatch lines across the bar at 45 degrees.
        val hatchColor = ink.copy(alpha = 0.24f)
        val step = 6.dp.toPx()
        val lineW = 1.dp.toPx()
        val clip = Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h)) }
        clipPath(clip) {
            var x = -h
            while (x < w + h) {
                drawLine(hatchColor, Offset(x, h), Offset(x + h, 0f), strokeWidth = lineW)
                x += step
            }
        }
        if (f > 0f) {
            drawRect(ink, Offset.Zero, Size(w * f, h))
        }
        // Crisp border on top.
        drawRect(ink, Offset(stroke / 2f, stroke / 2f), Size(w - stroke, h - stroke), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
    }
}

/** Builds a scalloped cookie/flower [Path] with configurable lobe depth [ampFrac], [lobes], and [rotRad]. */
fun buildCookiePath(
    cx: Float,
    cy: Float,
    radius: Float,
    ampFrac: Float,
    lobes: Int,
    rotRad: Float = 0f,
    steps: Int = 120,
): Path = Path().apply {
    val amp = radius * ampFrac
    for (i in 0..steps) {
        val a = (2.0 * PI * i / steps) - PI / 2.0
        val r = radius + amp * cos(lobes * (a - rotRad)).toFloat()
        val x = cx + r * cos(a).toFloat()
        val y = cy + r * sin(a).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * 素卡's scalloped cookie/flower shape (demo.html pending badge).
 */
val CookieShape: Shape = androidx.compose.foundation.shape.GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    addPath(buildCookiePath(cx, cy, minOf(cx, cy) * 0.87f, ampFrac = 0.11f, lobes = 8))
}

/** A crisp vertical accent/ink divider line along the left edge. */
fun Modifier.leftRail(color: Color, width: Dp): Modifier = drawBehind {
    drawRect(color, Offset.Zero, Size(width.toPx(), size.height))
}

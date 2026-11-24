package clauderc.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import clauderc.app.data.UiStyle
import kotlin.math.roundToInt

/**
 * The page under everything (--k-backdrop over --c-bg). 贴纸: cream paper dotted every [StickerDotGap],
 * with a soft glow of the accent from the top right and, in light, of yellow from the bottom left. The
 * other styles are the plain page colour.
 */
@Composable
fun Modifier.page(): Modifier {
    val c = Clauderc.colors
    if (Clauderc.style != UiStyle.Sticker) return background(c.bg)
    val density = LocalDensity.current
    val dots = remember(c.dot, density) {
        val cell = with(density) { StickerDotGap.toPx() }.roundToInt().coerceAtLeast(2)
        val tile = ImageBitmap(cell, cell)
        val paint = Paint().apply { color = c.dot; isAntiAlias = true }
        // radial-gradient(circle at 1px 1px, dot r, transparent r + 0.5px): solid to r and faded out 0.5px
        // further; drawn solid to the middle of that fade.
        val at = with(density) { 1.dp.toPx() }
        Canvas(tile).drawCircle(Offset(at, at), with(density) { (StickerDotRadius + 0.25.dp).toPx() }, paint)
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
    // The paper is drawn once into a layer of its own, kept as a picture until the size or the colours
    // change: the phone sends the whole screen to the GPU for every frame, and filling it with the dots
    // and the washes again on each frame of a scroll made the first frames after a pause miss their slot.
    return drawWithCache {
        val paper = obtainGraphicsLayer().apply {
            compositingStrategy = CompositingStrategy.Offscreen
            record {
                drawRect(c.bg)
                drawRect(dots)
                // radial-gradient(60% 34% at 100% 0%, --k-glow-1, transparent 72%)
                wash(Offset(size.width, 0f), 0.6f, 0.34f, c.accent.copy(alpha = c.glow1))
                // radial-gradient(60% 30% at 0% 100%, --k-glow-2, transparent 72%), light only
                if (c.glow2.alpha > 0f) wash(Offset(0f, size.height), 0.6f, 0.3f, c.glow2)
            }
        }
        onDrawBehind { drawLayer(paper) }
    }
}

/** An elliptical glow of [color] at [center], [rx] of the width across and [ry] of the height down, gone by 72%. */
private fun DrawScope.wash(center: Offset, rx: Float, ry: Float, color: Color) {
    val w = size.width * rx
    val h = size.height * ry
    scale(1f, h / w, pivot = center) {
        drawCircle(Brush.radialGradient(0f to color, 0.72f to Color.Transparent, center = center, radius = w), radius = w, center = center)
    }
}

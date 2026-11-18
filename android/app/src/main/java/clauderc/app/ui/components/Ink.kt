package clauderc.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.onPresses
import kotlinx.coroutines.Job

// Drawing helpers of 贴纸: ink outlines, hard offset shadows and highlighter strokes.

/**
 * 贴纸's ink-outlined surface (border: [border] solid ink; box-shadow: 0 [drop] 0 ink): a hard
 * shadow [drop] below, the [fill], the content clipped to the corners and an ink border inside the
 * edge. [top] and [bottom] false square that end off and leave out its border and the shadow, for the
 * middle items of a group drawn as one card. With a [source] the shadow drops away while it is
 * pressed, in step with pressShrink sinking the element onto it. [bottomStart] and [bottomEnd] give
 * those corners their own radius, as a chat bubble's tail.
 */
fun Modifier.sticker(
    fill: Color,
    ink: Color,
    corner: Dp,
    border: Dp = 2.dp,
    drop: Dp = 4.dp,
    source: InteractionSource? = null,
    top: Boolean = true,
    bottom: Boolean = true,
    bottomStart: Dp? = null,
    bottomEnd: Dp? = null,
): Modifier = this then StickerElement(fill, ink, corner, border, drop, source, top, bottom, bottomStart, bottomEnd)

private data class StickerElement(
    val fill: Color,
    val ink: Color,
    val corner: Dp,
    val border: Dp,
    val drop: Dp,
    val source: InteractionSource?,
    val top: Boolean,
    val bottom: Boolean,
    val bottomStart: Dp?,
    val bottomEnd: Dp?,
) : ModifierNodeElement<StickerNode>() {
    override fun create() = StickerNode(this)
    override fun update(node: StickerNode) = node.update(this)
}

private class StickerNode(private var spec: StickerElement) : Modifier.Node(), DrawModifierNode {
    private val pushed = Animatable(0f)
    private var job: Job? = null

    override fun onAttach() = follow()

    fun update(next: StickerElement) {
        val newSource = next.source != spec.source
        spec = next
        if (newSource && isAttached) follow()
        invalidateDraw()
    }

    private fun follow() {
        job?.cancel()
        val source = spec.source ?: return
        job = coroutineScope.onPresses(source, { pushed.animateTo(1f, Motion.push()) }, { pushed.animateTo(0f, Motion.push()) })
    }

    override fun ContentDrawScope.draw() {
        val s = spec
        val w = size.width
        val h = size.height
        val b = s.border.toPx()
        val cap = if (s.top && s.bottom) minOf(w, h) / 2 else minOf(w / 2, h)
        fun radius(d: Dp) = minOf(d.toPx(), cap)
        val r = radius(s.corner)
        val tr = if (s.top) r else 0f
        val bl = if (s.bottom) s.bottomStart?.let(::radius) ?: r else 0f
        val br = if (s.bottom) s.bottomEnd?.let(::radius) ?: r else 0f
        val outer = shape(0f, 0f, w, h, tr, tr, br, bl)
        val d = s.drop.toPx() * (1f - pushed.value)
        // Like CSS, the shadow shows only outside the box, so a see-through fill does not turn grey over it.
        if (s.bottom && d > 0f) clipPath(outer, ClipOp.Difference) { translate(top = d) { drawPath(outer, s.ink) } }
        drawPath(outer, s.fill)
        clipPath(outer) { this@draw.drawContent() }
        if (b > 0f) {
            fun inset(x: Float) = (x - b).coerceAtLeast(0f)
            val inner = shape(b, if (s.top) b else 0f, w - b, if (s.bottom) h - b else h, inset(tr), inset(tr), inset(br), inset(bl))
            val ring = Path().apply {
                fillType = PathFillType.EvenOdd
                addPath(outer)
                addPath(inner)
            }
            drawPath(ring, s.ink)
        }
    }

    private fun shape(l: Float, t: Float, r: Float, b: Float, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) = Path().apply {
        addRoundRect(RoundRect(l, t, r, b, CornerRadius(topLeft), CornerRadius(topRight), CornerRadius(bottomRight), CornerRadius(bottomLeft)))
    }
}

/**
 * An ink ring [ring] wide just outside the box, and a hard shadow of the same ink [drop] below it
 * (box-shadow: 0 0 0 ring ink, 0 drop 0 ring ink). The box's own content covers the middle.
 */
fun Modifier.inkFrame(ink: Color, corner: Dp, ring: Dp, drop: Dp): Modifier = drawBehind {
    val r = ring.toPx()
    val size = Size(size.width + 2 * r, size.height + 2 * r)
    val radius = CornerRadius(corner.toPx() + r)
    if (drop.value > 0f) drawRoundRect(ink, Offset(-r, -r + drop.toPx()), size, radius)
    drawRoundRect(ink, Offset(-r, -r), size, radius)
}

/** A highlighter stroke behind the lower part of the box, from [from] to [to] of its height. */
fun Modifier.highlighter(color: Color, from: Float = 0.58f, to: Float = 0.92f): Modifier = drawBehind {
    drawRect(color, Offset(0f, size.height * from), Size(size.width, size.height * (to - from)))
}

/** Text and icons on 贴纸's yellow stickers (the picked tab): ink-dark in both light and dark. */
val StickerOnYellow = Color(0xFF1E1B2E)

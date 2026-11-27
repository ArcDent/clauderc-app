package clauderc.app.chat

import android.content.ClipData
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.runtime.State
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import clauderc.app.ui.components.LocalSnack
import clauderc.app.ui.components.PopMenu
import clauderc.app.ui.components.PopMenuItem
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Symbols
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

// Links in chat bubbles (round 8): a thin underline in the text's own colour, and a tap opens a small
// menu under the stretch of link that was tapped, the whole address on its first line.

/** Whether the menu offers to open [url]: only web addresses with a host and mail addresses; anything else can only be copied. */
fun isOpenable(url: String): Boolean = Regex("^(https?://[^/?#\\s]|mailto:[^\\s])", RegexOption.IGNORE_CASE).containsMatchIn(url)

/**
 * The links of one text: where each runs, a box per line, from its last layout; where the last
 * finger went down; and the link whose menu is open, with the box it hangs from in the window.
 */
internal class TextLinks {
    class Spot(val url: String, val box: Rect, val baseline: Float, val range: IntRange)

    var spots: List<Spot> = emptyList()
    var coords: LayoutCoordinates? = null
    var down: Offset? = null
    var pressedRange by mutableStateOf<IntRange?>(null)
    var held by mutableStateOf(false)
    var open by mutableStateOf<String?>(null)
    var shown by mutableStateOf("")
    var at by mutableStateOf(IntRect.Zero)

    fun laidOut(layout: TextLayoutResult) {
        val text = layout.layoutInput.text
        val found = mutableListOf<Spot>()
        for (range in text.getLinkAnnotations(0, text.length)) {
            val url = (range.item as? LinkAnnotation.Clickable)?.tag ?: continue
            val span = range.start until range.end
            var line = -1
            var box = Rect.Zero
            for (i in range.start until minOf(range.end, text.length)) {
                val l = layout.getLineForOffset(i)
                val b = layout.getBoundingBox(i)
                if (l != line) {
                    if (line >= 0) found += Spot(url, box, layout.getLineBaseline(line), span)
                    line = l
                    box = b
                } else {
                    box = Rect(min(box.left, b.left), min(box.top, b.top), max(box.right, b.right), max(box.bottom, b.bottom))
                }
            }
            if (line >= 0) found += Spot(url, box, layout.getLineBaseline(line), span)
        }
        spots = found
    }

    /** Opens [url]'s menu under the line of it the finger went down on. */
    fun tapped(url: String) {
        val mine = spots.filter { it.url == url }
        val spot = down?.let { d -> mine.firstOrNull { d.y >= it.box.top - 2 && d.y <= it.box.bottom + 2 } } ?: mine.firstOrNull()
        val c = coords
        if (spot != null && c != null && c.isAttached) {
            val tl = c.localToWindow(spot.box.topLeft)
            val br = c.localToWindow(spot.box.bottomRight)
            at = IntRect(tl.x.toInt(), tl.y.toInt(), br.x.toInt(), br.y.toInt())
        }
        shown = url
        open = url
    }
}

/** Notes where fingers go down, how the text sits in the window, and draws the links' underlines 3dp under their baseline at 45%. */
internal fun Modifier.links(links: TextLinks, color: Color, press: State<Float>): Modifier = this
    .pointerInput(links) {
        awaitPointerEventScope {
            try {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.type == PointerEventType.Press) {
                        val point = e.changes.firstOrNull { it.pressed && !it.previousPressed }?.position
                        links.down = point
                        val hit = point?.let { p -> links.spots.firstOrNull { p in it.box } }
                        if (hit != null) links.pressedRange = hit.range
                        links.held = hit != null
                    } else if (e.type == PointerEventType.Release) {
                        links.held = false
                    }
                    if (links.held && e.type == PointerEventType.Move) {
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (final.changes.any { change ->
                            change.isConsumed || links.spots.none { it.range == links.pressedRange && change.position in it.box }
                        }) links.held = false
                    }
                }
            } finally {
                links.held = false
            }
        }
    }
    .onGloballyPositioned { links.coords = it }
    .drawBehind {
        for (s in links.spots) {
            val y = s.baseline + 3.dp.toPx()
            val opacity = if (s.range == links.pressedRange) 1f - 0.35f * press.value else 1f
            val line = color.copy(alpha = 0.45f * opacity)
            drawLine(line, Offset(s.box.left, y), Offset(s.box.right, y), strokeWidth = 1.dp.toPx())
        }
    }

/**
 * The menu of the link [links] has open (.lkm): 252dp wide, the whole address in grey mono, then
 * 「在浏览器中打开」 (「写邮件」 for mail) when it can be opened, and 「复制链接」, which says so in the snackbar.
 */
@Composable
internal fun LinkMenu(links: TextLinks) {
    if (links.shown.isEmpty()) return
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    val place = remember(links.at, density) {
        with(density) { NearLink(links.at, 6.dp.roundToPx(), 12.dp.roundToPx(), 16.dp.roundToPx()) { origin = it } }
    }
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val url = links.shown
    val mail = url.startsWith("mailto:", ignoreCase = true)
    PopMenu(links.open != null, onDismiss = { links.open = null }, place = place, origin = { origin }, width = 252.dp) {
        Text(
            url,
            Modifier.padding(start = Clauderc.spacing.sp4, end = Clauderc.spacing.sp4, top = 8.dp, bottom = 6.dp),
            color = Clauderc.colors.fg3,
            fontSize = 11.sp,
            fontFamily = MonoFamily,
            lineHeight = 1.55.em,
        )
        if (isOpenable(url)) {
            PopMenuItem(if (mail) "写邮件" else "在浏览器中打开", if (mail) Symbols.Mail else Symbols.OpenInBrowser, onClick = {
                links.open = null
                runCatching { uri.openUri(url) }.onFailure { snack?.show("没有能打开这个链接的 App") }
            })
        }
        PopMenuItem("复制链接", Symbols.ContentCopy, onClick = {
            links.open = null
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("链接", url))) }
            snack?.show("已复制链接")
        })
    }
}

/**
 * Puts the link menu [gap] under the tapped line of the link [at], its left edge at the link's and
 * [side] inside the window's sides; above the line when it would come within [bottom] of the window's
 * foot. [origin] learns the point it grows from: the link's start, on the side facing the link.
 */
internal class NearLink(
    private val at: IntRect,
    private val gap: Int,
    private val side: Int,
    private val bottom: Int,
    private val origin: (TransformOrigin) -> Unit = {},
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val x = at.left.coerceIn(side, maxOf(side, windowSize.width - w - side))
        val up = at.bottom + gap + h > windowSize.height - bottom
        val y = if (up) at.top - gap - h else at.bottom + gap
        if (w > 0) origin(TransformOrigin(((at.left - x + side).toFloat() / w).coerceIn(0f, 1f), if (up) 1f else 0f))
        return IntOffset(x, y)
    }
}

package clauderc.app.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import clauderc.app.data.UiStyle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercColors
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** How tall a tool's result box grows before it scrolls inside (.tool-detail.tbox). */
val ToolBoxMax = 232.dp

/**
 * The chat list as a tool detail sees it. The list is drawn bottom-up, so a detail that grows
 * would push the row above it up; [keepTop] scrolls by the same amount in the same frame, so the
 * row that was tapped stays put and what is below it moves instead, as in demo.html.
 */
@Stable
class ChatScroll(private val list: LazyListState?, val slack: Float = 0f) {
    /** The list's top and bottom edges in the root, from its last placement. */
    var top = 0f
    var bottom = Float.POSITIVE_INFINITY

    /**
     * Call it after the new heights are written: the list measures again at once, and
     * [Snapshot.sendApplyNotifications] lets that measure see them. Measured with the old
     * heights, a big shift would run past the item into the ones above and pin the list to its top.
     */
    fun keepTop(dy: Float) {
        if (dy == 0f || list == null) return
        Snapshot.sendApplyNotifications()
        list.dispatchRawDelta(dy)
    }
}

val LocalChatScroll = staticCompositionLocalOf { ChatScroll(null) }

/**
 * A tool row's detail folding open and shut the mockup's way (demo.html toggleTool). The detail
 * is measured whole and clipped to an animated height; both ways keep the tapped row in place and
 * move what is below it, and only grow or shrink what can be seen.
 */
@Stable
class Fold(expanded: Boolean) {
    /** Whether the user wants the detail open; the chevron follows it. */
    var expanded by mutableStateOf(expanded)
        private set
    /** Whether the detail is composed; it stays so until it has folded shut. */
    var open by mutableStateOf(expanded)
        private set
    /** True when the last [startOpen] composed the detail anew, so its rows fade in. */
    private var fresh = false

    /** The detail's height in px while it animates, -1 when it is as tall as its content. */
    private var shown by mutableFloatStateOf(-1f)
    var alpha by mutableFloatStateOf(1f)
        private set
    private var inFrom by mutableIntStateOf(0)
    private var inClock by mutableFloatStateOf(Float.MAX_VALUE)

    // Written during layout and placement, read when an animation starts.
    private var natural = 0
    private var coords: LayoutCoordinates? = null

    private var job: Job? = null

    /** Row [i]'s opacity as it fades in after an opening. */
    fun rowAlpha(i: Int): Float = if (i >= inFrom && i < inFrom + 40) Motion.rowIn(i - inFrom, inClock) else 1f

    /** How far row [i] still sits above its place as it fades in, 0 to 1 of 4dp. */
    fun rowLift(i: Int): Float = if (i >= inFrom && i < inFrom + 40) 1f - Motion.rowIn(i - inFrom, inClock) else 0f

    fun toggle(scope: CoroutineScope, scroll: ChatScroll) {
        job?.cancel()
        expanded = !expanded
        job = if (expanded) {
            startOpen()
            scope.launch { grow(scroll) }
        } else {
            scope.launch { shut(scroll) }
        }
    }

    /** Composes the detail at no height, or from where a fold-shut was stopped. */
    private fun startOpen() {
        fresh = !open
        if (!open) {
            shown = 0f
            alpha = 0f
            open = true
        }
    }

    private val current get() = if (shown >= 0f) shown else natural.toFloat()

    /** Up to the list's bottom edge, a little past it: what is lower cannot be seen growing. */
    private fun visible(scroll: ChatScroll, h: Float, atLeast: Float): Float {
        val top = coords?.takeIf { it.isAttached }?.positionInRoot()?.y ?: return h
        return min(h, max(atLeast, scroll.bottom - top + scroll.slack))
    }

    /** Waits for the frame that composes and measures what was just changed. */
    private suspend fun laidOut() {
        withFrameNanos {}
        withFrameNanos {}
    }

    private suspend fun grow(scroll: ChatScroll) = coroutineScope {
        val h0 = current
        val a0 = alpha
        val anew = fresh
        laidOut()
        if (anew) launch { rowsIn() }
        var last = h0
        animate(0f, 1f, animationSpec = Motion.enter()) { p, _ ->
            val h = h0 + (visible(scroll, natural.toFloat(), h0) - h0) * p
            shown = h
            alpha = a0 + (1f - a0) * p
            scroll.keepTop(h - last)
            last = h
        }
        shown = -1f
        alpha = 1f
        scroll.keepTop(natural - last)
    }

    /** Folds the detail shut on the same curve it opened on. */
    private suspend fun shut(scroll: ChatScroll) {
        val h0 = current
        // What hangs below the list's bottom edge goes at once, out of sight.
        val from = visible(scroll, h0, 0f)
        shown = from
        scroll.keepTop(from - h0)
        val a0 = alpha
        var last = from
        animate(1f, 0f, animationSpec = Motion.enter()) { p, _ ->
            val h = from * p
            shown = h
            alpha = a0 * p
            scroll.keepTop(h - last)
            last = h
        }
        open = false
        shown = -1f
        alpha = 1f
    }

    private suspend fun rowsIn() {
        inFrom = 0
        animate(0f, Motion.rowsInMillis.toFloat(), animationSpec = tween(Motion.rowsInMillis, easing = LinearEasing)) { v, _ -> inClock = v }
        inClock = Float.MAX_VALUE
    }

    /** Measures the detail whole and clips it to the animated height. */
    fun modifier(): Modifier = Modifier
        .graphicsLayer { alpha = this@Fold.alpha }
        .clipToBounds()
        .layout { m, c ->
            val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            natural = p.height
            val h = if (shown < 0f) p.height else shown.roundToInt().coerceIn(0, p.height)
            layout(p.width, h) { p.place(0, 0) }
        }
        .onPlaced { coords = it }

    companion object {
        val Saver = Saver<Fold, Boolean>(save = { it.expanded }, restore = { Fold(it) })
    }
}

/** Every tool row starts folded (tool view A); what the user opened stays open when the row scrolls back into view. */
@Composable
fun rememberFold(key: String): Fold = rememberSaveable(key, saver = Fold.Saver) { Fold(false) }

/** Background and text color of a diff row: the git diff page's add and del. */
fun patchTone(kind: RowKind, c: ClaudercColors): Pair<Color, Color> = when (kind) {
    RowKind.ADD -> c.addBg to c.addFg
    RowKind.DEL -> c.delBg to c.delFg
    else -> Color.Transparent to c.fg
}

private val PatchText = TextStyle(fontFamily = MonoFamily, fontSize = 11.5.sp, lineHeight = 1.7.em, fontFeatureSettings = "tnum")

/**
 * The diff view under an Edit, Write or Read row: a column of line numbers, the sign, the line.
 * All of it, in a box [ToolBoxMax] tall at most that scrolls inside; [fetching] while the whole
 * text of a cut result is on its way, said at the end with a spinner.
 */
@Composable
fun PatchDetail(fold: Fold, rows: List<PatchRow>, fetching: Boolean) {
    val sp = Clauderc.spacing
    Column(
        Modifier
            .padding(start = sp.sp2, end = sp.sp2, top = 2.dp, bottom = sp.sp2)
            .fillMaxWidth()
            .toolDetail()
            .toolBox()
            .padding(vertical = sp.sp2),
    ) {
        rows.forEachIndexed { i, r ->
            PatchLine(
                r,
                Modifier.graphicsLayer {
                    alpha = fold.rowAlpha(i)
                    translationY = -4.dp.toPx() * fold.rowLift(i)
                },
            )
        }
        if (fetching) Fetching(Modifier.padding(start = 56.dp, end = sp.sp3, top = sp.sp2))
    }
}

/** 「正在取全文」 after a 12dp spinner (.tmore while the whole text of a cut result is on its way). */
@Composable
fun Fetching(modifier: Modifier = Modifier) {
    val c = Clauderc.colors
    Row(modifier.testTag("fetching"), horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp2), verticalAlignment = Alignment.CenterVertically) {
        Spinner(size = 12.dp, color = c.fg3)
        Text("正在取全文", fontSize = Clauderc.fontSizes.caption, color = c.fg2)
    }
}

@Composable
private fun PatchLine(r: PatchRow, modifier: Modifier) {
    val c = Clauderc.colors
    if (r.kind == RowKind.GAP) {
        Text("⋯", modifier.padding(start = 56.dp), color = c.fg3, style = PatchText)
        return
    }
    val (bg, fg) = patchTone(r.kind, c)
    Row(modifier.fillMaxWidth().background(bg).padding(end = Clauderc.spacing.sp3).testTag("patchRow")) {
        Text(r.line.toString(), Modifier.width(40.dp), color = c.fg3, style = PatchText, textAlign = TextAlign.End)
        val sign = when (r.kind) {
            RowKind.ADD -> "+"
            RowKind.DEL -> "-"
            else -> ""
        }
        Text(sign, Modifier.width(16.dp), color = if (r.kind == RowKind.SAME) c.fg3 else fg, style = PatchText, textAlign = TextAlign.Center)
        Text(r.text.replace("\t", "    "), Modifier.weight(1f), color = fg, style = PatchText)
    }
}

/** The figures at the end of a diff row: +added and −deleted in the diff colors, or 「N 行」 for a read. */
@Composable
fun PatchStatText(stat: PatchStat) {
    val c = Clauderc.colors
    val style = TextStyle(fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, fontFeatureSettings = "tnum")
    Row(Modifier.padding(start = Clauderc.spacing.sp2), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (stat.read != null) {
            Text("${stat.read} 行", color = c.fg3, style = style)
        } else {
            if (stat.added > 0) Text("+${stat.added}", color = c.addFg, style = style)
            if (stat.deleted > 0) Text("−${stat.deleted}", color = c.delFg, style = style)
        }
    }
}

/**
 * The box a tool's detail sits in (.tool-detail): an 8dp grey tile; 贴纸 makes it a 14dp white tile
 * with a thin ink outline.
 */
@Composable
internal fun Modifier.toolDetail(): Modifier {
    val c = Clauderc.colors
    return if (Clauderc.style == UiStyle.Sticker) {
        clip(RoundedCornerShape(14.dp)).background(c.panel).border(1.5.dp, c.ink, RoundedCornerShape(14.dp))
    } else {
        clip(RoundedCornerShape(Clauderc.radii.sm)).background(c.surface)
    }
}

/**
 * A result box at most [ToolBoxMax] tall that scrolls inside (.tbox), with a 4dp thumb on its right
 * edge while there is more: 清玻 a faint rounded one, 格栅 a solid square one, 贴纸 an ink one.
 */
@Composable
internal fun Modifier.toolBox(state: ScrollState = rememberScrollState()): Modifier {
    val c = Clauderc.colors
    val (color, round) = when (Clauderc.style) {
        UiStyle.Clear -> c.fg.copy(alpha = 0.22f) to true
        UiStyle.Grid -> c.fg to false
        UiStyle.Sticker -> c.ink to true
    }
    return heightIn(max = ToolBoxMax)
        .scrollThumb(state, color, if (round) 2.dp else 0.dp)
        .verticalScroll(state)
        .testTag("toolBox")
}

private fun Modifier.scrollThumb(state: ScrollState, color: Color, corner: Dp): Modifier = drawWithContent {
    drawContent()
    val range = state.maxValue
    if (range <= 0 || range == Int.MAX_VALUE) return@drawWithContent
    val view = size.height
    val thumb = max(view * view / (view + range), 24.dp.toPx())
    val top = (view - thumb) * state.value / range
    val w = 4.dp.toPx()
    drawRoundRect(color, Offset(size.width - w - 2.dp.toPx(), top), Size(w, thumb), CornerRadius(corner.toPx()))
}

package clauderc.app.chat

import androidx.compose.foundation.border
import clauderc.app.data.UiStyle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
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

/** A long diff shows this many rows, then 「展开全部」. */
const val SHOW_LINES = 6

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
 * A tool row's detail folding open and shut the mockup's way (demo.html toggleTool, swapDetail
 * and collapseAll). The detail is measured whole and clipped to an animated height. Opening and
 * 「展开全部」 keep the row on top in place and only grow what can be seen, the rest added when it
 * ends, out of sight; 「收起」 keeps the block's bottom edge under the finger.
 */
@Stable
class Fold(expanded: Boolean, all: Boolean, decided: Boolean) {
    /** Whether the user wants the detail open; the chevron follows it. */
    var expanded by mutableStateOf(expanded)
        private set
    /** Whether the detail is composed; it stays so until it has folded shut. */
    var open by mutableStateOf(expanded)
        private set
    /** Whether a long diff shows all its rows rather than the first [SHOW_LINES]. */
    var all by mutableStateOf(all)
        private set
    /** False while the tool runs; the default, open or folded, is applied when its result comes in. */
    var decided = decided
    /** Set while 「展开全部」 waits for chat.toolOutput. */
    var wantAll = false

    /**
     * For a merged row: the line under its head, the latest call's path or command, that gives way
     * to the calls one by one. It is pulled up into the head's padding by [leadPull] px.
     */
    var hasLead = false
    var leadPull = 0
    private var leadH by mutableFloatStateOf(if (expanded) 0f else -1f)
    private var leadAlpha by mutableFloatStateOf(if (expanded) 0f else 1f)
    private var leadNatural = 0
    /** True when the last [startOpen] composed the detail anew, so its rows fade in. */
    private var fresh = false

    /** The detail's height in px while it animates, -1 when it is as tall as its content. */
    private var shown by mutableFloatStateOf(-1f)
    var alpha by mutableFloatStateOf(1f)
        private set
    private var inFrom by mutableIntStateOf(0)
    private var inClock by mutableFloatStateOf(Float.MAX_VALUE)
    private var outFrom by mutableIntStateOf(Int.MAX_VALUE)
    private var outLast by mutableIntStateOf(0)
    private var outClock by mutableFloatStateOf(-1f)
    private var moreIn by mutableFloatStateOf(1f)

    // Written during layout and placement, read when an animation starts.
    private var natural = 0
    private var coords: LayoutCoordinates? = null
    /** The row after the first [SHOW_LINES] and the 「收起」 line, for the height the block folds back to. */
    var seventh: LayoutCoordinates? = null
    var more: LayoutCoordinates? = null

    private var job: Job? = null

    /** Row [i]'s opacity: fading in after an opening, out before 「收起」 folds back. */
    fun rowAlpha(i: Int): Float {
        val inP = if (i >= inFrom && i < inFrom + 40) Motion.rowIn(i - inFrom, inClock) else 1f
        val outP = if (outClock >= 0f && i >= outFrom) 1f - Motion.rowOut(outLast - i, outClock) else 1f
        return inP * outP
    }

    /** How far row [i] still sits above its place as it fades in, 0 to 1 of 4dp. */
    fun rowLift(i: Int): Float = if (i >= inFrom && i < inFrom + 40) 1f - Motion.rowIn(i - inFrom, inClock) else 0f

    /** The opacity of the 「展开全部」 or 「收起」 line. */
    val moreAlpha: Float get() = if (outClock >= 0f) 1f - Motion.rowOut(0, outClock) else moreIn

    fun toggle(scope: CoroutineScope, scroll: ChatScroll) {
        job?.cancel()
        expanded = !expanded
        job = if (expanded) {
            startOpen()
            scope.launch { grow(scroll, keepTop = true) }
        } else {
            scope.launch { shut(scroll) }
        }
    }

    /** Opens a diff that just came in: it grows up from the bottom like a new reply. */
    fun reveal(scope: CoroutineScope, scroll: ChatScroll) {
        job?.cancel()
        expanded = true
        startOpen()
        job = scope.launch { grow(scroll, keepTop = false) }
    }

    fun showAll(scope: CoroutineScope, scroll: ChatScroll) {
        wantAll = false
        // Folded while the whole text was on its way: it shows all rows when opened again.
        if (!open) {
            all = true
            return
        }
        job?.cancel()
        if (shown < 0f) shown = natural.toFloat()
        all = true
        job = scope.launch { growAll(scroll) }
    }

    fun showLess(scope: CoroutineScope, scroll: ChatScroll, lastRow: Int) {
        job?.cancel()
        job = scope.launch { shutAll(scroll, lastRow) }
    }

    /** Composes the detail at no height, or from where a fold-shut was stopped. */
    private fun startOpen() {
        fresh = !open
        if (!open) {
            shown = 0f
            // A merged row's calls fade in row by row; a diff block fades in as a whole too.
            if (!hasLead) alpha = 0f
            open = true
        }
    }

    /** How much room the lead line takes at height [h]: less the part pulled into the head. */
    private fun leadRoom(h: Float) = max(0f, h - leadPull)

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

    private suspend fun grow(scroll: ChatScroll, keepTop: Boolean) = coroutineScope {
        val h0 = current
        val a0 = alpha
        val anew = fresh
        laidOut()
        if (anew) launch { rowsIn(0) }
        if (hasLead) launch { giveWay(scroll) }
        var last = h0
        animate(0f, 1f, animationSpec = Motion.enter()) { p, _ ->
            val to = if (keepTop) visible(scroll, natural.toFloat(), h0) else natural.toFloat()
            val h = h0 + (to - h0) * p
            shown = h
            alpha = a0 + (1f - a0) * p
            if (keepTop) scroll.keepTop(h - last)
            last = h
        }
        shown = -1f
        alpha = 1f
        if (keepTop) scroll.keepTop(natural - last)
    }

    /** The lead line folds away in durS as the calls come in under the head. */
    private suspend fun giveWay(scroll: ChatScroll) {
        val l0 = if (leadH >= 0f) leadH else leadNatural.toFloat()
        var last = leadRoom(l0)
        animate(l0, 0f, animationSpec = Motion.exit()) { v, _ ->
            leadH = v
            leadAlpha = if (leadNatural > 0) v / leadNatural else 0f
            scroll.keepTop(leadRoom(v) - last)
            last = leadRoom(v)
        }
    }

    /**
     * Folds the detail shut. A merged row's calls fold down to the height of its lead line, which then
     * takes their place and fades in, so nothing below moves at the swap.
     */
    private suspend fun shut(scroll: ChatScroll) {
        val h0 = current
        val floor = if (hasLead) leadRoom(leadNatural.toFloat()) else 0f
        // What hangs below the list's bottom edge goes at once, out of sight.
        val from = visible(scroll, h0, floor)
        shown = from
        scroll.keepTop(from - h0)
        val a0 = alpha
        var last = from
        animate(1f, 0f, animationSpec = Motion.enter()) { p, _ ->
            val h = floor + (from - floor) * p
            shown = h
            alpha = a0 * p
            scroll.keepTop(h - last)
            last = h
        }
        open = false
        shown = -1f
        alpha = 1f
        if (hasLead) {
            leadH = -1f
            animate(0f, 1f, animationSpec = Motion.enter()) { v, _ -> leadAlpha = v }
        }
    }

    private suspend fun growAll(scroll: ChatScroll) = coroutineScope {
        val h0 = current
        laidOut()
        launch { rowsIn(SHOW_LINES) }
        var last = h0
        animate(0f, 1f, animationSpec = Motion.enter()) { p, _ ->
            val h = h0 + (visible(scroll, natural.toFloat(), h0) - h0) * p
            shown = h
            scroll.keepTop(h - last)
            last = h
        }
        shown = -1f
        scroll.keepTop(natural - last)
    }

    /**
     * 「收起」: the rows past the first [SHOW_LINES] and the line itself fade out from the bottom up;
     * of a block whose top is far above the list, the part out of sight goes at once; the rest
     * folds back in durL. The list keeps the block's bottom edge where it is.
     */
    private suspend fun shutAll(scroll: ChatScroll, lastRow: Int) {
        val h0 = current
        val y7 = seventh?.takeIf { it.isAttached }?.positionInRoot()?.y
        val ym = more?.takeIf { it.isAttached }?.positionInRoot()?.y
        val h1 = if (y7 != null && ym != null) h0 - (ym - y7) else h0
        val top = (coords?.takeIf { it.isAttached }?.positionInRoot()?.y ?: scroll.top) - scroll.top
        val cut = min(h0 - h1, max(0f, -h1 - top))
        shown = h0
        outFrom = SHOW_LINES
        outLast = lastRow + 1
        animate(0f, Motion.rowsOutMillis.toFloat(), animationSpec = tween(Motion.rowsOutMillis, easing = LinearEasing)) { v, _ -> outClock = v }
        shown = h0 - cut
        animate(h0 - cut, h1, animationSpec = Motion.settle()) { h, _ -> shown = h }
        all = false
        outClock = -1f
        shown = -1f
        moreIn = 0f
        animate(0f, 1f, animationSpec = Motion.enter()) { v, _ -> moreIn = v }
    }

    private suspend fun rowsIn(from: Int) {
        inFrom = from
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

    /** The lead line's height and opacity; pulled up by [leadPull], clipped while it folds away. */
    fun leadModifier(): Modifier = Modifier
        .layout { m, c ->
            val p = m.measure(c)
            layout(p.width, max(0, p.height - leadPull)) { p.place(0, -leadPull) }
        }
        .graphicsLayer { alpha = leadAlpha }
        .clipToBounds()
        .layout { m, c ->
            val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            leadNatural = p.height
            val h = if (leadH < 0f) p.height else leadH.roundToInt().coerceIn(0, p.height)
            layout(p.width, h) { p.place(0, 0) }
        }

    companion object {
        val Saver = Saver<Fold, List<Boolean>>(
            save = { listOf(it.expanded, it.all, it.decided) },
            restore = { Fold(it[0], it[1], it[2]) },
        )
    }
}

@Composable
fun rememberFold(key: String, expanded: Boolean = false, decided: Boolean = true): Fold =
    rememberSaveable(key, saver = Fold.Saver) { Fold(expanded, all = false, decided) }

/** Background and text color of a diff row: the git diff page's add and del. */
fun patchTone(kind: RowKind, c: ClaudercColors): Pair<Color, Color> = when (kind) {
    RowKind.ADD -> c.addBg to c.addFg
    RowKind.DEL -> c.delBg to c.delFg
    else -> Color.Transparent to c.fg
}

private val PatchText = TextStyle(fontFamily = MonoFamily, fontSize = 11.5.sp, lineHeight = 1.7.em, fontFeatureSettings = "tnum")

/**
 * The diff view under an Edit, Write or Read row: a column of line numbers, the sign, the line.
 * Past [SHOW_LINES] rows it ends with 「展开全部」, which turns into 「收起」 once all are shown;
 * [fetching] while the whole text of a cut result is on its way.
 */
@Composable
fun PatchDetail(fold: Fold, rows: List<PatchRow>, total: Int?, more: Boolean, fetching: Boolean, onMore: () -> Unit, onLess: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val shown = if (fold.all) rows else rows.take(SHOW_LINES)
    Column(
        Modifier
            .padding(start = sp.sp2, end = sp.sp2, top = 2.dp, bottom = sp.sp2)
            .fillMaxWidth()
            .toolDetail()
            .padding(vertical = sp.sp2),
    ) {
        shown.forEachIndexed { i, r ->
            val m = Modifier.graphicsLayer {
                alpha = fold.rowAlpha(i)
                translationY = -4.dp.toPx() * fold.rowLift(i)
            }
            PatchLine(r, if (i == SHOW_LINES) m.onPlaced { fold.seventh = it } else m)
        }
        if (more) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .onPlaced { fold.more = it }
                    .graphicsLayer { alpha = fold.moreAlpha }
                    .clickable(enabled = !fetching, role = Role.Button) { if (fold.all) onLess() else onMore() }
                    .padding(start = 56.dp, end = sp.sp3, top = sp.sp2)
                    .testTag("patchMore"),
                horizontalArrangement = Arrangement.spacedBy(sp.sp2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (fetching) Spinner(size = 12.dp, color = c.fg3)
                val label = when {
                    fetching -> "正在取全文"
                    fold.all -> "收起"
                    total != null -> "展开全部 · 共 $total 行"
                    else -> "展开全部"
                }
                Text(label, fontSize = Clauderc.fontSizes.caption, color = c.fg2)
            }
        }
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

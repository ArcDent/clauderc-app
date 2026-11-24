package clauderc.app.chat

import android.content.ClipData
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.ui.components.LocalSnack
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercMotion
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.pressShrink
import clauderc.app.ui.theme.styled
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// A reply's blocks as round 8 of the mockup draws them (demo.html, .md, .cb and .tb).

/**
 * [blocks] in [color], the first [shown] characters of them while the reply types out: text a
 * character at a time, code inside its box from the start, a table whole once it is reached.
 */
@Composable
fun ReplyBlocks(blocks: List<MdBlock>, shown: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) { Blocks(blocks, shown, color) }
}

/** Blocks 8dp apart, a heading 16dp below what comes before it. */
@Composable
private fun Blocks(blocks: List<MdBlock>, shown: Int, color: Color) {
    val sp = Clauderc.spacing
    var left = shown
    for ((i, b) in blocks.withIndex()) {
        if (left <= 0) break
        val gap = when {
            i == 0 -> 0.dp
            b is MdBlock.Heading -> sp.sp4
            else -> sp.sp2
        }
        Block(b, left, color, Modifier.padding(top = gap))
        left -= b.shownLength()
    }
}

@Composable
private fun Block(b: MdBlock, shown: Int, color: Color, modifier: Modifier) {
    when (b) {
        is MdBlock.Paragraph -> RunsText(b.runs, shown, color, modifier)
        is MdBlock.Heading -> Heading(b, shown, color, modifier)
        is MdBlock.Quote -> Quote(b, shown, modifier)
        is MdBlock.ItemList -> ItemList(b, shown, color, modifier)
        is MdBlock.Code -> CodeBox(b, shown, modifier)
        is MdBlock.Table -> TableBox(b, modifier)
        is MdBlock.Math -> MathBox(b, shown, color, modifier)
    }
}

/** A bubble's text: 15sp on 1.55 lines. */
internal val BubbleText = TextStyle(fontSize = 15.sp, lineHeight = 1.55.em)

/**
 * [runs] styled in [color]: `code` in mono a size down, **bold** at 600, both keeping the colour; a
 * link opens [links]' menu when tapped, its stretch shaded 12% while pressed. A formula is inline
 * content written as its LaTeX (ReplyMath.kt) when [drawn] has its id, else that LaTeX in mono and [muted].
 */
internal fun styled(runs: List<MdRun>, color: Color, muted: Color, links: TextLinks, drawn: Set<String>): AnnotatedString = buildAnnotatedString {
    for ((i, r) in runs.withIndex()) {
        when (r.kind) {
            RunKind.TEXT -> append(r.text)
            RunKind.MATH ->
                if (mathId(i) in drawn) appendInlineContent(mathId(i), r.text)
                else withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = 0.86.em, color = muted)) { append(r.text) }
            RunKind.CODE -> withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = 0.86.em)) { append(r.text) }
            RunKind.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(r.text) }
            RunKind.LINK -> {
                val url = r.url.orEmpty()
                val link = LinkAnnotation.Clickable(url, TextLinkStyles(pressedStyle = SpanStyle(background = color.copy(alpha = 0.12f)))) { links.tapped(url) }
                withLink(link) { append(r.text) }
            }
        }
    }
}

/**
 * The first [shown] characters of [runs], their links underlined and opening a menu (ChatLinks.kt); a
 * formula shows whole once typing reached its end.
 */
@Composable
internal fun RunsText(runs: List<MdRun>, shown: Int, color: Color, modifier: Modifier = Modifier, style: TextStyle = BubbleText, softWrap: Boolean = true) {
    val links = remember { TextLinks() }
    val muted = Clauderc.colors.fg2
    val math = if (runs.any { it.kind == RunKind.MATH }) mathInline(runs, color, style.fontSize.takeIf { it.isSpecified } ?: BubbleText.fontSize) else emptyMap()
    // A formula the library gave no content for shows as written, not as plain text.
    val full = remember(runs, color, muted, math.keys) { styled(runs, color, muted, links, math.keys) }
    val cut = visibleLength(runs, shown)
    Box(modifier) {
        Text(
            if (cut >= full.length) full else full.subSequence(0, cut),
            Modifier.links(links, color),
            color = color,
            style = style,
            softWrap = softWrap,
            inlineContent = math,
            onTextLayout = links::laidOut,
        )
        LinkMenu(links)
    }
}

/**
 * A heading at 18, 16 or 15sp. 格栅 sets it in mono after a grey 「#」 run as long as its level;
 * 贴纸 sets it heavier.
 */
@Composable
private fun Heading(b: MdBlock.Heading, shown: Int, color: Color, modifier: Modifier) {
    val style = TextStyle(
        fontSize = when (b.level) { 1 -> 18.sp; 2 -> 16.sp; else -> 15.sp },
        lineHeight = 1.4.em,
        fontWeight = when (Clauderc.style) {
            UiStyle.Clear -> FontWeight.SemiBold
            UiStyle.Grid -> FontWeight.Bold
            UiStyle.Sticker -> FontWeight.ExtraBold
        },
        fontFamily = if (Clauderc.style == UiStyle.Grid) MonoFamily else null,
    )
    if (Clauderc.style != UiStyle.Grid) return RunsText(b.runs, shown, color, modifier, style)
    Row(modifier) {
        // Marks, like the list markers and the code head: not part of what is selected and copied.
        DisableSelection { Text("#".repeat(b.level) + " ", Modifier.alignByBaseline(), color = Clauderc.colors.fg3, style = style) }
        RunsText(b.runs, shown, color, Modifier.alignByBaseline(), style)
    }
}

/** A quote in the second grey behind a rule down its left: 2dp of surface2, 格栅 2dp of fg3, 贴纸 3dp of the accent. */
@Composable
private fun Quote(b: MdBlock.Quote, shown: Int, modifier: Modifier) {
    val c = Clauderc.colors
    val (rule, width) = when (Clauderc.style) {
        UiStyle.Clear -> c.surface2 to 2.dp
        UiStyle.Grid -> c.fg3 to 2.dp
        UiStyle.Sticker -> c.accent to 3.dp
    }
    Column(modifier.drawBehind { drawRect(rule, size = Size(width.toPx(), size.height)) }.padding(start = 12.dp)) {
        Blocks(b.blocks, shown, c.fg2)
    }
}

/**
 * A list: each item's marker in grey in a 1.4em column, its blocks beside it, items 2dp apart.
 * Numbers and dots end where the text starts; 格栅 marks a bullet with a mono 「-」 at the column's start.
 */
@Composable
private fun ItemList(b: MdBlock.ItemList, shown: Int, color: Color, modifier: Modifier) {
    val grid = Clauderc.style == UiStyle.Grid
    // Room for the widest number: 1.4em fits 「9.」, each further digit takes another 0.6em.
    val digits = if (b.ordered) (b.start + b.items.size - 1).toString().length else 1
    val column = (1.4f + 0.6f * (digits - 1)).em
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var left = shown
        for ((i, item) in b.items.withIndex()) {
            if (left <= 0) break
            Row(Modifier.fillMaxWidth()) {
                val dash = grid && !b.ordered
                DisableSelection {
                    Text(
                        if (b.ordered) "${b.start + i}." else if (dash) "-" else "•",
                        Modifier.alignByBaseline().width(with(LocalDensity.current) { (BubbleText.fontSize.value * column.value).sp.toDp() })
                            .padding(end = if (dash) 0.dp else 6.dp),
                        color = Clauderc.colors.fg3,
                        style = BubbleText.copy(fontFamily = if (dash) MonoFamily else null, textAlign = if (dash) TextAlign.Start else TextAlign.End),
                        softWrap = false,
                    )
                }
                // Lined up with the item's first line, whichever font its text falls back to.
                Column(Modifier.weight(1f).alignByBaseline()) { Blocks(item, left, color) }
            }
            left -= item.sumOf { it.shownLength() }
        }
    }
}

/** Reaches [by] past both sides of the width it is given (margin: 0 -by). */
private fun Modifier.bleed(by: Dp) = layout { measurable, constraints ->
    val px = by.roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + 2 * px, maxWidth = constraints.maxWidth + 2 * px) else constraints
    val p = measurable.measure(wide)
    layout((p.width - 2 * px).coerceAtLeast(0), p.height) { p.place(-px, 0) }
}

/**
 * A code block (.cb): a 30dp head with the language, then the code in mono, unwrapped, scrolling
 * sideways inside the box. 清玻 a 14dp box of the page's colour 6dp past the text on each side,
 * 格栅 a square box of surface inside the text in a 1dp text-coloured line, so the grey tells from a
 * white page, 贴纸 清玻's box ringed in ink. Tabs are 4 wide.
 */
@Composable
private fun CodeBox(b: MdBlock.Code, shown: Int, modifier: Modifier) {
    val c = Clauderc.colors
    val shape = if (Clauderc.style == UiStyle.Grid) RectangleShape else RoundedCornerShape(14.dp)
    val box = when (Clauderc.style) {
        UiStyle.Clear -> Modifier.bleed(6.dp).clip(shape).background(c.bg)
        UiStyle.Grid -> Modifier.clip(shape).background(c.surface).border(1.dp, c.fg, shape)
        UiStyle.Sticker -> Modifier.bleed(6.dp).clip(shape).background(c.bg).border(2.dp, c.ink, shape)
    }
    Column(modifier.fillMaxWidth().then(box).testTag("codeBlock")) {
        DisableSelection {
            Row(Modifier.fillMaxWidth().height(30.dp).padding(start = 12.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(b.lang, Modifier.weight(1f), color = c.fg3, fontSize = 11.sp, fontFamily = MonoFamily, softWrap = false)
                // Only once the code has typed out in full.
                if (shown >= b.code.length) CopyCodeButton(b.code)
            }
        }
        Text(
            b.code.take(shown).replace("\t", "    "),
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("code").padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            color = c.fg,
            style = TextStyle(fontFamily = MonoFamily, fontSize = 12.5.sp, lineHeight = 1.6.em),
            softWrap = false,
        )
    }
}

/**
 * The copy button in a code block's head (.cb-copy), 30dp: it copies the code as written and says
 * so itself, with no snackbar. The copy icon shrinks and fades while a tick draws itself left to right
 * and springs to size; 1.5s later the tick shrinks away and the copy icon grows back. Another tap starts over.
 */
@Composable
private fun CopyCodeButton(code: String) {
    val c = Clauderc.colors
    val m = Clauderc.motion
    val clipboard = LocalClipboard.current
    val press = remember { MutableInteractionSource() }
    val shape = if (Clauderc.style == UiStyle.Grid) RectangleShape else CircleShape
    val swap = remember { TickSwap() }
    var done by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("代码", code)))
        swap.reset()
        done = true
        coroutineScope {
            launch { swap.iconOut(m) }
            launch { swap.tickIn(m, (m.durS * 0.6f).toInt()) }
            delay(1_500)
            done = false
            swap.back(m)
        }
    }
    val tint = if (done) c.fg else c.fg2
    Box(
        Modifier
            .pressShrink(press)
            .size(30.dp)
            .clip(shape)
            .clickable(press, PressIndication(tint), role = Role.Button) { taps++ }
            .semantics { contentDescription = "复制代码" },
        contentAlignment = Alignment.Center,
    ) {
        swap.Icons(Symbols.ContentCopy.styled, tint, iconSize = 17.dp, tickSize = 18.dp)
    }
}

/**
 * An icon that gives way to a tick, which draws itself left to right and springs to size, and comes back: the
 * code block's copy button and the picture viewer's save button (.v-save). [iconShown] and [ticked] are how it starts.
 */
@Stable
internal class TickSwap(iconShown: Boolean = true, ticked: Boolean = false) {
    private val iconAlpha = Animatable(if (iconShown) 1f else 0f)
    private val iconScale = Animatable(if (iconShown) 1f else 0.4f)
    private val tickAlpha = Animatable(if (ticked) 1f else 0f)
    private val tickScale = Animatable(if (ticked) 1f else 0.7f)
    private val drawn = Animatable(if (ticked) 1f else 0f)

    /** Back to the icon at once. */
    suspend fun reset() {
        iconAlpha.snapTo(1f)
        iconScale.snapTo(1f)
        tickAlpha.snapTo(0f)
        tickScale.snapTo(0.7f)
        drawn.snapTo(0f)
    }

    /** The icon shrinks and fades away (durS, leaving curve). */
    suspend fun iconOut(m: ClaudercMotion) {
        val away = tween<Float>(m.durS, easing = m.easeEmphIn)
        coroutineScope {
            launch { iconAlpha.animateTo(0f, away) }
            launch { iconScale.animateTo(0.4f, away) }
        }
    }

    /** The tick draws itself and springs to size (durM), [delay] ms in. */
    suspend fun tickIn(m: ClaudercMotion, delay: Int) {
        coroutineScope {
            launch { drawn.animateTo(1f, tween(m.durM, delay, m.easeEmph)) }
            launch { tickAlpha.animateTo(1f, tween(m.durM, delay, m.easeSpring)) }
            launch { tickScale.animateTo(1f, tween(m.durM, delay, m.easeSpring)) }
        }
    }

    /** The tick shrinks away while the icon grows back, from [iconFrom] of its size when that is given. */
    suspend fun back(m: ClaudercMotion, iconFrom: Float? = null) {
        iconFrom?.let { iconScale.snapTo(it) }
        val away = tween<Float>(m.durS, easing = m.easeEmphIn)
        val back = tween<Float>(m.durM, (m.durS * 0.5f).toInt(), m.easeEmph)
        coroutineScope {
            launch { tickAlpha.animateTo(0f, away) }
            launch { tickScale.animateTo(0.6f, away) }
            launch { iconAlpha.animateTo(1f, back) }
            launch { iconScale.animateTo(1f, back) }
        }
    }

    /** [icon] and over it the tick (.cb-i2, .v-ok): one stroke on a 24-unit grid, drawn as far as it has got. */
    @Composable
    fun Icons(icon: ImageVector, tint: Color, iconSize: Dp, tickSize: Dp) {
        val stroke = if (Clauderc.style == UiStyle.Sticker) 2.4f else 1.9f
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize).graphicsLayer {
                alpha = iconAlpha.value
                scaleX = iconScale.value
                scaleY = iconScale.value
            },
        )
        Canvas(
            Modifier.size(tickSize).graphicsLayer {
                alpha = tickAlpha.value.coerceIn(0f, 1f)
                scaleX = tickScale.value
                scaleY = tickScale.value
            },
        ) {
            val part = Path()
            PathMeasure().apply {
                setPath(TickPath, false)
                getSegment(0f, length * drawn.value, part, true)
            }
            scale(size.width / 24f, pivot = Offset.Zero) {
                drawPath(part, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

private val TickPath = Path().apply {
    moveTo(5f, 12.6f)
    relativeLineTo(4.6f, 4.6f)
    lineTo(19.2f, 7.6f)
}

/**
 * A table (.tb), scrolling sideways inside the reply, its cells unwrapped: the header a size down in
 * the second grey, a line over each row. 格栅 sets it in mono with a heavy rule under the header;
 * 贴纸 rings it in ink and tints the header with the accent.
 */
@Composable
private fun TableBox(b: MdBlock.Table, modifier: Modifier) {
    val c = Clauderc.colors
    val look = Clauderc.style
    val rows = listOf(b.head) + b.rows
    val columns = rows.maxOf { it.size }
    val bodyStyle = TextStyle(fontSize = if (look == UiStyle.Grid) 12.sp else 13.5.sp, lineHeight = 1.5.em, fontFamily = if (look == UiStyle.Grid) MonoFamily else null)
    val headStyle = bodyStyle.copy(
        fontSize = Clauderc.fontSizes.caption,
        fontWeight = if (look == UiStyle.Sticker) FontWeight.Bold else FontWeight.Medium,
    )
    val headColor = if (look == UiStyle.Clear) c.fg2 else c.fg
    val rowLine = if (look == UiStyle.Sticker) 1.5.dp else 1.dp
    val frame = if (look == UiStyle.Sticker) RoundedCornerShape(12.dp).let { Modifier.clip(it).border(2.dp, c.ink, it) } else Modifier
    val tops = remember { RowTops() }
    Box(modifier.fillMaxWidth().let { if (look == UiStyle.Grid) it else it.bleed(6.dp) }.horizontalScroll(rememberScrollState()).testTag("table")) {
        Layout(
            content = {
                for ((r, row) in rows.withIndex()) {
                    for (col in 0 until columns) {
                        RunsText(
                            row.getOrNull(col).orEmpty(),
                            Int.MAX_VALUE,
                            if (r == 0) headColor else c.fg,
                            Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                            if (r == 0) headStyle else bodyStyle,
                            softWrap = false,
                        )
                    }
                }
            },
            modifier = frame.drawBehind {
                val t = tops.at
                if (t.size < 2) return@drawBehind
                when (look) {
                    UiStyle.Sticker -> drawRect(c.accent.copy(alpha = 0.22f), size = Size(size.width, t[1].toFloat()))
                    UiStyle.Grid -> drawRect(c.fg, Offset(0f, t[1] - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
                    UiStyle.Clear -> {}
                }
                for (r in 1 until t.size - 1) drawRect(c.line, Offset(0f, t[r].toFloat()), Size(size.width, rowLine.toPx()))
            },
        ) { cells, _ ->
            // Each column as wide as its widest cell; a row as tall as it takes for its cells to share a baseline.
            val widths = IntArray(columns)
            cells.forEachIndexed { i, m -> widths[i % columns] = maxOf(widths[i % columns], m.maxIntrinsicWidth(Constraints.Infinity)) }
            val placed = cells.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i % columns])) }
            fun baseline(p: Placeable) = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
            val at = IntArray(rows.size + 1)
            val ys = IntArray(placed.size)
            for (r in rows.indices) {
                val row = placed.subList(r * columns, (r + 1) * columns)
                val above = row.maxOf(::baseline)
                val below = row.maxOf { it.height - baseline(it) }
                row.forEachIndexed { k, p -> ys[r * columns + k] = at[r] + above - baseline(p) }
                at[r + 1] = at[r] + above + below
            }
            tops.at = at
            val xs = widths.runningFold(0) { x, w -> x + w }
            layout(xs.last(), at.last()) { placed.forEachIndexed { i, p -> p.place(xs[i % columns], ys[i]) } }
        }
    }
}

/** Where a table's rows start and its last one ends, from its last layout; its lines are drawn there. */
private class RowTops {
    var at = IntArray(0)
}

/**
 * Lets a bubble's text be selected with a long press once [enabled], that is once it has typed out
 * (demo.html .bubble ::selection): 28% of the accent on a reply, 36% of the page colour on the user's
 * black or white bubble, 28% of the text colour on 贴纸's accent one. Copying the selection says
 * 「已复制」 in the snackbar; the code and link buttons inside copy without it.
 */
@Composable
internal fun BubbleSelection(enabled: Boolean, mine: Boolean, content: @Composable () -> Unit) {
    // A reply goes in and out of the selection container as it starts and stops typing; moved, not
    // composed again, it keeps its code and tables where they were scrolled and its measured formulas.
    val body = remember { movableContentOf { inner: @Composable () -> Unit -> inner() } }
    if (!enabled) return body(content)
    val c = Clauderc.colors
    val tint = when {
        !mine -> c.accent.copy(alpha = 0.28f)
        Clauderc.style == UiStyle.Sticker -> c.onAccent.copy(alpha = 0.28f)
        else -> c.bg.copy(alpha = 0.36f)
    }
    val plain = LocalClipboard.current
    val snack = LocalSnack.current
    val telling = remember(plain, snack) { TellingClipboard(plain) { snack?.show("已复制") } }
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(c.accent, tint), LocalClipboard provides telling) {
        SelectionContainer {
            CompositionLocalProvider(LocalClipboard provides plain) { body(content) }
        }
    }
}

/** [inner], telling [copied] each time something is put on it. */
private class TellingClipboard(private val inner: Clipboard, private val copied: () -> Unit) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = inner.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        inner.setClipEntry(clipEntry)
        if (clipEntry != null) copied()
    }

    override val nativeClipboard get() = inner.nativeClipboard
}

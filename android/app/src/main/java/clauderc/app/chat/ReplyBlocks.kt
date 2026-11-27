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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.selection.rememberSelectionState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// A reply's blocks as round 8 of the mockup draws them (demo.html, .md, .cb and .tb).

/** Replies longer than this are split across adjacent list items so Compose does not measure them whole. */
const val SPLIT_REPLY_CHARS = 6_000

/** Target visible character budget per chunk once a reply crosses [SPLIT_REPLY_CHARS]. */
const val REPLY_CHUNK_CHARS = 3_000

/**
 * Partitions top-level [blocks] into contiguous index ranges for lazy list rendering. Replies up to
 * [SPLIT_REPLY_CHARS] stay in one chunk so selection spans the whole reply; longer ones break once
 * a chunk reaches [REPLY_CHUNK_CHARS], giving any oversized single block its own chunk intact.
 */
fun replyChunks(blocks: List<MdBlock>): List<IntRange> {
    if (blocks.isEmpty()) return emptyList()
    if (blocks.sumOf { it.shownLength() } <= SPLIT_REPLY_CHARS) return listOf(blocks.indices)
    val out = mutableListOf<IntRange>()
    var start = 0
    var chars = 0
    for ((i, b) in blocks.withIndex()) {
        val len = b.shownLength()
        if (len > REPLY_CHUNK_CHARS) {
            if (chars > 0) {
                out += start until i
                start = i
            }
            // Keep leading zero-length blocks attached so no chunk has zero visible length.
            out += start..i
            start = i + 1
            chars = 0
            continue
        }
        chars += len
        if (chars >= REPLY_CHUNK_CHARS) {
            out += start..i
            start = i + 1
            chars = 0
        }
    }
    if (start < blocks.size) {
        if (chars > 0 || out.isEmpty()) out += start..blocks.lastIndex
        else out[out.lastIndex] = out.last().first..blocks.lastIndex
    }
    return out
}

/**
 * [blocks] in [color], the first [shown] characters of them while the reply types out: text a
 * character at a time, code inside its box from the start, a table whole once it is reached.
 * [leadingGap] keeps the inter-block top gap on continuation chunks of a split reply.
 */
@Composable
fun ReplyBlocks(
    blocks: List<MdBlock>,
    shown: Int,
    color: Color,
    modifier: Modifier = Modifier,
    leadingGap: Boolean = false,
) {
    Column(modifier) { Blocks(blocks, shown, color, leadingGap) }
}

/** Blocks 12dp apart, a heading 18dp below what comes before it (demo.html round 16). */
@Composable
private fun Blocks(blocks: List<MdBlock>, shown: Int, color: Color, leadingGap: Boolean = false) {
    var left = shown
    for ((i, b) in blocks.withIndex()) {
        if (left <= 0) break
        val gap = when {
            i == 0 && !leadingGap -> 0.dp
            b is MdBlock.Heading -> 18.dp
            else -> 12.dp
        }
        Block(b, left, color, Modifier.padding(top = gap))
        left -= b.shownLength()
    }
}

@Composable
private fun Block(b: MdBlock, shown: Int, color: Color, modifier: Modifier) {
    when (b) {
        is MdBlock.Paragraph -> RunsText(b.runs, shown, color, modifier, style = BubbleText, promoteMath = true)
        is MdBlock.Heading -> Heading(b, shown, color, modifier)
        is MdBlock.Quote -> Quote(b, shown, modifier)
        is MdBlock.ItemList -> ItemList(b, shown, color, modifier)
        is MdBlock.Code -> CodeBox(b, shown, modifier)
        is MdBlock.Table -> TableBox(b, modifier)
        is MdBlock.Math -> MathBox(b, shown, color, modifier)
    }
}

/** A reply's body text: 16sp on 1.7 lines (demo.html round 16). */
internal val BubbleText = TextStyle(fontSize = 16.sp, lineHeight = 1.7.em)

/** The user's own bubble keeps its compact 15sp / 1.55 typography. */
private val UserBubbleText = TextStyle(fontSize = 15.sp, lineHeight = 1.55.em)

private const val CODE_ANNOTATION_TAG = "code"

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
                else withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = 0.86.em, color = muted, fontWeight = FontWeight.SemiBold.takeIf { r.bold })) { append(r.text) }
            RunKind.CODE -> withAnnotation(CODE_ANNOTATION_TAG, "") {
                withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = 0.86.em)) { append(r.text) }
            }
            RunKind.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(r.text) }
            RunKind.LINK -> {
                val url = r.url.orEmpty()
                val link = LinkAnnotation.Clickable(url, TextLinkStyles(pressedStyle = SpanStyle(background = color.copy(alpha = 0.12f)))) { links.tapped(url) }
                withLink(link) { append(r.text) }
            }
        }
    }
}

// Inline fractions, even continued ones, measure up to ~1.9em in text style and stay within the 1.7em
// line's leading; only math taller than 2.2em, such as \dfrac, would reach the next line's glyphs.
private const val TALL_INLINE_MATH_EM = 2.2f

private sealed interface RunsSegment {
    val length: Int
    data class Inline(val runs: List<MdRun>, val leadingTrim: Int, override val length: Int) : RunsSegment
    data class PromotedMath(val run: MdRun, val display: Boolean, override val length: Int = run.text.length) : RunsSegment
}

// Matrix environments, tall inline formulas (>2.2em) and formulas wider than the line are promoted
// onto their own scrollable line while keeping surrounding runs in order and preserving typing lengths.
private fun splitPromotedRuns(
    runs: List<MdRun>,
    inlineMath: Map<String, InlineTextContent>,
    availPx: Float,
    fontSizePx: Float,
    density: Density,
): List<RunsSegment> {
    val out = mutableListOf<RunsSegment>()
    val buf = mutableListOf<MdRun>()
    fun flush(afterPromoted: Boolean, beforePromoted: Boolean) {
        if (buf.isEmpty()) return
        val origLen = buf.shownLength()
        var leadingTrim = 0
        val trimmed = buf.toMutableList()
        if (afterPromoted) {
            while (trimmed.isNotEmpty() && (trimmed.first().kind == RunKind.TEXT || trimmed.first().kind == RunKind.BOLD)) {
                val first = trimmed.first()
                val stripped = first.text.trimStart(' ', '\t', '\n')
                leadingTrim += first.text.length - stripped.length
                if (stripped.isEmpty()) trimmed.removeAt(0)
                else {
                    trimmed[0] = first.copy(text = stripped)
                    break
                }
            }
        }
        if (beforePromoted) {
            while (trimmed.isNotEmpty() && (trimmed.last().kind == RunKind.TEXT || trimmed.last().kind == RunKind.BOLD)) {
                val last = trimmed.last()
                val stripped = last.text.trimEnd('\n')
                if (stripped.isEmpty()) trimmed.removeAt(trimmed.lastIndex)
                else {
                    trimmed[trimmed.lastIndex] = last.copy(text = stripped)
                    break
                }
            }
        }
        out += RunsSegment.Inline(trimmed, leadingTrim, origLen)
        buf.clear()
    }
    var hasPromoted = false
    val maxInlineHeightPx = fontSizePx * TALL_INLINE_MATH_EM
    for ((i, r) in runs.withIndex()) {
        val multiline = r.kind == RunKind.MATH && isMultilineMath(r.tex)
        val content = if (!multiline && r.kind == RunKind.MATH) inlineMath[mathId(i)] else null
        val tooTall = content != null && with(density) { content.placeholder.height.toPx() } > maxInlineHeightPx
        val tooWide = content != null && !tooTall && availPx.isFinite() && with(density) { content.placeholder.width.toPx() } > availPx + 1f
        if (multiline || tooTall || tooWide) {
            flush(afterPromoted = hasPromoted, beforePromoted = true)
            out += RunsSegment.PromotedMath(r, display = multiline || tooTall)
            hasPromoted = true
        } else {
            buf += r
        }
    }
    flush(afterPromoted = hasPromoted, beforePromoted = false)
    return out
}

/** Per-line boxes of `code` runs from the last text layout, drawn as style-specific pills behind the text. */
private class CodeSpots {
    data class Spot(val left: Float, val right: Float, val baseline: Float)
    var spots by mutableStateOf<List<Spot>>(emptyList())

    fun laidOut(layout: TextLayoutResult) {
        val text = layout.layoutInput.text
        val annotations = text.getStringAnnotations(CODE_ANNOTATION_TAG, 0, text.length)
        if (annotations.isEmpty()) {
            if (spots.isNotEmpty()) spots = emptyList()
            return
        }
        val found = mutableListOf<Spot>()
        for (range in annotations) {
            var line = -1
            var left = 0f
            var right = 0f
            for (i in range.start until minOf(range.end, text.length)) {
                val l = layout.getLineForOffset(i)
                val b = layout.getBoundingBox(i)
                if (l != line) {
                    if (line >= 0) found += Spot(left, right, layout.getLineBaseline(line))
                    line = l
                    left = b.left
                    right = b.right
                } else {
                    left = minOf(left, b.left)
                    right = maxOf(right, b.right)
                }
            }
            if (line >= 0) found += Spot(left, right, layout.getLineBaseline(line))
        }
        if (spots != found) spots = found
    }
}

/**
 * The first [shown] characters of [runs], their links underlined and opening a menu (ChatLinks.kt); a
 * formula shows whole once typing reached its end. When [promoteMath] is set, matrix environments and
 * formulas wider than the line promote onto their own scrollable line.
 */
@Composable
internal fun RunsText(
    runs: List<MdRun>,
    shown: Int,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = UserBubbleText,
    softWrap: Boolean = true,
    promoteMath: Boolean = false,
) {
    if (!promoteMath || !softWrap || runs.none { it.kind == RunKind.MATH }) {
        InlineRunsText(runs, shown, color, modifier, style, softWrap)
        return
    }
    val fontSize = style.fontSize.takeIf { it.isSpecified } ?: BubbleText.fontSize
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.toPx() }
    val inlineMath = mathInline(runs, color, fontSize, excludeMultiline = true)
    BoxWithConstraints(modifier) {
        val availPx = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.POSITIVE_INFINITY
        val segments = remember(runs, inlineMath, availPx, fontSizePx, density) { splitPromotedRuns(runs, inlineMath, availPx, fontSizePx, density) }
        if (segments.size == 1 && segments.first() is RunsSegment.Inline) {
            InlineRunsText(runs, shown, color, Modifier, style, softWrap, premeasured = inlineMath)
        } else {
            Column(Modifier.fillMaxWidth()) {
                var left = shown
                var rendered = 0
                for (seg in segments) {
                    if (left <= 0) break
                    val topGap = if (rendered == 0) 0.dp else 6.dp
                    when (seg) {
                        is RunsSegment.Inline -> {
                            val visibleShown = left - seg.leadingTrim
                            if (seg.runs.isNotEmpty() && visibleShown > 0) {
                                InlineRunsText(seg.runs, visibleShown, color, Modifier.padding(top = topGap), style, softWrap)
                                rendered++
                            }
                        }
                        is RunsSegment.PromotedMath -> {
                            if (left < seg.length) break
                            PromotedMathBox(seg.run, seg.display, color, fontSize, Modifier.padding(top = topGap))
                            rendered++
                        }
                    }
                    left -= seg.length
                }
            }
        }
    }
}

@Composable
private fun InlineRunsText(
    runs: List<MdRun>,
    shown: Int,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = BubbleText,
    softWrap: Boolean = true,
    premeasured: Map<String, InlineTextContent>? = null,
) {
    val links = remember { TextLinks() }
    val codeSpots = remember { CodeSpots() }
    val c = Clauderc.colors
    val ui = Clauderc.style
    val muted = c.fg2
    val fontSize = style.fontSize.takeIf { it.isSpecified } ?: BubbleText.fontSize
    val math = premeasured ?: if (runs.any { it.kind == RunKind.MATH }) mathInline(runs, color, fontSize) else emptyMap()
    // A formula the library gave no content for shows as written, not as plain text.
    val full = remember(runs, color, muted, math.keys) { styled(runs, color, muted, links, math.keys) }
    val cut = visibleLength(runs, shown)
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.toPx() }
    Box(modifier) {
        Text(
            // A zero-length slice can retain a formula placeholder that Android never measures.
            if (cut == 0) AnnotatedString("") else if (cut >= full.length) full else full.subSequence(0, cut),
            Modifier
                .drawBehind {
                    val boxes = codeSpots.spots
                    if (boxes.isEmpty()) return@drawBehind
                    if (ui == UiStyle.Plate) {
                        val pad = 1.5.dp.toPx()
                        val stroke = 1.5.dp.toPx()
                        for (s in boxes) {
                            val y = s.baseline + 2.5.dp.toPx()
                            drawLine(c.line, Offset((s.left - pad).coerceAtLeast(0f), y), Offset((s.right + pad).coerceAtMost(size.width), y), strokeWidth = stroke)
                        }
                    } else {
                        val padX = 3.dp.toPx()
                        val radius = CornerRadius((if (ui == UiStyle.Clear) 5.dp else 6.dp).toPx())
                        val fill = when (ui) {
                            UiStyle.Tile -> c.card
                            UiStyle.Ink -> c.surface2
                            UiStyle.Clear -> c.surface
                            UiStyle.Plate -> Color.Transparent
                        }
                        for (s in boxes) {
                            val l = (s.left - padX).coerceAtLeast(0f)
                            val r = (s.right + padX).coerceAtMost(size.width)
                            val t = (s.baseline - fontSizePx * 0.86f).coerceAtLeast(0f)
                            val b = (s.baseline + fontSizePx * 0.26f).coerceAtMost(size.height)
                            if (r > l && b > t) {
                                drawRoundRect(fill, Offset(l, t), Size(r - l, b - t), radius)
                                if (ui == UiStyle.Ink) drawRoundRect(c.edge, Offset(l, t), Size(r - l, b - t), radius, style = Stroke(1.dp.toPx()))
                            }
                        }
                    }
                }
                .links(links, color),
            color = color,
            style = style,
            softWrap = softWrap,
            inlineContent = math,
            onTextLayout = {
                links.laidOut(it)
                codeSpots.laidOut(it)
            },
        )
        LinkMenu(links)
    }
}

/**
 * A heading at 18.5, 17 or 16sp (1.15em, 1.07em, 1em of 16sp body). 印版 sets it in mono after a
 * grey 「#」 run as long as its level.
 */
@Composable
private fun Heading(b: MdBlock.Heading, shown: Int, color: Color, modifier: Modifier) {
    val style = TextStyle(
        fontSize = when (b.level) { 1 -> 18.5.sp; 2 -> 17.sp; else -> 16.sp },
        lineHeight = 1.4.em,
        fontWeight = when (Clauderc.style) {
            UiStyle.Clear, UiStyle.Ink -> FontWeight.SemiBold
            UiStyle.Tile -> FontWeight.Bold
            UiStyle.Plate -> FontWeight.ExtraBold
        },
        fontFamily = if (Clauderc.style == UiStyle.Plate) MonoFamily else null,
    )
    if (Clauderc.style != UiStyle.Plate) return RunsText(b.runs, shown, color, modifier, style, promoteMath = true)
    Row(modifier.fillMaxWidth()) {
        DisableSelection { Text("#".repeat(b.level) + " ", Modifier.alignByBaseline(), color = Clauderc.colors.fg3, style = style) }
        RunsText(b.runs, shown, color, Modifier.weight(1f).alignByBaseline(), style, promoteMath = true)
    }
}

@Composable
private fun Quote(b: MdBlock.Quote, shown: Int, modifier: Modifier) {
    val c = Clauderc.colors
    val rule = when (Clauderc.style) {
        UiStyle.Plate -> c.fg
        UiStyle.Ink -> c.edge
        UiStyle.Clear, UiStyle.Tile -> c.line
    }
    Column(modifier.drawBehind { drawRect(rule, size = Size(2.dp.toPx(), size.height)) }.padding(start = 12.dp)) {
        Blocks(b.blocks, shown, c.fg2)
    }
}

@Composable
private fun ItemList(b: MdBlock.ItemList, shown: Int, color: Color, modifier: Modifier) {
    val plate = Clauderc.style == UiStyle.Plate
    val digits = if (b.ordered) (b.start + b.items.size - 1).toString().length else 1
    val column = (1.4f + 0.6f * (digits - 1)).em
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var left = shown
        for ((i, item) in b.items.withIndex()) {
            if (left <= 0) break
            Row(Modifier.fillMaxWidth()) {
                val dash = plate && !b.ordered
                DisableSelection {
                    Text(
                        if (b.ordered) "${b.start + i}." else if (dash) "-" else "•",
                        Modifier.alignByBaseline().width(with(LocalDensity.current) { (BubbleText.fontSize.value * column.value).sp.toDp() })
                            .padding(end = if (dash) 0.dp else 6.dp),
                        color = if (plate) Clauderc.colors.fg2 else Clauderc.colors.fg3,
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

private const val MaxCodeLineChars = 800
private const val MaxTableCellChars = 200
private const val MaxTableWidthPx = 24_000

private fun visibleCode(code: String, shown: Int): String =
    code.take(shown).lineSequence().joinToString("\n") { line ->
        val expanded = line.replace("\t", "    ")
        val count = expanded.codePointCount(0, expanded.length)
        if (count <= MaxCodeLineChars) expanded else {
            expanded.substring(0, expanded.offsetByCodePoints(0, MaxCodeLineChars)) + "…（已省略 ${count - MaxCodeLineChars} 字符）"
        }
    }

private fun visibleTableCell(runs: List<MdRun>): List<MdRun> {
    val count = runs.sumOf { it.text.codePointCount(0, it.text.length) }
    if (count <= MaxTableCellChars) return runs
    return buildList {
        var left = MaxTableCellChars
        for (run in runs) {
            if (left == 0) break
            val length = run.text.codePointCount(0, run.text.length)
            if (length <= left) {
                add(run)
                left -= length
            } else {
                val prefix = run.text.substring(0, run.text.offsetByCodePoints(0, left))
                val kind = if (run.kind == RunKind.MATH) RunKind.TEXT else run.kind
                add(run.copy(kind = kind, text = prefix))
                break
            }
        }
        add(MdRun(RunKind.TEXT, "…（已省略 ${count - MaxTableCellChars} 字符）"))
    }
}

/**
 * A code block (.cb): a 32dp head with the language, then the code in mono, unwrapped, scrolling
 * sideways inside the box, flush with the reply's content width (demo.html round 16).
 */
@Composable
private fun CodeBox(b: MdBlock.Code, shown: Int, modifier: Modifier) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val box = when (ui) {
        UiStyle.Clear -> Modifier.clip(RoundedCornerShape(14.dp)).background(c.surface)
        UiStyle.Plate -> Modifier
            .background(c.surface)
            .border(1.dp, c.line, RectangleShape)
            .drawWithContent {
                drawContent()
                drawRect(c.fg, size = Size(3.dp.toPx(), size.height))
            }
        UiStyle.Tile -> Modifier.clip(RoundedCornerShape(14.dp)).background(c.card)
        UiStyle.Ink -> {
            val shape = RoundedCornerShape(16.dp)
            Modifier.clip(shape).background(c.bg).border(1.dp, c.edge, shape)
        }
    }
    Column(modifier.fillMaxWidth().then(box).testTag("codeBlock")) {
        DisableSelection {
            Row(Modifier.fillMaxWidth().height(32.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    b.lang,
                    Modifier.weight(1f),
                    color = if (ui == UiStyle.Plate) c.fg2 else c.fg3,
                    fontSize = 11.5.sp,
                    fontWeight = if (ui == UiStyle.Plate) FontWeight.Bold else null,
                    fontFamily = MonoFamily,
                    softWrap = false,
                )
                // Only once the code has typed out in full.
                if (shown >= b.code.length) CopyCodeButton(b.code)
            }
        }
        Text(
            remember(b.code, shown) { visibleCode(b.code, shown) },
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("code").padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            color = c.fg,
            style = TextStyle(fontFamily = MonoFamily, fontSize = 12.5.sp, lineHeight = 1.6.em),
            softWrap = false,
        )
    }
}

@Composable
private fun CopyCodeButton(code: String) {
    val c = Clauderc.colors
    val m = Clauderc.motion
    val clipboard = LocalClipboard.current
    val press = remember { MutableInteractionSource() }
    val shape = if (Clauderc.style == UiStyle.Plate) RectangleShape else CircleShape
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
        val stroke = if (Clauderc.style == UiStyle.Plate) 2.2f else 1.9f
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

private val TickPath by lazy {
    Path().apply {
        moveTo(5f, 12.6f)
        relativeLineTo(4.6f, 4.6f)
        lineTo(19.2f, 7.6f)
    }
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
    val rows = remember(b) { (listOf(b.head) + b.rows).map { row -> row.map(::visibleTableCell) } }
    val columns = rows.maxOf { it.size }
    if (columns == 0) return
    val bodyStyle = TextStyle(fontSize = if (look == UiStyle.Plate) 12.sp else 13.5.sp, lineHeight = 1.5.em, fontFamily = if (look == UiStyle.Plate) MonoFamily else null)
    val headStyle = bodyStyle.copy(
        fontSize = Clauderc.fontSizes.caption,
        fontWeight = if (look == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.Medium,
    )
    val headColor = if (look == UiStyle.Clear) c.fg2 else c.fg
    val rowLine = 1.dp
    val frame = if (look == UiStyle.Plate) Modifier.border(1.5.dp, c.fg) else Modifier
    val tops = remember { RowTops() }
    Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("table")) {
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
            modifier = frame.testTag("tableContent").drawBehind {
                val t = tops.at
                if (t.size < 2) return@drawBehind
                when (look) {
                    UiStyle.Plate -> drawRect(c.fg, Offset(0f, t[1] - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
                    UiStyle.Tile, UiStyle.Ink -> drawRect(c.surface, size = Size(size.width, t[1].toFloat()))
                    UiStyle.Clear -> {}
                }
                for (r in 1 until t.size - 1) drawRect(c.line, Offset(0f, t[r].toFloat()), Size(size.width, rowLine.toPx()))
            },
        ) { cells, _ ->
            // Each column as wide as its widest cell; a row as tall as it takes for its cells to share a baseline.
            val widths = IntArray(columns)
            cells.forEachIndexed { i, m -> widths[i % columns] = maxOf(widths[i % columns], m.maxIntrinsicWidth(Constraints.Infinity)) }
            if (widths.sumOf { it.toLong() } > MaxTableWidthPx) {
                // Keep narrow columns intact; the wide ones share the remaining space.
                var remaining = MaxTableWidthPx
                val cap = widths.sortedArray().withIndex().firstNotNullOf { (i, width) ->
                    val share = remaining / (columns - i)
                    if (width > share) share else { remaining -= width; null }
                }
                for (i in widths.indices) widths[i] = minOf(widths[i], cap)
            }
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

private object CopyWholeReplyKey

/**
 * Lets a bubble's text be selected with a long press once [enabled], that is once it has typed out
 * (demo.html .bubble ::selection): 28% of the accent on a reply, 36% of the page colour on the user's
 * black or white bubble, 28% of the text colour on 贴纸's accent one. Copying the selection says
 * 「已复制」 in the snackbar; the code and link buttons inside copy without it. When [copyAll] is
 * given, the system selection menu gains 「复制整条」 to copy the reply's full Markdown source.
 */
@Composable
internal fun BubbleSelection(
    enabled: Boolean,
    mine: Boolean,
    copyAll: String? = null,
    content: @Composable () -> Unit,
) {
    // A reply goes in and out of the selection container as it starts and stops typing; moved, not
    // composed again, it keeps its code and tables where they were scrolled and its measured formulas.
    val body = remember { movableContentOf { inner: @Composable () -> Unit -> inner() } }
    if (!enabled) return body(content)
    val c = Clauderc.colors
    val tint = when {
        !mine -> c.accent.copy(alpha = 0.28f)
        Clauderc.style == UiStyle.Tile -> c.onAccent.copy(alpha = 0.28f)
        else -> c.bg.copy(alpha = 0.36f)
    }
    val plain = LocalClipboard.current
    val snack = LocalSnack.current
    val telling = remember(plain, snack) { TellingClipboard(plain) { snack?.show("已复制") } }
    val scope = rememberCoroutineScope()
    val selection = rememberSelectionState()
    val latestCopyAll by rememberUpdatedState(copyAll)
    // Add 「复制整条」 to assistant reply selection bars so even split replies can be copied whole as Markdown.
    val menu = if (copyAll == null) {
        Modifier
    } else {
        Modifier.appendTextContextMenuComponents {
            item(key = CopyWholeReplyKey, label = "复制整条") {
                val source = latestCopyAll
                if (source != null) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        telling.setClipEntry(ClipEntry(ClipData.newPlainText("回复", source)))
                    }
                }
                close()
                selection.clear()
            }
        }
    }
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(c.accent, tint), LocalClipboard provides telling) {
        SelectionContainer(state = selection, modifier = menu) {
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

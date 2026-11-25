package clauderc.app.chat

import clauderc.app.ui.components.CardCorner
import clauderc.app.ui.components.inkSurface
import clauderc.app.ui.components.plateBox
import clauderc.app.ui.components.tileSurface
import clauderc.app.ui.theme.page
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import clauderc.app.protocol.TermKey
import clauderc.app.protocol.TermScreen
import clauderc.app.sessions.display
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.BarTitle
import clauderc.app.ui.components.SnackHost
import clauderc.app.ui.components.SnackState
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.components.SymbolButton
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Lift
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.theme.PressKind
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.pressShrink
import clauderc.app.ui.theme.snapPressed
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp

/** One key of the key bar: what term.key sends, and its label or symbol. */
private class BarKey(val key: String, val label: String, val icon: ImageVector? = null)

private val ArrowKeys = listOf(
    BarKey(TermKey.ESC, "Esc"),
    BarKey(TermKey.TAB, "Tab"),
    BarKey(TermKey.LEFT, "左", Symbols.KeyboardArrowLeft),
    BarKey(TermKey.UP, "上", Symbols.KeyboardArrowUp),
    BarKey(TermKey.DOWN, "下", Symbols.KeyboardArrowDown),
    BarKey(TermKey.RIGHT, "右", Symbols.KeyboardArrowRight),
    BarKey(TermKey.BACKSPACE, "退格", Symbols.Backspace),
    BarKey(TermKey.ENTER, "回车", Symbols.KeyboardReturn),
)

private val DigitKeys = "1234567890".map { BarKey(it.toString(), it.toString()) }

/** The terminal's text size and line height, as in the mockup. */
private val TermFont = 11.sp
private const val TermLine = 1.4f

/**
 * The session's screen as its hidden attach shows it, in a monospace font and without colors,
 * long lines kept whole and scrolled sideways; the cursor is an inverted cell that does not
 * blink. Two rows of keys under it; text still goes through the chat's composer.
 */
@Composable
fun TerminalScreen(state: TermUiState, onBack: () -> Unit, onKey: (String) -> Unit, snack: SnackState = remember { SnackState() }) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val card = Clauderc.look.cards
    Box(Modifier.fillMaxSize().page()) {
        Column(Modifier.fillMaxSize()) {
            AppBar(
                navigation = { SymbolButton(Symbols.ArrowBack, "返回", onClick = onBack) },
                title = {
                    BarTitle("终端") {
                        Text(state.session?.let(::display).orEmpty(), fontSize = Clauderc.fontSizes.caption, color = c.fg2, maxLines = 1)
                    }
                },
            )
            Column(Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(
                            when (Clauderc.style) {
                                UiStyle.Tile -> Modifier.padding(start = sp.sp4, end = sp.sp4, top = sp.sp1, bottom = sp.sp3).tileSurface(c.card, CardCorner)
                                UiStyle.Ink -> Modifier.padding(start = sp.sp4, end = sp.sp4, top = sp.sp1, bottom = sp.sp3).inkSurface(c.card, c.edge, CardCorner)
                                UiStyle.Plate -> Modifier.padding(start = 18.dp, end = 18.dp, top = sp.sp2, bottom = sp.sp3).plateBox(c.bg, c.fg, 1.5.dp)
                                UiStyle.Clear -> Modifier
                            },
                        ),
                ) {
                    val screen = state.screen
                    if (screen != null) {
                        TermText(screen, Modifier.fillMaxSize())
                    } else if (state.loading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(color = c.fg3) }
                    }
                }
                Lift(visible = state.ended) {
                    Row(
                        // Drawn whole as its room opens, over the screen above: it paints the page behind it.
                        Modifier.fillMaxWidth().background(c.bg).padding(start = sp.sp4, end = sp.sp4, bottom = sp.sp2).testTag("termEnded"),
                        horizontalArrangement = Arrangement.spacedBy(sp.sp2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Symbols.PowerSettingsNew, contentDescription = null, tint = c.fg3, modifier = Modifier.size(16.dp))
                        Text(state.error ?: "会话已退出", fontSize = Clauderc.fontSizes.caption, color = c.fg3)
                    }
                }
                KeyBar(enabled = state.online && !state.ended && state.screen != null, onKey = onKey)
            }
        }
        SnackHost(snack, Modifier.align(Alignment.BottomCenter), bottom = 120.dp)
    }
}

/**
 * The screen's lines with the cursor cell inverted; the cursor may sit past a line's end. The ⏺ Claude
 * Code puts before each message would be drawn as a color emoji on the phone, so it shows as a plain ●.
 * A status line's ☀ would be too, though the phone's symbol font has a plain one, which the text
 * presentation selector asks for. Block elements are left transparent: [drawBlocks] fills their cells instead.
 */
fun termText(screen: TermScreen, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color): AnnotatedString {
    val text = termLines(screen, fg, bg)
    val out = AnnotatedString.Builder(text)
    var i = 0
    while (i < text.length) {
        if (blockShape(text[i]) == null) { i++; continue }
        val start = i
        while (i < text.length && blockShape(text[i]) != null) i++
        out.addStyle(SpanStyle(color = androidx.compose.ui.graphics.Color.Transparent), start, i)
    }
    return out.toAnnotatedString()
}

private fun plainSymbols(s: String) = s.replace('⏺', '●').replace("☀️", "☀").replace("☀", "☀︎")

private fun termLines(screen: TermScreen, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    screen.lines.forEachIndexed { r, line ->
        if (r > 0) append("\n")
        if (!screen.cursor || r != screen.cursorRow) {
            append(plainSymbols(line))
            return@forEachIndexed
        }
        // Columns count characters, a wide one being one; Kotlin strings count UTF-16 units. The selector
        // added for ☀ is no column of its own, so it goes in after the line is split at the cursor.
        val chars = line.codePoints().toArray().map { String(Character.toChars(it)) }
        val col = screen.cursorCol.coerceAtLeast(0)
        append(plainSymbols(chars.take(col).joinToString("")))
        repeat(col - chars.size) { append(" ") }
        withStyle(SpanStyle(color = bg, background = fg)) { append(chars.getOrNull(col)?.let(::plainSymbols) ?: " ") }
        append(plainSymbols(chars.drop(col + 1).joinToString("")))
    }
}

/**
 * The filled parts of a block element's cell, as left, top, right and bottom fractions, and how dark they
 * are; null for any other character. Claude Code draws its logo with these.
 */
class BlockShape(val rects: List<FloatArray>, val alpha: Float = 1f)

fun blockShape(ch: Char): BlockShape? {
    fun r(l: Float, t: Float, rt: Float, b: Float) = floatArrayOf(l, t, rt, b)
    val ul = r(0f, 0f, .5f, .5f)
    val ur = r(.5f, 0f, 1f, .5f)
    val ll = r(0f, .5f, .5f, 1f)
    val lr = r(.5f, .5f, 1f, 1f)
    return when (val c = ch.code) {
        0x2580 -> BlockShape(listOf(r(0f, 0f, 1f, .5f)))
        // Lower one eighth to the full block.
        in 0x2581..0x2588 -> BlockShape(listOf(r(0f, 1f - (c - 0x2580) / 8f, 1f, 1f)))
        // Left seven eighths to one eighth.
        in 0x2589..0x258F -> BlockShape(listOf(r(0f, 0f, (0x2590 - c) / 8f, 1f)))
        0x2590 -> BlockShape(listOf(r(.5f, 0f, 1f, 1f)))
        0x2591 -> BlockShape(listOf(r(0f, 0f, 1f, 1f)), .25f)
        0x2592 -> BlockShape(listOf(r(0f, 0f, 1f, 1f)), .5f)
        0x2593 -> BlockShape(listOf(r(0f, 0f, 1f, 1f)), .75f)
        0x2594 -> BlockShape(listOf(r(0f, 0f, 1f, 1f / 8)))
        0x2595 -> BlockShape(listOf(r(7f / 8, 0f, 1f, 1f)))
        0x2596 -> BlockShape(listOf(ll))
        0x2597 -> BlockShape(listOf(lr))
        0x2598 -> BlockShape(listOf(ul))
        0x2599 -> BlockShape(listOf(ul, ll, lr))
        0x259A -> BlockShape(listOf(ul, lr))
        0x259B -> BlockShape(listOf(ul, ur, ll))
        0x259C -> BlockShape(listOf(ul, ur, lr))
        0x259D -> BlockShape(listOf(ur))
        0x259E -> BlockShape(listOf(ur, ll))
        0x259F -> BlockShape(listOf(ur, ll, lr))
        else -> null
    }
}

/**
 * Fills the cells of the block elements at [at] over the whole line height, as a terminal does, so the
 * rows of Claude Code's logo meet without the gaps the font's own glyphs leave. Edges are snapped out
 * to whole pixels so neighboring cells do not show a seam.
 */
private fun DrawScope.drawBlocks(layout: TextLayoutResult, at: List<Int>, color: Color) {
    val text = layout.layoutInput.text.text
    for (i in at) {
        if (i >= text.length) continue
        val shape = blockShape(text[i]) ?: continue
        val box = layout.getBoundingBox(i)
        val line = layout.getLineForOffset(i)
        val top = layout.getLineTop(line)
        val h = layout.getLineBottom(line) - top
        for (f in shape.rects) {
            val l = floor(box.left + f[0] * box.width)
            val t = floor(top + f[1] * h)
            drawRect(color.copy(alpha = color.alpha * shape.alpha), Offset(l, t), Size(ceil(box.left + f[2] * box.width) - l, ceil(top + f[3] * h) - t))
        }
    }
}

@Composable
private fun TermText(screen: TermScreen, modifier: Modifier) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val text = remember(screen, c.fg, c.bg) { termText(screen, c.fg, c.bg) }
    val blocks = remember(text) { text.text.indices.filter { blockShape(text.text[it]) != null } }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val padY = if (Clauderc.look.cards) sp.sp3 else sp.sp2
    val padPx = with(LocalDensity.current) { padY.toPx() }
    val slackPx = with(LocalDensity.current) { 24.dp.toPx() }.roundToInt()
    // The scroll offset where the last auto-follow landed, or -1 before the first scroll.
    // Scrolling up away from it stops new output from yanking the view back down until the user returns.
    var pinned by remember { mutableIntStateOf(-1) }
    // Like the mockup, keep the last line with text in view: the input of a long chat, the top of a fresh dialog.
    // Where it ends comes from the laid out text, as each line is rounded up to whole pixels.
    val last = screen.lines.indexOfLast { it.isNotBlank() }
    BoxWithConstraints(modifier.testTag("termViewport")) {
        val viewport = constraints.maxHeight
        LaunchedEffect(last, viewport) {
            if (last < 0) return@LaunchedEffect
            val laid = snapshotFlow { layout }.first { it != null && it.lineCount > last }!!
            val target = (padPx + laid.getLineBottom(last) + padPx - viewport).roundToInt().coerceAtLeast(0)
            val tolerance = minOf(slackPx, (pinned / 2).coerceAtLeast(0))
            val following = pinned < 0 || vertical.value >= pinned - tolerance
            if (following) {
                vertical.scrollTo(target)
                pinned = vertical.value
            } else {
                pinned = target.coerceAtMost(vertical.maxValue)
            }
        }
        Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal)) {
            Text(
                text,
                modifier = Modifier
                    .padding(horizontal = if (Clauderc.look.cards) sp.sp3 else sp.sp4, vertical = padY)
                    .drawBehind { layout?.let { if (blocks.isNotEmpty()) drawBlocks(it, blocks, c.fg) } }
                    .testTag("termText"),
                fontFamily = MonoFamily,
                fontSize = TermFont,
                lineHeight = TermLine.em,
                color = c.fg,
                softWrap = false,
                onTextLayout = { layout = it },
            )
        }
    }
}

@Composable
private fun KeyBar(enabled: Boolean, onKey: (String) -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val card = Clauderc.look.cards
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (card) Modifier else Modifier.drawBehind { drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) })
            .navigationBarsPadding()
            .padding(start = sp.sp3, end = sp.sp3, top = if (card) 0.dp else sp.sp3, bottom = sp.sp4),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArrowKeys.forEach { KeyCap(it, 42.dp, 13.sp, enabled, onKey) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DigitKeys.forEach { KeyCap(it, 38.dp, 14.sp, enabled, onKey) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyCap(k: BarKey, height: androidx.compose.ui.unit.Dp, fontSize: androidx.compose.ui.unit.TextUnit, enabled: Boolean, onKey: (String) -> Unit) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val press = remember { MutableInteractionSource() }
    val plate = ui == UiStyle.Plate
    // 印版 hits a key like a typewriter's: sinks 2dp and turns solid ink on press.
    val sink by snapPressed(press, Clauderc.motion.easeSpring)
    val hit by snapPressed(press, LinearEasing)
    val face = if (plate) lerp(c.fg, c.bg, hit) else c.fg
    val cap = when (ui) {
        UiStyle.Plate -> Modifier.plateBox(lerp(c.bg, c.fg, hit), c.fg, 1.5.dp)
        UiStyle.Ink -> Modifier.inkSurface(c.float, c.edge, 10.dp)
        UiStyle.Tile -> Modifier.tileSurface(c.card, 10.dp)
        UiStyle.Clear -> Modifier.clip(RoundedCornerShape(Clauderc.radii.sm)).background(c.surface)
    }
    Box(
        Modifier
            .weight(1f)
            .pressShrink(press, PressKind.Still)
            .then(if (plate) Modifier.graphicsLayer { translationY = 2.dp.toPx() * sink } else Modifier)
            .height(height)
            .then(cap)
            .clickable(press, PressIndication(c.fg2), enabled = enabled, role = Role.Button) { onKey(k.key) }
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = k.label },
        contentAlignment = Alignment.Center,
    ) {
        if (k.icon != null) Icon(k.icon, contentDescription = null, tint = face, modifier = Modifier.size(20.dp))
        else Text(k.label, fontFamily = MonoFamily, fontSize = fontSize, fontWeight = if (plate) FontWeight.Bold else null, color = face)
    }
}

package clauderc.app.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import com.hrm.latex.parser.LatexParser
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme

// Formulas in replies (round 9): drawn in the KaTeX fonts in the text's own colour; a formula the
// library cannot draw shows as written, mono in the second grey. Both kinds are text to selection: an
// inline formula is inline content, a block one a text of one placeholder, whose alternate text is the
// formula as written, so copying gives the LaTeX.

/** Inline formulas at 1.06 of the text, block ones at 1.12, as the mockup sets the KaTeX size. */
private const val INLINE_SCALE = 1.06f
private const val BLOCK_SCALE = 1.12f

/** Whether the library can draw [tex]: its parser finds no error, such as a missing brace or an unknown command. */
internal fun canDraw(tex: String): Boolean = synchronized(drawable) {
    drawable.getOrPut(tex) { LatexParser().parseWithDiagnostics(tex).errors.isEmpty() }
}

// Replies are cut into blocks each time one scrolls into view; a formula is parsed once.
private val drawable = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 256
}

/** The id of the [i]th run's inline content. */
internal fun mathId(i: Int) = "math$i"

/** The measurer and settings formulas of one colour and size are drawn with. */
@Composable
private fun rememberMath(color: Color, size: TextUnit): Pair<LatexMeasurerState, LatexConfig> {
    val config = remember(color, size) { LatexConfig(fontSize = size, theme = LatexTheme.light(color, Color.Transparent)) }
    return rememberLatexMeasurer(config) to config
}

/** The inline content of [runs]' formulas the library can draw, set in text style. */
@Composable
internal fun mathInline(runs: List<MdRun>, color: Color, fontSize: TextUnit): Map<String, InlineTextContent> {
    val (measurer, config) = rememberMath(color, fontSize * INLINE_SCALE)
    return remember(runs, measurer, config) {
        runs.withIndex()
            .filter { (_, r) -> r.kind == RunKind.MATH && canDraw(r.tex) }
            .mapNotNull { (i, r) -> measurer.inlineContent("\\textstyle " + r.tex, config)?.let { mathId(i) to it } }
            .toMap()
    }
}

/**
 * How many characters of runs to show of [shown]: a formula shows whole or not at all, so a count that
 * ends inside one stops before it.
 */
internal fun visibleLength(runs: List<MdRun>, shown: Int): Int {
    var at = 0
    for (r in runs) {
        val end = at + r.text.length
        if (shown < end) return if (r.kind == RunKind.MATH) at else shown
        at = end
    }
    return at
}

/**
 * A formula on lines of its own, once typing reached its end: centred in the bubble, 格栅 at the start,
 * scrolling sideways in place when wider than the bubble.
 */
@Composable
internal fun MathBox(b: MdBlock.Math, shown: Int, color: Color, modifier: Modifier) {
    if (shown < b.written.length) return
    val align = if (Clauderc.style == UiStyle.Plate) Alignment.Start else Alignment.CenterHorizontally
    Column(modifier.fillMaxWidth(), horizontalAlignment = align) {
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp).testTag("math")) {
            val (measurer, config) = rememberMath(color, BubbleText.fontSize * BLOCK_SCALE)
            val content = remember(b.tex, measurer, config) { if (canDraw(b.tex)) measurer.inlineContent("\\displaystyle " + b.tex, config) else null }
            if (content != null) {
                // A placeholder cannot span a line break, so the copied LaTeX is on one line.
                Text(buildAnnotatedString { appendInlineContent("m", b.written.replace('\n', ' ')) }, inlineContent = mapOf("m" to content), softWrap = false, style = BubbleText)
            } else {
                Text(b.written, color = Clauderc.colors.fg2, fontFamily = MonoFamily, fontSize = 0.86.em, lineHeight = 1.6.em, softWrap = false)
            }
        }
    }
}

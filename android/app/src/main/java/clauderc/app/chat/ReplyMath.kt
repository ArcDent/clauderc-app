package clauderc.app.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercColors
import clauderc.app.ui.theme.MonoFamily
import com.hrm.latex.parser.LatexParser
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme

// Formulas in replies (round 9 & 16): drawn in the KaTeX fonts in the text's own colour; a formula
// the library cannot draw shows as written, mono in the second grey. Both kinds are text to selection:
// an inline formula is inline content, a standalone one a text of one placeholder, whose alternate
// text is the formula as written, so copying gives the LaTeX.

/** Inline, promoted-inline and block formula sizes as demo.html sets KaTeX. */
private const val INLINE_SCALE = 1.04f
private const val PROMOTED_INLINE_SCALE = 1.06f
private const val BLOCK_SCALE = 1.08f
private val FADE_WIDTH = 28.dp
private const val MAX_FORMULA_LENGTH = 2_000
private const val MAX_PLACEHOLDER_PX = 24_000f

// Matrix and multi-line environments cannot fit inside a text line without colliding with adjacent lines.
private val multilineMathEnvironment = Regex(
    """\\begin\s*\{\s*(?:[pbvBV]?matrix|smallmatrix|cases|dcases|rcases|aligned|align\*?|array|gathered|gather\*?|split|multline\*?|eqnarray\*?|subarray)\s*\}""",
)

/** Whether [tex] uses a matrix or multi-line environment that must be promoted onto its own line. */
internal fun isMultilineMath(tex: String): Boolean = multilineMathEnvironment.containsMatchIn(tex)

/** The reply surface colour behind formulas, used for horizontal-scroll edge fades (--ai-bg). */
internal fun replySurfaceColor(style: UiStyle, c: ClaudercColors): Color = when (style) {
    UiStyle.Clear, UiStyle.Plate -> c.bg
    UiStyle.Tile -> c.surface
    UiStyle.Ink -> c.card
}

private val drawableEnvironments = setOf(
    "matrix", "pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix",
    "matrix*", "pmatrix*", "bmatrix*", "Bmatrix*", "vmatrix*", "Vmatrix*", "smallmatrix", "smallmatrix*",
    "array", "subarray", "tabular", "align", "aligned", "align*", "gather", "gathered", "gather*", "flalign", "flalign*",
    "alignat", "alignat*", "alignedat", "split", "prooftree", "multline", "multline*", "multlined", "eqnarray", "eqnarray*",
    "subequations", "cases", "cases*", "dcases", "dcases*", "rcases", "rcases*", "RTL", "LTR",
)
private val reference = Regex("""\\\\|\\(?:eqref|ref)(?![A-Za-z])""")

private fun completeMath(tex: String): Boolean {
    if (reference.findAll(tex).any { it.value != "\\\\" }) return false
    val stack = ArrayDeque<String>()
    for (m in mathEnvironment.findAll(tex)) {
        val kind = m.groups[1]?.value ?: continue
        val name = m.groups[2]?.value ?: return false
        if (name !in drawableEnvironments) return false
        if (kind == "begin") stack.addLast(name)
        else if (stack.removeLastOrNull() != name) return false
    }
    return stack.isEmpty()
}

private fun styledMath(tex: String, display: Boolean) = (if (display) "\\displaystyle " else "\\textstyle ") + tex

/** Whether the library can draw [tex]: its parser finds no error, such as a missing brace or an unknown command. */
internal fun canDraw(tex: String, display: Boolean = false): Boolean {
    if (tex.length > MAX_FORMULA_LENGTH) return false
    val latex = styledMath(tex, display)
    return synchronized(drawable) {
        drawable.getOrPut(latex) { completeMath(tex) && LatexParser().parseWithDiagnostics(latex).errors.isEmpty() }
    }
}

// Replies are cut into blocks each time one scrolls into view; a formula is parsed once.
private val drawable = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 256
}

internal fun drawableContent(
    tex: String, density: Density, display: Boolean = false, measure: (String) -> InlineTextContent?,
): InlineTextContent? {
    if (!canDraw(tex, display)) return null
    return runCatching { measure(styledMath(tex, display)) }.getOrNull()?.takeIf { content ->
        with(density) {
            val width = content.placeholder.width.toPx()
            val height = content.placeholder.height.toPx()
            width.isFinite() && height.isFinite() && width <= MAX_PLACEHOLDER_PX && height <= MAX_PLACEHOLDER_PX
        }
    }
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
internal fun mathInline(runs: List<MdRun>, color: Color, fontSize: TextUnit, excludeMultiline: Boolean = false): Map<String, InlineTextContent> {
    val (measurer, config) = rememberMath(color, fontSize * INLINE_SCALE)
    val density = LocalDensity.current
    return remember(runs, measurer, config, density, excludeMultiline) {
        runs.withIndex()
            .filter { (_, r) -> r.kind == RunKind.MATH && (!excludeMultiline || !isMultilineMath(r.tex)) }
            .mapNotNull { (i, r) -> drawableContent(r.tex, density) { measurer.inlineContent(it, config) }?.let { mathId(i) to it } }
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
 * A formula on lines of its own, once typing reached its end: centred across all styles as in
 * demo.html, scrolling sideways with edge fades when wider than the reply.
 */
@Composable
internal fun MathBox(b: MdBlock.Math, shown: Int, color: Color, modifier: Modifier) {
    if (shown < b.written.length) return
    MathScrollBox(b.tex, b.written, color, display = true, centered = true, fontSize = BubbleText.fontSize, modifier = modifier)
}

/** An inline formula promoted onto its own line: display-centred for matrices, left-aligned when merely wide. */
@Composable
internal fun PromotedMathBox(run: MdRun, display: Boolean, color: Color, fontSize: TextUnit, modifier: Modifier) {
    MathScrollBox(run.tex, run.text, color, display = display, centered = display, fontSize = fontSize, modifier = modifier)
}

@Composable
private fun MathScrollBox(
    tex: String,
    written: String,
    color: Color,
    display: Boolean,
    centered: Boolean,
    fontSize: TextUnit,
    modifier: Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val threshold = with(density) { 2.dp.roundToPx() }
    // Read the scroll only through these two flags, so a drag recomposes when an end is reached, not every frame.
    val fadeLeft by remember(scroll, threshold) {
        derivedStateOf { scroll.maxValue != Int.MAX_VALUE && scroll.maxValue > threshold && scroll.value > threshold }
    }
    val fadeRight by remember(scroll, threshold) {
        derivedStateOf { scroll.maxValue != Int.MAX_VALUE && scroll.maxValue > threshold && scroll.value < scroll.maxValue - threshold }
    }
    val dur = Clauderc.motion.durS
    val leftAlpha by animateFloatAsState(if (fadeLeft) 1f else 0f, tween(dur), label = "mathFadeL")
    val rightAlpha by animateFloatAsState(if (fadeRight) 1f else 0f, tween(dur), label = "mathFadeR")
    val fadeBg = replySurfaceColor(Clauderc.style, Clauderc.colors)
    val scale = if (display) BLOCK_SCALE else PROMOTED_INLINE_SCALE
    val (measurer, config) = rememberMath(color, fontSize * scale)
    val content = remember(tex, display, measurer, config, density) {
        drawableContent(tex, density, display = display) { measurer.inlineContent(it, config) }
    }
    Box(
        modifier
            .fillMaxWidth()
            // Soft edge masks indicate horizontal overflow without adding text or icons, fading out at each scroll end.
            .drawWithContent {
                drawContent()
                val fadePx = minOf(FADE_WIDTH.toPx(), size.width / 2f)
                if (fadePx <= 0f) return@drawWithContent
                if (leftAlpha > 0.005f) {
                    drawRect(
                        Brush.horizontalGradient(listOf(fadeBg, fadeBg.copy(alpha = 0f)), startX = 0f, endX = fadePx),
                        topLeft = Offset.Zero,
                        size = Size(fadePx, size.height),
                        alpha = leftAlpha,
                    )
                }
                if (rightAlpha > 0.005f) {
                    drawRect(
                        Brush.horizontalGradient(listOf(fadeBg.copy(alpha = 0f), fadeBg), startX = size.width - fadePx, endX = size.width),
                        topLeft = Offset(size.width - fadePx, 0f),
                        size = Size(fadePx, size.height),
                        alpha = rightAlpha,
                    )
                }
            },
    ) {
        Box(
            Modifier
                .align(if (centered) Alignment.Center else Alignment.CenterStart)
                .horizontalScroll(scroll)
                .padding(vertical = 4.dp)
                .testTag("math"),
        ) {
            if (content != null) {
                // A placeholder cannot span a line break, so the copied LaTeX is on one line.
                Text(
                    buildAnnotatedString { appendInlineContent("m", written.lineSequence().joinToString(" ")) },
                    inlineContent = mapOf("m" to content),
                    softWrap = false,
                    style = BubbleText,
                )
            } else {
                Text(
                    written,
                    color = Clauderc.colors.fg2,
                    fontFamily = MonoFamily,
                    fontSize = fontSize * 0.86f,
                    lineHeight = fontSize * 1.6f,
                    softWrap = false,
                )
            }
        }
    }
}

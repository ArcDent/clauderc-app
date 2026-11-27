package clauderc.app.chat

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import clauderc.app.chat.MdBlock.Code
import clauderc.app.chat.MdBlock.ItemList
import clauderc.app.chat.MdBlock.Math
import clauderc.app.chat.MdBlock.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which dollars and brackets in a reply start a formula, and which stay text. */
class MathDelimiterTest {
    private fun text(s: String) = MdRun(RunKind.TEXT, s)
    private fun math(s: String) = MdRun(RunKind.MATH, s)

    @Test fun doubleDollarsAndBracketsOnTheirOwnLinesAreBlocks() {
        assertEquals(listOf(Math("x", "\$\$x\$\$")), mdBlocks("\$\$x\$\$"))
        assertEquals(listOf(Math("x", """\[x\]""")), mdBlocks("""\[x\]"""))
        // Spread over lines, and right under a line of text, as replies often write it.
        assertEquals(
            listOf(Paragraph(listOf(text("其中："))), Math("E = mc^2", "\$\$\nE = mc^2\n\$\$"), Paragraph(listOf(text("就是质能方程。")))),
            mdBlocks("其中：\n\$\$\nE = mc^2\n\$\$\n就是质能方程。"),
        )
        assertEquals(listOf(Math("""\frac{a}{b}""", """\[ \frac{a}{b} \]""")), mdBlocks("""\[ \frac{a}{b} \]"""))
        assertEquals(listOf(ItemList(false, 1, listOf(listOf(Paragraph(listOf(text("面积："))), Math("\\pi r^2", "\$\$\\pi r^2\$\$"))))), mdBlocks("- 面积：\n  \$\$\\pi r^2\$\$"))
    }

    @Test fun parenthesesAndSingleDollarsAreInline() {
        assertEquals(listOf(text("设 "), math("""\(x\)"""), text(" 为实数")), mdRuns("""设 \(x\) 为实数"""))
        assertEquals(listOf(text("面积是 "), math("\$x^2\$"), text("。")), mdRuns("面积是 \$x^2\$。"))
        assertEquals(listOf(math("\$a\$"), text(" 和 "), math("\$\\alpha_1\$")), mdRuns("\$a\$ 和 \$\\alpha_1\$"))
        // Doubled dollars inside a sentence stay in it.
        assertEquals(listOf(text("即 "), math("\$\$\\sum x\$\$"), text(" 成立")), mdRuns("即 \$\$\\sum x\$\$ 成立"))
        assertEquals(listOf("x", "x^2", "\\sum x"), listOf(math("""\(x\)"""), math("\$x^2\$"), math("\$\$\\sum x\$\$")).map { it.tex })
    }

    @Test fun pricesShellVariablesAndCodeAreNotFormulas() {
        for (s in listOf("\$5 到 \$10", "改 \$PATH 就行", "\$ x \$", "差 \$5\$6", "价格 \\\$5，折后 \\\$3\$")) {
            assertEquals(s, listOf(RunKind.TEXT), mdRuns(s).map { it.kind }.distinct())
        }
        assertEquals(listOf(text("写 "), MdRun(RunKind.CODE, "\$x\$"), text(" 就行")), mdRuns("写 `\$x\$` 就行"))
        assertEquals(listOf(Code("sh", "echo \$x\$ \$\$y\$\$")), mdBlocks("```sh\necho \$x\$ \$\$y\$\$\n```"))
        assertEquals(listOf(Code("", "\$\$x\$\$")), mdBlocks("    \$\$x\$\$"))
    }

    @Test fun boldSkipsTheStarsInsideFormulasAndCode() {
        val starred = math("\$A^*\$").copy(bold = true)
        assertEquals(listOf(starred), mdRuns("**\$A^*\$**"))
        assertEquals(
            listOf(MdRun(RunKind.BOLD, "如果是 "), math("\$A^2=0\$").copy(bold = true), MdRun(RunKind.BOLD, "：")),
            mdRuns("**如果是 \$A^2=0\$：**"),
        )
        assertEquals(listOf(MdRun(RunKind.CODE, "**"), starred), mdRuns("**`**`\$A^*\$**"))
    }

    @Test fun multilineDisplaysAreCutOutBeforeMarkdownTakesTheirLines() {
        val tex = "x\n- 2x\n+ 3x\n\n* y\n# z\n> w\n1. v"
        for ((open, close) in listOf("\$\$" to "\$\$", "\\[" to "\\]")) {
            val written = "$open\n$tex\n$close"
            assertEquals(
                listOf(Paragraph(listOf(text("前文"))), Math(tex, written), Paragraph(listOf(text("后文")))),
                mdBlocks("前文\n$written\n后文"),
            )
            assertEquals(listOf(Code("tex", written)), mdBlocks("~~~tex\n$written\n~~~~"))
        }
        val windows = "\$\$\r\nx\r\n- 2x\r\n\$\$"
        assertEquals(listOf(Math("x\r\n- 2x", windows)), mdBlocks(windows))
    }

    @Test fun bracketsInAProseLineMakeAnInlineFormula() {
        val formula = mdRuns("即 \\[ x^2 \\] 成立")[1]
        assertEquals(math("\\[ x^2 \\]"), formula)
        assertEquals("x^2", formula.tex)
    }

    @Test fun standaloneEnvironmentsKeepTheirEntireSourceAsBlockMath() {
        val names = "aligned align align* gather gathered equation equation* cases matrix pmatrix bmatrix Bmatrix vmatrix Vmatrix array"
        for (name in names.split(' ')) {
            val written = "\\begin{$name}\nx&=y\\\\\n- 2x\n\\end{$name}"
            assertEquals(name, listOf(Math(written, written)), mdBlocks(written))
        }
        val nested = "\\begin{matrix}\n\\begin{matrix}x\\end{matrix}\n\\end{matrix}"
        assertEquals(listOf(Math(nested, nested)), mdBlocks(nested))
        val written = "\\begin{pmatrix}x\\end{pmatrix}"
        assertEquals(listOf(Paragraph(listOf(text("前文 $written 后文")))), mdBlocks("前文 $written 后文"))
    }

    @Test fun pricesAndVariablesDoNotBorrowAnOpeningDollar() {
        assertEquals(listOf(text("成本 \$5，令"), math("\$x=1\$"), text("。")), mdRuns("成本 \$5，令\$x=1\$。"))
        assertEquals(listOf(text("售价 \$5（"), math("\$n\$"), text(" 件）")), mdRuns("售价 \$5（\$n\$ 件）"))
        for (s in listOf("把\$HOME和\$PATH配好", "比较 \$FOO/\$BAR")) {
            assertEquals(listOf(text(s)), mdRuns(s))
        }
        val written = "\$x+\\\$5\$"
        val runs = mdRuns("比例为 $written。")
        assertEquals(listOf(text("比例为 "), math(written), text("。")), runs)
        assertEquals("x+\\\$5", runs[1].tex)
        assertEquals(listOf(math("\$2\\pi r\$"), text(" "), math("\$\\text{含{中文}}\$")), mdRuns("\$2\\pi r\$ \$\\text{含{中文}}\$"))
    }

    @Test fun matchingBacktickRunsHideInlineAndDisplayMath() {
        assertEquals(listOf(MdRun(RunKind.CODE, "`\$x\$`")), mdRuns("`` `\$x\$` ``"))
        assertEquals(listOf(MdRun(RunKind.CODE, "``\$x\$```")), mdRuns("```` ``\$x\$``` ````"))
        val written = "先写 ``代码\n\$\$\nx\n\$\$\n末尾``。"
        val expected = listOf(Paragraph(listOf(text("先写 "), MdRun(RunKind.CODE, "代码 \$\$ x \$\$ 末尾"), text("。"))))
        for (lineEnding in listOf("\n", "\r\n")) assertEquals(expected, mdBlocks(written.replace("\n", lineEnding)))
    }

    @Test fun onlyCompleteSupportedEnvironmentsCanBeDrawn() {
        assertTrue(canDraw("\\begin{pmatrix}1&0\\\\0&0\\end{pmatrix}"))
        assertTrue(canDraw("\\begin{align}x&=y\\end{align}", display = true))
        for (tex in listOf(
            "\\begin{pmatrix}1&0\\\\0&0", "\\end{pmatrix}",
            "\\begin{pmatrix}1\\end{bmatrix}", "\\begin{unknown}x\\end{unknown}",
            "\\begin{equation}x\\end{equation}",
        )) assertFalse(tex, canDraw(tex, display = true))
    }

    @Test fun externalReferencesAndOverlongSourceStayAsWritten() {
        assertFalse(canDraw("\\eqref{eq:x}"))
        assertFalse(canDraw("x=\\ref{eq:x}", display = true))
        assertTrue(canDraw("x" + " ".repeat(1999)))
        assertFalse(canDraw("x" + " ".repeat(2000)))
    }

    @Test fun measurementReceivesTheFormulaWithItsStylePrefix() {
        val density = Density(1f)
        for ((display, prefix) in listOf(false to "\\textstyle ", true to "\\displaystyle ")) {
            assertNotNull(drawableContent("x", density, display) {
                assertEquals(prefix + "x", it)
                placeholder(1f, 1f)
            })
        }
    }

    @Test fun measurementExceptionsAndInvalidPixelDimensionsFallBack() {
        val density = Density(2f)
        for (display in listOf(false, true)) {
            assertNull(drawableContent("x", density, display) { error("measurement failed") })
            assertNotNull(drawableContent("x", density, display) { placeholder(12_000f, 12_000f) })
            for (size in listOf(12_001f, Float.POSITIVE_INFINITY, Float.NaN)) {
                assertNull(drawableContent("x", density, display) { placeholder(size, 1f) })
                assertNull(drawableContent("x", density, display) { placeholder(1f, size) })
            }
        }
    }

    @Test fun anIndentedDisplayStaysOneBlockAcrossBlankLinesAndDashes() {
        val blocks = mdBlocks("前文\n\n  \$\$\nx = 1\n\n- 2x\n\$\$\n\n后文")
        assertEquals(3, blocks.size)
        val formula = blocks[1] as Math
        assertTrue("the dash line stays in the formula: ${formula.tex}", "- 2x" in formula.tex)
        assertEquals(Paragraph(listOf(text("后文"))), blocks[2])
        // Four spaces in is indented code, as before.
        assertTrue(mdBlocks("前文\n\n    \$\$\n    x\n    \$\$").none { it is Math })
    }

    @Test fun theScreensTableRulesDoNotCarryTypingPastTheWrittenTable() {
        val screen = mdBlocks("表格：\n┌───┐\n│ A │\n└───┘\n后面的正文")
        val written = mdBlocks("表格：\n\n| A |\n|---|\n| 1 |\n\n后面的正文")
        val shown = screen.sumOf { it.shownLength() } - "后面的正文".length
        // 「表格：」 then the table's cells, and not into the text after it.
        assertEquals(3 + 2, carryShown(screen, shown, written))
    }

    private fun placeholder(width: Float, height: Float) =
        InlineTextContent(Placeholder(width.sp, height.sp, PlaceholderVerticalAlign.TextCenter)) {}
}

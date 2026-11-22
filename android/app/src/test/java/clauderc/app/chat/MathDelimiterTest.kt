package clauderc.app.chat

import clauderc.app.chat.MdBlock.Code
import clauderc.app.chat.MdBlock.ItemList
import clauderc.app.chat.MdBlock.Math
import clauderc.app.chat.MdBlock.Paragraph
import org.junit.Assert.assertEquals
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
}

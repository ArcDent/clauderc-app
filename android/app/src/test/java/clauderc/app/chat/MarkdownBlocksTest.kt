package clauderc.app.chat

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.chat.MdBlock.Code
import clauderc.app.chat.MdBlock.Heading
import clauderc.app.chat.MdBlock.ItemList
import clauderc.app.chat.MdBlock.Paragraph
import clauderc.app.chat.MdBlock.Quote
import clauderc.app.chat.MdBlock.Table
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

class MarkdownBlocksTest {
    private fun text(s: String) = MdRun(RunKind.TEXT, s)
    private fun code(s: String) = MdRun(RunKind.CODE, s)
    private fun bold(s: String) = MdRun(RunKind.BOLD, s)
    private fun para(vararg runs: MdRun) = Paragraph(runs.toList())

    @Test fun progressCarriesAcrossHeadingsBoldTextAndListMarkers() {
        val firstItem = "标题\n说明\n2. 甲 乙"
        val written = "## 标题\n\n**说明**\n\n2. 甲乙\n3. 丙"
        assertEquals(6, carryShown(mdBlocks("$firstItem\n3. 丙"), firstItem.length, mdBlocks(written)))
        assertEquals(5, carryShown(listOf(para(text("标题\n说明\n- 甲"))), 9, mdBlocks(written)))
    }

    @Test fun progressCountsInlineAndBlockFormulasAsWholeUnits() {
        val inline = "\$x + y\$"
        val display = "\$\$\nz + 1\n\$\$"
        val newInline = "\$x+y\$"
        val newDisplay = "\$\$\nz+1\n\$\$"
        val old = mdBlocks("A $inline B\n\n$display\n\nC")
        val new = mdBlocks("## A\n\n**$newInline** B\n\n$newDisplay\n\nC")
        val inlineEnd = 2 + inline.length
        val displayEnd = inlineEnd + 2 + display.length
        val newInlineEnd = 1 + newInline.length
        assertEquals(1, carryShown(old, inlineEnd - 1, new))
        assertEquals(newInlineEnd, carryShown(old, inlineEnd, new))
        assertEquals(newInlineEnd, carryShown(listOf(para(text("A \$x"))), 4, new))
        assertEquals(newInlineEnd + 2, carryShown(old, displayEnd - 1, new))
        assertEquals(newInlineEnd + 2 + newDisplay.length, carryShown(old, displayEnd, new))
    }

    @Test fun progressCountsATableAtItsWholeLength() {
        val table = Table(listOf(listOf(text("b c"))), emptyList())
        val blocks = listOf(para(text("a")), table, para(text("z")))
        assertEquals(6, carryShown(blocks, 2, mdBlocks("a b cde")))
        assertEquals(4, carryShown(mdBlocks("ab"), 2, blocks))
    }

    @Test fun progressStopsAtTheEndWhenTheNewReplyHasTooFewCharacters() {
        val old = mdBlocks("ABCDE")
        val new = mdBlocks("短 句")
        assertEquals(3, carryShown(old, 5, new))
        assertEquals(0, carryShown(old, 0, new))
    }

    /** The text between two formulas on lines of their own starts with its words, no line break before them. */
    @Test fun theTextBetweenTwoFormulasStartsWithItsWords() {
        val blocks = mdBlocks("\$\$a\$\$\n中间\n\$\$b\$\$")
        assertEquals(listOf("Math", "Paragraph", "Math"), blocks.map { it::class.simpleName })
        assertEquals("中间", (blocks[1] as Paragraph).runs.joinToString("") { it.text })
    }

    @Test fun eachKindOfBlockIsCutOut() {
        val reply = """
            ## 重连退避

            每次失败后等待时间**翻倍**，上限见 `maxDelay`。
            第二行

            3. 先读配置
            4. 再连

            - 不重试 4xx

            > 引用一行
            > 第二行

            ```go
            d = min(d*2, maxDelay)
            ```

            | 次数 | 等待 |
            |---|---:|
            | 1 | `1s` |
            | 2 | 2s |
        """.trimIndent()
        assertEquals(
            listOf(
                Heading(2, listOf(text("重连退避"))),
                para(text("每次失败后等待时间"), bold("翻倍"), text("，上限见 "), code("maxDelay"), text("。\n第二行")),
                ItemList(true, 3, listOf(listOf(para(text("先读配置"))), listOf(para(text("再连"))))),
                ItemList(false, 1, listOf(listOf(para(text("不重试 4xx"))))),
                Quote(listOf(para(text("引用一行\n第二行")))),
                Code("go", "d = min(d*2, maxDelay)"),
                Table(
                    listOf(listOf(text("次数")), listOf(text("等待"))),
                    listOf(listOf(listOf(text("1")), listOf(code("1s"))), listOf(listOf(text("2")), listOf(text("2s")))),
                ),
            ),
            mdBlocks(reply),
        )
    }

    @Test fun anUnclosedFenceIsCodeToTheEnd() {
        assertEquals(
            listOf(para(text("跑一下：")), Code("sh", "go test ./...\n\n## 不是标题\n- 不是列表")),
            mdBlocks("跑一下：\n\n```sh\ngo test ./...\n\n## 不是标题\n- 不是列表"),
        )
        // Just the fence, as a reply typing out first shows it.
        assertEquals(listOf(Code("go", "")), mdBlocks("```go"))
    }

    @Test fun codeKeepsItsMarksAsWritten() {
        assertEquals(listOf(Code("", "**不是粗体** [x](y) `z`")), mdBlocks("```\n**不是粗体** [x](y) `z`\n```"))
        assertEquals(listOf(para(text("用 "), code("**x** [a](b)"), text(" 写"))), mdBlocks("用 `**x** [a](b)` 写"))
    }

    @Test fun formulaPipesKeepTheMathAndTheJudgmentInTheirCells() {
        val table = mdBlocks("""
            | 你写的 | 判断 |
            |---|---|
            | ${'$'}A^+=\begin{pmatrix}A_{11}^*&A_{21}^*\\A_{12}^*&A_{22}^*\end{pmatrix}${'$'} | ✓ 转置加共轭，正确 |
            | ${'$'}B_{11}=|A_{11}|^2+|A_{21}|^2${'$'} | ✓ 正确（${'$'}=1${'$'}） |
            | ${'$'}B_{22}=0=|A_{11}|^2+|A_{12}|^2${'$'} | ✗ **公式标记错了** |
            | ${'$'}A_{11}=A_{12}=0${'$'} | ✓ 结论对 |
            | ${'$'}|A_{21}|^2=1\Rightarrow A_{21}=e^{i\alpha}${'$'} | ✓ 正确 |
        """.trimIndent()).single() as Table
        assertEquals(listOf(listOf(text("你写的")), listOf(text("判断"))), table.head)
        assertEquals(5, table.rows.size)
        val expected = mapOf(
            1 to ("B_{11}=|A_{11}|^2+|A_{21}|^2" to listOf(text("✓ 正确（"), MdRun(RunKind.MATH, "\$=1\$"), text("）"))),
            2 to ("B_{22}=0=|A_{11}|^2+|A_{12}|^2" to listOf(text("✗ "), bold("公式标记错了"))),
            4 to ("|A_{21}|^2=1\\Rightarrow A_{21}=e^{i\\alpha}" to listOf(text("✓ 正确"))),
        )
        for ((i, pair) in expected) {
            val (tex, judgment) = pair
            val formula = MdRun(RunKind.MATH, "\$$tex\$")
            assertEquals(listOf(listOf(formula), judgment), table.rows[i])
            assertEquals(tex, table.rows[i][0].single().tex)
        }
    }

    @Test fun pipesInsideCodeAreNotProtectedAsFormulaPipes() {
        val reply = "```tex\n\$|x|\$\n```\n\n| a | b |\n|---|---|\n| `\$x|y\$` | tail |"
        val (fence, table) = mdBlocks(reply)
        assertEquals(Code("tex", "\$|x|\$"), fence)
        assertEquals(listOf(listOf(text("`\$x")), listOf(text("y\$`"))), (table as Table).rows.single())
    }

    @Test fun formulaPipesStayInsideCellsWithDoubleDollarsOrParentheses() {
        val table = mdBlocks("| a | b |\n|---|---|\n| \$\$|x|\$\$ | \\(|y|\\) |").single() as Table
        assertEquals(listOf("|x|", "|y|"), table.rows.single().map { cell ->
            cell.single().also { assertEquals(RunKind.MATH, it.kind) }.tex
        })
    }

    @Test fun underscoresAndSingleStarsStayAsWritten() {
        assertEquals(listOf(para(text("用 snake_case 命名，别动 __init__、_a_ 和 *b*"))), mdBlocks("用 snake_case 命名，别动 __init__、_a_ 和 *b*"))
    }

    @Test fun nestedBlocksStayInsideTheirItemOrQuote() {
        val reply = "1. 装依赖\n\n   ```sh\n   npm i\n   ```\n\n   - 子项\n\n> - 引用里的列表"
        assertEquals(
            listOf(
                ItemList(true, 1, listOf(listOf(para(text("装依赖")), Code("sh", "npm i"), ItemList(false, 1, listOf(listOf(para(text("子项")))))))),
                Quote(listOf(ItemList(false, 1, listOf(listOf(para(text("引用里的列表"))))))),
            ),
            mdBlocks(reply),
        )
    }

    @Test fun headingsEscapesAndTheRest() {
        // Deeper headings are drawn as the third level; a closing # run and a setext underline are not text.
        assertEquals(
            listOf(Heading(3, listOf(text("小节"))), Heading(1, listOf(text("标题"))), Heading(2, listOf(text("下划线标题")))),
            mdBlocks("##### 小节 ##\n\n# 标题\n\n下划线标题\n---"),
        )
        // An escaped pipe stays in its cell; a missing cell is empty.
        assertEquals(
            listOf(Table(listOf(listOf(text("a|b")), listOf(text("c"))), listOf(listOf(listOf(text("1")), emptyList())))),
            mdBlocks("| a\\|b | c |\n|---|---|\n| 1 |"),
        )
        assertEquals(listOf(para(text("**不是粗体**"))), mdBlocks("\\*\\*不是粗体\\*\\*"))
        // Indented code has no language; a rule shows nothing; HTML shows as written.
        assertEquals(
            listOf(Code("", "x := 1"), para(text("<details>原样</details>"))),
            mdBlocks("    x := 1\n\n---\n\n<details>原样</details>"),
        )
    }

    @Test fun replyChunksStaysWholeUpToSixThousandCharsAndSplitsAroundThreeThousand() {
        fun p(len: Int) = para(text("a".repeat(len)))
        assertEquals(emptyList<IntRange>(), replyChunks(emptyList()))
        assertEquals(listOf(0..1), replyChunks(listOf(p(3_000), p(3_000))))
        assertEquals(listOf(0..0, 1..1), replyChunks(listOf(p(3_000), p(3_001))))
        assertEquals(listOf(0..2, 3..5, 6..8, 9..9), replyChunks(List(10) { p(1_000) }))
        // A single block longer than 3000 stands alone without splitting it.
        assertEquals(listOf(0..0, 1..1, 2..2), replyChunks(listOf(p(1_000), Code("go", "x".repeat(4_500)), p(1_000))))
        assertEquals(listOf(0..0), replyChunks(listOf(Code("go", "x".repeat(7_000)))))
    }

    @Test fun replyChunksMergesZeroLengthBlocksIntoAdjacentNonEmptyChunks() {
        fun p(len: Int) = para(text("a".repeat(len)))
        val emptySh = Code("sh", "")
        val emptyPy = Code("py", "")
        // Leading empty code block before an oversized block merges into that block instead of forming a 0-char chunk.
        assertEquals(listOf(0..1, 2..2), replyChunks(listOf(emptySh, Code("go", "x".repeat(4_000)), p(3_000))))
        assertEquals(listOf(0..2, 3..3), replyChunks(listOf(emptySh, emptyPy, Code("go", "x".repeat(4_000)), p(3_000))))
        // Middle empty code block between a filled chunk and an oversized block merges forward.
        assertEquals(listOf(0..0, 1..2), replyChunks(listOf(p(3_000), emptySh, Code("go", "x".repeat(4_000)))))
        // Trailing empty code block after the final non-empty chunk merges into the previous chunk.
        assertEquals(listOf(0..0, 1..2), replyChunks(listOf(p(3_000), p(3_100), emptySh)))
    }

    @Test fun replyParseCacheOverwritesStreamingUpdatesByKeyAndPrunesRemovedKeys() {
        val cache = ReplyParseCache()
        var text = "开头"
        var last = cache.getOrParse("partial-1", text)
        repeat(20) { i ->
            text += "追加$i"
            last = cache.getOrParse("partial-1", text)
        }
        assertEquals("streaming updates replace in place under the same key", 1, cache.size)
        assertTrue("unchanged text hits cached instance", cache.getOrParse("partial-1", text) === last)
        cache.getOrParse(2L, "另一条回复")
        assertEquals(2, cache.size)
        cache.prune(setOf(2L))
        assertEquals("pruning drops keys no longer in the list", 1, cache.size)
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownBlocksTestRender {
    @get:Rule val compose = createComposeRule()

    private fun show(vararg blocks: MdBlock) {
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                ReplyBlocks(blocks.toList(), Int.MAX_VALUE, Clauderc.colors.fg)
            }
        }
    }

    @Test fun shortCodeKeepsItsLinesAndTabSpacing() {
        show(Code("go", "first\n\tsecond\n"))
        compose.onNodeWithText("first\n    second\n").assertIsDisplayed()
    }

    @Test fun longCodeCountsOmittedCharactersAfterExpandingTabs() {
        val long = "x".repeat(19_999) + "\t"
        show(Code("sh", long + "\nshort"))
        compose.onNodeWithText("${"x".repeat(800)}…（已省略 19203 字符）\nshort").assertIsDisplayed()
    }

    @Test fun wideTableCapsTheSumOfColumnWidths() {
        val long = listOf(MdRun(RunKind.TEXT, "w".repeat(200)))
        val short = listOf(MdRun(RunKind.TEXT, "短列"))
        val wideColumns = List(24) { long }
        show(Table(listOf(short) + wideColumns, listOf(listOf(emptyList<MdRun>()) + wideColumns)))
        compose.onNodeWithTag("table").assertIsDisplayed()
        val width = compose.onNodeWithTag("tableContent").fetchSemanticsNode().size.width
        assertTrue("capped width was $width", width <= 24_000 && width > 24_000 - wideColumns.size)
        compose.onNodeWithText("短列").assertIsDisplayed()
    }

    @Test fun tableCellsKeepTwoHundredCodePointsAcrossRuns() {
        val rocket = "🚀"
        val head = "a".repeat(199)
        val body = "b".repeat(199)
        show(*mdBlocks("| $head$rocket$rocket |\n|---|\n| **$body**$rocket${rocket}尾 |").toTypedArray())
        compose.onNodeWithText("$head$rocket…（已省略 1 字符）", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("$body$rocket…（已省略 2 字符）", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun tableWithNoColumnsDoesNotDraw() {
        show(Table(emptyList(), listOf(emptyList())), Paragraph(listOf(MdRun(RunKind.TEXT, "接着显示"))))
        compose.onNodeWithTag("table").assertDoesNotExist()
        compose.onNodeWithText("接着显示").assertIsDisplayed()
    }

    @Test fun overlongBlockAndInlineFormulasShowTheirSourceWithoutPlaceholders() {
        val tex = "x+".repeat(1_000) + "x"
        val display = "\$\$\n$tex\n\$\$"
        val inline = "结果是 \$$tex\$。"
        show(*mdBlocks("$display\n\n$inline").toTypedArray())
        for (source in listOf(display, inline)) {
            val layouts = mutableListOf<TextLayoutResult>()
            val node = compose.onNodeWithText(source, useUnmergedTree = true)
            node.assertIsDisplayed()
            node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertTrue("renders source as ordinary text", layouts.single().placeholderRects.isEmpty())
        }
    }

    /**
     * Proves Compose ui-text 1.12.1's LineHeightStyle.Mode.Minimum behavior with tall InlineTextContent:
     * LineHeightStyleSpan caches line-0 metrics and overwrites every line's ascent/descent, so a tall
     * placeholder on line 1 overflows into lines 0 and 2, while one on line 0 expands every line.
     */
    @Test fun lineHeightModeMinimumDoesNotExpandMiddleLinePlaceholder() {
        val layouts = mutableMapOf<String, androidx.compose.ui.text.TextLayoutResult>()
        val midText = androidx.compose.ui.text.buildAnnotatedString {
            append("第一行普通文字\n第二行有高占位")
            appendInlineContent("tall", "[tall]")
            append("后面\n第三行普通文字")
        }
        val firstText = androidx.compose.ui.text.buildAnnotatedString {
            append("第一行有高占位")
            appendInlineContent("tall", "[tall]")
            append("\n第二行普通文字\n第三行普通文字")
        }
        val tallInline = mapOf(
            "tall" to InlineTextContent(
                androidx.compose.ui.text.Placeholder(
                    androidx.compose.ui.unit.TextUnit(40f, androidx.compose.ui.unit.TextUnitType.Sp),
                    androidx.compose.ui.unit.TextUnit(52f, androidx.compose.ui.unit.TextUnitType.Sp),
                    androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter,
                ),
            ) {},
        )
        val minStyle = BubbleText.copy(
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Proportional,
                trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
                mode = androidx.compose.ui.text.style.LineHeightStyle.Mode.Minimum,
            ),
        )
        val noLineHeight = androidx.compose.ui.text.TextStyle(fontSize = BubbleText.fontSize)
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text(midText, style = minStyle, inlineContent = tallInline, onTextLayout = { layouts["midMin"] = it })
                    androidx.compose.material3.Text(firstText, style = minStyle, inlineContent = tallInline, onTextLayout = { layouts["firstMin"] = it })
                    androidx.compose.material3.Text(midText, style = noLineHeight, inlineContent = tallInline, onTextLayout = { layouts["midUnspecified"] = it })
                }
            }
        }
        compose.waitForIdle()
        val midMin = layouts.getValue("midMin")
        val midBox = midMin.placeholderRects.single()!!
        // Middle line stays at base lineHeight, so the tall placeholder overlaps both L0 and L2.
        assertTrue("Mode.Minimum fails to expand L1: placeholder=$midBox, L1=[${midMin.getLineTop(1)}..${midMin.getLineBottom(1)}]", midBox.top < midMin.getLineTop(1) && midBox.bottom > midMin.getLineBottom(1))
        val firstMin = layouts.getValue("firstMin")
        // On L0, Mode.Minimum expands L0 but also blows up L1 and L2 to the same tall height.
        val l0h = firstMin.getLineBottom(0) - firstMin.getLineTop(0)
        val l1h = firstMin.getLineBottom(1) - firstMin.getLineTop(1)
        assertEquals("Mode.Minimum on L0 expands every other line too", l0h, l1h, 1f)
        // Contrast: without LineHeightStyleSpan, StaticLayout expands only L1.
        val midUnspec = layouts.getValue("midUnspecified")
        val unspecBox = midUnspec.placeholderRects.single()!!
        assertTrue("Unspecified lineHeight fits placeholder inside L1", unspecBox.top >= midUnspec.getLineTop(1) - 0.5f && unspecBox.bottom <= midUnspec.getLineBottom(1) + 0.5f)
        assertTrue("Unspecified lineHeight leaves L0 shorter than L1", (midUnspec.getLineBottom(0) - midUnspec.getLineTop(0)) < (midUnspec.getLineBottom(1) - midUnspec.getLineTop(1)))
    }

    @Test fun inlineMatrixAndOverwideFormulasPromoteOntoTheirOwnLines() {
        val text = "前面 \$B=\\begin{pmatrix}1&0\\\\0&0\\end{pmatrix}\$ 中间 \$x^2\$ 后面 \$(A^+A)_{22}=A_{12}^*A_{12}+A_{22}^*A_{22}=|A_{12}|^2+|A_{22}|^2=0\\ \\Rightarrow\\ A_{12}=A_{22}=0\$ 结束"
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(androidx.compose.ui.unit.Dp(320f))) {
                    ReplyBlocks(mdBlocks(text), Int.MAX_VALUE, Clauderc.colors.fg)
                }
            }
        }
        compose.waitForIdle()
        val mathNodes = compose.onAllNodesWithTag("math", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("matrix and over-wide inline formula both promoted", 2, mathNodes.size)
        val before = compose.onNodeWithText("前面 ", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val middle = compose.onNode(androidx.compose.ui.test.hasText("中间", substring = true), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val after = compose.onNodeWithText("结束", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val promoted = mathNodes.map { it.boundsInRoot }.sortedBy { it.top }
        assertTrue("order: before < matrix < middle < wide < after", before.bottom <= promoted[0].top + 1f && promoted[0].bottom <= middle.top + 1f && middle.bottom <= promoted[1].top + 1f && promoted[1].bottom <= after.top + 1f)
        val wideRange = mathNodes.maxBy { it.boundsInRoot.top }.config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("promoted wide formula scrolls horizontally: max=${wideRange.maxValue()}", wideRange.maxValue() > 0f)
    }

    @Test fun boldContainingInlineFormulasDrawsThemWithoutRawDelimiters() {
        val text = "关键是 **\$A_{21}\$ 属于第 1 列**，且 **只要把「\$B_{22}\$ 用第 2 列、\$B_{11}\$ 用第 1 列」分开写**。"
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                ReplyBlocks(mdBlocks(text), Int.MAX_VALUE, Clauderc.colors.fg)
            }
        }
        compose.waitForIdle()
        val node = compose.onNode(androidx.compose.ui.test.hasText("关键是", substring = true), useUnmergedTree = true)
        node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        assertEquals("all 3 inline formulas inside bold are drawn as placeholders", 3, layout.placeholderRects.filterNotNull().size)
        assertTrue("no raw ** left in rendered text: ${layout.layoutInput.text.text}", "**" !in layout.layoutInput.text.text)
        assertTrue("none fell back to mono text", layout.layoutInput.text.spanStyles.none { it.item.fontFamily == clauderc.app.ui.theme.MonoFamily })
    }

    @Test fun displayFractionPromotesWhileInlineFractionsStayInline() {
        val text = "平均等待是 \$\\frac{a+b}{c+d}\$，连分式 \$\\frac{1}{1+\\frac{1}{x}}\$ 也留在行内，最后的修正系数是 \$\\dfrac{a+b}{c+d}\$，应当单独占一行。"
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(androidx.compose.ui.unit.Dp(340f))) {
                    ReplyBlocks(mdBlocks(text), Int.MAX_VALUE, Clauderc.colors.fg)
                }
            }
        }
        compose.waitForIdle()
        val promoted = compose.onAllNodesWithTag("math", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("only the display fraction is promoted", 1, promoted.size)
        val beforeNode = compose.onNode(androidx.compose.ui.test.hasText("平均等待是", substring = true), useUnmergedTree = true)
        beforeNode.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals("both text-style fractions stay inline as placeholders", 2, layouts.single().placeholderRects.filterNotNull().size)
        val before = beforeNode.fetchSemanticsNode().boundsInRoot
        val after = compose.onNode(androidx.compose.ui.test.hasText("应当单独占一行", substring = true), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val promotedBounds = promoted.single().boundsInRoot
        assertTrue("order: inline fractions < display fraction < trailing text", before.bottom <= promotedBounds.top + 1f && promotedBounds.bottom <= after.top + 1f)
    }
}

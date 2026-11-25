package clauderc.app.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        val long = listOf(MdRun(RunKind.TEXT, "w".repeat(20_000)))
        val short = listOf(MdRun(RunKind.TEXT, "短列"))
        show(Table(listOf(short, long, long), listOf(listOf(emptyList(), long, long))))
        compose.onNodeWithTag("table").assertIsDisplayed()
        val width = compose.onNodeWithTag("tableContent").fetchSemanticsNode().size.width
        assertTrue("capped width was $width", width in 23_999..24_000)
        compose.onNodeWithText("短列").assertIsDisplayed()
    }

    @Test fun tableWithNoColumnsDoesNotDraw() {
        show(Table(emptyList(), listOf(emptyList())), Paragraph(listOf(MdRun(RunKind.TEXT, "接着显示"))))
        compose.onNodeWithTag("table").assertDoesNotExist()
        compose.onNodeWithText("接着显示").assertIsDisplayed()
    }
}

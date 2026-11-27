package clauderc.app.chat

import clauderc.app.data.UiStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.Pending
import clauderc.app.protocol.PendingKind
import clauderc.app.protocol.SessionState
import clauderc.app.testing.session
import clauderc.app.ui.theme.ClaudercMotion
import clauderc.app.ui.theme.ClaudercTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Round 3: new replies type out, the CLI's status line, and the panel that rises whole. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class LiveReplyTest {
    @get:Rule val compose = createComposeRule()

    private val durM = ClaudercMotion().durM.toLong()

    /** 200 characters with no markdown, so the formatted text is as long as the reply. */
    private val reply = "第一，聊天记录改成文件变了才读。".repeat(12) + "下周再测一遍性能。"

    private fun items(n: Int) = (1..n).map { ChatItem(it.toLong(), if (it % 2 == 0) ItemKind.ASSISTANT else ItemKind.USER, text = "第 $it 条消息") }

    private var state by mutableStateOf(ChatUiState(loading = false))

    private fun show(initial: ChatUiState, darkTheme: Boolean = false, style: UiStyle = UiStyle.Plate) {
        state = initial
        compose.mainClock.autoAdvance = false
        // 印版 stacks the input under the list, so the list's bottom is where the messages end.
        compose.setContent { ClaudercTheme(darkTheme = darkTheme, style = style) { ChatScreen(state, ChatActions()) } }
        compose.mainClock.advanceTimeByFrame()
    }

    /** Writes the next state and lets it reach the screen in one frame, with the clock frozen. */
    private fun next(change: (ChatUiState) -> ChatUiState) {
        compose.runOnUiThread { state = change(state) }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun lowestReply(): SemanticsNode = compose.onAllNodesWithTag("reply", useUnmergedTree = true).fetchSemanticsNodes().maxBy { it.boundsInRoot.bottom }

    /** The bubble typing out [reply]: the one reply whose text so far begins it. */
    private fun typing(): SemanticsNode = compose.onAllNodesWithTag("reply", useUnmergedTree = true).fetchSemanticsNodes().single { reply.startsWith(it.shown()) }

    /** The text a reply shows: its blocks' texts one after another. */
    private fun SemanticsNode.shown(): String =
        config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text } + children.joinToString("") { it.shown() }

    /** Round 8: a reply with a heading, a list, code and a table. */
    private val markdown = listOf(
        "## 重连退避",
        "",
        "逻辑在 `internal/relay/conn.go` 的 `redial` 里，分三步：",
        "",
        "1. 第一次断开后等 **1 秒**",
        "2. 之后每次翻倍，最多等 30 秒",
        "3. 连上并收到第一个心跳后清零",
        "",
        "```go",
        "func backoff(n int) time.Duration {",
        "\td := time.Second << n",
        "\tif d > 30*time.Second || d <= 0 { // 移位溢出成负数时也取上限",
        "\t\treturn 30 * time.Second",
        "\t}",
        "\treturn d",
        "}",
        "```",
        "",
        "| 第几次 | 等待 | 累计 |",
        "|---|---|---|",
        "| 1 | 1s | 1s |",
        "| 6 | 30s | 61s |",
    ).joinToString("\n")

    /** The newest reply's text as shown, in all its blocks. */
    private fun newestShown() = lowestReply().shown()

    @Test fun aNewReplyTypesOutAtTheMockupsSpeed() {
        show(ChatUiState(loading = false, items = items(6)))
        next { it.copy(items = it.items + ChatItem(7, ItemKind.ASSISTANT, text = reply)) }
        val first = typing().shown()
        assertTrue("the first frame shows ${first.length} of ${reply.length}", first.length < reply.length)
        assertTrue(reply.startsWith(first))
        var last = first.length
        val at = mutableMapOf<Long, Int>()
        for (t in 100L..4000L step 100) {
            compose.mainClock.advanceTimeBy(100)
            val s = typing().shown()
            assertTrue("never shrinks: ${s.length} after $last", s.length >= last && reply.startsWith(s))
            last = s.length
            at[t] = s.length
        }
        // Behind by the whole reply, it catches up by what is left in 0.8s: about 63% after 0.8s.
        assertTrue("after 0.8s ${at[800L]}", at[800L]!! in 100..160)
        // Near the end it slows to 40 characters a second, so the last few take most of a second.
        assertTrue("after 1.5s ${at[1500L]}", at[1500L]!! < reply.length)
        assertEquals(reply.length, at[3500L])
    }

    @Test fun repliesThatWereThereWhenThePageOpenedShowInFull() {
        show(ChatUiState(loading = false, opened = false, items = items(4)))
        compose.onNodeWithText("第 4 条消息").assertIsDisplayed()
        // chat.open brings a reply written while the page was closed: it is already there, not new.
        next { it.copy(opened = true, items = items(4) + ChatItem(5, ItemKind.ASSISTANT, text = reply)) }
        assertEquals(reply, lowestReply().shown())
    }

    @Test fun aLongNewReplyReachesItsLastFourHundredCharactersWithinOneSecond() {
        val longReply = "长".repeat(5_000)
        show(ChatUiState(loading = false, items = items(1)))
        next { it.copy(items = it.items + ChatItem(2, ItemKind.ASSISTANT, text = longReply)) }
        compose.mainClock.advanceTimeBy(1_000)
        val shown = lowestReply().shown()
        assertTrue("one second in: ${shown.length} of ${longReply.length}", shown.length in 4_600 until 5_000)
        assertTrue(longReply.startsWith(shown))
    }

    @Test fun whileItTypesTheListStaysAtTheBottom() {
        show(ChatUiState(loading = false, items = items(30)))
        val listBottom = compose.onNodeWithTag("chatList").fetchSemanticsNode().boundsInRoot.bottom
        next { it.copy(items = it.items + ChatItem(31, ItemKind.ASSISTANT, text = reply)) }
        // Once it has grown in, its bottom stays where the list ends while lines are added above it.
        compose.mainClock.advanceTimeBy(durM + 50)
        val bottoms = mutableListOf<Float>()
        val heights = mutableListOf<Float>()
        repeat(12) {
            compose.mainClock.advanceTimeBy(100)
            val b = typing().boundsInRoot
            bottoms += b.bottom
            heights += b.height
        }
        assertTrue("still typing: $heights", heights.last() > heights.first())
        assertTrue("bottoms $bottoms above $listBottom", bottoms.all { kotlin.math.abs(it - bottoms.first()) < 1f } && bottoms.first() < listBottom && listBottom - bottoms.first() < 100f)
    }

    @Test fun aMarkdownReplyTypesOutBlockByBlockAndNeverShrinks() {
        show(ChatUiState(loading = false, items = items(6)))
        next { it.copy(items = it.items + ChatItem(7, ItemKind.ASSISTANT, text = markdown)) }
        val total = mdBlocks(markdown).sumOf { it.shownLength() }
        var last = newestShown().length
        assertTrue("the first frame shows $last of $total", last < total)
        val lengths = mutableListOf<Int>()
        for (t in 1..60) {
            compose.mainClock.advanceTimeBy(100)
            val n = newestShown().length
            assertTrue("never shrinks: $n after $last", n >= last)
            last = n
            lengths += n
        }
        // All of it in the end, a while before the last frame: 格栅 adds 「## 」 before the heading and code shows its tabs four wide.
        assertTrue("typed out in full: $lengths of $total", lengths.last() >= total && lengths[lengths.size - 10] == lengths.last())
        compose.onNodeWithText("重连退避", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("61s", useUnmergedTree = true).assertIsDisplayed()
    }

    /** Round 9: a formula shows whole once typing reaches its end, never half of it. */
    @Test fun aFormulaTypesOutWholeNeverHalf() {
        val math = "面积是 \$x^2\$，周长是 \$2\\pi r\$。\n\n\$\$\n\\sum_{k=1}^{n} k = \\frac{n(n+1)}{2}\n\$\$\n\n就这些。"
        show(ChatUiState(loading = false, items = items(6)))
        next { it.copy(items = it.items + ChatItem(7, ItemKind.ASSISTANT, text = math)) }
        val frames = mutableListOf<String>()
        for (t in 1..40) {
            compose.mainClock.advanceTimeBy(100)
            val s = newestShown()
            assertTrue("「$s」 shows half a formula", s.count { it == '$' } % 2 == 0)
            frames += s
        }
        assertTrue("the inline formula shows before the block one", frames.any { "\$x^2\$" in it && "\\sum" !in it })
        assertTrue("all of it in the end: ${frames.last()}", "\\frac{n(n+1)}{2}" in frames.last() && frames.last().endsWith("就这些。"))
    }

    @Test fun whileAMarkdownReplyTypesTheListStaysAtTheBottom() {
        show(ChatUiState(loading = false, items = items(30)))
        val listBottom = compose.onNodeWithTag("chatList").fetchSemanticsNode().boundsInRoot.bottom
        next { it.copy(items = it.items + ChatItem(31, ItemKind.ASSISTANT, text = markdown)) }
        compose.mainClock.advanceTimeBy(durM + 50)
        val bottoms = mutableListOf<Float>()
        val heights = mutableListOf<Float>()
        repeat(30) {
            compose.mainClock.advanceTimeBy(100)
            val b = lowestReply().boundsInRoot
            bottoms += b.bottom
            heights += b.height
        }
        assertTrue("grows as it types: $heights", heights.last() > heights.first() && heights.zipWithNext().all { (a, b) -> b >= a - 0.5f })
        assertTrue("bottoms $bottoms above $listBottom", bottoms.all { kotlin.math.abs(it - bottoms.first()) < 1f } && bottoms.first() < listBottom && listBottom - bottoms.first() < 100f)
    }

    @Test fun theWrittenReplyCarriesOnFromItsScreenPreview() {
        show(ChatUiState(loading = false, state = SessionState.Working, items = items(6)))
        next { it.copy(partial = PartialReply(reply.take(60), "partial-1", afterSeq = 6)) }
        compose.mainClock.advanceTimeBy(500)
        val before = typing().shown()
        assertTrue("the preview types: ${before.length}", before.isNotEmpty() && before.length < 60)
        next { it.copy(items = it.items + ChatItem(7, ItemKind.ASSISTANT, text = reply), keys = mapOf(7L to "partial-1"), partial = null) }
        val after = typing().shown()
        assertTrue("carries on: ${after.length} after ${before.length}", after.length >= before.length && reply.startsWith(after))
        val typing = compose.onAllNodesWithTag("reply", useUnmergedTree = true).fetchSemanticsNodes().count { n -> n.shown().let { it.isNotEmpty() && reply.startsWith(it) } }
        assertEquals("one bubble for the reply", 1, typing)
        compose.mainClock.advanceTimeBy(4_000)
        assertEquals(reply, typing().shown())
    }

    @Test fun aMarkdownReplyKeepsTheVisiblePrefixWhenTakingOverItsScreenPreview() {
        val line = "检查端口和服务是否正常。".repeat(4)
        val preview = "重连退避\n先读配置。\n- $line"
        val written = "## 重连退避\n\n**先读**        配置。\n\n- $line\n- 最后检查日志。"
        val complete = "重连退避先读配置。${line}最后检查日志。"
        fun visible() = lowestReply().shown().filterNot { it.isWhitespace() || it == '#' || it == '-' }

        show(ChatUiState(loading = false, state = SessionState.Working, items = items(1)))
        next { it.copy(partial = PartialReply(preview, "partial-1", afterSeq = 1)) }
        compose.mainClock.advanceTimeBy(500)
        val before = visible()
        assertTrue("partway through the list: $before", before.startsWith("重连退避先读配置。检查") && before.length < complete.length)

        next {
            it.copy(items = it.items + ChatItem(2, ItemKind.ASSISTANT, text = written), keys = mapOf(2L to "partial-1"), partial = null)
        }
        val after = visible()
        assertTrue("keeps the prefix: $after after $before", after.startsWith(before))
        assertTrue("continues from the same place: $after after $before", complete.startsWith(after) && after.length <= before.length + 2)
        compose.mainClock.advanceTimeBy(4_000)
        assertEquals(complete, visible())
    }

    @Test fun aWrittenReplyTypesItsNewTailAfterTheScreenPreviewHasFinished() {
        val preview = "先读配置。"
        val tail = "再检查服务是否启动。"
        val written = "**先读**        配置。$tail"
        show(ChatUiState(loading = false, state = SessionState.Working, items = items(1)))
        next { it.copy(partial = PartialReply(preview, "partial-1", afterSeq = 1)) }
        compose.mainClock.advanceTimeBy(500)
        assertEquals(preview, lowestReply().shown())

        next {
            it.copy(items = it.items + ChatItem(2, ItemKind.ASSISTANT, text = written), keys = mapOf(2L to "partial-1"), partial = null)
        }
        val shown = lowestReply().shown().filterNot { it.isWhitespace() }
        assertTrue("preview stays visible while the tail types: $shown", shown.startsWith(preview) && shown.length < preview.length + tail.length)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(preview + tail, lowestReply().shown().filterNot { it.isWhitespace() })
    }

    private val formulaReply = """
        你说得对，我上一轮又把一个地方看岔了。我重新仔细看你的手写。

        你写的最后一条等式链是：

        ${'$'}${'$'}|A_{11}|^2+|A_{21}|^2 = |A_{12}|^2+(A_{21}^*)^2 = |A_{21}|^2+|A_{21}|^2 = 0${'$'}${'$'}

        你的疑问是：**${'$'}|A_{11}|^2+|A_{21}|^2${'$'} 为什么不是 0？你用矩阵乘法算出来就是 0。**

        ## 你的矩阵乘法哪里出了问题

        **如果是 ${'$'}A^2=0${'$'}：**
        ${'$'}${'$'}A^2=\begin{pmatrix}A_{11}&A_{12}\\A_{21}&A_{22}\end{pmatrix}^2=\begin{pmatrix}A_{11}^2+A_{12}A_{21}&A_{11}A_{12}+A_{12}A_{22}\\A_{21}A_{11}+A_{22}A_{21}&A_{21}A_{12}+A_{22}^2\end{pmatrix}=0${'$'}${'$'}

        **真正给出 ${'$'}|A_{11}|^2+|A_{21}|^2${'$'} 的式子是 ${'$'}B_{11}=1${'$'}：**
        ${'$'}${'$'}(A^+A)_{11}=A_{11}^*A_{11}+A_{21}^*A_{21}=|A_{11}|^2+|A_{21}|^2=1${'$'}${'$'}

        - ${'$'}B=A^+A=\mathrm{diag}(1,0)${'$'} **只告诉你第 1 列模长为 1**（不是 0）
        - ${'$'}A^2=0${'$'} **才告诉你 ${'$'}A_{11}=0${'$'}**
        - 两者结合才得到 ${'$'}|A_{21}|=1${'$'}

        你手写的 B 是 ${'$'}\begin{pmatrix}1&0\\0&0\end{pmatrix}${'$'}，所以：
        - ${'$'}B_{11}=1${'$'}，第 1 列模长平方和 = 1
        - ${'$'}B_{22}=0${'$'}，第 2 列全零

        喵呜~
    """.trimIndent()

    private val formulaPreview = formulaReply.replace("**", "").replace(Regex("(?m)^## "), "")

    private fun advanceReplyFrames(millis: Long) {
        val end = compose.mainClock.currentTime + millis
        while (compose.mainClock.currentTime < end) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun assertFormulaReplyIsComplete() {
        val expected = formulaPreview.lineSequence().joinToString("") { it.removePrefix("- ") }
        assertEquals(expected, newestShown().replace("•", "").replace("\n", ""))
    }

    @Test fun aReplyWithFormulaLedItemsTypesOutWithoutCrashing() {
        show(ChatUiState(loading = false, items = items(6)), darkTheme = true, style = UiStyle.Ink)
        next { it.copy(items = it.items + ChatItem(7, ItemKind.ASSISTANT, text = formulaReply)) }
        advanceReplyFrames(8_000)
        assertFormulaReplyIsComplete()
    }

    @Test fun aFormulaLedReplyTakesOverItsScreenPreview() {
        show(ChatUiState(loading = false, state = SessionState.Working, items = items(6)), darkTheme = true, style = UiStyle.Ink)
        var preview = ""
        for (chunk in formulaPreview.chunked(150)) {
            preview += chunk
            next { it.copy(partial = PartialReply(preview, "partial-1", afterSeq = 6)) }
            advanceReplyFrames(1_000)
        }
        next {
            it.copy(items = it.items + ChatItem(7, ItemKind.ASSISTANT, text = formulaReply), keys = mapOf(7L to "partial-1"), partial = null)
        }
        advanceReplyFrames(4_000)
        assertFormulaReplyIsComplete()
    }

    /** A code block scrolled sideways stays put as the reply grows and types out again, preview to written reply. */
    @Test fun aScrolledCodeBlockStaysPutAsTheReplyGrows() {
        val code = "看这段：\n\n```go\nx := " + "veryLongIdentifier".repeat(12) + "\n```\n"
        show(ChatUiState(loading = false, state = SessionState.Working, items = items(6)))
        next { it.copy(partial = PartialReply(code, "partial-1", afterSeq = 6)) }
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithTag("code", useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(1_000)
        fun offset() = compose.onNodeWithTag("code", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        val at = offset()
        assertTrue("scrolled sideways: $at", at > 0f)
        next { it.copy(partial = PartialReply(code + "\n再补一句。", "partial-1", afterSeq = 6)) }
        compose.mainClock.advanceTimeBy(300)
        next { it.copy(partial = PartialReply(code + "\n再补一句。还有一句。", "partial-1", afterSeq = 6)) }
        compose.mainClock.advanceTimeBy(300)
        next { it.copy(items = it.items + ChatItem(7, ItemKind.ASSISTANT, text = code + "\n再补一句。还有一句。最后一句。"), keys = mapOf(7L to "partial-1"), partial = null) }
        compose.mainClock.advanceTimeBy(300)
        assertEquals("while it types", at, offset(), 0.5f)
        compose.mainClock.advanceTimeBy(4_000)
        assertEquals("once typed out", at, offset(), 0.5f)
    }

    @Test fun theStatusLineShowsTheScreensWordsAndATurningStar() {
        show(
            ChatUiState(
                loading = false, items = items(4), state = SessionState.Working, workingSinceMillis = System.currentTimeMillis() - 42_000,
                activity = "Incubating… (1m 17s · ↓ 7.5k tokens)",
            ),
        )
        compose.mainClock.advanceTimeBy(durM)
        compose.onNodeWithText("Incubating…").assertIsDisplayed()
        compose.onNodeWithText(" (1m 17s · ↓ 7.5k tokens)").assertIsDisplayed()
        compose.onNodeWithContentDescription("中断").assertIsDisplayed()
        compose.onNode(hasText("工作中", substring = true)).assertDoesNotExist()
        fun star() = compose.onNodeWithTag("cliStar").captureToImage().asAndroidBitmap()
        val a = star()
        compose.mainClock.advanceTimeBy(130)
        assertFalse("the star turns", a.sameAs(star()))
    }

    @Test fun withoutTheScreenItSaysWorkingForNSeconds() {
        show(ChatUiState(loading = false, items = items(4), state = SessionState.Working, workingSinceMillis = System.currentTimeMillis() - 42_000))
        compose.mainClock.advanceTimeBy(durM)
        compose.onNode(hasText("工作中 · ", substring = true)).assertIsDisplayed()
        compose.onNodeWithContentDescription("中断").assertIsDisplayed()
        fun star() = compose.onNodeWithTag("cliStar").captureToImage().asAndroidBitmap()
        val a = star()
        compose.mainClock.advanceTimeBy(130)
        assertTrue("no star", a.sameAs(star()))
    }

    /** The README's permission screenshot (ScreensTest 06-chat-permission). */
    private val readme = ChatUiState(
        session = session("s", "clauderc 协议", SessionState.Pending, "default"), loading = false, items = items(7),
        model = "claude-opus-5-5", permissionMode = "default",
    )
    private val goVet = Pending("t3", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "go vet ./... && go test ./internal/protocol/...") })

    private fun panel() = compose.onAllNodesWithTag("pendingPanel", useUnmergedTree = true).onLast()

    private fun DpRect.rect() = Rect(left.value, top.value, right.value, bottom.value)

    @Test fun thePanelIsWholeFromTheFirstFrame() {
        show(readme)
        compose.mainClock.advanceTimeBy(durM)
        val screen = compose.onRoot().getBoundsInRoot().rect()
        next { it.copy(pending = goVet) }
        val firstHeight = panel().fetchSemanticsNode().size.height
        val allow = compose.onNodeWithText("允许", useUnmergedTree = true).getUnclippedBoundsInRoot().rect()
        assertTrue("「允许」 at $allow inside the panel", panel().getUnclippedBoundsInRoot().rect().let { allow.top >= it.top && allow.bottom <= it.bottom })
        // On the way up only the screen's edge hides part of it: nothing clips it to the composer's height.
        for (t in listOf(durM / 8, durM / 4, durM / 2)) {
            compose.mainClock.advanceTimeBy(durM / 8)
            val clipped = panel().getBoundsInRoot().rect()
            val whole = panel().getUnclippedBoundsInRoot().rect()
            assertTrue("on screen at ${t}ms", whole.top < screen.bottom)
            assertEquals("nothing clips it at ${t}ms", whole.height, clipped.height, 0.5f)
        }
        compose.mainClock.advanceTimeBy(durM)
        assertEquals("laid out at its full height from the first frame", firstHeight, panel().fetchSemanticsNode().size.height)
        val range = panel().fetchSemanticsNode().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        assertEquals("the README panel fits without scrolling", 0f, range?.maxValue?.invoke() ?: 0f)
    }

    @Test fun aTenThousandCharReplySplitsIntoOrderedChunksAndTypesContinuously() {
        // Each section is exactly 1000 chars so replyChunks produces 0..2, 3..5, 6..8, 9..9.
        val sections = (1..10).map { i ->
            val tag = "%02d".format(i)
            "段$tag：" + "长回复分段验证文本。".repeat(99) + "【结尾$tag】"
        }
        val longReply = sections.joinToString("\n\n")
        val blocks = mdBlocks(longReply)
        val expectedChunks = replyChunks(blocks)
        assertEquals("10k reply splits into 4 chunks", listOf(0..2, 3..5, 6..8, 9..9), expectedChunks)

        show(ChatUiState(loading = false, items = items(1)))
        next { it.copy(items = it.items + ChatItem(2, ItemKind.ASSISTANT, text = longReply)) }
        // Before typing advances past chunk 0, unreached chunks are not drawn.
        assertEquals("only chunk 0 is drawn at progress 0", 1, compose.onAllNodesWithTag("reply", useUnmergedTree = true).fetchSemanticsNodes().size)

        // One second in, large-batch jump has exposed all chunks and the bottom chunk is typing its tail.
        compose.mainClock.advanceTimeBy(1_000)
        val midTail = lowestReply().shown()
        assertTrue("bottom chunk starts with last chunk text and is still typing: ${midTail.take(20)} len=${midTail.length}", midTail.startsWith("段10：") && !midTail.endsWith("【结尾10】"))

        compose.mainClock.advanceTimeBy(3_000)
        val doneTail = lowestReply().shown()
        assertTrue("bottom chunk finishes typing in full", doneTail.startsWith("段10：") && doneTail.endsWith("【结尾10】"))

        // Verify top-to-bottom order across chunk boundaries (use unclipped bounds since 1000-char blocks exceed one viewport).
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("chatList").performScrollToNode(hasText("段07：", substring = true))
        compose.waitForIdle()
        val p07 = compose.onNode(hasText("段07：", substring = true), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val p09 = compose.onNode(hasText("段09：", substring = true), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("section 07 ($p07) sits above section 09 ($p09)", p07.bottom <= p09.top)

        compose.onNodeWithTag("chatList").performScrollToNode(hasText("段01：", substring = true))
        compose.waitForIdle()
        val p01 = compose.onNode(hasText("段01：", substring = true), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val p02 = compose.onNode(hasText("段02：", substring = true), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("section 01 ($p01) sits above section 02 ($p02)", p01.bottom <= p02.top)
    }

    @Test fun aGrowingPartialCrossingSixThousandCharsSplitsWithoutRetyping() {
        // Each section is 1002 chars so 5 sections stay below 6000 and 7 sections split into 0..2, 3..5, 6..6.
        fun sec(i: Int): String = "第%02d段：".format(i) + "这是一段持续流式输出的回复内容。".repeat(62) + "（完%02d）".format(i)
        val part5 = (1..5).joinToString("\n\n", transform = ::sec)
        val part7 = (1..7).joinToString("\n\n", transform = ::sec)
        val formal7 = (1..7).joinToString("\n\n") { i -> "**第%02d段：**".format(i) + "这是一段持续流式输出的回复内容。".repeat(62) + "（完%02d）".format(i) }
        assertTrue("part5 stays unsplit: ${part5.length}", part5.length < SPLIT_REPLY_CHARS)
        assertTrue("part7 crosses split threshold: ${part7.length}", part7.length > SPLIT_REPLY_CHARS)

        show(ChatUiState(loading = false, state = SessionState.Working, items = items(1)))
        // Grow gradually below 6000 so it types to ~5000 without jumping.
        var built = ""
        for (i in 1..5) {
            built = if (built.isEmpty()) sec(i) else "$built\n\n${sec(i)}"
            next { it.copy(partial = PartialReply(built, "partial-1", afterSeq = 1)) }
            compose.mainClock.advanceTimeBy(5_000)
        }
        assertEquals("unsplit below 6000", 1, compose.onAllNodesWithTag("reply", useUnmergedTree = true).fetchSemanticsNodes().size)
        val beforeTail = lowestReply().shown()
        assertTrue("part5 finished typing before split: ${beforeTail.takeLast(20)}", beforeTail.endsWith("（完05）"))

        // Crossing 6000 splits into multiple chunks without re-typing what was already displayed;
        // chunk 2 (starting at section 7, past 6000) is not drawn until typing reaches it.
        next { it.copy(partial = PartialReply(part7, "partial-1", afterSeq = 1)) }
        val onSplitNodes = compose.onAllNodesWithTag("reply", useUnmergedTree = true).fetchSemanticsNodes()
        val onSplitBottom = onSplitNodes.maxBy { it.boundsInRoot.bottom }.shown()
        assertTrue("keeps already displayed text across split: ${onSplitBottom.take(20)}", "第05段：" in onSplitBottom && onSplitBottom.endsWith("（完05）"))
        assertFalse("unreached section 07 is not drawn yet", "第07段：" in onSplitBottom)

        // Typing continues into section 06 and then opens chunk 2 for section 07.
        compose.mainClock.advanceTimeBy(6_000)
        val afterPartialBottom = lowestReply().shown()
        assertTrue("typing advances into section 07: ${afterPartialBottom.take(20)}", "第07段：" in afterPartialBottom)

        // Formal Markdown reply takes over the same key without resetting progress.
        next {
            it.copy(
                items = it.items + ChatItem(2, ItemKind.ASSISTANT, text = formal7),
                keys = mapOf(2L to "partial-1"),
                partial = null,
            )
        }
        val onTakeover = lowestReply().shown()
        assertTrue("takeover keeps section 07 visible without retyping: ${onTakeover.take(20)}", "第07段：" in onTakeover)
        compose.mainClock.advanceTimeBy(4_000)
        assertTrue("finishes typing formal reply", lowestReply().shown().endsWith("（完07）"))
    }

    @Test fun aPreviewStartingWithALongFormulaDoesNotResetProgressAcrossAppends() {
        // A ~140-char display formula at the start takes >3s at 40 chars/s; repeated 150ms appends must not reset progress to 0.
        val formula = "\$\$\n\\sum_{k=1}^{n} \\frac{a_k + b_k}{c_k + d_k} = \\int_{0}^{1} \\left(x^2 + 2xy + y^2 + z^2\\right)\\,dx + \\prod_{j=1}^{m}(1+t_j)\n\$\$"
        assertTrue("formula is long enough to span many appends: ${formula.length}", formula.length > 100)
        show(ChatUiState(loading = false, state = SessionState.Working, items = items(1)))
        var preview = "$formula\n\n推导完成"
        next { it.copy(partial = PartialReply(preview, "partial-formula", afterSeq = 1)) }
        // Append small tails 20 times (every 150ms = 3.0s total) while the leading formula is still typing out.
        repeat(20) { i ->
            compose.mainClock.advanceTimeBy(150)
            preview += "，第${i}步"
            next { it.copy(partial = PartialReply(preview, "partial-formula", afterSeq = 1)) }
        }
        compose.mainClock.advanceTimeBy(1_500)
        val shown = lowestReply().shown()
        assertTrue("leading formula finished typing without being reset: ${shown.take(60)}", "\\prod_{j=1}^{m}" in shown && "推导完成" in shown)
    }

    @Test fun unrelatedStatusUpdatesDoNotDisturbLongReplyTyping() {
        val longReply = (1..8).joinToString("\n\n") { i ->
            "段%02d：".format(i) + "测试状态行刷新不重建条目。".repeat(82) + "【完%02d】".format(i)
        }
        show(ChatUiState(loading = false, state = SessionState.Working, items = items(1), activity = "Thinking… (1s)"))
        next { it.copy(partial = PartialReply(longReply.take(500), "partial-act", afterSeq = 1)) }
        var lastLen = 0
        repeat(10) { tick ->
            compose.mainClock.advanceTimeBy(250)
            next { it.copy(activity = "Thinking… (${tick + 2}s)") }
            val len = lowestReply().shown().length
            assertTrue("typing never resets on activity tick $tick: $len after $lastLen", len >= lastLen)
            lastLen = len
        }
        assertTrue("made steady progress across activity ticks: $lastLen", lastLen > 200)
    }
}

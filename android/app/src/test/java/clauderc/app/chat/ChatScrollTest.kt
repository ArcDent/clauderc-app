package clauderc.app.chat

import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import clauderc.app.data.UiStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.Hunk
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.ToolCall
import clauderc.app.protocol.ToolResult
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlinx.serialization.json.JsonNull
import clauderc.app.testing.session
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** plans.md 1: what the chat list does when a message arrives, frame by frame on a frozen clock. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp")
class ChatScrollTest {
    @get:Rule val compose = createComposeRule()

    private var state by mutableStateOf(
        ChatUiState(
            session = session("s-1", "协议", SessionState.Idle, "default"),
            loading = false,
            items = (51L..100L).map { ChatItem(it, ItemKind.ASSISTANT, text = "第 $it 条") },
        ),
    )
    private var bottomPadding = 0.dp

    /** The tool calls whose whole text the page asked for. */
    private val fetched = mutableListOf<String>()

    /**
     * These check the list's own scrolling, so they run in 格栅, which stacks the bar and the input
     * above and below the list: its edges are where the messages start and end. 清玻 floats both over
     * a full-screen list, which [clearFloatsTheBarsOverTheMessages] checks.
     */
    private fun show(style: UiStyle = UiStyle.Grid) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = style) {
                bottomPadding = Clauderc.spacing.sp5
                ChatScreen(state, ChatActions(onToolOutput = { fetched += it }))
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun bounds(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun px(dp: Dp) = with(compose.density) { dp.toPx() }

    @Test fun wideCodeAndTablesScrollInsideTheReplyNotTheList() {
        val code = "func backoff(n int) time.Duration { return min(time.Second<<n, 30*time.Second) } // 移位溢出成负数时也取上限"
        val reply = "看这段：\n\n```go\n$code\n```\n\n| 第几次 | 等待 | 累计 | 说明 |\n|---|---|---|---|\n| 1 | 1s | 1s | 断开后立刻安排，之后每次翻倍直到上限 |"
        state = state.copy(items = state.items + ChatItem(101, ItemKind.ASSISTANT, text = reply))
        show()
        val reply101 = compose.onAllNodesWithTag("reply", useUnmergedTree = true).fetchSemanticsNodes().maxBy { it.boundsInRoot.bottom }.boundsInRoot
        val list = compose.onNodeWithTag("chatList").fetchSemanticsNode().boundsInRoot
        assertTrue("the bubble ${reply101.width} is at most 82% of ${list.width}", reply101.width <= 0.82f * (list.width - 2 * px(16.dp)) + 1f)
        for (tag in listOf("code", "table")) {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            fun range() = node.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertTrue("$tag is wider than the bubble", range().maxValue() > 0f && range().value() == 0f)
            val top = compose.onNodeWithText("看这段：", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            node.performTouchInput { swipeLeft() }
            compose.mainClock.advanceTimeBy(1_000)
            assertTrue("$tag scrolled: ${range().value()}", range().value() > 0f)
            assertEquals("the list stays put", top, compose.onNodeWithText("看这段：", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top, 0.5f)
        }
    }

    @Test fun atTheBottomANewReplyPushesTheOthersUpWithoutBouncing() {
        show()
        val before = bounds("第 100 条").top
        state = state.copy(items = state.items + ChatItem(101, ItemKind.ASSISTANT, text = "新回复第一行\n第二行\n第三行"))
        val tops = mutableListOf<Float>()
        repeat(38) {
            compose.mainClock.advanceTimeBy(16)
            tops += bounds("第 100 条").top
        }
        tops.zipWithNext().forEachIndexed { i, (a, b) -> assertTrue("frame ${i + 1}: the previous message went down from $a to $b", b <= a + 0.5f) }
        assertTrue("the previous message moved up: $before -> ${tops.last()}", tops.last() < before - 1f)
        // The reply's text ends its bubble's 10dp padding above the list's bottom padding.
        val listBottom = compose.onNodeWithTag("chatList").fetchSemanticsNode().boundsInRoot.bottom
        assertEquals(listBottom - px(bottomPadding) - px(10.dp), bounds("新回复第一行\n第二行\n第三行").bottom, 1f)
    }

    /** 清玻: the list runs under the bar and the input, and the newest message still ends above the input. */
    @Test fun clearFloatsTheBarsOverTheMessages() {
        show(UiStyle.Clear)
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("chatList").fetchSemanticsNode().boundsInRoot
        assertEquals("the list reaches the bottom of the screen", screen.bottom, list.bottom, 1f)
        assertTrue("the list starts under the bar: ${list.top}", list.top < px(56.dp))
        val input = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot
        assertTrue("the newest message ends above the input: ${bounds("第 100 条").bottom} vs ${input.top}", bounds("第 100 条").bottom < input.top)
    }

    @Test fun aDeliveredMessageStaysWhereItsBubbleWas() {
        state = state.copy(outgoing = listOf(Outgoing(1, "把错误码写进 errors.go", emptyList(), afterSeq = 100)))
        show()
        val before = bounds("把错误码写进 errors.go")
        val delivered = listOf(ChatItem(101, ItemKind.USER, text = "把错误码写进 errors.go"))
        state = state.copy(
            items = mergeNewer(state.items, delivered),
            outgoing = settleOutgoing(state.outgoing, delivered),
            keys = state.keys + deliveredKeys(state.outgoing, delivered),
        )
        repeat(3) {
            compose.mainClock.advanceTimeByFrame()
            val now = bounds("把错误码写进 errors.go")
            assertEquals(before.top, now.top, 0.5f)
            assertEquals(before.left, now.left, 0.5f)
        }
    }

    @Test fun awayFromTheBottomWhatYouReadStaysPut() {
        show()
        compose.onNodeWithTag("chatList").performScrollToIndex(20)
        compose.mainClock.advanceTimeBy(1_000)
        val before = bounds("第 80 条").top
        state = state.copy(items = state.items + ChatItem(101, ItemKind.ASSISTANT, text = "新回复"))
        compose.mainClock.advanceTimeBy(600)
        assertEquals(before, bounds("第 80 条").top, 0.5f)
    }

    /** A Read near the bottom, folded, with [n] lines. */
    private fun withRead(n: Int) {
        val read = ToolCall("toolu_R", "Read", "读取 design.md", JsonNull, ToolResult((1..n).joinToString("\n") { "$it\tline $it" }, lines = n))
        state = state.copy(items = state.items.dropLast(1) + ChatItem(100, ItemKind.TOOL, tool = read) + ChatItem(101, ItemKind.ASSISTANT, text = "读完了"))
    }

    /** The tapped row's top each frame, and the top of [also] while it is still on screen. */
    private fun framesWhile(text: String, frames: Int, also: String): Pair<List<Float>, List<Float>> {
        val tops = mutableListOf<Float>()
        val below = mutableListOf<Float>()
        repeat(frames) {
            compose.mainClock.advanceTimeByFrame()
            tops += bounds(text).top
            compose.onAllNodesWithText(also).fetchSemanticsNodes().firstOrNull { it.layoutInfo.isPlaced && it.boundsInRoot.height > 0f }?.let { below += it.boundsInRoot.top }
        }
        return tops to below
    }

    @Test fun openingAToolRowKeepsItInPlaceAndPushesWhatIsBelowDown() {
        withRead(30)
        show()
        val before = bounds("design.md").top
        val replyBefore = bounds("读完了").top
        compose.onNodeWithText("design.md").performClick()
        val (tops, below) = framesWhile("design.md", 30, "读完了")
        tops.forEachIndexed { i, t -> assertEquals("frame ${i + 1}: the tapped row moved", before, t, 1f) }
        below.zipWithNext().forEachIndexed { i, (a, b) -> assertTrue("frame ${i + 1}: the reply went back up from $a to $b", b >= a - 0.5f) }
        // Pushed down, out of sight by the end: 30 lines do not fit under the row.
        assertTrue("the reply below was not pushed down: $replyBefore -> $below", below.size < 30 && (below.isEmpty() || below.last() > replyBefore + 1f))
    }

    @Test fun foldingItBackKeepsTheRowInPlaceToo() {
        withRead(30)
        show()
        compose.onNodeWithText("design.md").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        val before = bounds("design.md").top
        compose.onNodeWithText("design.md").performClick()
        val (tops, below) = framesWhile("design.md", 30, "读完了")
        tops.forEachIndexed { i, t -> assertEquals("frame ${i + 1}: the tapped row moved", before, t, 1f) }
        assertTrue("the reply below came back up into view", below.isNotEmpty())
    }

    @Test fun openingAndFoldingAMergedRowKeepsItsHeadInPlace() {
        val reads = (1..4).map { i ->
            ChatItem(96L + i, ItemKind.TOOL, tool = ToolCall("toolu_$i", "Read", "读取 f$i.go", JsonNull, ToolResult("1\tpackage x")))
        }
        state = state.copy(items = state.items.dropLast(4) + reads + ChatItem(101, ItemKind.ASSISTANT, text = "读完了"))
        show()
        val head = "读取 4 个文件"
        val before = bounds(head).top
        compose.onNodeWithText(head).performClick()
        val (open, _) = framesWhile(head, 30, "读完了")
        open.forEachIndexed { i, t -> assertEquals("opening, frame ${i + 1}: the head moved", before, t, 1f) }
        compose.onNodeWithText(head).performClick()
        val (shut, _) = framesWhile(head, 40, "读完了")
        shut.forEachIndexed { i, t -> assertEquals("folding, frame ${i + 1}: the head moved", before, t, 1f) }
        compose.onNodeWithText("f4.go").assertDoesNotExist()
    }

    @Test fun openingALongReadKeepsTheRowInPlace() {
        withRead(300)
        show()
        val before = bounds("design.md").top
        compose.onNodeWithText("design.md").performClick()
        val (tops, _) = framesWhile("design.md", 40, "读完了")
        tops.forEachIndexed { i, t -> assertEquals("frame ${i + 1}: the tapped row moved", before, t, 1f) }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("after the animation the row is where it was", before, bounds("design.md").top, 1f)
    }

    @Test fun foldingALongReadKeepsTheRowInPlace() {
        withRead(300)
        show()
        compose.onNodeWithText("design.md").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        val before = bounds("design.md").top
        compose.onNodeWithText("design.md").performClick()
        val (tops, _) = framesWhile("design.md", 40, "读完了")
        tops.forEachIndexed { i, t -> assertEquals("frame ${i + 1}: the tapped row moved", before, t, 1f) }
    }

    @Test fun openingACutReadInARunFetchesItsWholeTextAndKeepsTheRowInPlace() {
        fun logLines(n: Int) = (0 until n).joinToString("\n") { "${it + 1}\t2026-11-17T03:${"%02d".format(it / 60)}:${"%02d".format(it % 60)} INFO request id=r-${"%04d".format(it)} method=chat.items took=${it % 37}ms" }
        fun call(id: String, name: String, summary: String, result: ToolResult) = ToolCall(id, name, summary, JsonNull, result)
        val cut = ToolResult(logLines(120), lines = 601, truncated = true)
        val whole = ToolResult(logLines(250), lines = 601)
        val run = listOf(
            call("t1", "Grep", "搜索 TODO", ToolResult("notes.md")),
            call("t2", "Read", "读取 notes.md", ToolResult(logLines(31), lines = 31)),
            call("t3", "Read", "读取 big.log", cut),
            call("t4", "Bash", "运行 ls", ToolResult("big.log")),
            call("t5", "Bash", "运行 wc -l notes.md big.log", ToolResult("630 total")),
        ).mapIndexed { i, t -> ChatItem(3L + i, ItemKind.TOOL, tool = t) }
        state = state.copy(
            items = (-8L..0L).map { ChatItem(it, ItemKind.ASSISTANT, text = "早先的第 $it 条\n第二行\n第三行") } +
                listOf(ChatItem(1, ItemKind.USER, text = "请严格按顺序做下面 9 步"), ChatItem(2, ItemKind.ASSISTANT, text = "好的")) + run +
                ChatItem(8, ItemKind.ASSISTANT, text = "完成"),
        )
        show()
        // Getting there runs on the real clock; only the frames after the tap are stepped.
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("toolGroup").performClick()
        compose.onNodeWithText("big.log").performScrollTo().performClick()
        // Opening the cut read asks for its whole text at once.
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        assertEquals(listOf("t3"), fetched)
        state = state.copy(fetchingResults = setOf("t3"))
        compose.mainClock.advanceTimeBy(500)
        val before = bounds("big.log").top
        state = state.copy(fetchingResults = emptySet(), fullResults = mapOf("t3" to whole))
        // The whole text swaps in inside the box, which keeps its height: the row stays where it is.
        val tops = List(40) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText("big.log").fetchSemanticsNodes().firstOrNull { it.layoutInfo.isPlaced }?.boundsInRoot?.top ?: Float.NaN
        }
        tops.forEachIndexed { i, t -> assertEquals("frame ${i + 1}: the tapped row moved", before, t, 1f) }
    }
}

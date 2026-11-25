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

    private fun show(initial: ChatUiState) {
        state = initial
        compose.mainClock.autoAdvance = false
        // 印版 stacks the input under the list, so the list's bottom is where the messages end.
        compose.setContent { ClaudercTheme(darkTheme = false, style = UiStyle.Plate) { ChatScreen(state, ChatActions()) } }
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
}

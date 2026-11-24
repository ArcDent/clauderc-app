package clauderc.app.chat

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewModelScope
import clauderc.app.data.Fleet
import clauderc.app.protocol.ChatImageResponse
import clauderc.app.protocol.ChatOpenResponse
import clauderc.app.protocol.Image
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.SessionList
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.decode
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import clauderc.app.ui.components.SnackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.snapshots.Snapshot
import clauderc.app.testing.ShadowMagnifier
import kotlin.math.abs
import kotlinx.coroutines.awaitCancellation
import clauderc.app.data.UiStyle
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import org.junit.Assert.assertFalse
import clauderc.app.ui.theme.ClaudercMotion
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.protocol.Answer
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.Command
import clauderc.app.protocol.Hunk
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.Option
import clauderc.app.protocol.Pending
import clauderc.app.protocol.PendingKind
import clauderc.app.protocol.Question
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.ToolCall
import clauderc.app.protocol.ToolImage
import clauderc.app.protocol.ToolResult
import clauderc.app.testing.session
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercColors
import clauderc.app.ui.theme.ClaudercTheme
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tasks 13.1 to 13.4: what the chat page shows and sends. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp")
class ChatScreenTest {
    @get:Rule val compose = createComposeRule()

    private val bash = ToolCall(
        "toolu_01A", "Bash", "运行 go test ./internal/protocol/...",
        buildJsonObject { put("command", "go test ./internal/protocol/...") },
        ToolResult("ok  \tclauderc/internal/protocol\t0.412s"),
    )

    private fun page(seqs: LongRange) = seqs.map { ChatItem(it, ItemKind.ASSISTANT, text = "第 $it 条") }

    private fun baseState() = ChatUiState(
        session = session("s-need", "clauderc 协议", SessionState.Idle, "default"),
        loading = false,
        items = page(51L..98L) + ChatItem(99, ItemKind.TOOL, tool = bash) + ChatItem(100, ItemKind.USER, text = "先别提交\n跑一遍 go vet"),
        hasMore = true,
        model = "claude-opus-5-5",
        permissionMode = "default",
        commands = listOf(Command("model", "切换模型"), Command("memory", "编辑记忆文件"), Command("compact", "压缩对话上下文")),
    )

    private fun show(state: ChatUiState, actions: ChatActions = ChatActions()) {
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, actions) } }
    }

    private fun clipboardText() = ApplicationProvider.getApplicationContext<android.app.Application>()
        .getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    /** Round 8: the copy button in a code block's head. */
    @Test fun theCodeCopyButtonCopiesTheCodeAndTurnsIntoATickAndBack() {
        val m = ClaudercMotion()
        val code = "func backoff(n int) time.Duration {\n\treturn time.Second << n\n}"
        compose.mainClock.autoAdvance = false
        show(baseState().copy(items = listOf(ChatItem(1, ItemKind.ASSISTANT, text = "看这段：\n\n```go\n$code\n```")), hasMore = false))
        compose.mainClock.advanceTimeBy(1_000)
        val button = compose.onNodeWithContentDescription("复制代码")
        fun frame() = button.captureToImage().asAndroidBitmap()
        val idle = frame()
        button.performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the code as written, no language or fences", code, clipboardText())
        compose.mainClock.advanceTimeBy(m.durS / 2L)
        val leaving = frame()
        compose.mainClock.advanceTimeBy(m.durS + m.durM.toLong())
        val tick = frame()
        compose.onNode(hasText("已复制", substring = true)).assertDoesNotExist()
        // 1.5s after the tap it turns back, animated too.
        compose.mainClock.advanceTimeBy(1_500L - m.durS / 2 - m.durS - m.durM + m.durS)
        val returning = frame()
        compose.mainClock.advanceTimeBy(m.durS + m.durM + 200L)
        val back = frame()
        assertFalse("the copy icon leaves gradually", leaving.sameAs(idle) || leaving.sameAs(tick))
        assertFalse("then shows a tick", tick.sameAs(idle))
        assertFalse("which turns back gradually", returning.sameAs(tick) || returning.sameAs(back))
        assertTrue("back to the copy icon", back.sameAs(idle))
    }

    /** Stands in for the system's text toolbar: notes the menu the page asks for while it is up. */
    private class FakeToolbar : TextContextMenuProvider {
        var shown: TextContextMenuDataProvider? = null
        var times = 0

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            shown = dataProvider
            times++
            try {
                awaitCancellation()
            } finally {
                if (shown === dataProvider) shown = null
            }
        }

        fun click(key: Any) = shown!!.data().components.filterIsInstance<TextContextMenuItem>().single { it.key == key }
            .onClick(object : TextContextMenuSession { override fun close() {} })
    }

    /**
     * Waits until [shown], the selection's toolbar having come up: it comes up a beat after the long
     * press, and waitForIdle does not always wait for it.
     */
    private fun awaitToolbar(what: String, shown: () -> Boolean) = compose.waitUntil(what, 3_000, shown)

    private fun showSelecting(state: ChatUiState, toolbar: FakeToolbar, style: UiStyle = UiStyle.Clear): () -> ClaudercColors {
        var colors: ClaudercColors? = null
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = style) {
                colors = Clauderc.colors
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) { ChatScreen(state, ChatActions()) }
            }
        }
        compose.waitForIdle()
        return { colors!! }
    }

    private val selectable = "跑完了：\n\n```sh\ngo vet ./...\n```\n\n没有问题，可以提交。"

    @Config(shadows = [ShadowMagnifier::class])
    @Test fun aFinishedReplyAndTheUsersMessageSelectOnALongPress() {
        val toolbar = FakeToolbar()
        showSelecting(baseState().copy(items = listOf(ChatItem(1, ItemKind.USER, text = "跑一遍 go vet 再提交"), ChatItem(2, ItemKind.ASSISTANT, text = selectable)), hasMore = false), toolbar)
        compose.onNodeWithText("没有问题，可以提交。").performTouchInput { longClick() }
        awaitToolbar("the reply's selection brings up the toolbar") { toolbar.shown != null }
        // Select all and copy: the code head's language and button are not part of it.
        toolbar.click(TextContextMenuKeys.SelectAllKey)
        compose.waitForIdle()
        toolbar.click(TextContextMenuKeys.CopyKey)
        compose.waitForIdle()
        val copied = clipboardText().orEmpty()
        assertTrue("copied 「$copied」", copied.startsWith("跑完了：") && "go vet ./..." in copied && copied.endsWith("没有问题，可以提交。"))
        assertFalse("no button or language in 「$copied」", "复制" in copied || "sh" in copied)
        compose.onNodeWithText("已复制").assertIsDisplayed()

        val before = toolbar.times
        compose.onNodeWithText("跑一遍 go vet 再提交").performTouchInput { longClick() }
        awaitToolbar("the user's message selects too") { toolbar.times > before && toolbar.shown != null }
    }

    @Config(shadows = [ShadowMagnifier::class])
    @Test fun aReplyTypingOutCannotBeSelected() {
        val toolbar = FakeToolbar()
        val long = "第一，聊天记录改成文件变了才读。".repeat(40)
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf(baseState().copy(items = page(1L..3L), hasMore = false, opened = true))
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) { ChatScreen(state, ChatActions()) }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnUiThread { state = state.copy(items = state.items + ChatItem(4, ItemKind.ASSISTANT, text = long)) }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(400)
        val typing = compose.onNode(hasText("第一，聊天记录", substring = true))
        typing.performTouchInput { longClick() }
        compose.mainClock.advanceTimeBy(100)
        val shown = typing.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("still typing: ${shown.length} of ${long.length}", shown.length < long.length)
        assertEquals("no selection while it types", 0, toolbar.times)
    }

    @Config(shadows = [ShadowMagnifier::class])
    @Test fun stickersSelectionOnTheUsersBubbleShowsOnItsAccent() {
        val toolbar = FakeToolbar()
        val colors = showSelecting(baseState().copy(items = listOf(ChatItem(1, ItemKind.USER, text = "跑一遍 go vet 再提交")), hasMore = false), toolbar, UiStyle.Sticker)
        val bubble = compose.onNodeWithText("跑一遍 go vet 再提交")
        bubble.performTouchInput { longClick(Offset(width * 0.3f, height / 2f)) }
        awaitToolbar("the user's message selects") { toolbar.shown != null }
        val c = colors()
        val selection = c.onAccent.copy(alpha = 0.28f).compositeOver(c.accent)
        assertTrue("the selection $selection stands out from the bubble ${c.accent}", abs(selection.luminance() - c.accent.luminance()) > 0.05f)
        val px = bubble.captureToImage().toPixelMap()
        var hits = 0
        for (x in 0 until px.width) for (y in 0 until px.height) {
            val p = px[x, y]
            if (abs(p.red - selection.red) < 0.02f && abs(p.green - selection.green) < 0.02f && abs(p.blue - selection.blue) < 0.02f) hits++
        }
        assertTrue("selected text shows in the selection colour: $hits pixels", hits > 50)
    }

    /** Round 12: 贴纸's bubbles keep their 2dp ink outline but no longer sit on a hard ink shadow. */
    @Test fun stickersBubblesHaveNoShadow() {
        var colors: ClaudercColors? = null
        val items = listOf(ChatItem(1, ItemKind.USER, text = "跑一遍 go vet 再提交"), ChatItem(2, ItemKind.ASSISTANT, text = "好的，我先跑一遍。"))
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = UiStyle.Sticker) {
                colors = Clauderc.colors
                ChatScreen(baseState().copy(items = items, hasMore = false), ChatActions())
            }
        }
        compose.waitForIdle()
        val ink = colors!!.ink
        val image = compose.onRoot().captureToImage().toPixelMap()
        val d = compose.density.density
        for (text in items.map { it.text!! }) {
            // Down the middle from under the text: the padding, the outline, then the page; a shadow would make the ink run longer.
            val b = compose.onNodeWithText(text).getBoundsInRoot()
            val x = ((b.left + b.right) / 2).value * d
            var run = 0
            var longest = 0
            for (y in (b.bottom.value * d).toInt() + 1 until ((b.bottom.value + 20) * d).toInt()) {
                val p = image[x.toInt(), y]
                run = if ((abs(p.red - ink.red) + abs(p.green - ink.green) + abs(p.blue - ink.blue)) / 3 < 0.1f) run + 1 else 0
                longest = maxOf(longest, run)
            }
            assertTrue("「$text」: only the outline is ink, ${longest / d}dp", longest / d in 1f..3.5f)
        }
    }

    /** Round 12: 格栅's code block is ringed in a 1dp line of the text colour, so its grey tells from the page. */
    @Test fun gridsCodeBlockHasAThinLineInTheTextColour() {
        var colors: ClaudercColors? = null
        val reply = ChatItem(1, ItemKind.ASSISTANT, text = "看这段：\n\n```go\nfunc backoff(n int) time.Duration {\n\treturn time.Second << n\n}\n```")
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = UiStyle.Grid) {
                colors = Clauderc.colors
                ChatScreen(baseState().copy(items = listOf(reply), hasMore = false), ChatActions())
            }
        }
        compose.waitForIdle()
        val c = colors!!
        val image = compose.onRoot().captureToImage().toPixelMap()
        val d = compose.density.density
        val b = compose.onNodeWithTag("codeBlock").getBoundsInRoot()
        val left = b.left.value * d
        val top = b.top.value * d
        val right = b.right.value * d
        val middle = (b.top + b.bottom).value / 2 * d
        fun near(what: String, want: androidx.compose.ui.graphics.Color, x: Float, y: Float) {
            val p = image[x.toInt(), y.toInt()]
            assertTrue("$what: $p, want $want", (abs(p.red - want.red) + abs(p.green - want.green) + abs(p.blue - want.blue)) / 3 < 0.03f)
        }
        near("the left line", c.fg, left + 0.5f * d, middle)
        near("the right line", c.fg, right - 0.5f * d, middle)
        near("the top line", c.fg, (left + right) / 2, top + 0.5f * d)
        near("the grey inside", c.surface, left + 4 * d, middle)
    }

    @Test fun opensAtTheBottomWithToolRowsFolded() {
        show(baseState())
        compose.onNodeWithText("先别提交\n跑一遍 go vet").assertIsDisplayed()
        compose.onNodeWithText("go test ./internal/protocol/...").assertIsDisplayed()
        compose.onNode(hasText("0.412s", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("go test ./internal/protocol/...").performClick()
        compose.onNode(hasText("0.412s", substring = true)).assertIsDisplayed()
    }

    /** Short of room in the bar, the model's name gives way and 「上下文 N%」 shows whole. */
    @Test fun theContextShowsWholeBesideALongModelName() {
        show(baseState().copy(
            session = session("s-long", "chat-link-actions-latex", SessionState.Idle, "default", context = clauderc.app.protocol.SessionContext(84_000, 200_000)),
            model = "deepseek-v4.1-flash[1M]", permissionMode = "auto",
        ))
        fun layout(text: String): androidx.compose.ui.text.TextLayoutResult {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            return results.first()
        }
        // Whole on its one line: nothing left for a second line, nothing cut off its end.
        for (text in listOf("上下文 42%", "自动模式")) layout(text).let {
            assertFalse("「$text」 is cut", it.multiParagraph.didExceedMaxLines || it.isLineEllipsized(0) || it.getLineEnd(0, visibleEnd = true) < text.length)
        }
        assertTrue("the model's name gives way", layout("deepseek-v4.1-flash[1M]").isLineEllipsized(0))
    }

    /** Round 10: the task list folds to one row over the input, unfolds on a tap and leaves once cleared. */
    @Test fun theTaskBarFoldsUnfoldsAndLeaves() {
        val tasks = listOf(
            clauderc.app.protocol.ChatTask("1", "按位置核对光标", activeForm = "正在按位置核对光标", status = "in_progress"),
            clauderc.app.protocol.ChatTask("2", "补长列表的测试", status = "pending"),
            clauderc.app.protocol.ChatTask("3", "跑真实回归", status = "pending"),
        )
        var state by mutableStateOf(baseState())
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, ChatActions()) } }
        compose.waitForIdle()
        compose.onNodeWithTag("taskBar").assertDoesNotExist()

        compose.runOnUiThread { state = state.copy(tasks = tasks) }
        compose.waitForIdle()
        compose.onNode(hasText("按位置核对光标") and hasAnyAncestor(hasTestTag("taskHead")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("taskCount", useUnmergedTree = true).assert(hasText("0/3"))
        compose.onNodeWithTag("taskList").assertDoesNotExist()

        compose.onNodeWithTag("taskHead").performClick()
        compose.waitForIdle()
        val glyphs = compose.onAllNodes(hasAnyAncestor(hasTestTag("taskList")) and hasTestTag("taskGlyph:in_progress").or(hasTestTag("taskGlyph:pending")).or(hasTestTag("taskGlyph:completed")), useUnmergedTree = true)
            .fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }.map { it.config[SemanticsProperties.TestTag].removePrefix("taskGlyph:") }
        assertEquals(listOf("in_progress", "pending", "pending"), glyphs)

        compose.runOnUiThread { state = state.copy(tasks = emptyList()) }
        compose.waitForIdle()
        compose.onNodeWithTag("taskBar").assertDoesNotExist()
    }

    /** A formula the library gave no content for shows as written, in mono and the second grey, not as plain text. */
    @Test fun aFormulaWithoutContentShowsAsWritten() {
        val runs = (mdBlocks("面积是 \$x^2\$。").single() as MdBlock.Paragraph).runs
        val i = runs.indexOfFirst { it.kind == RunKind.MATH }
        val plain = styled(runs, androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Gray, TextLinks(), emptySet())
        val span = plain.spanStyles.single { plain.text.substring(it.start, it.end) == runs[i].text }
        assertEquals(clauderc.app.ui.theme.MonoFamily, span.item.fontFamily)
        assertEquals(androidx.compose.ui.graphics.Color.Gray, span.item.color)
        val drawn = styled(runs, androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Gray, TextLinks(), setOf(mathId(i)))
        assertTrue(drawn.spanStyles.none { it.item.fontFamily == clauderc.app.ui.theme.MonoFamily })
    }

    @Test fun thinkingItemsAreNotRendered() {
        val items = mergeItems(emptyList(), listOf(ChatItem(1, "thinking", text = "内心独白"), ChatItem(2, ItemKind.ASSISTANT, text = "你好")))
        show(baseState().copy(items = items, hasMore = false))
        compose.onNodeWithText("你好").assertIsDisplayed()
        compose.onNodeWithText("内心独白").assertDoesNotExist()
    }

    @Test fun aFailedSendPutsItsTextBackIntoTheComposerAndOutgoingIsFaded() {
        var state by mutableStateOf(baseState().copy(hasMore = false))
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, ChatActions()) } }
        state = state.copy(outgoing = listOf(Outgoing(1, "把错误码写进 errors.go", emptyList(), afterSeq = 100)))
        compose.onNodeWithText("把错误码写进 errors.go").assertIsDisplayed()
        state = state.copy(outgoing = emptyList(), restore = Restore(1, "把错误码写进 errors.go"))
        compose.onNodeWithTag("composer").assert(hasText("把错误码写进 errors.go"))
    }

    @Test fun interruptIsANoteNotABubble() {
        val items = mergeItems(
            emptyList(),
            listOf(ChatItem(1, ItemKind.USER, text = "写一篇长文"), ChatItem(2, ItemKind.USER, text = "排队的消息"), ChatItem(3, ItemKind.INTERRUPTED)),
        )
        // The queued message went back to the computer's input box.
        val after = mergeItems(items, listOf(ChatItem(2, ItemKind.REMOVED)))
        show(baseState().copy(items = after, hasMore = false))
        compose.onNodeWithText("写一篇长文").assertIsDisplayed()
        compose.onNodeWithText("已中断").assertIsDisplayed()
        compose.onNodeWithText("排队的消息").assertDoesNotExist()
        compose.onNode(hasText("Request interrupted", substring = true)).assertDoesNotExist()
    }

    @Test fun noticesAreGreyTextNotBubbles() {
        val items = mergeItems(
            emptyList(),
            listOf(
                ChatItem(1, ItemKind.USER, text = "等 CI 跑完告诉我"),
                ChatItem(2, ItemKind.NOTICE, text = "Background command \"等 CI 跑完\" completed (exit code 0)"),
                ChatItem(3, ItemKind.NOTICE, title = "协议文档 发来", text = "envelope.go 先别提交\n等我对完一遍。"),
            ),
        )
        assertEquals(listOf(1L, 2L, 3L), items.map { it.seq })
        show(baseState().copy(items = items, hasMore = false))
        compose.onNodeWithText("Background command \"等 CI 跑完\" completed (exit code 0)").assertIsDisplayed()
        compose.onNodeWithText("协议文档 发来").assertIsDisplayed()
        // The message shows in full, not folded to its first line.
        compose.onNodeWithText("envelope.go 先别提交\n等我对完一遍。").assertIsDisplayed()
    }

    /** The job-list line leaves the bubble and shows as written in grey; grey lines start where a reply's text starts. */
    @Test fun markerLineIsGreyTextAlignedWithTheReply() {
        val reply = "删除前我会单独复述范围，等你第二次确认。\n\nneeds input: 是否现在开始 collector 和 feed 切换？\n\n喵呜~"
        val items = mergeItems(
            emptyList(),
            listOf(
                ChatItem(1, ItemKind.NOTICE, title = "协议文档 发来", text = "envelope.go 先别提交"),
                ChatItem(2, ItemKind.INTERRUPTED),
                ChatItem(3, ItemKind.ASSISTANT, text = reply),
            ),
        )
        show(baseState().copy(items = items, hasMore = false))
        // Round 8: the two paragraphs left in the bubble are two blocks.
        val bubble = compose.onNodeWithText("删除前我会单独复述范围，等你第二次确认。").assertIsDisplayed()
        compose.onNodeWithText("喵呜~").assertIsDisplayed()
        compose.onNode(hasText("needs input:", substring = true) and hasText("确认。", substring = true)).assertDoesNotExist()
        val mark = compose.onNodeWithText("needs input: 是否现在开始 collector 和 feed 切换？").assertIsDisplayed()
        // A Text's semantics bounds sit inside its padding, so the bubble's left is where its text starts.
        val textStart = bubble.fetchSemanticsNode().boundsInRoot.left
        assertEquals(with(compose.density) { 32.dp.toPx() }, textStart, 1f)
        for (grey in listOf(mark, compose.onNodeWithText("已中断"), compose.onNodeWithText("协议文档 发来"))) {
            assertEquals(textStart, grey.fetchSemanticsNode().boundsInRoot.left, 1f)
        }
    }

    @Test fun sendMessageIsAGreyBlockNotAToolRow() {
        val tool = ToolCall(
            "toolu_01S", "SendMessage", "SendMessage",
            buildJsonObject {
                put("to", "09588c02")
                put("message", "灰字块已经画好了\n你那边可以接着做文档了")
                put("content", "灰字块已经画好了…")
            },
        )
        val items = mergeItems(emptyList(), listOf(ChatItem(1, ItemKind.USER, text = "继续"), ChatItem(2, ItemKind.TOOL, tool = tool)))
        show(baseState().copy(items = items, hasMore = false))
        compose.onNodeWithText("发给 09588c02").assertIsDisplayed()
        compose.onNodeWithText("灰字块已经画好了\n你那边可以接着做文档了").assertIsDisplayed()
        // Not a folded tool row, and the shortened content preview is not used.
        compose.onNodeWithText("SendMessage").assertDoesNotExist()
        compose.onNodeWithText("灰字块已经画好了…").assertDoesNotExist()
    }

    @Test fun sendMessageWithNoBodyWaitsForIdle() {
        val tool = ToolCall(
            "toolu_01T", "SendMessage", "SendMessage",
            buildJsonObject {
                put("to", "09588c02")
                put("notify_when_idle", true)
            },
        )
        val items = mergeItems(emptyList(), listOf(ChatItem(1, ItemKind.TOOL, tool = tool)))
        show(baseState().copy(items = items, hasMore = false))
        compose.onNodeWithText("等「09588c02」空闲").assertIsDisplayed()
        compose.onNodeWithText("SendMessage").assertDoesNotExist()
    }

    @Test fun scrollingToTheTopLoadsOlderAndKeepsThePlace() {
        var loads = 0
        var state by mutableStateOf(baseState())
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, ChatActions(onLoadOlder = { loads++ })) } }
        // Bottom-up list: the oldest loaded item plus the spinner sit at the highest indexes.
        compose.onNodeWithTag("chatList").performScrollToIndex(state.items.size)
        compose.waitForIdle()
        assertTrue(loads >= 1)
        compose.onNodeWithText("第 52 条").assertIsDisplayed()
        state = state.copy(items = page(1L..50L) + state.items, hasMore = false)
        compose.waitForIdle()
        compose.onNodeWithText("第 52 条").assertIsDisplayed()
    }

    @Test fun permissionPanelShowsTheCommandAndAnswers() {
        var allowed = 0
        show(
            baseState().copy(state = SessionState.Pending, pending = Pending("toolu_01B", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "touch marker.txt") })),
            ChatActions(onAllow = { allowed++ }),
        )
        compose.onNodeWithText("运行这条命令？").assertIsDisplayed()
        compose.onNodeWithText("touch marker.txt").assertIsDisplayed()
        compose.onNodeWithText("Bash").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertDoesNotExist()
        compose.onNodeWithText("允许").performClick()
        assertEquals(1, allowed)
    }

    @Test fun tappingAllowOnAnOutgoingPermissionPanelSendsNothing() {
        val x1 = Pending("toolu_X1", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "echo one") })
        val x2 = Pending("toolu_X2", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "echo two") })
        val live = showLive(emptyList()) {
            respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = emptyList(), state = SessionState.Pending, pending = x1))
        }
        compose.onNodeWithText("echo one").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        live.relay.event(Method.CHAT_PENDING, "m-1", clauderc.app.protocol.ChatPendingEvent.serializer(), clauderc.app.protocol.ChatPendingEvent("s-need", x2))
        compose.mainClock.advanceTimeByFrame()
        // X1 is leaving while X2 enters: tapping X1's 「允许」 carries toolu_X1 and sends nothing.
        val allowButtons = compose.onAllNodesWithText("允许")
        val x1Panel = compose.onAllNodes(hasTestTag("pendingPanel") and androidx.compose.ui.test.hasAnyDescendant(hasText("echo one")))
        assertTrue(x1Panel.fetchSemanticsNodes().isNotEmpty())
        compose.onNode(hasText("允许") and hasAnyAncestor(hasTestTag("pendingPanel") and androidx.compose.ui.test.hasAnyDescendant(hasText("echo one")))).performClick()
        assertTrue("tapping the stale panel sent no chat.answer", live.relay.sent(Method.CHAT_ANSWER).isEmpty())
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("echo two").assertIsDisplayed()
        live.close()
    }

    /**
     * The permission panel takes the composer's place until it is answered: half a message typed before it
     * is there when the composer comes back, and the old draft brought in on opening does not come back over it.
     */
    @Test fun whatWasTypedOutlivesThePermissionPanel() {
        val drafts = mutableListOf<String>()
        var state by mutableStateOf(baseState().copy(hasMore = false, restore = Restore(1, "上次没发的草稿")))
        // As the view model does: the panel goes at once on an answer, and a restore once dealt with.
        val actions = ChatActions(
            onAllow = { state = state.copy(state = SessionState.Working, pending = null) },
            onDraft = { drafts += it },
            onRestored = { id -> if (state.restore?.id == id) state = state.copy(restore = null) },
        )
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, actions) } }
        compose.waitForIdle()
        assertEquals("上次没发的草稿", composerText())
        compose.onNodeWithTag("composer").performTextReplacement("先别合并，等")

        state = state.copy(state = SessionState.Pending, pending = Pending("toolu_01B", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "touch marker.txt") }))
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertDoesNotExist()
        compose.onNodeWithText("允许").performClick()
        compose.waitForIdle()

        assertEquals("先别合并，等", composerText())
        assertEquals("the draft is what was typed last", "先别合并，等", drafts.last())
    }

    @Test fun singleChoiceSubmitsOnTapAndOtherSubmitsWithTheArrow() {
        val answers = mutableListOf<List<Answer>>()
        val q = Pending("toolu_01C", PendingKind.QUESTION, questions = listOf(Question("你喜欢哪种颜色", options = listOf(Option("红"), Option("绿"), Option("蓝")))))
        show(baseState().copy(pending = q), ChatActions(onAnswer = { _, a -> answers += a }))
        compose.onNodeWithText("绿").performClick()
        assertEquals(listOf(listOf(Answer(selected = listOf(1)))), answers)
        // 「其他」 is a choice like the rest: picked, it opens a field, and only the arrow sends.
        compose.onNodeWithText("写下你的回答").assertDoesNotExist()
        compose.onNodeWithText("其他").performClick()
        assertEquals(1, answers.size)
        compose.onNodeWithText("写下你的回答").performTextInput("都不喜欢")
        compose.onNodeWithContentDescription("发送回答").performClick()
        assertEquals(listOf(Answer(custom = "都不喜欢")), answers.last())
    }

    @Test fun inASingleChoiceQuestionOtherAndTheOptionsExcludeEachOther() {
        val answers = mutableListOf<List<Answer>>()
        val q = Pending(
            "toolu_01E", PendingKind.QUESTION,
            questions = listOf(
                Question("你喜欢哪种颜色？", options = listOf(Option("红"), Option("绿"))),
                Question("你喜欢哪种动物？", options = listOf(Option("猫"), Option("狗"))),
            ),
        )
        show(baseState().copy(pending = q), ChatActions(onAnswer = { _, a -> answers += a }))
        val red = compose.onNodeWithText("红")
        val other = compose.onAllNodesWithText("其他")[0]
        red.performClick()
        red.assertIsSelected()
        other.performScrollTo().performClick()
        other.assertIsSelected()
        red.assertIsNotSelected()
        compose.onAllNodesWithText("写下你的回答")[0].performTextInput("都不喜欢")
        red.performScrollTo().performClick()
        other.assertIsNotSelected()
        compose.onNodeWithText("狗").performScrollTo().performClick()
        compose.onNodeWithText("提交").performScrollTo().performClick()
        // What was typed while 「其他」 was picked does not go out.
        assertEquals(listOf(listOf(Answer(selected = listOf(0)), Answer(selected = listOf(1)))), answers)
    }

    @Test fun inAMultiSelectQuestionOtherIsTickedAlongWithTheOptions() {
        val answers = mutableListOf<List<Answer>>()
        val q = Pending("toolu_01F", PendingKind.QUESTION, questions = listOf(Question("为哪些消息补样例？", multiSelect = true, options = listOf(Option("请求"), Option("响应"), Option("事件")))))
        show(baseState().copy(pending = q), ChatActions(onAnswer = { _, a -> answers += a }))
        compose.onNodeWithText("请求").performClick()
        compose.onNodeWithText("事件").performClick()
        compose.onNodeWithText("其他").performScrollTo().performClick()
        compose.onNodeWithText("请求").assertIsOn()
        compose.onNodeWithText("其他").assertIsOn()
        compose.onNodeWithText("写下你的回答").performTextInput("还有错误码")
        compose.onNodeWithText("提交").performScrollTo().performClick()
        assertEquals(listOf(listOf(Answer(selected = listOf(0, 2), custom = "还有错误码"))), answers)
    }

    @Test fun dialogShowsItsOptionsAndSendsThePick() {
        val answers = mutableListOf<List<Answer>>()
        val options = listOf("Use this MCP server", "Use this and all future MCP servers in this project", "Continue without using this MCP server")
        val d = Pending("dialog-3f9a1c0b7e21", PendingKind.DIALOG, questions = listOf(Question("New MCP server found in this project: remote-ssh", options = options.map { Option(it) })))
        show(baseState().copy(state = SessionState.Pending, pending = d), ChatActions(onAnswer = { _, a -> answers += a }))
        compose.onNodeWithText("等你选择").assertIsDisplayed()
        compose.onNodeWithText("New MCP server found in this project: remote-ssh").assertIsDisplayed()
        options.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onAllNodesWithText("其他").assertCountEquals(0)
        compose.onNodeWithText(options[1]).performClick()
        assertEquals(listOf(listOf(Answer(selected = listOf(1)))), answers)
    }

    @Test fun multiSelectAndSeveralQuestionsSubmitTogether() {
        val answers = mutableListOf<List<Answer>>()
        val q = Pending(
            "toolu_01D", PendingKind.QUESTION,
            questions = listOf(
                Question("为哪些消息补样例？", multiSelect = true, options = listOf(Option("请求"), Option("响应"), Option("事件"))),
                Question("样例放在哪？", options = listOf(Option("testdata"), Option("samples"))),
            ),
        )
        show(baseState().copy(pending = q), ChatActions(onAnswer = { _, a -> answers += a }))
        compose.onNodeWithText("等你回答 · 2 题").assertIsDisplayed()
        compose.onNodeWithText("提交").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("请求").performScrollTo().performClick()
        compose.onNodeWithText("事件").performScrollTo().performClick()
        compose.onNodeWithText("testdata").performScrollTo().performClick()
        compose.onNodeWithText("提交").performScrollTo().performClick()
        assertEquals(listOf(listOf(Answer(selected = listOf(0, 2)), Answer(selected = listOf(0)))), answers)
    }

    @Test fun multilineTextGoesOutAsOneMessage() {
        val sent = mutableListOf<String>()
        show(baseState(), ChatActions(onSend = { sent += it; true }))
        compose.onNodeWithTag("composer").performTextInput("第一行\n第二行\n第三行")
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(listOf("第一行\n第二行\n第三行"), sent)
    }

    @Test fun workingSessionShowsInterrupt() {
        var interrupts = 0
        show(baseState().copy(state = SessionState.Working, workingSinceMillis = System.currentTimeMillis() - 42_000), ChatActions(onInterrupt = { interrupts++ }))
        compose.onNode(hasText("工作中 · ", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("中断").performClick()
        assertEquals(1, interrupts)
    }

    @Test fun slashFiltersCommandsAndFillsTheInput() {
        show(baseState())
        compose.onNodeWithTag("composer").performTextInput("/mo")
        compose.onNodeWithTag("slashPopup").assertIsDisplayed()
        compose.onNodeWithText("切换模型").assertIsDisplayed()
        compose.onNodeWithText("编辑记忆文件").assertIsDisplayed()
        compose.onNodeWithText("压缩对话上下文").assertDoesNotExist()
        compose.onNodeWithTag("composer").performTextReplacement("/mem")
        compose.onNodeWithText("切换模型").assertDoesNotExist()
        compose.onNodeWithText("编辑记忆文件").performClick()
        compose.onNodeWithText("/memory ").assertIsDisplayed()
        compose.onNodeWithTag("slashPopup").assertDoesNotExist()
    }

    private fun composerText() = compose.onNodeWithTag("composer").fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun slashCompletionFollowsTheCursorAndLeavesItAfterTheCommand() {
        show(baseState().copy(commands = baseState().commands + Command("todolist-workflow", "轻量任务清单")))
        val composer = compose.onNodeWithTag("composer")
        composer.performTextInput("先看下 /todo")
        compose.onNodeWithTag("slashPopup").assertIsDisplayed()
        compose.onNodeWithText("轻量任务清单").performClick()
        composer.performTextInput("先用这个")
        assertEquals("先看下 /todolist-workflow 先用这个", composerText())
        composer.performTextClearance()
        composer.performTextInput("/todo")
        compose.onNodeWithText("轻量任务清单").performClick()
        composer.performTextInput("先用这个")
        assertEquals("/todolist-workflow 先用这个", composerText())
    }

    @Test fun aPathMidMessageShowsNoPopupButALeadingSlashSaysNoMatch() {
        show(baseState())
        val composer = compose.onNodeWithTag("composer")
        composer.performTextInput("看 /usr")
        compose.onNodeWithTag("slashPopup").assertDoesNotExist()
        composer.performTextClearance()
        composer.performTextInput("/zzz")
        compose.onNodeWithText("没有匹配的命令").assertIsDisplayed()
    }

    @Test fun modelOpensThePhonesPickerInsteadOfBeingSent() {
        var loads = 0
        var sent = 0
        show(baseState(), ChatActions(onLoadModels = { loads++ }, onSend = { sent++; true }))
        val composer = compose.onNodeWithTag("composer")
        composer.performTextInput("/mo")
        compose.onNodeWithText("切换模型").performClick()
        compose.onNodeWithText("从下一条回复开始生效").assertIsDisplayed()
        assertEquals(1, loads)
        assertEquals(0, sent)
        assertEquals("", composerText())
    }

    @Test fun sendingSlashModelOpensThePickerToo() {
        var loads = 0
        var sent = 0
        show(baseState(), ChatActions(onLoadModels = { loads++ }, onSend = { sent++; true }))
        compose.onNodeWithTag("composer").performTextInput("/model sonnet")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.onNodeWithText("从下一条回复开始生效").assertIsDisplayed()
        assertEquals(1, loads)
        assertEquals(0, sent)
    }

    @Test fun slashCommandsAreTheUsersBubbleWithTheOutputFolded() {
        val compact = ToolCall("command-12", "/compact", "/compact", buildJsonObject { put("args", "") }, ToolResult("Compacted\n摘要已保存"))
        val skill = ToolCall("command-13", "/todolist-workflow", "/todolist-workflow 先用这个", buildJsonObject { put("args", "先用这个") })
        show(baseState().copy(items = listOf(ChatItem(12, ItemKind.TOOL, tool = compact), ChatItem(13, ItemKind.TOOL, tool = skill)), hasMore = false))
        compose.onNodeWithText("/compact").assertIsDisplayed()
        compose.onNodeWithText("Compacted").assertIsDisplayed()
        compose.onNodeWithText("/todolist-workflow 先用这个").assertIsDisplayed()
        // One folded output row, for /compact only.
        assertEquals(1, compose.onAllNodesWithText("输出").fetchSemanticsNodes().size)
        compose.onNode(hasText("摘要已保存", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("Compacted").performClick()
        compose.onNode(hasText("摘要已保存", substring = true)).assertIsDisplayed()
    }

    @Test fun queuedMessagesAreMarked() {
        var state by mutableStateOf(
            baseState().copy(
                items = listOf(ChatItem(1, ItemKind.USER, text = "已经发出的"), ChatItem(2, ItemKind.USER, text = "排队的消息", queued = true)),
                hasMore = false,
            ),
        )
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, ChatActions()) } }
        assertEquals(1, compose.onAllNodesWithText("排队中").fetchSemanticsNodes().size)
        // The formal entry replaces it under the same seq.
        state = state.copy(items = listOf(state.items[0], ChatItem(2, ItemKind.USER, text = "排队的消息")))
        compose.onNodeWithText("排队中").assertDoesNotExist()
        state = state.copy(outgoing = listOf(Outgoing(1, "/compact", emptyList(), afterSeq = 2, queued = true)))
        compose.onNodeWithText("/compact").assertIsDisplayed()
        compose.onNodeWithText("排队中").assertIsDisplayed()
    }

    @Test fun oversizedImageErrorIsShown() {
        show(baseState().copy(attachError = "IMG_2041.jpg 有 15.2 MB，超过 10 MB，没有添加"))
        compose.onNodeWithText("IMG_2041.jpg 有 15.2 MB，超过 10 MB，没有添加").assertIsDisplayed()
    }

    @Test fun compressedImagesKeepTheLongEdgeWithin2048() {
        val big = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().use { big.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        val jpeg = ImageCompressor.compress(png)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        assertEquals("image/jpeg", bounds.outMimeType)
        assertEquals(2048, maxOf(bounds.outWidth, bounds.outHeight))
        assertEquals(2048 to 1536, bounds.outWidth to bounds.outHeight)
        assertEquals(800 to 600, ImageCompressor.targetSize(800, 600))
        assertEquals(1536 to 2048, ImageCompressor.targetSize(3000, 4000))
    }

    @Test fun headerShowsModelAndModeAndSwitches() {
        val modes = mutableListOf<String>()
        show(baseState(), ChatActions(onSetMode = { modes += it }))
        compose.onNodeWithText("Opus 5.5").assertIsDisplayed()
        compose.onNodeWithText("默认询问").performClick()
        compose.onNodeWithText("自动接受编辑").performClick()
        assertEquals(listOf("acceptEdits"), modes)
    }

    @Test fun offlinePageIsReadOnlyButStillScrolls() {
        show(baseState().copy(online = false, offlineText = "已离线 · 最后在线 14:36"))
        compose.onNodeWithText("已离线 · 最后在线 14:36").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertIsNotEnabled()
        compose.onNodeWithText("离线，不能发送").assertIsDisplayed()
        compose.onNodeWithContentDescription("git").assertIsNotEnabled()
        compose.onNodeWithTag("chatList").performScrollToIndex(20)
        compose.onNodeWithText("第 80 条").assertIsDisplayed()
    }

    // plans.md 3: the diff views of Edit, Write and Read.

    private fun tool(id: String, name: String, summary: String, result: ToolResult) =
        ToolCall(id, name, summary, buildJsonObject { put("file_path", summary.substringAfter(' ')) }, result)

    private fun showTools(vararg tools: ToolCall, actions: ChatActions = ChatActions()): ClaudercColors {
        var colors: ClaudercColors? = null
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                colors = Clauderc.colors
                ChatScreen(baseState().copy(items = tools.mapIndexed { i, t -> ChatItem(i + 1L, ItemKind.TOOL, tool = t) }, hasMore = false), actions)
            }
        }
        compose.waitForIdle()
        return colors!!
    }

    /** The color at the left edge of the [i]th diff row, under its line number. */
    private fun rowColor(i: Int): Color {
        val px = compose.onAllNodesWithTag("patchRow")[i].captureToImage().toPixelMap()
        return px[2, px.height / 2]
    }

    private fun assertColor(want: Color, got: Color, what: String) =
        assertTrue("$what: want $want, got $got", listOf(want.red - got.red, want.green - got.green, want.blue - got.blue).all { kotlin.math.abs(it) < 0.02f })

    private val edit = tool(
        "toolu_E", "Edit", "编辑 envelope.go",
        ToolResult(
            "The file envelope.go has been updated successfully.",
            patch = listOf(Hunk(8, 3, 8, 3, listOf(" // ErrVersion is returned when an envelope is not version 1.", "-var ErrVersion = errors.New(\"unsupported\")", "+var ErrVersion = errors.New(\"version must be 1\")", " "))),
        ),
    )

    @Test fun anEditStartsFoldedAndOpensIntoItsDiffInTheDiffColorsNumberedFromTheHunk() {
        val c = showTools(edit)
        // Folded like every tool row, with the counts at the end of the row.
        compose.onNodeWithText("+1").assertIsDisplayed()
        compose.onNodeWithText("−1").assertIsDisplayed()
        compose.onAllNodesWithTag("patchRow").assertCountEquals(0)
        compose.onNodeWithText("envelope.go").performClick()
        compose.waitForIdle()
        assertEquals(4, compose.onAllNodesWithTag("patchRow").fetchSemanticsNodes().size)
        // Line numbers: the context line and the deleted one from the old start, the added one from the new start.
        assertEquals(listOf("8", "9", "9", "10"), (0..3).map { i -> compose.onAllNodesWithTag("patchRow", useUnmergedTree = true)[i].onChildAt(0).fetchSemanticsNode().config[SemanticsProperties.Text].first().text })
        assertColor(c.delBg, rowColor(1), "deleted row")
        assertColor(c.addBg, rowColor(2), "added row")
        compose.onNodeWithText("var ErrVersion = errors.New(\"unsupported\")", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun aWriteIsAllGreenAddedLines() {
        val write = tool("toolu_W", "Write", "写入 errors.go", ToolResult("File created successfully at: errors.go", patch = listOf(Hunk(0, 0, 1, 3, listOf("+package protocol", "+", "+type Error struct{}")))))
        val c = showTools(write)
        compose.onNodeWithText("+3").assertIsDisplayed()
        compose.onNodeWithText("errors.go").performClick()
        compose.waitForIdle()
        assertEquals(3, compose.onAllNodesWithText("+", useUnmergedTree = true).fetchSemanticsNodes().size)
        (0..2).forEach { assertColor(c.addBg, rowColor(it), "row $it") }
    }

    @Test fun aReadSaysHowManyLinesItReadAndOpensOnTap() {
        val read = tool("toolu_R", "Read", "读取 README.md", ToolResult("1\t# proj\n2\t\n3\thello", lines = 3))
        showTools(read)
        compose.onNodeWithText("3 行").assertIsDisplayed()
        compose.onAllNodesWithTag("patchRow").assertCountEquals(0)
        compose.onNodeWithText("README.md").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("patchRow").assertCountEquals(3)
        compose.onNodeWithText("hello", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun aLongResultOpensWholeInABoxThatScrollsInside() {
        val write = tool("toolu_W", "Write", "写入 envelope.go", ToolResult(patch = listOf(Hunk(0, 0, 1, 300, List(300) { "+line ${it + 1}" }))))
        showTools(write)
        compose.onNodeWithText("envelope.go").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("patchRow").assertCountEquals(300)
        val box = compose.onNodeWithTag("toolBox")
        assertTrue("the box stops at 232dp", box.fetchSemanticsNode().boundsInRoot.height <= 232 * compose.density.density + 1f)
        compose.onNodeWithText("line 300", useUnmergedTree = true).assertIsNotDisplayed()
        box.performScrollToNode(hasText("line 300"))
        compose.onNodeWithText("line 300", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("展开全部", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("收起")).fetchSemanticsNodes().size)
    }

    @Test fun aCutResultFetchesItsWholeTextAsItOpensWithASpinner() {
        val cut = ToolResult((1..10).joinToString("\n") { "$it\tline $it" }, lines = 30, truncated = true)
        val whole = ToolResult((1..30).joinToString("\n") { "$it\tline $it" }, lines = 30)
        val asked = mutableListOf<String>()
        var state by mutableStateOf(baseState().copy(items = listOf(ChatItem(1, ItemKind.TOOL, tool = tool("toolu_big", "Read", "读取 design.md", cut))), hasMore = false))
        val actions = ChatActions(onToolOutput = { asked += it; state = state.copy(fetchingResults = setOf(it)) })
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, actions) } }
        assertTrue("nothing is fetched while it is folded", asked.isEmpty())
        compose.onNodeWithText("design.md").performClick()
        compose.waitForIdle()
        assertEquals(listOf("toolu_big"), asked)
        compose.onNodeWithText("正在取全文").assertIsDisplayed()
        compose.onNode(hasContentDescription("加载中") and hasAnyAncestor(hasTestTag("fetching")), useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("patchRow").assertCountEquals(10)
        state = state.copy(fetchingResults = emptySet(), fullResults = mapOf("toolu_big" to whole))
        compose.waitForIdle()
        compose.onAllNodesWithTag("patchRow").assertCountEquals(30)
        compose.onNodeWithText("正在取全文").assertDoesNotExist()
        // Folded and opened again: the whole text is already here.
        compose.onNodeWithText("design.md").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("design.md").performClick()
        compose.waitForIdle()
        assertEquals(1, asked.size)
        assertEquals(0, compose.onAllNodes(hasText("展开全部", substring = true)).fetchSemanticsNodes().size)
    }

    private fun numbered(n: Int, word: String) = ToolResult((1..n).joinToString("\n") { "$it\t$word $it" }, lines = n)

    @Test fun fiveCallsInARowAreOneLineThatOpensIntoFoldedCallsEachOpeningIntoItsResult() {
        fun call(id: String, name: String, summary: String, k: String, v: String, result: ToolResult) =
            ChatItem(0, ItemKind.TOOL, tool = ToolCall(id, name, summary, buildJsonObject { put(k, v) }, result))
        val run = listOf(
            call("t1", "Grep", "搜索 Envelope", "pattern", "Envelope", ToolResult("No files found")),
            call("t2", "Read", "读取 go.mod", "file_path", "/p/go.mod", numbered(8, "mod")),
            call("t3", "Read", "读取 AGENTS.md", "file_path", "/p/AGENTS.md", numbered(64, "rule")),
            call("t4", "Bash", "运行 ls internal/protocol", "command", "ls internal/protocol", ToolResult("")),
            call("t5", "Bash", "运行 go list ./...", "command", "go list ./...", ToolResult("clauderc/cmd/agent")),
        ).mapIndexed { i, it -> it.copy(seq = i + 1L) }
        show(baseState().copy(items = listOf(ChatItem(0, ItemKind.ASSISTANT, text = "我先看一下。")) + run, hasMore = false))
        compose.onNodeWithText("搜索 Envelope，读取 2 个文件，列出 1 个目录，运行 go list ./...").assertIsDisplayed()
        assertEquals("one line for the five", 1, compose.onAllNodesWithTag("toolGroup").fetchSemanticsNodes().size)
        compose.onNodeWithText("AGENTS.md").assertDoesNotExist()

        compose.onNodeWithTag("toolGroup").performClick()
        compose.waitForIdle()
        listOf("Envelope", "go.mod", "AGENTS.md", "ls internal/protocol", "go list ./...").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onAllNodesWithTag("patchRow").assertCountEquals(0)
        compose.onAllNodesWithTag("toolBox").assertCountEquals(0)

        // One call opens into its whole result, in a box that stops at 232dp and scrolls inside.
        compose.onNodeWithText("AGENTS.md").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("patchRow").assertCountEquals(64)
        val box = compose.onNodeWithTag("toolBox")
        assertTrue(box.fetchSemanticsNode().boundsInRoot.height <= 232 * compose.density.density + 1f)
        box.performScrollToNode(hasText("rule 64"))
        compose.onNodeWithText("rule 64", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithTag("toolGroup").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("AGENTS.md").assertDoesNotExist()
        assertEquals(0, compose.onAllNodes(hasText("└", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun editsAndWritesJoinARunWithTheirLinesCountedAndALoneCallIsItsOwnRow() {
        val write = tool("t1", "Write", "写入 envelope.go", ToolResult(patch = listOf(Hunk(0, 0, 1, 3, listOf("+package protocol", "+", "+type Envelope struct{}")))))
        val test = ToolCall("t2", "Bash", "运行 go test ./internal/protocol/...", buildJsonObject { put("command", "go test ./internal/protocol/...") }, ToolResult("ok"))
        val lone = ToolCall("t3", "Bash", "运行 go vet ./...", buildJsonObject { put("command", "go vet ./...") }, ToolResult("vet: all good"))
        show(
            baseState().copy(
                items = listOf(
                    ChatItem(1, ItemKind.TOOL, tool = write), ChatItem(2, ItemKind.TOOL, tool = test),
                    ChatItem(3, ItemKind.ASSISTANT, text = "测试通过。"), ChatItem(4, ItemKind.TOOL, tool = lone),
                ),
                hasMore = false,
            ),
        )
        compose.onNodeWithText("写入 envelope.go，运行 go test ./internal/protocol/...").assertIsDisplayed()
        compose.onNode(hasText("+3") and hasAnyAncestor(hasTestTag("toolGroup")), useUnmergedTree = true).assertExists()
        // The lone command is a row of its own, and opens straight into its whole result.
        assertEquals(1, compose.onAllNodesWithTag("toolGroup").fetchSemanticsNodes().size)
        compose.onNodeWithText("go vet ./...").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("vet: all good", substring = true)).assertIsDisplayed()
    }

    // plans.md 7: pictures in tool results and from clauderc show.

    private fun pngThumb(w: Int, h: Int): String {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(46, 139, 122)) }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private val shot = ToolImage("toolu_I.0", "image/png", width = 720, height = 1560, size = 217_088, thumb = pngThumb(166, 360))

    private fun readImage(img: ToolImage = shot) =
        ChatItem(1, ItemKind.TOOL, tool = ToolCall("toolu_I", "Read", "读取 chat.png", buildJsonObject { put("file_path", "/p/chat.png") }, ToolResult(images = listOf(img))))

    @Test fun aPictureOpensFullScreenFetchingItOnce() {
        val asked = mutableListOf<String>()
        var state by mutableStateOf(baseState().copy(items = listOf(readImage()), hasMore = false))
        val actions = ChatActions(onImage = { asked += it; state = state.copy(fetchingImages = state.fetchingImages + it) })
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, actions) } }
        compose.onNodeWithTag("toolThumb").assertIsDisplayed()
        compose.onNodeWithTag("toolThumb").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("imageViewer").assertIsDisplayed()
        compose.onNodeWithText("正在取原图").assertIsDisplayed()
        compose.onNodeWithText("720 × 1560 · PNG · 212 KB").assertIsDisplayed()
        compose.onNode(hasText("chat.png") and hasAnyAncestor(hasTestTag("imageViewer")), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(listOf("toolu_I.0"), asked)

        val whole = java.util.Base64.getDecoder().decode(pngThumb(720, 1560))
        state = state.copy(fetchingImages = emptySet(), wholeImages = mapOf("toolu_I.0" to WholeImage(whole, "image/png")))
        // Decoded off the main thread, then the line fades out.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("正在取原图").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("imageViewer").assertDoesNotExist()
        compose.onNodeWithTag("toolThumb").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("imageViewer").assertIsDisplayed()
        compose.onNodeWithText("正在取原图").assertDoesNotExist()
        assertEquals("the same picture again sends nothing", 1, asked.size)
    }

    @Test fun aPictureThatCannotBeFetchedSaysSo() {
        var state by mutableStateOf(baseState().copy(items = listOf(readImage()), hasMore = false))
        val actions = ChatActions(onImage = { state = state.copy(failedImages = state.failedImages + it) })
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, actions) } }
        compose.onNodeWithTag("toolThumb").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("图片取不到").assertIsDisplayed()
    }

    @Test fun aPictureWithoutThumbnailSaysOpenToSee() {
        val webp = ToolImage("toolu_W.0", "image/webp", size = 1_258_291)
        show(baseState().copy(items = listOf(readImage(webp)), hasMore = false))
        compose.onNodeWithText("点开查看").assertIsDisplayed()
        compose.onNodeWithText("WebP · 1.2 MB").assertIsDisplayed()
    }

    @Test fun clauderShowIsThePictureAndItsNameWithoutTheCommand() {
        val hero = ToolImage("show-0123456789abcdef", "image/png", name = "readme-hero.png", width = 1440, height = 810, size = 497_664, thumb = pngThumb(360, 203))
        val show = ToolCall("toolu_S", "Bash", "运行 clauderc show readme-hero.png", buildJsonObject { put("command", "clauderc show readme-hero.png") }, ToolResult("[clauderc show 0123456789abcdef] readme-hero.png", images = listOf(hero)))
        show(baseState().copy(items = listOf(ChatItem(1, ItemKind.TOOL, tool = show)), hasMore = false))
        compose.onNodeWithTag("toolThumb").assertIsDisplayed()
        compose.onNodeWithText("readme-hero.png").assertIsDisplayed()
        compose.onNodeWithText("clauderc show readme-hero.png").assertDoesNotExist()
    }

    // ---------- 2026-11-22: saving the picture in the viewer ----------

    private val saveButton get() = compose.onNodeWithContentDescription("保存到相册")

    private fun openShot() {
        compose.onNodeWithTag("toolThumb").performClick()
        compose.waitForIdle()
    }

    @Test fun theSaveButtonWaitsForThePicturesBytesAndThenSaves() {
        val saves = mutableListOf<Pair<ToolImage, ByteArray?>>()
        var state by mutableStateOf(baseState().copy(items = listOf(readImage()), hasMore = false))
        val actions = ChatActions(onImage = { state = state.copy(fetchingImages = state.fetchingImages + it) }, onSaveImage = { img, local -> saves += img to local })
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, actions) } }
        openShot()
        saveButton.assertIsNotEnabled().performClick()
        assertTrue(saves.isEmpty())

        val whole = java.util.Base64.getDecoder().decode(pngThumb(720, 1560))
        compose.runOnUiThread { state = state.copy(fetchingImages = emptySet(), wholeImages = mapOf(shot.id to WholeImage(whole, "image/png"))) }
        compose.waitForIdle()
        saveButton.assertIsEnabled().performClick()
        val (img, local) = saves.single()
        assertEquals(shot.id, img.id)
        assertEquals("named after the file it was read from", "chat.png", img.name)
        assertEquals("fetched, not the phone's own", null, local)
    }

    /** A chat page on a real view model over a scripted relay, which opens the session on [items]. */
    private class Live(val relay: FakeRelay, val vm: ChatViewModel, val scope: CoroutineScope) {
        fun close() {
            vm.viewModelScope.cancel()
            scope.cancel()
        }
    }

    private fun showLive(items: List<ChatItem>, saver: ImageSaver = ImageSaver { _, _, _ -> }, script: FakeRelay.() -> Unit = {}): Live {
        val relay = FakeRelay().apply {
            respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "mac-mini"))))
            respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions)
            respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = items, state = SessionState.Idle))
            script()
        }
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val vm = ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = { it }, background = Dispatchers.Unconfined, saver = saver)
        compose.setContent {
            val s by vm.state.collectAsState()
            ClaudercTheme(darkTheme = false) {
                ChatScreen(
                    s,
                    ChatActions(
                        onImage = vm::loadImage, onSaveImage = vm::saveImage, onViewer = vm::viewImage,
                        onAllow = vm::allow, onDeny = vm::deny, onAnswer = vm::answerQuestions,
                    ),
                )
            }
        }
        compose.waitForIdle()
        return Live(relay, vm, scope)
    }

    @Test fun aSavedPictureIsATickUntilTheViewerCloses() {
        val writes = mutableListOf<ByteArray>()
        val png = pngThumb(720, 1560)
        val live = showLive(listOf(readImage()), saver = { b, _, _ -> writes += b }) {
            respond(Method.CHAT_IMAGE, ChatImageResponse.serializer(), ChatImageResponse("image/png", png))
        }
        openShot()
        saveButton.assertIsEnabled()
        val ready = saveButton.captureToImage().asAndroidBitmap()
        saveButton.performClick()
        compose.waitForIdle()
        saveButton.assertIsNotEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已保存"))
        assertFalse("the download icon turned into a tick", saveButton.captureToImage().asAndroidBitmap().sameAs(ready))

        compose.onNodeWithContentDescription("关闭").performClick()
        compose.waitForIdle()
        openShot()
        saveButton.assertIsEnabled().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        assertTrue("opened again, it is the download icon", saveButton.captureToImage().asAndroidBitmap().sameAs(ready))
        assertEquals(1, writes.size)
        assertTrue(writes.single().contentEquals(java.util.Base64.getDecoder().decode(png)))
        live.close()
    }

    @Test fun theViewerTakesEveryTouchAndATapOnItsBlackClosesIt() {
        var allowed = 0
        var denied = 0
        val permission = Pending("toolu_01B", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "touch marker.txt") })
        var state by mutableStateOf(baseState().copy(items = listOf(readImage()), hasMore = false, state = SessionState.Pending, pending = permission))
        val actions = ChatActions(onAllow = { allowed++ }, onDeny = { denied++ }, onImage = { state = state.copy(fetchingImages = state.fetchingImages + it) })
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, actions) } }
        for (button in listOf("允许", "拒绝")) {
            val under = compose.onNodeWithText(button).fetchSemanticsNode().boundsInRoot.center
            openShot()
            compose.onNodeWithTag("imageViewer").performTouchInput { click(under) }
            compose.waitForIdle()
            compose.onNodeWithTag("imageViewer").assertDoesNotExist()
        }
        assertEquals("the buttons under the viewer got no tap", 0 to 0, allowed to denied)
    }

    @Test fun theSnackbarShowsOverTheViewerInLightGrey() {
        val snack = SnackState()
        var state by mutableStateOf(baseState().copy(items = listOf(readImage()), hasMore = false))
        val actions = ChatActions(onImage = { state = state.copy(fetchingImages = state.fetchingImages + it) })
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, actions, snack) } }
        openShot()
        compose.runOnUiThread { snack.show("保存失败") }
        compose.waitForIdle()
        val text = compose.onNodeWithText("保存失败").fetchSemanticsNode().boundsInRoot
        val inside = with(compose.density) { 8.dp.toPx() }
        // Left of the words, inside the snackbar's padding: its own grey, not the viewer's black or the theme's dark grey.
        val px = compose.onRoot().captureToImage().toPixelMap()[(text.left - inside).toInt(), text.center.y.toInt()]
        assertEquals(Color(0xFFE6E6E6), px)
    }

    @Test fun bytesThatCannotBeDecodedStillEndTheWaitAndCanBeSaved() {
        val saves = mutableListOf<String>()
        // An id of its own: the decoded pictures are kept app-wide, and another test decoded shot's.
        val broken = shot.copy(id = "toolu_D.0")
        var state by mutableStateOf(baseState().copy(items = listOf(readImage(broken)), hasMore = false))
        val actions = ChatActions(
            onImage = { state = state.copy(wholeImages = mapOf(it to WholeImage(byteArrayOf(1, 2, 3), "image/png"))) },
            onSaveImage = { img, _ -> saves += img.id },
        )
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, actions) } }
        openShot()
        compose.onNodeWithText("正在取原图").assertDoesNotExist()
        compose.onNodeWithText("图片取不到").assertDoesNotExist()
        saveButton.assertIsEnabled().performClick()
        assertEquals(listOf(broken.id), saves)
    }

    @Test fun aPictureInTheUsersMessageOpensAndSavesFromThePhone() {
        val data = pngThumb(300, 200)
        val bytes = java.util.Base64.getDecoder().decode(data)
        val message = ChatItem(1, ItemKind.USER, text = "照这张改", images = listOf(Image("image/png", data)), uuid = "u-1")
        val writes = mutableListOf<Triple<ByteArray, String?, String?>>()
        val live = showLive(listOf(message, ChatItem(2, ItemKind.ASSISTANT, text = "好的。")), saver = { b, name, type -> writes += Triple(b, name, type) })
        // The bubble's picture is decoded off the main thread; its size names the picture in the viewer.
        compose.waitUntil(5_000) { ImageCache.shared.peek(data) != null }
        compose.waitForIdle()

        compose.onNodeWithTag("userPicture").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("imageViewer").assertIsDisplayed()
        compose.onNode(hasText("PNG") and hasAnyAncestor(hasTestTag("imageViewer")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("300 × 200 · ${byteSize(bytes.size.toLong())}").assertIsDisplayed()
        compose.onNodeWithText("正在取原图").assertDoesNotExist()
        saveButton.assertIsEnabled().performClick()
        compose.waitForIdle()
        val (saved, name, type) = writes.single()
        assertTrue(saved.contentEquals(bytes))
        assertEquals(null, name)
        assertEquals("image/png", type)
        assertTrue("nothing asked of the agent", live.relay.sent(Method.CHAT_IMAGE).isEmpty())

        // Sliding the message from its picture lays bare the rewind button and opens nothing.
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("userPicture").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("imageViewer").assertDoesNotExist()
        compose.onNodeWithTag("rewindAction").assertIsDisplayed()
        live.close()
    }

    // No count limit: the bar shows the size used against the total and always offers another picture.
    @Test fun attachBarShowsTheSizeUsedAndAlwaysOffersMore() {
        var state by mutableStateOf(baseState().copy(hasMore = false, attachments = List(2) { Attachment("p$it.jpg", ByteArray(1_258_291)) }))
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, ChatActions()) } }
        compose.onNodeWithText("2.4 / 11 MB").assertIsDisplayed()

        state = state.copy(attachments = List(6) { Attachment("p$it.jpg", ByteArray(1_000)) })
        compose.onNodeWithText("0.0 / 11 MB").assertIsDisplayed()
        compose.onNodeWithContentDescription("再添加图片").performScrollTo().assertIsDisplayed()
    }

    @Test fun photoTakenBeforeActivityRecreationStillAttaches() {
        val captured = mutableListOf<android.net.Uri>()
        var start: ((android.net.Uri) -> Unit)? = null
        var finish: ((Boolean) -> Unit)? = null
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        tester.setContent {
            val pair = clauderc.app.rememberPhotoCapture(onCaptured = { captured += it }, launch = {})
            start = pair.first
            finish = pair.second
        }
        val uri = android.net.Uri.parse("content://clauderc.app.files/camera/photo-1.jpg")
        compose.runOnUiThread { start!!.invoke(uri) }
        compose.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        compose.runOnUiThread { finish!!.invoke(true) }
        compose.waitForIdle()
        assertEquals(listOf(uri), captured)
    }

    @Test fun removingFirstAttachmentShowsSecondAndDoubleTapDeletesOnlyOne() {
        fun solidPng(argb: Int): ByteArray {
            val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        }
        val red = solidPng(android.graphics.Color.RED)
        val green = solidPng(android.graphics.Color.GREEN)
        val live = showLive(emptyList())
        live.vm.addImage("a.png", red.size.toLong()) { red }
        live.vm.addImage("b.png", green.size.toLong()) { green }
        compose.waitForIdle()
        val firstId = live.vm.state.value.attachments[0].id
        // Rapid double-tap on A's remove button sends A's id twice: only A is removed, B stays.
        live.vm.removeImage(firstId)
        live.vm.removeImage(firstId)
        compose.waitForIdle()
        assertEquals(listOf("b.png"), live.vm.state.value.attachments.map { it.name })
        compose.waitUntil(5_000) { ImageCache.shared.peek(live.vm.state.value.attachments.single()) != null }
        compose.waitForIdle()
        val px = compose.onNodeWithContentDescription("b.png").captureToImage().toPixelMap()
        val mid = px[px.width / 2, px.height / 2]
        assertTrue("remaining slot shows B's green, not A's red: $mid", mid.green > 0.8f && mid.red < 0.2f)
        live.close()
    }

    // ---------- Round 9: formulas in replies ----------

    private val formulas = listOf(
        "面积是 \$x^2\$，写错的 \$\\backofff(n)\$ 照原文显示。",
        "",
        "\$\$",
        "T_k = \\sum_{n=1}^{k} 2^{n-1} = 2^k - 1 \\qquad \\text{前 k 次一共要等这么久，到上限以后每次再多等三十秒}",
        "\$\$",
    ).joinToString("\n")

    @Config(shadows = [ShadowMagnifier::class])
    @Test fun formulasCopyAsTheirLaTeX() {
        val toolbar = FakeToolbar()
        showSelecting(baseState().copy(items = listOf(ChatItem(1, ItemKind.ASSISTANT, text = formulas)), hasMore = false), toolbar)
        compose.onNode(hasText("面积是", substring = true)).performTouchInput { longClick(Offset(width * 0.05f, height / 2f)) }
        awaitToolbar("the reply selects") { toolbar.shown != null }
        toolbar.click(TextContextMenuKeys.SelectAllKey)
        compose.waitForIdle()
        toolbar.click(TextContextMenuKeys.CopyKey)
        compose.waitForIdle()
        val copied = clipboardText().orEmpty()
        assertTrue("copied 「$copied」", copied.startsWith("面积是 \$x^2\$，写错的 \$\\backofff(n)\$ 照原文显示。") && "\$\$ T_k = \\sum_{n=1}^{k} 2^{n-1}" in copied)
    }

    @Test fun aWideFormulaScrollsInPlaceAndOneThatCannotBeDrawnShowsAsWritten() {
        show(baseState().copy(items = listOf(ChatItem(1, ItemKind.ASSISTANT, text = formulas)), hasMore = false))
        compose.waitForIdle()
        val range = compose.onNodeWithTag("math", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("the block formula is wider than the bubble: ${range.maxValue()}", range.maxValue() > 0f && range.value() == 0f)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNode(hasText("面积是", substring = true), useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        assertEquals("x² is drawn in its place", 1, layout.placeholderRects.filterNotNull().size)
        val text = layout.layoutInput.text
        val bad = "\$\\backofff(n)\$"
        val at = text.text.indexOf(bad)
        assertTrue("the unknown command shows as written, in mono", at >= 0 && text.spanStyles.any { it.start == at && it.end == at + bad.length && it.item.fontFamily == clauderc.app.ui.theme.MonoFamily })
    }

    @Test fun formulasTakeTheTextsColourInEveryStyle() {
        var look by mutableStateOf(UiStyle.Clear to false)
        var fg = Color.Unspecified
        compose.setContent {
            ClaudercTheme(darkTheme = look.second, style = look.first) {
                fg = Clauderc.colors.fg
                ChatScreen(baseState().copy(items = listOf(ChatItem(1, ItemKind.ASSISTANT, text = "\$\$\n\\blacksquare\\ \\mathbf{M}\n\$\$")), hasMore = false), ChatActions())
            }
        }
        for (style in UiStyle.entries) for (dark in listOf(false, true)) {
            compose.runOnUiThread { look = style to dark }
            compose.waitForIdle()
            val px = compose.onNodeWithTag("math", useUnmergedTree = true).captureToImage().toPixelMap()
            val back = px[0, 0]
            var ink = back
            var far = 0f
            for (x in 0 until px.width) for (y in 0 until px.height) {
                val p = px[x, y]
                val d = abs(p.red - back.red) + abs(p.green - back.green) + abs(p.blue - back.blue)
                if (d > far) { far = d; ink = p }
            }
            val off = abs(ink.red - fg.red) + abs(ink.green - fg.green) + abs(ink.blue - fg.blue)
            assertTrue("$style dark=$dark: formula ink $ink, text $fg", off < 0.06f)
        }
    }

    @Test fun multiQuestionSelectionsAndDialogsSurviveActivityRecreation() {
        val answered = mutableListOf<List<Answer>>()
        val questions = Pending(
            "toolu_multi",
            PendingKind.QUESTION,
            questions = listOf(
                Question("要跑哪些测试？", options = listOf(Option("单元测试"), Option("端到端测试"), Option("截图测试")), multiSelect = true),
                Question("发版吗？", options = listOf(Option("发正式版"), Option("先不发")), multiSelect = false),
            ),
        )
        val state = baseState().copy(hasMore = false, pending = questions)
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        tester.setContent {
            ClaudercTheme(darkTheme = false) {
                ChatScreen(state, ChatActions(onAnswer = { _, list -> answered += list }))
            }
        }
        compose.onNodeWithText("单元测试").performClick()
        compose.onNodeWithText("截图测试").performClick()
        compose.onNodeWithText("先不发").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("提交").assertIsEnabled()

        // Recreate Activity (rotation): both questions' selections stay intact.
        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("提交").assertIsEnabled().performClick()
        assertEquals(
            listOf(listOf(Answer(selected = listOf(0, 2)), Answer(selected = listOf(1)))),
            answered,
        )
    }

    @Test fun openForkDialogAndTypedTextSurviveActivityRecreation() {
        val state = baseState().copy(hasMore = false)
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        tester.setContent {
            ClaudercTheme(darkTheme = false) {
                ChatScreen(state, ChatActions())
            }
        }
        compose.onNodeWithContentDescription("git").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fork").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("forkInstruction").performTextInput("只改协议部分")
        compose.waitForIdle()

        tester.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("Fork 这个会话").assertIsDisplayed()
        compose.onNodeWithTag("forkInstruction").assert(hasText("只改协议部分"))
    }

    @Test fun twoSimultaneousFailedSendsReturnBothTextsToTheComposer() {
        var state by mutableStateOf(baseState().copy(hasMore = false))
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                ChatScreen(
                    state,
                    ChatActions(onRestored = { id -> if (state.restore?.id == id) state = state.copy(restore = null) }),
                )
            }
        }
        // First failure restores into the empty composer; second failure appends rather than dropping its text.
        compose.runOnUiThread { state = state.copy(restore = Restore(1, "第一条失败的字", append = true)) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assert(hasText("第一条失败的字"))
        compose.runOnUiThread { state = state.copy(restore = Restore(2, "第二条失败的字", append = true)) }
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assert(hasText("第一条失败的字\n第二条失败的字"))
    }

    @Test fun userMessageThumbnailFetchesOriginalViaChatImageAndSavesOriginal() {
        val thumbB64 = pngThumb(240, 180)
        val originalB64 = pngThumb(640, 480)
        val originalBytes = java.util.Base64.getDecoder().decode(originalB64)
        val message = ChatItem(
            1,
            ItemKind.USER,
            text = "看这张原图",
            images = listOf(Image("image/jpeg", thumbB64, id = "usr-remote.0", width = 640, height = 480, size = originalBytes.size.toLong())),
            uuid = "u-remote",
        )
        val writes = mutableListOf<Triple<ByteArray, String?, String?>>()
        val live = showLive(listOf(message), saver = { b, name, type -> writes += Triple(b, name, type) }) {
            respond(Method.CHAT_IMAGE, ChatImageResponse.serializer(), ChatImageResponse("image/png", originalB64))
        }
        compose.waitUntil(5_000) { ImageCache.shared.peek(thumbB64) != null }
        compose.waitForIdle()

        compose.onNodeWithTag("userPicture").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("imageViewer").assertIsDisplayed()
        assertEquals(1, live.relay.sent(Method.CHAT_IMAGE).size)
        val req = live.relay.sent(Method.CHAT_IMAGE).single().decode(clauderc.app.protocol.ChatImageRequest.serializer())
        assertEquals("usr-remote.0", req.id)

        saveButton.assertIsEnabled().performClick()
        compose.waitForIdle()
        val (saved, _, type) = writes.single()
        assertTrue("viewer saved original bytes, not thumbnail", saved.contentEquals(originalBytes))
        assertEquals("image/png", type)
        live.close()
    }

    @Test fun justSentUserMessageImageOpensFromLocalBytesWithoutChatImage() {
        val originalB64 = pngThumb(320, 240)
        val originalBytes = java.util.Base64.getDecoder().decode(originalB64)
        val thumbB64 = pngThumb(160, 120)
        val writes = mutableListOf<Triple<ByteArray, String?, String?>>()
        val live = showLive(emptyList(), saver = { b, name, type -> writes += Triple(b, name, type) }) {
            respond(Method.CHAT_SEND, clauderc.app.protocol.ChatSendResponse.serializer(), clauderc.app.protocol.ChatSendResponse())
        }
        compose.runOnUiThread {
            live.vm.addImage("local.jpg", originalBytes.size.toLong()) { originalBytes }
            live.vm.send("我刚发的图")
        }
        compose.waitForIdle()
        val delivered = ChatItem(
            1,
            ItemKind.USER,
            text = "[Image #1] 我刚发的图",
            images = listOf(Image("image/jpeg", thumbB64, id = "usr-justsent.0", width = 320, height = 240, size = originalBytes.size.toLong())),
            uuid = "u-justsent",
        )
        live.relay.event(Method.CHAT_ITEMS, "m-1", clauderc.app.protocol.ChatItemsEvent.serializer(), clauderc.app.protocol.ChatItemsEvent("s-need", listOf(delivered)))
        compose.waitUntil(5_000) { ImageCache.shared.peek(thumbB64) != null }
        compose.waitForIdle()

        compose.onNodeWithTag("userPicture").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("imageViewer").assertIsDisplayed()
        assertTrue("no chat.image sent for picture still held locally", live.relay.sent(Method.CHAT_IMAGE).isEmpty())
        saveButton.assertIsEnabled().performClick()
        compose.waitForIdle()
        val (saved, _, _) = writes.single()
        assertTrue("saved local original bytes", saved.contentEquals(originalBytes))
        live.close()
    }
}

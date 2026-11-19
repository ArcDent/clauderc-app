package clauderc.app.chat

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
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
import androidx.compose.ui.test.performClick
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

    @Test fun opensAtTheBottomWithToolRowsFolded() {
        show(baseState())
        compose.onNodeWithText("先别提交\n跑一遍 go vet").assertIsDisplayed()
        compose.onNodeWithText("go test ./internal/protocol/...").assertIsDisplayed()
        compose.onNode(hasText("0.412s", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("go test ./internal/protocol/...").performClick()
        compose.onNode(hasText("0.412s", substring = true)).assertIsDisplayed()
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
        val bubble = compose.onNodeWithText("删除前我会单独复述范围，等你第二次确认。\n\n喵呜~").assertIsDisplayed()
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

    @Test fun singleChoiceSubmitsOnTapAndOtherSubmitsWithTheArrow() {
        val answers = mutableListOf<List<Answer>>()
        val q = Pending("toolu_01C", PendingKind.QUESTION, questions = listOf(Question("你喜欢哪种颜色", options = listOf(Option("红"), Option("绿"), Option("蓝")))))
        show(baseState().copy(pending = q), ChatActions(onAnswer = { answers += it }))
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
        show(baseState().copy(pending = q), ChatActions(onAnswer = { answers += it }))
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
        show(baseState().copy(pending = q), ChatActions(onAnswer = { answers += it }))
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
        show(baseState().copy(state = SessionState.Pending, pending = d), ChatActions(onAnswer = { answers += it }))
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
        show(baseState().copy(pending = q), ChatActions(onAnswer = { answers += it }))
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
        compose.onNodeWithContentDescription("打开 git").assertIsNotEnabled()
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
        state = state.copy(fetchingImages = emptySet(), wholeImages = mapOf("toolu_I.0" to whole))
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
}

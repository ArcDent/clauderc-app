package clauderc.app.chat

import androidx.compose.ui.test.assert
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.protocol.Answer
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.Command
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.Option
import clauderc.app.protocol.Pending
import clauderc.app.protocol.PendingKind
import clauderc.app.protocol.Question
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.ToolCall
import clauderc.app.protocol.ToolResult
import clauderc.app.testing.session
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

    @Test fun singleChoiceSubmitsOnTapAndCustomAnswerWorks() {
        val answers = mutableListOf<List<Answer>>()
        val q = Pending("toolu_01C", PendingKind.QUESTION, questions = listOf(Question("你喜欢哪种颜色", options = listOf(Option("红"), Option("绿"), Option("蓝")))))
        show(baseState().copy(pending = q), ChatActions(onAnswer = { answers += it }))
        compose.onNodeWithText("绿").performClick()
        compose.onNodeWithText("其他回答").performTextInput("都不喜欢")
        compose.onNodeWithContentDescription("发送回答").performClick()
        assertEquals(listOf(listOf(Answer(selected = listOf(1))), listOf(Answer(custom = "都不喜欢"))), answers)
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
        compose.onNodeWithTag("composer").performTextReplacement("/mod")
        compose.onNodeWithText("编辑记忆文件").assertDoesNotExist()
        compose.onNodeWithText("切换模型").performClick()
        compose.onNodeWithText("/model ").assertIsDisplayed()
        compose.onNodeWithTag("slashPopup").assertDoesNotExist()
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
}

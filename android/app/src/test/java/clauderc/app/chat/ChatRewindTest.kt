package clauderc.app.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.Fleet
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.ChatOpenResponse
import clauderc.app.protocol.ChatRewindRequest
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.Hunk
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.RewindRestore
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.ToolCall
import clauderc.app.protocol.ToolResult
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.decode
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import clauderc.app.testing.session
import clauderc.app.ui.theme.ClaudercTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Round 9: a user message slides left to rewind the session to before it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp")
class ChatRewindTest {
    @get:Rule val compose = createComposeRule()

    private val target = ChatItem(2, ItemKind.USER, text = "把退避上限改成 60 秒", uuid = "u-2")
    private val edit = ChatItem(
        3, ItemKind.TOOL,
        tool = ToolCall(
            "t1", "Edit", "修改 conn.go", buildJsonObject { put("file_path", "internal/relay/conn.go") },
            ToolResult(patch = listOf(Hunk(41, 1, 41, 1, listOf("-\tif d > 30*time.Second {", "+\tif d > 60*time.Second {")))),
        ),
    )
    private val items = listOf(
        ChatItem(1, ItemKind.ASSISTANT, text = "退避从 1 秒起，每次翻倍。"),
        target,
        edit,
        ChatItem(4, ItemKind.ASSISTANT, text = "改好了。"),
        ChatItem(5, ItemKind.USER, text = "跑一下测试", uuid = "u-5"),
        ChatItem(6, ItemKind.ASSISTANT, text = "通过了。"),
    )

    private fun state(sessionState: SessionState = SessionState.Idle) = ChatUiState(
        session = session("s20", "退避说明", sessionState, "default"), loading = false, items = items, hasMore = false, state = sessionState,
    )

    @Test fun theWholeRowSlidesLeftToLayBareTheButtonUnlessTheSessionIsWorking() {
        var working by mutableStateOf(false)
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state(if (working) SessionState.Working else SessionState.Idle), ChatActions()) } }
        compose.waitForIdle()
        compose.onNodeWithTag("userRow:2").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("rewindAction").assertIsDisplayed()

        compose.runOnUiThread { working = true }
        compose.waitForIdle()
        compose.onNodeWithTag("rewindAction").assertDoesNotExist()
        compose.onNodeWithTag("userRow:5").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("rewindAction").assertDoesNotExist()
    }

    @Test fun theSheetQuotesTheMessageAndSendsTheWayBackPicked() {
        val asked = mutableListOf<Pair<ChatItem, String>>()
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state(), ChatActions(onRewind = { item, mode -> asked += item to mode })) } }
        compose.waitForIdle()
        compose.onNodeWithTag("userRow:2").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("rewindAction").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("回溯到这条消息之前").assertIsDisplayed()
        compose.onNodeWithTag("rewindQuote").assertIsDisplayed()
        compose.onNode(hasText("把退避上限改成 60 秒") and hasTestTagQuote()).assertIsDisplayed()
        // The turn changed conn.go, so all three ways back are offered.
        compose.onNodeWithText("conn.go +1 −1 改回去").assertIsDisplayed()
        compose.onNodeWithText("只回代码").performClick()
        compose.onNodeWithText("回溯").performClick()
        compose.waitForIdle()
        assertEquals(listOf("u-2" to RewindRestore.CODE), asked.map { it.first.uuid to it.second })
        compose.onNodeWithText("回溯到这条消息之前").assertDoesNotExist()
    }

    @Test fun aTurnThatChangedNoFileOnlyTakesTheConversationBack() {
        val asked = mutableListOf<String>()
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state(), ChatActions(onRewind = { _, mode -> asked += mode })) } }
        compose.waitForIdle()
        compose.onNodeWithTag("userRow:5").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("rewindAction").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("这一轮没改文件，只回对话。对话回去后，这条消息填回输入框。").assertIsDisplayed()
        compose.onNodeWithText("只回代码").assertDoesNotExist()
        compose.onNodeWithText("回溯").performClick()
        compose.waitForIdle()
        assertEquals(listOf(RewindRestore.CONVERSATION), asked)
    }

    @Test fun aRewoundMessageTakesTheComposersPlace() {
        var s by mutableStateOf(state())
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(s, ChatActions()) } }
        compose.waitForIdle()
        compose.runOnUiThread { s = s.copy(restore = Restore(1, "先写一半的草稿")) }
        compose.waitForIdle()
        compose.onNodeWithText("先写一半的草稿").assertIsDisplayed()
        compose.runOnUiThread { s = s.copy(restore = Restore(2, "把退避上限改成 60 秒", replace = true), items = items.take(1)) }
        compose.waitForIdle()
        compose.onNodeWithText("先写一半的草稿").assertDoesNotExist()
        compose.onNodeWithText("把退避上限改成 60 秒").assertIsDisplayed()
    }

    private fun hasTestTagQuote() = androidx.compose.ui.test.hasTestTag("rewindQuote")

    // ---------- the view model ----------

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher + Job())
    private val relay = FakeRelay()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        relay.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "mac-mini"))))
        relay.respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions)
        relay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = items, state = SessionState.Idle))
    }

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = { it }, background = dispatcher)

    @Test fun aRewindSendsTheMessagesUuidAndPutsTheMessageBack() = runTest(dispatcher) {
        val vm = viewModel()
        var failed = false
        vm.rewind(target, RewindRestore.BOTH) { failed = true }
        val sent = relay.sent(Method.CHAT_REWIND).single().decode(ChatRewindRequest.serializer())
        assertEquals(ChatRewindRequest("s-need", "u-2", RewindRestore.BOTH), sent)
        val s = vm.state.value
        assertFalse(s.rewinding)
        assertEquals("把退避上限改成 60 秒", s.restore?.text)
        assertTrue(s.restore!!.replace)
        assertFalse(failed)
    }

    @Test fun restoringOnlyTheCodeLeavesTheComposerAndSaysSo() = runTest(dispatcher) {
        val vm = viewModel()
        val before = vm.state.value.restore
        vm.rewind(target, RewindRestore.CODE) {}
        assertEquals(before, vm.state.value.restore)
        assertEquals("代码已回到这条消息之前", vm.messages.first())
    }

    @Test fun aPanelThatDidNotMatchOpensTheTerminalView() = runTest(dispatcher) {
        relay.fail(Method.CHAT_REWIND, ErrorCode.REWIND_FAILED, "回溯面板里光标那一行是「只回复 THREE」")
        val vm = viewModel()
        var failed = false
        vm.rewind(target, RewindRestore.BOTH) { failed = true }
        assertTrue("the terminal view opens", failed)
        assertFalse(vm.state.value.rewinding)
        assertNull(vm.state.value.restore?.takeIf { it.replace })
        assertEquals("没能回溯：Claude Code 里对不上这条消息，已退出面板", vm.messages.first())
    }
}

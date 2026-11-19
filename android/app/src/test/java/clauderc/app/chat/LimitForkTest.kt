package clauderc.app.chat

import clauderc.app.data.Fleet
import clauderc.app.protocol.AutoResume
import clauderc.app.protocol.ChatAutoResumeRequest
import clauderc.app.protocol.ChatAutoResumeResponse
import clauderc.app.protocol.ChatOpenResponse
import clauderc.app.protocol.ChatSendRequest
import clauderc.app.protocol.ChatSendResponse
import clauderc.app.protocol.ChatStateEvent
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.Limit
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.SessionCreateRequest
import clauderc.app.protocol.SessionCreateResponse
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.decode
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Round 6 items 4 and 5 on the phone: the usage limit's auto-send, and Fork and Worktree from the chat page. */
@OptIn(ExperimentalCoroutinesApi::class)
class LimitForkTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher + Job())
    private val relay = FakeRelay()
    private val resets = Instant.parse("2026-11-19T14:40:00Z")
    private val limit = Limit("fiveHour", "You've hit your 5-hour limit · resets 10:40pm (Asia/Shanghai)", resets)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        relay.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "mac-mini"))))
        relay.respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions)
        relay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(state = SessionState.Idle, limit = limit))
        relay.respond(Method.CHAT_SEND, ChatSendResponse.serializer(), ChatSendResponse())
    }

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = { it }, background = dispatcher)

    private fun answerAutoResume(text: String?) = relay.respond(
        Method.CHAT_AUTO_RESUME, ChatAutoResumeResponse.serializer(),
        ChatAutoResumeResponse(limit.copy(autoResume = text?.let { AutoResume(it, resets.plusSeconds(60)) })),
    )

    @Test fun theSwitchSendsTheTypedTextAndTheRowGoesWithTheLimit() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(limit, vm.state.value.limit)
        assertEquals("继续", vm.state.value.resumeText)
        answerAutoResume("继续")
        vm.setAutoResume(true)
        val req = relay.sent(Method.CHAT_AUTO_RESUME).single().decode(ChatAutoResumeRequest.serializer())
        assertEquals(ChatAutoResumeRequest("s-need", on = true, text = "继续"), req)
        assertEquals(AutoResume("继续", resets.plusSeconds(60)), vm.state.value.limit?.autoResume)
        // The text typed while it is on goes to the agent once typing pauses.
        answerAutoResume("接着把测试改完")
        vm.typedResume("接着把测试改完")
        assertEquals(1, relay.sent(Method.CHAT_AUTO_RESUME).size)
        advanceTimeBy(DRAFT_SAVE_MILLIS + 1)
        assertEquals("接着把测试改完", relay.sent(Method.CHAT_AUTO_RESUME).last().decode(ChatAutoResumeRequest.serializer()).text)
        // Off sends no text.
        answerAutoResume(null)
        vm.setAutoResume(false)
        assertEquals(ChatAutoResumeRequest("s-need", on = false), relay.sent(Method.CHAT_AUTO_RESUME).last().decode(ChatAutoResumeRequest.serializer()))
        assertNull(vm.state.value.limit?.autoResume)
        assertEquals("接着把测试改完", vm.state.value.resumeText)
        // The limit lifted: chat.state no longer carries it.
        relay.event(Method.CHAT_STATE, "m-1", ChatStateEvent.serializer(), ChatStateEvent("s-need", SessionState.Idle))
        assertNull(vm.state.value.limit)
    }

    @Test fun aSettingTheAgentKeepsComesBackWithThePage() = runTest(dispatcher) {
        relay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(limit = limit.copy(autoResume = AutoResume("接着改测试", resets.plusSeconds(60)))))
        val vm = viewModel()
        assertEquals("接着改测试", vm.state.value.limit?.autoResume?.text)
        assertEquals("接着改测试", vm.state.value.resumeText)
    }

    @Test fun aRefusedSwitchGoesBackAndSaysWhy() = runTest(dispatcher) {
        val vm = viewModel()
        relay.fail(Method.CHAT_AUTO_RESUME, ErrorCode.INVALID_PARAMS, "会话没有停在用量限额上")
        vm.setAutoResume(true)
        assertNull(vm.state.value.limit?.autoResume)
        assertEquals("会话没有停在用量限额上", withTimeout(1_000) { vm.messages.first() })
        // Nothing typed, nothing to turn on.
        vm.typedResume(" ")
        vm.setAutoResume(true)
        assertEquals(1, relay.sent(Method.CHAT_AUTO_RESUME).size)
    }

    @Test fun forkSendsTheCommandAndLeavesThePicturesAlone() = runTest(dispatcher) {
        val vm = viewModel()
        vm.addImage("a.jpg", 100) { byteArrayOf(1, 2, 3) }
        vm.state.first { it.attachments.size == 1 }
        assertTrue(vm.fork("  试试另一种写法 "))
        val sent = relay.sent(Method.CHAT_SEND).single().decode(ChatSendRequest.serializer())
        assertEquals("/fork 试试另一种写法", sent.text)
        assertNull(sent.images)
        assertEquals(1, vm.state.value.attachments.size)
        assertTrue(vm.fork(""))
        assertEquals("/fork", relay.sent(Method.CHAT_SEND).last().decode(ChatSendRequest.serializer()).text)
    }

    @Test fun worktreeStartsWithThisProfileInTheSessionsDirectory() = runTest(dispatcher) {
        relay.respond(Method.SESSION_CREATE, SessionCreateResponse.serializer(), SessionCreateResponse("s-wt"))
        val vm = viewModel()
        vm.state.first { it.session != null }
        vm.createWorktree("wt-1119-1530", " 先跑测试 ")
        val req = relay.sent(Method.SESSION_CREATE).single().decode(SessionCreateRequest.serializer())
        assertEquals(SessionCreateRequest("default", "/Users/me/github/clauderc-app", prompt = "先跑测试", worktree = "wt-1119-1530"), req)
        assertEquals("s-wt", withTimeout(1_000) { vm.created.first() })
        assertEquals(false, vm.state.value.creatingWorktree)
    }

    @Test fun aBadWorktreeNameIsNotSentAndARefusalIsShown() = runTest(dispatcher) {
        val vm = viewModel()
        vm.state.first { it.session != null }
        vm.createWorktree("a b", "")
        assertTrue(relay.sent(Method.SESSION_CREATE).isEmpty())
        assertEquals(WORKTREE_NAME_RULE, vm.state.value.worktreeError)
        relay.fail(Method.SESSION_CREATE, ErrorCode.INTERNAL, "fatal: a branch named 'worktree-wt' already exists")
        vm.createWorktree("wt", "")
        assertEquals("fatal: a branch named 'worktree-wt' already exists", vm.state.value.worktreeError)
        vm.clearWorktreeError()
        assertNull(vm.state.value.worktreeError)
    }

    @Test fun theSuggestedNameIsTheMonthDayAndTime() {
        assertEquals("wt-1119-1530", worktreeName(LocalDateTime.of(2026, 11, 19, 15, 30)))
        assertEquals("wt-0102-0905", worktreeName(LocalDateTime.of(2027, 1, 2, 9, 5)))
        assertTrue(WORKTREE_NAME.matches("wt-0102-0905"))
        listOf("a b", "a/b", "-x", ".x", "x".repeat(65)).forEach { assertTrue(it, !WORKTREE_NAME.matches(it)) }
    }
}

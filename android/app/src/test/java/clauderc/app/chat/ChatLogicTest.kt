package clauderc.app.chat

import clauderc.app.data.Fleet
import clauderc.app.protocol.Answer
import clauderc.app.protocol.ChatAnswerRequest
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.ChatItemsEvent
import clauderc.app.protocol.ChatModelsResponse
import clauderc.app.protocol.ChatOpenRequest
import clauderc.app.protocol.ChatOpenResponse
import clauderc.app.protocol.ChatPendingEvent
import clauderc.app.protocol.ChatSendRequest
import clauderc.app.protocol.ChatSendResponse
import clauderc.app.protocol.ChatSetModelRequest
import clauderc.app.protocol.ChatSetPermissionModeRequest
import clauderc.app.protocol.ChatStateEvent
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.Machine
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.ModelOption
import clauderc.app.protocol.Option
import clauderc.app.protocol.Pending
import clauderc.app.protocol.PendingKind
import clauderc.app.protocol.Question
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.ToolCall
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.decode
import clauderc.app.testing.eventually
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tasks 13.1 to 13.4: the chat view model against a scripted relay. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatLogicTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher + Job())
    private val relay = FakeRelay()

    private fun text(seq: Long, kind: String = ItemKind.ASSISTANT, body: String = "第 $seq 条") = ChatItem(seq, kind, text = body)
    private val bash = ToolCall("toolu_01B", "Bash", "运行 touch marker.txt", buildJsonObject { put("command", "touch marker.txt") })
    private val permission = Pending("toolu_01B", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "touch marker.txt") })

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        relay.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "mac-mini"))))
        relay.respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions)
        relay.respond(
            Method.CHAT_OPEN, ChatOpenResponse.serializer(),
            ChatOpenResponse(
                items = (51L..99L).map { text(it) } + ChatItem(100, "thinking", text = "secret") + ChatItem(101, ItemKind.TOOL, tool = bash),
                hasMore = true,
                state = SessionState.Pending,
                model = "claude-opus-5-5",
                permissionMode = "default",
                pending = permission,
            ),
        )
    }

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(cache: ChatCache = ChatCache()) =
        ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = { it }, cache = cache, background = dispatcher)

    /** Keeps [method] outstanding until the returned gate opens; [result] then answers it. */
    private fun hold(method: String, result: () -> kotlinx.serialization.json.JsonElement = { kotlinx.serialization.json.JsonObject(emptyMap()) }): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        relay.on(method) { gate.await(); result() }
        return gate
    }

    @Test fun aSentMessageShowsAtOnceAndGivesWayToTheTranscript() = runTest(dispatcher) {
        val vm = viewModel()
        val gate = hold(Method.CHAT_SEND)
        assertTrue(vm.send("把错误码写进 errors.go"))
        assertEquals(listOf("把错误码写进 errors.go"), vm.state.value.outgoing.map { it.text })
        // The same words from an older turn are not this message.
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-need", listOf(text(60, ItemKind.USER, "把错误码写进 errors.go"))))
        assertEquals(1, vm.state.value.outgoing.size)
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-need", listOf(text(102, ItemKind.USER, "把错误码写进 errors.go"))))
        assertTrue(vm.state.value.outgoing.isEmpty())
        assertEquals(102L, vm.state.value.items.last().seq)
        gate.complete(Unit)
        assertFalse(vm.state.value.sending)
    }

    @Test fun aSlashCommandGivesWayToItsCommandRow() {
        val out = listOf(Outgoing(1, "/compact", emptyList(), afterSeq = 101), Outgoing(2, "/todolist-workflow 先用这个", emptyList(), afterSeq = 101))
        val compact = ToolCall("command-102", "/compact", "/compact", buildJsonObject { put("args", "") })
        // An ordinary tool row with the same text is not the command.
        val lookalike = ToolCall("toolu_09", "Bash", "/todolist-workflow 先用这个", buildJsonObject { })
        val left = settleOutgoing(out, listOf(ChatItem(102, ItemKind.TOOL, tool = compact), ChatItem(103, ItemKind.TOOL, tool = lookalike)))
        assertEquals(listOf("/todolist-workflow 先用这个"), left.map { it.text })
    }

    @Test fun aQueuedCommandIsMarkedUntilItRuns() = runTest(dispatcher) {
        val vm = viewModel()
        relay.respond(Method.CHAT_SEND, ChatSendResponse.serializer(), ChatSendResponse(queued = true))
        assertTrue(vm.send("/todolist-workflow 先用这个"))
        assertEquals(listOf(true), vm.state.value.outgoing.map { it.queued })
        val run = ToolCall("command-102", "/todolist-workflow", "/todolist-workflow 先用这个", buildJsonObject { put("args", "先用这个") })
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-need", listOf(ChatItem(102, ItemKind.TOOL, tool = run))))
        assertTrue(vm.state.value.outgoing.isEmpty())
    }

    @Test fun anUnqueuedSendStaysFaded() = runTest(dispatcher) {
        val vm = viewModel()
        assertTrue(vm.send("你好"))
        assertEquals(listOf(false), vm.state.value.outgoing.map { it.queued })
    }

    @Test fun aFailedSendTakesTheBubbleBackAndReturnsTheText() = runTest(dispatcher) {
        val vm = viewModel()
        relay.fail(Method.CHAT_SEND, ErrorCode.NOT_DELIVERED)
        assertTrue(vm.send("先停一下"))
        assertTrue(vm.state.value.outgoing.isEmpty())
        assertEquals("先停一下", vm.state.value.restore?.text)
        assertEquals("消息没有送达，请重试", withTimeout(1_000) { vm.messages.first() })
    }

    @Test fun switchesAnswersAndInterruptShowBeforeTheAgentReplies() = runTest(dispatcher) {
        val vm = viewModel()
        val mode = hold(Method.CHAT_SET_PERMISSION_MODE) { throw clauderc.app.net.RemoteException("internal", "按键没有生效") }
        vm.setPermissionMode("plan")
        assertEquals("plan", vm.state.value.permissionMode)
        mode.complete(Unit)
        assertEquals("refused: back to the old mode", "default", vm.state.value.permissionMode)

        val model = hold(Method.CHAT_SET_MODEL)
        vm.setModel(ModelOption("claude-sonnet-5-5", "Sonnet 5.5", "", current = false))
        assertEquals("Sonnet 5.5", vm.state.value.model)
        model.complete(Unit)
        assertEquals("Sonnet 5.5", vm.state.value.model)
        val refusedModel = hold(Method.CHAT_SET_MODEL) { throw clauderc.app.net.RemoteException("internal", "没切过去") }
        vm.setModel(ModelOption("claude-haiku-5-5", "Haiku 5.5", "", current = false))
        assertEquals("Haiku 5.5", vm.state.value.model)
        refusedModel.complete(Unit)
        assertEquals("refused: back to the previous model", "Sonnet 5.5", vm.state.value.model)

        val stop = hold(Method.CHAT_INTERRUPT)
        vm.interrupt()
        assertEquals("已中断", vm.state.value.notice)
        stop.complete(Unit)

        val answer = hold(Method.CHAT_ANSWER) { throw clauderc.app.net.RemoteException("internal", "没送到") }
        vm.allow()
        assertNull("the panel closes at once", vm.state.value.pending)
        answer.complete(Unit)
        assertEquals("a refused answer brings it back", permission, vm.state.value.pending)
    }

    @Test fun aReopenedChatShowsWhatItShowedLastTime() = runTest(dispatcher) {
        val cache = ChatCache()
        val store = androidx.lifecycle.ViewModelStore()
        val first = androidx.lifecycle.ViewModelProvider(store, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = viewModel(cache) as T
        })[ChatViewModel::class.java]
        val shown = first.state.value.items
        store.clear()

        hold(Method.CHAT_OPEN)
        val again = viewModel(cache)
        assertEquals("before chat.open answers", shown, again.state.value.items)
        assertFalse(again.state.value.loading)
    }

    @Test fun opensWithTheLatestPageAndDropsThinking() = runTest(dispatcher) {
        val vm = viewModel()
        val open = relay.sent(Method.CHAT_OPEN).single()
        assertEquals("m-1", open.machine)
        assertEquals(ChatOpenRequest("s-need"), open.decode(ChatOpenRequest.serializer()))
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(50, s.items.size)
        assertTrue("thinking is never shown", s.items.none { it.kind == "thinking" })
        assertEquals(101L, s.items.last().seq)
        assertEquals("claude-opus-5-5", s.model)
        assertEquals(permission, s.pending)
    }

    @Test fun olderPagesArePrependedAndEventsMerged() = runTest(dispatcher) {
        val vm = viewModel()
        relay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = (1L..50L).map { text(it) }, hasMore = false))
        vm.loadOlder()
        assertEquals(ChatOpenRequest("s-need", before = 51), relay.sent(Method.CHAT_OPEN).last().decode(ChatOpenRequest.serializer()))
        assertEquals(100, vm.state.value.items.size)
        assertEquals(1L, vm.state.value.items.first().seq)
        assertFalse(vm.state.value.hasMore)

        val withResult = bash.copy(result = clauderc.app.protocol.ToolResult("", isError = false))
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-need", listOf(ChatItem(101, ItemKind.TOOL, tool = withResult), text(102))))
        val items = vm.state.value.items
        assertEquals(listOf(101L, 102L), items.takeLast(2).map { it.seq })
        assertEquals(withResult, items.first { it.seq == 101L }.tool)
        // Events for another session are ignored.
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("other", listOf(text(500))))
        assertEquals(102L, vm.state.value.items.last().seq)
    }

    @Test fun removedItemsDisappearAndInterruptsStay() = runTest(dispatcher) {
        val vm = viewModel()
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-need", listOf(text(102, ItemKind.USER, "排队的消息"))))
        assertEquals(102L, vm.state.value.items.last().seq)
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-need", listOf(ChatItem(102, ItemKind.REMOVED), ChatItem(103, ItemKind.INTERRUPTED))))
        val items = vm.state.value.items
        assertTrue(items.none { it.seq == 102L })
        assertEquals(ItemKind.INTERRUPTED, items.last().kind)
        // An older page may carry removed items of its own.
        relay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = (1L..49L).map { text(it) } + ChatItem(50, ItemKind.REMOVED), hasMore = false))
        vm.loadOlder()
        assertTrue(vm.state.value.items.none { it.kind == ItemKind.REMOVED })
    }

    @Test fun allowSendsTheToolUseIdAndAnswersQuestions() = runTest(dispatcher) {
        val vm = viewModel()
        vm.allow()
        assertEquals(ChatAnswerRequest("s-need", "toolu_01B", decision = "allow"), relay.sent(Method.CHAT_ANSWER).single().decode(ChatAnswerRequest.serializer()))
        assertNull(vm.state.value.pending)

        val q = Pending("toolu_01C", PendingKind.QUESTION, questions = listOf(Question("颜色", options = listOf(Option("红"), Option("绿")))))
        relay.event(Method.CHAT_PENDING, "m-1", ChatPendingEvent.serializer(), ChatPendingEvent("s-need", q))
        vm.answerQuestions(listOf(Answer(custom = "都不喜欢")))
        assertEquals(ChatAnswerRequest("s-need", "toolu_01C", answers = listOf(Answer(custom = "都不喜欢"))), relay.sent(Method.CHAT_ANSWER).last().decode(ChatAnswerRequest.serializer()))
    }

    @Test fun pendingClearedElsewhereClosesThePanelWithAHint() = runTest(dispatcher) {
        val vm = viewModel()
        relay.event(Method.CHAT_PENDING, "m-1", ChatPendingEvent.serializer(), ChatPendingEvent("s-need", null))
        assertNull(vm.state.value.pending)
        assertEquals("已在电脑上处理", withTimeout(1_000) { vm.messages.first() })
    }

    @Test fun changedPendingIsReported() = runTest(dispatcher) {
        relay.fail(Method.CHAT_ANSWER, ErrorCode.PENDING_CHANGED)
        val vm = viewModel()
        vm.deny()
        assertTrue(withTimeout(1_000) { vm.messages.first() }.contains("已经变了"))
    }

    @Test fun multilineTextIsOneMessage() = runTest(dispatcher) {
        val vm = viewModel()
        assertTrue(vm.send("第一行\n第二行\n第三行"))
        assertEquals(ChatSendRequest("s-need", "第一行\n第二行\n第三行"), relay.sent(Method.CHAT_SEND).single().decode(ChatSendRequest.serializer()))
    }

    @Test fun imageLimitsAreCheckedBeforeSending() = runTest(dispatcher) {
        val vm = viewModel()
        vm.addImage("IMG_2041.jpg", 15_200_000) { error("must not be read") }
        assertTrue(vm.state.value.attachError!!.contains("超过 10 MB"))
        assertTrue(vm.state.value.attachments.isEmpty())
        repeat(5) { i -> vm.addImage("p$i.png", 100) { tinyPng() } }
        eventually { vm.state.first { it.attachments.size == 5 } }
        vm.addImage("p6.png", 100) { tinyPng() }
        assertTrue(vm.state.value.attachError!!.contains("最多 5 张"))
        assertEquals(5, vm.state.value.attachments.size)
    }

    @Test fun modelAndPermissionModeSwitch() = runTest(dispatcher) {
        relay.respond(Method.CHAT_MODELS, ChatModelsResponse.serializer(), ChatModelsResponse(listOf(ModelOption("Opus 5.5", "Opus 5.5", current = true), ModelOption("Sonnet 5.5", "Sonnet 5.5"))))
        val vm = viewModel()
        vm.loadModels()
        assertEquals(2, vm.state.value.models!!.size)
        vm.setModel(vm.state.value.models!![1])
        assertEquals(ChatSetModelRequest("s-need", "Sonnet 5.5"), relay.sent(Method.CHAT_SET_MODEL).single().decode(ChatSetModelRequest.serializer()))
        assertEquals("Sonnet 5.5", vm.state.value.model)
        assertTrue(vm.state.value.models!![1].current)

        vm.setPermissionMode("acceptEdits")
        assertEquals(ChatSetPermissionModeRequest("s-need", "acceptEdits"), relay.sent(Method.CHAT_SET_PERMISSION_MODE).single().decode(ChatSetPermissionModeRequest.serializer()))
        assertEquals("acceptEdits", vm.state.value.permissionMode)

        relay.event(Method.CHAT_STATE, "m-1", ChatStateEvent.serializer(), ChatStateEvent("s-need", SessionState.Working, "claude-sonnet-5-5", "acceptEdits"))
        assertEquals("claude-sonnet-5-5", vm.state.value.model)
        assertEquals(SessionState.Working, vm.state.value.state)
    }

    @Test fun agentWithoutChatModelsSaysSo() = runTest(dispatcher) {
        relay.fail(Method.CHAT_MODELS, ErrorCode.UNKNOWN_METHOD)
        val vm = viewModel()
        vm.loadModels()
        assertEquals(emptyList<ModelOption>(), vm.state.value.models)
        assertTrue(vm.state.value.modelsError!!.contains("还不支持"))
    }

    @Test fun offlineMachineMakesThePageReadOnly() = runTest(dispatcher) {
        val vm = viewModel()
        relay.event(Method.MACHINE_STATUS, null, Machine.serializer(), machine("m-1", "mac-mini", online = false))
        assertFalse(vm.state.value.online)
        assertTrue(vm.state.value.offlineText.startsWith("已离线"))
        assertEquals(50, vm.state.value.items.size)
        assertFalse(vm.send("hello"))
        assertTrue(relay.sent(Method.CHAT_SEND).isEmpty())
    }

    private fun tinyPng(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )
}

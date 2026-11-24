package clauderc.app.chat

import clauderc.app.data.Fleet
import clauderc.app.protocol.Answer
import clauderc.app.protocol.ChatAnswerRequest
import clauderc.app.protocol.ChatImageRequest
import clauderc.app.protocol.ChatImageResponse
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
import clauderc.app.protocol.ChatToolOutputRequest
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.Hunk
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.Machine
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.ModelOption
import clauderc.app.protocol.Option
import clauderc.app.protocol.Pending
import clauderc.app.protocol.PendingKind
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.Question
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.ToolCall
import clauderc.app.protocol.ToolImage
import clauderc.app.protocol.ToolResult
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()
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

    private fun viewModel(cache: ChatCache = ChatCache(), saver: ImageSaver? = null) =
        ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = { it }, cache = cache, background = dispatcher, saver = saver)

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

    @Test fun typingIsKeptAsTheDraftOnceItPausesForHalfASecond() = runTest(dispatcher) {
        val storeScope = CoroutineScope(Dispatchers.IO + Job())
        val drafts = clauderc.app.data.DraftStore(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { java.io.File(tmp.root, "drafts.preferences_pb") }),
        )
        val vm = ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = { it }, background = dispatcher, drafts = drafts)
        vm.typed("改一")
        advanceTimeBy(300)
        vm.typed("改一下标题")
        advanceTimeBy(DRAFT_SAVE_MILLIS - 100)
        assertEquals("nothing kept while typing goes on", "", drafts.draft("m-1", "s-need").first())
        advanceTimeBy(200)
        assertEquals("改一下标题", clauderc.app.testing.eventually { drafts.draft("m-1", "s-need").first { it.isNotEmpty() } })
        assertEquals("one machine and session each", "", drafts.draft("m-1", "s-work").first())
        storeScope.cancel()
    }

    @Test fun aRunTakesEveryCallButPicturesSubagentsCommandsAndMessages() {
        fun call(id: String, name: String, summary: String, input: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonNull, result: ToolResult? = ToolResult("")) =
            ChatItem(0, ItemKind.TOOL, tool = ToolCall(id, name, summary, input, result))
        val items = listOf(
            call("e", "Edit", "编辑 a.go"),
            call("w", "Write", "写入 b.go"),
            call("r", "Bash", "运行 go test", buildJsonObject { put("command", "go test") }, result = null),
            call("i", "Read", "读取 shot.png", result = ToolResult("", images = listOf(clauderc.app.protocol.ToolImage("img1", "image/png")))),
            call("g", "Grep", "搜索 TODO"),
            ChatItem(0, ItemKind.TOOL, tool = ToolCall("a", "Agent", "Agent", agent = clauderc.app.protocol.Subagent("ag", "Explore", "读一遍", running = true, startedAt = java.time.Instant.EPOCH))),
            call("f", "WebFetch", "WebFetch"),
            call("command-9", "/compact", "/compact"),
            call("m", "SendMessage", "SendMessage", buildJsonObject { put("to", "协议文档"); put("message", "好") }),
            call("t1", "TodoWrite", "TodoWrite"),
        ).mapIndexed { i, it -> it.copy(seq = i + 1L) }
        // An edit, a write and a running command run together; the picture, the subagent, the slash command and the message stand alone.
        assertEquals(listOf(listOf("e", "w", "r"), listOf("i"), listOf("g"), listOf("a"), listOf("f"), listOf("command-9"), listOf("m"), listOf("t1")), chatRuns(items).map { run -> run.map { it.tool!!.toolUseId } })
    }

    @Test fun aMergedRowNamesWhatItDidInTheOrderItCame() {
        fun call(name: String, summary: String, input: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonNull, result: ToolResult = ToolResult("")) =
            ToolCall(summary, name, summary, input, result)
        val tools = listOf(
            call("Bash", "运行 go list ./...", buildJsonObject { put("command", "go list ./...") }),
            call("Read", "读取 go.mod"),
            call("Read", "读取 AGENTS.md"),
            call("Bash", "运行 ls internal", buildJsonObject { put("command", "ls internal") }),
            call("Edit", "编辑 envelope.go", result = ToolResult(patch = listOf(Hunk(1, 1, 1, 2, listOf("-a", "+b", "+c"))))),
            call("TodoWrite", "TodoWrite"),
            call("TodoWrite", "TodoWrite"),
            call("Bash", "运行 rm -rf build", buildJsonObject { put("command", "rm -rf build") }, ToolResult("The user doesn't want to proceed with this tool use.", isError = true)),
        )
        val head = groupSummary(tools, androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Red)
        assertEquals("运行 2 条命令，读取 2 个文件，列出 1 个目录，编辑 envelope.go，TodoWrite 2 次，1 条已拒绝", head.text)
        assertEquals(androidx.compose.ui.graphics.Color.Red, head.spanStyles.last().item.color)
        assertEquals(PatchStat(added = 2, deleted = 1), groupStat(tools))
        assertNull("reads change no lines", groupStat(tools.take(3)))
    }

    @Test fun aSlashCommandGivesWayToItsCommandRow() {
        val out = listOf(Outgoing(1, "/compact", emptyList(), afterSeq = 101), Outgoing(2, "/todolist-workflow 先用这个", emptyList(), afterSeq = 101))
        val compact = ToolCall("command-102", "/compact", "/compact", buildJsonObject { put("args", "") })
        // An ordinary tool row with the same text is not the command.
        val lookalike = ToolCall("toolu_09", "Bash", "/todolist-workflow 先用这个", buildJsonObject { })
        val left = settleOutgoing(out, listOf(ChatItem(102, ItemKind.TOOL, tool = compact), ChatItem(103, ItemKind.TOOL, tool = lookalike)))
        assertEquals(listOf("/todolist-workflow 先用这个"), left.map { it.text })
    }

    @Test fun aBangCommandGivesWayToItsCommandRow() {
        val out = listOf(Outgoing(1, "!ls", emptyList(), afterSeq = 101), Outgoing(2, "! git status", emptyList(), afterSeq = 101))
        val ls = ToolCall("command-102", "!", "! ls", buildJsonObject { put("args", "ls") })
        val status = ToolCall("command-103", "!", "! git status", buildJsonObject { put("args", "git status") })
        val left = settleOutgoing(out, listOf(ChatItem(102, ItemKind.TOOL, tool = ls), ChatItem(103, ItemKind.TOOL, tool = status)))
        assertTrue(left.isEmpty())
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

    /** A view model kept in [store], so clearing the store closes the page as navigation does. */
    private fun pageIn(store: androidx.lifecycle.ViewModelStore, cache: ChatCache, saver: ImageSaver? = null) =
        androidx.lifecycle.ViewModelProvider(store, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = viewModel(cache, saver) as T
        })[ChatViewModel::class.java]

    @Test fun aMessageSentJustBeforeLeavingStillShowsOnReturn() = runTest(dispatcher) {
        val cache = ChatCache()
        val store = androidx.lifecycle.ViewModelStore()
        val first = pageIn(store, cache)
        val gate = hold(Method.CHAT_SEND)
        assertTrue(first.send("跑一下测试"))
        val sent = first.state.value.outgoing.single()
        store.clear()

        val again = viewModel(cache)
        assertEquals(listOf(sent), again.state.value.outgoing)
        assertEquals("still under the last item", again.state.value.items.last().seq, sent.afterSeq)
        // Another session never shows it.
        val other = ChatViewModel("m-1", "s-other", relay, Fleet(relay, scope), scope, compress = { it }, cache = cache, background = dispatcher)
        assertTrue(other.state.value.outgoing.isEmpty())

        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-need", listOf(text(102, ItemKind.USER, "跑一下测试"))))
        gate.complete(Unit)
        val s = again.state.value
        assertTrue(s.outgoing.isEmpty())
        assertEquals(1, s.items.count { it.text == "跑一下测试" })
        assertEquals("the item takes over the bubble's key", outgoingKey(sent), s.keys[102])
    }

    @Test fun aSendThatFailsAfterLeavingIsReportedByTheNextPage() = runTest(dispatcher) {
        val cache = ChatCache()
        val store = androidx.lifecycle.ViewModelStore()
        val first = pageIn(store, cache)
        first.addImage("shot.png", 100) { tinyPng() }
        assertEquals(1, first.state.value.attachments.size)
        val gate = hold(Method.CHAT_SEND) { throw clauderc.app.net.RemoteException(ErrorCode.NOT_DELIVERED, "没出现在会话里") }
        assertTrue(first.send("看这张图"))
        store.clear()
        gate.complete(Unit)

        val again = viewModel(cache)
        val s = again.state.value
        assertTrue(s.outgoing.isEmpty())
        assertEquals("看这张图", s.restore?.text)
        assertEquals(1, s.attachments.size)
        assertEquals("消息没有送达，请重试", withTimeout(1_000) { again.messages.first() })
        // Reported once: a later page has nothing to take back.
        assertNull(viewModel(cache).state.value.restore)
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
        vm.allow("toolu_01B")
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

    @Test fun theWorkingCountGoesOnWhenTheChatIsOpenedAgain() = runTest(dispatcher) {
        val cache = ChatCache()
        val store = androidx.lifecycle.ViewModelStore()
        fun at(millis: Long) = ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, now = { millis }, compress = { it }, cache = cache, background = dispatcher)
        val first = androidx.lifecycle.ViewModelProvider(store, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = at(1_000) as T
        })[ChatViewModel::class.java]
        relay.event(Method.CHAT_STATE, "m-1", ChatStateEvent.serializer(), ChatStateEvent("s-need", SessionState.Working, "claude-opus-5-5", "default"))
        assertEquals(1_000L, first.state.value.workingSinceMillis)
        store.clear()

        relay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = listOf(text(51)), state = SessionState.Working))
        val again = at(9_000)
        assertEquals(1_000L, again.state.value.workingSinceMillis)
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

    @Test fun newerItemsAreAppendedAndReplacementsStillMerge() {
        val old = mergeItems(emptyList(), listOf(ChatItem(1, ItemKind.USER, text = "写测试"), ChatItem(2, ItemKind.ASSISTANT, text = "好")))
        // All newer: appended in order, the shown items kept as the same objects, unknown kinds dropped.
        val appended = mergeNewer(old, listOf(ChatItem(3, "thinking", text = "想"), ChatItem(4, ItemKind.ASSISTANT, text = "写完了")))
        assertEquals(listOf(1L, 2L, 4L), appended.map { it.seq })
        assertTrue(appended[0] === old[0] && appended[1] === old[1])
        // A later item with a shown seq replaces it in place, and a removal deletes it.
        val replaced = mergeNewer(appended, listOf(ChatItem(2, ItemKind.ASSISTANT, text = "好的"), ChatItem(5, ItemKind.ASSISTANT, text = "还有")))
        assertEquals(listOf("写测试", "好的", "写完了", "还有"), replaced.map { it.text })
        assertEquals(listOf(1L, 2L, 5L), mergeNewer(replaced, listOf(ChatItem(4, ItemKind.REMOVED))).map { it.seq })
        // The same seq twice in one batch: the later one wins.
        assertEquals(listOf("二稿"), mergeNewer(old, listOf(ChatItem(7, ItemKind.ASSISTANT, text = "初稿"), ChatItem(7, ItemKind.ASSISTANT, text = "二稿"))).map { it.text }.drop(2))
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
        vm.allow("toolu_01B")
        assertEquals(ChatAnswerRequest("s-need", "toolu_01B", decision = "allow"), relay.sent(Method.CHAT_ANSWER).single().decode(ChatAnswerRequest.serializer()))
        assertNull(vm.state.value.pending)

        val q = Pending("toolu_01C", PendingKind.QUESTION, questions = listOf(Question("颜色", options = listOf(Option("红"), Option("绿")))))
        relay.event(Method.CHAT_PENDING, "m-1", ChatPendingEvent.serializer(), ChatPendingEvent("s-need", q))
        vm.answerQuestions("toolu_01C", listOf(Answer(custom = "都不喜欢")))
        assertEquals(ChatAnswerRequest("s-need", "toolu_01C", answers = listOf(Answer(custom = "都不喜欢"))), relay.sent(Method.CHAT_ANSWER).last().decode(ChatAnswerRequest.serializer()))
    }

    @Test fun markerLinesAreSplitOffOnlyOnTheirOwnLineOutsideCode() {
        // At the end.
        assertEquals("测试通过。" to listOf("result: 颜色测试已完成"), splitMarkers("测试通过。\n\nresult: 颜色测试已完成"))
        // With a sign-off after it, which stays in the reply.
        assertEquals(
            "等你第二次确认。\n\n喵呜~" to listOf("needs input: 是否现在开始 collector 和 feed 切换？"),
            splitMarkers("等你第二次确认。\n\nneeds input: 是否现在开始 collector 和 feed 切换？\n\n喵呜~"),
        )
        // Inside a code block and in the middle of a line it is part of the text.
        val code = "示例：\n```\nfailed: 这行在代码块里\n```"
        assertEquals(code to emptyList<String>(), splitMarkers(code))
        val inline = "我会在最后写一行 needs input: 这样的话"
        assertEquals(inline to emptyList<String>(), splitMarkers(inline))
        // A reply that is only the line leaves an empty bubble text.
        assertEquals("" to listOf("failed: 仓库不存在"), splitMarkers("failed: 仓库不存在"))
    }

    @Test fun dialogPickGoesOutAsOneSelectedOption() = runTest(dispatcher) {
        val vm = viewModel()
        val d = Pending("dialog-3f9a1c0b7e21", PendingKind.DIALOG, questions = listOf(Question("New MCP server found in this project: remote-ssh", options = listOf(Option("Use this MCP server"), Option("Use this and all future MCP servers in this project"), Option("Continue without using this MCP server")))))
        relay.event(Method.CHAT_PENDING, "m-1", ChatPendingEvent.serializer(), ChatPendingEvent("s-need", d))
        vm.answerQuestions("dialog-3f9a1c0b7e21", listOf(Answer(selected = listOf(1))))
        assertEquals(ChatAnswerRequest("s-need", "dialog-3f9a1c0b7e21", answers = listOf(Answer(selected = listOf(1)))), relay.sent(Method.CHAT_ANSWER).last().decode(ChatAnswerRequest.serializer()))
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
        vm.deny("toolu_01B")
        assertTrue(withTimeout(1_000) { vm.messages.first() }.contains("已经变了"))
    }

    /** The panel shows X1 when chat.pending brings X2: a tap on X1's panel answers nothing, and X2's own panel still can. */
    @Test fun anAnswerFromAPanelGivingWayToANewOneSendsNothing() = runTest(dispatcher) {
        val vm = viewModel()
        val next = Pending("toolu_01X", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "rm marker.txt") })
        relay.event(Method.CHAT_PENDING, "m-1", ChatPendingEvent.serializer(), ChatPendingEvent("s-need", next))
        vm.allow("toolu_01B")
        vm.deny("toolu_01B")
        vm.answerQuestions("toolu_01B", listOf(Answer(selected = listOf(0))))
        assertTrue(relay.sent(Method.CHAT_ANSWER).isEmpty())
        assertEquals("the new panel stays", next, vm.state.value.pending)

        vm.allow("toolu_01X")
        assertEquals(ChatAnswerRequest("s-need", "toolu_01X", decision = "allow"), relay.sent(Method.CHAT_ANSWER).single().decode(ChatAnswerRequest.serializer()))
    }

    /** A refused answer defers to chat.pending that came meanwhile: cleared, the old panel stays gone; replaced, the new one stays. */
    @Test fun aRefusedAnswerDoesNotBringBackAPanelTheAgentClearedOrReplaced() = runTest(dispatcher) {
        val vm = viewModel()
        val refused = hold(Method.CHAT_ANSWER) { throw clauderc.app.net.RemoteException(ErrorCode.PENDING_CHANGED, "pending changed") }
        vm.allow("toolu_01B")
        relay.event(Method.CHAT_PENDING, "m-1", ChatPendingEvent.serializer(), ChatPendingEvent("s-need", null))
        refused.complete(Unit)
        assertNull(vm.state.value.pending)
        assertTrue(withTimeout(1_000) { vm.messages.first() }.contains("已经变了"))

        val x2 = permission.copy(toolUseId = "toolu_01X")
        val x3 = permission.copy(toolUseId = "toolu_01Y")
        relay.event(Method.CHAT_PENDING, "m-1", ChatPendingEvent.serializer(), ChatPendingEvent("s-need", x2))
        val again = hold(Method.CHAT_ANSWER) { throw clauderc.app.net.RemoteException(ErrorCode.PENDING_CHANGED, "pending changed") }
        vm.deny("toolu_01X")
        relay.event(Method.CHAT_PENDING, "m-1", ChatPendingEvent.serializer(), ChatPendingEvent("s-need", x3))
        again.complete(Unit)
        assertEquals(x3, vm.state.value.pending)
    }

    @Test fun aSecondInterruptWhileTheFirstIsOutSendsNothing() = runTest(dispatcher) {
        val vm = viewModel()
        val stop = hold(Method.CHAT_INTERRUPT)
        vm.interrupt()
        vm.interrupt()
        assertEquals(1, relay.sent(Method.CHAT_INTERRUPT).size)
        stop.complete(Unit)
        vm.interrupt()
        assertEquals("answered, it can be sent again", 2, relay.sent(Method.CHAT_INTERRUPT).size)
    }

    @Test fun nothingIsSentWhileARewindIsUnderway() = runTest(dispatcher) {
        val vm = viewModel()
        val rewound = hold(Method.CHAT_REWIND)
        vm.rewind(ChatItem(60, ItemKind.USER, text = "第 60 条", uuid = "u-60"), clauderc.app.protocol.RewindRestore.BOTH) {}
        assertTrue(vm.state.value.rewinding)
        assertFalse(vm.send("再跑一遍"))
        assertFalse(vm.fork(""))
        assertTrue(relay.sent(Method.CHAT_SEND).isEmpty())
        rewound.complete(Unit)
        assertTrue(vm.send("再跑一遍"))
        assertEquals(1, relay.sent(Method.CHAT_SEND).size)
    }

    @Test fun multilineTextIsOneMessage() = runTest(dispatcher) {
        val vm = viewModel()
        assertTrue(vm.send("第一行\n第二行\n第三行"))
        val sent = relay.sent(Method.CHAT_SEND).single().decode(ChatSendRequest.serializer())
        assertTrue("send carries clientReqId", !sent.clientReqId.isNullOrEmpty())
        assertEquals(ChatSendRequest("s-need", "第一行\n第二行\n第三行"), sent.copy(clientReqId = null))
    }

    @Test fun imageLimitsAreCheckedBeforeSending() = runTest(dispatcher) {
        val vm = viewModel()
        vm.addImage("IMG_2041.jpg", 15_200_000) { error("must not be read") }
        assertTrue(vm.state.value.attachError!!.contains("超过 10 MB"))
        assertTrue(vm.state.value.attachments.isEmpty())
        // No count limit.
        repeat(6) { i -> vm.addImage("p$i.png", 100) { tinyPng() } }
        eventually { vm.state.first { it.attachments.size == 6 } }
        assertNull(vm.state.value.attachError)
    }

    @Test fun sendingPicturesWaitsLonger() = runTest(dispatcher) {
        val vm = viewModel()
        assertTrue(vm.send("只有文字"))
        vm.addImage("p.png", 100) { tinyPng() }
        assertEquals(1, vm.state.value.attachments.size)
        assertTrue(vm.send("看这张图"))
        assertEquals(listOf(null, SEND_IMAGES_TIMEOUT_MILLIS), relay.sent(Method.CHAT_SEND).map { it.timeoutMillis })
    }

    @Test fun imagesTogetherStayUnderTheTotal() = runTest(dispatcher) {
        val vm = ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = { ByteArray(4 shl 20) }, background = dispatcher)
        repeat(3) { i -> vm.addImage("p$i.jpg", 100) { tinyPng() } }
        val s = eventually { vm.state.first { it.attachError != null } }
        assertTrue(s.attachError!!.contains("加起来超过 11 MB"))
        assertEquals(listOf("p0.jpg", "p1.jpg"), s.attachments.map { it.name })
    }

    @Test fun imagesAreCompressedOneAtATimeInOrder() = runTest(dispatcher) {
        val running = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        val compress = { b: ByteArray ->
            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            Thread.sleep(50)
            running.decrementAndGet()
            b
        }
        val vm = ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = compress, background = Dispatchers.Default)
        repeat(4) { i -> vm.addImage("p$i.png", 100) { tinyPng() } }
        eventually { vm.state.first { it.attachments.size == 4 } }
        assertEquals(1, most.get())
        assertEquals(listOf("p0.png", "p1.png", "p2.png", "p3.png"), vm.state.value.attachments.map { it.name })
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

    @Test fun editHunksBecomeNumberedRowsWithAGapBetween() {
        val edit = ToolResult(
            patch = listOf(
                Hunk(8, 3, 8, 4, listOf(" // ErrVersion", "-var A = 1", "+var A = 2", "+var B = 3", " type Envelope struct {")),
                Hunk(15, 1, 24, 2, listOf(" }", "+// end")),
            ),
        )
        val rows = patchRows(edit)!!
        assertEquals(
            listOf(
                PatchRow(RowKind.SAME, 8, "// ErrVersion"), PatchRow(RowKind.DEL, 9, "var A = 1"), PatchRow(RowKind.ADD, 9, "var A = 2"),
                PatchRow(RowKind.ADD, 10, "var B = 3"), PatchRow(RowKind.SAME, 11, "type Envelope struct {"),
                PatchRow(RowKind.GAP), PatchRow(RowKind.SAME, 24, "}"), PatchRow(RowKind.ADD, 25, "// end"),
            ),
            rows,
        )
        assertEquals(7, patchTotal(edit, rows))
        assertEquals(PatchStat(added = 3, deleted = 1), patchStat(edit, rows))
    }

    @Test fun aWriteIsAllAddedAndAReadIsItsNumberedLines() {
        val write = ToolResult(patch = listOf(Hunk(0, 0, 1, 3, listOf("+package protocol", "+", "+type Error struct{}"))))
        val wrows = patchRows(write)!!
        assertEquals(listOf(1, 2, 3), wrows.map { it.line })
        assertTrue(wrows.all { it.kind == RowKind.ADD })
        assertEquals(PatchStat(added = 3), patchStat(write, wrows))

        val read = ToolResult("1\t# proj\n2\t\n3\thello\n\n<system-reminder>\nnot part of the file\n</system-reminder>", lines = 3)
        val rrows = patchRows(read)!!
        assertEquals(listOf(PatchRow(RowKind.SAME, 1, "# proj"), PatchRow(RowKind.SAME, 2, ""), PatchRow(RowKind.SAME, 3, "hello")), rrows)
        assertEquals(PatchStat(read = 3), patchStat(read, rrows))
        // Older Claude Code numbered lines with an arrow.
        assertEquals(PatchRow(RowKind.SAME, 12, "x := 1"), patchRows(ToolResult("    12→x := 1", lines = 1))!!.single())
        // Anything else keeps its plain detail.
        assertEquals(null, patchRows(ToolResult("Found 1 file\ninternal/protocol/envelope.go")))
        assertEquals(null, patchRows(null))
    }

    @Test fun aCutDiffKnowsItsTotalOnlyWhenAllLinesAreAdded() {
        val write = ToolResult(patch = listOf(Hunk(0, 0, 1, 900, List(80) { "+line $it" })), truncated = true)
        assertEquals(900, patchTotal(write, patchRows(write)!!))
        assertEquals(PatchStat(added = 900), patchStat(write, patchRows(write)!!))
        val edit = ToolResult(patch = listOf(Hunk(1, 400, 1, 420, List(80) { " line $it" })), truncated = true)
        assertEquals(null, patchTotal(edit, patchRows(edit)!!))
        assertEquals(null, patchStat(edit, patchRows(edit)!!))
        val read = ToolResult((1..80).joinToString("\n") { "$it\tline" }, lines = 2000, truncated = true)
        assertEquals(2000, patchTotal(read, patchRows(read)!!))
    }

    @Test fun theWholeResultIsFetchedOnceAndKeptByToolUseId() = runTest(dispatcher) {
        val whole = ToolResult((1..900).joinToString("\n") { "$it\tline" }, lines = 900)
        val gate = hold(Method.CHAT_TOOL_OUTPUT) { ProtocolJson.encodeToJsonElement(ToolResult.serializer(), whole) }
        val vm = viewModel()
        vm.loadToolOutput("toolu_big")
        vm.loadToolOutput("toolu_big")
        assertEquals(setOf("toolu_big"), vm.state.value.fetchingResults)
        gate.complete(Unit)
        assertEquals(ChatToolOutputRequest("s-need", "toolu_big"), relay.sent(Method.CHAT_TOOL_OUTPUT).single().decode(ChatToolOutputRequest.serializer()))
        assertEquals(whole, vm.state.value.fullResults["toolu_big"])
        assertTrue(vm.state.value.fetchingResults.isEmpty())
        vm.loadToolOutput("toolu_big")
        assertEquals(1, relay.sent(Method.CHAT_TOOL_OUTPUT).size)
    }

    @Test fun aFailedFetchIsReportedAndCanBeTriedAgain() = runTest(dispatcher) {
        relay.fail(Method.CHAT_TOOL_OUTPUT, ErrorCode.NOT_FOUND, "会话记录里找不到这次工具调用")
        val vm = viewModel()
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.messages.collect { messages += it } }
        vm.loadToolOutput("toolu_gone")
        assertEquals(listOf("会话记录里找不到这次工具调用"), messages)
        assertTrue(vm.state.value.fetchingResults.isEmpty())
        assertTrue(vm.state.value.fullResults.isEmpty())
        vm.loadToolOutput("toolu_gone")
        assertEquals(2, relay.sent(Method.CHAT_TOOL_OUTPUT).size)
    }

    /** A finished call with the one-line summary the agent writes for it (transcript.summary). */
    private fun call(id: String, name: String, input: Map<String, String>): ToolCall {
        val summary = when (name) {
            "Bash" -> "运行 " + input["command"]
            "Read" -> "读取 " + input["file_path"]!!.substringAfterLast('/')
            "Edit" -> "编辑 " + input["file_path"]!!.substringAfterLast('/')
            "Write" -> "写入 " + input["file_path"]!!.substringAfterLast('/')
            "Grep", "Glob" -> "搜索 " + input["pattern"]
            else -> name
        }
        return ToolCall(id, name, summary, buildJsonObject { input.forEach { (k, v) -> put(k, v) } }, ToolResult(""))
    }

    private fun summary(tools: List<ToolCall>) = groupSummary(tools, androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.Red).text

    /** The acceptance run: Grep, Read, Read, `ls src`, `go test`. */
    private val run = listOf(
        call("t1", "Grep", mapOf("pattern" to "Envelope")),
        call("t2", "Read", mapOf("file_path" to "/p/go.mod")),
        call("t3", "Read", mapOf("file_path" to "/p/AGENTS.md")),
        call("t4", "Bash", mapOf("command" to "ls src")),
        call("t5", "Bash", mapOf("command" to "go test")),
    )

    private fun tools(seq: Long, calls: List<ToolCall>) = calls.mapIndexed { i, t -> ChatItem(seq + i, ItemKind.TOOL, tool = t) }

    @Test fun fiveCallsInARowMergeIntoOneEntry() {
        val runs = chatRuns(tools(1, run))
        assertEquals(1, runs.size)
        assertEquals(5, runs.single().size)
        assertEquals("搜索 Envelope，读取 2 个文件，列出 1 个目录，运行 go test", summary(run))
    }

    @Test fun aReplyOrAPictureEndsTheRunAndOneCallStaysAlone() {
        val split = chatRuns(tools(1, run.take(2)) + ChatItem(3, ItemKind.ASSISTANT, text = "看一下") + tools(4, run.drop(2)))
        assertEquals(listOf(2, 1, 3), split.map { it.size })
        // Edits and writes join the run too (tool view A).
        val edit = call("e1", "Edit", mapOf("file_path" to "/p/x.go"))
        val write = call("w1", "Write", mapOf("file_path" to "/p/y.go"))
        assertEquals(listOf(5), chatRuns(tools(1, listOf(run[0], edit, run[1], write, run[4]))).map { it.size })
        assertEquals(listOf(1), chatRuns(tools(1, listOf(run[1]))).map { it.size })
        // Slash commands and clauderc show are rows of their own.
        val show = call("s1", "Bash", mapOf("command" to "clauderc show /tmp/a.png"))
        val slash = ToolCall("command-9", "/compact", "/compact")
        assertEquals(listOf(1, 1, 1), chatRuns(tools(1, listOf(run[1], show, run[2]))).map { it.size })
        val byPath = call("s2", "Bash", mapOf("command" to "cd /p && /usr/local/bin/clauderc show a.png"))
        assertEquals(listOf(1, 1, 1), chatRuns(tools(1, listOf(run[1], byPath, run[2]))).map { it.size })
        assertEquals(listOf(1, 1), chatRuns(tools(1, listOf(slash, run[2]))).map { it.size })
        // So is a read of a picture, shown under its row.
        val shot = ToolCall("i1", "Read", "读取 chat.png", result = ToolResult(images = listOf(ToolImage("i1.0", "image/png", size = 10))))
        assertEquals(listOf(1, 1, 1), chatRuns(tools(1, listOf(run[1], shot, run[2]))).map { it.size })
    }

    @Test fun summaryCountsOnlyWhatIsThere() {
        assertEquals("读取 2 个文件", summary(run.subList(1, 3)))
        assertEquals("列出 1 个目录，运行 go test", summary(run.subList(3, 5)))
        assertEquals("搜索 2 个模式", summary(listOf(run[0], call("g", "Glob", mapOf("pattern" to "*.go")))))
    }

    @Test fun aPictureIsFetchedOnceAndAFailureIsMarked() = runTest(dispatcher) {
        relay.respond(Method.CHAT_IMAGE, ChatImageResponse.serializer(), ChatImageResponse("image/png", "AQID"))
        val vm = viewModel()
        vm.loadImage("toolu_I.0")
        vm.loadImage("toolu_I.0")
        assertEquals(ChatImageRequest("s-need", "toolu_I.0"), relay.sent(Method.CHAT_IMAGE).single().decode(ChatImageRequest.serializer()))
        assertTrue(vm.state.value.wholeImages["toolu_I.0"]!!.bytes.contentEquals(byteArrayOf(1, 2, 3)))

        relay.fail(Method.CHAT_IMAGE, ErrorCode.NOT_FOUND)
        vm.loadImage("toolu_X.0")
        assertEquals(setOf("toolu_X.0"), vm.state.value.failedImages)
        assertTrue(vm.state.value.fetchingImages.isEmpty())
    }

    // ---------- 2026-11-22: saving a picture into the gallery ----------

    private val shot = ToolImage("toolu_I.0", "image/png", name = "chat.png", width = 720, height = 1560, size = 3)

    /** One write the saver was asked for: what, under which name, in which format. */
    private class Write(val bytes: ByteArray, val name: String?, val mediaType: String?)

    /** A saver that notes each write as it starts and then waits for [gate], when given, before it finishes. */
    private class FakeSaver(val gate: CompletableDeferred<Unit>? = null, val fail: Boolean = false) : ImageSaver {
        val writes = mutableListOf<Write>()

        override suspend fun save(bytes: ByteArray, name: String?, mediaType: String?) {
            writes += Write(bytes, name, mediaType)
            gate?.await()
            if (fail) throw java.io.IOException("相册没有接受这张图")
        }
    }

    /** A page whose viewer shows [shot], its bytes fetched. */
    private fun viewingShot(saver: ImageSaver, vm: ChatViewModel = viewModel(saver = saver)): ChatViewModel {
        relay.respond(Method.CHAT_IMAGE, ChatImageResponse.serializer(), ChatImageResponse("image/png", "AQID"))
        vm.loadImage(shot.id)
        vm.viewImage(shot.id)
        return vm
    }

    @Test fun tappingSaveThreeTimesWritesThePictureOnce() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val saver = FakeSaver(gate)
        val vm = viewingShot(saver)
        repeat(3) { vm.saveImage(shot) }
        assertEquals(setOf(shot.id), vm.state.value.savingImages)
        gate.complete(Unit)
        assertEquals(setOf(shot.id), vm.state.value.savedImages)
        assertTrue(vm.state.value.savingImages.isEmpty())
        // Saved, the button is a tick until the viewer closes: another tap writes nothing either.
        vm.saveImage(shot)
        assertEquals(1, saver.writes.size)
        assertTrue(saver.writes.single().bytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("chat.png", saver.writes.single().name)
        // Closed and opened again, it can be saved again.
        vm.viewImage(null)
        assertTrue(vm.state.value.savedImages.isEmpty())
        vm.viewImage(shot.id)
        vm.saveImage(shot)
        assertEquals(2, saver.writes.size)
        // With the viewer on it, the tick is all that says so.
        assertNull(withTimeoutOrNull(1_000) { vm.messages.first() })
    }

    @Test fun aSaveGoesOnAfterThePageIsGone() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val saver = FakeSaver(gate)
        val store = androidx.lifecycle.ViewModelStore()
        val vm = viewingShot(saver, pageIn(store, ChatCache(), saver))
        vm.saveImage(shot)
        store.clear()
        gate.complete(Unit)
        assertEquals(1, saver.writes.size)
        assertTrue("the write ran to its end", vm.state.value.savingImages.isEmpty())
    }

    @Test fun aFailedSaveSaysSo() = runTest(dispatcher) {
        val vm = viewingShot(FakeSaver(fail = true))
        vm.saveImage(shot)
        assertEquals("保存失败", withTimeout(1_000) { vm.messages.first() })
        assertTrue(vm.state.value.savingImages.isEmpty())
        assertTrue("the download icon comes back", vm.state.value.savedImages.isEmpty())
    }

    @Test fun aSaveThatEndsWithTheViewerClosedSaysWhereThePictureWent() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = viewingShot(FakeSaver(gate))
        vm.saveImage(shot)
        vm.viewImage(null)
        gate.complete(Unit)
        assertEquals("已保存到相册", withTimeout(1_000) { vm.messages.first() })
        assertTrue("opened again, it shows the download icon", vm.state.value.savedImages.isEmpty())
    }

    @Test fun aFetchedPictureKeepsTheMediaTypeChatImageNamed() = runTest(dispatcher) {
        // The thumbnail's row said PNG; the picture itself came as WebP.
        relay.respond(Method.CHAT_IMAGE, ChatImageResponse.serializer(), ChatImageResponse("image/webp", "UklGRg=="))
        val saver = FakeSaver()
        val vm = viewModel(saver = saver)
        vm.loadImage(shot.id)
        assertEquals("image/webp", vm.state.value.wholeImages[shot.id]!!.mediaType)
        vm.saveImage(shot)
        assertEquals("image/webp", saver.writes.single().mediaType)
    }

    private fun tinyPng(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    /** Round 3: the reply drawn on the screen types under the last item, and the written reply takes over its bubble. */
    @Test fun theScreensReplyGivesItsBubbleToTheWrittenReply() = runTest(dispatcher) {
        val vm = viewModel()
        fun screen(partial: String?, activity: String? = "Whirring… (20s · ↓ 2.4k tokens)", state: SessionState = SessionState.Working) =
            relay.event(Method.CHAT_STATE, "m-1", ChatStateEvent.serializer(), ChatStateEvent("s-need", state, activity = activity, partial = partial))
        screen("这周主要做了")
        val p = vm.state.value.partial!!
        assertEquals("这周主要做了", p.text)
        assertEquals(101L, p.afterSeq)
        assertEquals("Whirring… (20s · ↓ 2.4k tokens)", vm.state.value.activity)
        screen("这周主要做了三件事。\n\n第一")
        assertEquals("the same bubble grows", p.key, vm.state.value.partial!!.key)
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-need", listOf(text(102, body = "这周主要做了**三件事**。\n\n第一，聊天记录改成变了才读。"))))
        assertNull(vm.state.value.partial)
        assertEquals("the reply takes over its key", p.key, vm.state.value.keys[102])
        // The agent may send the screen once more before it reads the transcript: that echo is dropped.
        screen("这周主要做了三件事。 第一，聊天记录")
        assertNull(vm.state.value.partial)
        // After a tool call, the next reply is a bubble of its own.
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-need", listOf(ChatItem(103, ItemKind.TOOL, tool = bash))))
        screen("测试都过了")
        assertTrue(vm.state.value.partial!!.key != p.key)
        // When the turn ends, the status line and the preview go.
        screen(null, null, SessionState.Idle)
        assertNull(vm.state.value.partial)
        assertNull(vm.state.value.activity)
    }

    @Test fun networkFailureOnOpenReopensWhenReconnected() = runTest(dispatcher) {
        var fail = true
        relay.on(Method.CHAT_OPEN) {
            if (fail) throw clauderc.app.net.NotConnectedException()
            ProtocolJson.encodeToJsonElement(ChatOpenResponse.serializer(), ChatOpenResponse(items = listOf(text(1, body = "连上后到了")), state = SessionState.Idle))
        }
        val vm = viewModel()
        assertTrue(vm.state.value.items.isEmpty())
        assertFalse("network failure must not mark the page opened", vm.state.value.opened)
        assertEquals(1, relay.sent(Method.CHAT_OPEN).size)

        fail = false
        relay.state.value = clauderc.app.net.ConnectionState.Waiting(1, 0)
        relay.state.value = clauderc.app.net.ConnectionState.Connected
        assertEquals(2, relay.sent(Method.CHAT_OPEN).size)
        assertEquals(listOf("连上后到了"), vm.state.value.items.map { it.text })
        assertTrue(vm.state.value.opened)
    }

    @Test fun failedLoadOlderMarksOlderFailedAndRetries() = runTest(dispatcher) {
        var failOlder = true
        relay.on(Method.CHAT_OPEN) { sent ->
            val req = sent.decode(ChatOpenRequest.serializer())
            if (req.before == null) {
                ProtocolJson.encodeToJsonElement(ChatOpenResponse.serializer(), ChatOpenResponse(items = listOf(text(50, body = "第 50 条")), hasMore = true, state = SessionState.Idle))
            } else if (failOlder) {
                throw clauderc.app.net.NotConnectedException()
            } else {
                ProtocolJson.encodeToJsonElement(ChatOpenResponse.serializer(), ChatOpenResponse(items = listOf(text(40, body = "第 40 条")), hasMore = false, state = SessionState.Idle))
            }
        }
        val vm = viewModel()
        vm.loadOlder()
        assertFalse(vm.state.value.loadingOlder)
        assertTrue(vm.state.value.olderFailed)
        assertTrue(vm.state.value.hasMore)

        failOlder = false
        vm.loadOlder()
        assertFalse(vm.state.value.loadingOlder)
        assertFalse(vm.state.value.olderFailed)
        assertFalse(vm.state.value.hasMore)
        assertEquals(listOf(40L, 50L), vm.state.value.items.map { it.seq })
    }

    @Test fun rapidAutoResumeTogglesEndOnTheLastTap() = runTest(dispatcher) {
        val resets = java.time.Instant.parse("2026-11-23T22:00:00Z")
        val baseLimit = clauderc.app.protocol.Limit("fiveHour", "limit", resets)
        relay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = emptyList(), state = SessionState.Idle, limit = baseLimit))
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        val calls = mutableListOf<Boolean>()
        relay.on(Method.CHAT_AUTO_RESUME) { sent ->
            val req = sent.decode(clauderc.app.protocol.ChatAutoResumeRequest.serializer())
            calls += req.on
            val gate = CompletableDeferred<Unit>()
            gates += gate
            gate.await()
            val out = if (req.on) baseLimit.copy(autoResume = clauderc.app.protocol.AutoResume(req.text.orEmpty(), resets.plusSeconds(60))) else baseLimit
            ProtocolJson.encodeToJsonElement(clauderc.app.protocol.ChatAutoResumeResponse.serializer(), clauderc.app.protocol.ChatAutoResumeResponse(out))
        }
        val vm = viewModel()
        // Rapid taps: ON -> OFF -> ON -> OFF while the first request is still in flight.
        vm.setAutoResume(true)
        vm.setAutoResume(false)
        vm.setAutoResume(true)
        vm.setAutoResume(false)
        assertEquals("only the first request went out before answering", listOf(true), calls)
        assertNull("UI reflects the last tap (off)", vm.state.value.limit?.autoResume)

        // First request answers ON: must not flip UI back on; then the latest (OFF) goes out.
        gates[0].complete(Unit)
        assertNull("stale ON answer did not overwrite OFF", vm.state.value.limit?.autoResume)
        assertEquals("skipped superseded middle taps and sent final OFF", listOf(true, false), calls)
        gates[1].complete(Unit)
        assertNull(vm.state.value.limit?.autoResume)

        // Now tap ON -> OFF -> ON: must end ON.
        vm.setAutoResume(true)
        vm.setAutoResume(false)
        vm.setAutoResume(true)
        gates[2].complete(Unit)
        assertEquals(listOf(true, false, true, true), calls)
        gates[3].complete(Unit)
        assertTrue(vm.state.value.limit?.autoResume != null)
    }

    @Test fun messageSentBeforeOpenIsNotSettledByOlderMatchingSnapshotItem() = runTest(dispatcher) {
        val openGate = hold(Method.CHAT_OPEN) {
            ProtocolJson.encodeToJsonElement(
                ChatOpenResponse.serializer(),
                ChatOpenResponse(
                    items = listOf(text(1, ItemKind.USER, "继续"), text(2, ItemKind.ASSISTANT, "上一次的回复")),
                    state = SessionState.Idle,
                ),
            )
        }
        val sendGate = hold(Method.CHAT_SEND)
        val vm = viewModel()
        // Send "继续" before chat.open returns.
        assertTrue(vm.send("继续"))
        assertEquals(listOf("继续"), vm.state.value.outgoing.map { it.text })

        // chat.open returns a snapshot containing an older "继续": the new outgoing bubble must stay unsettled.
        openGate.complete(Unit)
        assertEquals("older matching item in snapshot must not settle the new send", listOf("继续"), vm.state.value.outgoing.map { it.text })
        assertEquals("anchored to the snapshot tip", 2L, vm.state.value.outgoing.single().afterSeq)

        // Once the new "继续" actually lands after the snapshot tip, it settles.
        sendGate.complete(Unit)
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-need", listOf(text(3, ItemKind.USER, "继续"))))
        assertTrue(vm.state.value.outgoing.isEmpty())
    }

    @Test fun twoSimultaneousFailedSendsKeepBothTextsInRestoreAndDraft() = runTest(dispatcher) {
        val storeScope = CoroutineScope(Dispatchers.IO + Job())
        val drafts = clauderc.app.data.DraftStore(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { java.io.File(tmp.root, "both-fail.preferences_pb") }),
        )
        val cache = ChatCache()
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        relay.on(Method.CHAT_SEND) {
            val g = CompletableDeferred<Unit>()
            gates += g
            g.await()
            throw clauderc.app.net.RemoteException(ErrorCode.NOT_DELIVERED, "没送达")
        }
        val store1 = androidx.lifecycle.ViewModelStore()
        val vm1 = androidx.lifecycle.ViewModelProvider(store1, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = { it }, cache = cache, background = dispatcher, drafts = drafts) as T
        })[ChatViewModel::class.java]
        assertTrue(vm1.send("第一条消息"))
        store1.clear()

        val vm2 = ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = { it }, cache = cache, background = dispatcher, drafts = drafts)
        assertTrue(vm2.send("第二条消息"))
        assertEquals(2, gates.size)

        // Both fail concurrently while vm2 is open.
        gates[0].complete(Unit)
        gates[1].complete(Unit)
        assertEquals("第一条消息\n第二条消息", vm2.state.value.restore?.text)
        assertEquals("第一条消息\n第二条消息", eventually { drafts.draft("m-1", "s-need").first { it.contains("第一条消息") && it.contains("第二条消息") } })
        storeScope.cancel()
    }

    @Test fun connIdChangeWhileOnlineReopensChat() = runTest(dispatcher) {
        relay.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "mac-mini", online = true, connId = "g-1"))))
        var opens = 0
        relay.on(Method.CHAT_OPEN) {
            opens++
            ProtocolJson.encodeToJsonElement(
                ChatOpenResponse.serializer(),
                ChatOpenResponse(items = listOf(text(opens.toLong(), ItemKind.ASSISTANT, "第 $opens 次打开")), state = SessionState.Idle),
            )
        }
        val vm = viewModel()
        assertEquals(1, opens)
        assertEquals(listOf("第 1 次打开"), vm.state.value.items.map { it.text })

        // Machine stays online=true but receives a fresh connId after being replaced.
        relay.event(Method.MACHINE_STATUS, "m-1", clauderc.app.protocol.Machine.serializer(), machine("m-1", "mac-mini", online = true, connId = "g-2"))
        assertEquals("connId change reopens chat even when online stayed true", 2, opens)
        assertTrue(vm.state.value.online)
        assertEquals(listOf("第 2 次打开"), vm.state.value.items.map { it.text })
    }

    @Test fun staleChatOpenDoesNotOverwriteNewerItemsPendingOrOlderPages() = runTest(dispatcher) {
        var openCalls = 0
        val reopenGate = CompletableDeferred<Unit>()
        relay.on(Method.CHAT_OPEN) { sent ->
            val req = sent.decode(ChatOpenRequest.serializer())
            if (req.before != null) {
                ProtocolJson.encodeToJsonElement(
                    ChatOpenResponse.serializer(),
                    ChatOpenResponse(items = (1L..10L).map { text(it, ItemKind.USER, "old-$it") }, hasMore = false),
                )
            } else if (++openCalls == 1) {
                ProtocolJson.encodeToJsonElement(
                    ChatOpenResponse.serializer(),
                    ChatOpenResponse(
                        items = (11L..15L).map { text(it, ItemKind.USER, "m-$it") },
                        hasMore = true,
                        state = SessionState.Idle,
                        rev = 2,
                        subId = "sub-1",
                    ),
                )
            } else {
                reopenGate.await()
                ProtocolJson.encodeToJsonElement(
                    ChatOpenResponse.serializer(),
                    ChatOpenResponse(
                        items = (11L..15L).map { text(it, ItemKind.USER, "m-$it") },
                        hasMore = true,
                        state = SessionState.Idle,
                        pending = null,
                        rev = 3,
                        subId = "sub-2",
                    ),
                )
            }
        }
        val vm = viewModel()
        vm.loadOlder()
        assertEquals((1L..15L).toList(), vm.state.value.items.map { it.seq })
        assertFalse(vm.state.value.hasMore)

        // Trigger a second open while keeping the same rev space (e.g. machine blinked or re-opened):
        // newer pushes arrive with rev 5 and 6 BEFORE the older rev 3 chat.open response arrives.
        relay.event(Method.MACHINE_STATUS, "m-1", clauderc.app.protocol.Machine.serializer(), machine("m-1", "mac-mini", online = false))
        relay.event(Method.MACHINE_STATUS, "m-1", clauderc.app.protocol.Machine.serializer(), machine("m-1", "mac-mini", online = true))
        assertEquals(2, openCalls)

        val newerPending = clauderc.app.protocol.Pending("toolu_new", clauderc.app.protocol.PendingKind.PERMISSION, toolName = "Bash")
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-need", listOf(text(16, ItemKind.ASSISTANT, "新回复")), rev = 5))
        relay.event(Method.CHAT_PENDING, "m-1", clauderc.app.protocol.ChatPendingEvent.serializer(), clauderc.app.protocol.ChatPendingEvent("s-need", newerPending, rev = 6))

        // Now the stale rev=3 chat.open arrives: must preserve newer item 16, newer pending, and older pages 1..10.
        reopenGate.complete(Unit)
        assertEquals((1L..16L).toList(), vm.state.value.items.map { it.seq })
        assertEquals("新回复", vm.state.value.items.last().text)
        assertFalse("already loaded down to seq 1", vm.state.value.hasMore)
        assertEquals("toolu_new", vm.state.value.pending?.toolUseId)
    }

    @Test fun resetItemsEventClearsOldItemsAndShowsNewTranscript() = runTest(dispatcher) {
        relay.respond(
            Method.CHAT_OPEN,
            ChatOpenResponse.serializer(),
            ChatOpenResponse(items = listOf(text(1, ItemKind.USER, "旧消息"), text(2, ItemKind.ASSISTANT, "旧回复")), hasMore = true, rev = 1),
        )
        val vm = viewModel()
        assertEquals(listOf("旧消息", "旧回复"), vm.state.value.items.map { it.text })
        assertTrue(vm.state.value.hasMore)

        relay.event(
            Method.CHAT_ITEMS,
            "m-1",
            ChatItemsEvent.serializer(),
            ChatItemsEvent("s-need", listOf(text(1, ItemKind.USER, "清空后的新话题")), rev = 2, reset = true),
        )
        assertEquals(listOf("清空后的新话题"), vm.state.value.items.map { it.text })
        assertFalse(vm.state.value.hasMore)
    }

    @Test fun sendDuringDisconnectStaysPendingAndSettlesWhenFoundInReopenSnapshot() = runTest(dispatcher) {
        var openCalls = 0
        relay.on(Method.CHAT_OPEN) {
            val items = if (++openCalls == 1) {
                listOf(text(1, ItemKind.USER, "旧消息"))
            } else {
                listOf(text(1, ItemKind.USER, "旧消息"), text(2, ItemKind.USER, "断线时发出的消息"))
            }
            ProtocolJson.encodeToJsonElement(ChatOpenResponse.serializer(), ChatOpenResponse(items = items, state = SessionState.Idle))
        }
        val sendGate = hold(Method.CHAT_SEND) { throw clauderc.app.net.NotConnectedException() }
        val vm = viewModel()
        assertTrue(vm.send("断线时发出的消息"))

        // Connection drops while chat.send is in flight.
        relay.state.value = clauderc.app.net.ConnectionState.Waiting(1, 0)
        sendGate.complete(Unit)
        assertEquals("bubble stays pending confirmation", listOf("断线时发出的消息"), vm.state.value.outgoing.map { it.text })
        assertTrue(vm.state.value.outgoing.single().uncertain)
        assertNull("composer is not restored while outcome is unknown", vm.state.value.restore)

        // Reconnect: chat.open snapshot contains the message -> settled as delivered, no restore.
        relay.state.value = clauderc.app.net.ConnectionState.Connected
        assertTrue("settled as delivered", vm.state.value.outgoing.isEmpty())
        assertNull(vm.state.value.restore)
        assertEquals(listOf("旧消息", "断线时发出的消息"), vm.state.value.items.map { it.text })
    }

    @Test fun sendDuringDisconnectReturnsToComposerWhenMissingFromReopenSnapshot() = runTest(dispatcher) {
        relay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = listOf(text(1, ItemKind.USER, "旧消息")), state = SessionState.Idle))
        val sendGate = hold(Method.CHAT_SEND) { throw clauderc.app.net.NotConnectedException() }
        val vm = viewModel()
        val notices = mutableListOf<String>()
        val job = launch { vm.messages.collect { notices += it } }

        assertTrue(vm.send("没送到的消息"))
        relay.state.value = clauderc.app.net.ConnectionState.Waiting(1, 0)
        sendGate.complete(Unit)
        assertEquals(listOf("没送到的消息"), vm.state.value.outgoing.map { it.text })
        assertNull(vm.state.value.restore)

        // Reconnect: chat.open snapshot does NOT contain the message -> returned to composer with notice.
        relay.state.value = clauderc.app.net.ConnectionState.Connected
        assertTrue(vm.state.value.outgoing.isEmpty())
        assertEquals("没送到的消息", vm.state.value.restore?.text)
        assertTrue("user is told it was not delivered", notices.any { it.contains("没有送到") })
        job.cancel()
    }

    @Test fun stoppedSessionChatSendUses120SecondTimeout() = runTest(dispatcher) {
        relay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = emptyList(), state = SessionState.Stopped))
        val vm = viewModel()
        assertTrue(vm.send("唤醒后发的消息"))
        assertEquals(120_000L, relay.sent(Method.CHAT_SEND).single().timeoutMillis)
    }

    @Test fun sentUserMessageImagesStayLocalAndSkipChatImage() = runTest(dispatcher) {
        val original = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
        val vm = ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = { it }, background = dispatcher)
        vm.addImage("photo.jpg", original.size.toLong()) { original }
        assertEquals(1, vm.state.value.attachments.size)
        assertTrue(vm.send("刚拍的图"))
        val sentReq = relay.sent(Method.CHAT_SEND).single().decode(ChatSendRequest.serializer())
        assertTrue(!sentReq.clientReqId.isNullOrEmpty())

        // Transcript push arrives carrying a thumbnail with an image ID: ViewModel pairs it with the local bytes.
        val thumbB64 = java.util.Base64.getEncoder().encodeToString(tinyPng())
        val delivered = ChatItem(
            seq = 102,
            kind = ItemKind.USER,
            text = "[Image #1] 刚拍的图",
            images = listOf(clauderc.app.protocol.Image("image/jpeg", thumbB64, id = "usr-local.0", width = 1200, height = 900, size = original.size.toLong())),
        )
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-need", listOf(delivered)))

        val whole = vm.state.value.wholeImages["usr-local.0"]
        assertTrue("original bytes kept locally under the server's image id", whole != null && whole.bytes.contentEquals(original))
        // Requesting loadImage for it does NOT hit the network.
        vm.loadImage("usr-local.0")
        assertTrue("no chat.image request sent", relay.sent(Method.CHAT_IMAGE).isEmpty())
    }

    @Test fun oldAppStyleDecodeStillReadsThumbnailData() {
        // Older apps ignore unknown optional fields (id, width, height, size) and decode mediaType + data directly.
        val json = """{"id":"usr-u1.0","mediaType":"image/jpeg","data":"${java.util.Base64.getEncoder().encodeToString(tinyPng())}","width":1200,"height":900,"size":4194304}"""
        val decoded = ProtocolJson.decodeFromString(clauderc.app.protocol.Image.serializer(), json)
        assertEquals("image/jpeg", decoded.mediaType)
        assertTrue(java.util.Base64.getDecoder().decode(decoded.data).contentEquals(tinyPng()))
        // Simulate an older schema with only mediaType + data under ignoreUnknownKeys = true.
        val obj = ProtocolJson.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        assertEquals("image/jpeg", (obj["mediaType"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue(java.util.Base64.getDecoder().decode((obj["data"] as kotlinx.serialization.json.JsonPrimitive).content).contentEquals(tinyPng()))
    }
}

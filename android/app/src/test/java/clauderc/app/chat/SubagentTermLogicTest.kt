package clauderc.app.chat

import clauderc.app.data.Fleet
import clauderc.app.protocol.ChatCloseRequest
import clauderc.app.protocol.ChatImageRequest
import clauderc.app.protocol.ChatImageResponse
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.ChatItemsEvent
import clauderc.app.protocol.ChatOpenRequest
import clauderc.app.protocol.ChatOpenResponse
import clauderc.app.protocol.ChatStateEvent
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.SessionIdRequest
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.TermKey
import clauderc.app.protocol.TermKeyRequest
import clauderc.app.protocol.TermScreen
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.decode
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Round 2 C: a subagent's page and the terminal view against a scripted relay. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentTermLogicTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher + Job())
    private val relay = FakeRelay()
    private val agentId = "ab360a15e1da6a482"

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        relay.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "mac-mini"))))
        relay.respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions)
    }

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun chat(agent: String?) =
        ChatViewModel("m-1", "s-work", relay, Fleet(relay, scope), scope, compress = { it }, background = dispatcher, agentId = agent)

    /** A view model kept in [store], so clearing the store closes the page as navigation does. */
    private fun <T : androidx.lifecycle.ViewModel> pageIn(store: androidx.lifecycle.ViewModelStore, make: () -> T, cls: Class<T>): T =
        androidx.lifecycle.ViewModelProvider(store, object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : androidx.lifecycle.ViewModel> create(modelClass: Class<V>): V = make() as V
        })[cls]

    @Test fun aSubagentPageOpensItsTranscriptReadOnlyAndFollowsOnlyItsOwnEvents() = runTest(dispatcher) {
        relay.respond(
            Method.CHAT_OPEN, ChatOpenResponse.serializer(),
            ChatOpenResponse(items = listOf(ChatItem(1, ItemKind.NOTICE, title = "任务", text = "读一遍 relay 的连接管理")), state = SessionState.Working),
        )
        val store = androidx.lifecycle.ViewModelStore()
        val vm = pageIn(store, { chat(agentId) }, ChatViewModel::class.java)
        assertEquals(ChatOpenRequest("s-work", agentId = agentId), relay.sent(Method.CHAT_OPEN).single().decode(ChatOpenRequest.serializer()))
        assertTrue("no commands for a page without a composer", relay.sent(Method.CHAT_COMMANDS).isEmpty())
        assertEquals(SessionState.Working, vm.state.value.state)

        // The session's own items and those of another subagent are not its.
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-work", listOf(ChatItem(2, ItemKind.ASSISTANT, text = "主会话的回复"))))
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-work", listOf(ChatItem(2, ItemKind.ASSISTANT, text = "别的子代理")), agentId = "a0000000000000000"))
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-work", listOf(ChatItem(2, ItemKind.ASSISTANT, text = "两种断开都会进退避。")), agentId = agentId))
        assertEquals(listOf("读一遍 relay 的连接管理", "两种断开都会进退避。"), vm.state.value.items.map { it.text })

        relay.event(Method.CHAT_STATE, "m-1", ChatStateEvent.serializer(), ChatStateEvent("s-work", SessionState.Idle))
        assertEquals("the session's own state is not the subagent's", SessionState.Working, vm.state.value.state)
        relay.event(Method.CHAT_STATE, "m-1", ChatStateEvent.serializer(), ChatStateEvent("s-work", SessionState.Idle, agentId = agentId))
        assertEquals(SessionState.Idle, vm.state.value.state)

        store.clear()
        assertEquals(ChatCloseRequest("s-work", agentId), relay.sent(Method.CHAT_CLOSE).single().decode(ChatCloseRequest.serializer()))
    }

    @Test fun aSubagentPageAsksForItsOwnPictures() = runTest(dispatcher) {
        relay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = emptyList()))
        relay.respond(Method.CHAT_IMAGE, ChatImageResponse.serializer(), ChatImageResponse("image/png", "iVBORw0KGgo="))
        val sub = chat(agentId)
        sub.loadImage("toolu_sub.0")
        assertEquals(ChatImageRequest("s-work", "toolu_sub.0", agentId), relay.sent(Method.CHAT_IMAGE).single().decode(ChatImageRequest.serializer()))
        assertTrue("toolu_sub.0" in sub.state.value.wholeImages)
        // The session's own chat names no subagent.
        chat(null).loadImage("toolu_main.0")
        assertEquals(ChatImageRequest("s-work", "toolu_main.0"), relay.sent(Method.CHAT_IMAGE).last().decode(ChatImageRequest.serializer()))
    }

    @Test fun theSessionsChatIgnoresSubagentEventsAndReadsTheUnknownDialogFlag() = runTest(dispatcher) {
        relay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = listOf(ChatItem(1, ItemKind.USER, text = "开始")), unknownDialog = true))
        val vm = chat(null)
        assertTrue(vm.state.value.unknownDialog)
        relay.event(Method.CHAT_ITEMS, "m-1", ChatItemsEvent.serializer(), ChatItemsEvent("s-work", listOf(ChatItem(2, ItemKind.ASSISTANT, text = "子代理的回复")), agentId = agentId))
        assertEquals(listOf("开始"), vm.state.value.items.map { it.text })
        relay.event(Method.CHAT_STATE, "m-1", ChatStateEvent.serializer(), ChatStateEvent("s-work", SessionState.Idle))
        assertFalse(vm.state.value.unknownDialog)
        relay.event(Method.CHAT_STATE, "m-1", ChatStateEvent.serializer(), ChatStateEvent("s-work", SessionState.Idle, unknownDialog = true))
        assertTrue(vm.state.value.unknownDialog)
    }

    private val prompt = TermScreen("s-work", List(40) { if (it == 36) "❯ " else "" }, cursorRow = 36, cursorCol = 2, cursor = true)

    @Test fun theTerminalOpensFollowsScreensPressesKeysAndClosesOnLeaving() = runTest(dispatcher) {
        relay.respond(Method.TERM_OPEN, TermScreen.serializer(), prompt)
        val store = androidx.lifecycle.ViewModelStore()
        val vm = pageIn(store, { TermViewModel("m-1", "s-work", relay, Fleet(relay, scope), scope, background = dispatcher) }, TermViewModel::class.java)
        assertEquals(SessionIdRequest("s-work"), relay.sent(Method.TERM_OPEN).single().decode(SessionIdRequest.serializer()))
        assertEquals(prompt, vm.state.value.screen)

        val panel = TermScreen("s-work", listOf("   Manage MCP servers", "   ❯ ✔ chrome-devtools     26 tools"), cursorRow = 1, cursorCol = 3)
        relay.event(Method.TERM_SCREEN, "m-1", TermScreen.serializer(), panel.copy(sessionId = "s-idle"))
        assertEquals("another session's screen is not this one's", prompt, vm.state.value.screen)
        relay.event(Method.TERM_SCREEN, "m-1", TermScreen.serializer(), panel)
        assertEquals(panel, vm.state.value.screen)

        vm.key(TermKey.UP)
        vm.key("1")
        assertEquals(listOf(TermKeyRequest("s-work", "up"), TermKeyRequest("s-work", "1")), relay.sent(Method.TERM_KEY).map { it.decode(TermKeyRequest.serializer()) })

        store.clear()
        assertEquals(SessionIdRequest("s-work"), relay.sent(Method.TERM_CLOSE).single().decode(SessionIdRequest.serializer()))
    }

    @Test fun aStoppedSessionsTerminalSaysSoAndTakesNoKeys() = runTest(dispatcher) {
        relay.fail(Method.TERM_OPEN, "invalidParams", "会话已停止，无法操作")
        val vm = TermViewModel("m-1", "s-work", relay, Fleet(relay, scope), scope, background = dispatcher)
        assertTrue(vm.state.value.ended)
        assertEquals("会话已停止，无法操作", vm.state.value.error)
        vm.key(TermKey.ENTER)
        assertTrue(relay.sent(Method.TERM_KEY).isEmpty())
    }

    @Test fun networkFailureOnTermOpenReopensWhenReconnected() = runTest(dispatcher) {
        var fail = true
        relay.on(Method.TERM_OPEN) {
            if (fail) throw clauderc.app.net.NotConnectedException()
            clauderc.app.protocol.ProtocolJson.encodeToJsonElement(TermScreen.serializer(), prompt)
        }
        val vm = TermViewModel("m-1", "s-work", relay, Fleet(relay, scope), scope, background = dispatcher)
        assertEquals(null, vm.state.value.screen)
        assertFalse(vm.state.value.ended)
        assertEquals(1, relay.sent(Method.TERM_OPEN).size)

        fail = false
        relay.state.value = clauderc.app.net.ConnectionState.Waiting(1, 0)
        relay.state.value = clauderc.app.net.ConnectionState.Connected
        assertEquals(2, relay.sent(Method.TERM_OPEN).size)
        assertEquals(prompt, vm.state.value.screen)
    }

    @Test fun rapidKeyTapsAreSentOneAfterAnotherInOrder() = runTest(dispatcher) {
        relay.respond(Method.TERM_OPEN, TermScreen.serializer(), prompt)
        val gates = List(3) { kotlinx.coroutines.CompletableDeferred<Unit>() }
        var seen = 0
        relay.on(Method.TERM_KEY) {
            val g = gates[seen++]
            g.await()
            kotlinx.serialization.json.JsonObject(emptyMap())
        }
        val vm = TermViewModel("m-1", "s-work", relay, Fleet(relay, scope), scope, background = dispatcher)
        vm.key(TermKey.DOWN)
        vm.key(TermKey.DOWN)
        vm.key(TermKey.ENTER)
        assertEquals("only the first key goes out before it answers", listOf("down"), relay.sent(Method.TERM_KEY).map { it.decode(TermKeyRequest.serializer()).key })
        gates[0].complete(Unit)
        assertEquals(listOf("down", "down"), relay.sent(Method.TERM_KEY).map { it.decode(TermKeyRequest.serializer()).key })
        gates[1].complete(Unit)
        assertEquals(listOf("down", "down", "enter"), relay.sent(Method.TERM_KEY).map { it.decode(TermKeyRequest.serializer()).key })
        gates[2].complete(Unit)
    }

    @Test fun connIdChangeWhileOnlineReopensTerminal() = runTest(dispatcher) {
        relay.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "mac-mini", online = true, connId = "g-1"))))
        relay.respond(Method.TERM_OPEN, TermScreen.serializer(), prompt)
        val vm = TermViewModel("m-1", "s-work", relay, Fleet(relay, scope), scope, background = dispatcher)
        assertEquals(1, relay.sent(Method.TERM_OPEN).size)

        relay.event(Method.MACHINE_STATUS, "m-1", clauderc.app.protocol.Machine.serializer(), machine("m-1", "mac-mini", online = true, connId = "g-2"))
        assertEquals("connId change reopens terminal even when online stayed true", 2, relay.sent(Method.TERM_OPEN).size)
        assertTrue(vm.state.value.online)
    }

    @Test fun staleTermOpenDoesNotOverwriteNewerScreen() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val oldScreen = TermScreen("s-work", listOf("旧画面"), rev = 2, subId = "sub-7")
        val newScreen = TermScreen("s-work", listOf("新画面"), rev = 5)
        relay.on(Method.TERM_OPEN) {
            gate.await()
            clauderc.app.protocol.ProtocolJson.encodeToJsonElement(TermScreen.serializer(), oldScreen)
        }
        val store = androidx.lifecycle.ViewModelStore()
        val vm = pageIn(store, { TermViewModel("m-1", "s-work", relay, Fleet(relay, scope), scope, background = dispatcher) }, TermViewModel::class.java)
        relay.event(Method.TERM_SCREEN, "m-1", TermScreen.serializer(), newScreen)
        assertEquals(listOf("新画面"), vm.state.value.screen?.lines)

        gate.complete(Unit)
        assertEquals("stale rev=2 term.open must not overwrite rev=5 screen", listOf("新画面"), vm.state.value.screen?.lines)

        store.clear()
        assertEquals(clauderc.app.protocol.TermCloseRequest("s-work", "sub-7"), relay.sent(Method.TERM_CLOSE).single().decode(clauderc.app.protocol.TermCloseRequest.serializer()))
    }
}

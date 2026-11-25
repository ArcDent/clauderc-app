package clauderc.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.DraftStore
import clauderc.app.data.Fleet
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.ChatOpenResponse
import clauderc.app.protocol.ChatRewindResponse
import clauderc.app.protocol.ChatSendRequest
import clauderc.app.protocol.ChatSendResponse
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.Method
import clauderc.app.protocol.RewindRestore
import clauderc.app.protocol.SessionList
import clauderc.app.sessions.SessionsScreen
import clauderc.app.sessions.SessionsViewModel
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.decode
import clauderc.app.testing.eventually
import clauderc.app.testing.machine
import clauderc.app.testing.profileNames
import clauderc.app.testing.sampleSessions
import clauderc.app.ui.theme.ClaudercTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round 5 item 9: what was typed into a session and not sent is kept on the phone, shows on its row
 * and is back in the composer next time, until it is sent.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h1400dp")
class DraftTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val relay = FakeRelay().apply {
        respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "工作室 Mac mini"))))
        respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions)
        respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(model = "claude-opus-5-5", permissionMode = "default"))
        respond(Method.CHAT_SEND, ChatSendResponse.serializer(), ChatSendResponse())
    }
    private val appScope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val namesScope = CoroutineScope(Dispatchers.IO + Job())
    private var draftScope = CoroutineScope(Dispatchers.IO + Job())

    @After fun tearDown() {
        appScope.cancel()
        namesScope.cancel()
        draftScope.cancel()
    }

    private fun draftStore() = DraftStore(PreferenceDataStoreFactory.create(scope = draftScope, produceFile = { File(tmp.root, "drafts.preferences_pb") }))

    /** A page's view models live in their own store, cleared when the page goes, as the nav back stack does. */
    @Composable
    private fun <VM : ViewModel> pageViewModel(create: () -> VM): VM {
        val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
        DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
        return remember(owner) {
            @Suppress("UNCHECKED_CAST")
            ViewModelProvider(owner, object : ViewModelProvider.Factory { override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T })[ViewModel::class.java] as VM
        }
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun composer() = compose.onNodeWithTag("composer").fetchSemanticsNode().config
    private fun composerText() = composer()[SemanticsProperties.EditableText].text

    /** Waits in real time for DataStore reads and writes on their own threads. */
    private fun settle() {
        runBlocking { delay(DRAFT_SAVE_MILLIS + 300) }
        compose.waitForIdle()
    }

    @Test fun draftShowsOnTheRowComesBackAndGoesOnceSent() {
        val fleet = Fleet(relay, appScope)
        val names = profileNames(tmp.root, namesScope)
        var store by mutableStateOf(draftStore())
        // null shows the session page; a session id opens that session's chat.
        var page by mutableStateOf<String?>("s-idle")
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                val open = page
                key(open, store) {
                    if (open == null) {
                        val vm = pageViewModel { SessionsViewModel("m-1", fleet, relay, names, store) }
                        val state by vm.state.collectAsState()
                        SessionsScreen(state, {}, {}, {}, { _, _ -> }, {}, {})
                    } else {
                        val vm = pageViewModel { ChatViewModel("m-1", open, relay, fleet, appScope, compress = { it }, drafts = store) }
                        val state by vm.state.collectAsState()
                        ChatScreen(state, ChatActions(onSend = vm::send, onDraft = vm::typed))
                    }
                }
            }
        }

        // Type into A (周报整理) and go back at once: the row shows the draft.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("发消息")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("composer").performTextInput("改一下标题")
        compose.runOnUiThread { page = null }
        compose.waitUntil(5_000) { has("草稿：改一下标题") }
        assertTrue("the row's own second line gives way", !has("周报要按周一到周五拆开写吗"))

        // A new store on the same file, as after a restart: A's composer has the text, the cursor after it.
        runBlocking { eventually { draftScope.coroutineContext.job.cancelAndJoin() } }
        draftScope = CoroutineScope(Dispatchers.IO + Job())
        compose.runOnUiThread { store = draftStore(); page = "s-idle" }
        compose.waitUntil(5_000) { has("改一下标题") && runCatching { composerText() == "改一下标题" }.getOrDefault(false) }
        assertEquals(TextRange(5), composer()[SemanticsProperties.TextSelectionRange])

        // B (修复重连) has its own, empty composer.
        compose.runOnUiThread { page = "s-work" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("发消息")).fetchSemanticsNodes().isNotEmpty() }
        settle()
        assertEquals("", composerText())

        // A again: send it. Once the send succeeds the draft is gone, from the composer and the row.
        compose.runOnUiThread { page = "s-idle" }
        compose.waitUntil(5_000) { runCatching { composerText() == "改一下标题" }.getOrDefault(false) }
        compose.onNode(hasContentDescription("发送")).performClick()
        compose.waitUntil(5_000) { relay.sent(Method.CHAT_SEND).isNotEmpty() }
        assertEquals("改一下标题", relay.sent(Method.CHAT_SEND).single().decode(ChatSendRequest.serializer()).text)
        runBlocking { eventually { store.draft("m-1", "s-idle").first { it.isEmpty() } } }
        compose.runOnUiThread { page = null }
        compose.waitUntil(5_000) { has("周报要按周一到周五拆开写吗") }
        assertTrue(!has("草稿："))
        compose.runOnUiThread { page = "s-idle" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("发消息")).fetchSemanticsNodes().isNotEmpty() }
        settle()
        assertEquals("", composerText())
    }

    /**
     * A draft brought back into the composer and sent is used up: back from the terminal, with the chat page
     * kept under it as on the nav back stack, the composer is empty and the session's row shows no draft.
     */
    @Test fun aSentDraftDoesNotComeBackAfterTheTerminal() {
        val fleet = Fleet(relay, appScope)
        val names = profileNames(tmp.root, namesScope)
        val store = draftStore()
        runBlocking { store.set("m-1", "s-idle", "改一下标题") }
        // null shows the session page; "chat" the chat; "term" the terminal over it.
        var page by mutableStateOf<String?>("chat")
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                if (page == null) {
                    val vm = pageViewModel { SessionsViewModel("m-1", fleet, relay, names, store) }
                    val state by vm.state.collectAsState()
                    SessionsScreen(state, {}, {}, {}, { _, _ -> }, {}, {})
                } else {
                    // Under the terminal the chat page keeps its view model and its saved state.
                    val vm = pageViewModel { ChatViewModel("m-1", "s-idle", relay, fleet, appScope, compress = { it }, drafts = store) }
                    val saved = rememberSaveableStateHolder()
                    if (page == "chat") saved.SaveableStateProvider("chat") {
                        val state by vm.state.collectAsState()
                        ChatScreen(state, ChatActions(onSend = vm::send, onDraft = vm::typed, onRestored = vm::restored))
                    }
                }
            }
        }
        compose.waitUntil(5_000) { runCatching { composerText() == "改一下标题" }.getOrDefault(false) }
        compose.onNode(hasContentDescription("发送")).performClick()
        compose.waitUntil(5_000) { relay.sent(Method.CHAT_SEND).isNotEmpty() }
        runBlocking { eventually { store.draft("m-1", "s-idle").first { it.isEmpty() } } }

        compose.runOnUiThread { page = "term" }
        compose.waitForIdle()
        compose.runOnUiThread { page = "chat" }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("composer").fetchSemanticsNodes().isNotEmpty() }
        settle()
        assertEquals("", composerText())

        compose.runOnUiThread { page = null }
        compose.waitUntil(5_000) { has("周报要按周一到周五拆开写吗") }
        assertTrue("the row shows no draft", !has("草稿："))
        assertEquals("", runBlocking { store.draft("m-1", "s-idle").first() })
    }

    /** A rewound message put back into the composer and then changed keeps the change through a rebuilt activity. */
    @Test fun aRewoundMessageChangedOutlivesARebuiltActivity() {
        val message = ChatItem(1, ItemKind.USER, text = "把退避上限改成 60 秒", uuid = "u-1")
        relay.respond(
            Method.CHAT_OPEN, ChatOpenResponse.serializer(),
            ChatOpenResponse(items = listOf(message, ChatItem(2, ItemKind.ASSISTANT, text = "改好了。")), model = "claude-opus-5-5", permissionMode = "default"),
        )
        relay.respond(Method.CHAT_REWIND, ChatRewindResponse.serializer(), ChatRewindResponse())
        val store = draftStore()
        // The view model outlives the activity, as a real one does.
        val vm = ChatViewModel("m-1", "s-idle", relay, Fleet(relay, appScope), appScope, compress = { it }, drafts = store)
        val drafts = mutableListOf<String>()
        val activity = StateRestorationTester(compose)
        activity.setContent {
            ClaudercTheme(darkTheme = false) {
                val state by vm.state.collectAsState()
                ChatScreen(state, ChatActions(onDraft = { drafts += it; vm.typed(it) }, onRestored = vm::restored))
            }
        }
        compose.waitUntil(5_000) { has("改好了。") }
        compose.runOnUiThread { vm.rewind(message, RewindRestore.CONVERSATION) }
        compose.waitUntil(5_000) { runCatching { composerText() == "把退避上限改成 60 秒" }.getOrDefault(false) }
        compose.onNodeWithTag("composer").performTextReplacement("把退避上限改成 30 秒")
        settle()

        activity.emulateSavedInstanceStateRestore()
        settle()
        assertEquals("把退避上限改成 30 秒", composerText())
        assertEquals("the draft is the changed text", "把退避上限改成 30 秒", drafts.last())
        vm.viewModelScope.cancel()
    }
}

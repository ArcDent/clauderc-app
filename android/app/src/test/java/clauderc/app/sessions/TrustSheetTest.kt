package clauderc.app.sessions

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.Fleet
import clauderc.app.net.RemoteException
import clauderc.app.protocol.DirsRecentResponse
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.SessionCreateRequest
import clauderc.app.protocol.SessionCreateResponse
import clauderc.app.protocol.SessionList
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.decode
import clauderc.app.testing.machine
import clauderc.app.testing.profileNames
import clauderc.app.testing.sampleSessions
import clauderc.app.ui.theme.ClaudercTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * Round 5 item 11: session.create answers notTrusted, the sheet names the directory, and
 * 「信任并新建」 sends session.create again with trust set, then opens the new session as usual.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h1400dp")
class TrustSheetTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val dir = "/Users/me/github/new-repo"
    private val trusted = CompletableDeferred<Unit>()
    private val relay = FakeRelay().apply {
        respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "工作室 Mac mini"))))
        respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions)
        respond(Method.DIRS_RECENT, DirsRecentResponse.serializer(), DirsRecentResponse(listOf(dir, "/Users/me/github/infra")))
        on(Method.SESSION_CREATE) { s ->
            if (s.decode(SessionCreateRequest.serializer()).trust != true) throw RemoteException(ErrorCode.NOT_TRUSTED, "Claude Code 还没信任这个目录")
            trusted.await()
            ProtocolJson.encodeToJsonElement(SessionCreateResponse.serializer(), SessionCreateResponse("n1"))
        }
    }
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())

    @After fun tearDown() {
        scope.cancel()
        storeScope.cancel()
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun creates() = relay.sent(Method.SESSION_CREATE).map { it.decode(SessionCreateRequest.serializer()) }

    @Test fun untrustedDirectoryAsksThenTrustsAndCreatesInOneStep() {
        val vm = CreateSessionViewModel("m-1", "default", Fleet(relay, scope), relay, profileNames(tmp.root, storeScope))
        val opened = CopyOnWriteArrayList<String>()
        scope.launch { vm.created.collect { opened += it } }
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                val state by vm.state.collectAsState()
                CreateSessionScreen(
                    state, {}, vm::submit, vm::setDir, vm::pickDir, vm::showAllDirs, vm::setName, vm::setPrompt,
                    onTrust = vm::trustAndCreate, onDismissTrust = vm::dismissTrust,
                )
            }
        }
        compose.waitUntil(5_000) { has("new-repo") }
        assertEquals(1, compose.onAllNodes(hasText("~/github/new-repo")).fetchSemanticsNodes().size)
        compose.onNodeWithText("new-repo").performClick()

        // The agent answers notTrusted: the sheet names the directory.
        compose.onNode(hasText("启动") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { has("这个目录还没被信任") }
        compose.onNodeWithText("Claude Code 信任目录后才能在里面启动会话，之后它能读写和运行这里的文件。").assertIsDisplayed()
        assertEquals("the sheet names it beside the list's own row", 2, compose.onAllNodes(hasText("~/github/new-repo")).fetchSemanticsNodes().size)
        compose.onNodeWithText("信任并新建").assertIsDisplayed()
        assertEquals(listOf(SessionCreateRequest("default", dir)), creates())

        // 「取消」 closes it and sends nothing more.
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(5_000) { !has("这个目录还没被信任") }
        runBlocking { delay(300) }
        compose.waitForIdle()
        assertEquals(1, creates().size)
        assertTrue(opened.isEmpty())

        // Again, and this time 「信任并新建」: session.create with trust, working until it answers.
        compose.onNode(hasText("启动") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { has("信任并新建") }
        compose.onNodeWithText("信任并新建").performClick()
        compose.waitUntil(5_000) { has("正在信任") }
        assertEquals(SessionCreateRequest("default", dir, trust = true), creates().last())
        assertTrue("not opened before it answers", opened.isEmpty())

        trusted.complete(Unit)
        compose.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals("the new session opens as after any create", listOf("n1"), opened.toList())
        compose.waitUntil(5_000) { !has("这个目录还没被信任") }
        assertEquals(3, creates().size)
    }
}

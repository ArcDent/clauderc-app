package clauderc.app.sessions

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.Fleet
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.SessionList
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.branchSessions
import clauderc.app.testing.eventually
import clauderc.app.testing.machine
import clauderc.app.testing.profileNames
import clauderc.app.ui.theme.ClaudercTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
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
 * Round 5 item 7: a worktree session sits under its original directory with its mark, a fork hangs
 * right under its source, and folding the source's forks survives an app restart.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h1400dp")
class ForkTreeTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val relay = FakeRelay().apply {
        respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "工作室 Mac mini"))))
        respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), branchSessions)
    }
    private val fleetScope = CoroutineScope(Dispatchers.Unconfined + Job())
    private var storeScope = CoroutineScope(Dispatchers.IO + Job())

    @After fun tearDown() {
        fleetScope.cancel()
        storeScope.cancel()
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun bounds(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun forkHangsUnderItsSourceAndItsFoldSurvivesRestart() {
        val fleet = Fleet(relay, fleetScope)
        var vm by mutableStateOf(SessionsViewModel("m-1", fleet, relay, profileNames(tmp.root, storeScope)))
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                val current = vm
                val state by current.state.collectAsState()
                SessionsScreen(state, {}, {}, {}, {}, { _, _ -> }, {}, {}, onToggleForks = current::toggleForks)
            }
        }
        compose.waitUntil(5_000) { has("clauderc 协议 ⑂ 补错误码测试") }

        // The worktree session is in clauderc-app, not under its worktree path, and carries the mark and its branch.
        assertTrue(!has("tools-view/") && !has(".claude/worktrees"))
        compose.onNode(hasContentDescription("worktree"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("worktree-tools-view", substring = true).assertIsDisplayed()

        // The fork comes right after its source, ahead of the worktree session, and further in.
        val source = bounds("clauderc 协议")
        val fork = bounds("clauderc 协议 ⑂ 补错误码测试")
        val next = bounds("工具视图重写")
        assertTrue("under its source", fork.top > source.bottom && fork.bottom < next.top)
        assertEquals("22dp further in", 22f, fork.left - source.left, 0.5f)
        // Titles are Claude Code's own names; the fork whose source is gone stands alone.
        assertTrue(!has("(fork)"))
        compose.onNodeWithText("排查日志 ⑂ 只看 relay 部分").assertIsDisplayed()
        assertEquals("只有来源有折叠按钮", 1, compose.onAllNodes(hasContentDescription("个 fork", substring = true)).fetchSemanticsNodes().size)

        compose.onNodeWithContentDescription("收起 1 个 fork").performClick()
        compose.waitUntil(5_000) { !has("补错误码测试") }
        compose.onNodeWithText("工具视图重写").assertIsDisplayed()

        // Restart: a new store on the same file, read by a new view model.
        runBlocking { eventually { storeScope.coroutineContext.job.cancelAndJoin() } }
        storeScope = CoroutineScope(Dispatchers.IO + Job())
        compose.runOnUiThread { vm = SessionsViewModel("m-1", fleet, relay, profileNames(tmp.root, storeScope)) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("展开 1 个 fork")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("still folded after the restart", !has("补错误码测试"))

        compose.onNodeWithContentDescription("展开 1 个 fork").performClick()
        compose.waitUntil(5_000) { has("补错误码测试") }
        compose.onNodeWithText("clauderc 协议 ⑂ 补错误码测试").assertIsDisplayed()
    }
}

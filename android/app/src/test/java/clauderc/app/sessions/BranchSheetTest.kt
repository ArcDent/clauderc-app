package clauderc.app.sessions

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.Fleet
import clauderc.app.protocol.DirBusyResponse
import clauderc.app.protocol.DirRequest
import clauderc.app.protocol.GitBranchesResponse
import clauderc.app.protocol.GitSwitchRequest
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.SessionList
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.branchSessions
import clauderc.app.testing.decode
import clauderc.app.testing.machine
import clauderc.app.testing.profileNames
import clauderc.app.ui.components.SnackState
import clauderc.app.ui.theme.ClaudercTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Round 5 item 8: the directory title shows its branch, the sheet narrows the rows to a branch and
 * switches with git.switch, asking first while a session works in the directory.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h1400dp")
class BranchSheetTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val app = "/Users/me/github/clauderc-app"
    private val relaySvc = "/Users/me/github/relay-svc"
    private val current = mutableMapOf(app to "dev", relaySvc to "fix-reconnect", "/Users/me/github/infra" to "main")
    private val local = mapOf(app to listOf("dev", "main", "feat-tools", "worktree-tools-view"), relaySvc to listOf("fix-reconnect", "main"), "/Users/me/github/infra" to listOf("main"))

    private val relay = FakeRelay().apply {
        respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "工作室 Mac mini"))))
        respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), branchSessions)
        on(Method.GIT_BRANCHES) { s ->
            val d = s.decode(DirRequest.serializer()).dir
            ProtocolJson.encodeToJsonElement(GitBranchesResponse.serializer(), GitBranchesResponse(current.getValue(d), local.getValue(d)))
        }
        // 修复重连 works in relay-svc.
        on(Method.DIR_BUSY) { s -> ProtocolJson.encodeToJsonElement(DirBusyResponse.serializer(), DirBusyResponse(s.decode(DirRequest.serializer()).dir == relaySvc)) }
        on(Method.GIT_SWITCH) { s ->
            val r = s.decode(GitSwitchRequest.serializer())
            current[r.dir] = r.branch
            JsonObject(emptyMap())
        }
    }
    private val fleetScope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())

    @After fun tearDown() {
        fleetScope.cancel()
        storeScope.cancel()
    }

    private fun has(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun has(text: String) = has(hasText(text, substring = true))

    /** A directory title's branch button. */
    private fun branchButton(name: String) = hasText(name) and hasClickAction()

    private fun show() {
        val vm = SessionsViewModel("m-1", Fleet(relay, fleetScope), relay, profileNames(tmp.root, storeScope))
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                val state by vm.state.collectAsState()
                SessionsScreen(
                    state, {}, {}, {}, {}, { _, _ -> }, {}, {},
                    snack = remember { SnackState() },
                    onBranches = vm::openBranches,
                    onFilterBranch = vm::filterBranch,
                    onSwitchBranch = vm::switchBranch,
                    onConfirmSwitch = vm::confirmSwitch,
                    onDismissSwitch = vm::dismissSwitch,
                )
            }
        }
        compose.waitUntil(5_000) { has("工具视图重写") && has(branchButton("dev")) && has(branchButton("fix-reconnect")) }
    }

    @Test fun titleShowsTheBranchAndTheSheetNarrowsTheRows() {
        show()
        // clauderc-app's own sessions are all on dev, so its rows name no branch; relay-svc's are on two.
        assertTrue(!has("dev · "))
        assertTrue(has("fix-reconnect · 工作中") && has("main · 待确认"))

        compose.onNode(branchButton("dev")).performClick()
        compose.onNodeWithText("clauderc-app 的分支").assertIsDisplayed()
        compose.onNodeWithText("点分支只看它上面的会话").assertIsDisplayed()
        compose.onNode(hasText("全部分支") and hasText("4 个会话")).assertIsDisplayed()
        compose.onNode(hasText("当前分支 · 3 个会话")).assertIsDisplayed()
        compose.onNode(hasText("在 worktree tools-view 里 · 1 个会话")).assertIsDisplayed()
        // The current branch and the one the worktree holds cannot be switched to.
        assertEquals(2, compose.onAllNodes(hasText("切换")).fetchSemanticsNodes().size)
        assertTrue(has(hasContentDescription("切换到 main")) && has(hasContentDescription("切换到 feat-tools")))

        compose.onNode(hasText("当前分支 · 3 个会话")).performClick()
        compose.waitUntil(5_000) { !has("工具视图重写") }
        compose.onNodeWithText("README 截图").assertIsDisplayed()
        compose.onNodeWithText("clauderc 协议 ⑂ 补错误码测试").assertIsDisplayed()
        compose.onNode(hasText("clauderc-app") and hasText("3 个 · 1 个等你")).assertIsDisplayed()

        compose.onNode(branchButton("dev")).performClick()
        compose.onNode(hasText("全部分支") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { has("工具视图重写") }
        compose.onNode(hasText("clauderc-app") and hasText("4 个 · 1 个等你")).assertIsDisplayed()
    }

    @Test fun switchingAsksFirstWhileASessionWorksThere() {
        show()
        compose.onNode(branchButton("fix-reconnect")).performClick()
        compose.onNode(hasContentDescription("切换到 main")).performClick()
        compose.waitUntil(5_000) { has("claude 正在这个目录工作") }
        compose.onNodeWithText("「修复重连」正在改 relay-svc 里的代码，现在切换可能带上它写到一半的改动。").assertIsDisplayed()
        assertTrue("not switched before the answer", relay.sent(Method.GIT_SWITCH).isEmpty())

        compose.onNodeWithText("仍然切换").performClick()
        compose.waitUntil(5_000) { has(branchButton("main")) }
        assertEquals(listOf(GitSwitchRequest(relaySvc, "main")), relay.sent(Method.GIT_SWITCH).map { it.decode(GitSwitchRequest.serializer()) })

        // Nothing works in clauderc-app: 「切换」 switches at once.
        compose.onNode(branchButton("dev")).performClick()
        compose.onNode(hasContentDescription("切换到 feat-tools")).performClick()
        compose.waitUntil(5_000) { relay.sent(Method.GIT_SWITCH).size == 2 }
        assertTrue(!has("claude 正在这个目录工作"))
        assertEquals(GitSwitchRequest(app, "feat-tools"), relay.sent(Method.GIT_SWITCH).last().decode(GitSwitchRequest.serializer()))
        compose.waitUntil(5_000) { has(branchButton("feat-tools")) }
    }
}

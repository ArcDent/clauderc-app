package clauderc.app.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.protocol.AutoResume
import clauderc.app.protocol.Limit
import clauderc.app.protocol.SessionState
import clauderc.app.testing.session
import clauderc.app.ui.theme.ClaudercTheme
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Round 6 items 4 and 5 on the chat page: the git icon's menu with Fork and Worktree, and the usage limit row. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
class LimitForkScreenTest {
    @get:Rule val compose = createComposeRule()

    private val base = ChatUiState(
        session = session("s-need", "clauderc 协议", SessionState.Idle, "default", cwd = "/Users/me/github/clauderc-app"),
        loading = false,
        model = "claude-opus-5-5",
        permissionMode = "default",
    )

    private fun show(state: ChatUiState, actions: ChatActions = ChatActions()) {
        compose.setContent { ClaudercTheme(darkTheme = false, style = clauderc.app.data.UiStyle.Clear) { ChatScreen(state, actions) } }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription("git").performClick()
        compose.waitForIdle()
    }

    @Test fun theGitIconOpensForkWorktreeAndGit() {
        val opened = mutableListOf<String>()
        show(base, ChatActions(onOpenGit = { opened += it }))
        openMenu()
        compose.onNodeWithText("Fork").assertIsDisplayed()
        compose.onNodeWithText("Worktree").assertIsDisplayed()
        compose.onNodeWithText("看 git").performClick()
        assertEquals(listOf("/Users/me/github/clauderc-app"), opened)
        compose.onNodeWithTag("popMenu").assertDoesNotExist()
    }

    @Test fun forkSendsTheInstructionAndStaysHere() {
        val forks = mutableListOf<String>()
        show(base, ChatActions(onFork = { forks += it; true }))
        openMenu()
        compose.onNodeWithText("Fork").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fork 这个会话").assertIsDisplayed()
        compose.onNodeWithTag("forkInstruction").performTextInput("试试另一种写法")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Fork").performClick()
        compose.onNodeWithText("Fork 这个会话").assertIsDisplayed()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf("试试另一种写法"), forks)
        compose.onNodeWithText("Fork 这个会话").assertDoesNotExist()
        compose.onNode(hasText("已发出 /fork", substring = true)).assertIsDisplayed()
    }

    @Test fun worktreeComesWithANameAndRefusesABadOne() {
        val created = mutableListOf<Pair<String, String>>()
        show(base, ChatActions(onCreateWorktree = { n, p -> created += n to p }))
        openMenu()
        compose.onNodeWithText("Worktree").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("在新 worktree 里开会话").assertIsDisplayed()
        val name = compose.onNodeWithTag("worktreeName").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertTrue(name, Regex("wt-\\d{4}-\\d{4}").matches(name))
        compose.onNodeWithTag("worktreeName").performTextReplacement("a b")
        compose.onNodeWithText("新建").performClick()
        assertTrue(created.isEmpty())
        compose.onNodeWithText(WORKTREE_NAME_RULE).assertIsDisplayed()
        compose.onNodeWithTag("worktreeName").performTextReplacement("probe-wt")
        compose.onNodeWithTag("worktreePrompt").performTextInput("先跑测试")
        compose.onNodeWithText("新建").performClick()
        assertEquals(listOf("probe-wt" to "先跑测试"), created)
    }

    @Test fun theDialogClosesOnceTheSessionIsCreated() {
        var state by mutableStateOf(base)
        var created by mutableStateOf<String?>(null)
        val opened = mutableListOf<String>()
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = clauderc.app.data.UiStyle.Clear) {
                ChatScreen(state, ChatActions(), createdWorktree = created, onWorktreeClosed = { opened += it })
            }
        }
        openMenu()
        compose.onNodeWithText("Worktree").performClick()
        compose.waitForIdle()
        state = state.copy(creatingWorktree = true)
        compose.onNodeWithText("正在新建").assertIsDisplayed()
        compose.onNodeWithTag("clearScrim").assertIsNotEnabled()
        state = state.copy(creatingWorktree = false, worktreeError = "fatal: a branch named 'worktree-x' already exists")
        compose.onNodeWithText("fatal: a branch named 'worktree-x' already exists").assertIsDisplayed()
        state = state.copy(creatingWorktree = true, worktreeError = null)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            state = state.copy(creatingWorktree = false)
            created = "new-worktree-session"
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("在新 worktree 里开会话").assertIsDisplayed()
        assertTrue(opened.isEmpty())
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("在新 worktree 里开会话").assertDoesNotExist()
        assertEquals(listOf("new-worktree-session"), opened)
    }

    @Test fun offlineTheMenuCannotOpen() {
        show(base.copy(online = false, offlineText = "已离线"))
        compose.onNodeWithContentDescription("git").assertIsNotEnabled()
    }

    @Test fun theLimitRowSaysWhenAndItsSwitchSendsTheText() {
        val resets = Instant.now().plus(1, ChronoUnit.HOURS)
        val limit = Limit("fiveHour", "You've hit your 5-hour limit", resets)
        val switched = mutableListOf<Boolean>()
        val typed = mutableListOf<String>()
        var state by mutableStateOf(base.copy(limit = limit))
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, ChatActions(onAutoResume = { switched += it }, onResumeText = { typed += it })) } }
        compose.onNodeWithTag("limitRow").assertIsDisplayed()
        compose.onNode(hasText("5 小时限额，", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag("limitInput").assert(hasText("继续"))
        compose.onNodeWithTag("limitSwitch").assertIsOff().performClick()
        assertEquals(listOf(true), switched)
        compose.onNodeWithTag("limitInput").performTextReplacement("接着改")
        assertEquals(listOf("接着改"), typed)
        // The agent's setting: on, and when it goes out.
        state = state.copy(limit = limit.copy(autoResume = AutoResume("继续", resets.plusSeconds(60))))
        compose.onNodeWithTag("limitSwitch").assertIsOn()
        compose.onNode(hasText("自动发出", substring = true)).assertIsDisplayed()
        // Nothing typed and off: the switch has nothing to send.
        state = state.copy(limit = limit, resumeText = "")
        compose.onNodeWithTag("limitSwitch").assertIsNotEnabled()
        // The limit lifted: the row goes.
        state = state.copy(limit = null)
        compose.waitForIdle()
        compose.onNodeWithTag("limitRow").assertDoesNotExist()
    }
}

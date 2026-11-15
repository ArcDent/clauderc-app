package clauderc.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.chat.ChatActions
import clauderc.app.chat.ChatScreen
import clauderc.app.chat.ChatUiState
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.Pending
import clauderc.app.protocol.PendingKind
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.ToolCall
import clauderc.app.protocol.ToolResult
import clauderc.app.sessions.SessionsScreen
import clauderc.app.sessions.SessionsUiState
import clauderc.app.sessions.groupSessions
import clauderc.app.testing.machine
import clauderc.app.testing.sampleProfiles
import clauderc.app.testing.session
import clauderc.app.ui.theme.ClaudercMotion
import clauderc.app.ui.theme.ClaudercTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The animations picked in the demo on 2026-11-15: a finished tool, a new reply, a state change and the light tick. */
@RunWith(AndroidJUnit4::class)
class StateMotionTest {
    @get:Rule val compose = createComposeRule()

    private val durS = ClaudercMotion().durS.toLong()
    private val durM = ClaudercMotion().durM.toLong()

    /** With the clock frozen, lets the state written by the test reach the screen, then runs [millis]. */
    private fun run(millis: Long) = with(compose.mainClock) {
        Snapshot.sendApplyNotifications()
        advanceTimeByFrame()
        advanceTimeBy(millis)
    }

    @Test fun aFinishedToolsSpinnerFadesOutAndGivesItsRoomBack() {
        val running = ChatItem(1, ItemKind.TOOL, tool = ToolCall("toolu_1", "Bash", "运行 go vet ./..."))
        var state by mutableStateOf(ChatUiState(loading = false, state = SessionState.Working, items = listOf(running)))
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, ChatActions()) } }
        compose.waitForIdle()
        fun width() = compose.onNodeWithTag("toolSpinner", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width
        val full = width()
        compose.mainClock.autoAdvance = false

        compose.runOnUiThread { state = state.copy(items = listOf(running.copy(tool = running.tool!!.copy(result = ToolResult("没有输出"))))) }
        run(durS / 2)
        val mid = width()
        assertTrue("spinner room halfway out $mid of $full", mid > 0f && mid < full)
        compose.mainClock.advanceTimeBy(durS)
        compose.onNodeWithTag("toolSpinner", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun aNewReplyGrowsFromNothingButTheOnesAlreadyThereDoNot() {
        val shown = ChatItem(1, ItemKind.ASSISTANT, text = "已经在的回复")
        var state by mutableStateOf(ChatUiState(loading = false, items = listOf(shown)))
        compose.mainClock.autoAdvance = false
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, ChatActions()) } }
        compose.mainClock.advanceTimeByFrame()
        fun height(text: String) = compose.onNodeWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        val first = height("已经在的回复")
        compose.mainClock.advanceTimeBy(durM)
        assertEquals("a reply that was there when the page opened shows at once", height("已经在的回复"), first)

        compose.runOnUiThread { state = state.copy(items = listOf(shown, ChatItem(2, ItemKind.ASSISTANT, text = "好，我先读一下现有的测试。\n然后补上缺的几条。"))) }
        // The emphasized curve covers most of the way early, so the grow is caught a quarter in, as in MotionTest.
        run(durM / 4)
        val mid = height("我先读一下")
        compose.mainClock.advanceTimeBy(durM)
        val end = height("我先读一下")
        assertTrue("new reply halfway at $mid, grown to $end", mid > 0f && mid < end)
    }

    @Test fun aSessionsSymbolCrossfadesWhenItsStateChanges() {
        var list by mutableStateOf(SessionList(listOf(session("s1", "修复重连", SessionState.Working, "default")), sampleProfiles))
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                SessionsScreen(SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(list, emptyMap())), {}, {}, {}, {}, { _, _ -> }, {}, {})
            }
        }
        compose.waitForIdle()
        fun symbols(label: String) = compose.onAllNodes(hasContentDescription(label), useUnmergedTree = true).fetchSemanticsNodes().size
        compose.mainClock.autoAdvance = false

        compose.runOnUiThread { list = SessionList(listOf(session("s1", "修复重连", SessionState.Idle, "default")), sampleProfiles) }
        run(durS / 2)
        assertTrue("both symbols halfway", symbols("工作中") == 1 && symbols("空闲") == 1)
        compose.mainClock.advanceTimeBy(durM)
        assertTrue("only the new symbol at the end", symbols("工作中") == 0 && symbols("空闲") == 1)
    }

    @Test fun sendAllowAndDenyEachTickOnce() {
        val ticks = mutableListOf<HapticFeedbackType>()
        val haptic = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                ticks += hapticFeedbackType
            }
        }
        var state by mutableStateOf(ChatUiState(loading = false))
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                CompositionLocalProvider(LocalHapticFeedback provides haptic) { ChatScreen(state, ChatActions(onSend = { true })) }
            }
        }
        compose.onNodeWithTag("composer").performTextInput("跑一遍测试")
        compose.onNodeWithContentDescription("发送").performClick()
        compose.runOnUiThread { state = state.copy(pending = Pending("toolu_1", PendingKind.PERMISSION, "Bash")) }
        compose.onNodeWithText("允许").performClick()
        compose.onNodeWithText("拒绝").performClick()
        assertEquals(List(3) { HapticFeedbackType.VirtualKey }, ticks)
    }
}

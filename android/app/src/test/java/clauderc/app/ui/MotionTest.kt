package clauderc.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.ClaudercNavHost
import clauderc.app.chat.ChatActions
import clauderc.app.chat.ChatScreen
import clauderc.app.chat.ChatUiState
import clauderc.app.protocol.Command
import clauderc.app.protocol.Pending
import clauderc.app.protocol.PendingKind
import clauderc.app.protocol.SessionState
import clauderc.app.ui.theme.ClaudercMotion
import clauderc.app.ui.theme.ClaudercTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@Serializable internal data object ListPage
@Serializable internal data object ChatPage

/** Motion rules with a frozen clock: pages slide off to the right on back, small parts grow in instead of popping. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
class MotionTest {
    @get:Rule val compose = createComposeRule()
    private val enter = ClaudercMotion().durM.toLong()

    @Test fun backSlidesTheChatOffToTheRight() {
        lateinit var nav: NavHostController
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                nav = rememberNavController()
                ClaudercNavHost(nav, startDestination = ListPage) {
                    composable<ListPage> { Box(Modifier.fillMaxSize().testTag("list")) }
                    composable<ChatPage> { Box(Modifier.fillMaxSize().testTag("chat")) }
                }
            }
        }
        compose.runOnUiThread { nav.navigate(ChatPage) }
        compose.waitForIdle()
        val width = compose.onNodeWithTag("chat").fetchSemanticsNode().size.width

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { nav.popBackStack() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(enter / 2)
        val mid = compose.onNodeWithTag("chat", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        assertTrue("halfway the chat is partly off to the right: x=$mid of $width", mid > 0 && mid < width)
        val listMid = compose.onNodeWithTag("list", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        assertTrue("the list comes back from the left: x=$listMid", listMid < 0)

        compose.mainClock.advanceTimeBy(enter)
        compose.onNodeWithTag("chat").assertDoesNotExist()
        assertTrue(compose.onNodeWithTag("list").fetchSemanticsNode().positionInRoot.x == 0f)
    }

    private val base = ChatUiState(
        loading = false,
        commands = listOf(Command("model", "切换模型"), Command("compact", "压缩对话上下文")),
    )

    /** Height of the visible part of [tag]: during the grow-in it is clipped by its container. */
    private fun visibleHeight(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height

    /**
     * Starts the change and stops [fraction] of the enter time in. With the clock frozen, state written
     * by the test reaches the composition only after an explicit notice. The emphasized curve covers
     * most of the way early, so short parts are measured a quarter in.
     */
    private fun partWay(fraction: Double = 0.25): Unit = with(compose.mainClock) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        advanceTimeByFrame()
        advanceTimeBy((enter * fraction).toLong())
    }

    @Test fun slashPopupGrowsInRatherThanPopping() {
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(base, ChatActions()) } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("composer").performTextInput("/")
        partWay(0.5)
        val mid = visibleHeight("slashPopup")
        compose.mainClock.advanceTimeBy(enter)
        val end = visibleHeight("slashPopup")
        assertTrue("halfway $mid, settled $end", mid > 0f && mid < end)
    }

    @Test fun workingRowOfflineLineAndPanelAnimateIn() {
        var state by mutableStateOf(base)
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, ChatActions()) } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        compose.runOnUiThread { state = base.copy(state = SessionState.Working, workingSinceMillis = 0) }
        partWay()
        compose.onNodeWithText("中断").assertExists()
        val workMid = compose.onNodeWithText("中断", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.advanceTimeBy(enter)
        val workEnd = compose.onNodeWithText("中断", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        assertTrue("working row a quarter in $workMid, settled $workEnd", workMid < workEnd)

        compose.runOnUiThread { state = state.copy(online = false, offlineText = "已离线 · 最后在线 14:36") }
        partWay()
        val offMid = visibleHeight("offlineLine")
        compose.mainClock.advanceTimeBy(enter)
        val offEnd = visibleHeight("offlineLine")
        assertTrue("offline line a quarter in $offMid, settled $offEnd", offMid > 0f && offMid < offEnd)

        compose.runOnUiThread { state = base.copy(pending = Pending("toolu_1", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "go vet ./...") })) }
        partWay()
        val panelMid = compose.onAllNodesWithTag("pendingPanel", useUnmergedTree = true).onFirst().fetchSemanticsNode().positionInRoot.y
        compose.mainClock.advanceTimeBy(enter)
        val panelEnd = compose.onAllNodesWithTag("pendingPanel", useUnmergedTree = true).onFirst().fetchSemanticsNode().positionInRoot.y
        assertTrue("panel a quarter in at y=$panelMid, settled at y=$panelEnd", panelMid > panelEnd)
    }
}

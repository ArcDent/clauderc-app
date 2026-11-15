package clauderc.app.ui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.ClaudercNavHost
import clauderc.app.chat.ChatActions
import clauderc.app.chat.ChatScreen
import clauderc.app.chat.ChatUiState
import clauderc.app.git.DiffScreen
import clauderc.app.git.GitActions
import clauderc.app.git.GitScreen
import clauderc.app.git.GitUiState
import clauderc.app.git.Loadable
import clauderc.app.protocol.Command
import clauderc.app.protocol.Commit
import clauderc.app.protocol.Edge
import clauderc.app.protocol.GitDiffResponse
import clauderc.app.protocol.GitStatusResponse
import clauderc.app.protocol.Pending
import clauderc.app.protocol.PendingKind
import clauderc.app.protocol.SessionState
import clauderc.app.sessions.CreateSessionScreen
import clauderc.app.sessions.CreateUiState
import clauderc.app.testing.T0
import clauderc.app.ui.theme.ClaudercMotion
import clauderc.app.ui.theme.ClaudercTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    @Test fun backGestureFollowsTheFingerAndDeceleratesAfterRelease() {
        lateinit var nav: NavHostController
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
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
        val width = compose.onNodeWithTag("chat").fetchSemanticsNode().size.width.toFloat()
        fun chatX() = compose.onNodeWithTag("chat", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        fun dragTo(progress: Float) {
            compose.runOnUiThread { back.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)) }
            compose.runOnUiThread { back.dispatchOnBackProgressed(BackEventCompat(progress * width, 400f, progress, BackEventCompat.EDGE_LEFT)) }
            compose.waitForIdle()
        }

        // While the finger is down the page keeps pace with it; a cancel brings it back.
        dragTo(0.4f)
        assertEquals("the chat follows the finger", 0.4f * width, chatX(), 1f)
        compose.runOnUiThread { back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(0f, chatX(), 0f)
        compose.onNodeWithTag("list").assertDoesNotExist()

        // After the release the rest of the way (60% of durM) decelerates: a quarter of that time in, the
        // emphasized curve has covered about 69% of the remaining distance, a linear finish only 25%.
        dragTo(0.4f)
        val rest = (enter * 0.6).toLong()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { back.onBackPressed() }
        // NavHost takes two frames to start the finish: one pops the entry, the next starts the animation at play time 0.
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("the finish has not moved yet", 0.4f * width, chatX(), 1f)
        // Frames come every 16ms, so the first one at or after a quarter (42ms) is 48ms in.
        compose.mainClock.advanceTimeBy(rest / 4)
        val x = chatX()
        assertTrue("a quarter into the finish the chat is past 75%: x=$x of $width (linear ≈ 57%)", x > 0.75f * width)

        compose.mainClock.advanceTimeBy(rest)
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

    @Test fun allDirsGrowIn() {
        val dirs = (1..14).map { "/Users/me/github/p$it" }
        var state by mutableStateOf(CreateUiState(profileTitle = "官方", dir = dirs[0], recent = dirs))
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                CreateSessionScreen(
                    state, onClose = {}, onSubmit = {}, onDir = {}, onPickDir = {},
                    onShowAllDirs = { state = state.copy(showAllDirs = true) }, onName = {}, onPrompt = {},
                )
            }
        }
        compose.waitForIdle()
        // The list runs past the screen bottom, so measure its laid-out height rather than its clipped bounds.
        fun height() = compose.onNodeWithTag("dirs", useUnmergedTree = true).fetchSemanticsNode().size.height
        val before = height()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("全部 14 个").performClick()
        partWay(0.5)
        val mid = height()
        compose.mainClock.advanceTimeBy(enter)
        val end = height()
        assertTrue("before $before, halfway $mid, settled $end", before < mid && mid < end)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertCountEquals(14)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun expandedDiffFadesIn() {
        val patch = "@@ -0,0 +1,1200 @@\n" + (1..1200).joinToString("\n") { "+line $it" }
        val diff = Loadable(GitDiffResponse("big.txt", patch = patch, collapsed = true))
        compose.setContent { ClaudercTheme(darkTheme = false) { DiffScreen("big.txt", "未暂存", diff, onBack = {}) } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        fun shot() = compose.onRoot().captureToImage().asAndroidBitmap()
        val folded = shot()
        compose.onNodeWithText("展开").performClick()
        partWay(0.5)
        val mid = shot()
        compose.mainClock.advanceTimeBy(enter)
        val end = shot()
        assertFalse("halfway looks like the folded page", mid.sameAs(folded))
        assertFalse("halfway looks like the settled diff", mid.sameAs(end))
    }

    @Test fun gitTabsSlideTheUnderlineAndSwapTheContent() {
        val commit = Commit(hash = "c000001", author = "林舟", time = T0, subject = "提交", edges = listOf(Edge(0, 0)))
        var state by mutableStateOf(GitUiState(dir = "/Users/me/github/app", status = GitStatusResponse(branch = "dev"), commits = listOf(commit)))
        compose.setContent { ClaudercTheme(darkTheme = false) { GitScreen(state, GitActions(onTab = { state = state.copy(tab = it) })) } }
        compose.waitForIdle()
        fun x(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        val logX = compose.onNodeWithText("时间线").fetchSemanticsNode().positionInRoot.x
        val statusX = compose.onNodeWithText("改动").fetchSemanticsNode().positionInRoot.x

        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("改动").performClick()
        partWay(0.5)
        compose.onNodeWithTag("timeline").assertExists()
        compose.onNodeWithTag("changes").assertExists()
        val mid = x("tabUnderline")
        assertTrue("halfway the underline is at $mid, between $logX and $statusX", mid > logX && mid < statusX)

        compose.mainClock.advanceTimeBy(enter)
        compose.onNodeWithTag("timeline").assertDoesNotExist()
        compose.onNodeWithTag("changes").assertExists()
        assertEquals(statusX, x("tabUnderline"), 1f)
    }
}

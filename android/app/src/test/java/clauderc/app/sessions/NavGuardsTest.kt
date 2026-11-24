package clauderc.app.sessions

import android.os.Looper
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.ChatRoute
import clauderc.app.ClaudercNavHost
import clauderc.app.HomeRoute
import clauderc.app.navigateFrom
import clauderc.app.navigateWhenResumed
import clauderc.app.popFrom
import clauderc.app.ui.theme.ClaudercTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** plans.md 6: taps during the slide into or out of the chat page must not leave a page stranded. */
@RunWith(AndroidJUnit4::class)
class NavGuardsTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController
    private lateinit var back: OnBackPressedDispatcher

    /** Home opens the chat of s-1 the way the session list does; the chat page goes back the way its back arrow does. */
    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                nav = rememberNavController()
                ClaudercNavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> {
                        val home = LocalLifecycleOwner.current
                        Text("会话列表", Modifier.fillMaxSize().testTag("home").clickable { nav.navigateFrom(home, ChatRoute("m-1", "s-1")) })
                    }
                    composable<ChatRoute> { entry ->
                        Text("聊天", Modifier.fillMaxSize().testTag("chat").clickable { nav.popFrom(entry) })
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun chats() = nav.currentBackStack.value.count { it.destination.hasRoute<ChatRoute>() }

    /** Taps near the left edge, which is on screen even while the page slides in from the right. */
    private fun tap(tag: String) = compose.onNodeWithTag(tag).performTouchInput { click(Offset(8f, height / 2f)) }

    @Test fun aDoubleTapOnASessionOpensOneChat() {
        host()
        tap("home")
        compose.mainClock.advanceTimeByFrame()
        tap("home")
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, chats())
        assertTrue(nav.currentBackStackEntry!!.destination.hasRoute<ChatRoute>())
    }

    @Test fun twoBacksRightAfterOpeningLandOnHome() {
        host()
        tap("home")
        compose.mainClock.advanceTimeBy(50)
        tap("chat")
        compose.mainClock.advanceTimeByFrame()
        tap("chat")
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(nav.currentBackStackEntry!!.destination.hasRoute<HomeRoute>())
        assertEquals(listOf(true), nav.currentBackStack.value.filter { it.destination.route != null }.map { it.destination.hasRoute<HomeRoute>() })
        assertEquals(0, chats())
        compose.onNodeWithTag("chat").assertDoesNotExist()
    }

    /** A back swipe from the left edge: started, dragged to [upTo] over a few frames, then released. */
    private fun swipeBack(upTo: Float = 0.6f, release: Boolean = true, frames: Int = 6, onFrame: () -> Unit = {}) {
        val width = compose.onRoot().fetchSemanticsNode().size.width.toFloat()
        // The gesture's handlers run on the main looper, which a frozen clock does not drain by itself.
        compose.runOnUiThread { back.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)) }
        shadowOf(Looper.getMainLooper()).idle()
        for (i in 1..frames) {
            val progress = upTo * i / frames
            compose.runOnUiThread { back.dispatchOnBackProgressed(BackEventCompat(progress * width, 400f, progress, BackEventCompat.EDGE_LEFT)) }
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(16)
            onFrame()
        }
        if (release) {
            compose.runOnUiThread { back.onBackPressed() }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /**
     * plans.md 6 as reproduced on the phone: a back swipe that starts while the chat page is still
     * sliding in, early or late in the slide, and goes on past its end. Scrubbing the unfinished
     * slide pushed the chat page a quarter to the left (-0.25 of the width when started at 250ms).
     */
    @Test fun aSwipeBackWhileTheChatSlidesInLandsOnHome() {
        host()
        for (start in listOf(50L, 150L, 250L, 350L)) {
            tap("home")
            compose.mainClock.advanceTimeBy(start)
            var leftmost = Float.MAX_VALUE
            swipeBack(frames = 20) {
                leftmost = minOf(leftmost, compose.onNodeWithTag("chat", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x)
            }
            assertTrue("start $start: chat page went left to $leftmost", leftmost >= -0.5f)
            compose.mainClock.advanceTimeBy(1_000)
            assertTrue("start $start", nav.currentBackStackEntry!!.destination.hasRoute<HomeRoute>())
            assertEquals("start $start", 0, chats())
            compose.onNodeWithTag("chat").assertDoesNotExist()
            assertEquals("start $start", 0f, compose.onNodeWithTag("home").fetchSemanticsNode().boundsInRoot.left, 0.5f)
        }
    }

    /** A back swipe started during the slide and given up: the chat page stays, fully in place. */
    @Test fun aCancelledSwipeWhileTheChatSlidesInLeavesTheChatInPlace() {
        host()
        for (start in listOf(50L, 150L, 250L, 350L)) {
            tap("home")
            compose.mainClock.advanceTimeBy(start)
            swipeBack(upTo = 0.2f, release = false, frames = 10)
            compose.runOnUiThread { back.dispatchOnBackCancelled() }
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(1_000)
            assertTrue("start $start", nav.currentBackStackEntry!!.destination.hasRoute<ChatRoute>())
            assertEquals("start $start", 0f, compose.onNodeWithTag("chat", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x, 0.5f)
            compose.onNodeWithTag("home").assertDoesNotExist()
            compose.runOnUiThread { nav.popBackStack() }
            compose.mainClock.advanceTimeBy(1_000)
        }
    }

    /** Once the slide is over the swipe follows the finger again. */
    @Test fun aSwipeBackAfterTheSlideStillFollowsTheFinger() {
        host()
        tap("home")
        compose.mainClock.advanceTimeBy(1_000)
        swipeBack(upTo = 0.5f, release = false)
        val left = compose.onNodeWithTag("chat", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        assertTrue("chat page at $left should follow the finger", left > 0f)
        compose.runOnUiThread { back.onBackPressed() }
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue(nav.currentBackStackEntry!!.destination.hasRoute<HomeRoute>())
        compose.onNodeWithTag("chat").assertDoesNotExist()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun worktreeFinishingWhilePausedNavigatesOnceResumed() {
        val created = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
        val owner = androidx.lifecycle.testing.TestLifecycleOwner(androidx.lifecycle.Lifecycle.State.RESUMED, kotlinx.coroutines.test.UnconfinedTestDispatcher())
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                nav = rememberNavController()
                ClaudercNavHost(nav, startDestination = HomeRoute) {
                    composable<HomeRoute> {
                        Text("会话列表", Modifier.fillMaxSize().testTag("home"))
                    }
                    composable<ChatRoute> { entry ->
                        val r = entry.toRoute<ChatRoute>()
                        if (r.sessionId == "s-1") {
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                for (id in created) nav.navigateWhenResumed(owner, ChatRoute("m-1", id))
                            }
                        }
                        Text("聊天:${r.sessionId}", Modifier.fillMaxSize().testTag("chat:${r.sessionId}"))
                    }
                }
            }
        }
        compose.runOnUiThread { nav.navigate(ChatRoute("m-1", "s-1")) }
        compose.waitForIdle()
        // Pause the page (e.g. user switched apps), then the worktree creation finishes.
        compose.runOnUiThread { owner.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
        created.trySend("s-wt")
        compose.waitForIdle()
        assertEquals("paused: stays on s-1 until foreground returns", 1, chats())
        // Return to the foreground: navigates to the new worktree session at once.
        compose.runOnUiThread { owner.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(2, chats())
        assertEquals("s-wt", nav.currentBackStackEntry!!.toRoute<ChatRoute>().sessionId)
    }
}

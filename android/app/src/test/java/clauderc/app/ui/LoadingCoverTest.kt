package clauderc.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.UiStyle
import androidx.compose.runtime.CompositionLocalProvider
import clauderc.app.ui.components.FIRST_LOAD_HOLD_MILLIS
import clauderc.app.ui.components.LOADING_DELAY_MILLIS
import clauderc.app.ui.components.LoadingFirsts
import clauderc.app.ui.components.LoadingPolicy
import clauderc.app.ui.components.LocalLoadingPolicy
import clauderc.app.ui.components.LoadingArt
import clauderc.app.ui.components.LoadingArtwork
import clauderc.app.ui.components.LoadingCover
import clauderc.app.ui.theme.ClaudercTheme
import clauderc.app.ui.theme.page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The full-screen loading layer: only loads past 300ms get it, and it folds away to show the page. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingCoverTest {
    @get:Rule val compose = createComposeRule()

    private var loading by mutableStateOf(true)

    /** Which place's cover is up, and a key that mounts it anew, as opening the page again does. */
    private var place by mutableStateOf(LoadingArt.Deal)
    private var visit by mutableStateOf(0)

    /** Taps the button on the page under the cover got. */
    private var taps = 0

    /** A red page under the cover, so a corner pixel tells whether the page shows, with a button in its middle. */
    private fun show(policy: LoadingPolicy = LoadingPolicy()) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                CompositionLocalProvider(LocalLoadingPolicy provides policy) {
                    key(place, visit) {
                        LoadingCover(loading, place, Modifier.fillMaxSize(), label = "正在读取会话") {
                            Box(Modifier.fillMaxSize().background(Color.Red)) {
                                Text("下面的按钮", Modifier.align(Alignment.Center).clickable { taps++ })
                            }
                        }
                    }
                }
            }
        }
        run(0)
    }

    /** Opens [art]'s place again with a load that takes [millis]; whether the cover ever came up. */
    private fun openAgain(art: LoadingArt, millis: Long): Boolean {
        compose.runOnUiThread { loading = true; place = art; visit++ }
        run(16)
        var seen = covered()
        run(millis - 16)
        seen = seen || covered()
        compose.runOnUiThread { loading = false }
        repeat(30) { run(100); seen = seen || covered() }
        return seen
    }

    /** Lets the state written by the test reach the screen, then runs [millis]. */
    private fun run(millis: Long) = with(compose.mainClock) {
        Snapshot.sendApplyNotifications()
        advanceTimeByFrame()
        advanceTimeBy(millis)
    }

    private fun covered() = compose.onAllNodesWithTag("loadingCover").fetchSemanticsNodes().isNotEmpty()

    private fun pageShows() = compose.onRoot().captureToImage().asAndroidBitmap().getPixel(4, 4) == android.graphics.Color.RED

    @Test fun aLoadDoneWithin200msNeverShowsTheCover() {
        show()
        run(200 - 16)
        assertFalse("no cover at 0.2s", covered())
        compose.runOnUiThread { loading = false }
        run(0)
        assertTrue("page shows as soon as the load is done", pageShows())
        run(1_000)
        assertFalse("the cover never comes up", covered())
        assertTrue(pageShows())
    }

    @Test fun aLongLoadIsCoveredAndFoldsAwayToThePage() {
        show()
        run(LOADING_DELAY_MILLIS - 50)
        assertFalse("no cover before 0.3s", covered())
        run(2_000 - LOADING_DELAY_MILLIS + 50 - 16)
        assertTrue("cover up at 2s", covered())
        assertFalse("page kept out of sight while loading", pageShows())
        compose.runOnUiThread { loading = false }
        run(100)
        assertTrue("still fading out", covered())
        run(1_000)
        assertFalse("cover gone once loaded", covered())
        assertTrue("page shows once loaded", pageShows())
    }

    /** Up or fading out, the cover takes every touch: the button under it gets one only once it has gone. */
    @Test fun theCoverTakesEveryTouchUntilItHasFaded() {
        show()
        run(2_000)
        assertTrue("cover up at 2s", covered())
        val button = compose.onNodeWithText("下面的按钮")
        button.performClick()
        compose.runOnUiThread { loading = false }
        run(100)
        button.performClick()
        assertTrue("the second tap lands while the cover fades out", covered())
        assertEquals("the button under the cover got no tap", 0, taps)
        run(1_000)
        assertFalse("cover gone once loaded", covered())
        button.performClick()
        assertEquals("once the cover has gone the button takes the tap", 1, taps)
    }

    /** The page is hidden as the load starts, before the cover comes up: the button under it gets no tap then either. */
    @Test fun theHiddenPageTakesNoTouchBeforeTheCoverComesUp() {
        show()
        run(100)
        assertFalse("no cover yet", covered())
        compose.onNodeWithText("下面的按钮").performClick()
        assertFalse("the tap lands before the cover", covered())
        assertEquals("the hidden button got no tap", 0, taps)
        // Done before the cover came up, the page shows at once and takes taps again.
        compose.runOnUiThread { loading = false }
        run(16)
        compose.onNodeWithText("下面的按钮").performClick()
        assertEquals("the shown button takes the tap", 1, taps)
    }

    /**
     * 「总是播放加载动画」: a place's first load since the app started is covered at once, though it is
     * done in 0.2s, and the art stays 1.5s; its next load goes by the 300ms rule; each place counts on its own.
     */
    @Test fun withTheSwitchOnAPlacesFirstLoadIsCoveredAtOnceAndHeldOneAndAHalfSeconds() {
        show(LoadingPolicy(always = true, LoadingFirsts()))
        run(16)
        assertTrue("covered at once", covered())
        assertFalse(pageShows())
        run(200 - 32)
        compose.runOnUiThread { loading = false }
        run(0)
        // Held: still up and not fading just short of 1.5s after it came up.
        run(FIRST_LOAD_HOLD_MILLIS - 200 - 50)
        assertTrue("still up at 1.45s", covered())
        assertFalse("the page waits under it", pageShows())
        run(1_000)
        assertFalse("gone after the hold and the fade", covered())
        assertTrue(pageShows())

        assertFalse("the same place's second 0.2s load is not covered", openAgain(LoadingArt.Deal, 200))
        // The chat page played its first load; git's first one still plays.
        assertTrue("chat's first load", openAgain(LoadingArt.Skeleton, 200))
        assertFalse("chat's second load", openAgain(LoadingArt.Skeleton, 200))
        assertTrue("git's first load after chat's", openAgain(LoadingArt.Trace, 200))
    }

    @Test fun withTheSwitchOffLoadsGoByThe300msRuleAndCountForNothing() {
        val firsts = LoadingFirsts()
        show(LoadingPolicy(always = false, firsts))
        run(200 - 16)
        assertFalse("no cover at 0.2s", covered())
        compose.runOnUiThread { loading = false }
        run(1_000)
        assertFalse(covered())
        // Loads made while it was off do not use up the place's first load.
        assertTrue(LoadingPolicy(always = true, firsts).first(LoadingArt.Deal))
    }

    /** Every art draws in every style, and moves: two moments of its loop differ. */
    @Test fun everyArtAnimatesInEveryStyle() {
        var art by mutableStateOf(LoadingArt.Link)
        var style by mutableStateOf(UiStyle.Clear)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            key(art, style) {
                ClaudercTheme(darkTheme = false, style = style) {
                    Box(Modifier.fillMaxSize().page()) { LoadingArtwork(art, label = "加载中", title = "README 截图") }
                }
            }
        }
        fun frame(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        for (s in UiStyle.entries) for (a in LoadingArt.entries) {
            compose.runOnUiThread { art = a; style = s }
            run(250)
            val early = frame()
            run(950)
            assertFalse("$a in $s moves between 250ms and 1200ms", early.sameAs(frame()))
        }
    }

    @Test fun loadingAgainWhileFadingOutReturnsFromCurrentAlphaWithoutDisappearing() {
        compose.mainClock.autoAdvance = false
        loading = true
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                // Solid red behind a splash cover whose white page paints at cover.value alpha, so green == alpha.
                Box(Modifier.fillMaxSize().background(Color.Red)) {
                    LoadingCover(loading, LoadingArt.Deal, Modifier.fillMaxSize(), splash = true) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        fun coverAlpha(): Float = android.graphics.Color.green(compose.onRoot().captureToImage().asAndroidBitmap().getPixel(4, 4)) / 255f
        run(2_000)
        assertTrue("cover is up and opaque", covered() && coverAlpha() > 0.95f)

        // Start fading out and pause partway through.
        compose.runOnUiThread { loading = false }
        run(260)
        val mid = coverAlpha()
        assertTrue("still fading out partway: alpha=$mid", covered() && mid in 0.1f..0.92f)

        // Enter loading again mid-fade: cover stays mounted and alpha rises from [mid] back to 1 without disappearing.
        compose.runOnUiThread { loading = true }
        run(0)
        var prev = mid
        var minSeen = mid
        repeat(20) {
            run(16)
            assertTrue("cover never disappears while returning to opaque", covered())
            val cur = coverAlpha()
            minSeen = minOf(minSeen, cur)
            prev = cur
        }
        assertTrue("alpha did not drop below its mid-fade level ($minSeen vs $mid)", minSeen >= mid - 0.05f)
        assertTrue("alpha returned to opaque ($prev)", prev > 0.95f)
    }
}

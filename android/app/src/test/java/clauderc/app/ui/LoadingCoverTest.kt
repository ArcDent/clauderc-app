package clauderc.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.UiStyle
import clauderc.app.ui.components.LOADING_DELAY_MILLIS
import clauderc.app.ui.components.LoadingArt
import clauderc.app.ui.components.LoadingArtwork
import clauderc.app.ui.components.LoadingCover
import clauderc.app.ui.theme.ClaudercTheme
import clauderc.app.ui.theme.page
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

    /** A red page under the cover, so a corner pixel tells whether the page shows. */
    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                LoadingCover(loading, LoadingArt.Deal, Modifier.fillMaxSize(), label = "正在读取会话") {
                    Box(Modifier.fillMaxSize().background(Color.Red))
                }
            }
        }
        run(0)
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
}

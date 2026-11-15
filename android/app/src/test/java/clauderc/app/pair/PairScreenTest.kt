package clauderc.app.pair

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.ui.theme.ClaudercMotion
import clauderc.app.ui.theme.ClaudercTheme
import clauderc.app.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The scan page opens its camera only once it has slid in, and its scan line crosses in 3.6s. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairScreenTest {
    @get:Rule val compose = createComposeRule()

    private val motion = ClaudercMotion()

    @Test fun cameraWaitsForTheFadeIn() {
        var opened = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                PairScreen(PairUiState.Scanning, {}, {}, scanner = { m, _ ->
                    opened = true
                    Box(m)
                })
            }
        }
        compose.mainClock.advanceTimeBy(motion.durM / 2L)
        assertFalse("the camera opened while the page was still sliding in", opened)
        compose.mainClock.advanceTimeBy(motion.durM.toLong())
        assertTrue("the camera never opened", opened)
    }

    @Test fun scanLineTakesLongerToCross() {
        compose.mainClock.autoAdvance = false
        compose.setContent { ClaudercTheme(darkTheme = false) { PairScreen(PairUiState.Scanning, {}, {}, scanner = { m, _ -> Box(m) }) } }
        val box = compose.onNodeWithTag("viewfinder").fetchSemanticsNode().boundsInRoot
        val d = compose.density.density
        // The line starts 10dp down and travels 220dp: its bottom place is 230dp down the finder.
        val bottom = Offset(box.center.x, box.top + 230.5f * d)
        val black = LightColors.camera.toArgb()

        compose.mainClock.advanceTimeBy(2_600)
        assertEquals("at the old 2.6s the line has not reached the bottom yet", black, shot().at(bottom))
        compose.mainClock.advanceTimeBy(1_000 + 16)
        assertNotEquals("at 3.6s the line is at the bottom", black, shot().at(bottom))
    }

    private fun shot(): ImageBitmap = compose.onRoot().captureToImage()

    private fun ImageBitmap.at(p: Offset): Int {
        val px = IntArray(1)
        readPixels(px, startX = p.x.toInt(), startY = p.y.toInt(), width = 1, height = 1)
        return px[0]
    }
}

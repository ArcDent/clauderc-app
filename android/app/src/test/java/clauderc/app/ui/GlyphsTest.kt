package clauderc.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.theme.ClaudercTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The spinner turns its layer instead of redrawing, and must still visibly turn. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlyphsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun spinnerKeepsTurningAtMostEveryOtherFrame() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                Box(Modifier.testTag("spin").size(48.dp).background(Color.White), contentAlignment = Alignment.Center) {
                    Spinner(size = 40.dp, color = Color.Black)
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        fun shot() = compose.onNodeWithTag("spin").captureToImage().asAndroidBitmap()
        val start = shot()
        // About a quarter of the 0.9s turn.
        compose.mainClock.advanceTimeBy(225)
        val quarter = shot()
        assertFalse("the ring did not turn", start.sameAs(quarter))
        // The loop steps at most every 33ms, so over 12 frames of 16ms the ring moves at most 6 times.
        var last = quarter
        var moves = 0
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            val now = shot()
            if (!now.sameAs(last)) moves++
            last = now
        }
        assertTrue("the ring moved $moves times in 12 frames", moves in 2..6)
    }
}

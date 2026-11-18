package clauderc.app.ui

import org.junit.Assert.assertEquals
import clauderc.app.ui.components.StatusGlyph
import clauderc.app.ui.components.Glyph
import clauderc.app.ui.components.CliGlyphs
import clauderc.app.data.UiStyle
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

    /** 格栅's star steps through Claude Code's ten frames every 1.2s and comes back to the dot. */
    @Test fun gridStarStepsLikeClaudeCode() {
        val frames = (0 until 10).map { CliGlyphs.star(it * 120L + 60) }
        assertEquals(listOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢"), frames)
        assertEquals("·", CliGlyphs.star(1_200))
        assertEquals("✢", CliGlyphs.star(1_200 + 120))
    }

    /** 格栅's spinner turns the braille dots in ten steps of 80ms. */
    @Test fun gridSpinnerTurnsBrailleDots() {
        val frames = (0 until 10).map { CliGlyphs.braille(it * 80L) }
        assertEquals(listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"), frames)
        assertEquals("⠋", CliGlyphs.braille(800))
        assertEquals("⠋", CliGlyphs.braille(879))
        assertEquals("⠙", CliGlyphs.braille(880))
    }

    /** 格栅's 「等你」 dot is full for 61% of each 1.2s and faint for the rest. */
    @Test fun gridNeedDotBlinks() {
        assertEquals(1f, CliGlyphs.blink(0), 0f)
        assertEquals(1f, CliGlyphs.blink(731), 0f)
        assertEquals(0.25f, CliGlyphs.blink(732), 0f)
        assertEquals(0.25f, CliGlyphs.blink(1_199), 0f)
        assertEquals(1f, CliGlyphs.blink(1_200), 0f)
    }

    /** The star redraws its own canvas when the frame changes, and only then. */
    @Test fun gridStarRedrawsOnItsSteps() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = UiStyle.Grid) {
                Box(Modifier.testTag("star").size(48.dp).background(Color.White), contentAlignment = Alignment.Center) { StatusGlyph(Glyph.Work) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        fun shot() = compose.onNodeWithTag("star").captureToImage().asAndroidBitmap()
        val first = shot()
        fun ink(b: android.graphics.Bitmap) = (0 until b.height).sumOf { y -> (0 until b.width).count { x -> b.getPixel(x, y) != android.graphics.Color.WHITE } }
        assertTrue("the first frame draws something", ink(first) > 0)
        // The loop clock steps about every 49ms here, so 250ms surely crosses the first 120ms step.
        compose.mainClock.advanceTimeBy(250)
        val next = shot()
        assertFalse("the star did not change past its first 120ms step (${ink(first)} -> ${ink(next)} inked pixels)", first.sameAs(next))
    }
}

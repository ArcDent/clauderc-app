package clauderc.app.git

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.ClaudercTheme
import clauderc.app.ui.theme.PlateLightColors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/**
 * Round 5 item 13: in 贴纸, 「最近的目录」 with eight directories has its ink outline along the sheet's
 * own edge only, with no line across the list. The outline used to stay where the sheet started and
 * its lower edge crossed the list under the second directory (待办/image/todo/1795049261470.png).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecentDirsSheetTest {
    @get:Rule val compose = createComposeRule()

    private val dirs = listOf(
        "/Users/me/github/TEST/QA", "/Users/me/github/TEST/STUDY", "/Users/me/github/SmallProjects/android/clauderc-app",
        "/Users/me/homework/machine-learning", "/Users/me/homework/思政报告", "/Users/me/homework/physics-experiment",
        "/Users/me/homework/cry-liquid", "/Users/me/notes",
    )

    /** The sheet's window as drawn; Robolectric's captureToImage of it would give back the page. */
    private fun sheetWindow(): Bitmap {
        val w = ShadowDialog.getShownDialogs().last { it.isShowing }.window!!.decorView
        return Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888).also { w.draw(Canvas(it)) }
    }

    private val ink = PlateLightColors.ink.toArgb()

    /** Matches opaque ink pixels (excluding the translucent dark scrim above the sheet). */
    private fun isInk(px: Int) = (px ushr 24) >= 0xF0 && listOf(16, 8, 0).all { s -> abs((px shr s and 0xFF) - (ink shr s and 0xFF)) < 24 }

    @Test fun theOutlineRunsAlongTheSheetsEdgeAndNotAcrossTheList() {
        compose.mainClock.autoAdvance = false
        compose.setContent { ClaudercTheme(darkTheme = false, style = UiStyle.Plate) { GitScreen(GitUiState(dirs = dirs), GitActions()) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onAllNodesWithText("选择目录")[0].performClick()
        // The sheet's window lays out on Robolectric's looper and slides up on the test clock: step both.
        repeat(30) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(50)
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("cry-liquid").fetchSemanticsNodes().let { assertTrue("the eighth row is there", it.isNotEmpty()) }

        val bmp = sheetWindow()
        val w = bmp.width
        val inset = (14f * compose.density.density).toInt()
        val px = IntArray(w)
        // Rows that are opaque ink across the slip's 14dp-inset width.
        val span = (inset + 20) until (w - inset - 20)
        val lines = (0 until bmp.height).filter { y ->
            bmp.getPixels(px, 0, w, 0, y, w, 1)
            span.count { isInk(px[it]) } > span.count() * 0.9
        }
        assertTrue("the slip has its ink top & bottom edges", lines.size >= 2)
        val top = lines.first()
        val bottom = lines.last()
        assertTrue("slip spans the list height: top=$top, bottom=$bottom", bottom - top > 300)
        // No opaque ink line cuts across the directory list body (away from the top border, title rule, and bottom edge/shadow).
        val bodyLines = lines.filter { it > top + (80 * compose.density.density).toInt() && it < bottom - (12 * compose.density.density).toInt() }
        assertTrue("no ink line cuts across the directory list: $bodyLines", bodyLines.isEmpty())
        // Both vertical borders of the 14dp-inset PlateSlipPanel run cleanly from top to bottom.
        val sides = listOf(top + 200, (top + bottom) / 2, bottom - 40).map { y ->
            ((inset - 2)..(inset + 3)).any { x -> isInk(bmp.getPixel(x, y)) } &&
                ((w - inset - 4)..(w - inset + 1)).any { x -> isInk(bmp.getPixel(x, y)) }
        }
        assertEquals("ink down both sides of the slip", listOf(true, true, true), sides)
    }
}

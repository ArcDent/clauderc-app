package clauderc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercTheme
import clauderc.app.ui.theme.palette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class FieldsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allStylesRenderFocusErrorAndSingleAndMultilineLayout() {
        var style by mutableStateOf(UiStyle.Ink)
        var error by mutableStateOf(false)
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = style) {
                Column(
                    Modifier.testTag("fields").background(Clauderc.colors.bg).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UnderlineField("", {}, Modifier.testTag("single"), placeholder = "Single", error = error)
                    UnderlineField("", {}, Modifier.testTag("multi"), placeholder = "Multiple", singleLine = false, minLines = 2)
                }
            }
        }

        val single = compose.onNodeWithTag("single")
        val multi = compose.onNodeWithTag("multi")
        fun border() = single.captureToImage().toPixelMap().let { it[1, it.height / 2] }
        fun shadow(): androidx.compose.ui.graphics.Color {
            val fields = compose.onNodeWithTag("fields")
            val bottom = (single.fetchSemanticsNode().boundsInRoot.bottom - fields.fetchSemanticsNode().boundsInRoot.top).toInt()
            return fields.captureToImage().toPixelMap().let { it[it.width / 2, bottom + 2] }
        }

        for (look in UiStyle.entries) {
            compose.runOnUiThread { style = look; error = false }
            multi.performClick()
            val c = palette(look, false)
            val fill = when (look) {
                UiStyle.Ink -> c.surface2
                UiStyle.Plate -> c.bg
                UiStyle.Tile, UiStyle.Clear -> c.surface
            }
            val idleBorder = when (look) {
                UiStyle.Ink -> c.edge
                UiStyle.Plate -> c.fg
                UiStyle.Tile, UiStyle.Clear -> c.line
            }
            val expected = idleBorder.compositeOver(fill)
            val actual = border()
            assertEquals("$look idle red", expected.red, actual.red, 1.01f / 255f)
            assertEquals("$look idle green", expected.green, actual.green, 1.01f / 255f)
            assertEquals("$look idle blue", expected.blue, actual.blue, 1.01f / 255f)
            if (look == UiStyle.Plate) assertEquals(c.bg, shadow())
            single.assertHeightIsEqualTo(42.dp)
            multi.assertHeightIsEqualTo(76.dp)
            val singleBounds = single.fetchSemanticsNode().boundsInRoot
            val multiBounds = multi.fetchSemanticsNode().boundsInRoot
            val singleHint = compose.onNodeWithText("Single", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val multiHint = compose.onNodeWithText("Multiple", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("$look single hint centered", singleBounds.center.y, singleHint.center.y, 1f)
            assertEquals("$look multi hint has 10dp top padding", 20f, multiHint.top - multiBounds.top, 1f)

            single.performClick().assertIsFocused()
            val focus = when (look) {
                UiStyle.Ink -> c.inverse
                UiStyle.Plate -> c.fg
                UiStyle.Tile, UiStyle.Clear -> c.accent
            }
            assertEquals("$look focus", focus, border())
            if (look == UiStyle.Plate) assertEquals(c.fg, shadow())
            compose.runOnUiThread { error = true }
            assertEquals("$look focused error", c.danger, border())
        }
    }
}

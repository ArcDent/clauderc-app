package clauderc.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.UiStyle
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.BarTitle
import clauderc.app.ui.components.Glyph
import clauderc.app.ui.components.GlassNavBar
import clauderc.app.ui.components.Hero
import clauderc.app.ui.components.MenuItem
import clauderc.app.ui.components.NavTab
import clauderc.app.ui.components.PillButton
import clauderc.app.ui.components.PillStyle
import clauderc.app.ui.components.StatusGlyph
import clauderc.app.ui.components.TextAction
import clauderc.app.ui.components.UnderlineField
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Task 11.2: the theme and shared components render in both light and dark. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeRenderTest {
    @get:Rule val compose = createComposeRule()

    private fun gallery(dark: Boolean) {
        compose.setContent {
            ClaudercTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(Clauderc.colors.bg).testTag("root")) {
                    AppBar(title = { BarTitle("clauderc 协议") })
                    Hero("目标机") { StatusGlyph(Glyph.On); androidx.compose.material3.Text("relay.example.com") }
                    StatusGlyph(Glyph.Need)
                    StatusGlyph(Glyph.Work)
                    StatusGlyph(Glyph.Idle)
                    StatusGlyph(Glyph.Stop)
                    PillButton("允许", onClick = {}, style = PillStyle.Accent)
                    PillButton("拒绝", onClick = {}, style = PillStyle.Outline)
                    TextAction("取消", onClick = {})
                    UnderlineField("~/github/infra", onValueChange = {}, mono = true)
                    MenuItem("打开", onClick = {}, icon = Symbols.Description, checked = true)
                    Box(Modifier.size(1.dp))
                    GlassNavBar(
                        tabs = listOf(
                            NavTab("目标机", Symbols.Computer, Symbols.ComputerFilled),
                            NavTab("会话", Symbols.Forum, Symbols.ForumFilled, dot = true),
                            NavTab("git", Symbols.AccountTree, Symbols.AccountTreeFilled),
                        ),
                        selected = 1,
                        onSelect = {},
                        hazeState = null,
                    )
                }
            }
        }
    }

    private fun assertRendered(dark: Boolean) {
        gallery(dark)
        compose.onNodeWithText("clauderc 协议").assertIsDisplayed()
        compose.onNodeWithText("relay.example.com").assertIsDisplayed()
        compose.onNodeWithText("允许").assertIsDisplayed()
        compose.onNodeWithText("会话").assertIsDisplayed()
        val image = compose.onNodeWithTag("root").captureToImage()
        val pixels = IntArray(1)
        // Top right corner: plain background, away from the nav bar's shadow.
        image.readPixels(pixels, startX = image.width - 2, startY = 2, width = 1, height = 1)
        val expected = (if (dark) DarkColors else LightColors).bg.toArgb()
        assertEquals("background pixel", expected, pixels[0])
    }

    @Test fun lightThemeRenders() = assertRendered(dark = false)

    @Test fun darkThemeRenders() = assertRendered(dark = true)

    /** Colour of the accent pill button's left end, inside the rounded part and away from its label. */
    private fun accentButtonPixel(style: UiStyle, accent: Color?): Int {
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = style, accent = accent) {
                PillButton("允许", onClick = {}, modifier = Modifier.testTag("btn"), style = PillStyle.Accent)
            }
        }
        val image = compose.onNodeWithTag("btn").captureToImage()
        val pixels = IntArray(1)
        image.readPixels(pixels, startX = 10, startY = image.height / 2, width = 1, height = 1)
        return pixels[0]
    }

    @Test fun stickerTakesThePickedAccent() {
        assertEquals(Color(0xFF34C759).toArgb(), accentButtonPixel(UiStyle.Sticker, Color(0xFF34C759)))
    }

    @Test fun minimalStyleKeepsItsCobalt() {
        assertEquals(Color(0xFF2A45E0).toArgb(), accentButtonPixel(UiStyle.Clear, Color(0xFF34C759)))
    }
}

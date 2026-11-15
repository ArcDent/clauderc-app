package clauderc.app.settings

import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.ClaudercApp
import clauderc.app.MainActivity
import clauderc.app.data.AccentChoice
import clauderc.app.data.ThemeMode
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.CardDarkColors
import clauderc.app.ui.theme.CardLightColors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The 设置 tab in the real app: it switches the style, light or dark and the accent, and keeps them. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val theme get() = (compose.activity.application as ClaudercApp).container.theme
    private val swatchNames = AccentChoice.entries.map { it.label }

    // The settings data store lives as long as the test process, so every test starts and ends at the defaults.
    private fun resetLook() {
        runBlocking {
            theme.setStyle(UiStyle.Minimal)
            theme.setMode(ThemeMode.System)
            theme.setAccent(AccentChoice.Blue)
        }
    }

    @Before fun startAtDefaults() {
        resetLook()
        waitFor("还没有目标机")
    }

    @After fun backToDefaults() {
        resetLook()
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForGone(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    private fun openSettings() {
        compose.onNodeWithText("设置").performClick()
        waitFor("深浅色")
    }

    private fun pick(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    private val tabs = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val swatches = SemanticsMatcher("is a swatch") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it in swatchNames } == true
    }

    /** Top right corner of the screen: under the transparent status bar, nothing but the page background. */
    private fun backgroundPixel(): Int {
        val image = compose.onRoot().captureToImage()
        val px = IntArray(1)
        image.readPixels(px, startX = image.width - 2, startY = 2, width = 1, height = 1)
        return px[0]
    }

    @Test fun bothStylesHaveFourTabs() {
        compose.onAllNodes(tabs).assertCountEquals(4)
        openSettings()
        pick("卡片")
        compose.onAllNodes(tabs).assertCountEquals(4)
    }

    @Test fun cardAndDarkRetintTheApp() {
        openSettings()
        compose.onNodeWithText("样式").assertIsDisplayed()
        compose.onAllNodesWithText("主题色").assertCountEquals(0)

        pick("卡片")
        waitFor("主题色")
        assertEquals("card light background", CardLightColors.bg.toArgb(), backgroundPixel())

        pick("深色")
        // The pick is written to DataStore on a background thread, which waitForIdle does not wait for.
        compose.waitUntil("card dark background", 5_000) { backgroundPixel() == CardDarkColors.bg.toArgb() }
    }

    @Config(sdk = [30])
    @Test fun noWallpaperAccentBeforeAndroid12() {
        openSettings()
        pick("卡片")
        waitFor("主题色")
        compose.onAllNodes(swatches).assertCountEquals(8)
        compose.onAllNodes(hasContentDescription("跟随壁纸")).assertCountEquals(0)
    }

    @Test fun wallpaperAccentFollowsTheSystemColours() {
        openSettings()
        pick("卡片")
        waitFor("主题色")
        compose.onAllNodes(swatches).assertCountEquals(9)
        compose.onNodeWithContentDescription("跟随壁纸").performClick()
        compose.waitForIdle()

        // The empty machine page's 「添加」 is a solid accent button in the card style.
        compose.onNodeWithText("目标机").performClick()
        waitFor("还没有目标机")
        val button = compose.onNodeWithText("添加").captureToImage()
        val px = IntArray(1)
        button.readPixels(px, startX = 2, startY = button.height / 2, width = 1, height = 1)
        assertEquals(dynamicLightColorScheme(compose.activity).primary.toArgb(), px[0])
    }

    @Test fun picksSurviveRecreatingTheActivity() {
        openSettings()
        pick("卡片")
        waitFor("主题色")
        pick("深色")
        compose.onNodeWithContentDescription("绿色").performClick()
        compose.waitForIdle()

        compose.activityRule.scenario.recreate()
        // The open tab lives in the app container, so the new activity comes back on 设置.
        waitFor("深浅色")
        compose.onNodeWithText("卡片").assertIsSelected()
        compose.onNodeWithText("深色").assertIsSelected()
        compose.onNodeWithContentDescription("绿色").assertIsSelected()
    }
}

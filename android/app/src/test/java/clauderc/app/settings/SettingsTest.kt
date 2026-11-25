package clauderc.app.settings

import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.ClaudercApp
import clauderc.app.MainActivity
import clauderc.app.data.AccentChoice
import clauderc.app.data.PlateInk
import clauderc.app.data.PlateNavStyle
import clauderc.app.data.ThemeMode
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.PlateLightColors
import clauderc.app.ui.theme.TileDarkColors
import clauderc.app.ui.theme.TileLightColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The 设置 tab in the real app: switches among all 4 styles, light/dark, accents, Plate ink/nav, animation, and connection/pairing. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val theme get() = (compose.activity.application as ClaudercApp).container.theme
    private val swatchNames = AccentChoice.entries.map { it.label }

    private fun resetLook() {
        runBlocking {
            theme.setStyle(UiStyle.Ink)
            theme.setMode(ThemeMode.System)
            theme.setAccent(AccentChoice.Default)
            theme.setPlateInk(PlateInk.Black)
            theme.setPlateNav(PlateNavStyle.Float)
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

    private fun backgroundPixel(): Int {
        val image = compose.onRoot().captureToImage()
        val px = IntArray(1)
        val at = with(compose.density) { 9.dp.toPx() }.toInt()
        image.readPixels(px, startX = at, startY = at, width = 1, height = 1)
        return px[0]
    }

    @Test fun allStylesHaveThreeTabs() {
        compose.onAllNodes(tabs).assertCountEquals(3)
        openSettings()
        for (style in UiStyle.entries) {
            pick(style.label)
            compose.onAllNodes(tabs).assertCountEquals(3)
        }
    }

    @Test fun tileAndDarkRetintTheApp() {
        openSettings()
        compose.onNodeWithText("外观").assertIsDisplayed()
        compose.onNodeWithText("浮墨").assertIsSelected()

        pick("素卡")
        waitFor("主题色")
        compose.waitUntil("素卡 light background", 5_000) { backgroundPixel() == TileLightColors.bg.toArgb() }

        pick("深色")
        compose.waitUntil("素卡 dark background", 5_000) { backgroundPixel() == TileDarkColors.bg.toArgb() }
    }

    @Test fun plateShowsEightInksAndNavStyleToggleAndPersists() {
        openSettings()
        pick("印版")
        waitFor("墨色")
        compose.waitUntil("印版 light background", 5_000) { backgroundPixel() == PlateLightColors.bg.toArgb() }
        compose.onNodeWithText("墨黑").assertIsSelected()
        compose.onNodeWithText("群青").performScrollTo().performClick()
        compose.onNodeWithText("底栏样式").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("贴底通栏").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            runBlocking {
                val a = theme.appearance.first()
                a.style == UiStyle.Plate && a.plateInk == PlateInk.Ultra && a.plateNav == PlateNavStyle.Dock
            }
        }

        compose.activityRule.scenario.recreate()
        waitFor("墨色")
        compose.onNodeWithText("印版").assertIsSelected()
        compose.onNodeWithText("群青").performScrollTo().assertIsSelected()
        compose.onNodeWithText("贴底通栏").performScrollTo().assertIsSelected()
    }

    @Config(sdk = [30])
    @Test fun noWallpaperAccentBeforeAndroid12() {
        openSettings()
        pick("素卡")
        waitFor("主题色")
        compose.onAllNodes(swatches).assertCountEquals(9)
        compose.onAllNodes(hasContentDescription("跟随壁纸")).assertCountEquals(0)
    }

    @Test fun wallpaperAccentFollowsTheSystemColours() {
        openSettings()
        pick("素卡")
        waitFor("主题色")
        compose.onAllNodes(swatches).assertCountEquals(10)
        compose.onNodeWithContentDescription("跟随壁纸").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { runBlocking { theme.appearance.first().accent == AccentChoice.Wallpaper } }
        val expected = dynamicLightColorScheme(compose.activity).primary.toArgb()
        // The Wallpaper swatch itself renders the system dynamic primary colour.
        val swatch = compose.onNodeWithContentDescription("跟随壁纸").captureToImage()
        val px = IntArray(1)
        swatch.readPixels(px, startX = swatch.width / 2, startY = swatch.height / 3, width = 1, height = 1)
        assertEquals(expected, px[0])
    }

    @Test fun animationSectionIsRemovedFromSettings() {
        openSettings()
        compose.onNodeWithText("连接").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("总是播放加载动画")).assertCountEquals(0)
        compose.onAllNodes(hasText("动画")).assertCountEquals(0)
    }

    @Test fun inkSettingsFitsAppearanceConnectionAndAboutOnFirstScreenWithMorphOverlays() {
        openSettings()
        // First screen shows 外观 + 深浅色 + 点缀色 + 连接 + 关于 simultaneously without scrolling!
        compose.onNodeWithText("外观").assertIsDisplayed()
        compose.onNodeWithText("深浅色").assertIsDisplayed()
        compose.onNodeWithText("点缀色").assertIsDisplayed()
        compose.onNodeWithText("连接").assertIsDisplayed()
        compose.onNodeWithText("中转服务器").assertIsDisplayed()
        compose.onNodeWithText("配对新的目标机").assertIsDisplayed()
        compose.onNodeWithText("关于").assertIsDisplayed()

        // Tapping 中转服务器 opens live relay status morph panel.
        compose.onNodeWithTag("settingsRelayRow").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("尚未配对目标机").assertIsDisplayed()
        compose.onNodeWithTag("morphScrim").performClick()
        compose.waitForIdle()

        // Tapping 关于 opens about morph panel.
        compose.onNodeWithTag("settingsAboutRow").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("关于 clauderc").assertIsDisplayed()
        compose.onNodeWithTag("morphScrim").performClick()
        compose.waitForIdle()

        // Tapping 配对新的目标机 morphs open PairMorphPanel.
        compose.onNodeWithTag("settingsPairRow").performClick()
        waitFor("扫描配对二维码")
        compose.onNodeWithTag("inkPairMorph").assertIsDisplayed()
    }

    @Test fun connectionSectionShowsRelayStatusAndOpensPairing() {
        openSettings()
        compose.onNodeWithText("连接").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("中转服务器") and hasText("未配对")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settingsPairRow").performScrollTo().performClick()
        waitFor("扫描配对二维码")
        compose.onNodeWithText("扫描配对二维码").assertIsDisplayed()
    }

    @Test fun picksSurviveRecreatingTheActivity() {
        openSettings()
        pick("素卡")
        waitFor("主题色")
        pick("深色")
        compose.onNodeWithContentDescription("绿色").performClick()
        compose.waitForIdle()

        compose.activityRule.scenario.recreate()
        waitFor("深浅色")
        compose.onNodeWithText("素卡").assertIsSelected()
        compose.onNodeWithText("深色").assertIsSelected()
        compose.onNodeWithContentDescription("绿色").assertIsSelected()
    }
}

/** Verifies the paired relay status and 「清除全部配对」 confirmation flow in SettingsScreen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
class ConnectionSettingsTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.v2.createComposeRule()

    @Test fun pairedRelayShowsConnectedAndClearAllAsksBeforeClearing() {
        var cleared = 0
        compose.setContent {
            clauderc.app.ui.theme.ClaudercTheme(darkTheme = false, style = UiStyle.Ink) {
                SettingsScreen(
                    look = clauderc.app.data.Appearance(style = UiStyle.Ink),
                    cardAccent = null,
                    wallpaperAvailable = false,
                    onStyle = { _, _ -> },
                    onMode = { _, _ -> },
                    onAccent = { _, _ -> },
                    relays = listOf(clauderc.app.net.RelayLink("wss://relay.example.com", "relay.example.com", clauderc.app.net.ConnectionState.Connected)),
                    onClearAll = { cleared++ },
                )
            }
        }
        compose.onNodeWithText("relay.example.com").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已连接").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settingsClearPairingsRow").performScrollTo().performClick()
        compose.onNodeWithText("清除全部配对？").assertIsDisplayed()
        assertEquals(0, cleared)
        compose.onNodeWithText("清除").performClick()
        assertEquals(1, cleared)
    }
}

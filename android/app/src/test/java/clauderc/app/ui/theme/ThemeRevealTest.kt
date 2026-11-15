package clauderc.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.AccentChoice
import clauderc.app.data.Appearance
import clauderc.app.data.ThemeMode
import clauderc.app.data.UiStyle
import clauderc.app.settings.SettingsScreen
import clauderc.app.ui.components.GlassNavBar
import clauderc.app.ui.components.NavTab
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A pick in 设置 spreads the new theme out from the tapped option over durL; outside the circle the old one stays. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeRevealTest {
    @get:Rule val compose = createComposeRule()
    private val motion = ClaudercMotion()
    private lateinit var reveal: ThemeReveal

    private val start = Appearance(UiStyle.Card, ThemeMode.Light, AccentChoice.Blue)

    /** The 设置 page over the nav bar, with the pick kept in memory so a change lands in the same frame. */
    private fun app() {
        compose.setContent {
            var look by remember { mutableStateOf(start) }
            ClaudercRoot(look) {
                val r = LocalThemeReveal.current!!
                reveal = r
                Box(Modifier.fillMaxSize().background(Clauderc.colors.bg)) {
                    SettingsScreen(
                        look = look,
                        cardAccent = accentColor(look.accent, Clauderc.colors.isDark),
                        wallpaperAvailable = false,
                        onStyle = { s, at -> r.start(at) { look = look.copy(style = s) } },
                        onMode = { m, at -> r.start(at) { look = look.copy(mode = m) } },
                        onAccent = { a, at -> r.start(at) { look = look.copy(accent = a) } },
                    )
                    GlassNavBar(
                        tabs = listOf(
                            NavTab("目标机", Symbols.Computer, Symbols.ComputerFilled),
                            NavTab("会话", Symbols.Forum, Symbols.ForumFilled),
                            NavTab("git", Symbols.AccountTree, Symbols.AccountTreeFilled),
                            NavTab("设置", Symbols.Settings, Symbols.SettingsFilled),
                        ),
                        selected = 3,
                        onSelect = {},
                        hazeState = null,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** Taps [node] with the clock frozen and stops at the frame the circle starts growing from radius 0. */
    private fun tapAndWaitForTheCircle(node: SemanticsNodeInteraction) {
        compose.mainClock.autoAdvance = false
        // Robolectric only draws when asked; a phone draws every frame, which keeps the reveal's layer current.
        shot()
        node.performClick()
        var frames = 0
        while (!reveal.radius.isRunning) {
            compose.mainClock.advanceTimeByFrame()
            assertTrue("the circle never started", ++frames < 60)
        }
    }

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot

    private fun shot(): ImageBitmap = compose.onRoot().captureToImage()

    private fun ImageBitmap.at(p: Offset): Int {
        val px = IntArray(1)
        readPixels(px, startX = p.x.toInt(), startY = p.y.toInt(), width = 1, height = 1)
        return px[0]
    }

    /** The screen corner farthest from [c], 2px in. */
    private fun farCorner(c: Offset, w: Int, h: Int): Offset =
        listOf(Offset(2f, 2f), Offset(w - 3f, 2f), Offset(2f, h - 3f), Offset(w - 3f, h - 3f)).maxBy { hypot(it.x - c.x, it.y - c.y) }

    private fun assertClose(message: String, expected: Int, actual: Int) {
        val e = Color(expected)
        val a = Color(actual)
        val ok = listOf(e.red to a.red, e.green to a.green, e.blue to a.blue).all { (x, y) -> abs(x - y) <= 2f / 255 }
        assertTrue("$message: expected ${Integer.toHexString(expected)} got ${Integer.toHexString(actual)}", ok)
    }

    @Test fun darkSpreadsFromTheDarkOption() {
        app()
        val option = compose.onNodeWithText("深色")
        val b = bounds(option)
        val below = Offset(b.center.x, b.bottom + with(compose.density) { 8.dp.toPx() })
        tapAndWaitForTheCircle(option)

        compose.mainClock.advanceTimeBy(motion.durL / 2L, ignoreFrameDuration = true)
        var image = shot()
        val corner = farCorner(b.center, image.width, image.height)
        assertEquals("next to the option the group card is already dark", CardDarkColors.card.toArgb(), image.at(below))
        assertEquals("the far corner still shows the light page", CardLightColors.bg.toArgb(), image.at(corner))

        compose.mainClock.advanceTimeBy(motion.durL.toLong())
        image = shot()
        assertEquals("the far corner is dark at the end", CardDarkColors.bg.toArgb(), image.at(corner))
        assertFalse(reveal.running)
    }

    @Test fun accentSpreadsFromTheSwatch() {
        app()
        val green = compose.onNodeWithContentDescription("绿色")
        val swatch = bounds(green)
        // The 2dp ring is drawn at radius 26dp in the 54dp swatch: its top is 1dp below the swatch's.
        val ring = Offset(swatch.center.x, swatch.top + with(compose.density) { 1.dp.toPx() })
        val tab = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))[3]
        val cell = bounds(tab)
        val indicator = Offset(cell.left + with(compose.density) { 6.dp.toPx() }, cell.center.y)
        fun tint(rgb: Long) = Color(0xFF000000 or rgb).copy(alpha = 0.14f).compositeOver(CardLightColors.card).toArgb()
        tapAndWaitForTheCircle(green)

        // The first frame the circle shows at all.
        while (reveal.radius.value == 0f) compose.mainClock.advanceTimeByFrame()
        var image = shot()
        assertEquals("the picked swatch already has its green ring", Color(0xFF34C759).toArgb(), image.at(ring))
        assertClose("the nav bar is still blue", tint(0x007AFF), image.at(indicator))

        compose.mainClock.advanceTimeBy(motion.durL.toLong())
        image = shot()
        assertClose("the nav bar is green at the end", tint(0x34C759), image.at(indicator))
    }

    @Test fun minimalSpreadsFromTheMinimalOption() {
        app()
        val option = compose.onNodeWithText("极简")
        val b = bounds(option)
        // The 12dp gap between the 样式 and 深浅色 cards, on the right: grey page in the card style, white in the minimal one.
        val heading = bounds(compose.onNodeWithText("深浅色"))
        val gap = with(compose.density) { Offset(390.dp.toPx() - 40.dp.toPx(), heading.top - 16.dp.toPx() - 6.dp.toPx()) }
        tapAndWaitForTheCircle(option)

        compose.mainClock.advanceTimeBy(motion.durL / 2L, ignoreFrameDuration = true)
        var image = shot()
        val corner = farCorner(b.center, image.width, image.height)
        assertEquals("near the option the page is already the minimal white", LightColors.bg.toArgb(), image.at(gap))
        assertEquals("the far corner still shows the card grey", CardLightColors.bg.toArgb(), image.at(corner))

        compose.mainClock.advanceTimeBy(motion.durL.toLong())
        image = shot()
        // The far corner is the lower right one, where the glass nav bar's soft shadow tints the white by a step.
        assertClose("the far corner is white at the end", LightColors.bg.toArgb(), image.at(corner))
    }
}

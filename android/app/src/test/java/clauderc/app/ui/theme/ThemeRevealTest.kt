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
import androidx.compose.ui.graphics.asAndroidBitmap
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

/** A pick in 设置 spreads the new theme out from the tapped option over durReveal; outside the circle the old one stays. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeRevealTest {
    @get:Rule val compose = createComposeRule()
    private val motion = ClaudercMotion()
    private lateinit var reveal: ThemeReveal

    private val start = Appearance(UiStyle.Tile, ThemeMode.Light, AccentChoice.Blue)

    /** The nav bar's tabs picked, in order. */
    private val picked = mutableListOf<Int>()

    /** The 设置 page over the nav bar, with the pick kept in memory so a change lands in the same frame. */
    private fun app(initial: Appearance = start) {
        compose.setContent {
            var look by remember { mutableStateOf(initial) }
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
                        onPlateInk = { ink, at -> r.start(at) { look = look.copy(plateInk = ink) } },
                        onPlateNav = { nav -> look = look.copy(plateNav = nav) },
                    )
                    GlassNavBar(
                        tabs = listOf(
                            NavTab("会话", Symbols.Forum, Symbols.ForumFilled, dot = true),
                            NavTab("git", Symbols.AccountTree, Symbols.AccountTreeFilled),
                            NavTab("设置", Symbols.Settings, Symbols.SettingsFilled),
                        ),
                        selected = 2,
                        onSelect = { picked += it },
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

    /** The screen corner farthest from [c], 9dp in. */
    private fun farCorner(c: Offset, w: Int, h: Int): Offset {
        val i = with(compose.density) { 9.dp.toPx() }
        return listOf(Offset(i, i), Offset(w - i, i), Offset(i, h - i), Offset(w - i, h - i)).maxBy { hypot(it.x - c.x, it.y - c.y) }
    }

    private fun assertClose(message: String, expected: Int, actual: Int) {
        val e = Color(expected)
        val a = Color(actual)
        val ok = listOf(e.red to a.red, e.green to a.green, e.blue to a.blue).all { (x, y) -> abs(x - y) <= 4f / 255 }
        assertTrue("$message: expected ${Integer.toHexString(expected)} got ${Integer.toHexString(actual)}", ok)
    }

    @Test fun darkSpreadsFromTheDarkOption() {
        app()
        val option = compose.onNodeWithText("深色")
        val b = bounds(option)
        val below = Offset(b.center.x, b.bottom + with(compose.density) { 8.dp.toPx() })
        val lightCorner = shot().let { it.at(farCorner(b.center, it.width, it.height)) }
        tapAndWaitForTheCircle(option)

        // Slow at the start: a quarter of the way in, the circle has not reached half its size.
        compose.mainClock.advanceTimeBy(motion.durReveal / 4L, ignoreFrameDuration = true)
        assertTrue("radius ${reveal.radius.value} of ${reveal.farthest()}", reveal.radius.value > 0f && reveal.radius.value <= reveal.farthest() / 2)

        compose.mainClock.advanceTimeBy(motion.durReveal / 4L, ignoreFrameDuration = true)
        var image = shot()
        val corner = farCorner(b.center, image.width, image.height)
        assertClose("next to the option the group card is already dark", TileDarkColors.card.toArgb(), image.at(below))
        assertEquals("the far corner still shows the light page", lightCorner, image.at(corner))

        compose.mainClock.advanceTimeBy(motion.durReveal.toLong())
        image = shot()
        assertClose("the far corner is dark at the end", TileDarkColors.bg.toArgb(), image.at(corner))
        assertFalse(reveal.running)
    }

    @Test fun accentSpreadsFromTheSwatch() {
        app()
        val green = compose.onNodeWithContentDescription("绿色")
        val swatch = bounds(green)
        // Outer ring sits around the 36dp compact swatch box: 17dp above swatch centre.
        val ring = Offset(swatch.center.x, swatch.center.y - with(compose.density) { 17.dp.toPx() })
        // The accent dot on the 会话 tab (index 0).
        val tab = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))[0]
        val cell = bounds(tab)
        val dot = with(compose.density) { Offset(cell.center.x + 10.5.dp.toPx(), cell.top + 6.5.dp.toPx()) }
        tapAndWaitForTheCircle(green)

        val reach = with(compose.density) { 30.dp.toPx() }
        while (reveal.radius.value < reach) compose.mainClock.advanceTimeByFrame()
        var image = shot()
        assertClose("the nav bar's dot is still blue", Color(0xFF007AFF).toArgb(), image.at(dot))
        assertClose("the picked swatch already has its green ring", Color(0xFF34C759).toArgb(), image.at(ring))

        compose.mainClock.advanceTimeBy(motion.durReveal.toLong())
        image = shot()
        assertClose("the nav bar's dot is green at the end", Color(0xFF34C759).toArgb(), image.at(dot))
    }

    /**
     * 素卡 → 浮墨 → 清玻 → 印版 → 素卡: the circle grows from the tapped option; halfway, every point
     * well outside it is the page from before the tap and every point well inside it is the page from
     * the end; then nothing of the old page is left.
     */
    @Test fun everyStyleSpreadsIntoEveryOther() {
        app()
        val step = with(compose.density) { 12.dp.toPx() }
        val edge = with(compose.density) { 3.dp.toPx() }
        val barTop = with(compose.density) { (844 - 110).dp.toPx() }
        var from = start.style
        for (to in listOf(UiStyle.Ink, UiStyle.Clear, UiStyle.Plate, UiStyle.Tile)) {
            val option = compose.onNodeWithText(to.label)
            val b = bounds(option).inflate(with(compose.density) { 8.dp.toPx() })
            val before = shot()
            tapAndWaitForTheCircle(option)
            assertTrue("$from → $to starts at the option", b.contains(reveal.center))
            compose.mainClock.advanceTimeBy(motion.durReveal / 2L, ignoreFrameDuration = true)
            val mid = shot()
            val c = reveal.center
            val r = reveal.radius.value
            compose.mainClock.advanceTimeBy(motion.durReveal + 500L)
            assertFalse("$from → $to has ended", reveal.running)
            val end = shot()
            compose.mainClock.advanceTimeBy(500L)
            val later = shot()

            var outside = 0
            var inside = 0
            val wrong = mutableListOf<String>()
            var y = step / 2
            while (y < mid.height) {
                var x = step / 2
                while (x < mid.width) {
                    val p = Offset(x, y)
                    val d = hypot(p.x - c.x, p.y - c.y)
                    if (!b.contains(p)) {
                        if (d > r + edge && y < barTop) {
                            outside++
                            if (mid.at(p) != before.at(p)) wrong += "outside $p ${Integer.toHexString(before.at(p))}→${Integer.toHexString(mid.at(p))}"
                        } else if (d < r - edge && y < barTop) {
                            inside++
                            if (mid.at(p) != end.at(p)) wrong += "inside $p ${Integer.toHexString(end.at(p))}→${Integer.toHexString(mid.at(p))}"
                        }
                    }
                    if (end.at(p) != later.at(p)) wrong += "after the end $p"
                    x += step
                }
                y += step
            }
            assertTrue("$from → $to halfway compares points on both sides ($inside in, $outside out)", inside > 50 && outside > 50)
            assertTrue("$from → $to: ${wrong.size} points off, first ${wrong.take(8)}", wrong.isEmpty())
            assertFalse("$from → $to changes the page", before.asAndroidBitmap().sameAs(end.asAndroidBitmap()))
            from = to
        }
    }

    /** The old page's picture covers the nav bar while the circle grows: a tap on a tab there switches nothing. */
    @Test fun aTabTappedWhileTheCircleGrowsIsNotPicked() {
        app()
        val tab = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))[0]
        tapAndWaitForTheCircle(compose.onNodeWithText("深色"))
        compose.mainClock.advanceTimeBy(motion.durReveal / 2L, ignoreFrameDuration = true)
        tab.performClick()
        assertTrue("the tap lands mid reveal", reveal.running)
        assertEquals("no tab picked mid reveal", emptyList<Int>(), picked)

        compose.mainClock.advanceTimeBy(motion.durReveal.toLong())
        assertFalse(reveal.running)
        tab.performClick()
        assertEquals("once the reveal ends the tab takes the tap", listOf(0), picked)
    }

    @Test fun clearSpreadsFromTheClearOption() {
        app()
        val option = compose.onNodeWithText("清玻")
        val b = bounds(option)
        val heading = bounds(compose.onNodeWithText("外观"))
        val gap = Offset(b.center.x, heading.top - with(compose.density) { 26.dp.toPx() })
        val paperCorner = shot().let { it.at(farCorner(b.center, it.width, it.height)) }
        tapAndWaitForTheCircle(option)

        compose.mainClock.advanceTimeBy(motion.durReveal * 3L / 4, ignoreFrameDuration = true)
        var image = shot()
        val corner = farCorner(b.center, image.width, image.height)
        assertEquals("near the option the page is already 清玻 white", ClearLightColors.bg.toArgb(), image.at(gap))
        assertEquals("the far corner still shows the 素卡 paper", paperCorner, image.at(corner))

        compose.mainClock.advanceTimeBy(motion.durReveal.toLong())
        image = shot()
        assertClose("the far corner is white at the end", LightColors.bg.toArgb(), image.at(corner))
    }
}

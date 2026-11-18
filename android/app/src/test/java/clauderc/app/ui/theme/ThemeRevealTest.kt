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

/** A pick in 设置 spreads the new theme out from the tapped option over durReveal; outside the circle the old one stays. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeRevealTest {
    @get:Rule val compose = createComposeRule()
    private val motion = ClaudercMotion()
    private lateinit var reveal: ThemeReveal

    private val start = Appearance(UiStyle.Sticker, ThemeMode.Light, AccentChoice.Blue)

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
                            NavTab("会话", Symbols.Forum, Symbols.ForumFilled, dot = true),
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

    /** The screen corner farthest from [c], 9dp in: between 贴纸's paper dots and clear of the floating nav bar. */
    private fun farCorner(c: Offset, w: Int, h: Int): Offset {
        val i = with(compose.density) { 9.dp.toPx() }
        return listOf(Offset(i, i), Offset(w - i, i), Offset(i, h - i), Offset(w - i, h - i)).maxBy { hypot(it.x - c.x, it.y - c.y) }
    }

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
        // The light paper as drawn, washes and all, before the tap.
        val lightCorner = shot().let { it.at(farCorner(b.center, it.width, it.height)) }
        tapAndWaitForTheCircle(option)

        // Slow at the start: a quarter of the way in, the circle has not reached half its size.
        compose.mainClock.advanceTimeBy(motion.durReveal / 4L, ignoreFrameDuration = true)
        assertTrue("radius ${reveal.radius.value} of ${reveal.farthest()}", reveal.radius.value > 0f && reveal.radius.value <= reveal.farthest() / 2)

        compose.mainClock.advanceTimeBy(motion.durReveal / 4L, ignoreFrameDuration = true)
        var image = shot()
        val corner = farCorner(b.center, image.width, image.height)
        // The group card is 贴纸's see-through panel over the dark paper.
        assertClose("next to the option the group card is already dark", StickerDarkColors.panel.compositeOver(StickerDarkColors.bg).toArgb(), image.at(below))
        assertEquals("the far corner still shows the light page", lightCorner, image.at(corner))

        compose.mainClock.advanceTimeBy(motion.durReveal.toLong())
        image = shot()
        assertClose("the far corner is dark at the end", StickerDarkColors.bg.toArgb(), image.at(corner))
        assertFalse(reveal.running)
    }

    @Test fun accentSpreadsFromTheSwatch() {
        app()
        val green = compose.onNodeWithContentDescription("绿色")
        val swatch = bounds(green)
        // 贴纸 lifts the picked dot 3dp: 3.5dp below the swatch's top its ink ring gives way to the green dot.
        val lifted = Offset(swatch.center.x, swatch.top + with(compose.density) { 3.5.dp.toPx() })
        // The accent dot on the 会话 tab, 10.5dp right of the cell's centre and 6.5dp below its top.
        val tab = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))[1]
        val cell = bounds(tab)
        val dot = with(compose.density) { Offset(cell.center.x + 10.5.dp.toPx(), cell.top + 6.5.dp.toPx()) }
        tapAndWaitForTheCircle(green)

        // The first frame the circle takes in the lifted dot's top edge.
        val reach = with(compose.density) { 25.dp.toPx() }
        while (reveal.radius.value < reach) compose.mainClock.advanceTimeByFrame()
        var image = shot()
        assertEquals("the picked swatch is already lifted", Color(0xFF34C759).toArgb(), image.at(lifted))
        assertClose("the nav bar's dot is still blue", Color(0xFF007AFF).toArgb(), image.at(dot))

        compose.mainClock.advanceTimeBy(motion.durReveal.toLong())
        image = shot()
        assertClose("the nav bar's dot is green at the end", Color(0xFF34C759).toArgb(), image.at(dot))
    }

    /**
     * 贴纸 → 清玻 → 格栅 → 贴纸 → 格栅 → 清玻 → 贴纸, each ordered pair once: the circle grows from the
     * tapped option; halfway, every point well outside it is the page from before the tap and every
     * point well inside it is the page from the end; then nothing of the old page is left.
     */
    @Test fun everyStyleSpreadsIntoEveryOther() {
        app()
        val step = with(compose.density) { 12.dp.toPx() }
        val edge = with(compose.density) { 3.dp.toPx() }
        // The old page's snapshot leaves out elevation shadows as Robolectric draws it: outside the
        // circle 清玻's glass nav bar loses its soft shadow, so the bar and 28dp round it are skipped there.
        val bar = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
            .map { it.boundsInRoot }.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }.inflate(with(compose.density) { 28.dp.toPx() })
        var from = start.style
        for (to in listOf(UiStyle.Clear, UiStyle.Grid, UiStyle.Sticker, UiStyle.Grid, UiStyle.Clear, UiStyle.Sticker)) {
            val option = compose.onNodeWithText(to.label)
            // The option itself animates its pick, so it is left out of the comparison.
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
                        if (d > r + edge && !bar.contains(p)) {
                            outside++
                            if (mid.at(p) != before.at(p)) wrong += "outside $p ${Integer.toHexString(before.at(p))}→${Integer.toHexString(mid.at(p))}"
                        } else if (d < r - edge) {
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

    @Test fun clearSpreadsFromTheClearOption() {
        app()
        val option = compose.onNodeWithText("清玻")
        val b = bounds(option)
        // The 12dp gap between the 样式 and 深浅色 cards, on the right: the 贴纸 paper, or 清玻 white.
        val heading = bounds(compose.onNodeWithText("深浅色"))
        val gap = with(compose.density) { Offset(390.dp.toPx() - 40.dp.toPx(), heading.top - 16.dp.toPx() - 6.dp.toPx()) }
        // The 贴纸 paper as drawn, washes and all, before the tap.
        val paperCorner = shot().let { it.at(farCorner(b.center, it.width, it.height)) }
        tapAndWaitForTheCircle(option)

        compose.mainClock.advanceTimeBy(motion.durReveal * 3L / 4, ignoreFrameDuration = true)
        var image = shot()
        val corner = farCorner(b.center, image.width, image.height)
        assertEquals("near the option the page is already 清玻 white", ClearLightColors.bg.toArgb(), image.at(gap))
        assertEquals("the far corner still shows the 贴纸 paper", paperCorner, image.at(corner))

        compose.mainClock.advanceTimeBy(motion.durReveal.toLong())
        image = shot()
        // Near the glass nav bar its soft shadow may tint the white by a step.
        assertClose("the far corner is white at the end", LightColors.bg.toArgb(), image.at(corner))
    }
}

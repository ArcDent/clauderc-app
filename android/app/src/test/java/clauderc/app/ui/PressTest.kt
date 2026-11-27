package clauderc.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import clauderc.app.ui.components.ClaudercSheet
import clauderc.app.ui.components.GlassNavBar
import clauderc.app.ui.components.NavTab
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.PressRow
import clauderc.app.ui.theme.listRowPress
import clauderc.app.ui.theme.pressShrink
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.roundToIntRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import clauderc.app.chat.ChatActions
import clauderc.app.chat.ChatScreen
import clauderc.app.chat.ChatUiState
import clauderc.app.chat.ImageViewer
import clauderc.app.chat.Viewing
import clauderc.app.chat.TermUiState
import clauderc.app.chat.TerminalScreen
import clauderc.app.data.UiStyle
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.TermScreen
import clauderc.app.protocol.ToolImage
import clauderc.app.sessions.InkPushDrawerHost
import clauderc.app.sessions.SessionsScreen
import clauderc.app.sessions.SessionsUiState
import clauderc.app.sessions.groupSessions
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import clauderc.app.testing.session
import clauderc.app.ui.components.PillButton
import clauderc.app.ui.components.PillStyle
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.theme.ClaudercMotion
import clauderc.app.ui.theme.ClaudercTheme
import clauderc.app.ui.theme.PressKind
import clauderc.app.ui.theme.Symbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Press feedback with a frozen clock: holding a control visibly reacts in every style
 * (清玻 liquid spread, 印版 mechanical sink, 素卡 soft shrink, 浮墨 elastic shrink) and returns
 * to the exact resting picture once released.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class PressTest {
    @get:Rule val compose = createComposeRule()
    private val exit = ClaudercMotion().durS.toLong()
    private val settle = maxOf(exit, 350L)

    private fun screen(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun changed(a: Bitmap, b: Bitmap, r: IntRect): Int {
        var n = 0
        for (y in r.top until r.bottom) for (x in r.left until r.right) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    private fun assertPressShows(prepare: () -> Unit = {}, wait: Long = settle, find: () -> SemanticsNodeInteraction, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { ClaudercTheme(darkTheme = false) { content() } }
        compose.mainClock.advanceTimeBy(1_000)
        prepare()
        compose.mainClock.advanceTimeBy(1_000)
        val node = find()
        val rest = screen()
        val box = node.fetchSemanticsNode().boundsInRoot.roundToIntRect().intersect(IntRect(0, 0, rest.width, rest.height))
        val probe = IntOffset(box.center.x, box.top + box.height / 10)

        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(150)
        val held = screen()
        assertTrue("pixels changed while held", changed(rest, held, box) > 0)
        assertNotEquals("background color at $probe while held", rest.getPixel(probe.x, probe.y), held.getPixel(probe.x, probe.y))

        node.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(wait)
        assertEquals("pixels still changed once the release ended", 0, changed(rest, screen(), box))
    }

    @Test fun accentPill() = assertPressShows(find = { compose.onNodeWithTag("pill") }) {
        PillButton("允许", onClick = {}, modifier = Modifier.testTag("pill"))
    }

    @Test fun inkPill() = assertPressShows(find = { compose.onNodeWithTag("pill") }) {
        PillButton("添加", onClick = {}, style = PillStyle.Ink, modifier = Modifier.testTag("pill"))
    }

    @Test fun sendKey() = assertPressShows(
        prepare = { compose.onNodeWithTag("composer").performTextInput("跑一遍测试") },
        find = { compose.onNodeWithContentDescription("发送") },
    ) {
        ChatScreen(ChatUiState(loading = false), ChatActions(onSend = { false }))
    }

    @Test fun symbolButton() = assertPressShows(find = { compose.onNodeWithTag("icon") }) {
        SymbolButton(Symbols.Add, "添加目标机", onClick = {}, modifier = Modifier.testTag("icon"))
    }

    @Test fun sessionRow() = assertPressShows(wait = exit, find = { compose.onNodeWithText("周报整理") }) {
        val state = SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(sampleSessions, emptyMap()))
        SessionsScreen(state, {}, {}, {}, { _, _ -> }, {}, {})
    }

    private fun redBounds(bitmap: Bitmap): IntRect {
        var left = bitmap.width
        var top = bitmap.height
        var right = 0
        var bottom = 0
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        for (x in row.indices) if ((row[x] shr 8 and 0xff) < 128) {
                left = minOf(left, x); right = maxOf(right, x + 1)
                top = minOf(top, y); bottom = maxOf(bottom, y + 1)
            }
        }
        return IntRect(left, top, right, bottom)
    }

    @Test fun transformFollowsStyleAndNeverChangesTouchBounds() {
        var style by mutableStateOf(UiStyle.Tile)
        var tier by mutableStateOf(PressKind.Primary)
        var clicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = style) {
                Box(Modifier.fillMaxSize().background(Color.White).padding(40.dp)) {
                    val press = remember { MutableInteractionSource() }
                Box(Modifier.size(240.dp, 110.dp).pressShrink(press, tier).background(Color.Red)
                        .clickable(press, null) { clicks++ }.testTag("pose"))
                }
            }
        }
        val node = compose.onNodeWithTag("pose")
        val density = compose.density.density
        val cases = listOf(
            Triple(UiStyle.Tile, PressKind.Primary, 0.96f to 0.96f),
            Triple(UiStyle.Ink, PressKind.Primary, 0.95f to 0.95f),
            Triple(UiStyle.Clear, PressKind.Primary, 1.04f to 0.93f),
            Triple(UiStyle.Clear, PressKind.Send, 1.06f to 0.90f),
            Triple(UiStyle.Plate, PressKind.Primary, 1f to 1f),
        )
        fun pose() = redBounds(screen())
        for ((look, kind, scales) in cases) {
            updateLook { style = look; tier = kind }
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val restingBitmap = screen()
            val rest = pose()
            node.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(150)
            val held = pose()
            assertEquals("$look width", rest.width * scales.first, held.width.toFloat(), 2f)
            assertEquals("$look height", rest.height * scales.second, held.height.toFloat(), 2f)
            assertEquals(bounds, node.fetchSemanticsNode().boundsInRoot)
            if (look == UiStyle.Plate) {
                assertEquals(rest.left + 2.5f * density, held.left.toFloat(), 1f)
                assertEquals(rest.top + 2.5f * density, held.top.toFloat(), 1f)
            }
            node.performTouchInput { up() }
            compose.mainClock.advanceTimeByFrame()
            val releasing = List(18) { compose.mainClock.advanceTimeBy(16L); pose() }
            when (look) {
                UiStyle.Tile -> assertTrue(releasing.all { it.width in held.width..rest.width })
                UiStyle.Ink -> assertTrue("$rest $releasing", releasing.any { it.width > rest.width })
                UiStyle.Clear -> {
                    // At this density the button's slight overshoot can round back to its resting pixel.
                    val spring = Motion.press(look, kind, false, transform = true).vectorize(Float.VectorConverter)
                    assertTrue(spring.getValueFromNanos(190_000_000L, AnimationVector1D(1f), AnimationVector1D(0f), AnimationVector1D(0f)).value < 0f)
                }
                UiStyle.Plate -> assertTrue(releasing.all { it.top in rest.top..held.top })
            }
            compose.mainClock.advanceTimeBy(350)
            assertEquals(rest, pose())
            assertEquals(0, changed(restingBitmap, screen(), IntRect(0, 0, restingBitmap.width, restingBitmap.height)))
        }
        assertEquals(cases.size, clicks)
    }

    @Test fun plateKeySinksSmoothlyAndTurnsInk() {
        val term = TermUiState(session = session("s-work", "修复重连", SessionState.Idle, "default"), loading = false, screen = TermScreen("s-work", listOf("❯ ")))
        compose.mainClock.autoAdvance = false
        compose.setContent { ClaudercTheme(darkTheme = false, style = UiStyle.Plate) { TerminalScreen(term, onBack = {}, onKey = {}) } }
        compose.mainClock.advanceTimeBy(2_000)
        val key = compose.onNodeWithContentDescription("Esc")
        val box = key.fetchSemanticsNode().boundsInRoot.roundToIntRect()
        val face = IntOffset(box.left + (6 * compose.density.density).toInt(), box.center.y)
        val before = screen().getPixel(face.x, face.y)
        key.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(64)
        val halfway = screen().getPixel(face.x, face.y)
        compose.mainClock.advanceTimeBy(140)
        val held = screen().getPixel(face.x, face.y)
        assertTrue("$before $halfway $held", Color(before).luminance() > Color(halfway).luminance())
        assertTrue(Color(halfway).luminance() > Color(held).luminance())
        assertTrue(Color(held).luminance() < 0.1f)
        assertEquals(box, key.fetchSemanticsNode().boundsInRoot.roundToIntRect())
        key.performTouchInput { cancel() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(400)
        assertEquals(before, screen().getPixel(face.x, face.y))
    }

    @Test fun rowHasInsetRoundedFillOrThreeDpRail() {
        var style by mutableStateOf(UiStyle.Tile)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = style) {
                key(style) {
                    Column(Modifier.fillMaxSize().background(Clauderc.colors.bg).padding(horizontal = 16.dp)) {
                        for (row in listOf(PressRow.Page, PressRow.Choice, PressRow.Timeline)) {
                            Box(Modifier.size(220.dp, 50.dp).listRowPress(row) {}.testTag(row.name))
                        }
                    }
                }
            }
        }
        val density = compose.density.density
        for (look in UiStyle.entries) {
            updateLook { style = look }
            for (row in listOf(PressRow.Page, PressRow.Choice, PressRow.Timeline)) {
                val node = compose.onNodeWithTag(row.name)
                val box = node.fetchSemanticsNode().boundsInRoot.roundToIntRect()
                screen()
                val rest = screen()
                node.performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(150)
                screen()
                val held = screen()
                val y = box.center.y
                if (look == UiStyle.Plate) {
                    val x = when (row) {
                        PressRow.Timeline -> box.right - 1
                        PressRow.Choice -> box.left - (6f * density).toInt()
                        else -> box.left
                    }
                    val dir = if (row == PressRow.Timeline) -1 else 1
                    val ink = held.getPixel(x, y)
                    assertNotEquals(rest.getPixel(x, y), ink)
                    assertEquals(ink, held.getPixel(x + dir * (2.5f * density).toInt(), y))
                    assertNotEquals(ink, held.getPixel(x + dir * (3.5f * density).toInt(), y))
                } else if (row == PressRow.Choice) {
                    val breath = (6 * density).toInt()
                    val beyond = (9 * density).toInt()
                    assertEquals(rest.getPixel(box.left - beyond, y), held.getPixel(box.left - beyond, y))
                    assertNotEquals(rest.getPixel(box.left - breath, y), held.getPixel(box.left - breath, y))
                    assertEquals(rest.getPixel(box.right - 1 + beyond, y), held.getPixel(box.right - 1 + beyond, y))
                    assertNotEquals(rest.getPixel(box.right - 1 + breath, y), held.getPixel(box.right - 1 + breath, y))
                } else {
                    val inset = when (look) {
                        UiStyle.Tile -> 6
                        UiStyle.Ink -> 8
                        else -> 12
                    }
                    val outside = ((inset - 1) * density).toInt()
                    val inside = ((inset + 2) * density).toInt()
                    assertEquals(rest.getPixel(box.left + outside, y), held.getPixel(box.left + outside, y))
                    assertEquals(rest.getPixel(box.left + inside, box.top), held.getPixel(box.left + inside, box.top))
                    assertEquals(rest.getPixel(box.right - 1 - outside, y), held.getPixel(box.right - 1 - outside, y))
                    assertEquals(rest.getPixel(box.right - 1 - inside, box.top), held.getPixel(box.right - 1 - inside, box.top))
                    assertNotEquals("$look $row interior has a fill", rest.getPixel(box.left + inside, y), held.getPixel(box.left + inside, y))
                }
                node.performTouchInput { up() }
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeBy(350)
                val checkBox = IntRect(box.left - (10 * density).toInt(), box.top, box.right + (10 * density).toInt(), box.bottom)
                assertEquals(0, changed(rest, screen(), checkBox))
            }
        }
    }

    @Test fun everyNavStyleRespondsAndRestoresAfterRelease() {
        var style by mutableStateOf(UiStyle.Tile)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = style) {
                Box(Modifier.fillMaxSize().background(Clauderc.colors.bg)) {
                    GlassNavBar(listOf(NavTab("One", Symbols.Forum, Symbols.ForumFilled), NavTab("Two", Symbols.Settings, Symbols.SettingsFilled)), 0, {}, hazeState = null)
                }
            }
        }
        for (look in UiStyle.entries) {
            updateLook { style = look }
            val node = compose.onNodeWithText("Two")
            val rest = screen()
            val box = node.fetchSemanticsNode().boundsInRoot.roundToIntRect()
            node.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(150)
            assertTrue("$look nav cell", changed(rest, screen(), box) > 0)
            node.performTouchInput { up() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(400)
            assertEquals("$look nav cell restored", 0, changed(rest, screen(), box))
        }
    }

    private fun updateLook(update: () -> Unit) {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle(update)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    @Test fun drawerAndViewerBackgroundHaveNoPressPixels() {
        var viewer by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = true, style = UiStyle.Ink) {
                if (viewer) {
                    ImageViewer(Viewing(ToolImage("press", "image/png"), null), whole = null, failed = true, onClosed = {})
                } else {
                    InkPushDrawerHost(open = true, machine = null, dirs = emptyList(), totalSessions = 0,
                        selectedDir = null, onSelectDir = {}, onScanPairAt = {}, onClose = {}) {}
                }
            }
        }
        for ((index, tag) in listOf("inkDrawerCloseTap", "imageViewer").withIndex()) {
            updateLook { viewer = index > 0 }
            compose.mainClock.advanceTimeBy(1_000)
            val node = compose.onNodeWithTag(tag)
            val rest = screen()
            node.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(150)
            assertEquals(tag, 0, changed(rest, screen(), IntRect(0, 0, rest.width, rest.height)))
            node.performTouchInput { cancel() }
        }
    }

    @Test fun tileScrimHasNoPressPixels() = assertScrimDoesNotReact(UiStyle.Tile, "tileScrim")
    @Test fun inkScrimHasNoPressPixels() = assertScrimDoesNotReact(UiStyle.Ink, "morphScrim")
    @Test fun clearScrimHasNoPressPixels() = assertScrimDoesNotReact(UiStyle.Clear, "clearScrim")
    @Test fun plateScrimHasNoPressPixels() = assertScrimDoesNotReact(UiStyle.Plate, "plateScrim")

    private fun assertScrimDoesNotReact(style: UiStyle, tag: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = style) {
                Box(Modifier.fillMaxSize().background(Clauderc.colors.bg))
                ClaudercSheet(onDismiss = {}) { Box(Modifier.size(200.dp, 60.dp)) }
            }
        }
        repeat(20) {
            ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(50)
        }
        val node = compose.onNodeWithTag(tag)
        fun window() = compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        val rest = window()
        node.performTouchInput { down(Offset(5f, 5f)) }
        compose.mainClock.advanceTimeBy(150)
        val held = window()
        assertEquals(tag, 0, changed(rest, held, IntRect(0, 0, rest.width, rest.height)))
        node.performTouchInput { cancel() }
    }

    /** Press tiers differentiate buttons, primary actions, small icons, cards and static targets. */
    @Test fun pressKindsDifferentiateTiers() {
        assertTrue(PressKind.Primary.plateSinkDp > PressKind.Plain.plateSinkDp)
        assertTrue(PressKind.Send.inkScale < PressKind.Card.inkScale)
        assertEquals(1f, PressKind.Still.tileScale, 0f)
        assertEquals(0f, PressKind.Still.plateSinkDp, 0f)
    }

    /** 印版's danger pill sweeps vermilion from right to left on press and restores cleanly on release. */
    @Test fun plateDangerPillSweepsVermilionRightToLeft() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = UiStyle.Plate) {
                PillButton("拒绝", onClick = {}, style = PillStyle.Danger, modifier = Modifier.testTag("denyPill"))
            }
        }
        compose.mainClock.advanceTimeBy(500)
        val node = compose.onNodeWithTag("denyPill")
        val rest = screen()
        val box = node.fetchSemanticsNode().boundsInRoot.roundToIntRect().intersect(IntRect(0, 0, rest.width, rest.height))
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(250)
        val held = screen()
        assertTrue("vermilion sweep fills the pill while held", changed(rest, held, box) > (box.width * box.height) / 3)
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals("pill restores to paper after release", 0, changed(rest, screen(), box))
    }

    /** 印版's Chat working stop button renders its pulsing vermilion square and inverts solid vermilion when held. */
    @Test fun plateWorkingStopButtonInvertsWhenHeld() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = UiStyle.Plate) {
                ChatScreen(ChatUiState(loading = false, state = SessionState.Working, workingSinceMillis = 0), ChatActions())
            }
        }
        compose.mainClock.advanceTimeBy(600)
        val node = compose.onNodeWithTag("plateStopButton")
        val rest = screen()
        val box = node.fetchSemanticsNode().boundsInRoot.roundToIntRect().intersect(IntRect(0, 0, rest.width, rest.height))
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(160)
        val held = screen()
        assertTrue("vermilion stop square visibly reacts on press", changed(rest, held, box) > 50)
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(400)
    }
}

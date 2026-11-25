package clauderc.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import clauderc.app.chat.ChatActions
import clauderc.app.chat.ChatScreen
import clauderc.app.chat.ChatUiState
import clauderc.app.chat.TermUiState
import clauderc.app.chat.TerminalScreen
import clauderc.app.data.UiStyle
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.TermScreen
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
import kotlin.math.abs
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

    /** Bounds of the pill in [style] at rest, held 150ms, [probe]ms after release and settled. */
    private fun pillBounds(style: UiStyle, probe: Long): List<Rect> {
        compose.mainClock.autoAdvance = false
        compose.setContent { ClaudercTheme(darkTheme = false, style = style) { PillButton("允许", onClick = {}, modifier = Modifier.testTag("pill")) } }
        compose.mainClock.advanceTimeBy(1_000)
        val node = compose.onNodeWithTag("pill")
        fun now() = node.fetchSemanticsNode().boundsInRoot
        val rest = now()
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(150)
        val held = now()
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(probe)
        val after = now()
        compose.mainClock.advanceTimeBy(1_000)
        return listOf(rest, held, after, now())
    }

    /** 印版 sinks a primary button diagonally by 2dp with zero squishy scaling and rebounds cleanly. */
    @Test fun plateSinksMechanically() {
        val (rest, held, _, settled) = pillBounds(UiStyle.Plate, 0)
        val sink = 2f * compose.density.density
        assertEquals(rest.width, held.width, 0.5f)
        assertEquals(rest.height, held.height, 0.5f)
        assertEquals(rest.left + sink, held.left, 1f)
        assertEquals(rest.top + sink, held.top, 1f)
        assertEquals(rest, settled)
    }

    /** 清玻 spreads it like a drop, 104% wide and 93% high, and on release overshoots narrow and tall once. */
    @Test fun clearSpreadsLikeLiquid() {
        val (rest, held, mid, settled) = pillBounds(UiStyle.Clear, 160)
        assertEquals(rest.width * 1.04f, held.width, 1f)
        assertEquals(rest.height * 0.93f, held.height, 1f)
        assertTrue("narrower than at rest halfway back: ${mid.width} vs ${rest.width}", mid.width < rest.width)
        assertTrue("taller than at rest halfway back: ${mid.height} vs ${rest.height}", mid.height > rest.height)
        assertEquals(rest, settled)
    }

    /** 素卡 shrinks a pressed primary pill to 96% and springs back. */
    @Test fun tileShrinksSoftly() {
        val (rest, held, _, settled) = pillBounds(UiStyle.Tile, 0)
        assertEquals(rest.width * 0.96f, held.width, 1f)
        assertEquals(rest.height * 0.96f, held.height, 1f)
        assertEquals(rest, settled)
    }

    /** 浮墨 shrinks a pressed primary pill to 95% and springs back. */
    @Test fun inkShrinksElastically() {
        val (rest, held, _, settled) = pillBounds(UiStyle.Ink, 0)
        assertEquals(rest.width * 0.95f, held.width, 1f)
        assertEquals(rest.height * 0.95f, held.height, 1f)
        assertEquals(rest, settled)
    }

    private fun assertBox(what: String, want: Rect, got: Rect) {
        val off = listOf(want.left - got.left, want.top - got.top, want.right - got.right, want.bottom - got.bottom).maxOf { abs(it) }
        assertTrue("$what: $got, want $want", off <= 1.5f)
    }

    /** In 印版, a terminal key sinks 2dp the moment it is hit, turns ink, and comes back up. */
    @Test fun plateKeySinksAtOnceAndTurnsInk() {
        val term = TermUiState(session = session("s-work", "修复重连", SessionState.Idle, "default"), loading = false, screen = TermScreen("s-work", listOf("❯ ")))
        val key = { compose.onNodeWithContentDescription("Esc") }
        compose.mainClock.autoAdvance = false
        compose.setContent { ClaudercTheme(darkTheme = false, style = UiStyle.Plate) { TerminalScreen(term, onBack = {}, onKey = {}) } }
        compose.mainClock.advanceTimeBy(2_000)
        fun now() = key().fetchSemanticsNode().boundsInRoot
        val rest = now()
        val face = IntOffset((rest.left + 4 * compose.density.density).toInt(), rest.center.y.toInt())
        val before = screen().getPixel(face.x, face.y)
        key().performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        val sink = 2 * compose.density.density
        assertBox("one frame into the press", rest.translate(0f, sink), now())
        compose.mainClock.advanceTimeBy(400)
        assertBox("held", rest.translate(0f, sink), now())
        val held = screen().getPixel(face.x, face.y)
        assertTrue("the key turns ink: ${Integer.toHexString(held)} was ${Integer.toHexString(before)}", Color(held).luminance() < 0.1f && Color(before).luminance() > 0.5f)
        key().performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(400)
        assertEquals(rest, now())
        assertEquals("back to paper", before, screen().getPixel(face.x, face.y))
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

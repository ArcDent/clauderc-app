package clauderc.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import clauderc.app.sessions.SessionsScreen
import clauderc.app.sessions.SessionsUiState
import clauderc.app.sessions.groupSessions
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import clauderc.app.ui.components.PillButton
import clauderc.app.ui.components.PillStyle
import clauderc.app.ui.components.SymbolButton
import androidx.compose.ui.unit.dp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.ClaudercMotion
import clauderc.app.ui.theme.ClaudercTheme
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
 * Press feedback with a frozen clock: holding a control changes how it looks, also on the solid ink
 * button and the send key, whose background is the text color; releasing it returns to the exact
 * resting picture once the release ends: the layer fades in one exit duration, and 清玻's drop
 * overshoots and settles in 320ms.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class PressTest {
    @get:Rule val compose = createComposeRule()
    private val exit = ClaudercMotion().durS.toLong()
    private val settle = maxOf(exit, 320L)

    private fun screen(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun changed(a: Bitmap, b: Bitmap, r: IntRect): Int {
        var n = 0
        for (y in r.top until r.bottom) for (x in r.left until r.right) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    /**
     * Compares the element's resting area on whole-screen shots, since the shrink also shrinks the
     * node's own bounds. [probe] sits on the element's background, a tenth of the way down from its
     * top edge, clear of text and icons, so a layer the same color as the background fails there.
     */
    private fun assertPressShows(prepare: () -> Unit = {}, wait: Long = settle, find: () -> SemanticsNodeInteraction, content: @Composable () -> Unit) {
        // Spinners in the session list never settle, so drive the clock by hand.
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

    // A row only lays the layer over itself, so it is back at rest once the layer has faded; waiting
    // longer would catch the 「等你」 dot's pulse in another frame.
    @Test fun sessionRow() = assertPressShows(wait = exit, find = { compose.onNodeWithText("周报整理") }) {
        val state = SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(sampleSessions, emptyMap()))
        SessionsScreen(state, {}, {}, {}, { _, _ -> }, {}, {})
    }

    /** Bounds of the pill in [style] at rest, held 150ms, [probe]ms after release and settled. */
    private fun pillBounds(style: UiStyle, probe: Long): List<androidx.compose.ui.geometry.Rect> {
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

    /** 格栅 shrinks a pressed control to 96% and springs it back. */
    @Test fun gridShrinks() {
        val (rest, held, _, settled) = pillBounds(UiStyle.Grid, 0)
        assertEquals(rest.width * 0.96f, held.width, 1f)
        assertEquals(rest.height * 0.96f, held.height, 1f)
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

    /** 贴纸 sinks it 3dp onto its hard shadow, at full size, and pops it back up. */
    @Test fun stickerSinks() {
        val (rest, held, _, settled) = pillBounds(UiStyle.Sticker, 0)
        val depth = with(compose.density) { 3.dp.toPx() }
        assertEquals(rest.width, held.width, 0.5f)
        assertEquals(rest.top + depth, held.top, 1f)
        assertEquals(rest, settled)
    }
}

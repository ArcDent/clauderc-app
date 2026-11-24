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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import clauderc.app.chat.TermUiState
import clauderc.app.chat.TerminalScreen
import clauderc.app.data.AccentChoice
import clauderc.app.data.Appearance
import clauderc.app.data.ThemeMode
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.TermScreen
import clauderc.app.settings.SettingsScreen
import clauderc.app.testing.session
import clauderc.app.ui.components.TextAction
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.StickerPress
import clauderc.app.ui.theme.accentColor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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

    // ---------- 贴纸: each kind of control has its own pose while pressed (round 12) ----------

    /** The box a rect like [rest] fills once scaled by [scale] and turned [turn]° about its centre. */
    private fun posed(rest: Rect, scale: Float, turn: Float): Rect {
        val a = Math.toRadians(turn.toDouble())
        val cos = abs(cos(a)).toFloat()
        val sin = abs(sin(a)).toFloat()
        val w = scale * (rest.width * cos + rest.height * sin)
        val h = scale * (rest.width * sin + rest.height * cos)
        return Rect(rest.center.x - w / 2, rest.center.y - h / 2, rest.center.x + w / 2, rest.center.y + h / 2)
    }

    private fun assertBox(what: String, want: Rect, got: Rect) {
        val off = listOf(want.left - got.left, want.top - got.top, want.right - got.right, want.bottom - got.bottom).maxOf { abs(it) }
        assertTrue("$what: $got, want $want", off <= 1.5f)
    }

    /**
     * Bounds of [find] in 贴纸, at rest, one frame into a press, held 400ms and 400ms after the press is
     * let go of; [prepare] runs once the page is up, and the press is cancelled so nothing is clicked.
     */
    private fun stickerPress(find: () -> SemanticsNodeInteraction, prepare: () -> Unit = {}, content: @Composable () -> Unit): List<Rect> {
        compose.mainClock.autoAdvance = false
        compose.setContent { ClaudercTheme(darkTheme = false, style = UiStyle.Sticker) { content() } }
        compose.mainClock.advanceTimeBy(1_000)
        prepare()
        compose.mainClock.advanceTimeBy(1_000)
        fun now() = find().fetchSemanticsNode().boundsInRoot
        val rest = now()
        find().performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        val first = now()
        compose.mainClock.advanceTimeBy(400)
        val held = now()
        find().performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(400)
        return listOf(rest, first, held, now())
    }

    /** A pill button presses like a rubber stamp: 96% and 1.5° to the left, then springs back. */
    @Test fun stickerPillPressesLikeAStamp() {
        val (rest, _, held, after) = stickerPress({ compose.onNodeWithTag("pill") }) {
            PillButton("允许", onClick = {}, modifier = Modifier.testTag("pill"))
        }
        assertBox("held", posed(rest, 0.96f, -1.5f), held)
        assertEquals(rest, after)
    }

    /** A text button that is not quiet presses the same way as the pills. */
    @Test fun stickerTextActionPressesLikeAStamp() {
        val (rest, _, held, after) = stickerPress({ compose.onNodeWithTag("text") }) {
            TextAction("全部 6 个", onClick = {}, modifier = Modifier.testTag("text"))
        }
        assertBox("held", posed(rest, 0.96f, -1.5f), held)
        assertEquals(rest, after)
    }

    /** The send button leans far back, to 84% and 14°, like a bow drawn. */
    @Test fun stickerSendLeansBack() {
        val (rest, _, held, after) = stickerPress(
            find = { compose.onNodeWithContentDescription("发送") },
            prepare = { compose.onNodeWithTag("composer").performTextInput("跑一遍测试") },
        ) {
            ChatScreen(ChatUiState(loading = false), ChatActions(onSend = { false }))
        }
        assertBox("held", posed(rest, 0.84f, -14f), held)
        assertEquals(rest, after)
    }

    /** An icon button tilts 10° at 88%. */
    @Test fun stickerIconButtonTilts() {
        val (rest, _, held, after) = stickerPress({ compose.onNodeWithTag("icon") }) {
            SymbolButton(Symbols.Add, "添加目标机", onClick = {}, modifier = Modifier.testTag("icon"))
        }
        assertBox("held", posed(rest, 0.88f, -10f), held)
        assertEquals(rest, after)
    }

    /** A terminal key sinks 2dp the moment it is hit, keeps its size, turns ink, and comes back up. */
    @Test fun stickerKeySinksAtOnceAndTurnsInk() {
        val term = TermUiState(session = session("s-work", "修复重连", SessionState.Idle, "default"), loading = false, screen = TermScreen("s-work", listOf("❯ ")))
        var ink = Color.Unspecified
        val key = { compose.onNodeWithContentDescription("Esc") }
        compose.mainClock.autoAdvance = false
        compose.setContent { ClaudercTheme(darkTheme = false, style = UiStyle.Sticker) { ink = Clauderc.colors.ink; TerminalScreen(term, onBack = {}, onKey = {}) } }
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
        assertEquals("back to white", before, screen().getPixel(face.x, face.y))
    }

    /**
     * An accent swatch squashes to 82% and tilts 10° while pressed; picked, it lands like a stamp,
     * overshooting to 112% before it settles, and its tick springs in.
     */
    @Test fun stickerSwatchSquashesThenLandsLikeAStamp() {
        var look by mutableStateOf(Appearance(UiStyle.Sticker, ThemeMode.Light, AccentChoice.Blue))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = UiStyle.Sticker, accent = accentColor(look.accent, false)) {
                SettingsScreen(look, accentColor(look.accent, false), wallpaperAvailable = true, onStyle = { _, _ -> }, onMode = { _, _ -> }, onAccent = { a, _ -> look = look.copy(accent = a) })
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val swatch = compose.onNodeWithContentDescription("紫色")
        val face = { compose.onNode(hasTestTag("swatchFace") and hasAnyAncestor(hasContentDescription("紫色")), useUnmergedTree = true) }
        fun now() = face().fetchSemanticsNode().boundsInRoot
        val rest = now()
        swatch.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        assertBox("held", posed(rest, 0.82f, -10f), now())
        swatch.performTouchInput { up() }
        // 45% into the 350ms stamp it is at its largest, 112% and turned 6° the other way.
        compose.mainClock.advanceTimeBy(160)
        val stamped = now()
        assertTrue("stamping: ${stamped.width} > ${rest.width}", stamped.width > rest.width * 1.08f)
        assertEquals(AccentChoice.Purple, look.accent)
        compose.mainClock.advanceTimeBy(600)
        assertEquals(rest, now())
    }

    /** Each kind's held pose is its own: what was pressed reads at a glance. */
    @Test fun stickerKindsAreAllDifferent() {
        val poses = StickerPress.entries.map { it.scale to it.turn }
        assertEquals(poses.size, poses.toSet().size)
    }
}

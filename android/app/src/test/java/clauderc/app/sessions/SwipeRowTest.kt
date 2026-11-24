package clauderc.app.sessions

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.cancel
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.testing.branchSessions
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import clauderc.app.ui.theme.ClaudercTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercColors
import kotlin.math.abs

/**
 * Round 6 on the sessions page: a row slides left to 「停止」 or 「移出」 in place of the ⋮ menu, a
 * removed row folds away before the removal goes out, and unfolding forks draws the line first.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwipeRowTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = org.junit.rules.TemporaryFolder()

    private val opened = mutableListOf<String>()
    private val stopped = mutableListOf<String>()
    private val removed = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                SessionsScreen(
                    state = SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(sampleSessions, emptyMap())),
                    onRefresh = {},
                    onOpenChat = { opened += it.id },
                    onCreate = {},
                    onRename = { _, _ -> },
                    onStop = { stopped += it.id },
                    onRemove = { removed += it.id },
                )
            }
        }
    }

    private fun row(id: String) = compose.onNodeWithTag("sessionRow:$id")
    private fun actionOf(id: String) = compose.onAllNodes(hasTestTag("swipeAction") and hasAnyAncestor(hasTestTag("sessionRow:$id"))).fetchSemanticsNodes().size
    private fun slide(id: String) {
        row(id).performTouchInput { swipeLeft() }
        compose.waitForIdle()
    }

    @Test fun rowsHaveNoMenuAndATapOpensTheChat() {
        show()
        compose.onNode(hasContentDescription("会话操作")).assertDoesNotExist()
        compose.onNodeWithText("修复重连").performClick()
        assertEquals(listOf("s-work"), opened)
    }

    @Test fun slidingAnotherRowClosesTheFirst() {
        show()
        slide("s-work")
        assertEquals(1, actionOf("s-work"))
        slide("s-idle")
        assertEquals(0, actionOf("s-work"))
        assertEquals(1, actionOf("s-idle"))
    }

    @Test fun aTapWhileARowIsOpenOnlyClosesIt() {
        show()
        slide("s-work")
        compose.onNodeWithText("周报整理").performClick()
        compose.waitForIdle()
        assertTrue(opened.isEmpty())
        assertEquals(0, actionOf("s-work"))
        compose.onNodeWithText("周报整理").performClick()
        assertEquals(listOf("s-idle"), opened)
    }

    @Test fun stoppingAsksFirst() {
        show()
        slide("s-work")
        compose.onNodeWithContentDescription("停止").performClick()
        compose.onNode(hasText("打断当前任务", substring = true)).assertIsDisplayed()
        assertTrue(stopped.isEmpty())
        compose.onNode(hasText("停止") and !hasText("「", substring = true)).performClick()
        assertEquals(listOf("s-work"), stopped)
    }

    @Test fun aConfirmedRemovalFoldsTheRowAwayBeforeItGoesOut() {
        show()
        slide("s-stop")
        compose.onNodeWithContentDescription("移出").performClick()
        compose.onNodeWithText("移出「landing 页」？").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNode(hasText("移出") and !hasText("「", substring = true) and !hasTestTag("swipeAction")).performClick()
        compose.mainClock.advanceTimeBy(100)
        val h = heightOf(compose.onNodeWithTag("leaving:s-stop"))
        compose.mainClock.advanceTimeBy(64)
        assertTrue("folding: ${heightOf(compose.onNodeWithTag("leaving:s-stop"))} < $h", heightOf(compose.onNodeWithTag("leaving:s-stop")) in 1 until h)
        assertTrue("not sent while it folds", removed.isEmpty())
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf("s-stop"), removed)
        compose.mainClock.autoAdvance = true
    }

    private fun heightOf(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().size.height

    // ---------- round 12: the stop button drawn as each style draws its rewind button ----------

    private class StopShots(val c: ClaudercColors, val density: Float, val cx: Float, val cy: Float, val rest: PixelMap, val held: PixelMap, val after: PixelMap) {
        fun dp(v: Float) = v * density
    }

    /** 修复重连 slid open in [style]: its stop button at rest, just pressed, and once a cancelled press has let go. */
    private fun stopShots(style: UiStyle): StopShots {
        var colors: ClaudercColors? = null
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = style) {
                colors = Clauderc.colors
                SessionsScreen(SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(sampleSessions, emptyMap())), {}, {}, {}, { _, _ -> }, {}, {})
            }
        }
        slide("s-work")
        val button = compose.onNode(hasTestTag("swipeAction") and hasAnyAncestor(hasTestTag("sessionRow:s-work")))
        // No word under it any more; it is read out as 「停止」.
        val node = button.fetchSemanticsNode()
        assertEquals(listOf("停止"), node.config.getOrNull(SemanticsProperties.ContentDescription))
        assertTrue("no word: ${node.config.getOrNull(SemanticsProperties.Text)}", node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
        val d = compose.density.density
        val b = node.boundsInRoot
        compose.mainClock.autoAdvance = false
        val rest = compose.onRoot().captureToImage().toPixelMap()
        button.performTouchInput { down(center) }
        // In the scrolling list the press lands 100ms late; a few frames after that it shows in full, with no
        // transition in (a durS one would be less than half way).
        compose.mainClock.advanceTimeBy(160)
        val held = compose.onRoot().captureToImage().toPixelMap()
        button.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(400)
        val after = compose.onRoot().captureToImage().toPixelMap()
        compose.mainClock.autoAdvance = true
        return StopShots(colors!!, d, b.center.x, b.center.y, rest, held, after)
    }

    private fun PixelMap.at(x: Float, y: Float) = this[x.toInt(), y.toInt()]

    private fun distance(a: Color, b: Color) = (abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)) / 3

    private fun assertNear(what: String, want: Color, got: Color, tolerance: Float = 0.06f) =
        assertTrue("$what: $got, want $want", distance(want, got) < tolerance)

    /** The pixel in the square [r]dp round the button's middle that stands out most from [back]. */
    private fun StopShots.strongest(px: PixelMap, r: Float, back: Color): Color {
        var best = back
        for (x in (cx - dp(r)).toInt() until (cx + dp(r)).toInt()) for (y in (cy - dp(r)).toInt() until (cy + dp(r)).toInt()) {
            if (distance(px[x, y], back) > distance(best, back)) best = px[x, y]
        }
        return best
    }

    /** Pixels in the square [r]dp round the button's middle within reach of [color]. */
    private fun StopShots.count(px: PixelMap, r: Float, color: Color): Int {
        var n = 0
        for (x in (cx - dp(r)).toInt() until (cx + dp(r)).toInt()) for (y in (cy - dp(r)).toInt() until (cy + dp(r)).toInt()) {
            if (distance(px[x, y], color) < 0.2f) n++
        }
        return n
    }

    private fun StopShots.assertBackAtRest() {
        for (x in (cx - dp(24f)).toInt() until (cx + dp(24f)).toInt()) for (y in (cy - dp(24f)).toInt() until (cy + dp(24f)).toInt()) {
            assertEquals("back at rest at ($x, $y)", rest[x, y], after[x, y])
        }
    }

    /** 清玻: the bare red symbol, 24dp, no circle; pressed it shrinks to 82% and turns, and comes back. */
    @Test fun clearsStopButtonIsTheBareRedSymbol() {
        val s = stopShots(UiStyle.Clear)
        val c = s.c
        assertNear("the symbol is red", c.danger, s.strongest(s.rest, 12f, c.bg))
        for ((dx, dy) in listOf(16f to 0f, -16f to 0f, 0f to 16f, 0f to -16f)) assertNear("no circle at ($dx, $dy)", c.bg, s.rest.at(s.cx + s.dp(dx), s.cy + s.dp(dy)), 0.02f)
        val red = s.count(s.rest, 14f, c.danger)
        val pressed = s.count(s.held, 14f, c.danger)
        assertTrue("pressed, the symbol shrinks at once: $pressed of $red red pixels", red > 0 && pressed < red * 0.85f)
        s.assertBackAtRest()
    }

    /** 格栅: a 40dp square in a 1.5dp red line round a red symbol; pressed it fills red under a white symbol. */
    @Test fun gridsStopButtonIsASquareInARedLine() {
        val s = stopShots(UiStyle.Grid)
        val c = s.c
        assertNear("the red line", c.danger, s.rest.at(s.cx - s.dp(19.25f), s.cy))
        assertNear("nothing fills it", c.bg, s.rest.at(s.cx - s.dp(15f), s.cy), 0.02f)
        assertNear("square corners", c.danger, s.rest.at(s.cx - s.dp(19.25f), s.cy - s.dp(19.25f)))
        assertNear("the symbol is red", c.danger, s.strongest(s.rest, 10f, c.bg))
        assertNear("pressed, it fills red at once", c.danger, s.held.at(s.cx - s.dp(15f), s.cy))
        assertNear("pressed, the symbol is white", Color.White, s.strongest(s.held, 10f, c.danger), 0.1f)
        s.assertBackAtRest()
    }

    /** 贴纸: a card-white circle in a 2dp ink line round a red symbol, no shadow; pressed it fills red and tilts smaller. */
    @Test fun stickersStopButtonIsACardCircleInInk() {
        val s = stopShots(UiStyle.Sticker)
        val c = s.c
        assertNear("the ink line", c.ink, s.rest.at(s.cx - s.dp(19f), s.cy))
        assertNear("the card inside", c.card, s.rest.at(s.cx - s.dp(15f), s.cy))
        assertTrue("no shadow under it", distance(s.rest.at(s.cx, s.cy + s.dp(21.5f)), c.ink) > 0.3f)
        assertNear("the symbol is red", c.danger, s.strongest(s.rest, 10f, c.card))
        assertNear("pressed, it fills red at once", c.danger, s.held.at(s.cx - s.dp(12f), s.cy))
        assertNear("pressed, the ink line comes in with the 90% size", c.ink, s.held.at(s.cx - s.dp(17.1f), s.cy))
        assertNear("pressed, the symbol is white", Color.White, s.strongest(s.held, 9f, c.danger), 0.1f)
        s.assertBackAtRest()
    }

    @Test fun unfoldingDrawsTheLineBeforeTheForksGrowAndFoldingIsTheReverse() {
        var folded by mutableStateOf(emptySet<String>())
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                val groups = groupSessions(branchSessions, emptyMap()).map { it.copy(foldedForks = folded) }
                SessionsScreen(SessionsUiState(machine("m-1", "工作室 Mac mini"), groups), {}, {}, {}, { _, _ -> }, {}, {})
            }
        }
        fun line() = compose.onNodeWithTag("treeLine:s1", useUnmergedTree = true).fetchSemanticsNode().size.height
        fun forks() = compose.onNodeWithTag("forks:s1").fetchSemanticsNode().size.height
        val fullLine = line()
        val fullForks = forks()
        assertTrue("line $fullLine forks $fullForks", fullLine > 0 && fullForks > 0)

        compose.mainClock.autoAdvance = false
        // Folding: the forks shrink first while the line stays whole, then the line goes back up.
        folded = setOf("s1")
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        assertEquals(fullLine, line())
        forks().let { assertTrue("forks $it of $fullForks", it in 1 until fullForks) }
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithTag("forks:s1").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(0, line())

        // Unfolding: the line comes down first, the forks still shut; then they grow along it.
        folded = emptySet()
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48)
        val partway = line()
        assertTrue("line on its way: $partway of $fullLine", partway in 1 until fullLine)
        compose.onNodeWithTag("forks:s1").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(150)
        assertEquals(fullLine, line())
        forks().let { assertTrue("forks $it of $fullForks", it in 1 until fullForks) }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(fullForks, forks())
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("收起 1 个 fork").assertExists()
    }

    @Test fun refusedRemovalRestoresTheRowAndInFlightStopHidesRemove() {
        val stopGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val relay = clauderc.app.testing.FakeRelay().apply {
            respond(clauderc.app.protocol.Method.MACHINES_LIST, clauderc.app.protocol.MachinesListResponse.serializer(), clauderc.app.protocol.MachinesListResponse(listOf(machine("m-1", "工作室 Mac mini"))))
            respond(clauderc.app.protocol.Method.SESSIONS_SUBSCRIBE, clauderc.app.protocol.SessionList.serializer(), sampleSessions)
            fail(clauderc.app.protocol.Method.SESSION_REMOVE, clauderc.app.protocol.ErrorCode.INTERNAL, "移出被拒")
            on(clauderc.app.protocol.Method.SESSION_STOP) {
                stopGate.await()
                kotlinx.serialization.json.JsonObject(emptyMap())
            }
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.Job())
        val storeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.Job())
        try {
            val vm = SessionsViewModel("m-1", clauderc.app.data.Fleet(relay, scope), relay, clauderc.app.testing.profileNames(tmp.root, storeScope))
            compose.setContent {
                val s by vm.state.collectAsState()
                ClaudercTheme(darkTheme = false) {
                    SessionsScreen(s, vm::refresh, {}, {}, { _, _ -> }, vm::stop, vm::remove)
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("landing 页")).fetchSemanticsNodes().isNotEmpty() }

            // Remove s-stop: folds away, agent refuses, row comes back whole.
            slide("s-stop")
            compose.onNodeWithContentDescription("移出").performClick()
            compose.onNode(hasText("移出") and !hasText("「", substring = true) and !hasTestTag("swipeAction")).performClick()
            compose.waitUntil(5_000) { relay.sent(clauderc.app.protocol.Method.SESSION_REMOVE).isNotEmpty() }
            compose.waitForIdle()
            assertTrue("refused removal restored row height", heightOf(row("s-stop")) > 0)
            compose.onNodeWithText("landing 页").assertIsDisplayed()

            // Stop s-work: while session.stop is in flight, swiping left must not offer 「移出」.
            slide("s-work")
            compose.onNodeWithContentDescription("停止").performClick()
            compose.onNode(hasText("停止") and !hasText("「", substring = true)).performClick()
            compose.waitForIdle()
            assertTrue("s-work is marked stopping while in flight", "s-work" in vm.state.value.stopping)
            slide("s-work")
            assertEquals("no swipe action while stop is in flight", 0, actionOf("s-work"))
            assertEquals(0, compose.onAllNodes(hasContentDescription("移出")).fetchSemanticsNodes().size)
            stopGate.complete(Unit)
        } finally {
            scope.cancel()
            storeScope.cancel()
        }
    }
}

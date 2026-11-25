package clauderc.app.chat

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import clauderc.app.ChatRoute
import clauderc.app.ClaudercNavHost
import clauderc.app.TermRoute
import clauderc.app.data.Fleet
import clauderc.app.navigateWhenResumed
import clauderc.app.data.UiStyle
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.ChatOpenResponse
import clauderc.app.protocol.ChatRewindRequest
import clauderc.app.protocol.ChatRewindResponse
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.Hunk
import clauderc.app.protocol.Image
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.RewindRestore
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.ToolCall
import clauderc.app.protocol.ToolResult
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.decode
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import clauderc.app.testing.session
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercColors
import clauderc.app.ui.theme.ClaudercTheme
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Round 9: a user message slides left to rewind the session to before it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp")
class ChatRewindTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = org.junit.rules.TemporaryFolder()

    private val target = ChatItem(2, ItemKind.USER, text = "把退避上限改成 60 秒", uuid = "u-2")
    private val edit = ChatItem(
        3, ItemKind.TOOL,
        tool = ToolCall(
            "t1", "Edit", "修改 conn.go", buildJsonObject { put("file_path", "internal/relay/conn.go") },
            ToolResult(patch = listOf(Hunk(41, 1, 41, 1, listOf("-\tif d > 30*time.Second {", "+\tif d > 60*time.Second {")))),
        ),
    )
    private val items = listOf(
        ChatItem(1, ItemKind.ASSISTANT, text = "退避从 1 秒起，每次翻倍。"),
        target,
        edit,
        ChatItem(4, ItemKind.ASSISTANT, text = "改好了。"),
        ChatItem(5, ItemKind.USER, text = "跑一下测试", uuid = "u-5"),
        ChatItem(6, ItemKind.ASSISTANT, text = "通过了。"),
    )

    private fun state(sessionState: SessionState = SessionState.Idle) = ChatUiState(
        session = session("s20", "退避说明", sessionState, "default"), loading = false, items = items, hasMore = false, state = sessionState,
    )

    @Test fun theWholeRowSlidesLeftToLayBareTheButtonUnlessTheSessionIsWorking() {
        var working by mutableStateOf(false)
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state(if (working) SessionState.Working else SessionState.Idle), ChatActions()) } }
        compose.waitForIdle()
        compose.onNodeWithTag("userRow:2").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("rewindAction").assertIsDisplayed()

        compose.runOnUiThread { working = true }
        compose.waitForIdle()
        compose.onNodeWithTag("rewindAction").assertDoesNotExist()
        compose.onNodeWithTag("userRow:5").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("rewindAction").assertDoesNotExist()
    }

    /** Offline the page is read only: no rewind either. While the agent rewinds, its panel is no dialog to open the terminal for. */
    @Test fun noRewindOfflineAndNoDialogRowWhileRewinding() {
        var s by mutableStateOf(state().copy(online = false))
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(s, ChatActions()) } }
        compose.waitForIdle()
        compose.onNodeWithTag("userRow:2").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("rewindAction").assertDoesNotExist()
        compose.runOnUiThread { s = state().copy(unknownDialog = true, rewinding = true) }
        compose.waitForIdle()
        compose.onNodeWithText("会话停在一个对话框上").assertDoesNotExist()
    }

    @Test fun theSheetQuotesTheMessageAndSendsTheWayBackPicked() {
        val asked = mutableListOf<Pair<ChatItem, String>>()
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state(), ChatActions(onRewind = { item, mode -> asked += item to mode })) } }
        compose.waitForIdle()
        compose.onNodeWithTag("userRow:2").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("rewindAction").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("回溯到这条消息之前").assertIsDisplayed()
        compose.onNodeWithTag("rewindQuote").assertIsDisplayed()
        compose.onNode(hasText("把退避上限改成 60 秒") and hasTestTagQuote()).assertIsDisplayed()
        // The turn changed conn.go, so all three ways back are offered.
        compose.onNodeWithText("conn.go +1 −1 改回去").assertIsDisplayed()
        compose.onNodeWithText("只回代码").performClick()
        compose.onNodeWithText("回溯").performClick()
        compose.waitForIdle()
        assertEquals(listOf("u-2" to RewindRestore.CODE), asked.map { it.first.uuid to it.second })
        compose.onNodeWithText("回溯到这条消息之前").assertDoesNotExist()
    }

    @Test fun aTurnThatChangedNoFileOnlyTakesTheConversationBack() {
        val asked = mutableListOf<String>()
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state(), ChatActions(onRewind = { _, mode -> asked += mode })) } }
        compose.waitForIdle()
        compose.onNodeWithTag("userRow:5").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("rewindAction").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("这条消息之后没改过文件，只回对话。对话回去后，这条消息填回输入框。").assertIsDisplayed()
        compose.onNodeWithText("只回代码").assertDoesNotExist()
        compose.onNodeWithText("回溯").performClick()
        compose.waitForIdle()
        assertEquals(listOf(RewindRestore.CONVERSATION), asked)
    }

    /** Claude Code offers the code back for a message whose own turn changed nothing when a later one did. */
    @Test fun aMessageBeforeATurnThatChangedFilesOffersTheCodeToo() {
        val first = ChatItem(1, ItemKind.USER, text = "先讲讲退避", uuid = "u-1")
        val earlier = listOf(first) + items.drop(1)
        assertEquals(listOf("conn.go +1 −1"), filesChangedSince(earlier, first))
        assertEquals(emptyList<String>(), filesChangedSince(items, items[4]))
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state().copy(items = earlier), ChatActions()) } }
        compose.waitForIdle()
        compose.onNodeWithTag("userRow:1").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("rewindAction").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("conn.go +1 −1 改回去").assertIsDisplayed()
        compose.onNodeWithText("只回代码").assertIsDisplayed()
    }

    @Test fun aRewoundMessageTakesTheComposersPlace() {
        var s by mutableStateOf(state())
        val drafts = mutableListOf<String>()
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(s, ChatActions(onDraft = { drafts += it })) } }
        compose.waitForIdle()
        compose.runOnUiThread { s = s.copy(restore = Restore(1, "先写一半的草稿")) }
        compose.waitForIdle()
        compose.onNodeWithText("先写一半的草稿").assertIsDisplayed()
        compose.runOnUiThread { s = s.copy(restore = Restore(2, "把退避上限改成 60 秒", replace = true), items = items.take(1)) }
        compose.waitForIdle()
        compose.onNodeWithText("先写一半的草稿").assertDoesNotExist()
        compose.onNodeWithText("把退避上限改成 60 秒").assertIsDisplayed()
        // Saved as the draft, so leaving the page keeps it.
        assertEquals("把退避上限改成 60 秒", drafts.last())
    }

    private fun hasTestTagQuote() = androidx.compose.ui.test.hasTestTag("rewindQuote")

    // ---------- round 11: where the button sits and how each style draws it ----------

    private fun png(): String {
        val bmp = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(46, 139, 122)) }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun showOnly(message: ChatItem) {
        val list = listOf(message, ChatItem(message.seq + 1, ItemKind.ASSISTANT, text = "好的。"))
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state().copy(items = list), ChatActions()) } }
        compose.waitForIdle()
    }

    /** Slides the user's message [seq] open and gives the rewind button's bounds. */
    private fun openButton(seq: Long): DpRect {
        compose.onNodeWithTag("userRow:$seq").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        return compose.onNodeWithTag("rewindAction").getBoundsInRoot()
    }

    private fun assertSameEdges(what: String, button: DpRect, target: DpRect) {
        assertTrue("$what: the button's top ${button.top} against ${target.top}", abs((button.top - target.top).value) <= 1f)
        assertTrue("$what: the button's bottom ${button.bottom} against ${target.bottom}", abs((button.bottom - target.bottom).value) <= 1f)
    }

    @Test fun underAMessagesPictureTheButtonFacesTheTextBubble() {
        showOnly(ChatItem(1, ItemKind.USER, text = "照这张改", images = listOf(Image("image/png", png())), uuid = "u-1"))
        val button = openButton(1)
        assertSameEdges("a picture and a line", button, compose.onNodeWithTag("userBubble").getBoundsInRoot())
        assertTrue("below the picture", button.top >= compose.onNodeWithTag("userPicture").getBoundsInRoot().bottom)
    }

    @Test fun withOnlyAPictureTheButtonFacesThePicture() {
        showOnly(ChatItem(1, ItemKind.USER, images = listOf(Image("image/png", png())), uuid = "u-1"))
        compose.onNodeWithTag("userBubble").assertDoesNotExist()
        assertSameEdges("only a picture", openButton(1), compose.onNodeWithTag("userPicture").getBoundsInRoot())
    }

    @Test fun withOnlyTextTheButtonIsFlushWithTheBubble() {
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state(), ChatActions()) } }
        compose.waitForIdle()
        val bubble = compose.onNode(hasTestTag("userBubble") and hasAnyAncestor(hasTestTag("userRow:2"))).getBoundsInRoot()
        assertSameEdges("only text", openButton(2), bubble)
    }

    /** What the rewind button looks like in [style]: the page shot half open, fully open, and pressed. */
    private class Looks(
        val colors: ClaudercColors,
        val density: Float,
        val half: PixelMap,
        val full: PixelMap,
        val pressed: PixelMap,
        /** The right edge of the row and the stretch of it the button takes, in pixels. */
        val right: Float,
        val top: Float,
        val bottom: Float,
    ) {
        fun dp(v: Float) = v * density
        val cy get() = (top + bottom) / 2
        /** Where the button's middle is when fully open; half open it is at the row's right edge. */
        val cx get() = right - dp(30f)
    }

    private fun looks(style: UiStyle): Looks {
        var colors: ClaudercColors? = null
        var slop = 0f
        compose.setContent {
            ClaudercTheme(darkTheme = false, style = style) {
                colors = Clauderc.colors
                slop = LocalViewConfiguration.current.touchSlop
                ChatScreen(state(), ChatActions())
            }
        }
        compose.waitForIdle()
        val density = compose.density.density
        val row = compose.onNodeWithTag("userRow:5")
        val right = row.getBoundsInRoot().right.value * density
        // Half open: the finger holds the row 30dp to the left, half the button's room.
        row.performTouchInput {
            down(Offset(width * 0.9f, height / 2f))
            moveBy(Offset(-(slop + 30 * density), 0f))
        }
        compose.waitForIdle()
        val half = compose.onRoot().captureToImage().toPixelMap()
        row.performTouchInput { up() }
        compose.waitForIdle()
        val bounds = openButton(5)
        val full = compose.onRoot().captureToImage().toPixelMap()
        val button = compose.onNodeWithTag("rewindAction")
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        val pressed = compose.onRoot().captureToImage().toPixelMap()
        button.performTouchInput { cancel() }
        return Looks(colors!!, density, half, full, pressed, right, bounds.top.value * density, bounds.bottom.value * density)
    }

    private fun PixelMap.at(x: Float, y: Float) = this[x.toInt(), y.toInt()]

    private fun distance(a: Color, b: Color) = (abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)) / 3

    private fun assertNear(what: String, want: Color, got: Color, tolerance: Float = 0.04f) =
        assertTrue("$what: $got, want $want", distance(want, got) < tolerance)

    /** The pixel in [x0, x1) × [y0, y1) that stands out most from [back]. */
    private fun PixelMap.strongest(x0: Float, x1: Float, y0: Float, y1: Float, back: Color): Color {
        var best = back
        for (x in x0.toInt() until x1.toInt()) for (y in y0.toInt() until y1.toInt()) {
            if (distance(this[x, y], back) > distance(best, back)) best = this[x, y]
        }
        return best
    }

    /**
     * The longest stretch, top to bottom, from the first to the last pixel standing out from [back] by more
     * than [by], over the columns of [x0, x1) × [y0, y1); [across] measures left to right over its rows instead.
     */
    private fun PixelMap.tallest(x0: Float, x1: Float, y0: Float, y1: Float, back: Color, by: Float, across: Boolean = false): Int {
        val xs = x0.toInt() until x1.toInt()
        val ys = y0.toInt() until y1.toInt()
        var most = 0
        for (line in if (across) ys else xs) {
            val hits = (if (across) xs else ys).filter { distance(if (across) this[it, line] else this[line, it], back) > by }
            if (hits.isNotEmpty()) most = maxOf(most, hits.last() - hits.first() + 1)
        }
        return most
    }

    /** The left half of the button half open, at the row's edge, and fully open, 30dp in from it. */
    private fun Looks.leftHalves(): Pair<FloatArray, FloatArray> =
        floatArrayOf(right - dp(25f), right - 1, top, bottom) to floatArrayOf(cx - dp(25f), cx - 1, top, bottom)

    @Test fun tilesButtonIsACardCircleThatGrowsAndFadesInWithTheSlide() {
        val l = looks(UiStyle.Tile)
        val c = l.colors
        assertNear("the card inside", c.card, l.full.at(l.cx, l.cy - l.dp(13f)))
        val (h, f) = l.leftHalves()
        val grown = l.half.tallest(h[0], h[1], h[2], h[3], c.bg, 0.15f).toFloat() / l.full.tallest(f[0], f[1], f[2], f[3], c.bg, 0.15f)
        assertTrue("half open it is ~0.8 of its size: $grown", grown in 0.72f..0.88f)
        assertNear("pressed, the fill is the accent", c.accent, l.pressed.at(l.cx, l.cy - l.dp(13f)))
        assertNear("pressed, the icon is on-accent", c.onAccent, l.pressed.strongest(l.cx - l.dp(9f), l.cx + l.dp(9f), l.cy - l.dp(9f), l.cy + l.dp(9f), c.accent), 0.1f)
    }

    @Test fun platesButtonIsAnEmptySquareInAThinLineThatOnlySlidesIn() {
        val l = looks(UiStyle.Plate)
        val c = l.colors
        // A square as tall as the one-line bubble beside it, its 1.5dp grey line reaching into the corners.
        val half = (l.bottom - l.top) / 2
        val tall = l.full.tallest(l.cx - half, l.cx - half + l.dp(2f), l.top - l.dp(4f), l.bottom + l.dp(4f), c.bg, 0.06f)
        val wide = l.full.tallest(l.cx - half - l.dp(4f), l.cx + half + l.dp(4f), l.top, l.top + l.dp(2f), c.bg, 0.06f, across = true)
        assertTrue("as tall as the bubble: $tall against ${2 * half}", abs(tall - 2 * half) <= l.dp(1f))
        assertTrue("as wide as tall: $wide against $tall", abs(wide - tall) <= l.dp(1f))
        val corner = l.full.strongest(l.cx + half - l.dp(2.5f), l.cx + half, l.cy + half - l.dp(2.5f), l.cy + half, c.bg)
        assertNear("the line in the corner", c.surface2, corner)
        assertNear("nothing fills it", c.bg, l.full.at(l.cx + l.dp(15f), l.cy))
        val icon = l.full.strongest(l.cx - l.dp(10f), l.cx + l.dp(10f), l.cy - l.dp(10f), l.cy + l.dp(10f), c.bg)
        assertTrue("the icon is the second grey, not the text colour: $icon", distance(icon, c.fg2) < distance(icon, c.fg))
        // Half open it is the same, only further right.
        val (h, f) = l.leftHalves()
        val faded = distance(l.half.strongest(h[0], h[1], h[2], h[3], c.bg), c.bg) / distance(l.full.strongest(f[0], f[1], f[2], f[3], c.bg), c.bg)
        assertTrue("half open it does not fade: $faded", faded > 0.9f)
        // Round 12: a press fills the square with the text colour under an icon in the page colour.
        assertNear("pressed, the square fills with the text colour", c.fg, l.pressed.at(l.cx + l.dp(15f), l.cy))
        assertNear("pressed, the line is the text colour too", c.fg, l.pressed.at(l.cx + half - l.dp(0.75f), l.cy))
        assertNear("pressed, the icon is the page colour", c.bg, l.pressed.strongest(l.cx - l.dp(10f), l.cx + l.dp(10f), l.cy - l.dp(10f), l.cy + l.dp(10f), c.fg), 0.06f)
    }

    @Test fun clearsButtonIsTheBareGreyIconFadingInWithTheSlide() {
        val l = looks(UiStyle.Clear)
        val c = l.colors
        // No fill and no ring: round the icon the page shows.
        for ((dx, dy) in listOf(14f to 0f, -14f to 0f, 0f to 14f, 0f to -14f, 14f to 14f, -14f to -14f)) {
            assertNear("the page at ($dx, $dy)", c.bg, l.full.at(l.cx + l.dp(dx), l.cy + l.dp(dy)))
        }
        assertNear("the icon in the second grey", c.fg2, l.full.strongest(l.cx - l.dp(11f), l.cx + l.dp(11f), l.cy - l.dp(11f), l.cy + l.dp(11f), c.bg), 0.06f)
        val (h, f) = l.leftHalves()
        val faded = distance(l.half.strongest(h[0], h[1], h[2], h[3], c.bg), c.bg) / distance(l.full.strongest(f[0], f[1], f[2], f[3], c.bg), c.bg)
        assertTrue("half open it is half faded: $faded", faded in 0.3f..0.75f)
        // Round 12: a press turns the icon the text colour on a round 10% tint of it, 8dp past the icon.
        assertNear("pressed, the icon is the text colour", c.fg, l.pressed.strongest(l.cx - l.dp(11f), l.cx + l.dp(11f), l.cy - l.dp(11f), l.cy + l.dp(11f), c.bg), 0.06f)
        val tint = c.fg.copy(alpha = 0.1f).compositeOver(c.bg)
        for ((dx, dy) in listOf(15f to 0f, -15f to 0f, 0f to 15f, 0f to -15f)) {
            assertNear("pressed, the tint at ($dx, $dy)", tint, l.pressed.at(l.cx + l.dp(dx), l.cy + l.dp(dy)), 0.02f)
        }
        assertNear("pressed, the page past the tint", c.bg, l.pressed.at(l.cx + l.dp(21f), l.cy), 0.02f)
    }

    // ---------- the view model ----------

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher + Job())
    private val relay = FakeRelay()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        relay.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "mac-mini"))))
        relay.respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions)
        relay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = items, state = SessionState.Idle))
    }

    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = { it }, background = dispatcher)

    @Test fun aRewindSendsTheMessagesUuidAndPutsTheMessageBack() = runTest(dispatcher) {
        val vm = viewModel()
        val failed = mutableListOf<String>()
        val collect = backgroundScope.launch { vm.rewindFailed.collect { failed += it } }
        vm.rewind(target, RewindRestore.BOTH)
        val sent = relay.sent(Method.CHAT_REWIND).single()
        assertEquals(ChatRewindRequest("s-need", "u-2", RewindRestore.BOTH), sent.decode(ChatRewindRequest.serializer()))
        assertEquals(120_000L, sent.timeoutMillis)
        val s = vm.state.value
        assertFalse(s.rewinding)
        assertEquals("把退避上限改成 60 秒", s.restore?.text)
        assertTrue(s.restore!!.replace)
        assertTrue(failed.isEmpty())
        collect.cancel()
    }

    @Test fun restoringOnlyTheCodeLeavesTheComposerAndSaysSo() = runTest(dispatcher) {
        val vm = viewModel()
        val before = vm.state.value.restore
        vm.rewind(target, RewindRestore.CODE)
        assertEquals(before, vm.state.value.restore)
        assertEquals("代码已回到这条消息之前", vm.messages.first())
    }

    @Test fun aFailedRewindSaysWhyAndOpensTheTerminalView() = runTest(dispatcher) {
        for (why in listOf(
            "会话正在工作或等你确认，停下来以后再回溯",
            "电脑上的输入框里有还没发出去的字，清空以后再回溯",
            "回溯面板上没有「Restore code」这一项，这条消息之后没改过文件，已按 Esc 退出",
        )) {
            relay.fail(Method.CHAT_REWIND, ErrorCode.REWIND_FAILED, why)
            val vm = viewModel()
            vm.rewind(target, RewindRestore.BOTH)
            assertEquals("没能回溯：$why", vm.rewindFailed.first())
            assertFalse(vm.state.value.rewinding)
            assertNull(vm.state.value.restore?.takeIf { it.replace })
            assertEquals("没能回溯：$why", vm.messages.first())
        }
    }

    @Test fun aRewindThatHappenedShowsWhatWentWrongAfter() = runTest(dispatcher) {
        relay.respond(Method.CHAT_REWIND, ChatRewindResponse.serializer(), ChatRewindResponse("已经回溯，但没能清空电脑上的输入框，原消息还留在那里"))
        val vm = viewModel()
        val failed = mutableListOf<String>()
        val collect = backgroundScope.launch { vm.rewindFailed.collect { failed += it } }
        vm.rewind(target, RewindRestore.BOTH)
        assertTrue(failed.isEmpty())
        assertEquals("把退避上限改成 60 秒", vm.state.value.restore?.text)
        assertEquals("已经回溯，但没能清空电脑上的输入框，原消息还留在那里", vm.messages.first())
        collect.cancel()
    }

    @Test fun rewindingStillSavesTheMessageAsDraftAfterLeaving() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        relay.on(Method.CHAT_REWIND) {
            gate.await()
            clauderc.app.protocol.ProtocolJson.encodeToJsonElement(ChatRewindResponse.serializer(), ChatRewindResponse())
        }
        val storeScope = CoroutineScope(Dispatchers.IO + Job())
        val drafts = clauderc.app.data.DraftStore(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = storeScope) { java.io.File(tmp.root, "drafts.preferences_pb") },
        )
        val noticed = kotlinx.coroutines.CompletableDeferred<String>()
        val store = androidx.lifecycle.ViewModelStore()
        try {
            val vm = androidx.lifecycle.ViewModelProvider(store, object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel("m-1", "s-need", relay, Fleet(relay, scope), scope, compress = { it }, background = dispatcher, drafts = drafts, onNotice = { noticed.complete(it) }) as T
            })[ChatViewModel::class.java]
            vm.rewind(target, RewindRestore.BOTH)
            // User leaves the chat page while rewind is still running.
            store.clear()
            gate.complete(Unit)
            assertEquals("把退避上限改成 60 秒", clauderc.app.testing.eventually { drafts.draft("m-1", "s-need").first { it.isNotEmpty() } })
            assertEquals("已回溯，原消息已放进草稿", clauderc.app.testing.eventually { noticed.await() })
        } finally {
            storeScope.cancel()
        }
    }

    @Test fun aFailedRewindShowsItsReasonOnTheTerminalPage() = runTest(dispatcher) {
        val why = "电脑上的输入框里有还没发出去的字，清空以后再回溯"
        relay.fail(Method.CHAT_REWIND, ErrorCode.REWIND_FAILED, why)
        relay.respond(Method.TERM_OPEN, clauderc.app.protocol.TermScreen.serializer(), clauderc.app.protocol.TermScreen("s-need", listOf("❯ ")))
        val fleet = Fleet(relay, scope)
        val chatVm = ChatViewModel("m-1", "s-need", relay, fleet, scope, compress = { it }, background = dispatcher)
        chatVm.rewind(target, RewindRestore.BOTH)
        val reason = chatVm.rewindFailed.first()
        assertEquals("没能回溯：$why", reason)

        val termVm = TermViewModel("m-1", "s-need", relay, fleet, scope, background = dispatcher, initialNotice = reason)
        compose.setContent {
            val s by termVm.state.collectAsState()
            val snack = androidx.compose.runtime.remember { clauderc.app.ui.components.SnackState() }
            androidx.compose.runtime.LaunchedEffect(termVm) { termVm.messages.collect { snack.show(it) } }
            ClaudercTheme(darkTheme = false) { TerminalScreen(s, onBack = {}, onKey = termVm::key, snack = snack) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("没能回溯：$why").assertIsDisplayed()
    }

    @Test fun failedRewindOnBackgroundAppScopeNavigatesToTerminalOnMainThread() {
        val why = "回溯 90 秒还没做完，已按 Esc 退出"
        val bgRelay = FakeRelay()
        bgRelay.respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "mac-mini"))))
        bgRelay.respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions)
        bgRelay.respond(Method.CHAT_OPEN, ChatOpenResponse.serializer(), ChatOpenResponse(items = items, state = SessionState.Idle))
        bgRelay.fail(Method.CHAT_REWIND, ErrorCode.REWIND_FAILED, why)
        bgRelay.respond(Method.TERM_OPEN, clauderc.app.protocol.TermScreen.serializer(), clauderc.app.protocol.TermScreen("s-need", listOf("❯ ")))

        val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fleet = Fleet(bgRelay, bgScope)
            val chatVm = ChatViewModel("m-1", "s-need", bgRelay, fleet, bgScope, compress = { it }, background = Dispatchers.Default)
            var navigatedOnMain = false
            var termNotice: String? = null
            compose.setContent {
                ClaudercTheme(darkTheme = false) {
                    val nav = rememberNavController()
                    ClaudercNavHost(nav, startDestination = ChatRoute("m-1", "s-need")) {
                        composable<ChatRoute> { entry ->
                            val s by chatVm.state.collectAsState()
                            androidx.compose.runtime.LaunchedEffect(chatVm) {
                                chatVm.rewindFailed.collect { reason ->
                                    navigatedOnMain = android.os.Looper.getMainLooper().isCurrentThread
                                    nav.navigateWhenResumed(entry, TermRoute("m-1", "s-need", notice = reason))
                                }
                            }
                            ChatScreen(s, ChatActions(onRewind = chatVm::rewind))
                        }
                        composable<TermRoute> { entry ->
                            val r = entry.toRoute<TermRoute>()
                            termNotice = r.notice
                            androidx.compose.material3.Text(r.notice.orEmpty())
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { chatVm.state.value.items.isNotEmpty() }
            compose.runOnUiThread { chatVm.rewind(target, RewindRestore.BOTH) }
            compose.waitUntil(5_000) { termNotice != null }
            assertTrue("rewindFailed must navigate on the main thread", navigatedOnMain)
            assertEquals("没能回溯：$why", termNotice)
            compose.onNodeWithText("没能回溯：$why").assertIsDisplayed()
        } finally {
            bgScope.cancel()
        }
    }
}

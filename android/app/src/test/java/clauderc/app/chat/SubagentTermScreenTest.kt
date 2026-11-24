package clauderc.app.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.Command
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.Subagent
import clauderc.app.protocol.TermScreen
import clauderc.app.protocol.ToolCall
import clauderc.app.protocol.ToolResult
import clauderc.app.testing.T0
import clauderc.app.testing.session
import clauderc.app.ui.theme.ClaudercTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Round 2 C: the Agent row, a subagent's page, the terminal view and its ways in. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp")
class SubagentTermScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun agentCall(seq: Long, id: String, desc: String, running: Boolean) = ChatItem(
        seq, ItemKind.TOOL,
        tool = ToolCall(
            "toolu_$seq", "Agent", "Agent", buildJsonObject { put("description", desc); put("subagent_type", "Explore") },
            ToolResult("Async agent launched successfully."),
            agent = Subagent(id, "Explore", desc, running, T0),
        ),
    )

    private fun chatState(items: List<ChatItem>, state: SessionState = SessionState.Idle) = ChatUiState(
        session = session("s-work", "修复重连", state, "default"),
        loading = false,
        items = items,
        state = state,
        commands = listOf(Command("compact", "压缩对话上下文"), Command("mcp", "管理 MCP 服务器", panel = true)),
    )

    private fun show(state: ChatUiState, actions: ChatActions = ChatActions()) {
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, actions) } }
    }

    @Test fun anAgentCallIsASubagentRowThatSpinsWhileItRunsAndOpensItsPage() {
        val opened = mutableListOf<Subagent>()
        var state by mutableStateOf(chatState(listOf(agentCall(1, "a1", "读一遍 relay 的连接管理", running = true))))
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, ChatActions(onOpenAgent = { opened += it })) } }
        compose.onNodeWithText("子代理 ·").assertIsDisplayed()
        compose.onNodeWithText("读一遍 relay 的连接管理").assertIsDisplayed()
        compose.onNodeWithTag("agentSpinner", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("agentRow").performClick()
        assertEquals(listOf("a1"), opened.map { it.id })

        state = chatState(listOf(agentCall(1, "a1", "读一遍 relay 的连接管理", running = false)))
        compose.waitForIdle()
        compose.onNodeWithTag("agentSpinner", useUnmergedTree = true).assertDoesNotExist()
        // A stopped session's subagent cannot be running either.
        state = chatState(listOf(agentCall(1, "a1", "读一遍 relay 的连接管理", running = true)), SessionState.Stopped)
        compose.waitForIdle()
        compose.onNodeWithTag("agentSpinner", useUnmergedTree = true).assertDoesNotExist()
    }

    private val task = ChatItem(1, ItemKind.NOTICE, title = "任务", text = "读一遍 internal/relay 里连接的建立、心跳和断开。")

    @Test fun aSubagentPageIsReadOnlyCountsWhileItRunsAndSaysWhenItEnded() {
        var state by mutableStateOf(chatState(listOf(task, ChatItem(2, ItemKind.ASSISTANT, text = "先找重连在哪里触发。")), SessionState.Working))
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                AgentScreen(state, AgentHeader("读一遍 relay 的连接管理", "Explore", System.currentTimeMillis() - 12_000), ChatActions())
            }
        }
        compose.onNodeWithText("读一遍 relay 的连接管理").assertIsDisplayed()
        compose.onNode(hasText("Explore", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("任务").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertDoesNotExist()
        compose.onNodeWithText("中断").assertDoesNotExist()
        compose.onNode(hasText("工作中 · 1", substring = true)).assertIsDisplayed()
        compose.onNodeWithText(AGENT_ENDED).assertDoesNotExist()

        state = state.copy(state = SessionState.Idle)
        compose.waitForIdle()
        compose.onNodeWithText(AGENT_ENDED).assertIsDisplayed()
        compose.onNodeWithTag("agentWorking").assertDoesNotExist()
    }

    private val long = "─".repeat(101) + " e2e-probe ─"
    private val prompt = TermScreen(
        "s-work",
        List(40) { r -> when (r) { 1 -> " ▐▛███▛█   Claude Code v2.1.326"; 35 -> long; 36 -> "❯ abc"; else -> "" } },
        cursorRow = 36, cursorCol = 5, cursor = true,
    )

    @Test fun theTerminalShowsTheScreenUnwrappedWithItsCursorAndSendsKeys() {
        val keys = mutableListOf<String>()
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                TerminalScreen(TermUiState(session = session("s-work", "修复重连", SessionState.Idle, "default"), loading = false, screen = prompt), onBack = {}, onKey = { keys += it })
            }
        }
        val text = compose.onNodeWithTag("termText").fetchSemanticsNode()
        val shown = text.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(shown.contains("Claude Code v2.1.326") && shown.contains("❯ abc") && shown.contains(long))
        // The long rule stays one line, wider than the phone, and scrolls sideways.
        val width = compose.onRoot().fetchSemanticsNode().size.width
        assertTrue("text ${text.size.width}px is wider than the screen ${width}px", text.size.width > width)

        compose.onNodeWithContentDescription("上").performClick()
        compose.onNodeWithContentDescription("回车").performClick()
        compose.onNodeWithContentDescription("Esc").performClick()
        compose.onNodeWithContentDescription("3").performClick()
        assertEquals(listOf("up", "enter", "esc", "3"), keys)
    }

    // At the phone's density each line is laid out a fraction of a pixel taller than 11sp × 1.4; summed
    // over a screen that put the last line half under the key bar.
    @Test @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun theLastLineWithTextIsScrolledFullyIntoView() {
        val tall = TermScreen("s-work", List(60) { r -> if (r == 59) "  accept edits on (shift+tab to cycle)" else "第 $r 行" })
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                TerminalScreen(TermUiState(session = session("s-work", "修复重连", SessionState.Idle, "default"), loading = false, screen = tall), onBack = {}, onKey = {})
            }
        }
        compose.waitForIdle()
        val text = compose.onNodeWithTag("termText").fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        text.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        val lastBottom = text.positionInRoot.y + layouts.single().getLineBottom(59)
        val viewport = compose.onNodeWithTag("termViewport").fetchSemanticsNode().boundsInRoot
        assertTrue("last line ends at ${lastBottom}px, below the view's ${viewport.bottom}px", lastBottom <= viewport.bottom)
        assertTrue("scrolled to the end: last line ends at ${lastBottom}px, view ${viewport.bottom}px", lastBottom > viewport.bottom - 100)
    }

    @Test fun theCursorCellIsInvertedEvenPastTheLinesEnd() {
        val fg = Color.Black
        val bg = Color.White
        val at = termText(prompt, fg, bg)
        val cursor = at.spanStyles.single { it.item.background != Color.Unspecified }
        val start = at.text.indexOf("❯ abc") + "❯ abc".length
        assertEquals("a cell past the end is a space", " ", at.text.substring(cursor.start, cursor.end))
        assertEquals(start, cursor.start)
        assertEquals(fg, cursor.item.background)
        // Columns count characters: a wide one is one.
        val wide = TermScreen("s", listOf("中文ab"), cursorRow = 0, cursorCol = 2, cursor = true)
        val w = termText(wide, fg, bg)
        assertEquals("a", w.text.substring(w.spanStyles.single().start, w.spanStyles.single().end))
        assertTrue(termText(wide.copy(cursor = false), fg, bg).spanStyles.isEmpty())
        // The message dot is drawn plain, one character as before.
        assertEquals("● 我先看一下。", termText(TermScreen("s", listOf("⏺ 我先看一下。")), fg, bg).text)
        // A status line's sun asks for its plain glyph, with or without the emoji selector, and the
        // selector added before the cursor does not move it.
        assertEquals("☀︎ 52.3k", termText(TermScreen("s", listOf("☀️ 52.3k")), fg, bg).text)
        assertEquals("☀︎ 52.3k", termText(TermScreen("s", listOf("☀ 52.3k")), fg, bg).text)
        val sun = termText(TermScreen("s", listOf("☀ ab"), cursorRow = 0, cursorCol = 2, cursor = true), fg, bg)
        assertEquals("☀︎ ab", sun.text)
        assertEquals("a", sun.text.substring(sun.spanStyles.single().start, sun.spanStyles.single().end))
    }

    @Test fun blockElementsFillTheirCellsInsteadOfBeingDrawnByTheFont() {
        // Claude Code's logo: ▐▛███▛█ over ▝▜██████▀.
        assertEquals(listOf(listOf(.5f, 0f, 1f, 1f)), blockShape('▐')!!.rects.map { it.toList() })
        assertEquals(3, blockShape('▛')!!.rects.size)
        assertEquals(listOf(listOf(0f, 0f, 1f, 1f)), blockShape('█')!!.rects.map { it.toList() })
        assertEquals(listOf(listOf(0f, .5f, 1f, 1f)), blockShape('▄')!!.rects.map { it.toList() })
        assertEquals(listOf(listOf(0f, 0f, .5f, 1f)), blockShape('▌')!!.rects.map { it.toList() })
        assertEquals(.5f, blockShape('▒')!!.alpha)
        assertEquals(null, blockShape('─'))
        assertEquals(null, blockShape('a'))
        val logo = termText(TermScreen("s", listOf(" ▐▛███▛█   Claude Code")), Color.Black, Color.White)
        val hidden = logo.spanStyles.single()
        assertEquals("▐▛███▛█", logo.text.substring(hidden.start, hidden.end))
        assertEquals(Color.Transparent, hidden.item.color)
    }

    @Test fun aStoppedSessionsTerminalSaysItExited() {
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                TerminalScreen(TermUiState(session = session("s-work", "修复重连", SessionState.Stopped, "default"), loading = false, screen = prompt), onBack = {}, onKey = {})
            }
        }
        compose.onNodeWithText("会话已退出").assertIsDisplayed()
    }

    @Test fun aPanelCommandIsSentAsIsAndOpensTheTerminal() {
        val sent = mutableListOf<String>()
        var terms = 0
        show(chatState(emptyList()), ChatActions(onSend = { sent += it; true }, onOpenTerm = { terms++ }))
        compose.onNodeWithTag("composer").performTextInput("/mc")
        compose.onNodeWithContentDescription("会打开终端", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("管理 MCP 服务器").performClick()
        assertEquals(listOf("/mcp"), sent)
        assertEquals(1, terms)
        assertEquals("", compose.onNodeWithTag("composer").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test fun theBarAndAnUnknownDialogOpenTheTerminal() {
        var terms = 0
        var state by mutableStateOf(chatState(emptyList()))
        compose.setContent { ClaudercTheme(darkTheme = false) { ChatScreen(state, ChatActions(onOpenTerm = { terms++ })) } }
        compose.onNodeWithText("会话停在一个对话框上").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开终端").performClick()
        assertEquals(1, terms)

        state = state.copy(unknownDialog = true)
        compose.waitForIdle()
        compose.onNodeWithText("会话停在一个对话框上").assertIsDisplayed()
        compose.onNodeWithTag("blockedRow").assertIsDisplayed()
        compose.onNodeWithText("打开终端").performClick()
        assertEquals(2, terms)
    }

    @Test @Config(qualifiers = "w390dp-h844dp-xxhdpi")
    fun newOutputDoesNotYankTerminalBackToBottomWhenScrolledUp() {
        var screen by mutableStateOf(TermScreen("s-work", List(60) { r -> "第 $r 行" }))
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                TerminalScreen(TermUiState(session = session("s-work", "修复重连", SessionState.Idle, "default"), loading = false, screen = screen), onBack = {}, onKey = {})
            }
        }
        compose.waitForIdle()
        val atBottomY = compose.onNodeWithTag("termText").fetchSemanticsNode().positionInRoot.y

        // Scroll up to read older lines.
        repeat(3) {
            compose.onNodeWithTag("termViewport").performTouchInput { swipeDown() }
            compose.waitForIdle()
        }
        val scrolledUpY = compose.onNodeWithTag("termText").fetchSemanticsNode().positionInRoot.y
        assertTrue("scrolled up toward top ($scrolledUpY > $atBottomY)", scrolledUpY > atBottomY + 50f)

        // More lines arrive at the bottom: view stays where the user scrolled instead of jumping to bottom.
        compose.runOnUiThread { screen = TermScreen("s-work", List(75) { r -> "第 $r 行" }) }
        compose.waitForIdle()
        val afterOutputY = compose.onNodeWithTag("termText").fetchSemanticsNode().positionInRoot.y
        assertEquals("did not jump back to bottom on new output", scrolledUpY, afterOutputY, 2f)
    }
}

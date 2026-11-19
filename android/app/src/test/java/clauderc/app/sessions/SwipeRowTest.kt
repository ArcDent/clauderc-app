package clauderc.app.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
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

/**
 * Round 6 on the sessions page: a row slides left to 「停止」 or 「移出」 in place of the ⋮ menu, a
 * removed row folds away before the removal goes out, and unfolding forks draws the line first.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h1400dp")
class SwipeRowTest {
    @get:Rule val compose = createComposeRule()

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
        compose.onNodeWithText("停止").performClick()
        compose.onNode(hasText("打断当前任务", substring = true)).assertIsDisplayed()
        assertTrue(stopped.isEmpty())
        compose.onNode(hasText("停止") and !hasText("「", substring = true)).performClick()
        assertEquals(listOf("s-work"), stopped)
    }

    @Test fun aConfirmedRemovalFoldsTheRowAwayBeforeItGoesOut() {
        show()
        slide("s-stop")
        compose.onNodeWithText("移出").performClick()
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
}

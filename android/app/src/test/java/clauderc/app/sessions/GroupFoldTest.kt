package clauderc.app.sessions

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.data.Fleet
import clauderc.app.protocol.MachinesListResponse
import clauderc.app.protocol.Method
import clauderc.app.protocol.SessionList
import clauderc.app.testing.FakeRelay
import clauderc.app.testing.machine
import clauderc.app.testing.profileNames
import clauderc.app.testing.sampleSessions
import clauderc.app.ui.theme.ClaudercTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A folded profile group keeps only its header, with the counts, and stays folded when the page is rebuilt. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h1400dp")
class GroupFoldTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val relay = FakeRelay().apply {
        respond(Method.MACHINES_LIST, MachinesListResponse.serializer(), MachinesListResponse(listOf(machine("m-1", "工作室 Mac mini"))))
        respond(Method.SESSIONS_SUBSCRIBE, SessionList.serializer(), sampleSessions)
    }
    private val fleetScope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())

    @After fun tearDown() {
        fleetScope.cancel()
        storeScope.cancel()
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun foldShowsCountsAndSurvivesRebuild() {
        val fleet = Fleet(relay, fleetScope)
        val store = profileNames(tmp.root, storeScope)
        var vm by mutableStateOf(SessionsViewModel("m-1", fleet, relay, store))
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                val current = vm
                val state by current.state.collectAsState()
                SessionsScreen(state, {}, {}, {}, {}, { _, _ -> }, {}, {}, onToggle = current::toggle)
            }
        }
        compose.waitUntil(5_000) { has("迁移脚本审查") }

        compose.onNodeWithContentDescription("折叠api.anthropic.com档位").performClick()
        compose.waitUntil(5_000) { !has("迁移脚本审查") }
        compose.onNodeWithText("2 个会话 · 1 个等你").assertIsDisplayed()
        compose.onNodeWithText("周报整理").assertIsDisplayed()

        // A new view model reads the fold back from the phone's settings.
        compose.runOnUiThread { vm = SessionsViewModel("m-1", fleet, relay, store) }
        compose.waitUntil(5_000) { has("2 个会话 · 1 个等你") && !has("迁移脚本审查") }

        compose.onNodeWithContentDescription("展开api.anthropic.com档位").performClick()
        compose.waitUntil(5_000) { has("迁移脚本审查") }
        compose.onNodeWithText("迁移脚本审查").assertIsDisplayed()
    }
}

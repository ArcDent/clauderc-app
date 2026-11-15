package clauderc.app.sessions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.machines.MachineRow
import clauderc.app.machines.MachinesScreen
import clauderc.app.machines.MachinesUiState
import clauderc.app.net.RelayLink
import clauderc.app.net.ConnectionState
import clauderc.app.protocol.Session
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import clauderc.app.ui.theme.ClaudercTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tasks 12.1, 12.2 and 12.4: what the machine and session pages show and do. */
@RunWith(AndroidJUnit4::class)
class ListScreensTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun machines() {
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                MachinesScreen(
                    MachinesUiState(
                        relays = listOf(RelayLink("wss://relay.example.com", "relay.example.com", ConnectionState.Connected)),
                        rows = listOf(
                            MachineRow(machine("m-1", "工作室 Mac mini"), sessions = 6, pending = 2),
                            MachineRow(machine("m-2", "dev-linux", online = false), sessions = null, pending = 0),
                        ),
                    ),
                    onOpen = { opened += it },
                    onAdd = {},
                )
            }
        }
    }

    @Test fun machinesShowOnlineCountsAndOfflineLastSeen() {
        machines()
        compose.onNodeWithText("relay.example.com").assertIsDisplayed()
        compose.onNodeWithText("6 个会话 · 2 个等你").assertIsDisplayed()
        compose.onNode(hasText("离线 · 最后在线", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("工作室 Mac mini").performClick()
        assertEquals(listOf("m-1"), opened)
    }

    @Test fun tappingAnOfflineMachineOnlyShowsAHint() {
        machines()
        compose.onNodeWithText("dev-linux").performClick()
        compose.onNodeWithText("dev-linux 已离线，联网后才能查看会话").assertIsDisplayed()
        assertTrue(opened.isEmpty())
    }

    private var refreshes = 0
    private val stopped = mutableListOf<Session>()

    private fun sessions() {
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                SessionsScreen(
                    state = SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(sampleSessions, emptyMap())),
                    onRefresh = { refreshes++ },
                    onOpenChat = {},
                    onCreate = {},
                    onOpenGit = {},
                    onRename = { _, _ -> },
                    onStop = { stopped += it },
                    onRemove = {},
                )
            }
        }
    }

    @Test fun groupsShowWithEmptyProfileAndNoCreateInOther() {
        sessions()
        compose.onNodeWithText("默认").assertIsDisplayed()
        compose.onNodeWithContentDescription("在默认档位新建会话").assertIsDisplayed()
        compose.onNodeWithTag("sessionList").performScrollToNode(hasText("读不到启动参数"))
        compose.onNodeWithText("没有会话").assertIsDisplayed()
        compose.onNodeWithText("读不到启动参数").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithContentDescription("在其他档位新建会话").fetchSemanticsNodes().size)
    }

    @Test fun pullingDownRefreshes() {
        sessions()
        compose.onNodeWithTag("sessionList").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, refreshes)
    }

    @Test fun stoppingAWorkingSessionWarnsAboutInterruptingIt() {
        sessions()
        // Open the menu of the working session, 修复重连.
        compose.onNodeWithTag("sessionList").performScrollToNode(hasText("修复重连"))
        compose.onNode(hasContentDescription("会话操作") and hasAnySibling(hasText("修复重连", substring = true))).performClick()
        compose.onNodeWithText("停止").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("移出列表")).fetchSemanticsNodes().size)
        compose.onNodeWithText("停止").performClick()
        compose.onNode(hasText("打断当前任务", substring = true)).assertIsDisplayed()
        compose.onAllNodes(hasText("停止"))[0].assertIsDisplayed()
        compose.onNode(hasText("停止") and !hasText("「", substring = true)).performClick()
        assertEquals(listOf("s-work"), stopped.map { it.id })
    }

    @Test fun stoppedSessionMenuOffersRemoveInsteadOfStop() {
        sessions()
        compose.onNodeWithTag("sessionList").performScrollToNode(hasText("landing 页"))
        compose.onNode(hasContentDescription("会话操作") and hasAnySibling(hasText("landing 页", substring = true))).performClick()
        compose.onNodeWithText("移出列表").assertIsDisplayed()
        compose.onNodeWithText("发消息时自动唤醒").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("停止")).fetchSemanticsNodes().size)
    }

    @Test fun createFormShowsMissingDirectoryAndRecentDirs() {
        val picked = mutableListOf<String>()
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                CreateSessionScreen(
                    state = CreateUiState(
                        profileTitle = "官方",
                        profileHost = "api.anthropic.com",
                        templateName = "迁移脚本审查",
                        dir = "/Users/me/github/clauderc-ap",
                        recent = listOf("/Users/me/github/infra", "/Users/me/notes"),
                        dirError = "目标机上没有这个目录",
                    ),
                    onClose = {}, onSubmit = {}, onDir = {}, onPickDir = { picked += it },
                    onShowAllDirs = {}, onName = {}, onPrompt = {},
                )
            }
        }
        compose.onNodeWithText("目标机上没有这个目录").assertIsDisplayed()
        compose.onNode(hasText("沿用「迁移脚本审查」的启动参数", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("~/notes").performClick()
        assertEquals(listOf("/Users/me/notes"), picked)
    }

    @Test fun createButtonIsDisabledWithoutADirectory() {
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                CreateSessionScreen(
                    state = CreateUiState(profileTitle = "默认"),
                    onClose = {}, onSubmit = {}, onDir = {}, onPickDir = {}, onShowAllDirs = {}, onName = {}, onPrompt = {},
                )
            }
        }
        compose.onNode(hasText("启动") and !hasContentDescription("x")).assertIsNotEnabled()
    }
}

package clauderc.app.sessions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.machines.MachineRow
import clauderc.app.machines.MachinesScreen
import clauderc.app.machines.MachinesUiState
import clauderc.app.net.RelayLink
import clauderc.app.net.ConnectionState
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionContext
import clauderc.app.protocol.SessionList
import clauderc.app.protocol.SessionState
import clauderc.app.testing.machine
import clauderc.app.testing.sampleProfiles
import clauderc.app.testing.sampleSessions
import clauderc.app.testing.session
import clauderc.app.ui.theme.Clauderc
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
                    onRename = { _, _ -> },
                    onStop = { stopped += it },
                    onRemove = {},
                )
            }
        }
    }

    @Test fun groupsShowWithEmptyProfileAndOneCreateButtonInTheBar() {
        sessions()
        compose.onNodeWithText("默认").assertIsDisplayed()
        // Round 7: one 「+」 in the bar; the profile is picked on the form, so no group header has one.
        compose.onNodeWithContentDescription("新建会话").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithContentDescription("档位新建会话", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("sessionList").performScrollToNode(hasText("读不到启动参数"))
        compose.onNodeWithText("没有会话").assertIsDisplayed()
        compose.onNodeWithText("读不到启动参数").assertIsDisplayed()
    }

    @Test fun pullingDownRefreshes() {
        sessions()
        compose.onNodeWithTag("sessionList").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, refreshes)
    }

    @Test fun stoppingAWorkingSessionWarnsAboutInterruptingIt() {
        sessions()
        // Slide the working session, 修复重连, open: 「停止」 comes in, not 「移出」.
        compose.onNodeWithTag("sessionList").performScrollToNode(hasText("修复重连"))
        compose.onNodeWithTag("sessionRow:s-work").performTouchInput { swipeLeft() }
        compose.onNodeWithText("停止").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("移出")).fetchSemanticsNodes().size)
        compose.onNodeWithText("停止").performClick()
        compose.onNode(hasText("打断当前任务", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("停止") and !hasText("「", substring = true)).performClick()
        assertEquals(listOf("s-work"), stopped.map { it.id })
    }

    /** The row's second line is the state and Claude Code's one-line progress; the context moved up beside the time. */
    @Test fun rowsShowStateAndProgress() {
        var accent = Color.Unspecified
        val list = SessionList(
            sessions = listOf(
                session("s-ask", "collector 切换", SessionState.Idle, "default", minutesAgo = 3, context = SessionContext(370_000, 1_000_000))
                    .copy(jobState = "blocked", detail = "是否现在开始 collector 和 feed 切换？"),
                session("s-run", "诺连节点", SessionState.Working, "default", minutesAgo = 0).copy(jobState = "working", detail = "正在查 22:04 订阅更新改了什么"),
                session("s-done", "实验报告", SessionState.Idle, "default", minutesAgo = 120).copy(jobState = "done"),
            ),
            profiles = sampleProfiles,
        )
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                accent = Clauderc.colors.accent
                SessionsScreen(
                    state = SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(list, emptyMap())),
                    onRefresh = {}, onOpenChat = {}, onCreate = {}, onRename = { _, _ -> }, onStop = {}, onRemove = {},
                )
            }
        }
        compose.onNodeWithText("上下文 37% · ", substring = true).assertIsDisplayed()
        val ask = compose.onNodeWithText("需要你回复 · 是否现在开始 collector 和 feed 切换？").assertIsDisplayed()
        // The clickable row merges its texts: the name, the time and this line.
        val text = ask.fetchSemanticsNode().config[SemanticsProperties.Text].first { it.text.startsWith("需要你回复") }
        val word = text.spanStyles.single { it.start == 0 }
        assertEquals("需要你回复", text.text.substring(word.start, word.end))
        assertEquals(accent, word.item.color)
        compose.onNodeWithText("工作中 · 正在查 22:04 订阅更新改了什么").assertIsDisplayed()
        // Without progress the line is the state alone.
        compose.onNodeWithTag("sessionList").performScrollToNode(hasText("已完成"))
        compose.onNodeWithText("已完成").assertIsDisplayed()
    }

    @Test fun aStoppedSessionSlidesOpenToRemoveInsteadOfStop() {
        sessions()
        compose.onNodeWithTag("sessionList").performScrollToNode(hasText("landing 页"))
        compose.onNodeWithTag("sessionRow:s-stop").performTouchInput { swipeLeft() }
        compose.onNodeWithText("移出").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("停止")).fetchSemanticsNodes().size)
    }

    @Test fun createFormMarksAMissingDirectoryWithTheFloatingCard() {
        val picked = mutableListOf<String>()
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                CreateSessionScreen(
                    state = CreateUiState(
                        dir = "/Users/me/github/clauderc-ap",
                        recent = listOf("/Users/me/github/infra", "/Users/me/notes"),
                        presets = presetOptions(sampleSessions, emptyMap()),
                        badFields = setOf(CreateField.Dir),
                        note = FormNote("目标机上没有这个目录", 1),
                    ),
                    onClose = {}, onSubmit = {}, onDir = {}, onPickDir = { picked += it },
                    onShowAllDirs = {}, onName = {}, onPrompt = {},
                )
            }
        }
        compose.onNodeWithTag("formNote").assertIsDisplayed()
        compose.onNodeWithText("目标机上没有这个目录").assertIsDisplayed()
        // The profile is picked on the form now; nothing says which profile's flags it copies.
        assertEquals(0, compose.onAllNodes(hasText("启动参数", substring = true)).fetchSemanticsNodes().size)
        compose.onNodeWithText("~/notes").performClick()
        assertEquals(listOf("/Users/me/notes"), picked)
    }

    @Test fun createFormListsTheProfilesAndShowsAddressAndKeyOnlyForCustom() {
        var state by mutableStateOf(CreateUiState(dir = "/Users/me/notes", presets = presetOptions(sampleSessions, emptyMap())))
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                CreateSessionScreen(
                    state, onClose = {}, onSubmit = {}, onDir = {}, onPickDir = {}, onShowAllDirs = {}, onName = {}, onPrompt = {},
                    onPreset = { state = state.copy(preset = it) }, onToggleKey = { state = state.copy(keyShown = !state.keyShown) },
                )
            }
        }
        for (t in listOf("默认", "api.anthropic.com", "anyrouter.top", "自己填", "账户登录")) compose.onNodeWithText(t).performScrollTo().assertIsDisplayed()
        assertEquals("「其他」 cannot start sessions", 0, compose.onAllNodes(hasText("其他")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("接口地址")).fetchSemanticsNodes().size)
        compose.onNodeWithText("自己填").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("接口地址").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("显示 key").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("隐藏 key").assertIsDisplayed()
    }

    @Test fun createButtonIsDisabledWithoutADirectory() {
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                CreateSessionScreen(
                    state = CreateUiState(),
                    onClose = {}, onSubmit = {}, onDir = {}, onPickDir = {}, onShowAllDirs = {}, onName = {}, onPrompt = {},
                )
            }
        }
        compose.onNode(hasText("启动") and !hasContentDescription("x")).assertIsNotEnabled()
    }

    @Test fun runningSubagentsAreIndentedUnderTheirSessionAndOpenTheirPage() {
        val agents = listOf(
            clauderc.app.protocol.Subagent("a1", "Explore", "读一遍 relay 的连接管理", running = true, startedAt = clauderc.app.testing.T0),
            clauderc.app.protocol.Subagent("a2", "general-purpose", "已经跑完的", running = false, startedAt = clauderc.app.testing.T0),
        )
        val list = sampleSessions.copy(
            sessions = sampleSessions.sessions.map {
                when (it.id) {
                    "s-work" -> it.copy(agents = agents)
                    "s-stop" -> it.copy(agents = agents)
                    else -> it
                }
            },
        )
        val opened = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            ClaudercTheme(darkTheme = false) {
                SessionsScreen(
                    state = SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(list, emptyMap())),
                    onRefresh = {}, onOpenChat = {}, onCreate = {}, onRename = { _, _ -> }, onStop = {}, onRemove = {},
                    onOpenAgent = { s, a -> opened += s.id to a.id },
                )
            }
        }
        compose.onNodeWithTag("sessionList").performScrollToNode(hasText("读一遍 relay 的连接管理"))
        val title = compose.onNodeWithText("修复重连", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithText("读一遍 relay 的连接管理")
        row.assertIsDisplayed()
        compose.onNodeWithText("Explore").assertIsDisplayed()
        compose.onNodeWithText("已经跑完的").assertDoesNotExist()
        val desc = compose.onNodeWithText("读一遍 relay 的连接管理", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("under its session", desc.top >= title.bottom)
        assertTrue("indented past the session's title (${desc.left} > ${title.left})", desc.left > title.left)
        val spin = compose.onNode(
            hasContentDescription("加载中") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("agentRows")),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertEquals("the spinner lines up with the session's title", title.left, spin.left, 0.5f)
        row.performClick()
        assertEquals(listOf("s-work" to "a1"), opened)
        // The stopped session lists none, though its data still names one.
        assertEquals(1, compose.onAllNodesWithText("读一遍 relay 的连接管理").fetchSemanticsNodes().size)
    }
}

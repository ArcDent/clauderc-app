package clauderc.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.chat.ChatActions
import clauderc.app.chat.ChatScreen
import clauderc.app.chat.ChatUiState
import clauderc.app.git.GitActions
import clauderc.app.git.GitScreen
import clauderc.app.git.GitTab
import clauderc.app.git.GitUiState
import clauderc.app.machines.MachineRow
import clauderc.app.machines.MachinesScreen
import clauderc.app.machines.MachinesUiState
import clauderc.app.net.RelayLink
import clauderc.app.net.ConnectionState
import clauderc.app.pair.PairScreen
import clauderc.app.pair.PairUiState
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.Commit
import clauderc.app.protocol.Envelope
import clauderc.app.protocol.FileChange
import clauderc.app.protocol.FileStatus
import clauderc.app.protocol.GitLogResponse
import clauderc.app.protocol.GitStatusResponse
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.Pending
import clauderc.app.protocol.PendingKind
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.Ref
import clauderc.app.protocol.RefKind
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.ToolCall
import clauderc.app.protocol.ToolResult
import clauderc.app.sessions.SessionsScreen
import clauderc.app.sessions.SessionsUiState
import clauderc.app.sessions.groupSessions
import clauderc.app.testing.T0
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import clauderc.app.testing.session
import clauderc.app.ui.components.GlassNavBar
import clauderc.app.ui.components.NavTab
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercTheme
import clauderc.app.ui.theme.Symbols
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the main pages to PNG for a visual check against the mockup; runs only with -Pscreens. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private val out: File? = System.getProperty("clauderc.screens")?.let(::File)

    @Before fun onlyWhenAsked() = assumeTrue(out != null)

    private var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var dark by mutableStateOf(false)
    private var started = false

    private fun shot(name: String, isDark: Boolean = false, nav: Int? = null, body: @Composable () -> Unit) {
        dark = isDark
        content = {
            Box(Modifier.fillMaxSize().background(Clauderc.colors.bg)) {
                body()
                if (nav != null) {
                    GlassNavBar(
                        listOf(
                            NavTab("目标机", Symbols.Computer, Symbols.ComputerFilled),
                            NavTab("会话", Symbols.Forum, Symbols.ForumFilled, dot = true),
                            NavTab("git", Symbols.AccountTree, Symbols.AccountTreeFilled),
                        ),
                        selected = nav, onSelect = {}, hazeState = null, modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        if (!started) {
            started = true
            compose.setContent { ClaudercTheme(darkTheme = dark) { Box(Modifier.fillMaxSize().testTag("screen")) { content?.invoke() } } }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        val bitmap = compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap()
        out!!.mkdirs()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val log: List<Commit> by lazy {
        val text = javaClass.classLoader!!.getResource("git.log.res.json")!!.readText()
        ProtocolJson.decodeFromJsonElement(GitLogResponse.serializer(), Envelope.decode(text).body!!).commits
    }

    @Test fun pages() {
        compose.mainClock.autoAdvance = false
        shot("01-pair") { PairScreen(PairUiState.Scanning, {}, {}, scanner = { _, _ -> }) }
        shot("02-pair-expired") { PairScreen(PairUiState.Expired, {}, {}, scanner = { _, _ -> }) }
        val machines = MachinesUiState(
            listOf(RelayLink("wss://relay.example.com", "relay.example.com", ConnectionState.Connected)),
            listOf(
                MachineRow(machine("m-1", "工作室 Mac mini"), 6, 2),
                MachineRow(machine("m-2", "MacBook Pro"), 2, 0),
                MachineRow(machine("m-3", "dev-linux", online = false, lastSeen = T0.minusSeconds(36_000)), null, 0),
            ),
        )
        shot("03-machines", nav = 0) { MachinesScreen(machines, {}, {}) }
        shot("03-machines-dark", isDark = true, nav = 0) { MachinesScreen(machines, {}, {}) }
        val sessions = SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(sampleSessions, mapOf("api.anthropic.com" to "官方")))
        shot("04-sessions", nav = 1) { SessionsScreen(sessions, {}, {}, {}, {}, { _, _ -> }, {}, {}) }
        shot("04-sessions-dark", isDark = true, nav = 1) { SessionsScreen(sessions, {}, {}, {}, {}, { _, _ -> }, {}, {}) }
        val folded = sessions.copy(groups = sessions.groups!!.map { it.copy(collapsed = it.profile.id == "api.anthropic.com") })
        shot("04-sessions-folded", nav = 1) { SessionsScreen(folded, {}, {}, {}, {}, { _, _ -> }, {}, {}) }

        val items = listOf(
            ChatItem(1, ItemKind.USER, text = "给 internal/protocol 加上消息信封的编解码，版本号不是 1 时返回错误。"),
            ChatItem(2, ItemKind.ASSISTANT, text = "好的。我先看一下模块路径和现有目录。"),
            ChatItem(3, ItemKind.TOOL, tool = ToolCall("t1", "Read", "读取 go.mod", buildJsonObject { put("file_path", "go.mod") }, ToolResult("module clauderc"))),
            ChatItem(4, ItemKind.TOOL, tool = ToolCall("t2", "Bash", "运行 ls internal/protocol", buildJsonObject { put("command", "ls internal/protocol") }, ToolResult(""))),
            ChatItem(5, ItemKind.ASSISTANT, text = "目录还是空的。我新建 `envelope.go`，定义 `Envelope` 和 `Encode`、`Decode`。"),
            ChatItem(6, ItemKind.USER, text = "先别提交\n把错误码也一起定义了\n然后跑一遍 go vet"),
            ChatItem(7, ItemKind.ASSISTANT, text = "改完了，我跑一下检查。"),
        )
        val chat = ChatUiState(
            session = session("s", "clauderc 协议", SessionState.Pending, "default"), loading = false, items = items,
            model = "claude-opus-5-5", permissionMode = "default",
        )
        shot("05-chat") { ChatScreen(chat, ChatActions()) }
        val permission = chat.copy(pending = Pending("t3", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "go vet ./... && go test ./internal/protocol/...") }))
        shot("06-chat-permission") { ChatScreen(permission, ChatActions()) }
        shot("06-chat-permission-dark", isDark = true) { ChatScreen(permission, ChatActions()) }
        val working = chat.copy(state = SessionState.Working, workingSinceMillis = System.currentTimeMillis() - 42_000)
        shot("07-chat-working") { ChatScreen(working, ChatActions()) }
        shot("08-chat-offline") { ChatScreen(chat.copy(online = false, offlineText = "已离线 · 最后在线 14:36"), ChatActions()) }

        val status = GitStatusResponse(
            "dev", "origin/dev", 2, 0,
            staged = listOf(FileStatus("internal/protocol/errors.go", change = FileChange.Added)),
            unstaged = listOf(FileStatus("AGENTS.md", change = FileChange.Modified), FileStatus("scripts/e2e-claude/run.py", change = FileChange.Untracked)),
        )
        val commits = log + listOf(
            Commit("d2e8f41", listOf("c1b0d77"), "周遥", T0.minusSeconds(90_000), "试验 SSE 通道", listOf(Ref("spike-sse", RefKind.Branch)), 0, emptyList()),
        )
        val git = GitUiState(dir = "/Users/me/code/clauderc-app", status = status.copy(branch = "main", upstream = "origin/main"), commits = commits)
        shot("09-git-log", nav = 2) { GitScreen(git, GitActions()) }
        shot("09-git-log-dark", isDark = true, nav = 2) { GitScreen(git, GitActions()) }
        shot("10-git-status", nav = 2) { GitScreen(git.copy(tab = GitTab.Status, status = status, busySession = "clauderc 协议"), GitActions()) }
    }
}

package clauderc.app

import clauderc.app.ui.theme.page
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import clauderc.app.chat.ChatActions
import clauderc.app.chat.ChatScreen
import clauderc.app.chat.ChatUiState
import clauderc.app.chat.ImageViewer
import clauderc.app.chat.Viewing
import clauderc.app.data.AccentChoice
import clauderc.app.data.Appearance
import clauderc.app.data.ThemeMode
import clauderc.app.data.UiStyle
import clauderc.app.git.CommitScreen
import clauderc.app.git.DiffScreen
import clauderc.app.git.GitActions
import clauderc.app.git.GitScreen
import clauderc.app.git.GitTab
import clauderc.app.git.GitUiState
import clauderc.app.git.Loadable
import clauderc.app.machines.MachineRow
import clauderc.app.machines.MachinesScreen
import clauderc.app.machines.MachinesUiState
import clauderc.app.net.ConnectionState
import clauderc.app.net.RelayLink
import clauderc.app.pair.PairScreen
import clauderc.app.pair.PairUiState
import java.time.Instant
import clauderc.app.protocol.UsageWindow
import clauderc.app.protocol.UsageStale
import clauderc.app.protocol.UsageResponse
import clauderc.app.protocol.SessionContext
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.Commit
import clauderc.app.protocol.CommitFile
import clauderc.app.protocol.Envelope
import clauderc.app.protocol.FileChange
import clauderc.app.protocol.FileStatus
import clauderc.app.protocol.GitDiffResponse
import clauderc.app.protocol.GitLogResponse
import clauderc.app.protocol.GitShowResponse
import clauderc.app.protocol.GitStatusResponse
import clauderc.app.protocol.Hunk
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.Pending
import clauderc.app.protocol.PendingKind
import clauderc.app.protocol.ProtocolJson
import clauderc.app.protocol.Ref
import clauderc.app.protocol.RefKind
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.ToolCall
import clauderc.app.protocol.ToolImage
import clauderc.app.protocol.ToolResult
import clauderc.app.sessions.CreateSessionScreen
import clauderc.app.sessions.CreateUiState
import clauderc.app.sessions.SessionsScreen
import clauderc.app.sessions.SessionsUiState
import clauderc.app.sessions.groupSessions
import clauderc.app.settings.SettingsScreen
import clauderc.app.testing.T0
import clauderc.app.testing.machine
import clauderc.app.testing.sampleSessions
import clauderc.app.testing.session
import clauderc.app.ui.components.GlassNavBar
import clauderc.app.ui.components.LOADING_DELAY_MILLIS
import clauderc.app.ui.components.LoadingArt
import clauderc.app.ui.components.LoadingCover
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
    private var style by mutableStateOf(UiStyle.Clear)
    private var started = false

    /** [after] is how long the page runs before the shot; [fresh] starts it anew instead of reusing the last shot's state. */
    private fun shot(name: String, isDark: Boolean = false, nav: Int? = null, look: UiStyle = UiStyle.Clear, after: Long = 1_000, fresh: Boolean = false, body: @Composable () -> Unit) {
        dark = isDark
        style = look
        content = {
            Box(Modifier.fillMaxSize().page()) {
                if (fresh) key(name) { body() } else body()
                if (nav != null) {
                    GlassNavBar(
                        listOf(
                            NavTab("目标机", Symbols.Computer, Symbols.ComputerFilled),
                            NavTab("会话", Symbols.Forum, Symbols.ForumFilled, dot = true),
                            NavTab("git", Symbols.AccountTree, Symbols.AccountTreeFilled),
                            NavTab("设置", Symbols.Settings, Symbols.SettingsFilled),
                        ),
                        selected = nav, onSelect = {}, hazeState = null, modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        if (!started) {
            started = true
            compose.setContent { ClaudercTheme(darkTheme = dark, style = style) { Box(Modifier.fillMaxSize().testTag("screen")) { content?.invoke() } } }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(after)
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
        val sessions = SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(sampleSessions, mapOf("api.anthropic.com" to "官方")), usage = sampleUsage())
        shot("04-sessions", nav = 1) { SessionsScreen(sessions, {}, {}, {}, {}, { _, _ -> }, {}, {}) }
        val expired = sessions.copy(usage = sampleUsage().copy(stale = UsageStale.Expired, fetchedAt = Instant.now().minusSeconds(5_400)))
        shot("04-sessions-usage-expired", nav = 1) { SessionsScreen(expired, {}, {}, {}, {}, { _, _ -> }, {}, {}) }
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
            session = session("s", "clauderc 协议", SessionState.Pending, "default", context = SessionContext(254_185, 1_000_000)), loading = false, items = items,
            model = "claude-opus-5-5", permissionMode = "default",
        )
        shot("05-chat") { ChatScreen(chat, ChatActions()) }
        // Grey text as in the demo's 「clauderc 协议」 session (design/ui-mockup/demo.html).
        val notices = chat.copy(
            items = items.take(5) + listOf(
                ChatItem(7, ItemKind.ASSISTANT, text = "测试通过，覆盖了 req、res、evt 三种消息的往返编解码。\n\nneeds input: 要现在提交吗？"),
                ChatItem(8, ItemKind.NOTICE, text = "Background command \"等 CI 跑完\" completed (exit code 0)"),
                ChatItem(9, ItemKind.NOTICE, title = "协议文档 发来", text = "我在改 design.md 的错误码那一节，名字照你这边来。envelope.go 先别提交，等我对完一遍。"),
                ChatItem(10, ItemKind.NOTICE, text = "「协议文档」空闲了"),
                ChatItem(11, ItemKind.TOOL, tool = ToolCall(
                    "toolu_d1", "SendMessage", "SendMessage",
                    buildJsonObject { put("to", "09588c02"); put("message", "灰字块已经画好了，你那边可以接着做文档了。") },
                )),
                ChatItem(12, ItemKind.USER, text = "先别提交\n把错误码也一起定义了\n然后跑一遍 go vet"),
            ),
        )
        shot("05b-chat-notices") { ChatScreen(notices, ChatActions()) }
        shot("05b-chat-notices-dark", isDark = true) { ChatScreen(notices, ChatActions()) }
        // Diff views as in the demo's 「clauderc 协议」 session: a cut read folded, a write and an edit open.
        val tools = chat.copy(items = toolItems())
        shot("05c-chat-tools") { ChatScreen(tools, ChatActions()) }
        shot("05c-chat-tools-dark", isDark = true) { ChatScreen(tools, ChatActions()) }
        shot("05c-chat-tools-sticker", look = UiStyle.Sticker) { ChatScreen(tools, ChatActions()) }
        shot("05c-chat-tools-sticker-dark", isDark = true, look = UiStyle.Sticker) { ChatScreen(tools, ChatActions()) }
        // Pictures as in the demo's 「README 截图」 session: a read with its thumbnail, a WebP without one, clauderc show.
        val pictures = chat.copy(items = imageItems())
        // Decoded up front: in the app a thumbnail decodes off the main thread a frame or two after it shows.
        kotlinx.coroutines.runBlocking {
            pictures.items.mapNotNull { it.tool?.result?.images }.flatten().forEach { img ->
                img.thumb?.let { t -> clauderc.app.chat.ImageCache.shared.load("thumb:" + img.id) { java.util.Base64.getDecoder().decode(t) } }
            }
        }
        shot("05d-chat-images") { ChatScreen(pictures, ChatActions()) }
        shot("05d-chat-images-dark", isDark = true) { ChatScreen(pictures, ChatActions()) }
        shot("05d-chat-images-sticker", look = UiStyle.Sticker) { ChatScreen(pictures, ChatActions()) }
        val shotImage = pictures.items.first { it.tool?.toolUseId == "t1" }.tool!!.result!!.images!!.single()
        shot("05e-viewer") { Box { ChatScreen(pictures, ChatActions()); ImageViewer(Viewing(shotImage, null), null, false) {} } }
        val permission = chat.copy(pending = Pending("t3", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "go vet ./... && go test ./internal/protocol/...") }))
        shot("06-chat-permission") { ChatScreen(permission, ChatActions()) }
        shot("06-chat-permission-dark", isDark = true) { ChatScreen(permission, ChatActions()) }
        val working = chat.copy(state = SessionState.Working, workingSinceMillis = System.currentTimeMillis() - 42_000)
        shot("07-chat-working") { ChatScreen(working, ChatActions()) }
        // Round 3: the CLI's status line, as in the demo's 「周报整理」 long reply.
        shot("07b-chat-activity") { ChatScreen(working.copy(activity = "Incubating… (1m 17s · ↓ 7.5k tokens)"), ChatActions()) }
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

    /** Round 2 C as in the demo's 「修复重连」 and 「README 截图」 sessions: subagents and the terminal view. */
    @Test fun subagentsAndTerminal() {
        compose.mainClock.autoAdvance = false
        val now = Instant.now()
        val running = listOf(
            clauderc.app.protocol.Subagent("a1", "Explore", "排查断线重连的退避逻辑", running = true, startedAt = now.minusSeconds(48)),
            clauderc.app.protocol.Subagent("a2", "general-purpose", "补重连的单元测试", running = true, startedAt = now.minusSeconds(31)),
        )
        val withAgents = sampleSessions.copy(sessions = sampleSessions.sessions.map { if (it.id == "s-work") it.copy(agents = running) else it })
        val sessions = SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(withAgents, mapOf("api.anthropic.com" to "官方")))
        for ((tail, isDark, s) in styleVariants) {
            shot("11-sessions-agents$tail", isDark, nav = 1, look = s) { SessionsScreen(sessions, {}, {}, {}, {}, { _, _ -> }, {}, {}) }
        }

        fun agentCall(seq: Long, a: clauderc.app.protocol.Subagent) = ChatItem(
            seq, ItemKind.TOOL,
            tool = ToolCall("ag$seq", "Agent", "Agent", buildJsonObject { put("description", a.description); put("subagent_type", a.type) }, ToolResult("Async agent launched successfully."), agent = a),
        )
        val chat = ChatUiState(
            session = session("s-work", "修复重连", SessionState.Working, "default", context = SessionContext(124_000, 200_000)), loading = false,
            state = SessionState.Working, workingSinceMillis = System.currentTimeMillis() - 52_000, model = "claude-opus-5-5", permissionMode = "default",
            items = listOf(
                ChatItem(1, ItemKind.USER, text = "断线后为什么没有自动重连？查一下，顺便把测试补上。"),
                ChatItem(2, ItemKind.ASSISTANT, text = "我派两个子代理分头看：一个读连接管理，一个查退避逻辑。"),
                agentCall(3, clauderc.app.protocol.Subagent("a0", "Explore", "读一遍 relay 的连接管理", running = false, startedAt = now.minusSeconds(300))),
                agentCall(4, running[0]),
                ChatItem(5, ItemKind.ASSISTANT, text = "连接管理看完了：断开后走 `conn.close`，重连由 `client.go` 的退避循环触发。测试我也派一个子代理去补。"),
                agentCall(6, running[1]),
            ),
        )
        for ((tail, isDark, s) in styleVariants) {
            shot("12-chat-agents$tail", isDark, look = s) { ChatScreen(chat, ChatActions()) }
        }
        val blocked = chat.copy(state = SessionState.Idle, workingSinceMillis = null, unknownDialog = true, items = chat.items.take(2))
        shot("12-chat-unknown-dialog") { ChatScreen(blocked, ChatActions()) }

        val task = ChatItem(1, ItemKind.NOTICE, title = "任务", text = "看 internal/relay 断线后的重连：哪里触发、退避怎么算、有没有上限。只读，不要改代码。")
        val agentItems = listOf(
            task,
            ChatItem(2, ItemKind.TOOL, tool = ToolCall("g1", "Grep", "搜索 backoff", buildJsonObject { put("pattern", "backoff") }, ToolResult("internal/relay/client.go"))),
            ChatItem(3, ItemKind.TOOL, tool = ToolCall("r1", "Read", "读取 client.go", buildJsonObject { put("file_path", "internal/relay/client.go") }, ToolResult("1\tpackage relay", lines = 212))),
            ChatItem(4, ItemKind.ASSISTANT, text = "退避从 1 秒起翻倍，上限 30 秒，读失败和心跳超时都会进入这个循环。"),
        )
        val agentPage = ChatUiState(session = chat.session, loading = false, items = agentItems, state = SessionState.Working)
        val header = clauderc.app.chat.AgentHeader("排查断线重连的退避逻辑", "Explore", System.currentTimeMillis() - 48_000)
        for ((tail, isDark, s) in styleVariants) {
            shot("13-agent$tail", isDark, look = s) { clauderc.app.chat.AgentScreen(agentPage, header, ChatActions()) }
        }
        shot("13-agent-ended") { clauderc.app.chat.AgentScreen(agentPage.copy(state = SessionState.Idle), header, ChatActions()) }

        val rule = "─".repeat(120)
        val prompt = clauderc.app.protocol.TermScreen(
            "s-readme",
            List(40) { r ->
                when (r) {
                    1 -> " ▐▛███▛█   Claude Code v2.1.326"
                    2 -> "▝▜██████▀  Opus 5.5 · Claude Max"
                    3 -> " ▝▝   ▝▝   ~/github/clauderc-app"
                    6 -> "> 把 README 顶部的截图换成新的聊天页"
                    8 -> "⏺ 我先看一下现在用的是哪张。"
                    10 -> "⏺ Read(design/screenshots/chat.png)"
                    11 -> "  ⎿  Read image (212.0KB)"
                    13 -> "⏺ 还是旧样式。我重新渲染一套截图。"
                    15 -> "⏺ Bash(./gradlew testDebugUnitTest -Pscreens)"
                    16 -> "  ⎿  BUILD SUCCESSFUL in 41s"
                    18 -> "⏺ 新截图拼好了，没问题的话我把 README 里的路径换过去。"
                    35 -> rule
                    36 -> "❯ "
                    37 -> rule
                    38 -> "  ? for shortcuts"
                    else -> ""
                }
            },
            cursorRow = 36, cursorCol = 2, cursor = true,
        )
        val readme = session("s-readme", "README 截图", SessionState.Idle, "default")
        val term = clauderc.app.chat.TermUiState(session = readme, loading = false, screen = prompt)
        for ((tail, isDark, s) in styleVariants) {
            shot("14-term$tail", isDark, look = s) { clauderc.app.chat.TerminalScreen(term, onBack = {}, onKey = {}) }
        }
        val mcp = prompt.copy(
            lines = prompt.lines.take(19) + List(21) { r ->
                when (r) {
                    1 -> rule
                    2 -> "  Manage MCP servers"
                    3 -> "  3 servers"
                    5 -> "    Project MCPs (.mcp.json)"
                    6 -> "  ❯ probe · ✔ connected"
                    8 -> "    User MCPs (~/.claude.json)"
                    9 -> "    github · ✔ connected"
                    10 -> "    linear · ✘ failed"
                    12 -> "  ↑↓ to navigate · Enter to select · Esc to close"
                    else -> ""
                }
            },
            cursor = false,
        )
        shot("14-term-panel") { clauderc.app.chat.TerminalScreen(term.copy(screen = mcp), onBack = {}, onKey = {}) }
        shot("14-term-ended") { clauderc.app.chat.TerminalScreen(term.copy(session = readme.copy(state = SessionState.Stopped)), onBack = {}, onKey = {}) }
    }

    /**
     * The six loading arts in each style, paused 250, 700, 1200 and 1800ms after they come up: the
     * demo's paused frames. The cover comes up after LOADING_DELAY_MILLIS and its art starts about
     * three frames later.
     */
    @Test fun loadingFrames() {
        compose.mainClock.autoAdvance = false
        val readme = session("s-readme", "README 截图", SessionState.Idle, "default")
        val places: List<Triple<String, Int?, @Composable () -> Unit>> = listOf(
            Triple("boot", null) { LoadingCover(true, LoadingArt.Link, Modifier.fillMaxSize(), splash = true, label = "正在连接 relay.example.com") {} },
            Triple("chat", null) { ChatScreen(ChatUiState(session = readme, loading = true), ChatActions()) },
            Triple("sessions", 1) { SessionsScreen(SessionsUiState(machine("m-1", "工作室 Mac mini")), {}, {}, {}, {}, { _, _ -> }, {}, {}) },
            Triple("git", 2) { GitScreen(GitUiState(dir = "/Users/me/code/clauderc-app", loading = true), GitActions()) },
            Triple("term", null) { clauderc.app.chat.TerminalScreen(clauderc.app.chat.TermUiState(session = readme), onBack = {}, onKey = {}) },
            Triple("agent", null) {
                clauderc.app.chat.AgentScreen(ChatUiState(session = readme, loading = true), clauderc.app.chat.AgentHeader("排查断线重连的退避逻辑", "Explore"), ChatActions())
            },
        )
        for (look in UiStyle.entries) for ((place, nav, body) in places) for (t in listOf(250L, 700L, 1200L, 1800L)) {
            shot("15-load-${look.name.lowercase()}-$place-$t", nav = nav, look = look, after = LOADING_DELAY_MILLIS + 48 + t, fresh = true, body = body)
        }
    }

    /** The minimal shots again in dark, 格栅 and 贴纸. */
    private val styleVariants = listOf(
        Triple("", false, UiStyle.Clear),
        Triple("-dark", true, UiStyle.Clear),
        Triple("-grid", false, UiStyle.Grid),
        Triple("-sticker", false, UiStyle.Sticker),
    )

    /** Each style's pages in light and dark, the same ones the demo shows (design/ui-mockup/demo.html). */
    @Test fun stylePages() {
        compose.mainClock.autoAdvance = false
        val machines = MachinesUiState(
            listOf(RelayLink("wss://relay.example.com", "relay.example.com", ConnectionState.Connected)),
            listOf(
                MachineRow(machine("m-1", "工作室 Mac mini"), 6, 2),
                MachineRow(machine("m-2", "MacBook Pro"), 2, 0),
                MachineRow(machine("m-3", "dev-linux", online = false, lastSeen = T0.minusSeconds(36_000)), null, 0),
            ),
        )
        val sessions = SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(sampleSessions, mapOf("api.anthropic.com" to "官方")), usage = sampleUsage())
        val items = listOf(
            ChatItem(1, ItemKind.USER, text = "给 internal/protocol 加上消息信封的编解码，版本号不是 1 时返回错误。"),
            ChatItem(2, ItemKind.ASSISTANT, text = "好的。我先看一下模块路径和现有目录。"),
            ChatItem(3, ItemKind.TOOL, tool = ToolCall("t1", "Read", "读取 go.mod", buildJsonObject { put("file_path", "go.mod") }, ToolResult("module clauderc"))),
            ChatItem(5, ItemKind.ASSISTANT, text = "目录还是空的。我新建 `envelope.go`，定义 `Envelope` 和 `Encode`、`Decode`。"),
            ChatItem(6, ItemKind.USER, text = "先别提交\n把错误码也一起定义了\n然后跑一遍 go vet"),
            ChatItem(7, ItemKind.ASSISTANT, text = "改完了，我跑一下检查。"),
        )
        val chat = ChatUiState(
            session = session("s", "clauderc 协议", SessionState.Pending, "default", context = SessionContext(254_185, 1_000_000)), loading = false, items = items,
            model = "claude-opus-5-5", permissionMode = "default",
            pending = Pending("t3", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "go vet ./... && go test ./internal/protocol/...") }),
        )
        val create = CreateUiState(profileTitle = "默认", dir = "~/github/infra", recent = listOf("~/github/SmallProjects/android/clauderc-app", "~/github/infra", "~/github/relay-svc", "~/notes", "~/github/site"))
        val status = GitStatusResponse(
            "dev", "origin/dev", 2, 0,
            staged = listOf(FileStatus("internal/protocol/errors.go", change = FileChange.Added)),
            unstaged = listOf(FileStatus("AGENTS.md", change = FileChange.Modified), FileStatus("scripts/e2e-claude/run.py", change = FileChange.Untracked)),
        )
        val git = GitUiState(dir = "/Users/me/code/clauderc-app", status = status, commits = log)
        val show = GitShowResponse(
            hash = "a1f3c92", parents = listOf("7c2e0d4"), author = "林舟", time = T0, message = "定义协议错误码",
            files = listOf(
                CommitFile("internal/protocol/envelope.go", added = 86),
                CommitFile("internal/protocol/envelope_test.go", added = 112, deleted = 3),
                CommitFile("design/ui-mockup/preview.png", binary = true),
            ),
        )
        val patch = "@@ -1,14 +1,32 @@\n package protocol\n \n import (\n-\t\"testing\"\n+\t\"errors\"\n+\t\"testing\"\n )\n \n-func TestEnvelope(t *testing.T) {\n+func TestEnvelopeRoundTrip(t *testing.T) {\n"
        for (s in UiStyle.entries) for (isDark in listOf(false, true)) {
            val look = Appearance(s, ThemeMode.Light, AccentChoice.Blue)
            val tail = if (isDark) "-dark" else ""
            val name = s.name.lowercase()
            shot("$name-01-machines$tail", isDark, nav = 0, look = s) { MachinesScreen(machines, {}, {}) }
            shot("$name-02-sessions$tail", isDark, nav = 1, look = s) { SessionsScreen(sessions, {}, {}, {}, {}, { _, _ -> }, {}, {}) }
            shot("$name-03-chat$tail", isDark, look = s) { ChatScreen(chat, ChatActions()) }
            shot("$name-04-create$tail", isDark, look = s) { CreateSessionScreen(create, {}, {}, {}, {}, {}, {}, {}) }
            shot("$name-05-git$tail", isDark, nav = 2, look = s) { GitScreen(git, GitActions()) }
            shot("$name-06-status$tail", isDark, nav = 2, look = s) { GitScreen(git.copy(tab = GitTab.Status, busySession = "clauderc 协议"), GitActions()) }
            shot("$name-07-commit$tail", isDark, look = s) { CommitScreen("a1f3c92", Loadable(show), onBack = {}, onOpenFile = {}) }
            shot("$name-08-diff$tail", isDark, look = s) { DiffScreen("envelope.go", "a1f3c92", Loadable(GitDiffResponse("envelope.go", patch = patch)), onBack = {}) }
            shot("$name-09-pair$tail", isDark, look = s) { PairScreen(PairUiState.Scanning, {}, {}, scanner = { _, _ -> }) }
            shot("$name-10-settings$tail", isDark, nav = 3, look = s) {
                SettingsScreen(look, Color(0xFF007AFF), wallpaperAvailable = true, onStyle = { _, _ -> }, onMode = { _, _ -> }, onAccent = { _, _ -> })
            }
        }
    }

    /** The demo's ENVELOPE_GO, the file the write creates. */
    private val envelopeGo = listOf(
        "package protocol", "", "import (", "\t\"encoding/json\"", "\t\"errors\"", ")", "",
        "// ErrVersion is returned when an envelope is not version 1.", "var ErrVersion = errors.New(\"protocol: unsupported version\")", "",
        "type Envelope struct {", "\tV      int             `json:\"v\"`", "\tKind   string          `json:\"kind\"`", "\tID     string          `json:\"id,omitempty\"`",
        "\tMethod string          `json:\"method,omitempty\"`", "\tBody   json.RawMessage `json:\"body,omitempty\"`", "}", "",
        "func Encode(e Envelope) ([]byte, error) { return json.Marshal(e) }", "", "func Decode(b []byte) (Envelope, error) {", "\tvar e Envelope",
        "\tif err := json.Unmarshal(b, &e); err != nil {", "\t\treturn e, err", "\t}", "\tif e.V != 1 {", "\t\treturn e, ErrVersion", "\t}", "\treturn e, nil", "}",
    )

    private fun toolItems(): List<ChatItem> {
        fun tool(seq: Long, id: String, name: String, summary: String, result: ToolResult) =
            ChatItem(seq, ItemKind.TOOL, tool = ToolCall(id, name, summary, buildJsonObject { put("file_path", summary.substringAfter(' ')) }, result))
        val design = listOf("# 设计：远程会话控制", "", "## D1 消息信封", "", "所有消息都包在同一个信封里：", "", "```json", "{ \"v\": 1, \"kind\": \"req\", \"id\": \"42\", \"method\": \"chat.open\" }", "```")
        val edit = listOf(
            " // ErrVersion is returned when an envelope is not version 1.", "-var ErrVersion = errors.New(\"protocol: unsupported version\")", "+var ErrVersion = errors.New(\"protocol: version must be 1\")", " ",
            "+// Error codes carried in Envelope.Error.Code.", "+const (", "+\tCodeBadRequest   = \"bad_request\"", "+\tCodeNotFound     = \"not_found\"", "+\tCodeChanged      = \"changed\"",
            "+\tCodeUnauthorized = \"unauthorized\"", "+\tCodeInternal     = \"internal\"", "+)", "+", " type Envelope struct {",
        )
        return listOf(
            ChatItem(1, ItemKind.USER, text = "给 internal/protocol 加上消息信封的编解码，版本号不是 1 时返回错误。"),
            ChatItem(2, ItemKind.ASSISTANT, text = "目录还是空的。协议约定写在 design.md 里，我先对一下。"),
            tool(3, "t1", "Read", "读取 design.md", ToolResult(design.mapIndexed { i, l -> "${i + 1}\t$l" }.joinToString("\n"), lines = 182, truncated = true)),
            ChatItem(4, ItemKind.ASSISTANT, text = "我新建 `envelope.go`，定义 `Envelope` 和 `Encode`、`Decode`，`v` 不等于 1 时返回 `ErrVersion`。"),
            tool(5, "t2", "Write", "写入 envelope.go", ToolResult("File created successfully", patch = listOf(Hunk(0, 0, 1, envelopeGo.size, envelopeGo.map { "+$it" })))),
            ChatItem(6, ItemKind.TOOL, tool = ToolCall("t3", "Bash", "运行 go test ./internal/protocol/...", buildJsonObject { put("command", "go test ./internal/protocol/...") }, ToolResult("ok"))),
            ChatItem(7, ItemKind.USER, text = "先别提交\n把错误码也一起定义了\n然后跑一遍 go vet"),
            ChatItem(8, ItemKind.ASSISTANT, text = "明白。错误码先列一版：`bad_request`、`not_found`、`changed`、`unauthorized`、`internal`。"),
            tool(9, "t4", "Edit", "编辑 envelope.go", ToolResult("The file envelope.go has been updated successfully.", patch = listOf(
                Hunk(8, 4, 8, 13, edit),
                Hunk(15, 3, 24, 4, listOf(" \tMethod string          `json:\"method,omitempty\"`", " \tBody   json.RawMessage `json:\"body,omitempty\"`", "+\tError  *Error          `json:\"error,omitempty\"`", " }")),
            ))),
            ChatItem(10, ItemKind.ASSISTANT, text = "改完了，我跑一下检查。"),
        )
    }

    /** A fake phone screenshot like the demo's, [w]×[h] pixels, as base64 PNG. */
    private fun fakeShot(w: Int, h: Int, dark: Boolean = false, me: Int = 0xFF2E8B7A.toInt()): String {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val k = w / 360f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        fun r(x: Int, y: Int, rw: Int, rh: Int, rx: Int, c: Int) {
            paint.color = c
            cv.drawRoundRect(x * k, y * k, (x + rw) * k, (y + rh) * k, rx * k, rx * k, paint)
        }
        val bg = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val fg = if (dark) 0xFFF1F1F1.toInt() else 0xFF111111.toInt()
        val sf = if (dark) 0xFF171717.toInt() else 0xFFF3F3F3.toInt()
        val f3 = if (dark) 0xFF626262.toInt() else 0xFFB5B5B5.toInt()
        r(0, 0, 360, (h / k).toInt(), 0, bg); r(24, 64, 150, 16, 8, fg); r(24, 90, 90, 9, 4, f3)
        r(150, 130, 186, 40, 20, me); r(24, 186, 250, 64, 20, sf); r(32, 266, 120, 8, 4, f3); r(32, 286, 168, 8, 4, f3)
        r(24, 312, 290, 92, 20, sf); r(120, 424, 216, 40, 20, me); r(24, 480, 230, 48, 20, sf); r(32, 544, 140, 8, 4, f3)
        r(16, 700, 328, 56, 28, sf); r(292, 706, 44, 44, 22, me)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun imageItems(): List<ChatItem> {
        fun read(seq: Long, id: String, path: String, img: ToolImage) =
            ChatItem(seq, ItemKind.TOOL, tool = ToolCall(id, "Read", "读取 ${path.substringAfterLast('/')}", buildJsonObject { put("file_path", path) }, ToolResult(images = listOf(img))))
        return listOf(
            ChatItem(1, ItemKind.USER, text = "README 的截图帮我挑一张，深色那张也看看。"),
            ChatItem(2, ItemKind.ASSISTANT, text = "我先看一下现在的两张截图。"),
            read(3, "t1", "/p/design/screenshots/chat.png", ToolImage("t1.0", "image/png", width = 720, height = 1560, size = 217_088, thumb = fakeShot(166, 360))),
            read(4, "t2", "/p/design/screenshots/chat-dark.png", ToolImage("t2.0", "image/png", width = 720, height = 1560, size = 202_752, thumb = fakeShot(166, 360, dark = true, me = 0xFF8395FF.toInt()))),
            ChatItem(5, ItemKind.ASSISTANT, text = "浅色那张对比更清楚。草稿是 WebP，我这边做不了缩略图。"),
            read(6, "t3", "/p/design/hero-draft.webp", ToolImage("t3.0", "image/webp", size = 1_258_291)),
            ChatItem(7, ItemKind.ASSISTANT, text = "我把三张拼成了一张，发给你看。"),
            ChatItem(8, ItemKind.TOOL, tool = ToolCall(
                "t4", "Bash", "运行 clauderc show readme-hero.png", buildJsonObject { put("command", "clauderc show readme-hero.png") },
                ToolResult("[clauderc show 0123456789abcdef] readme-hero.png", images = listOf(ToolImage("show-0123456789abcdef", "image/png", name = "readme-hero.png", width = 1440, height = 810, size = 497_664, thumb = fakeShot(360, 203)))),
            )),
            ChatItem(9, ItemKind.ASSISTANT, text = "用这张做 README 的头图可以吗？"),
        )
    }

    /** The machine's plan usage: 60% of the 5 hours, starting over in 3 hours, and 70% of the week. */
    private fun sampleUsage() = UsageResponse(
        available = true,
        fiveHour = UsageWindow(60.0, Instant.now().plusSeconds(3 * 3_600)),
        sevenDay = UsageWindow(70.0, Instant.now().plusSeconds(4 * 86_400)),
        fetchedAt = Instant.now(),
    )
}

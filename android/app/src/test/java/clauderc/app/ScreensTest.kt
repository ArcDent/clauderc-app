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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
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
import clauderc.app.protocol.ModelTier
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
import clauderc.app.sessions.CUSTOM_PRESET
import clauderc.app.sessions.CreateField
import clauderc.app.sessions.CreateUiState
import clauderc.app.sessions.FormNote
import clauderc.app.sessions.SessionsScreen
import clauderc.app.sessions.SessionsUiState
import clauderc.app.sessions.groupSessions
import clauderc.app.sessions.presetOptions
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

    /**
     * [after] is how long the page runs before the shot; [fresh] starts it anew instead of reusing the last
     * shot's state. [open], when given, then runs (a tap that opens a sheet, say) and the shot lays the
     * windows of sheets and dialogs over the page.
     */
    private fun shot(name: String, isDark: Boolean = false, nav: Int? = null, look: UiStyle = UiStyle.Clear, after: Long = 1_000, fresh: Boolean = false, open: (() -> Unit)? = null, body: @Composable () -> Unit) {
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
        val bitmap = if (open == null) {
            compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap()
        } else {
            open()
            // A sheet's window lays out on Robolectric's looper and animates on the test clock: step both.
            repeat(20) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                compose.mainClock.advanceTimeBy(50)
            }
            compose.waitForIdle()
            layered()
        }
        out!!.mkdirs()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The page with the windows of open sheets and dialogs drawn over it where they sit on the screen,
     * dimmed behind as the window asks. Robolectric's captureToImage of such a window gives back the
     * page, so their views draw themselves.
     */
    private fun layered(): Bitmap {
        val page = compose.onNodeWithTag("screen").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(page)
        // A popup menu sits in a window of its own: draw it under the git icon, its right edge 4dp in, as PopMenu places it.
        if (compose.onAllNodes(androidx.compose.ui.test.hasTestTag("popMenu")).fetchSemanticsNodes().isNotEmpty()) {
            val img = compose.onNode(androidx.compose.ui.test.hasTestTag("popMenu")).captureToImage().asAndroidBitmap()
            val a = compose.onNode(androidx.compose.ui.test.hasContentDescription("git")).fetchSemanticsNode().boundsInRoot
            val dp = android.content.res.Resources.getSystem().displayMetrics.density
            canvas.drawBitmap(img, a.right - 4 * dp - img.width, a.bottom + 2 * dp, null)
        }
        org.robolectric.shadows.ShadowDialog.getShownDialogs().filter { it.isShowing }.forEach { d ->
            val w = d.window ?: return@forEach
            if (w.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                canvas.drawColor(android.graphics.Color.argb((w.attributes.dimAmount * 255).toInt(), 0, 0, 0))
            }
            val v = w.decorView
            val img = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(img))
            // Robolectric leaves every window at the top left; place it by its gravity as the device would.
            val r = android.graphics.Rect()
            android.view.Gravity.apply(w.attributes.gravity, v.width, v.height, android.graphics.Rect(0, 0, page.width, page.height), r)
            canvas.drawBitmap(img, r.left.toFloat(), r.top.toFloat(), null)
        }
        return page
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
        shot("04-sessions", nav = 1) { SessionsScreen(sessions, {}, {}, {}, { _, _ -> }, {}, {}) }
        val expired = sessions.copy(usage = sampleUsage().copy(stale = UsageStale.Expired, fetchedAt = Instant.now().minusSeconds(5_400)))
        shot("04-sessions-usage-expired", nav = 1) { SessionsScreen(expired, {}, {}, {}, { _, _ -> }, {}, {}) }
        shot("04-sessions-dark", isDark = true, nav = 1) { SessionsScreen(sessions, {}, {}, {}, { _, _ -> }, {}, {}) }
        val folded = sessions.copy(groups = sessions.groups!!.map { it.copy(collapsed = it.profile.id == "api.anthropic.com") })
        shot("04-sessions-folded", nav = 1) { SessionsScreen(folded, {}, {}, {}, { _, _ -> }, {}, {}) }

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
            shot("11-sessions-agents$tail", isDark, nav = 1, look = s) { SessionsScreen(sessions, {}, {}, {}, { _, _ -> }, {}, {}) }
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
     * Round 5 items 7 and 8 as in the demo's 「打开会话列表」: clauderc-app with its branch, the worktree
     * session and the fork under 「clauderc 协议」, the rows narrowed to dev, the forks folded, the branch
     * sheet, and the question before switching relay-svc while 「修复重连」 works there.
     */
    @Test fun forksAndBranches() {
        compose.mainClock.autoAdvance = false
        val app = "/Users/me/github/clauderc-app"
        val relaySvc = "/Users/me/github/relay-svc"
        val known = mapOf(
            app to clauderc.app.protocol.GitBranchesResponse("dev", listOf("dev", "main", "feat-tools", "worktree-tools-view")),
            relaySvc to clauderc.app.protocol.GitBranchesResponse("fix-reconnect", listOf("fix-reconnect", "main")),
            "/Users/me/github/infra" to clauderc.app.protocol.GitBranchesResponse("main", listOf("main")),
        )
        val groups = groupSessions(clauderc.app.testing.branchSessions, mapOf("api.anthropic.com" to "官方")).map { it.copy(branches = known) }
        val list = SessionsUiState(machine("m-1", "工作室 Mac mini"), groups)
        fun withDefault(change: (clauderc.app.sessions.SessionGroup) -> clauderc.app.sessions.SessionGroup) =
            list.copy(groups = groups.map { if (it.profile.id == "default") change(it) else it })
        val filtered = withDefault { it.copy(branchFilters = mapOf(app to "dev")) }
        val folded = withDefault { it.copy(foldedForks = setOf("s1")) }
        val asking = list.copy(switchAsk = clauderc.app.sessions.BranchSwitch(relaySvc, "relay-svc", "main", "修复重连"))
        for ((tail, isDark, s) in styleVariants) {
            shot("16-sessions-forks$tail", isDark, nav = 1, look = s, fresh = true) { SessionsScreen(list, {}, {}, {}, { _, _ -> }, {}, {}) }
            shot("16-sessions-filtered$tail", isDark, nav = 1, look = s, fresh = true) { SessionsScreen(filtered, {}, {}, {}, { _, _ -> }, {}, {}) }
            shot("16-sessions-fork-folded$tail", isDark, nav = 1, look = s, fresh = true) { SessionsScreen(folded, {}, {}, {}, { _, _ -> }, {}, {}) }
            shot(
                "16-branch-sheet$tail", isDark, nav = 1, look = s, fresh = true,
                open = { compose.onNode(androidx.compose.ui.test.hasText("dev") and androidx.compose.ui.test.hasClickAction()).performClick() },
            ) { SessionsScreen(list, {}, {}, {}, { _, _ -> }, {}, {}) }
            shot("16-switch-busy$tail", isDark, nav = 1, look = s, fresh = true, open = {}) { SessionsScreen(asking, {}, {}, {}, { _, _ -> }, {}, {}) }
        }
    }

    /**
     * Round 6 as in the demo: the chat page's limit row before and after the switch, the git icon's menu,
     * the Fork and Worktree dialogs, a deleted branch in the branch sheet and a row slid open.
     */
    @Test fun roundSix() {
        compose.mainClock.autoAdvance = false
        val resets = java.time.ZonedDateTime.now().withHour(22).withMinute(40).withSecond(0).toInstant()
        val limit = clauderc.app.protocol.Limit("fiveHour", "You've hit your 5-hour limit · resets 10:40pm", resets)
        val chat = ChatUiState(
            session = session("s16", "批量改测试", SessionState.Idle, "default", cwd = "/Users/me/github/relay-svc"), loading = false,
            model = "claude-opus-5-5", permissionMode = "default", limit = limit,
            items = listOf(
                ChatItem(1, ItemKind.USER, text = "把 relay 包的测试都改成表驱动。"),
                ChatItem(2, ItemKind.ASSISTANT, text = "先从 router_test.go 开始，改了三个用例。"),
                ChatItem(3, ItemKind.ASSISTANT, text = "You've hit your 5-hour limit · resets 10:40pm (Asia/Shanghai)"),
            ),
        )
        val on = chat.copy(limit = limit.copy(autoResume = clauderc.app.protocol.AutoResume("继续", resets.plusSeconds(60))))
        val relaySvc = "/Users/me/github/relay-svc"
        val app = "/Users/me/github/clauderc-app"
        val known = mapOf(
            app to clauderc.app.protocol.GitBranchesResponse("dev", listOf("dev", "main", "worktree-tools-view")),
            relaySvc to clauderc.app.protocol.GitBranchesResponse("fix-reconnect", listOf("fix-reconnect")),
            "/Users/me/github/infra" to clauderc.app.protocol.GitBranchesResponse("main", listOf("main")),
        )
        val list = SessionsUiState(machine("m-1", "工作室 Mac mini"), groupSessions(clauderc.app.testing.branchSessions, mapOf("api.anthropic.com" to "官方")).map { it.copy(branches = known) })
        // The popup's window lays out on Robolectric's looper and animates on the test clock: step both.
        fun settle() = repeat(8) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(50)
        }
        fun git() {
            compose.onNode(androidx.compose.ui.test.hasContentDescription("git")).performClick()
            settle()
        }
        for ((tail, isDark, s) in styleVariants) {
            shot("22-chat-limit$tail", isDark, look = s, fresh = true) { ChatScreen(chat, ChatActions()) }
            shot("22-chat-limit-on$tail", isDark, look = s, fresh = true) { ChatScreen(on, ChatActions()) }
            shot("22-git-menu$tail", isDark, look = s, fresh = true, open = { git() }) { ChatScreen(chat.copy(limit = null), ChatActions()) }
            shot("22-fork-dialog$tail", isDark, look = s, fresh = true, open = { git(); compose.onNode(androidx.compose.ui.test.hasText("Fork")).performClick() }) { ChatScreen(chat.copy(limit = null), ChatActions()) }
            shot("22-worktree-dialog$tail", isDark, look = s, fresh = true, open = { git(); compose.onNode(androidx.compose.ui.test.hasText("Worktree")).performClick() }) { ChatScreen(chat.copy(limit = null), ChatActions()) }
            shot(
                "22-branch-gone$tail", isDark, nav = 1, look = s, fresh = true,
                open = { compose.onNode(androidx.compose.ui.test.hasText("fix-reconnect") and androidx.compose.ui.test.hasClickAction()).performClick() },
            ) { SessionsScreen(list, {}, {}, {}, { _, _ -> }, {}, {}) }
            shot("22-swipe-open$tail", isDark, nav = 1, look = s, fresh = true, open = { compose.onNodeWithTag("sessionRow:s2").performTouchInput { swipeLeft() } }) {
                SessionsScreen(list, {}, {}, {}, { _, _ -> }, {}, {})
            }
        }
    }

    /**
     * Round 5 items 9 and 11 as in the demo's 「自动演示一遍」 and 「新建到 new-repo」: a draft in place of
     * 周报整理's second line and back in its composer, and the sheet for a directory Claude Code does not
     * trust yet, before and while it trusts.
     */
    @Test fun draftsAndTrust() {
        compose.mainClock.autoAdvance = false
        val list = SessionsUiState(
            machine("m-1", "工作室 Mac mini"), groupSessions(sampleSessions, mapOf("api.anthropic.com" to "官方")),
            drafts = mapOf("s-idle" to "改一下标题"),
        )
        val chat = ChatUiState(
            session = session("s-idle", "周报整理", SessionState.Idle, "default"), loading = false, model = "claude-opus-5-5", permissionMode = "default",
            items = listOf(ChatItem(1, ItemKind.USER, text = "把这周的周报整理一下。"), ChatItem(2, ItemKind.ASSISTANT, text = "整理好了，按项目分了三段。")),
            restore = clauderc.app.chat.Restore(1, "改一下标题"),
        )
        val dir = "/Users/me/github/new-repo"
        val create = CreateUiState(
            dir = dir, presets = presetOptions(sampleSessions, mapOf("api.anthropic.com" to "官方")),
            recent = listOf(dir, "/Users/me/github/SmallProjects/android/clauderc-app", "/Users/me/github/infra", "/Users/me/github/relay-svc", "/Users/me/notes", "/Users/me/github/site"),
            untrusted = dir,
        )
        for ((tail, isDark, s) in styleVariants) {
            shot("17-sessions-draft$tail", isDark, nav = 1, look = s, fresh = true) { SessionsScreen(list, {}, {}, {}, { _, _ -> }, {}, {}) }
            shot("17-chat-draft$tail", isDark, look = s, fresh = true) { ChatScreen(chat, ChatActions()) }
            shot("18-trust-sheet$tail", isDark, look = s, fresh = true, open = {}) { CreateSessionScreen(create, {}, {}, {}, {}, {}, {}, {}) }
            shot("18-trust-busy$tail", isDark, look = s, fresh = true, open = {}) { CreateSessionScreen(create.copy(trusting = true), {}, {}, {}, {}, {}, {}, {}) }
        }
    }

    /**
     * Round 5 item 10, tool view A, as in the demo's 「打开示例会话」: five calls in a row as one line, a
     * cut read on its own, a write and a test run as one line with the lines they added; then the five
     * opened with one call opened into its box, and the cut read fetching its whole text.
     */
    @Test fun toolViewA() {
        compose.mainClock.autoAdvance = false
        fun numbered(lines: List<String>, total: Int = lines.size, cut: Boolean = false) =
            ToolResult(lines.mapIndexed { i, l -> "${i + 1}\t$l" }.joinToString("\n"), lines = total, truncated = if (cut) true else null)
        fun call(seq: Long, id: String, name: String, summary: String, input: Pair<String, String>, result: ToolResult) =
            ChatItem(seq, ItemKind.TOOL, tool = ToolCall(id, name, summary, buildJsonObject { put(input.first, input.second) }, result))
        val goMod = listOf("module clauderc", "", "go 1.27.3", "", "require (", "\tgithub.com/coder/websocket v1.8.13", "\tmodernc.org/sqlite v1.38.0", ")")
        val rules = List(64) { listOf("# clauderc 开发规则", "", "用手机远程查看和操作目标机上的 claude 后台会话。", "", "## 目录结构", "")[it % 6] }
        val design = listOf("# 设计：远程会话控制", "", "## D1 消息信封", "", "所有消息都包在同一个信封里：", "", "```json", "{ \"v\": 1, \"kind\": \"req\", \"id\": \"42\", \"method\": \"chat.open\" }", "```")
        val items = listOf(
            ChatItem(1, ItemKind.USER, text = "给 internal/protocol 加上消息信封的编解码，版本号不是 1 时返回错误。"),
            ChatItem(2, ItemKind.ASSISTANT, text = "好的。我先看一下模块路径和现有目录。"),
            call(3, "g1", "Grep", "搜索 Envelope", "pattern" to "Envelope", ToolResult("No files found")),
            call(4, "r1", "Read", "读取 go.mod", "file_path" to "/p/go.mod", numbered(goMod)),
            call(5, "r2", "Read", "读取 AGENTS.md", "file_path" to "/p/AGENTS.md", numbered(rules)),
            call(6, "b1", "Bash", "运行 ls internal/protocol", "command" to "ls internal/protocol", ToolResult("")),
            call(7, "b2", "Bash", "运行 go list ./...", "command" to "go list ./...", ToolResult("clauderc/cmd/agent\nclauderc/cmd/relay")),
            ChatItem(8, ItemKind.ASSISTANT, text = "目录还是空的。协议约定写在 design.md 里，我先对一下。"),
            call(9, "d1", "Read", "读取 design.md", "file_path" to "/p/design.md", numbered(design, 182, cut = true)),
            ChatItem(10, ItemKind.ASSISTANT, text = "我新建 `envelope.go`，定义 `Envelope` 和 `Encode`、`Decode`，`v` 不等于 1 时返回 `ErrVersion`。"),
            call(11, "w1", "Write", "写入 envelope.go", "file_path" to "/p/envelope.go", ToolResult("File created successfully", patch = listOf(Hunk(0, 0, 1, envelopeGo.size, envelopeGo.map { "+$it" })))),
            call(12, "b3", "Bash", "运行 go test ./internal/protocol/...", "command" to "go test ./internal/protocol/...", ToolResult("--- PASS: TestEnvelopeRoundTrip (0.00s)\nok  \tclauderc/internal/protocol\t0.412s")),
            ChatItem(13, ItemKind.ASSISTANT, text = "测试通过，覆盖了 req、res、evt 三种消息的往返编解码。"),
        )
        val chat = ChatUiState(
            session = session("s1", "clauderc 协议", SessionState.Idle, "default", context = SessionContext(254_185, 1_000_000)), loading = false, items = items,
            model = "claude-opus-5-5", permissionMode = "default",
        )
        val fetching = chat.copy(items = items.take(9), fetchingResults = setOf("d1"))
        for ((tail, isDark, s) in styleVariants) {
            shot("19-tools-folded$tail", isDark, look = s, fresh = true) { ChatScreen(chat, ChatActions()) }
            shot(
                "19-tools-call$tail", isDark, look = s, fresh = true,
                open = {
                    compose.onAllNodesWithTag("toolGroup")[0].performClick()
                    compose.mainClock.advanceTimeBy(600)
                    compose.onNodeWithText("AGENTS.md").performClick()
                },
            ) { ChatScreen(chat.copy(items = items.take(8)), ChatActions()) }
            shot("19-tools-fetching$tail", isDark, look = s, fresh = true, open = { compose.onNodeWithText("design.md").performClick() }) { ChatScreen(fetching, ChatActions()) }
        }
    }

    /** Round 8 as in the demo's 「退避说明」 session: a reply cut into a heading, a list, a quote, code and a table. */
    @Test fun markdownReplies() {
        compose.mainClock.autoAdvance = false
        val reply = listOf(
            "## 重连退避",
            "",
            "逻辑在 `internal/relay/conn.go` 的 `redial` 里，分三步：",
            "",
            "1. 第一次断开后等 **1 秒**",
            "2. 之后每次翻倍，最多等 30 秒",
            "3. 连上并收到第一个心跳后清零",
            "",
            "> 读失败和心跳超时走的是同一条路径，两种断开都会退避。",
            "",
            "```go",
            "func backoff(n int) time.Duration {",
            "\td := time.Second << n",
            "\tif d > 30*time.Second || d <= 0 { // 移位溢出成负数时也取上限",
            "\t\treturn 30 * time.Second",
            "\t}",
            "\treturn d",
            "}",
            "```",
            "",
            "| 第几次 | 等待 | 累计 | 说明 |",
            "|---|---|---|---|",
            "| 1 | 1s | 1s | 断开后立刻安排 |",
            "| 2 | 2s | 3s | 翻倍 |",
            "| 5 | 16s | 31s | 还在翻倍 |",
            "| 6 | 30s | 61s | 到上限，之后一直是 30s |",
            "",
            "写法参考了 [gRPC 的连接退避说明](https://github.com/grpc/grpc/blob/master/doc/connection-backoff.md)。Duration 的文档：https://pkg.go.dev/time#Duration。",
        ).joinToString("\n")
        val chat = ChatUiState(
            session = session("s20", "退避说明", SessionState.Idle, "default", context = SessionContext(28_000, 200_000)), loading = false,
            model = "claude-opus-5-5", permissionMode = "default",
            items = listOf(
                ChatItem(1, ItemKind.USER, text = "重连退避现在是怎么算的？顺便把 Duration 的文档发我 https://pkg.go.dev/time"),
                ChatItem(2, ItemKind.TOOL, tool = ToolCall("r1", "Read", "读取 internal/relay/conn.go", buildJsonObject { put("file_path", "internal/relay/conn.go") }, ToolResult("package relay"))),
                ChatItem(3, ItemKind.ASSISTANT, text = reply),
            ),
        )
        for (s in UiStyle.entries) for (isDark in listOf(false, true)) {
            shot("23-chat-markdown-${s.name.lowercase()}${if (isDark) "-dark" else ""}", isDark, look = s, fresh = true) { ChatScreen(chat, ChatActions()) }
        }
    }

    /** Round 9: a reply with inline formulas, a block one, one wider than the bubble and one that cannot be drawn. */
    @Test fun formulaReplies() {
        compose.mainClock.autoAdvance = false
        val reply = listOf(
            "可以直接算。第 \$n\$ 次断开后等 \$d_n = \\min(2^{n-1},\\ 30)\$ 秒，没到上限前，前 \$k\$ 次一共等",
            "\$\$",
            "T_k = \\sum_{n=1}^{k} 2^{n-1} = 2^k - 1",
            "\$\$",
            "到上限以后每次多等 30 秒，所以 \$k > 5\$ 时是 \\(T_k = 31 + 30(k-5)\\)。",
            "",
            "加上抖动，每次等待乘一个 0.8 到 1.2 之间均匀分布的系数，平均下来总时间不变：",
            "",
            "\\[ \\mathbb{E}\\big[\\tilde T_k\\big] = \\sum_{n=1}^{k} \\mathbb{E}\\big[d_n \\cdot U(0.8,\\ 1.2)\\big] = T_k \\cdot \\frac{0.8 + 1.2}{2} = T_k \\]",
            "",
            "日志里的 \$PATH、「每月 \$5 到 \$10」这种写法不会被当成公式；写错的公式照原文显示，比如 \$\\backofff(n)\$。",
        ).joinToString("\n")
        val chat = ChatUiState(
            session = session("s20", "退避说明", SessionState.Idle, "default", context = SessionContext(28_000, 200_000)), loading = false,
            model = "claude-opus-5-5", permissionMode = "default",
            items = listOf(
                ChatItem(1, ItemKind.USER, text = "连着失败 k 次一共要等多久？给个公式"),
                ChatItem(2, ItemKind.ASSISTANT, text = reply),
            ),
        )
        for (s in UiStyle.entries) for (isDark in listOf(false, true)) {
            shot("24-chat-math-${s.name.lowercase()}${if (isDark) "-dark" else ""}", isDark, look = s, fresh = true) { ChatScreen(chat, ChatActions()) }
        }
    }

    /** Round 9: a user message slid left to lay bare the rewind button, and the rewind sheet it opens. */
    @Test fun rewindSwipeAndSheet() {
        compose.mainClock.autoAdvance = false
        val chat = ChatUiState(
            session = session("s20", "退避说明", SessionState.Idle, "default", context = SessionContext(28_000, 200_000)), loading = false,
            model = "claude-opus-5-5", permissionMode = "default", state = SessionState.Idle, hasMore = false,
            items = listOf(
                ChatItem(1, ItemKind.USER, text = "连着失败 k 次一共要等多久？", uuid = "u-1"),
                ChatItem(2, ItemKind.ASSISTANT, text = "前 k 次一共等 2^k − 1 秒，到上限以后每次再加 30 秒。"),
                ChatItem(3, ItemKind.USER, text = "把退避上限改成 60 秒", uuid = "u-3"),
                ChatItem(
                    4, ItemKind.TOOL,
                    tool = ToolCall(
                        "t1", "Edit", "修改 conn.go", buildJsonObject { put("file_path", "internal/relay/conn.go") },
                        ToolResult(patch = listOf(Hunk(41, 1, 41, 1, listOf("-\tif d > 30*time.Second || d <= 0 {", "+\tif d > 60*time.Second || d <= 0 {")))),
                    ),
                ),
                ChatItem(5, ItemKind.ASSISTANT, text = "改好了，`backoff` 的上限从 30 秒改成 60 秒。"),
                ChatItem(6, ItemKind.USER, text = "跑一下测试", uuid = "u-6"),
                ChatItem(7, ItemKind.ASSISTANT, text = "`go test ./internal/relay/...` 通过了。"),
            ),
        )
        val slide: () -> Unit = { compose.onNodeWithTag("userRow:3").performTouchInput { swipeLeft() } }
        for (s in UiStyle.entries) for (isDark in listOf(false, true)) {
            val tail = "${s.name.lowercase()}${if (isDark) "-dark" else ""}"
            shot("25-chat-rewind-swipe-$tail", isDark, look = s, fresh = true, open = slide) { ChatScreen(chat, ChatActions()) }
            shot("25-chat-rewind-sheet-$tail", isDark, look = s, fresh = true, open = {
                slide()
                repeat(10) { compose.mainClock.advanceTimeBy(50) }
                compose.onNodeWithTag("rewindAction").performClick()
            }) { ChatScreen(chat, ChatActions()) }
        }
    }

    /** Round 10: the task list over the input, folded and unfolded, while the session works. */
    @Test fun taskBar() {
        compose.mainClock.autoAdvance = false
        val chat = ChatUiState(
            session = session("s20", "退避说明", SessionState.Working, "default", context = SessionContext(28_000, 200_000)), loading = false,
            model = "claude-opus-5-5", permissionMode = "default", state = SessionState.Working, hasMore = false,
            workingSinceMillis = System.currentTimeMillis() - 42_000, activity = "正在按位置核对光标… (42s · ↓ 3.1k tokens)",
            items = listOf(
                ChatItem(1, ItemKind.USER, text = "把回溯面板的长列表也处理好", uuid = "u-1"),
                ChatItem(2, ItemKind.ASSISTANT, text = "好，先列个清单。"),
            ),
            tasks = listOf(
                clauderc.app.protocol.ChatTask("1", "读懂回溯面板的长列表", status = "completed"),
                clauderc.app.protocol.ChatTask("2", "按位置核对光标", activeForm = "正在按位置核对光标", status = "in_progress"),
                clauderc.app.protocol.ChatTask("3", "补长列表的测试", status = "pending"),
                clauderc.app.protocol.ChatTask("4", "跑真实回归", status = "pending"),
            ),
        )
        for (s in UiStyle.entries) for (isDark in listOf(false, true)) {
            val tail = "${s.name.lowercase()}${if (isDark) "-dark" else ""}"
            shot("26-chat-tasks-folded-$tail", isDark, look = s, fresh = true) { ChatScreen(chat, ChatActions()) }
            shot("26-chat-tasks-open-$tail", isDark, look = s, fresh = true, open = { compose.onNodeWithTag("taskHead").performClick() }) { ChatScreen(chat, ChatActions()) }
        }
    }

    /** Round 5 item 13: git's 「最近的目录」 with eight directories, its outline only along the sheet's edge. */
    @Test fun recentDirsSheet() {
        compose.mainClock.autoAdvance = false
        val dirs = listOf(
            "/Users/me/github/TEST/QA", "/Users/me/github/TEST/STUDY", "/Users/me/github/SmallProjects/android/clauderc-app",
            "/Users/me/homework/machine-learning", "/Users/me/homework/思政报告", "/Users/me/homework/physics-experiment",
            "/Users/me/homework/cry-liquid", "/Users/me/notes",
        )
        for ((tail, isDark, s) in styleVariants) {
            shot("21-recent-dirs$tail", isDark, nav = 2, look = s, fresh = true, open = { compose.onAllNodesWithText("选择目录")[0].performClick() }) {
                GitScreen(GitUiState(dirs = dirs), GitActions())
            }
        }
    }

    /** Round 5 item 12: 设置's 「动画」 group with 「总是播放加载动画」 off and on, scrolled into view. */
    @Test fun animationSwitch() {
        compose.mainClock.autoAdvance = false
        for ((tail, isDark, s) in styleVariants) for (on in listOf(false, true)) {
            val look = Appearance(s, ThemeMode.Light, AccentChoice.Blue)
            shot(
                "20-settings-animation-${if (on) "on" else "off"}$tail", isDark, nav = 3, look = s, fresh = true,
                // Swiped to the end, clear of the nav bar. The scroll animates: let the clock run for it, or it never ends with the clock held.
                open = {
                    compose.mainClock.autoAdvance = true
                    repeat(2) { compose.onNodeWithTag("screen").performTouchInput { swipeUp() } }
                    compose.mainClock.autoAdvance = false
                },
            ) {
                SettingsScreen(look, Color(0xFF007AFF), wallpaperAvailable = true, onStyle = { _, _ -> }, onMode = { _, _ -> }, onAccent = { _, _ -> }, alwaysLoading = on)
            }
        }
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
            Triple("sessions", 1) { SessionsScreen(SessionsUiState(machine("m-1", "工作室 Mac mini")), {}, {}, {}, { _, _ -> }, {}, {}) },
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

    /**
     * README's screenshots in 贴纸, light and dark: one made-up developer's day on 「书房的 Mac」 with
     * shop-web, shop-api and infra, every time counted back from now so the rows read as minutes ago.
     */
    @Test fun readmeShots() {
        compose.mainClock.autoAdvance = false
        val now = Instant.now()
        val web = "/Users/me/code/shop-web"
        val api = "/Users/me/code/shop-api"
        val infra = "/Users/me/code/infra"
        fun row(id: String, name: String, state: SessionState, profile: String, minutesAgo: Long, cwd: String, used: Int) =
            session(id, name, state, profile, cwd = cwd, context = SessionContext(used, 200_000)).copy(lastActiveAt = now.minusSeconds(minutesAgo * 60), branch = "main")
        val subagents = listOf(
            clauderc.app.protocol.Subagent("a1", "Explore", "翻支付回调的日志", running = true, startedAt = now.minusSeconds(52)),
            clauderc.app.protocol.Subagent("a2", "Explore", "读订单状态机的超时逻辑", running = true, startedAt = now.minusSeconds(37)),
        )
        val lazyImages = "$web/.claude/worktrees/lazy-images"
        val list = clauderc.app.protocol.SessionList(
            sessions = listOf(
                row("s1", "结算页改版", SessionState.Pending, "default", 1, web, 76_000).copy(branch = "feat-checkout", detail = "npm run test -- checkout"),
                row("s2", "结算页改版 ⑂ 试试单页布局", SessionState.Idle, "default", 9, web, 52_000).copy(branch = "feat-checkout", forkOf = "s1"),
                row("s3", "图片懒加载", SessionState.Idle, "default", 22, lazyImages, 41_000)
                    .copy(dir = web, branch = "worktree-lazy-images", worktree = clauderc.app.protocol.SessionWorktree("lazy-images", lazyImages)),
                row("s4", "订单超时排查", SessionState.Working, "default", 0, api, 118_000).copy(jobState = "working", detail = "正在查支付回调为什么超时", agents = subagents),
                row("s5", "接口文档", SessionState.Idle, "default", 35, api, 23_000).copy(jobState = "blocked", detail = "废弃的接口也要写进文档吗？"),
                row("s6", "备份脚本审查", SessionState.Idle, "llm.example.com", 180, infra, 64_000),
                row("s7", "迁移到 Postgres 17", SessionState.Stopped, "llm.example.com", 60 * 26, infra, 96_000).copy(jobState = "done", detail = "迁移脚本已合并"),
            ),
            profiles = listOf(
                clauderc.app.protocol.Profile("default", clauderc.app.protocol.ProfileKind.Default),
                clauderc.app.protocol.Profile("llm.example.com", clauderc.app.protocol.ProfileKind.Endpoint),
            ),
        )
        val branches = mapOf(
            web to clauderc.app.protocol.GitBranchesResponse("feat-checkout", listOf("feat-checkout", "main", "worktree-lazy-images")),
            api to clauderc.app.protocol.GitBranchesResponse("main", listOf("main")),
            infra to clauderc.app.protocol.GitBranchesResponse("main", listOf("main")),
        )
        val usage = UsageResponse(
            available = true,
            fiveHour = UsageWindow(42.0, now.plusSeconds(2 * 3_600 + 1_200)),
            sevenDay = UsageWindow(63.0, now.plusSeconds(3 * 86_400)),
            fetchedAt = now,
        )
        val desk = machine("m-1", "书房的 Mac")
        val sessions = SessionsUiState(desk, groupSessions(list, mapOf("llm.example.com" to "公司网关")).map { it.copy(branches = branches) }, usage = usage)

        fun numbered(lines: List<String>) = ToolResult(lines.mapIndexed { i, l -> "${i + 1}\t$l" }.joinToString("\n"), lines = lines.size)
        fun call(seq: Long, id: String, name: String, summary: String, input: Pair<String, String>, result: ToolResult) =
            ChatItem(seq, ItemKind.TOOL, tool = ToolCall(id, name, summary, buildJsonObject { put(input.first, input.second) }, result))
        val coupon = listOf("import { useState } from \"react\"", "", "export function CouponInput({ onApply }: Props) {", "  const [code, setCode] = useState(\"\")", "  return (")
        val edit = listOf(
            " export function CouponInput({ onApply }: Props) {",
            "+  const ref = useRef<HTMLInputElement>(null)",
            "+  const reveal = () => ref.current?.scrollIntoView({ block: \"center\" })",
            "   return (",
            "-    <div className=\"coupon fixed bottom-0\">",
            "+    <div className=\"coupon sticky bottom-0\">",
            "-      <input placeholder=\"优惠码\" />",
            "+      <input ref={ref} onFocus={reveal} placeholder=\"优惠码\" />",
        )
        val checkout = ChatUiState(
            session = list.sessions[0], loading = false, state = SessionState.Pending, model = "claude-opus-5-5", permissionMode = "default",
            items = listOf(
                ChatItem(1, ItemKind.USER, text = "结算页的优惠券输入框在手机上会被键盘挡住，修一下，改完跑一遍结算页的测试。"),
                ChatItem(2, ItemKind.ASSISTANT, text = "我先找一下输入框在哪个组件里。"),
                call(3, "g1", "Grep", "搜索 CouponInput", "pattern" to "CouponInput", ToolResult("src/checkout/CouponInput.tsx\nsrc/checkout/Checkout.tsx")),
                call(4, "r1", "Read", "读取 CouponInput.tsx", "file_path" to "$web/src/checkout/CouponInput.tsx", numbered(coupon)),
                call(5, "r2", "Read", "读取 Checkout.tsx", "file_path" to "$web/src/checkout/Checkout.tsx", numbered(coupon)),
                ChatItem(6, ItemKind.ASSISTANT, text = "底部操作栏用了 `position: fixed`，键盘弹起后视口变矮，输入框被压在栏下面。我改成 `sticky`，聚焦时再把输入框滚到看得见的地方。"),
                call(7, "e1", "Edit", "编辑 CouponInput.tsx", "file_path" to "$web/src/checkout/CouponInput.tsx", ToolResult("The file has been updated successfully.", patch = listOf(Hunk(3, 5, 3, 7, edit)))),
                ChatItem(8, ItemKind.ASSISTANT, text = "改完了，我跑一下结算页的测试。"),
            ),
            pending = Pending("b1", PendingKind.PERMISSION, "Bash", buildJsonObject { put("command", "npm run test -- checkout") }),
        )
        fun agentCall(seq: Long, a: clauderc.app.protocol.Subagent) = ChatItem(
            seq, ItemKind.TOOL,
            tool = ToolCall("ag$seq", "Agent", "Agent", buildJsonObject { put("description", a.description); put("subagent_type", a.type) }, ToolResult("Async agent launched successfully."), agent = a),
        )
        val timeout = ChatUiState(
            session = list.sessions[3], loading = false, state = SessionState.Working, workingSinceMillis = System.currentTimeMillis() - 84_000,
            model = "claude-opus-5-5", permissionMode = "default", activity = "Investigating… (1m 24s · ↓ 3.2k tokens)",
            items = listOf(
                ChatItem(1, ItemKind.USER, text = "这两天有订单付了钱还显示超时，查一下原因。"),
                ChatItem(2, ItemKind.ASSISTANT, text = "我先让一个子代理把最近三天超时的订单列出来。"),
                agentCall(3, clauderc.app.protocol.Subagent("a0", "general-purpose", "列出最近三天超时的订单", running = false, startedAt = now.minusSeconds(300))),
                ChatItem(4, ItemKind.ASSISTANT, text = "超时的订单都在晚上 8 点到 10 点，全走的微信支付。我再派两个子代理，分头查回调日志和订单状态机。"),
                agentCall(5, subagents[0]),
                agentCall(6, subagents[1]),
            ),
        )
        fun commit(hash: String, parents: List<String>, hoursAgo: Long, subject: String, lane: Int, edges: List<Pair<Int, Int>>, refs: List<Ref>? = null) =
            Commit(hash, parents, "林舟", now.minusSeconds(hoursAgo * 3_600 + 600), subject, refs, lane, edges.map { clauderc.app.protocol.Edge(it.first, it.second) })
        val commits = listOf(
            commit("e4a1c07", listOf("9b3f2d8", "3be8c41"), 0, "合并 feat-checkout", 0, listOf(0 to 0, 0 to 1), listOf(Ref("main", RefKind.Branch), Ref("origin/main", RefKind.RemoteBranch))),
            commit("9b3f2d8", listOf("7d0e6f1"), 1, "金额统一按分计算", 0, listOf(0 to 0, 1 to 1)),
            commit("3be8c41", listOf("52c7a90"), 2, "优惠券输入框不再被键盘挡住", 1, listOf(0 to 0, 1 to 1), listOf(Ref("feat-checkout", RefKind.Branch))),
            commit("52c7a90", listOf("7d0e6f1"), 5, "结算页拆成三步", 1, listOf(0 to 0, 1 to 0)),
            commit("7d0e6f1", listOf("c18b2a4"), 26, "商品卡片加载时显示骨架屏", 0, listOf(0 to 0), listOf(Ref("v1.4.0", RefKind.Tag))),
            commit("c18b2a4", listOf("0fa93d5"), 50, "升级到 Vite 7", 0, listOf(0 to 0)),
            commit("0fa93d5", listOf("6e2b81c"), 74, "购物车数量为 0 时不能下单", 0, listOf(0 to 0)),
            commit("6e2b81c", emptyList(), 98, "接入前端错误监控", 0, emptyList()),
        )
        val git = GitUiState(dir = web, status = GitStatusResponse("main", "origin/main", 0, 0), commits = commits)

        for (isDark in listOf(false, true)) {
            val tail = if (isDark) "-dark" else ""
            val s = UiStyle.Sticker
            shot("readme-sessions$tail", isDark, nav = 1, look = s, fresh = true) { SessionsScreen(sessions, {}, {}, {}, { _, _ -> }, {}, {}) }
            shot("readme-permission$tail", isDark, look = s, fresh = true) { ChatScreen(checkout, ChatActions()) }
            shot("readme-agents$tail", isDark, look = s, fresh = true) { ChatScreen(timeout, ChatActions()) }
            shot("readme-git$tail", isDark, nav = 2, look = s, fresh = true) { GitScreen(git, GitActions()) }
            cover(tail, isDark)
        }
    }

    /**
     * readme-cover: the four README shots as 贴纸 stickers on its dotted paper, ink outline and hard
     * shadow, each set a little higher or lower; design/screenshots/cover.png is a copy of it.
     */
    private fun cover(tail: String, isDark: Boolean) {
        val ink = if (isDark) 0xFF000000.toInt() else 0xFF1E1B2E.toInt()
        val bmp = Bitmap.createBitmap(1800, 820, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(if (isDark) 0xFF1C1A27.toInt() else 0xFFFFF6E9.toInt())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = if (isDark) 0x14FFFFFF else 0x1E1E1B2E
        for (y in 14 until bmp.height step 28) for (x in 14 until bmp.width step 28) canvas.drawCircle(x.toFloat(), y.toFloat(), 2f, paint)
        listOf("sessions" to 96, "permission" to 44, "agents" to 136, "git" to 70).forEachIndexed { i, (name, top) ->
            val shot = android.graphics.BitmapFactory.decodeFile(File(out, "readme-$name$tail.png").path)
            // Two smaller steps down to 360 wide: one bilinear step from 1170 drops strokes of the text.
            val half = Bitmap.createScaledBitmap(shot, 720, shot.height * 720 / shot.width, true)
            val phone = Bitmap.createScaledBitmap(half, 360, half.height / 2, true)
            val x = 90f + i * 420
            val y = top.toFloat()
            paint.color = ink
            canvas.drawRoundRect(x + 9, y + 9, x + 379, y + phone.height + 19, 46f, 46f, paint)
            canvas.drawRoundRect(x - 5, y - 5, x + 365, y + phone.height + 5, 46f, 46f, paint)
            val image = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
            image.shader = android.graphics.BitmapShader(phone, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(android.graphics.Matrix().apply { setTranslate(x, y) }) }
            canvas.drawRoundRect(x, y, x + 360, y + phone.height, 40f, 40f, image)
        }
        File(out, "readme-cover$tail.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        // Round 7 as index.html draws it: anyrouter.top chosen, the models open with Haiku changed; then 「自己填」
        // with an address and key, and the floating card for a missing key.
        val presets = presetOptions(sampleSessions, mapOf("api.anthropic.com" to "官方"))
        val anyModels = presets.first { it.id == "anyrouter.top" }.models
        val create = CreateUiState(
            dir = "~/github/infra", recent = listOf("~/github/SmallProjects/android/clauderc-app", "~/github/infra", "~/github/relay-svc", "~/notes", "~/github/site"),
            presets = presets, preset = "anyrouter.top", models = anyModels + (ModelTier.Haiku to "hy-4-flash[1M]"), presetModels = anyModels, modelsOpen = true,
        )
        val createCustom = create.copy(preset = CUSTOM_PRESET, presetModels = null, baseUrl = "https://anyrouter.example", apiKey = "sk-example-0123456789", modelsOpen = false)
        val createNote = createCustom.copy(apiKey = "", badFields = setOf(CreateField.Key), note = FormNote("地址和 key 都要填", 1))
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
            shot("$name-02-sessions$tail", isDark, nav = 1, look = s) { SessionsScreen(sessions, {}, {}, {}, { _, _ -> }, {}, {}) }
            shot("$name-03-chat$tail", isDark, look = s) { ChatScreen(chat, ChatActions()) }
            shot("$name-04-create$tail", isDark, look = s) { CreateSessionScreen(create, {}, {}, {}, {}, {}, {}, {}) }
            shot("$name-04b-create-custom$tail", isDark, look = s) { CreateSessionScreen(createCustom, {}, {}, {}, {}, {}, {}, {}) }
            shot("$name-04c-create-note$tail", isDark, look = s) { CreateSessionScreen(createNote, {}, {}, {}, {}, {}, {}, {}) }
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

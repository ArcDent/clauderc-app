package clauderc.app

import clauderc.app.net.ConnectionState
import clauderc.app.ui.components.LoadingArt
import clauderc.app.ui.components.LoadingCover
import clauderc.app.ui.theme.page
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import clauderc.app.chat.AgentHeader
import clauderc.app.chat.AgentScreen
import clauderc.app.chat.ChatActions
import clauderc.app.chat.ChatScreen
import clauderc.app.chat.ChatViewModel
import clauderc.app.chat.TermViewModel
import clauderc.app.chat.TerminalScreen
import clauderc.app.protocol.Subagent
import clauderc.app.git.CommitScreen
import clauderc.app.git.CommitViewModel
import clauderc.app.git.DiffScreen
import clauderc.app.git.DiffViewModel
import clauderc.app.git.GitActions
import clauderc.app.git.GitScreen
import clauderc.app.git.GitViewModel
import clauderc.app.machines.MachinesScreen
import clauderc.app.machines.MachinesViewModel
import clauderc.app.pair.PairScreen
import clauderc.app.pair.PairUiState
import clauderc.app.pair.PairViewModel
import clauderc.app.protocol.GitDiffRequest
import clauderc.app.sessions.CreateSessionScreen
import clauderc.app.sessions.CreateSessionViewModel
import clauderc.app.sessions.SessionsScreen
import clauderc.app.sessions.SessionsUiState
import clauderc.app.sessions.SessionsViewModel
import clauderc.app.ui.components.GlassNavBar
import clauderc.app.ui.components.NavTab
import clauderc.app.ui.components.SnackState
import clauderc.app.settings.SettingsScreen
import clauderc.app.ui.needsYou
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.LocalAppearance
import clauderc.app.ui.components.LocalLoadingPolicy
import clauderc.app.ui.theme.LocalThemeReveal
import clauderc.app.ui.theme.accentColor
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object PairRoute
@Serializable data object HomeRoute
@Serializable data class CreateRoute(val machineId: String, val profileId: String)
@Serializable data class ChatRoute(val machineId: String, val sessionId: String)
/** A subagent's page; [startedAt] is epoch milliseconds, 0 when unknown. */
@Serializable data class AgentRoute(val machineId: String, val sessionId: String, val agentId: String, val description: String, val type: String, val startedAt: Long)
@Serializable data class TermRoute(val machineId: String, val sessionId: String)
@Serializable data class CommitRoute(val machineId: String, val dir: String, val hash: String)
@Serializable data class DiffRoute(val machineId: String, val dir: String, val path: String, val hash: String? = null, val staged: Boolean = false)

@Composable
fun ClaudercNav(container: AppContainer) {
    val nav = rememberNavController()
    // The home tabs are the glass bar's blur source; the bar itself floats over every page.
    val haze = rememberHazeState()
    // Starting up, a dot runs between a phone and a computer over the whole app until the first machine
    // list arrives. Once the relay fails and waits to retry, the page shows how the reconnect goes instead.
    val links by container.connection.links.collectAsStateWithLifecycle()
    val machines by container.fleet.machines.collectAsStateWithLifecycle()
    val booting = machines == null && links.isNotEmpty() && links.none { it.state is ConnectionState.Waiting }
    LoadingCover(booting, LoadingArt.Link, Modifier.fillMaxSize(), splash = true, label = links.firstOrNull()?.let { "正在连接 ${it.host}" }.orEmpty()) {
        Box(Modifier.fillMaxSize()) {
            ClaudercPages(container, nav, haze)
            HomeNavBar(container, nav, haze, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ClaudercPages(container: AppContainer, nav: NavHostController, haze: HazeState) {
    val scope = rememberCoroutineScope()

    // The machine page is always the first page; scanning starts from its 「添加」.
    ClaudercNavHost(nav, startDestination = HomeRoute) {
        composable<PairRoute> {
            LightSystemBars()
            val vm = viewModel {
                PairViewModel(
                    container.pairing,
                    deviceName(),
                    onPaired = { container.credentials.add(it) },
                    paired = { container.credentials.all().map { it.relay }.toSet() },
                )
            }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(state) {
                when (val s = state) {
                    PairUiState.Paired -> nav.popBackStack(HomeRoute, inclusive = false)
                    is PairUiState.Known -> {
                        container.notice("${s.host} 上的目标机已经都在列表里")
                        nav.popBackStack(HomeRoute, inclusive = false)
                    }
                    else -> Unit
                }
            }
            PairScreen(state, vm::onScanned, vm::rescan, onBack = { nav.popBackStack(HomeRoute, inclusive = false) })
        }
        composable<HomeRoute> {
            HomeScreen(
                container,
                nav,
                haze,
                onAdd = { nav.navigate(PairRoute) { launchSingleTop = true } },
                onClearAll = { scope.launch { container.credentials.clear() } },
            )
        }
        composable<CreateRoute> { entry ->
            val r = entry.toRoute<CreateRoute>()
            val vm = viewModel { CreateSessionViewModel(r.machineId, r.profileId, container.fleet, container.connection, container.profileNames) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                vm.created.collect { id -> nav.navigate(ChatRoute(r.machineId, id)) { popUpTo<CreateRoute> { inclusive = true } } }
            }
            CreateSessionScreen(
                state, { nav.popFrom(entry) }, vm::submit, vm::setDir, vm::pickDir, vm::showAllDirs, vm::setName, vm::setPrompt,
                onTrust = vm::trustAndCreate, onDismissTrust = vm::dismissTrust,
            )
        }
        composable<ChatRoute> { entry ->
            val r = entry.toRoute<ChatRoute>()
            ChatRouteContent(container, r, nav, entry)
        }
        composable<AgentRoute> { entry ->
            val r = entry.toRoute<AgentRoute>()
            AgentRouteContent(container, r, nav, entry)
        }
        composable<TermRoute> { entry ->
            val r = entry.toRoute<TermRoute>()
            val vm = viewModel { TermViewModel(r.machineId, r.sessionId, container.connection, container.fleet, container.scope) }
            val state by vm.state.collectAsStateWithLifecycle()
            val snack = remember { SnackState() }
            LaunchedEffect(vm) { vm.messages.collect { snack.show(it) } }
            TerminalScreen(state, onBack = { nav.popFrom(entry) }, onKey = vm::key, snack = snack)
        }
        composable<CommitRoute> { entry ->
            val r = entry.toRoute<CommitRoute>()
            val vm = viewModel { CommitViewModel(r.machineId, r.dir, r.hash, container.connection) }
            val state by vm.state.collectAsStateWithLifecycle()
            CommitScreen(r.hash, state, onBack = { nav.popFrom(entry) }, onOpenFile = { f -> nav.navigateFrom(entry, DiffRoute(r.machineId, r.dir, f.path, hash = r.hash)) })
        }
        composable<DiffRoute> { entry ->
            val r = entry.toRoute<DiffRoute>()
            val vm = viewModel { DiffViewModel(r.machineId, GitDiffRequest(r.dir, r.path, r.hash, r.staged), container.connection) }
            val state by vm.state.collectAsStateWithLifecycle()
            DiffScreen(r.path, r.hash?.take(7) ?: if (r.staged) "已暂存" else "未暂存", state, onBack = { nav.popFrom(entry) })
        }
    }
}

/**
 * The app's NavHost: a deeper page slides in from the right over the current one, and back
 * slides it off to the right again (Motion rule 2). The system back gesture scrubs a copy of
 * that slide that follows the finger and decelerates the rest of the way once it lets go.
 */
@Composable
fun ClaudercNavHost(nav: NavHostController, startDestination: Any, builder: NavGraphBuilder.() -> Unit) {
    val events = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val gesture = remember(events) {
        Motion.gestureEasing { events?.transitionState?.value is NavigationEventTransitionState.InProgress }
    }
    NavHost(
        navController = nav,
        startDestination = startDestination,
        modifier = Modifier.fillMaxSize().page(),
        enterTransition = { Motion.pushIn },
        exitTransition = { Motion.pushOut },
        popEnterTransition = { Motion.popIn },
        popExitTransition = { Motion.popOut },
        // Without these NavHost scales the leaving page down to 0.7 during the back gesture.
        predictivePopEnterTransition = { Motion.gestureIn(gesture) },
        predictivePopExitTransition = { Motion.gestureOut(gesture) },
        builder = builder,
    )
    // A page is resumed once its slide is over. A back swipe during the slide would scrub the
    // unfinished slide, which on the phone left the chat page stranded a quarter to the left
    // (plans.md 6), so until then the swipe is taken here, after NavHost's own handler, and goes
    // back plainly on release.
    val entry by nav.currentBackStackEntryAsState()
    val resumed = entry?.lifecycle?.currentStateAsState()?.value?.isAtLeast(Lifecycle.State.RESUMED) ?: true
    BackHandler(enabled = !resumed && nav.previousBackStackEntry != null) { entry?.let(nav::popFrom) }
}

/** The camera page is black, so its status bar icons are light. */
@Composable
private fun LightSystemBars() {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val dark = Clauderc.colors.isDark
    DisposableEffect(activity) {
        activity.enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        onDispose { activity.themedSystemBars(dark) }
    }
}

@Composable
private fun HomeScreen(container: AppContainer, nav: NavHostController, haze: HazeState, onAdd: () -> Unit, onClearAll: () -> Unit) {
    val c = Clauderc.colors
    val selection = container.selection
    val tab by selection.tab.collectAsStateWithLifecycle()
    val machines by container.fleet.machines.collectAsStateWithLifecycle()
    val chosen by selection.machineId.collectAsStateWithLifecycle()
    // Default to the first online machine until the user picks one.
    val machineId = chosen ?: machines?.firstOrNull { it.online }?.id
    val snack = remember { SnackState() }
    LaunchedEffect(Unit) { container.notices.collect { snack.show(it) } }
    val shift = with(LocalDensity.current) { 24.dp.roundToPx() }

    Box(Modifier.fillMaxSize().page().hazeSource(haze)) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                Motion.tabIn(shift, dir).togetherWith(Motion.tabOut(shift, dir))
            },
            label = "tabs",
        ) { t ->
            when (t) {
                MainTab.Machines -> {
                    val vm = viewModel { MachinesViewModel(container.fleet, container.connection.links) }
                    val state by vm.state.collectAsStateWithLifecycle()
                    MachinesScreen(state, onOpen = selection::openSessions, onAdd = onAdd, onClearAll = onClearAll, snack = snack)
                }
                MainTab.Sessions -> if (machineId == null) {
                    SessionsScreen(SessionsUiState(), {}, {}, {}, { _, _ -> }, {}, {})
                } else {
                    SessionsTab(container, machineId, nav)
                }
                MainTab.Git -> if (machineId == null) {
                    GitScreen(clauderc.app.git.GitUiState(), GitActions())
                } else {
                    GitTab(container, machineId, nav)
                }
                MainTab.Settings -> SettingsTab(container)
            }
        }
    }
}

/**
 * The glass bar is its own layer over the pages, as in the mockup: it drops away when a deeper page
 * opens and springs back once home is current again. During the back gesture home is not current
 * until the finger lifts, so the bar waits for the release.
 */
@Composable
private fun HomeNavBar(container: AppContainer, nav: NavHostController, haze: HazeState, modifier: Modifier) {
    val entry by nav.currentBackStackEntryAsState()
    // Home is the start page, so the bar is already there before the first entry lands.
    val onHome = entry?.destination?.hasRoute<HomeRoute>() ?: true
    val tab by container.selection.tab.collectAsStateWithLifecycle()
    val sessions by container.fleet.sessions.collectAsStateWithLifecycle()
    val pendingSomewhere = sessions.values.any { list -> list.sessions.any(::needsYou) }
    AnimatedVisibility(onHome, modifier, enter = Motion.navBarIn, exit = Motion.navBarOut) {
        GlassNavBar(
            tabs = listOf(
                NavTab("目标机", Symbols.Computer, Symbols.ComputerFilled),
                NavTab("会话", Symbols.Forum, Symbols.ForumFilled, dot = pendingSomewhere),
                NavTab("git", Symbols.AccountTree, Symbols.AccountTreeFilled),
                NavTab("设置", Symbols.Settings, Symbols.SettingsFilled),
            ),
            selected = tab.ordinal,
            onSelect = { container.selection.tab.value = MainTab.entries[it] },
            hazeState = haze,
        )
    }
}

/** 设置: the pick is saved on this phone and the whole app retints from it. */
@Composable
private fun SettingsTab(container: AppContainer) {
    val look = LocalAppearance.current
    val store = container.theme
    val alwaysLoading by store.alwaysLoading.collectAsStateWithLifecycle(LocalLoadingPolicy.current.always)
    val reveal = LocalThemeReveal.current
    // The new look spreads out from the tapped option; picking what is already set does nothing.
    fun pick(changed: Boolean, at: Offset, save: suspend () -> Unit) {
        if (!changed) return
        val change = { container.scope.launch { save() }; Unit }
        if (reveal != null) reveal.start(at, change) else change()
    }
    SettingsScreen(
        look = look,
        cardAccent = accentColor(look.accent, Clauderc.colors.isDark),
        wallpaperAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        onStyle = { style, at -> pick(style != look.style, at) { store.setStyle(style) } },
        onMode = { mode, at -> pick(mode != look.mode, at) { store.setMode(mode) } },
        onAccent = { accent, at -> pick(accent != look.accent, at) { store.setAccent(accent) } },
        alwaysLoading = alwaysLoading,
        onAlwaysLoading = { on -> container.scope.launch { store.setAlwaysLoading(on) } },
    )
}

@Composable
private fun SessionsTab(container: AppContainer, machineId: String, nav: NavHostController) {
    // The home page's entry: a tap that opens a page counts only while home is resumed.
    val home = LocalLifecycleOwner.current
    val vm = viewModel(key = "sessions:$machineId") { SessionsViewModel(machineId, container.fleet, container.connection, container.profileNames, container.drafts) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackState() }
    LaunchedEffect(vm) { vm.messages.collect { snack.show(it) } }
    SessionsScreen(
        state = state,
        onRefresh = vm::refresh,
        onOpenChat = { nav.navigateFrom(home, ChatRoute(machineId, it.id)) },
        onCreate = { nav.navigateFrom(home, CreateRoute(machineId, it.profile.id)) },
        onRename = vm::rename,
        onStop = vm::stop,
        onRemove = vm::remove,
        onToggle = vm::toggle,
        onToggleDir = vm::toggleDir,
        snack = snack,
        onOpenAgent = { s, a -> nav.navigateFrom(home, agentRoute(machineId, s.id, a)) },
        onToggleForks = vm::toggleForks,
        onBranches = vm::openBranches,
        onFilterBranch = vm::filterBranch,
        onSwitchBranch = vm::switchBranch,
        onConfirmSwitch = vm::confirmSwitch,
        onDismissSwitch = vm::dismissSwitch,
    )
}

private fun agentRoute(machineId: String, sessionId: String, a: Subagent) =
    AgentRoute(machineId, sessionId, a.id.orEmpty(), a.description, a.type, a.startedAt.toEpochMilli().coerceAtLeast(0))

@Composable
private fun AgentRouteContent(container: AppContainer, r: AgentRoute, nav: NavHostController, entry: NavBackStackEntry) {
    val vm = viewModel { ChatViewModel(r.machineId, r.sessionId, container.connection, container.fleet, container.scope, cache = container.chatCache, agentId = r.agentId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackState() }
    LaunchedEffect(vm) { vm.messages.collect { snack.show(it) } }
    AgentScreen(
        state = state,
        header = AgentHeader(r.description, r.type, r.startedAt.takeIf { it > 0 }),
        snack = snack,
        actions = ChatActions(
            onBack = { nav.popFrom(entry) },
            onLoadOlder = vm::loadOlder,
            onToolOutput = vm::loadToolOutput,
            onImage = vm::loadImage,
        ),
    )
}

@Composable
private fun GitTab(container: AppContainer, machineId: String, nav: NavHostController) {
    val home = LocalLifecycleOwner.current
    val dirs by container.selection.gitDirs.collectAsStateWithLifecycle()
    val wanted = dirs[machineId]
    val vm = viewModel(key = "git:$machineId") {
        GitViewModel(machineId, wanted, container.connection, container.fleet, onDirChosen = { container.selection.setGitDir(machineId, it) })
    }
    val state by vm.state.collectAsStateWithLifecycle()
    // The chat page may point the git tab at another directory.
    LaunchedEffect(wanted) { if (wanted != null && wanted != vm.state.value.dir) vm.setDir(wanted) }
    val snack = remember { SnackState() }
    LaunchedEffect(vm) { vm.messages.collect { snack.show(it) } }
    GitScreen(
        state = state,
        snack = snack,
        actions = GitActions(
            onRefresh = vm::refresh,
            onPickDir = vm::setDir,
            onTab = vm::setTab,
            onLoadMore = vm::loadMore,
            onOpenCommit = { hash -> state.dir?.let { nav.navigateFrom(home, CommitRoute(machineId, it, hash)) } },
            onOpenDiff = { path, staged -> state.dir?.let { nav.navigateFrom(home, DiffRoute(machineId, it, path, staged = staged)) } },
            onLoadBranches = vm::loadBranches,
            onRequest = vm::request,
            onConfirm = vm::confirm,
            onDismissConfirm = vm::dismissConfirm,
            onDismissFailure = vm::dismissFailure,
            onMessage = vm::setMessage,
        ),
    )
}

@Composable
private fun ChatRouteContent(container: AppContainer, r: ChatRoute, nav: NavHostController, entry: NavBackStackEntry) {
    val context = LocalContext.current
    val vm = viewModel { ChatViewModel(r.machineId, r.sessionId, container.connection, container.fleet, container.scope, cache = container.chatCache, drafts = container.drafts) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackState() }
    LaunchedEffect(vm) { vm.messages.collect { snack.show(it) } }
    // A session started in a new worktree from here opens on top of this one.
    LaunchedEffect(vm) { vm.created.collect { id -> nav.navigateFrom(entry, ChatRoute(r.machineId, id)) } }

    fun add(uri: Uri) {
        val (name, size) = nameAndSize(context, uri)
        vm.addImage(name, size) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris -> uris.forEach(::add) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) photo?.let(::add) }
    fun shoot() {
        val file = File(context.cacheDir, "camera/photo-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        photo = uri
        take.launch(uri)
    }
    // The manifest declares CAMERA for the QR scanner, so the system camera needs it granted too.
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) shoot() else snack.show("没有相机权限，不能拍照") }

    ChatScreen(
        state = state,
        snack = snack,
        actions = ChatActions(
            onBack = { nav.popFrom(entry) },
            onOpenGit = { cwd ->
                container.selection.openGit(r.machineId, cwd)
                nav.popBackStack(HomeRoute, inclusive = false)
            },
            onLoadOlder = vm::loadOlder,
            onSend = vm::send,
            onInterrupt = vm::interrupt,
            onAllow = vm::allow,
            onDeny = vm::deny,
            onAnswer = vm::answerQuestions,
            onLoadModels = vm::loadModels,
            onSetModel = vm::setModel,
            onSetMode = vm::setPermissionMode,
            onPickImages = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onTakePhoto = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) shoot()
                else askCamera.launch(Manifest.permission.CAMERA)
            },
            onRemoveImage = vm::removeImage,
            onToolOutput = vm::loadToolOutput,
            onImage = vm::loadImage,
            onOpenTerm = { nav.navigateFrom(entry, TermRoute(r.machineId, r.sessionId)) },
            onOpenAgent = { a -> if (a.id != null) nav.navigateFrom(entry, agentRoute(r.machineId, r.sessionId, a)) },
            onDraft = vm::typed,
            onFork = vm::fork,
            onCreateWorktree = vm::createWorktree,
            onClearWorktreeError = vm::clearWorktreeError,
            onAutoResume = vm::setAutoResume,
            onResumeText = vm::typedResume,
        ),
    )
}

private fun nameAndSize(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment ?: "图片"
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) {
            cur.getString(0)?.let { name = it }
            if (!cur.isNull(1)) size = cur.getLong(1)
        }
    }
    if (size < 0) size = context.contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0
    return name to size
}

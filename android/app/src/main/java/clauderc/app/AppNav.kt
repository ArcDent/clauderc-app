package clauderc.app

import clauderc.app.data.Fleet
import clauderc.app.net.ConnectionState
import clauderc.app.net.RelayLink
import clauderc.app.protocol.Machine
import clauderc.app.protocol.SessionList
import clauderc.app.ui.components.SnackHost
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import clauderc.app.git.CommitMorphPanel
import clauderc.app.git.CommitScreen
import clauderc.app.git.CommitViewModel
import clauderc.app.git.DiffMorphPanel
import clauderc.app.git.DiffScreen
import clauderc.app.git.DiffViewModel
import clauderc.app.git.GitActions
import clauderc.app.git.GitOp
import clauderc.app.git.GitScreen
import clauderc.app.git.GitViewModel
import clauderc.app.data.UiStyle
import clauderc.app.machines.ClearPendingStrip
import clauderc.app.machines.InkHubSheet
import clauderc.app.machines.PendingEntry
import clauderc.app.machines.collectPendingOnline
import clauderc.app.pair.PairMorphPanel
import clauderc.app.pair.PairScreen
import clauderc.app.pair.PairUiState
import clauderc.app.pair.PairViewModel
import clauderc.app.protocol.GitDiffRequest
import clauderc.app.sessions.CreateSessionMorphPanel
import clauderc.app.sessions.CreateSessionScreen
import clauderc.app.sessions.CreateSessionViewModel
import clauderc.app.sessions.InkPushDrawerHost
import clauderc.app.sessions.SessionsScreen
import clauderc.app.sessions.SessionsUiState
import clauderc.app.sessions.SessionsViewModel
import clauderc.app.sessions.dirGroups
import clauderc.app.ui.components.GlassNavBar
import clauderc.app.ui.components.NavTab
import clauderc.app.ui.components.SnackState
import clauderc.app.settings.SettingsScreen
import clauderc.app.ui.needsYou
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.LocalAppearance
import clauderc.app.ui.theme.LocalThemeReveal
import clauderc.app.ui.theme.accentColor
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object PairRoute
@Serializable data object HomeRoute
@Serializable data class CreateRoute(val machineId: String)
@Serializable data class ChatRoute(val machineId: String, val sessionId: String)
/** A subagent's page; [startedAt] is epoch milliseconds, 0 when unknown. */
@Serializable data class AgentRoute(val machineId: String, val sessionId: String, val agentId: String, val description: String, val type: String, val startedAt: Long)
@Serializable data class TermRoute(val machineId: String, val sessionId: String, val notice: String? = null)
@Serializable data class CommitRoute(val machineId: String, val dir: String, val hash: String)
@Serializable data class DiffRoute(val machineId: String, val dir: String, val path: String, val hash: String? = null, val staged: Boolean = false)

/** True when any currently online machine has a session waiting for the user (drives the 会话 tab's dot & pending controls). */
internal fun hasPendingOnline(machines: List<Machine>?, sessions: Map<String, SessionList>): Boolean =
    collectPendingOnline(machines, sessions).isNotEmpty()

/** Root-level 浮墨 shell actions: push-aside drawer + in-place morph panels for pairing & creating sessions. */
internal data class InkShellActions(
    val openDrawer: () -> Unit,
    val openPair: (anchor: Rect?, fromTop: Boolean) -> Unit,
    val openCreate: (machineId: String, anchor: Rect?) -> Unit,
)

internal val LocalInkShell = staticCompositionLocalOf<InkShellActions?> { null }

@Composable
fun ClaudercNav(container: AppContainer) {
    val nav = rememberNavController()
    // The home tabs are the glass bar's blur source; the bar itself floats over every page.
    val haze = rememberHazeState()
    val ui = Clauderc.style
    val machines by container.fleet.machines.collectAsStateWithLifecycle()
    val sessions by container.fleet.sessions.collectAsStateWithLifecycle()
    val chosen by container.selection.machineId.collectAsStateWithLifecycle()
    val dirFilter by container.selection.sessionDir.collectAsStateWithLifecycle()
    val effectiveMachineId = container.selection.effectiveMachine(machines) ?: chosen
    val activeMachine = machines?.firstOrNull { it.id == effectiveMachineId }
    val machineSessions = sessions[effectiveMachineId]?.sessions.orEmpty()
    val activeDirs = remember(machineSessions) { dirGroups(machineSessions) }

    var inkDrawerOpen by remember { mutableStateOf(false) }
    var pairMorph by remember { mutableStateOf<Pair<Rect?, Boolean>?>(null) }
    var createMorph by remember { mutableStateOf<Pair<String, Rect?>?>(null) }
    LaunchedEffect(ui) {
        if (ui != UiStyle.Ink) {
            inkDrawerOpen = false
            pairMorph = null
            createMorph = null
        }
    }

    val shellActions = remember(ui) {
        if (ui == UiStyle.Ink) {
            InkShellActions(
                openDrawer = { inkDrawerOpen = true },
                openPair = { rect, fromTop -> pairMorph = rect to fromTop },
                openCreate = { mid, rect -> createMorph = mid to rect },
            )
        } else {
            null
        }
    }

    CompositionLocalProvider(LocalInkShell provides shellActions) {
        val appContent: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize()) {
                ClaudercPages(container, nav, haze)
                HomeNavBar(container, nav, haze, Modifier.align(Alignment.BottomCenter))
            }
        }
        if (ui == UiStyle.Ink) {
            InkPushDrawerHost(
                open = inkDrawerOpen,
                machine = activeMachine,
                dirs = activeDirs,
                totalSessions = machineSessions.size,
                selectedDir = dirFilter,
                onSelectDir = { dir ->
                    container.selection.sessionDir.value = dir
                    container.selection.tab.value = MainTab.Sessions
                    nav.popBackStack(HomeRoute, inclusive = false)
                },
                onScanPairAt = { rect -> pairMorph = rect to false },
                onClose = { inkDrawerOpen = false },
            ) {
                appContent()
            }
        } else {
            appContent()
        }

        pairMorph?.let { (anchor, fromTop) ->
            InkPairMorphHost(
                container = container,
                anchor = anchor,
                fromTop = fromTop,
                onDismiss = { pairMorph = null },
                onPairedDone = {
                    pairMorph = null
                    inkDrawerOpen = false
                },
            )
        }

        createMorph?.let { (machineId, anchor) ->
            InkCreateMorphHost(
                container = container,
                nav = nav,
                machineId = machineId,
                machineName = machines?.firstOrNull { it.id == machineId }?.name.orEmpty(),
                anchor = anchor,
                onDismiss = { createMorph = null },
            )
        }
    }
}

@Composable
private fun InkPairMorphHost(
    container: AppContainer,
    anchor: Rect?,
    fromTop: Boolean,
    onDismiss: () -> Unit,
    onPairedDone: () -> Unit,
) {
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val vm = remember(store) {
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = PairViewModel(
                    container.pairing,
                    deviceName(),
                    onPaired = { container.credentials.add(it) },
                    paired = { container.credentials.all().map { it.relay }.toSet() },
                    appScope = container.scope,
                    onNotice = container::notice,
                ) as T
            },
        )[PairViewModel::class.java]
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        when (val s = state) {
            PairUiState.Paired -> onPairedDone()
            is PairUiState.Known -> {
                container.notice("${s.host} 上的目标机已经都在列表里")
                onPairedDone()
            }
            else -> Unit
        }
    }
    PairMorphPanel(
        state = state,
        onScanned = vm::onScanned,
        onRescan = vm::rescan,
        onDismiss = onDismiss,
        anchor = anchor,
        fromTop = fromTop,
    )
}

@Composable
private fun InkCreateMorphHost(
    container: AppContainer,
    nav: NavHostController,
    machineId: String,
    machineName: String,
    anchor: Rect?,
    onDismiss: () -> Unit,
) {
    val store = remember(machineId) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val vm = remember(store) {
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = CreateSessionViewModel(
                    machineId,
                    container.fleet,
                    container.connection,
                    container.profileNames,
                    container.scope,
                    container::notice,
                ) as T
            },
        )[CreateSessionViewModel::class.java]
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.created.collect { id ->
            onDismiss()
            nav.navigate(ChatRoute(machineId, id)) { launchSingleTop = true }
        }
    }
    CreateSessionMorphPanel(
        machineName = machineName,
        state = state,
        anchor = anchor,
        onClose = onDismiss,
        onSubmit = vm::submit,
        onDir = vm::setDir,
        onPickDir = vm::pickDir,
        onShowAllDirs = vm::showAllDirs,
        onName = vm::setName,
        onPrompt = vm::setPrompt,
        onTrust = vm::trustAndCreate,
        onDismissTrust = vm::dismissTrust,
        onPreset = vm::pickPreset,
        onUrl = vm::setUrl,
        onKey = vm::setKey,
        onToggleKey = vm::toggleKey,
        onToggleModels = vm::toggleModels,
        onModel = vm::setModel,
        onDismissNote = vm::dismissNote,
    )
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
                    appScope = container.scope,
                    onNotice = container::notice,
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
            val vm = viewModel { CreateSessionViewModel(r.machineId, container.fleet, container.connection, container.profileNames, container.scope, container::notice) }
            val state by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(vm) {
                vm.created.collect { id -> nav.navigateWhenResumed(entry, ChatRoute(r.machineId, id)) { popUpTo<CreateRoute> { inclusive = true } } }
            }
            CreateSessionScreen(
                state, { nav.popFrom(entry) }, vm::submit, vm::setDir, vm::pickDir, vm::showAllDirs, vm::setName, vm::setPrompt,
                onTrust = vm::trustAndCreate, onDismissTrust = vm::dismissTrust,
                onPreset = vm::pickPreset, onUrl = vm::setUrl, onKey = vm::setKey, onToggleKey = vm::toggleKey,
                onToggleModels = vm::toggleModels, onModel = vm::setModel, onDismissNote = vm::dismissNote,
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
            val vm = viewModel { TermViewModel(r.machineId, r.sessionId, container.connection, container.fleet, container.scope, initialNotice = r.notice) }
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
    val links by container.connection.links.collectAsStateWithLifecycle()
    val machinesFailed by container.fleet.machinesFailed.collectAsStateWithLifecycle()
    val inkShell = LocalInkShell.current
    HomeTabsContent(
        selection = container.selection,
        fleet = container.fleet,
        notices = container.notices,
        haze = haze,
    ) { tab, machineId ->
        when (tab) {
            MainTab.Sessions -> if (machineId == null) {
                SessionsScreen(
                    state = SessionsUiState(),
                    onRefresh = container.fleet::retryMachines,
                    onOpenChat = {},
                    onCreate = onAdd,
                    onRename = { _, _ -> },
                    onStop = {},
                    onRemove = {},
                    paired = links.isNotEmpty(),
                    loadFailed = machinesFailed,
                    onPair = onAdd,
                    onRetry = container.fleet::retryMachines,
                    onOpenDrawer = inkShell?.openDrawer,
                    onPairAt = inkShell?.let { shell -> { rect -> shell.openPair(rect, true) } },
                    relays = links,
                )
            } else {
                SessionsTab(container, machineId, nav, onPair = onAdd)
            }
            MainTab.Git -> if (machineId == null) {
                GitScreen(
                    clauderc.app.git.GitUiState(),
                    GitActions(),
                    onOpenDrawer = inkShell?.openDrawer,
                )
            } else {
                GitTab(container, machineId, nav)
            }
            MainTab.Settings -> SettingsTab(container, nav, onPair = onAdd, onClearAll = onClearAll)
        }
    }
}

/**
 * The home screen's four tabs and app-wide notice snackbar. Pins the automatically chosen default
 * machine once a machine tab is open so it going offline does not switch tabs to another machine,
 * and shows container notices (such as token revocation) regardless of which tab is open.
 */
@Composable
internal fun HomeTabsContent(
    selection: Selection,
    fleet: Fleet,
    notices: Flow<String>,
    haze: HazeState? = null,
    content: @Composable (MainTab, String?) -> Unit,
) {
    val tab by selection.tab.collectAsStateWithLifecycle()
    val machines by fleet.machines.collectAsStateWithLifecycle()
    val chosen by selection.machineId.collectAsStateWithLifecycle()
    val onMachineTab = tab == MainTab.Sessions || tab == MainTab.Git
    LaunchedEffect(machines, chosen, onMachineTab) { selection.syncMachines(machines, onMachineTab) }
    val machineList = machines
    val machineId = if (chosen != null && (machineList == null || machineList.any { it.id == chosen })) {
        chosen
    } else {
        machineList?.firstOrNull { it.online }?.id
    }
    val snack = remember { SnackState() }
    LaunchedEffect(notices) { notices.collect { snack.show(it) } }
    val shift = with(LocalDensity.current) { 24.dp.roundToPx() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().page().let { if (haze != null) it.hazeSource(haze) else it }) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    Motion.tabIn(shift, dir).togetherWith(Motion.tabOut(shift, dir))
                },
                label = "tabs",
            ) { t ->
                content(t, machineId)
            }
        }
        SnackHost(snack, Modifier.align(Alignment.BottomCenter), bottom = 88.dp)
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
    HomeNavBarContent(
        selection = container.selection,
        fleet = container.fleet,
        visible = onHome,
        haze = haze,
        modifier = modifier,
        onOpenPending = { pending ->
            container.selection.selectMachine(pending.machineId)
            val current = entry
            if (current != null) nav.navigateFrom(current, ChatRoute(pending.machineId, pending.session.id))
            else nav.navigate(ChatRoute(pending.machineId, pending.session.id))
        },
        onPair = { nav.navigate(PairRoute) { launchSingleTop = true } },
        onOfflineMachine = { m -> container.notice("${m.name} 已离线，联网后才能查看会话") },
    )
}

@Composable
internal fun HomeNavBarContent(
    selection: Selection,
    fleet: Fleet,
    visible: Boolean,
    haze: HazeState? = null,
    modifier: Modifier = Modifier,
    onOpenPending: (PendingEntry) -> Unit = {},
    onPair: () -> Unit = {},
    onOfflineMachine: (Machine) -> Unit = {},
) {
    val tab by selection.tab.collectAsStateWithLifecycle()
    val machines by fleet.machines.collectAsStateWithLifecycle()
    val sessions by fleet.sessions.collectAsStateWithLifecycle()
    val currentMachineId by selection.machineId.collectAsStateWithLifecycle()
    val effectiveMachine = selection.effectiveMachine(machines) ?: currentMachineId
    val pending = collectPendingOnline(machines, sessions, effectiveMachine)
    var inkHubOpen by remember { mutableStateOf(false) }
    var inkHubAnchor by remember { mutableStateOf<Rect?>(null) }
    val inkShell = LocalInkShell.current

    AnimatedVisibility(visible, modifier, enter = Motion.navBarIn, exit = Motion.navBarOut) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (Clauderc.style == UiStyle.Clear) {
                AnimatedVisibility(visible = pending.isNotEmpty(), enter = Motion.navBarIn, exit = Motion.navBarOut) {
                    ClearPendingStrip(
                        entries = pending,
                        currentMachineId = effectiveMachine,
                        haze = haze,
                        onOpenNext = onOpenPending,
                    )
                }
            }
            GlassNavBar(
                tabs = listOf(
                    NavTab("会话", Symbols.Forum, Symbols.ForumFilled, dot = pending.isNotEmpty()),
                    NavTab("git", Symbols.AccountTree, Symbols.AccountTreeFilled),
                    NavTab("设置", Symbols.Settings, Symbols.SettingsFilled),
                ),
                selected = tab.ordinal,
                onSelect = { selection.tab.value = MainTab.entries[it] },
                hazeState = haze,
                pendingCount = pending.size,
                onPendingOrbAt = if (Clauderc.style == UiStyle.Ink) ({ rect -> inkHubAnchor = rect; inkHubOpen = true }) else null,
            )
        }
    }

    if (inkHubOpen) {
        val isInk = Clauderc.style == UiStyle.Ink
        InkHubSheet(
            pending = pending,
            machines = machines.orEmpty(),
            sessions = sessions,
            currentMachineId = effectiveMachine,
            onDismiss = { inkHubOpen = false },
            onOpenPending = onOpenPending,
            onSelectMachine = { m ->
                if (m.online) selection.selectMachine(m.id) else onOfflineMachine(m)
            },
            onPair = {
                if (isInk && inkShell != null) inkShell.openPair(inkHubAnchor, false)
                else onPair()
            },
            anchor = inkHubAnchor,
        )
    }
}

/** 设置: the pick is saved on this phone and the whole app retints from it. */
@Composable
private fun SettingsTab(container: AppContainer, nav: NavHostController, onPair: () -> Unit, onClearAll: () -> Unit) {
    val home = LocalLifecycleOwner.current
    val look = LocalAppearance.current
    val store = container.theme
    val relays by container.connection.links.collectAsStateWithLifecycle()
    val machines by container.fleet.machines.collectAsStateWithLifecycle()
    val allSessions by container.fleet.sessions.collectAsStateWithLifecycle()
    val currentMachineId by container.selection.machineId.collectAsStateWithLifecycle()
    val effectiveMachine = container.selection.effectiveMachine(machines) ?: currentMachineId
    val activeMachine = machines?.firstOrNull { it.id == effectiveMachine }
    val pending = collectPendingOnline(machines, allSessions, currentMachineId)
    val inkShell = LocalInkShell.current
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
        onPlateInk = { ink, at -> pick(ink != look.plateInk, at) { store.setPlateInk(ink) } },
        onPlateNav = { navStyle -> container.scope.launch { store.setPlateNav(navStyle) } },
        relays = relays,
        onPair = onPair,
        onClearAll = onClearAll,
        pendingCount = pending.size,
        onOpenPending = {
            pending.firstOrNull()?.let { next ->
                container.selection.selectMachine(next.machineId)
                nav.navigateFrom(home, ChatRoute(next.machineId, next.session.id))
            }
        },
        onOpenDrawer = inkShell?.openDrawer,
        onPairAt = inkShell?.let { shell -> { rect -> shell.openPair(rect, false) } },
        machine = activeMachine,
        machines = machines.orEmpty(),
    )
}

@Composable
private fun SessionsTab(container: AppContainer, machineId: String, nav: NavHostController, onPair: () -> Unit) {
    // The home page's entry: a tap that opens a page counts only while home is resumed.
    val home = LocalLifecycleOwner.current
    val vm = viewModel(key = "sessions:$machineId") { SessionsViewModel(machineId, container.fleet, container.connection, container.profileNames, container.drafts) }
    val state by vm.state.collectAsStateWithLifecycle()
    val machines by container.fleet.machines.collectAsStateWithLifecycle()
    val allSessions by container.fleet.sessions.collectAsStateWithLifecycle()
    val relays by container.connection.links.collectAsStateWithLifecycle()
    val dirFilter by container.selection.sessionDir.collectAsStateWithLifecycle()
    val inkTab by container.selection.sessionTab.collectAsStateWithLifecycle()
    val pending = collectPendingOnline(machines, allSessions, machineId)
    val inkShell = LocalInkShell.current
    val snack = remember { SnackState() }
    LaunchedEffect(vm) { vm.messages.collect { snack.show(it) } }
    SessionsScreen(
        state = state,
        onRefresh = vm::refresh,
        onOpenChat = { nav.navigateFrom(home, ChatRoute(machineId, it.id)) },
        onCreate = { nav.navigateFrom(home, CreateRoute(machineId)) },
        onRename = vm::rename,
        onStop = vm::stop,
        onRemove = vm::remove,
        onForget = vm::forget,
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
        onPair = onPair,
        machines = machines.orEmpty(),
        allSessions = allSessions,
        pendingEntries = pending,
        dirFilter = dirFilter,
        onSelectDirFilter = { container.selection.sessionDir.value = it },
        initialInkTab = inkTab,
        onInkTabChange = { container.selection.sessionTab.value = it },
        onSelectMachine = { m ->
            if (m.online) container.selection.selectMachine(m.id)
            else container.notice("${m.name} 已离线，联网后才能查看会话")
        },
        onOpenPending = { entry ->
            container.selection.selectMachine(entry.machineId)
            nav.navigateFrom(home, ChatRoute(entry.machineId, entry.session.id))
        },
        onOpenDrawer = inkShell?.openDrawer,
        onCreateAt = inkShell?.let { shell -> { rect -> shell.openCreate(machineId, rect) } },
        onPairAt = inkShell?.let { shell -> { rect -> shell.openPair(rect, true) } },
        relays = relays,
    )
}

private fun agentRoute(machineId: String, sessionId: String, a: Subagent) =
    AgentRoute(machineId, sessionId, a.id.orEmpty(), a.description, a.type, a.startedAt.toEpochMilli().coerceAtLeast(0))

@Composable
private fun AgentRouteContent(container: AppContainer, r: AgentRoute, nav: NavHostController, entry: NavBackStackEntry) {
    val vm = viewModel { ChatViewModel(r.machineId, r.sessionId, container.connection, container.fleet, container.scope, cache = container.chatCache, agentId = r.agentId, saver = container.gallery) }
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
            onSaveImage = vm::saveImage,
            onViewer = vm::viewImage,
        ),
    )
}

private data class InkDiffTarget(
    val dir: String,
    val path: String,
    val hash: String?,
    val staged: Boolean,
    val anchor: Rect?,
    val fromTop: Boolean,
)

@Composable
private fun GitTab(container: AppContainer, machineId: String, nav: NavHostController) {
    val home = LocalLifecycleOwner.current
    val ui = Clauderc.style
    val inkShell = LocalInkShell.current
    val dirs by container.selection.gitDirs.collectAsStateWithLifecycle()
    val machines by container.fleet.machines.collectAsStateWithLifecycle()
    val allSessions by container.fleet.sessions.collectAsStateWithLifecycle()
    val pending = collectPendingOnline(machines, allSessions, machineId)
    val wanted = dirs[machineId]
    val vm = viewModel(key = "git:$machineId") {
        GitViewModel(machineId, wanted, container.connection, container.fleet, onDirChosen = { container.selection.setGitDir(machineId, it) })
    }
    val state by vm.state.collectAsStateWithLifecycle()
    // The chat page may point the git tab at another directory.
    LaunchedEffect(wanted) { if (wanted != null && wanted != vm.state.value.dir) vm.setDir(wanted) }
    val snack = remember { SnackState() }
    LaunchedEffect(vm) { vm.messages.collect { snack.show(it) } }

    var commitTrail by remember(machineId, state.dir) { mutableStateOf<Pair<List<String>, Rect?>?>(null) }
    var diffTarget by remember(machineId, state.dir) { mutableStateOf<InkDiffTarget?>(null) }
    LaunchedEffect(ui) {
        if (ui != UiStyle.Ink) {
            commitTrail = null
            diffTarget = null
        }
    }

    GitScreen(
        state = state,
        snack = snack,
        pendingCount = pending.size,
        onOpenPending = {
            pending.firstOrNull()?.let { next ->
                container.selection.selectMachine(next.machineId)
                nav.navigateFrom(home, ChatRoute(next.machineId, next.session.id))
            }
        },
        onOpenDrawer = inkShell?.openDrawer,
        machines = machines.orEmpty(),
        currentMachineId = machineId,
        onSelectMachine = { m ->
            if (m.online) container.selection.selectMachine(m.id)
            else container.notice("${m.name} 已离线，联网后才能查看仓库")
        },
        actions = GitActions(
            onRefresh = vm::refresh,
            onPickDir = vm::setDir,
            onTab = vm::setTab,
            onLoadMore = vm::loadMore,
            onOpenCommit = { hash -> state.dir?.let { nav.navigateFrom(home, CommitRoute(machineId, it, hash)) } },
            onOpenDiff = { path, staged -> state.dir?.let { nav.navigateFrom(home, DiffRoute(machineId, it, path, staged = staged)) } },
            onOpenCommitAt = if (ui == UiStyle.Ink) ({ hash, rect -> commitTrail = listOf(hash) to rect }) else null,
            onOpenDiffAt = if (ui == UiStyle.Ink) ({ path, staged, rect ->
                state.dir?.let { d -> diffTarget = InkDiffTarget(d, path, null, staged, rect, fromTop = false) }
            }) else null,
            onLoadBranches = vm::loadBranches,
            onRequest = vm::request,
            onConfirm = vm::confirm,
            onDismissConfirm = vm::dismissConfirm,
            onDismissFailure = vm::dismissFailure,
            onMessage = vm::setMessage,
        ),
    )

    val activeDir = state.dir
    commitTrail?.let { (hashes, anchor) ->
        val currentHash = hashes.lastOrNull()
        if (activeDir != null && currentHash != null) {
            InkCommitMorphHost(
                container = container,
                machineId = machineId,
                dir = activeDir,
                hash = currentHash,
                canGoBack = hashes.size > 1,
                anchor = anchor,
                onBackParent = {
                    val popped = hashes.dropLast(1)
                    commitTrail = if (popped.isEmpty()) null else popped to anchor
                },
                onClose = { commitTrail = null },
                onOpenParent = { parentHash -> commitTrail = (hashes + parentHash) to anchor },
                onOpenFileAt = { file, fileRect ->
                    diffTarget = InkDiffTarget(activeDir, file.path, currentHash, staged = false, anchor = fileRect, fromTop = true)
                },
            )
        }
    }

    diffTarget?.let { target ->
        val isWorkingTree = target.hash == null
        val isStaged = if (isWorkingTree) {
            state.status?.staged?.any { it.path == target.path } ?: target.staged
        } else {
            false
        }
        InkDiffMorphHost(
            container = container,
            machineId = machineId,
            target = target.copy(staged = isStaged),
            busy = state.running,
            onToggleStage = if (isWorkingTree) ({
                vm.request(if (isStaged) GitOp.Unstage(target.path) else GitOp.Stage(target.path))
                diffTarget = target.copy(staged = !isStaged)
            }) else null,
            onClose = { diffTarget = null },
        )
    }
}

@Composable
private fun InkCommitMorphHost(
    container: AppContainer,
    machineId: String,
    dir: String,
    hash: String,
    canGoBack: Boolean,
    anchor: Rect?,
    onBackParent: () -> Unit,
    onClose: () -> Unit,
    onOpenParent: (String) -> Unit,
    onOpenFileAt: (clauderc.app.protocol.CommitFile, Rect?) -> Unit,
) {
    val store = remember(machineId, dir, hash) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val vm = remember(store) {
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CommitViewModel(machineId, dir, hash, container.connection) as T
            },
        )[CommitViewModel::class.java]
    }
    val state by vm.state.collectAsStateWithLifecycle()
    CommitMorphPanel(
        dir = dir,
        hash = hash,
        canGoBack = canGoBack,
        state = state,
        anchor = anchor,
        onBackParent = onBackParent,
        onClose = onClose,
        onOpenParent = onOpenParent,
        onOpenFileAt = onOpenFileAt,
    )
}

@Composable
private fun InkDiffMorphHost(
    container: AppContainer,
    machineId: String,
    target: InkDiffTarget,
    busy: Boolean,
    onToggleStage: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val store = remember(machineId, target.dir, target.path, target.hash, target.staged) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val vm = remember(store) {
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DiffViewModel(machineId, GitDiffRequest(target.dir, target.path, target.hash, target.staged), container.connection) as T
            },
        )[DiffViewModel::class.java]
    }
    val state by vm.state.collectAsStateWithLifecycle()
    DiffMorphPanel(
        path = target.path,
        subtitle = target.hash?.take(7) ?: if (target.staged) "已暂存" else "未暂存",
        state = state,
        anchor = target.anchor,
        fromTop = target.fromTop,
        staged = if (target.hash == null) target.staged else null,
        busy = busy,
        onToggleStage = onToggleStage,
        onClose = onClose,
    )
}

@Composable
private fun ChatRouteContent(container: AppContainer, r: ChatRoute, nav: NavHostController, entry: NavBackStackEntry) {
    val context = LocalContext.current
    val vm = viewModel { ChatViewModel(r.machineId, r.sessionId, container.connection, container.fleet, container.scope, cache = container.chatCache, drafts = container.drafts, saver = container.gallery, onNotice = container::notice) }
    val state by vm.state.collectAsStateWithLifecycle()
    val machines by container.fleet.machines.collectAsStateWithLifecycle()
    val allSessions by container.fleet.sessions.collectAsStateWithLifecycle()
    val nextPending = collectPendingOnline(machines, allSessions, r.machineId).firstOrNull {
        it.machineId != r.machineId || it.session.id != r.sessionId
    }
    val snack = remember { SnackState() }
    LaunchedEffect(vm) { vm.messages.collect { snack.show(it) } }
    var createdWorktree by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(vm) { vm.created.collect { createdWorktree = it } }
    // A failed rewind falls back to the terminal view on the main thread, never directly from appScope.
    LaunchedEffect(vm) { vm.rewindFailed.collect { reason -> nav.navigateWhenResumed(entry, TermRoute(r.machineId, r.sessionId, notice = reason)) } }

    fun add(uri: Uri) {
        val (name, size) = nameAndSize(context, uri)
        vm.addImage(name, size) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> uris.forEach(::add) }
    var finishPhoto: (Boolean) -> Unit = {}
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> finishPhoto(ok) }
    val (startPhoto, onPhotoResult) = rememberPhotoCapture(onCaptured = ::add, launch = take::launch)
    finishPhoto = onPhotoResult
    fun shoot() {
        val file = File(context.cacheDir, "camera/photo-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        startPhoto(uri)
    }
    // The manifest declares CAMERA for the QR scanner, so the system camera needs it granted too.
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) shoot() else snack.show("没有相机权限，不能拍照") }

    ChatScreen(
        state = state,
        snack = snack,
        nextPending = nextPending,
        createdWorktree = createdWorktree,
        onWorktreeClosed = { id ->
            nav.navigateWhenResumed(entry, ChatRoute(r.machineId, id))
            createdWorktree = null
        },
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
            onSaveImage = vm::saveImage,
            onViewer = vm::viewImage,
            onOpenTerm ={ nav.navigateFrom(entry, TermRoute(r.machineId, r.sessionId)) },
            onOpenAgent = { a -> if (a.id != null) nav.navigateFrom(entry, agentRoute(r.machineId, r.sessionId, a)) },
            onDraft = vm::typed,
            onRestored = vm::restored,
            onFork = vm::fork,
            onCreateWorktree = vm::createWorktree,
            onClearWorktreeError = vm::clearWorktreeError,
            onAutoResume = vm::setAutoResume,
            onResumeText = vm::typedResume,
            onRewind = vm::rewind,
            onOpenNextPending = { next ->
                container.selection.selectMachine(next.machineId)
                nav.navigate(ChatRoute(next.machineId, next.session.id)) { popUpTo<ChatRoute> { inclusive = true } }
            },
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

/** Keeps the camera output URI across Activity recreation while the system camera app is open. */
@Composable
internal fun rememberPhotoCapture(onCaptured: (Uri) -> Unit, launch: (Uri) -> Unit): Pair<(Uri) -> Unit, (Boolean) -> Unit> {
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val start: (Uri) -> Unit = { uri ->
        pending = uri.toString()
        launch(uri)
    }
    val finish: (Boolean) -> Unit = { ok ->
        val saved = pending
        pending = null
        if (ok && saved != null) onCaptured(Uri.parse(saved))
    }
    return start to finish
}

package clauderc.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import clauderc.app.chat.ChatActions
import clauderc.app.chat.ChatScreen
import clauderc.app.chat.ChatViewModel
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
import clauderc.app.protocol.SessionState
import clauderc.app.sessions.CreateSessionScreen
import clauderc.app.sessions.CreateSessionViewModel
import clauderc.app.sessions.SessionsScreen
import clauderc.app.sessions.SessionsUiState
import clauderc.app.sessions.SessionsViewModel
import clauderc.app.ui.components.GlassNavBar
import clauderc.app.ui.components.NavTab
import clauderc.app.ui.components.SnackState
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object PairRoute
@Serializable data object HomeRoute
@Serializable data class CreateRoute(val machineId: String, val profileId: String)
@Serializable data class ChatRoute(val machineId: String, val sessionId: String)
@Serializable data class CommitRoute(val machineId: String, val dir: String, val hash: String)
@Serializable data class DiffRoute(val machineId: String, val dir: String, val path: String, val hash: String? = null, val staged: Boolean = false)

@Composable
fun ClaudercNav(container: AppContainer) {
    val nav = rememberNavController()
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
            CreateSessionScreen(state, nav::popBackStack, vm::submit, vm::setDir, vm::pickDir, vm::showAllDirs, vm::setName, vm::setPrompt)
        }
        composable<ChatRoute> { entry ->
            val r = entry.toRoute<ChatRoute>()
            ChatRouteContent(container, r, nav)
        }
        composable<CommitRoute> { entry ->
            val r = entry.toRoute<CommitRoute>()
            val vm = viewModel { CommitViewModel(r.machineId, r.dir, r.hash, container.connection) }
            val state by vm.state.collectAsStateWithLifecycle()
            CommitScreen(r.hash, state, onBack = nav::popBackStack, onOpenFile = { f -> nav.navigate(DiffRoute(r.machineId, r.dir, f.path, hash = r.hash)) })
        }
        composable<DiffRoute> { entry ->
            val r = entry.toRoute<DiffRoute>()
            val vm = viewModel { DiffViewModel(r.machineId, GitDiffRequest(r.dir, r.path, r.hash, r.staged), container.connection) }
            val state by vm.state.collectAsStateWithLifecycle()
            DiffScreen(r.path, r.hash?.take(7) ?: if (r.staged) "已暂存" else "未暂存", state, onBack = nav::popBackStack)
        }
    }
}

/**
 * The app's NavHost: a deeper page slides in from the right over the current one, and back
 * slides it off to the right again (Motion rule 2). The system back gesture scrubs the same
 * transition, so the page follows the finger.
 */
@Composable
fun ClaudercNavHost(nav: NavHostController, startDestination: Any, builder: NavGraphBuilder.() -> Unit) {
    NavHost(
        navController = nav,
        startDestination = startDestination,
        modifier = Modifier.fillMaxSize().background(Clauderc.colors.bg),
        enterTransition = { Motion.pushIn },
        exitTransition = { Motion.pushOut },
        popEnterTransition = { Motion.popIn },
        popExitTransition = { Motion.popOut },
        builder = builder,
    )
}

/** The camera page is black, so its status bar icons are light. */
@Composable
private fun LightSystemBars() {
    val activity = LocalContext.current as? ComponentActivity ?: return
    DisposableEffect(activity) {
        activity.enableEdgeToEdge(SystemBarStyle.dark(AndroidColor.TRANSPARENT), SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        onDispose { activity.enableEdgeToEdge() }
    }
}

@Composable
private fun HomeScreen(container: AppContainer, nav: NavHostController, onAdd: () -> Unit, onClearAll: () -> Unit) {
    val c = Clauderc.colors
    val selection = container.selection
    val tab by selection.tab.collectAsStateWithLifecycle()
    val machines by container.fleet.machines.collectAsStateWithLifecycle()
    val sessions by container.fleet.sessions.collectAsStateWithLifecycle()
    val chosen by selection.machineId.collectAsStateWithLifecycle()
    // Default to the first online machine until the user picks one.
    val machineId = chosen ?: machines?.firstOrNull { it.online }?.id
    val pendingSomewhere = sessions.values.any { list -> list.sessions.any { it.state == SessionState.Pending } }
    val haze = rememberHazeState()
    val snack = remember { SnackState() }
    LaunchedEffect(Unit) { container.notices.collect { snack.show(it) } }
    val shift = with(LocalDensity.current) { 24.dp.roundToPx() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(c.bg).hazeSource(haze)) {
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
                        SessionsScreen(SessionsUiState(), {}, {}, {}, {}, { _, _ -> }, {}, {})
                    } else {
                        SessionsTab(container, machineId, nav)
                    }
                    MainTab.Git -> if (machineId == null) {
                        GitScreen(clauderc.app.git.GitUiState(), GitActions())
                    } else {
                        GitTab(container, machineId, nav)
                    }
                }
            }
        }
        GlassNavBar(
            tabs = listOf(
                NavTab("目标机", Symbols.Computer, Symbols.ComputerFilled),
                NavTab("会话", Symbols.Forum, Symbols.ForumFilled, dot = pendingSomewhere),
                NavTab("git", Symbols.AccountTree, Symbols.AccountTreeFilled),
            ),
            selected = tab.ordinal,
            onSelect = { selection.tab.value = MainTab.entries[it] },
            hazeState = haze,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SessionsTab(container: AppContainer, machineId: String, nav: NavHostController) {
    val vm = viewModel(key = "sessions:$machineId") { SessionsViewModel(machineId, container.fleet, container.connection, container.profileNames) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackState() }
    LaunchedEffect(vm) { vm.messages.collect { snack.show(it) } }
    SessionsScreen(
        state = state,
        onRefresh = vm::refresh,
        onOpenChat = { nav.navigate(ChatRoute(machineId, it.id)) },
        onCreate = { nav.navigate(CreateRoute(machineId, it.profile.id)) },
        onOpenGit = { container.selection.openGit(machineId, it.cwd) },
        onRename = vm::rename,
        onStop = vm::stop,
        onRemove = vm::remove,
        onToggle = vm::toggle,
        snack = snack,
    )
}

@Composable
private fun GitTab(container: AppContainer, machineId: String, nav: NavHostController) {
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
            onOpenCommit = { hash -> state.dir?.let { nav.navigate(CommitRoute(machineId, it, hash)) } },
            onOpenDiff = { path, staged -> state.dir?.let { nav.navigate(DiffRoute(machineId, it, path, staged = staged)) } },
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
private fun ChatRouteContent(container: AppContainer, r: ChatRoute, nav: NavHostController) {
    val context = LocalContext.current
    val vm = viewModel { ChatViewModel(r.machineId, r.sessionId, container.connection, container.fleet, container.scope, cache = container.chatCache) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackState() }
    LaunchedEffect(vm) { vm.messages.collect { snack.show(it) } }

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
            onBack = nav::popBackStack,
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

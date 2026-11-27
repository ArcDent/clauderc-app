package clauderc.app.git

import clauderc.app.ui.components.smallPressButton
import clauderc.app.ui.components.smallButtonTint
import clauderc.app.ui.components.CardCorner
import clauderc.app.ui.components.FieldLabel
import clauderc.app.ui.components.LocalMorphClose
import clauderc.app.ui.components.PlateClipShape
import clauderc.app.ui.components.PlateSweep
import clauderc.app.ui.components.UnderlineField
import clauderc.app.ui.components.inkSurface
import clauderc.app.ui.components.plateBox
import clauderc.app.ui.components.plateHardShadow
import clauderc.app.ui.components.plateStamp
import clauderc.app.ui.components.plateSweepFill
import clauderc.app.ui.components.tileSurface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import clauderc.app.sessions.ClearGlassTopBar
import clauderc.app.sessions.TileMiniBar
import clauderc.app.ui.components.glassPane
import clauderc.app.ui.components.topHighlight
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.machines.NavClearance
import clauderc.app.machines.PendingJumpBadge
import clauderc.app.protocol.Commit
import clauderc.app.protocol.FileChange
import clauderc.app.protocol.FileStatus
import clauderc.app.protocol.Machine
import clauderc.app.protocol.RefKind
import clauderc.app.sessions.InkBrushTabs
import clauderc.app.sessions.InkTopBar
import clauderc.app.sessions.InkTopRight
import clauderc.app.sessions.shortPath
import clauderc.app.ui.TimeText
import clauderc.app.ui.baseName
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.BarSubButton
import clauderc.app.ui.components.BarTitle
import clauderc.app.ui.components.ClaudercDialog
import clauderc.app.ui.components.ClaudercSheet
import clauderc.app.ui.components.Glyph
import clauderc.app.ui.components.MenuItem
import clauderc.app.ui.components.PillButton
import clauderc.app.ui.components.PillStyle
import clauderc.app.ui.components.SheetHead
import clauderc.app.ui.components.SheetSection
import clauderc.app.ui.components.SnackHost
import clauderc.app.ui.components.SnackState
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.components.StatusGlyph
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.components.TextAction
import clauderc.app.ui.components.captureAnchor
import clauderc.app.ui.components.rememberAnchorBox
import androidx.compose.ui.graphics.RectangleShape
import clauderc.app.ui.components.groupItem
import clauderc.app.ui.theme.pressPose
import clauderc.app.ui.theme.pressProgress
import clauderc.app.ui.theme.listRowPress
import clauderc.app.ui.theme.pressClickable
import clauderc.app.ui.theme.controlPress
import clauderc.app.ui.theme.PressRow
import clauderc.app.ui.theme.PressTone
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.PressKind
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.pressShrink
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class GitActions(
    val onRefresh: () -> Unit = {},
    val onPickDir: (String) -> Unit = {},
    val onTab: (GitTab) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onOpenCommit: (String) -> Unit = {},
    val onOpenDiff: (path: String, staged: Boolean) -> Unit = { _, _ -> },
    val onOpenCommitAt: ((hash: String, anchor: Rect?) -> Unit)? = null,
    val onOpenDiffAt: ((path: String, staged: Boolean, anchor: Rect?) -> Unit)? = null,
    val onLoadBranches: () -> Unit = {},
    val onRequest: (GitOp) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onDismissFailure: () -> Unit = {},
    val onMessage: (String) -> Unit = {},
)

private sealed interface GitSheet {
    data class Repo(val anchor: Rect? = null, val fromTop: Boolean = false) : GitSheet
    data class Branches(val anchor: Rect? = null) : GitSheet
    data class Commit(val anchor: Rect? = null) : GitSheet
}

@Composable
fun GitScreen(
    state: GitUiState,
    actions: GitActions,
    snack: SnackState = remember { SnackState() },
    pendingCount: Int = 0,
    onOpenPending: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null,
    machines: List<Machine> = emptyList(),
    currentMachineId: String? = null,
    onSelectMachine: (Machine) -> Unit = {},
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    var sheet by remember { mutableStateOf<GitSheet?>(null) }
    val dir = state.dir
    val currentMachine = machines.firstOrNull { it.id == currentMachineId }
    val inkTopPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 74.dp
    val timelineList = rememberLazyListState()
    val changesList = rememberLazyListState()
    val clearHaze = if (ui == UiStyle.Clear) rememberHazeState() else null
    val clearScrollPx = with(LocalDensity.current) { 90.dp.toPx() }
    val tileScrollPx = with(LocalDensity.current) { 70.dp.toPx() }
    val clearScrolled by remember(state.tab, timelineList, changesList, clearScrollPx) {
        derivedStateOf {
            val active = if (state.tab == GitTab.Log) timelineList else changesList
            active.firstVisibleItemIndex > 0 || active.firstVisibleItemScrollOffset > clearScrollPx
        }
    }
    val tileScrolled by remember(state.tab, timelineList, changesList, tileScrollPx) {
        derivedStateOf {
            val active = if (state.tab == GitTab.Log) timelineList else changesList
            active.firstVisibleItemIndex > 0 || active.firstVisibleItemScrollOffset > tileScrollPx
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (clearHaze != null) Modifier.hazeSource(clearHaze) else Modifier)
                .imePadding()
                .padding(top = if (ui == UiStyle.Ink) inkTopPad else 0.dp),
        ) {
            when (ui) {
                UiStyle.Ink -> Unit
                UiStyle.Plate -> PlateGitHeader(
                    dir = dir,
                    machineName = currentMachine?.name,
                    pendingCount = pendingCount,
                    onRefresh = actions.onRefresh,
                    onPickRepo = { sheet = GitSheet.Repo() },
                    onOpenPending = onOpenPending,
                )
                UiStyle.Clear -> ClearGitHeader(
                    dir = dir,
                    machineName = currentMachine?.name,
                    repoOpen = sheet is GitSheet.Repo,
                    onPickRepo = { sheet = GitSheet.Repo() },
                    onBranches = { actions.onLoadBranches(); sheet = GitSheet.Branches() },
                    onRefresh = actions.onRefresh,
                )
                UiStyle.Tile -> TileGitHeader(
                    dir = dir,
                    machineName = currentMachine?.name,
                    pendingCount = pendingCount,
                    onPickRepo = { sheet = GitSheet.Repo() },
                    onBranches = { actions.onLoadBranches(); sheet = GitSheet.Branches() },
                    onRefresh = actions.onRefresh,
                    onOpenPending = onOpenPending,
                )
            }
            when {
                dir == null -> Empty("还没有目录", null, "选择目录") { sheet = GitSheet.Repo() }
                state.notRepo -> Empty("不是 git 仓库", shortPath(dir), "换一个目录") { sheet = GitSheet.Repo() }
                else -> Column(Modifier.weight(1f).fillMaxWidth()) {
                    Tabs(state, actions.onTab, clearHaze)
                    BranchBar(state) { rect -> actions.onLoadBranches(); sheet = GitSheet.Branches(rect) }
                    if (state.loading && state.status == null && state.commits.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Spinner(color = c.fg3) }
                    } else if (ui == UiStyle.Plate) {
                        // 印版: leaving pane wipes out to the right while the new pane prints in from the left.
                        AnimatedContent(
                            targetState = state.tab,
                            modifier = Modifier.weight(1f),
                            transitionSpec = {
                                fadeIn(tween(510), initialAlpha = 0.999f)
                                    .togetherWith(fadeOut(tween(210), targetAlpha = 0.999f))
                            },
                            label = "plateGitPanes",
                        ) { tab ->
                            val leftClip by transition.animateFloat(
                                transitionSpec = { tween(210, easing = Motion.inkEaseIn) },
                                label = "platePaneWipe",
                            ) { st -> if (st == EnterExitState.PostExit) 1f else 0f }
                            val rightClip by transition.animateFloat(
                                transitionSpec = { tween(420, delayMillis = 90, easing = Motion.plateEasePress) },
                                label = "platePanePrint",
                            ) { st -> if (st == EnterExitState.PreEnter) 0f else 1f }
                            val paneShape = remember { PlateClipShape({ leftClip }, { rightClip }) }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        clip = leftClip > 0.001f || rightClip < 0.999f
                                        shape = paneShape
                                    },
                            ) {
                                if (tab == GitTab.Log) {
                                    Timeline(state, actions, timelineList)
                                } else {
                                    StatusPage(
                                        state = state,
                                        actions = actions,
                                        changesList = changesList,
                                        pendingCount = pendingCount,
                                        clearHaze = clearHaze,
                                        onNeedStage = { snack.show("先点右侧 + 暂存要提交的文件") },
                                        onOpenCommit = { rect -> sheet = GitSheet.Commit(rect) },
                                    )
                                }
                            }
                        }
                    } else {
                        // The two tabs swap like the top-level pages: a 24dp shift and a fade.
                        val shift = with(LocalDensity.current) { 24.dp.roundToPx() }
                        AnimatedContent(
                            targetState = state.tab,
                            modifier = Modifier.weight(1f),
                            transitionSpec = {
                                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                Motion.tabIn(shift, dir).togetherWith(Motion.tabOut(shift, dir))
                            },
                            label = "gitTabs",
                        ) { tab ->
                            if (tab == GitTab.Log) {
                                Timeline(state, actions, timelineList)
                            } else {
                                StatusPage(
                                    state = state,
                                    actions = actions,
                                    changesList = changesList,
                                    pendingCount = pendingCount,
                                    clearHaze = clearHaze,
                                    onNeedStage = { snack.show("先点右侧 + 暂存要提交的文件") },
                                    onOpenCommit = { rect -> sheet = GitSheet.Commit(rect) },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (ui == UiStyle.Tile) {
            TileMiniBar(
                visible = tileScrolled,
                title = dir?.let(::baseName) ?: "git",
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (ui == UiStyle.Clear) {
            ClearGlassTopBar(
                visible = clearScrolled,
                title = dir?.let(::baseName) ?: "git",
                haze = clearHaze,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (ui == UiStyle.Ink) {
            InkTopBar(
                title = dir?.let(::baseName) ?: "git",
                online = currentMachine?.online ?: true,
                showDot = currentMachine != null || dir != null,
                rightAction = InkTopRight.Refresh,
                rightEnabled = dir != null,
                onOpenDrawer = { onOpenDrawer?.invoke() },
                onPillClick = { rect -> sheet = GitSheet.Repo(rect, fromTop = true) },
                onRightClick = { actions.onRefresh() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        val hasChanges = state.status?.let { it.staged.isNotEmpty() || it.unstaged.isNotEmpty() } == true
        val snackBottom = when {
            state.tab == GitTab.Status && hasChanges -> if (ui == UiStyle.Clear && pendingCount > 0) 208.dp else 154.dp
            ui == UiStyle.Clear && pendingCount > 0 -> 148.dp
            else -> 88.dp
        }
        SnackHost(snack, Modifier.align(Alignment.BottomCenter), bottom = snackBottom)
    }

    when (val s = sheet) {
        is GitSheet.Repo -> ClaudercSheet(onDismiss = { sheet = null }, anchor = s.anchor, fromTop = s.fromTop) {
            SheetHead("最近的目录")
            if (state.dirs.isEmpty()) {
                Text("目标机上还没有最近用过的目录", Modifier.padding(horizontal = Clauderc.spacing.page), color = c.fg3, fontSize = Clauderc.fontSizes.label)
            }
            state.dirs.forEach { d ->
                MenuItem(baseName(d), onClick = { sheet = null; actions.onPickDir(d) }, icon = Symbols.Folder, supporting = shortPath(d), supportingMono = true, checked = d == dir)
            }
        }
        is GitSheet.Branches -> ClaudercSheet(onDismiss = { sheet = null }, anchor = s.anchor, fromTop = ui == UiStyle.Ink) {
            SheetHead("切换分支")
            val b = state.branches
            if (b == null) {
                Row(Modifier.padding(horizontal = Clauderc.spacing.page, vertical = Clauderc.spacing.sp4)) { Spinner(size = 16.dp, color = c.fg3) }
            } else {
                SheetSection("本地")
                b.local.forEach { name ->
                    MenuItem(name, onClick = { sheet = null; if (name != b.current) actions.onRequest(GitOp.Switch(name)) }, mono = true, checked = name == b.current)
                }
                val remoteOnly = b.remote.filter { r -> r.name !in b.local && r.name != "HEAD" }
                if (remoteOnly.isNotEmpty()) {
                    SheetSection("只在远端")
                    remoteOnly.forEach { r ->
                        MenuItem("${r.remote}/${r.name}", onClick = { sheet = null; actions.onRequest(GitOp.Switch(r.name)) }, mono = true, supporting = "在本地创建 ${r.name} 并跟踪")
                    }
                }
            }
        }
        is GitSheet.Commit -> ClaudercSheet(onDismiss = { sheet = null }, anchor = s.anchor, fromTop = false) {
            CommitSheetBody(
                state = state,
                onMessage = actions.onMessage,
                onClose = { sheet = null },
                onCommit = { msg -> actions.onRequest(GitOp.Commit(msg)) },
            )
        }
        null -> Unit
    }

    when (val confirm = state.confirm) {
        is GitConfirm.Busy -> ClaudercDialog(
            onDismiss = actions.onDismissConfirm,
            title = busyTitle(confirm.unsure),
            text = busyText(confirm.sessionName, confirm.unsure, baseName(confirm.dir), confirm.op.verb),
            buttons = {
                TextAction("取消", onClick = actions.onDismissConfirm)
                TextAction("仍然${confirm.op.verb}", onClick = actions.onConfirm)
            },
        )
        is GitConfirm.Upstream -> ClaudercDialog(
            onDismiss = actions.onDismissConfirm,
            title = "${confirm.branch} 还没有上游",
            body = {
                Text(
                    buildAnnotatedString {
                        append("推送到 ")
                        withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, color = c.fg)) { append("origin/${confirm.branch}") }
                        append(" 并设为上游。")
                    },
                    fontSize = Clauderc.fontSizes.label,
                    color = c.fg2,
                    lineHeight = 1.65.em,
                )
            },
            buttons = {
                TextAction("取消", onClick = actions.onDismissConfirm)
                TextAction("推送", onClick = actions.onConfirm)
            },
        )
        null -> Unit
    }

    state.failure?.let { f ->
        ClaudercSheet(onDismiss = actions.onDismissFailure) {
            SheetHead(f.title, f.text)
            Text(
                buildAnnotatedString {
                    f.command?.let { append("$ $it\n") }
                    withStyle(SpanStyle(color = c.danger)) { append(f.output.trimEnd()) }
                },
                modifier = Modifier
                    .padding(start = Clauderc.spacing.page, end = Clauderc.spacing.page, top = Clauderc.spacing.sp2)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Clauderc.radii.sm))
                    .background(c.surface)
                    .padding(horizontal = Clauderc.spacing.sp4, vertical = Clauderc.spacing.sp3)
                    .testTag("gitOutput"),
                fontFamily = MonoFamily,
                fontSize = 11.5.sp,
                lineHeight = 1.65.em,
                color = c.fg2,
            )
            Text("时间线和改动已刷新", Modifier.padding(start = Clauderc.spacing.page, top = Clauderc.spacing.sp3), fontSize = Clauderc.fontSizes.caption, color = c.fg3)
            Row(Modifier.fillMaxWidth().padding(start = Clauderc.spacing.sp3, end = Clauderc.spacing.sp3, top = Clauderc.spacing.sp3), horizontalArrangement = Arrangement.End) {
                TextAction("好", onClick = actions.onDismissFailure)
            }
        }
    }
}

/**
 * 印版's editorial Git header (demo.html `.ghead` + `.ptitle` + `.where`):
 * left 34dp square framed refresh button + right solid-ink pending `.stamp` seal,
 * followed by a 30sp heavy dir heading and underlined clickable `machine · path v` trigger.
 */
@Composable
private fun PlateGitHeader(
    dir: String?,
    machineName: String?,
    pendingCount: Int,
    onRefresh: () -> Unit,
    onPickRepo: () -> Unit,
    onOpenPending: () -> Unit,
) {
    val c = Clauderc.colors
    val refPress = remember { MutableInteractionSource() }
    val refHit by pressProgress(refPress, PressKind.Icon)
    val spin = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp)
            .testTag("plateGitHeader"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .pressShrink(refPress, PressKind.Icon)
                    .size(34.dp)
                    .plateBox(c.bg, if (dir != null) c.fg else c.fg3, 1.5.dp)
                    .clickable(refPress, controlPress(PressKind.Icon, fill = c.fg), enabled = dir != null, role = Role.Button) {
                        scope.launch {
                            spin.snapTo(0f)
                            spin.animateTo(360f, tween(520, easing = Motion.plateEasePress))
                            spin.snapTo(0f)
                        }
                        onRefresh()
                    }
                    .semantics { contentDescription = "刷新" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Symbols.Refresh,
                    contentDescription = null,
                    tint = if (dir != null) lerp(c.fg, c.bg, refHit) else c.fg3,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = spin.value },
                )
            }
            Spacer(Modifier.weight(1f))
            PendingJumpBadge(pendingCount, onOpenPending)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                dir?.let(::baseName) ?: "git",
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                color = c.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val wherePress = remember { MutableInteractionSource() }
            val whereHit by pressProgress(wherePress, PressKind.Title)
            val whereColor = lerp(c.fg2, c.bg, whereHit)
            val whereLabel = when {
                dir == null -> "选择目录"
                !machineName.isNullOrEmpty() -> "$machineName · ${shortPath(dir)}"
                else -> shortPath(dir)
            }
            Row(
                Modifier
                    .pressShrink(wherePress, PressKind.Title)
                    .drawBehind {
                        val h = 1.dp.toPx()
                        drawRect(c.line, Offset(0f, size.height - h), Size(size.width, h))
                    }
                    .clickable(wherePress, controlPress(PressKind.Title, fill = c.fg), role = Role.Button, onClick = onPickRepo)
                    .padding(bottom = 3.dp)
                    .testTag("plateGitWhere"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    whereLabel,
                    fontFamily = MonoFamily,
                    fontSize = 12.sp,
                    color = whereColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Symbols.ExpandMore, contentDescription = null, tint = whereColor, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * 清玻's Git header (demo.html `.toprow` + `.hero` + `.titlebtn`):
 * right-aligned branch + refresh buttons, 34sp dir title with chevron rotating 180° when the
 * top-falling glass panel is open, and mono path subtitle.
 */
@Composable
private fun ClearGitHeader(
    dir: String?,
    machineName: String?,
    repoOpen: Boolean,
    onPickRepo: () -> Unit,
    onBranches: () -> Unit,
    onRefresh: () -> Unit,
) {
    val c = Clauderc.colors
    val chevTurn by animateFloatAsState(if (repoOpen) 180f else 0f, Motion.enter(), label = "clearGitChev")
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(bottom = 4.dp)
            .testTag("clearGitHeader"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dir != null) SymbolButton(Symbols.AltRoute, "切换分支", onClick = onBranches)
            SymbolButton(Symbols.Refresh, "刷新", onClick = onRefresh, enabled = dir != null)
        }
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 2.dp)) {
            val wherePress = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .pressShrink(wherePress, PressKind.Title)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(wherePress, controlPress(PressKind.Title), role = Role.Button, onClick = onPickRepo)
                    .testTag("clearGitWhere"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    dir?.let(::baseName) ?: "git",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    Symbols.ExpandMore,
                    contentDescription = null,
                    tint = c.fg3,
                    modifier = Modifier.size(26.dp).rotate(chevTurn),
                )
            }
            val sub = when {
                dir == null -> "选择目录"
                !machineName.isNullOrEmpty() -> "$machineName · ${shortPath(dir)}"
                else -> shortPath(dir)
            }
            Text(
                sub,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .pressClickable(PressKind.Title, onClick = onPickRepo),
                fontFamily = if (dir != null) MonoFamily else null,
                fontSize = 13.sp,
                color = c.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 素卡's Git header (demo.html `.hero` + `.pchip` + `.pillgroup`):
 * top-right scalloped cookie pending badge + grouped white capsule (branch + refresh),
 * followed by 30sp Bold dir title and clickable mono subtitle opening [TileBottomSheet].
 */
@Composable
private fun TileGitHeader(
    dir: String?,
    machineName: String?,
    pendingCount: Int,
    onPickRepo: () -> Unit,
    onBranches: () -> Unit,
    onRefresh: () -> Unit,
    onOpenPending: () -> Unit,
) {
    val c = Clauderc.colors
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)
            .testTag("tileGitHeader"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PendingJumpBadge(pendingCount, onOpenPending)
            Row(
                Modifier.tileSurface(c.card, 999.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dir != null) SymbolButton(Symbols.AltRoute, "切换分支", onClick = onBranches, small = true)
                SymbolButton(Symbols.Refresh, "刷新", onClick = onRefresh, enabled = dir != null, small = true)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                dir?.let(::baseName) ?: "git",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = c.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                dir == null -> "选择目录"
                !machineName.isNullOrEmpty() -> "$machineName · ${shortPath(dir)}"
                else -> shortPath(dir)
            }
            val wherePress = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .pressShrink(wherePress, PressKind.Title)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(wherePress, controlPress(PressKind.Title), role = Role.Button, onClick = onPickRepo)
                    .testTag("tileGitWhere"),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    sub,
                    fontFamily = if (dir != null) MonoFamily else null,
                    fontSize = 13.sp,
                    color = c.fg2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Symbols.ExpandMore, contentDescription = null, tint = c.fg3, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Empty(title: String, path: String?, button: String, onClick: () -> Unit) {
    val c = Clauderc.colors
    Column(
        Modifier.fillMaxSize().padding(Clauderc.spacing.sp8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp3, Alignment.CenterVertically),
    ) {
        Icon(Symbols.Folder, contentDescription = null, tint = c.fg3, modifier = Modifier.size(40.dp))
        Text(title, fontSize = Clauderc.fontSizes.title)
        if (path != null) Text(path, fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, color = c.fg3, textAlign = TextAlign.Center)
        PillButton(button, onClick = onClick, style = PillStyle.Tonal, modifier = Modifier.padding(top = Clauderc.spacing.sp3))
    }
}

@Composable
private fun Tabs(state: GitUiState, onTab: (GitTab) -> Unit, clearHaze: HazeState? = null) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val count = state.status?.let { it.staged.size + it.unstaged.size }
    if (ui == UiStyle.Ink) {
        InkBrushTabs(
            labels = listOf("时间线" to null, "改动" to count?.takeIf { it > 0 }?.toString()),
            selected = if (state.tab == GitTab.Log) 0 else 1,
            onSelect = { onTab(if (it == 0) GitTab.Log else GitTab.Status) },
        )
        return
    }
    if (ui == UiStyle.Clear) {
        // 清玻 .seglens.glass: frosted glass capsule with 70ms delayed leading/trailing stretching lens (demo.html).
        val spans = remember { mutableStateMapOf<GitTab, Pair<Int, Int>>() }
        val capsuleShape = RoundedCornerShape(22.dp)
        val lensShape = RoundedCornerShape(18.dp)
        val movingRight = state.tab == GitTab.Status
        val presses = remember { List(2) { MutableInteractionSource() } }
        val held = presses.map { it.collectIsPressedAsState().value }.any { it }
        val lensPress = animateFloatAsState(if (held) 1f else 0f, Motion.press(ui, PressKind.SegmentLens, held, transform = true), label = "segLensPress")
        Box(
            Modifier
                .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 6.dp)
                .shadow(10.dp, capsuleShape, ambientColor = c.glassShadow, spotColor = c.glassShadow)
                .clip(capsuleShape)
                .glassPane(clearHaze)
                .border(0.5.dp, c.edge, capsuleShape)
                .padding(4.dp)
                .testTag("clearSegLens"),
        ) {
            spans[state.tab]?.let { (targetStart, targetWidth) ->
                val targetEnd = targetStart + targetWidth
                val lensStart by animateIntAsState(
                    targetValue = targetStart,
                    animationSpec = if (movingRight) Motion.navTrail() else Motion.navLead(),
                    label = "segLensL",
                )
                val lensEnd by animateIntAsState(
                    targetValue = targetEnd,
                    animationSpec = if (movingRight) Motion.navLead() else Motion.navTrail(),
                    label = "segLensR",
                )
                Box(
                    Modifier
                        .offset { IntOffset(lensStart, 0) }
                        .width(with(LocalDensity.current) { (lensEnd - lensStart).coerceAtLeast(0).toDp() })
                        .height(36.dp)
                        .pressPose(ui, PressKind.SegmentLens, lensPress)
                        .clip(lensShape)
                        .background(c.lens)
                        .topHighlight(c.rim)
                        .border(0.5.dp, c.edge, lensShape)
                        .testTag("tabUnderline"),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(GitTab.Log to "时间线", GitTab.Status to "改动").forEachIndexed { index, (tab, label) ->
                    val on = state.tab == tab
                    val press = presses[index]
                    Row(
                        Modifier
                            .height(36.dp)
                            .onPlaced { spans[tab] = it.positionInParent().x.roundToInt() to it.size.width }
                            .pressShrink(press, PressKind.Segment)
                            .clip(lensShape)
                            .clickable(press, controlPress(PressKind.Segment, corner = 18.dp), role = Role.Tab) { onTab(tab) }
                            .semantics { selected = on }
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildAnnotatedString {
                                append(label)
                                if (tab == GitTab.Status && count != null && count > 0) {
                                    withStyle(SpanStyle(color = c.fg3, fontWeight = FontWeight.Normal)) { append("($count)") }
                                }
                            },
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                            color = if (on) c.fg else c.fg2,
                        )
                    }
                }
            }
        }
        return
    }
    if (ui == UiStyle.Plate) {
        // 印版 .tabs2: left-aligned bold text tabs with 2.5dp animated scaleX ink underline (demo.html).
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp)
                    .testTag("plateTabs2"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(GitTab.Log to "时间线", GitTab.Status to "改动").forEach { (tab, label) ->
                    val on = state.tab == tab
                    val ruleScale by animateFloatAsState(
                        targetValue = if (on) 1f else 0f,
                        animationSpec = tween(340, easing = Motion.plateEasePress),
                        label = "plateTabUnderline:${tab.name}",
                    )
                    val press = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .pressShrink(press, PressKind.Segment)
                            .clickable(press, controlPress(PressKind.Segment), role = Role.Tab) { onTab(tab) }
                            .semantics { selected = on }
                            .drawBehind {
                                if (ruleScale > 0.005f) {
                                    val h = 2.5.dp.toPx()
                                    val pad = 4.dp.toPx()
                                    val fullW = (size.width - pad * 2f).coerceAtLeast(0f)
                                    val w = fullW * ruleScale
                                    val x = pad + (fullW - w) / 2f
                                    drawRect(c.fg, Offset(x, size.height - h), Size(w, h))
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(
                                label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (on) c.fg else c.fg2,
                            )
                            if (tab == GitTab.Status && count != null && count > 0) {
                                Text(
                                    "%02d".format(count),
                                    fontFamily = MonoFamily,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (on) c.fg else c.fg2,
                                )
                            }
                        }
                    }
                }
            }
            Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(2.dp).background(c.fg))
        }
        return
    }
    val filled = ui != UiStyle.Clear
    val spans = remember { mutableStateMapOf<GitTab, Pair<Int, Int>>() }
    val track = when (ui) {
        UiStyle.Tile -> Modifier.padding(start = sp.sp4, end = sp.sp4, top = sp.sp1).tileSurface(c.card, 999.dp).padding(4.dp)
        UiStyle.Ink -> Modifier.padding(start = sp.sp4, end = sp.sp4, top = sp.sp1).inkSurface(c.float, c.edge, 999.dp).padding(4.dp)
        UiStyle.Plate -> Modifier.padding(horizontal = 18.dp)
        UiStyle.Clear -> Modifier.padding(horizontal = sp.page)
    }
    Column {
        Box(track) {
            val indicator: @Composable (Modifier) -> Unit = { place ->
                spans[state.tab]?.let { (left, width) ->
                    val x by animateIntAsState(left, Motion.enter(), label = "underlineX")
                    val w by animateIntAsState(width, Motion.enter(), label = "underlineWidth")
                    val at = place.offset { IntOffset(x, 0) }.width(with(LocalDensity.current) { w.toDp() })
                    when (ui) {
                        UiStyle.Tile -> Box(at.fillMaxHeight().clip(CircleShape).background(c.accent).testTag("tabUnderline"))
                        UiStyle.Ink -> Box(at.fillMaxHeight().clip(CircleShape).background(c.inverse).testTag("tabUnderline"))
                        UiStyle.Plate -> Box(at.fillMaxHeight().padding(vertical = 4.dp).background(c.fg, RectangleShape).testTag("tabUnderline"))
                        UiStyle.Clear -> Box(at.height(2.dp).background(c.fg).testTag("tabUnderline"))
                    }
                }
            }
            if (filled) Box(Modifier.matchParentSize()) { indicator(Modifier) }
            Row(if (filled) Modifier.fillMaxWidth() else Modifier, horizontalArrangement = Arrangement.spacedBy(if (ui == UiStyle.Tile || ui == UiStyle.Ink) 4.dp else if (filled) 0.dp else sp.sp8)) {
                listOf(GitTab.Log to "时间线", GitTab.Status to "改动").forEach { (tab, label) ->
                    val on = state.tab == tab
                    val tabPress = remember { MutableInteractionSource() }
                    Column(
                        Modifier
                            .then(if (filled) Modifier.weight(1f) else Modifier.width(IntrinsicSize.Max))
                            .onPlaced { spans[tab] = it.positionInParent().x.roundToInt() to it.size.width }
                            .pressShrink(tabPress, PressKind.Segment)
                            .then(if (filled) Modifier.clip(if (ui == UiStyle.Plate) RectangleShape else CircleShape) else Modifier)
                            .clickable(
                                tabPress,
                                controlPress(
                                    PressKind.Segment,
                                    if (on && ui == UiStyle.Ink) PressTone.Primary else PressTone.Normal,
                                    corner = if (filled && ui != UiStyle.Plate) 100.dp else if (ui == UiStyle.Plate) 0.dp else 12.dp,
                                ),
                                role = Role.Tab,
                            ) { onTab(tab) }
                            .semantics { selected = on },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            buildAnnotatedString {
                                append(label)
                                if (tab == GitTab.Status && count != null && count > 0) append("($count)")
                            },
                            modifier = Modifier.padding(top = if (ui == UiStyle.Tile || ui == UiStyle.Ink) 9.dp else 12.dp, bottom = if (ui == UiStyle.Tile || ui == UiStyle.Ink) 9.dp else if (filled) 14.dp else 12.dp),
                            fontSize = if (ui == UiStyle.Plate) 13.sp else Clauderc.fontSizes.label,
                            fontFamily = if (ui == UiStyle.Plate) MonoFamily else null,
                            fontWeight = if (ui == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.Medium,
                            color = when {
                                on && ui == UiStyle.Tile -> c.onAccent
                                on && ui == UiStyle.Ink -> c.onInverse
                                on && ui == UiStyle.Plate -> c.bg
                                on -> c.fg
                                else -> c.fg2
                            },
                        )
                        if (!filled) Spacer(Modifier.height(2.dp))
                    }
                }
            }
            if (!filled) indicator(Modifier.align(Alignment.BottomStart))
        }
        when (ui) {
            UiStyle.Clear -> Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            UiStyle.Plate -> Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(2.dp).background(c.fg))
            UiStyle.Tile, UiStyle.Ink -> Unit
        }
    }
}

@Composable
private fun BranchBar(state: GitUiState, onBranches: (Rect?) -> Unit) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val st = state.status
    val branchAnchor = rememberAnchorBox()
    Row(
        Modifier.fillMaxWidth().padding(
            start = if (ui == UiStyle.Ink) 24.dp else Clauderc.spacing.page,
            end = Clauderc.spacing.page,
            top = if (ui == UiStyle.Ink) 0.dp else Clauderc.spacing.sp4,
            bottom = Clauderc.spacing.sp3,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (ui == UiStyle.Ink) 10.dp else Clauderc.spacing.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val press = remember { MutableInteractionSource() }
        if (ui == UiStyle.Ink) {
            Row(
                Modifier
                    .captureAnchor(branchAnchor)
                    .pressShrink(press, PressKind.Branch)
                    .inkSurface(c.float, c.edge, 999.dp)
                    .clickable(press, controlPress(PressKind.Branch), role = Role.Button, onClickLabel = "切换分支") { onBranches(branchAnchor.rect) }
                    .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Symbols.AltRoute, contentDescription = null, tint = c.fg2, modifier = Modifier.size(15.dp))
                Text(st?.branch?.ifEmpty { "分离的 HEAD" } ?: "…", fontFamily = MonoFamily, fontSize = 13.sp, color = c.fg)
                Icon(Symbols.ExpandMore, contentDescription = null, tint = c.fg2, modifier = Modifier.size(16.dp))
            }
        } else if (ui == UiStyle.Plate) {
            val sweep = if (ui == UiStyle.Plate) pressProgress(press, sweep = true).value else 0f
            val tint = lerp(c.fg, c.bg, sweep)
            Row(
                Modifier
                    .captureAnchor(branchAnchor)
                    .pressShrink(press, PressKind.Branch)
                    .background(c.bg, RectangleShape)
                    .plateSweepFill(sweep, c.fg, PlateSweep.LeftToRight)
                    .border(1.5.dp, c.fg, RectangleShape)
                    .clickable(press, indication = null, role = Role.Button, onClickLabel = "切换分支") { onBranches(branchAnchor.rect) }
                    .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                    .testTag("plateBranchPill"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Symbols.AltRoute, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
                Text(st?.branch?.ifEmpty { "分离的 HEAD" } ?: "…", fontFamily = MonoFamily, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = tint)
                Icon(Symbols.ExpandMore, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            }
        } else if (ui == UiStyle.Tile) {
            Row(
                Modifier
                    .captureAnchor(branchAnchor)
                    .pressShrink(press, PressKind.Branch)
                    .tileSurface(c.card, 999.dp)
                    .clickable(press, controlPress(PressKind.Branch), role = Role.Button, onClickLabel = "切换分支") { onBranches(branchAnchor.rect) }
                    .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Symbols.AltRoute, contentDescription = null, tint = c.fg2, modifier = Modifier.size(15.dp))
                Text(st?.branch?.ifEmpty { "分离的 HEAD" } ?: "…", fontFamily = MonoFamily, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.fg)
                Icon(Symbols.ExpandMore, contentDescription = null, tint = c.fg2, modifier = Modifier.size(16.dp))
            }
        } else {
            Row(
                Modifier
                    .captureAnchor(branchAnchor)
                    .pressShrink(press, PressKind.Branch)
                    .clickable(press, controlPress(PressKind.Branch), role = Role.Button, onClickLabel = "切换分支") { onBranches(branchAnchor.rect) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(st?.branch?.ifEmpty { "分离的 HEAD" } ?: "…", fontFamily = MonoFamily, fontSize = 15.sp)
                Icon(Symbols.ExpandMore, contentDescription = null, tint = c.fg3, modifier = Modifier.size(18.dp))
            }
        }
        if (st != null) {
            Text(
                if (st.upstream.isNullOrEmpty()) "没有上游" else "↑${st.ahead} ↓${st.behind} ${st.upstream}",
                fontFamily = MonoFamily,
                fontSize = Clauderc.fontSizes.monoSm,
                color = c.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Timeline(state: GitUiState, actions: GitActions, list: LazyListState = rememberLazyListState()) {
    val c = Clauderc.colors
    val geometry = remember(state.commits, state.status?.branch) { timelineGeometry(state.commits, state.status?.branch) }
    val textStart = remember(state.commits) { graphWidth(state.commits) }
    val autoLoad by rememberUpdatedState(state.hasMore && !state.loadingMore && !state.moreFailed)
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (autoLoad && last != null && total > 0 && last >= total - 2) actions.onLoadMore() }
    }
    val navBottom = if (Clauderc.style == UiStyle.Clear) 144.dp else NavClearance
    LazyColumn(Modifier.fillMaxSize().testTag("timeline"), state = list, contentPadding = PaddingValues(bottom = navBottom)) {
        itemsIndexed(state.commits, key = { _, it -> it.hash }) { i, commit ->
            // One card for the whole graph in the card style; dividers would cut its lanes.
            Box(Modifier.groupItem(first = i == 0, last = i == state.commits.lastIndex, divider = false)) {
                geometry.getOrNull(i)?.let {
                    CommitRow(commit, it, textStart, state.status?.branch) { rect ->
                        actions.onOpenCommitAt?.invoke(commit.hash, rect) ?: actions.onOpenCommit(commit.hash)
                    }
                }
            }
        }
        if (state.hasMore || state.loadingMore) {
            item(key = "more") {
                if (state.moreFailed && !state.loadingMore) {
                    val morePress = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .smallPressButton(morePress, onClick = actions.onLoadMore)
                            .padding(top = Clauderc.spacing.sp4, bottom = Clauderc.spacing.sp6),
                        horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp2, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("加载失败，点此重试", fontSize = Clauderc.fontSizes.caption, color = smallButtonTint(morePress, c.fg3))
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(top = Clauderc.spacing.sp4, bottom = Clauderc.spacing.sp6),
                        horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp2, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spinner(size = 14.dp, color = c.fg3)
                        Text("继续加载更早的提交", fontSize = Clauderc.fontSizes.caption, color = c.fg3)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitRow(commit: Commit, g: RowGeometry, textStart: Float, head: String?, onClick: (Rect?) -> Unit) {
    val c = Clauderc.colors
    val density = LocalDensity.current
    val anchor = rememberAnchorBox()
    Box(Modifier.fillMaxWidth().height(64.dp).captureAnchor(anchor).listRowPress(PressRow.Timeline) { onClick(anchor.rect) }) {
        Canvas(Modifier.width(textStart.dp).fillMaxHeight()) {
            val k = density.density
            fun o(p: Pt) = Offset(p.x * k, p.y * k)
            g.segments.forEach { s ->
                val path = Path().apply {
                    moveTo(s.p0.x * k, s.p0.y * k)
                    cubicTo(s.p1.x * k, s.p1.y * k, s.p2.x * k, s.p2.y * k, s.p3.x * k, s.p3.y * k)
                }
                drawPath(path, c.lane(s.lane), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
            }
            val color = c.lane(g.lane)
            if (g.hollow) {
                drawCircle(c.bg, radius = 4.5.dp.toPx(), center = o(g.node))
                drawCircle(color, radius = 4.5.dp.toPx(), center = o(g.node), style = Stroke(1.5.dp.toPx()))
            } else {
                drawCircle(color, radius = 3.5.dp.toPx(), center = o(g.node))
            }
        }
        Column(
            Modifier.fillMaxSize().padding(start = textStart.dp, end = Clauderc.spacing.page),
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp2), verticalAlignment = Alignment.Bottom) {
                val refs = commit.refs.orEmpty()
                if (refs.isNotEmpty()) {
                    Text(
                        buildAnnotatedString {
                            refs.sortedByDescending { it.kind == RefKind.Branch && it.name == head }.forEachIndexed { i, r ->
                                if (i > 0) append(", ")
                                when {
                                    r.kind == RefKind.Branch && r.name == head -> {
                                        append("HEAD → ")
                                        withStyle(SpanStyle(color = c.fg, fontWeight = FontWeight.Medium)) { append(r.name) }
                                    }
                                    r.kind == RefKind.Tag -> append("tag: ${r.name}")
                                    else -> append(r.name)
                                }
                            }
                        },
                        // Keep long ref lists from pushing the subject off the row.
                        modifier = Modifier.widthIn(max = 150.dp),
                        fontFamily = MonoFamily,
                        fontSize = 11.sp,
                        color = c.fg3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(commit.subject, Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${commit.hash.take(7)} · ${commit.author} · ${TimeText.relative(commit.time)}",
                fontFamily = MonoFamily,
                fontSize = 11.sp,
                color = c.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusPage(
    state: GitUiState,
    actions: GitActions,
    changesList: LazyListState = rememberLazyListState(),
    pendingCount: Int = 0,
    clearHaze: HazeState? = null,
    onNeedStage: () -> Unit = {},
    onOpenCommit: (Rect?) -> Unit = {},
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val st = state.status
    val staged = st?.staged.orEmpty()
    val unstaged = st?.unstaged.orEmpty()
    val hasFiles = st != null && (staged.isNotEmpty() || unstaged.isNotEmpty())
    val opsPad = when (ui) {
        UiStyle.Ink, UiStyle.Tile -> 16.dp
        UiStyle.Plate -> 18.dp
        UiStyle.Clear -> sp.page
    }
    val listBottomPad = when {
        hasFiles && ui == UiStyle.Clear && pendingCount > 0 -> 224.dp
        hasFiles -> 172.dp
        ui == UiStyle.Clear -> 144.dp
        else -> NavClearance
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            state.busySession?.let { name ->
                Row(Modifier.padding(start = sp.page, end = sp.page, bottom = sp.sp3), horizontalArrangement = Arrangement.spacedBy(sp.sp3), verticalAlignment = Alignment.CenterVertically) {
                    StatusGlyph(Glyph.Work)
                    Text("「$name」正在这个目录工作", fontSize = Clauderc.fontSizes.caption, color = c.fg2)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = opsPad, end = opsPad, bottom = 10.dp)
                    .testTag("gitOpsBar"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val behind = st?.behind ?: 0
                val ahead = st?.ahead ?: 0
                GitOpButton(
                    label = if (behind > 0) "拉取 $behind" else "拉取",
                    icon = Symbols.Download,
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                    onClick = { actions.onRequest(GitOp.Pull) },
                )
                GitOpButton(
                    label = if (ahead > 0) "推送 $ahead" else "推送",
                    icon = Symbols.Upload,
                    enabled = !state.running,
                    modifier = Modifier.weight(1f),
                    onClick = { actions.onRequest(GitOp.Push()) },
                )
            }
            LazyColumn(
                Modifier.weight(1f).testTag("changes"),
                state = changesList,
                contentPadding = PaddingValues(bottom = listBottomPad),
            ) {
                if (st != null && !hasFiles) {
                    item(key = "clean") { CleanWorkspaceEmpty() }
                }
                if (staged.isNotEmpty()) {
                    item(key = "staged-head") {
                        FileSectionHeader(
                            title = "已暂存 · ${staged.size}",
                            actionLabel = "全部取消",
                            enabled = !state.running,
                            onAction = { actions.onRequest(GitOp.UnstageAll(staged.map { it.path })) },
                        )
                    }
                    staged.forEachIndexed { i, f ->
                        item(key = "s:" + f.path) {
                            FileRow(
                                f = f,
                                staged = true,
                                onOpen = { rect -> actions.onOpenDiffAt?.invoke(f.path, true, rect) ?: actions.onOpenDiff(f.path, true) },
                                modifier = Modifier.groupItem(i == 0, i == staged.lastIndex),
                                enabled = !state.running,
                                onAction = { actions.onRequest(GitOp.Unstage(f.path)) },
                            )
                        }
                    }
                }
                if (unstaged.isNotEmpty()) {
                    item(key = "unstaged-head") {
                        FileSectionHeader(
                            title = "未暂存 · ${unstaged.size}",
                            actionLabel = "全部暂存",
                            enabled = !state.running,
                            onAction = { actions.onRequest(GitOp.StageAll(unstaged.map { it.path })) },
                        )
                    }
                    unstaged.forEachIndexed { i, f ->
                        item(key = "u:" + f.path) {
                            FileRow(
                                f = f,
                                staged = false,
                                onOpen = { rect -> actions.onOpenDiffAt?.invoke(f.path, false, rect) ?: actions.onOpenDiff(f.path, false) },
                                modifier = Modifier.groupItem(i == 0, i == unstaged.lastIndex),
                                enabled = !state.running,
                                onAction = { actions.onRequest(GitOp.Stage(f.path)) },
                            )
                        }
                    }
                }
            }
        }
        val barBottom by animateDpAsState(
            targetValue = when {
                ui == UiStyle.Clear && pendingCount > 0 -> 148.dp
                ui == UiStyle.Plate -> 86.dp
                else -> 92.dp
            },
            animationSpec = Motion.enter(),
            label = "gitCommitBarBottom",
        )
        AnimatedVisibility(
            visible = hasFiles,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = barBottom),
            enter = fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it / 3 },
            exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { it / 3 },
        ) {
            CommitCapsuleBar(
                draft = state.message,
                ready = staged.isNotEmpty() && !state.running,
                hasStaged = staged.isNotEmpty(),
                clearHaze = clearHaze,
                onNeedStage = onNeedStage,
                onOpenCommit = onOpenCommit,
            )
        }
    }
}

/**
 * Compact floating commit bar above the bottom nav (demo.html `.git-cbar`):
 * single-line draft preview or prompt + circular checkmark button (square stamp in 印版).
 * Tapping with staged files opens the style-matched commit panel from this bar's anchor.
 */
@Composable
private fun CommitCapsuleBar(
    draft: String,
    ready: Boolean,
    hasStaged: Boolean,
    clearHaze: HazeState?,
    onNeedStage: () -> Unit,
    onOpenCommit: (Rect?) -> Unit,
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val press = remember { MutableInteractionSource() }
    val hit by pressProgress(press, PressKind.Capsule)
    val barAnchor = rememberAnchorBox()
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val trimmed = draft.trim()
    val hasDraft = trimmed.isNotEmpty()

    val btnFill by animateColorAsState(
        targetValue = when {
            !ready -> when (ui) {
                UiStyle.Ink -> c.surface2
                UiStyle.Plate -> c.bg
                UiStyle.Tile, UiStyle.Clear -> c.surface
            }
            ui == UiStyle.Ink -> c.inverse
            ui == UiStyle.Plate -> c.fg
            else -> c.accent
        },
        animationSpec = Motion.enter(),
        label = "commitBarBtnBg",
    )
    val btnFg by animateColorAsState(
        targetValue = when {
            !ready -> c.fg3
            ui == UiStyle.Ink -> c.onInverse
            ui == UiStyle.Plate -> c.bg
            else -> c.onAccent
        },
        animationSpec = Motion.enter(),
        label = "commitBarBtnFg",
    )
    val shape = if (ui == UiStyle.Plate) RectangleShape else RoundedCornerShape(28.dp)
    val sidePad = if (ui == UiStyle.Plate) 18.dp else 16.dp
    val surface = when (ui) {
        UiStyle.Ink -> Modifier
            .shadow(if (c.isDark) 0.dp else 10.dp, shape, ambientColor = c.scrim, spotColor = c.scrim)
            .inkSurface(c.float, c.edge, 28.dp)
        UiStyle.Tile -> Modifier
            .shadow(if (c.isDark) 8.dp else 10.dp, shape, ambientColor = c.scrim, spotColor = c.scrim)
            .tileSurface(c.card, 28.dp)
            .border(1.dp, c.line, shape)
        UiStyle.Clear -> Modifier
            .shadow(10.dp, shape, ambientColor = c.glassShadow, spotColor = c.glassShadow)
            .clip(shape)
            .glassPane(clearHaze)
            .border(0.5.dp, c.edge, shape)
        UiStyle.Plate -> Modifier
            .plateHardShadow(c.fg, 3.dp, hit, flat = 1.5.dp)
            .plateBox(c.bg, c.fg, 1.5.dp)
    }

    val label = when {
        hasDraft -> trimmed
        hasStaged -> "写提交说明…"
        else -> "先暂存要提交的文件"
    }
    val labelColor = when {
        hasDraft -> c.fg
        hasStaged -> c.fg2
        else -> c.fg3
    }
    val iconColor = if (hasDraft || hasStaged) c.fg2 else c.fg3

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = sidePad)
            .offset(x = Motion.nudgeDp(shake.value).dp)
            .captureAnchor(barAnchor)
            .pressShrink(press, PressKind.Capsule)
            .heightIn(min = 52.dp)
            .then(surface)
            .clickable(press, if (ui == UiStyle.Plate) null else controlPress(PressKind.Capsule, corner = 26.dp), role = Role.Button, onClickLabel = "填写提交说明") {
                if (!hasStaged) {
                    scope.launch {
                        shake.snapTo(0f)
                        shake.animateTo(1f, Motion.nudge())
                        shake.snapTo(0f)
                    }
                    onNeedStage()
                } else {
                    onOpenCommit(barAnchor.rect)
                }
            }
            .padding(start = if (ui == UiStyle.Plate) 12.dp else 16.dp, end = if (ui == UiStyle.Plate) 8.dp else 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("gitCommitBar"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Symbols.Edit, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = if (ui == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.Medium,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .then(if (ui != UiStyle.Plate || ready) Modifier.pressShrink(press, if (ui == UiStyle.Plate) PressKind.Button else PressKind.Icon) else Modifier)
                .size(if (ui == UiStyle.Plate) 36.dp else 40.dp)
                .then(
                    when {
                        ui == UiStyle.Plate && ready -> Modifier.plateStamp(btnFill, c.bg, c.fg, source = press, enabled = true)
                        ui == UiStyle.Plate -> Modifier.background(btnFill, RectangleShape).border(1.5.dp, c.line, RectangleShape)
                        else -> Modifier.clip(CircleShape).background(btnFill)
                    },
                )
                .semantics { contentDescription = "提交" }
                .testTag("gitCommitBarCheck"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Symbols.Check,
                contentDescription = null,
                tint = btnFg,
                modifier = Modifier.size(if (ui == UiStyle.Plate) 19.dp else 20.dp),
            )
        }
    }
}

/** Modal commit form opened from [CommitCapsuleBar] through [ClaudercSheet] (demo.html `A.openCommitDialog`). */
@Composable
private fun CommitSheetBody(
    state: GitUiState,
    onMessage: (String) -> Unit,
    onClose: () -> Unit,
    onCommit: (String) -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val close = LocalMorphClose.current
    val stagedCount = state.status?.staged?.size ?: 0
    val branch = state.status?.branch?.ifEmpty { "分离的 HEAD" } ?: "当前分支"
    val side = when (ui) {
        UiStyle.Plate -> 16.dp
        UiStyle.Clear -> sp.page
        UiStyle.Ink, UiStyle.Tile -> 20.dp
    }
    SheetHead("提交改动")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = side, end = side, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            buildAnnotatedString {
                append("将提交已暂存的 $stagedCount 个文件到分支 ")
                withStyle(SpanStyle(fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, color = c.fg)) { append(branch) }
            },
            fontSize = 13.sp,
            color = c.fg2,
            lineHeight = 1.6.em,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("提交说明")
            UnderlineField(
                value = state.message,
                onValueChange = onMessage,
                modifier = Modifier.testTag("commitMessageInput"),
                placeholder = "输入提交说明…",
                enabled = !state.running,
                singleLine = false,
                minLines = 2,
                maxLines = 5,
                fontSize = 14.5.sp,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PillButton(
                "取消",
                onClick = { close?.invoke(null) ?: onClose() },
                style = PillStyle.Tonal,
                enabled = !state.running,
                height = 44.dp,
                modifier = Modifier.weight(1f),
            )
            PillButton(
                if (state.running) "处理中" else "提交",
                onClick = {
                    val msg = state.message
                    val go = { onCommit(msg) }
                    if (close != null) close(go) else { onClose(); go() }
                },
                style = PillStyle.Accent,
                enabled = state.canCommit,
                height = 44.dp,
                modifier = Modifier.weight(1f).testTag("commitSubmitButton"),
            )
        }
    }
}

/** .git-op: Pull / Push action buttons styled per look (demo.html). */
@Composable
private fun GitOpButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val press = remember { MutableInteractionSource() }
    val sweep = if (ui == UiStyle.Plate) pressProgress(press, sweep = true).value else 0f
    val contentFg = when {
        !enabled -> c.fg3
        ui == UiStyle.Plate -> lerp(c.fg, c.bg, sweep)
        else -> c.fg
    }
    val surface = when (ui) {
        UiStyle.Ink -> Modifier
            .shadow(if (c.isDark) 0.dp else 4.dp, CircleShape, ambientColor = c.scrim, spotColor = c.scrim)
            .inkSurface(c.float, c.edge, 999.dp)
        UiStyle.Tile -> Modifier.tileSurface(c.card, 999.dp)
        UiStyle.Clear -> Modifier.clip(CircleShape).background(c.surface)
        UiStyle.Plate -> Modifier
            .background(c.bg, RectangleShape)
            .plateSweepFill(sweep, c.fg, PlateSweep.LeftToRight)
            .border(1.5.dp, if (enabled) c.fg else c.fg3, RectangleShape)
    }
    Row(
        modifier
            .pressShrink(press, PressKind.Button)
            .height(38.dp)
            .then(surface)
            .clickable(press, if (ui == UiStyle.Plate) null else controlPress(PressKind.Button, corner = 100.dp), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = contentFg, modifier = Modifier.size(17.dp))
        Text(
            label,
            color = contentFg,
            fontSize = 13.sp,
            fontWeight = if (ui == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** .fsec: file group heading with count on the left and bulk stage/unstage button on the right (demo.html). */
@Composable
private fun FileSectionHeader(
    title: String,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val side = when (ui) {
        UiStyle.Plate -> 18.dp
        UiStyle.Tile -> 20.dp
        UiStyle.Ink, UiStyle.Clear -> 24.dp
    }
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = side, end = side, top = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 12.5.sp,
            fontWeight = if (ui == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = if (ui == UiStyle.Plate) c.fg2 else c.fg3,
        )
        val actionColor = when {
            !enabled -> c.fg3
            ui == UiStyle.Plate -> lerp(c.fg, c.bg, pressProgress(press, PressKind.SmallButton).value)
            else -> c.accent
        }
        Box(
            Modifier
                .pressShrink(press, PressKind.Plain)
                .then(
                    if (ui == UiStyle.Plate) {
                        Modifier.drawBehind {
                            val h = 1.5.dp.toPx()
                            drawRect(actionColor, Offset(0f, size.height - h), Size(size.width, h))
                        }
                    } else {
                        Modifier.clip(RoundedCornerShape(8.dp))
                    },
                )
                .clickable(press, controlPress(PressKind.SmallButton, fill = if (ui == UiStyle.Plate) c.fg else Color.Unspecified), enabled = enabled, role = Role.Button, onClick = onAction)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                actionLabel,
                fontSize = 12.5.sp,
                fontWeight = if (ui == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = actionColor,
            )
        }
    }
}

/** .empty-box for a clean working tree (demo.html). */
@Composable
private fun CleanWorkspaceEmpty() {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val boxMod = if (ui == UiStyle.Tile) {
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .tileSurface(c.card, CardCorner)
            .padding(horizontal = 24.dp, vertical = 40.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 48.dp)
    }
    Column(
        boxMod.testTag("gitCleanEmpty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Symbols.CheckCircle, contentDescription = null, tint = c.fg3, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            "工作区干净",
            fontSize = 17.sp,
            fontWeight = if (ui == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = c.fg,
        )
        Text(
            "当前分支没有未提交的改动",
            fontSize = 13.sp,
            color = c.fg2,
        )
    }
}

fun changeLetter(change: FileChange) = when (change) {
    FileChange.Added -> "A"
    FileChange.Modified -> "M"
    FileChange.Deleted -> "D"
    FileChange.Renamed -> "R"
    FileChange.Untracked -> "?"
}

@Composable
private fun FileRow(
    f: FileStatus,
    staged: Boolean,
    onOpen: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val rowAnchor = rememberAnchorBox()
    val startPad = when (ui) {
        UiStyle.Ink, UiStyle.Clear -> 24.dp
        UiStyle.Tile -> 16.dp
        UiStyle.Plate -> 4.dp
    }
    val endPad = when (ui) {
        UiStyle.Ink, UiStyle.Clear -> 20.dp
        UiStyle.Tile -> 12.dp
        UiStyle.Plate -> 4.dp
    }
    Row(
        modifier
            .fillMaxWidth()
            .captureAnchor(rowAnchor)
            .listRowPress { onOpen(rowAnchor.rect) }
            .padding(start = startPad, end = endPad, top = 11.dp, bottom = 11.dp)
            .semantics { isTraversalGroup = true },
        horizontalArrangement = Arrangement.spacedBy(if (ui == UiStyle.Ink || ui == UiStyle.Clear) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui == UiStyle.Plate) {
            val solid = f.change == FileChange.Added
            Box(
                Modifier
                    .size(20.dp)
                    .background(if (solid) c.fg else c.bg, RectangleShape)
                    .border(1.5.dp, c.fg, RectangleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    changeLetter(f.change),
                    fontFamily = MonoFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (solid) c.bg else c.fg,
                )
            }
        } else {
            val letterColor = when (f.change) {
                FileChange.Added -> c.addFg
                FileChange.Modified -> if (ui == UiStyle.Ink) c.fg else c.accent
                FileChange.Deleted -> c.delFg
                FileChange.Renamed, FileChange.Untracked -> c.fg3
            }
            Text(
                changeLetter(f.change),
                Modifier.width(18.dp),
                fontFamily = MonoFamily,
                fontSize = 13.sp,
                fontWeight = if (ui == UiStyle.Tile) FontWeight.SemiBold else FontWeight.Medium,
                color = letterColor,
                textAlign = TextAlign.Center,
            )
        }
        FilePath(f.path, Modifier.weight(1f))
        if (f.change == FileChange.Untracked) {
            Text("未跟踪", fontSize = 11.sp, color = c.fg3)
        }
        FileStageButton(staged = staged, enabled = enabled, onClick = onAction)
    }
}

/** .file-act: compact circular/square icon button to stage (+) or unstage (−) one file (demo.html). */
@Composable
private fun FileStageButton(staged: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val press = remember { MutableInteractionSource() }
    val sweep = if (ui == UiStyle.Plate) pressProgress(press, sweep = true).value else 0f
    val label = if (staged) "取消暂存" else "暂存"
    val icon = if (staged) Symbols.Remove else Symbols.Add
    val tint = when {
        !enabled -> c.fg3
        ui == UiStyle.Plate -> lerp(c.fg, c.bg, sweep)
        else -> c.fg2
    }
    val boxMod = when (ui) {
        UiStyle.Ink -> Modifier
            .size(32.dp)
            .shadow(if (c.isDark) 0.dp else 3.dp, CircleShape, ambientColor = c.scrim, spotColor = c.scrim)
            .inkSurface(c.float, c.edge, 16.dp)
        UiStyle.Tile, UiStyle.Clear -> Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(c.surface)
        UiStyle.Plate -> Modifier
            .size(28.dp)
            .background(c.bg, RectangleShape)
            .plateSweepFill(sweep, c.fg, PlateSweep.LeftToRight)
            .border(1.5.dp, if (enabled) c.fg else c.fg3, RectangleShape)
    }
    Box(
        Modifier
            .pressShrink(press, PressKind.Icon)
            .then(boxMod)
            .clickable(press, if (ui == UiStyle.Plate) null else controlPress(PressKind.Icon, fill = if (ui == UiStyle.Tile) c.line else c.surface2), enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (ui == UiStyle.Plate) 16.dp else 17.dp))
    }
}

/** .fpath: file name above its directory (showing "根目录" for repo-root files). */
@Composable
fun FilePath(path: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val dir = path.substringBeforeLast('/', "").ifEmpty { "根目录" }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            baseName(path),
            fontSize = if (mono) Clauderc.fontSizes.mono else 15.sp,
            fontFamily = if (mono) MonoFamily else null,
            fontWeight = when {
                mono -> null
                ui == UiStyle.Plate -> FontWeight.ExtraBold
                ui == UiStyle.Clear -> FontWeight.Normal
                else -> FontWeight.Medium
            },
            color = c.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            dir,
            fontFamily = MonoFamily,
            fontSize = 11.5.sp,
            color = if (ui == UiStyle.Plate) c.fg2 else c.fg3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

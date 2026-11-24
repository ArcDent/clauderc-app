package clauderc.app.sessions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.customActions
import clauderc.app.ui.theme.styled
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import clauderc.app.protocol.UsageResponse
import clauderc.app.protocol.UsageStale
import clauderc.app.ui.contextPercent
import clauderc.app.ui.needsReply
import clauderc.app.ui.sessionStateText
import kotlin.math.roundToInt
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import clauderc.app.ui.components.LocalOverlayActions
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import clauderc.app.machines.NavClearance
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.unit.sp
import clauderc.app.protocol.Subagent
import clauderc.app.ui.components.Spinner
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionState
import clauderc.app.ui.TimeText
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.GroupBlock
import clauderc.app.ui.components.ClaudercDialog
import clauderc.app.ui.components.ClaudercSheet
import clauderc.app.ui.components.FieldLabel
import clauderc.app.ui.components.Glyph
import clauderc.app.ui.components.Hero
import clauderc.app.ui.components.MenuItem
import clauderc.app.ui.components.SheetHead
import clauderc.app.ui.components.SnackHost
import clauderc.app.ui.components.LoadingCover
import clauderc.app.ui.components.LoadingArt
import clauderc.app.ui.components.SnackState
import clauderc.app.ui.components.StatusGlyph
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.components.TextAction
import clauderc.app.ui.components.TextTone
import clauderc.app.ui.components.UnderlineField
import clauderc.app.ui.components.groupDivider
import clauderc.app.data.UiStyle
import clauderc.app.git.busyText
import clauderc.app.git.busyTitle
import clauderc.app.ui.components.sticker
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.pressShrink
import clauderc.app.ui.theme.snapPressed
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.border
import clauderc.app.ui.theme.stillLayer

/** Sessions sit 32dp in under their directory's folder icon. */
private val DirIndent = 32.dp

fun glyphOf(state: SessionState) = when (state) {
    SessionState.Pending -> Glyph.Need
    SessionState.Working -> Glyph.Work
    SessionState.Idle -> Glyph.Idle
    SessionState.Stopped -> Glyph.Stop
}

private sealed interface Overlay {
    data class ConfirmStop(val session: Session) : Overlay
    data class ConfirmRemove(val session: Session) : Overlay
    data class Rename(val group: SessionGroup) : Overlay
    /** The branch sheet of one directory, looked up again on every change so it shows fresh branches. */
    data class Branches(val profileId: String, val dir: String) : Overlay
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    state: SessionsUiState,
    onRefresh: () -> Unit,
    onOpenChat: (Session) -> Unit,
    /** The bar's 「+」: a new session on this machine. */
    onCreate: () -> Unit,
    onRename: (profileId: String, name: String?) -> Unit,
    onStop: (Session) -> Unit,
    onRemove: (Session) -> Unit,
    onToggle: (SessionGroup) -> Unit = {},
    onToggleDir: (SessionGroup, DirGroup) -> Unit = { _, _ -> },
    snack: SnackState = remember { SnackState() },
    /** Opens a running subagent's page from its row under its session. */
    onOpenAgent: (Session, Subagent) -> Unit = { _, _ -> },
    /** Folds or unfolds the forks hung under a session. */
    onToggleForks: (SessionNode) -> Unit = {},
    /** The branch sheet of a directory opens. */
    onBranches: (DirGroup) -> Unit = {},
    /** Narrows a directory's rows to a branch; null shows every branch. */
    onFilterBranch: (SessionGroup, DirGroup, String?) -> Unit = { _, _, _ -> },
    onSwitchBranch: (DirGroup, String) -> Unit = { _, _ -> },
    onConfirmSwitch: () -> Unit = {},
    onDismissSwitch: () -> Unit = {},
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val machine = state.machine
    val online = machine?.online == true
    val pull = rememberPullToRefreshState()
    // The row slid open to its 「停止」 or 「移出」; one at a time.
    val swipe = remember { SwipeHub() }
    // Rows confirmed for removal: false while the row folds away, true once session.remove went out. An id
    // goes when the list no longer holds the session, or when the removal fails so the row comes back whole.
    val removing = remember { mutableStateMapOf<String, Boolean>() }
    val listed = state.groups?.flatMapTo(HashSet()) { g -> g.sessions.map { it.id } }
    LaunchedEffect(listed) {
        if (listed != null) removing.keys.filter { removing[it] == true && it !in listed }.forEach { removing.remove(it) }
    }
    LaunchedEffect(state.removeFailed) {
        if (state.removeFailed > 0) {
            removing.keys.filter { removing[it] == true && it !in state.removing }.forEach { removing.remove(it) }
        }
    }
    val rowActs = RowActs(
        swipe = swipe,
        removing = removing,
        stopping = state.stopping,
        onAct = { s -> overlay = if (s.state == SessionState.Stopped) Overlay.ConfirmRemove(s) else Overlay.ConfirmStop(s) },
        onLeft = { s -> removing[s.id] = true; onRemove(s) },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // The one way in to a new session (mockup round 7): the profile is picked on the form.
            AppBar(actions = { if (machine != null) SymbolButton(Symbols.Add, "新建会话", onClick = onCreate, enabled = online) })
            if (machine != null && !online) {
                Row(
                    Modifier.fillMaxWidth().background(c.surface).padding(horizontal = sp.page, vertical = sp.sp3),
                    horizontalArrangement = Arrangement.spacedBy(sp.sp3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.CloudOff, contentDescription = null, tint = c.fg3, modifier = Modifier.size(20.dp))
                    Text("已离线 · 最后在线 ${TimeText.clock(machine.lastSeenAt)}", fontSize = Clauderc.fontSizes.label, color = c.fg2)
                }
            }
            // The machine's first session list deals cards over the page until it arrives.
            LoadingCover(machine != null && state.groups == null, LoadingArt.Deal, Modifier.fillMaxSize(), label = "正在读取会话") {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                state = pull,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pull,
                        isRefreshing = state.refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = c.sheet,
                        color = c.fg,
                    )
                },
            ) {
                val list = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize().stillLayer(list).testTag("sessionList"), state = list, contentPadding = PaddingValues(bottom = NavClearance)) {
                    item {
                        Hero(machine?.name ?: "会话", bottom = sp.sp2) {
                            if (machine?.claudeVersion?.isNotEmpty() == true) Text("claude ${machine.claudeVersion}")
                        }
                    }
                    state.usage?.takeIf { it.available }?.let { usage ->
                        item(key = "usage") { UsageBlock(usage) }
                    }
                    val groups = state.groups
                    if (groups == null) {
                        item {
                            Text(
                                if (machine == null) "先在目标机页选一台在线的目标机" else "正在读取会话",
                                modifier = Modifier.padding(sp.page),
                                fontSize = Clauderc.fontSizes.label,
                                color = c.fg3,
                            )
                        }
                    } else {
                        groups.forEach { group ->
                            item(key = "h:" + group.profile.id) {
                                GroupHeader(group, onRename = { overlay = Overlay.Rename(group) }, onToggle = { onToggle(group) })
                            }
                            if (group.sessions.isEmpty()) {
                                item(key = "e:" + group.profile.id) {
                                    Fold(visible = !group.collapsed) {
                                        if (Clauderc.look.cards) {
                                            GroupBlock(padding = sp.sp4) { Text("没有会话", fontSize = Clauderc.fontSizes.label, color = c.fg3) }
                                        } else {
                                            Text(
                                                "没有会话",
                                                modifier = Modifier.padding(start = sp.page, end = sp.page, top = sp.sp2, bottom = sp.sp3),
                                                fontSize = Clauderc.fontSizes.label,
                                                color = c.fg3,
                                            )
                                        }
                                    }
                                }
                            }
                            // One item per group, so folding can shrink the rows together like the mockup.
                            if (group.sessions.isNotEmpty()) {
                                item(key = "g:" + group.profile.id) {
                                    Fold(visible = !group.collapsed) {
                                        GroupBlock(ruled = true) {
                                            group.dirs.forEachIndexed { i, dir ->
                                                key(dir.cwd) {
                                                    DirRow(
                                                        dir,
                                                        onToggle = { onToggleDir(group, dir) },
                                                        modifier = Modifier.groupDivider(i > 0),
                                                        onBranches = { overlay = Overlay.Branches(group.profile.id, dir.cwd); onBranches(dir) },
                                                    )
                                                    Fold(visible = !dir.collapsed) {
                                                        val reveal = filterReveal(dir.branchFilter)
                                                        Column {
                                                            dir.rows.forEachIndexed { r, node ->
                                                                key(node.session.id) {
                                                                    SessionNodeRows(
                                                                        node,
                                                                        enabled = online,
                                                                        onOpen = onOpenChat,
                                                                        acts = rowActs,
                                                                        onOpenAgent = onOpenAgent,
                                                                        onToggleForks = { onToggleForks(node) },
                                                                        showBranch = dir.rowsShowBranch,
                                                                        drafts = state.drafts,
                                                                        modifier = Modifier.graphicsLayer {
                                                                            val p = Motion.rowIn(r, reveal.floatValue)
                                                                            alpha = p
                                                                            translationY = -4.dp.toPx() * (1 - p)
                                                                        },
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
        SnackHost(snack, Modifier.align(Alignment.BottomCenter), bottom = 88.dp)
    }

    when (val o = overlay) {
        is Overlay.ConfirmStop -> ClaudercDialog(
            onDismiss = { overlay = null },
            title = "停止「${display(o.session)}」？",
            text = stopWarning(o.session),
            buttons = {
                TextAction("取消", onClick = { overlay = null })
                TextAction("停止", onClick = { overlay = null; onStop(o.session) }, tone = TextTone.Danger)
            },
        )
        is Overlay.ConfirmRemove -> ClaudercDialog(
            onDismiss = { overlay = null },
            title = "移出「${display(o.session)}」？",
            text = "不再显示在列表里，对话记录仍留在目标机上。",
            buttons = {
                TextAction("取消", onClick = { overlay = null })
                // The row folds away first, then session.remove goes out.
                TextAction("移出", onClick = { overlay = null; removing[o.session.id] = false }, tone = TextTone.Danger)
            },
        )
        is Overlay.Rename -> RenameDialog(
            group = o.group,
            machineName = machine?.name.orEmpty(),
            onDismiss = { overlay = null },
            onSave = { name -> overlay = null; onRename(o.group.profile.id, name) },
        )
        is Overlay.Branches -> {
            val group = state.groups?.firstOrNull { it.profile.id == o.profileId }
            val dir = group?.dirs?.firstOrNull { it.cwd == o.dir }
            if (group == null || dir == null) {
                SideEffect { overlay = null }
            } else {
                BranchSheet(
                    dir,
                    onDismiss = { overlay = null },
                    onFilter = { b -> overlay = null; onFilterBranch(group, dir, b) },
                    onSwitch = { b -> overlay = null; onSwitchBranch(dir, b) },
                )
            }
        }
        null -> Unit
    }

    state.switchAsk?.let { ask ->
        ClaudercDialog(
            onDismiss = onDismissSwitch,
            title = busyTitle(ask.unsure),
            text = busyText(ask.sessionName, ask.unsure, ask.title, "切换"),
            buttons = {
                TextAction("取消", onClick = onDismissSwitch)
                TextAction("仍然切换", onClick = onConfirmSwitch)
            },
        )
    }
}

/**
 * Time since the rows were last narrowed to another branch, for [Motion.rowIn]: the new rows fade in
 * from 4dp above, top down (demo.html fadeRows). Settled at [Motion.rowsInMillis].
 */
@Composable
private fun filterReveal(filter: String?): MutableFloatState {
    val done = Motion.rowsInMillis.toFloat()
    val clock = remember { mutableFloatStateOf(done) }
    var last by remember { mutableStateOf(filter) }
    if (filter != last) {
        last = filter
        clock.floatValue = 0f
    }
    LaunchedEffect(filter) {
        if (clock.floatValue < done) animate(clock.floatValue, done, animationSpec = tween(Motion.rowsInMillis, easing = LinearEasing)) { v, _ -> clock.floatValue = v }
    }
    return clock
}

/**
 * A directory's branches: 「全部分支」, each local branch with its session count, then the deleted branches
 * sessions are still on. A row narrows the list to its branch; 「切换」 at the end switches the directory,
 * except for the current branch, one a worktree holds and a deleted one.
 */
@Composable
private fun BranchSheet(dir: DirGroup, onDismiss: () -> Unit, onFilter: (String?) -> Unit, onSwitch: (String) -> Unit) {
    val sp = Clauderc.spacing
    fun count(n: Int) = if (n > 0) "$n 个会话" else "没有会话"
    ClaudercSheet(onDismiss = onDismiss) {
        SheetHead("${dir.title} 的分支", "点分支只看它上面的会话")
        MenuItem("全部分支", onClick = { onFilter(null) }, supporting = count(dir.sessions.size), checked = dir.branchFilter == null)
        val b = dir.branches
        if (b == null) {
            Row(Modifier.padding(horizontal = sp.page, vertical = sp.sp4)) { Spinner(size = 16.dp, color = Clauderc.colors.fg3) }
            return@ClaudercSheet
        }
        b.local.forEach { name ->
            val on = dir.sessions.filter { it.branch == name }
            val worktree = on.firstNotNullOfOrNull { it.worktree }
            val where = when {
                name == dir.currentBranch -> "当前分支 · "
                worktree != null -> "在 worktree ${worktree.name} 里 · "
                else -> ""
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MenuItem(name, onClick = { onFilter(name) }, mono = true, supporting = where + count(on.size), checked = dir.branchFilter == name, modifier = Modifier.weight(1f))
                if (name != dir.currentBranch && worktree == null) {
                    // A plain text button here, also in 贴纸, which draws only a sheet's bottom actions as stickers.
                    CompositionLocalProvider(LocalOverlayActions provides false) {
                        TextAction("切换", onClick = { onSwitch(name) }, modifier = Modifier.padding(end = sp.sp3).semantics { contentDescription = "切换到 $name" })
                    }
                }
            }
        }
        // Sessions still on a branch deleted since: a row of its own that narrows the list, with nothing to switch to.
        dir.goneBranches.forEach { name ->
            val on = dir.sessions.count { it.branch == name }
            MenuItem(name, onClick = { onFilter(name) }, mono = true, muted = true, supporting = "已删除 · " + count(on), checked = dir.branchFilter == name)
        }
    }
}

/** Grows a folded group's rows in and shrinks them out, like the tool rows of the chat. */
@Composable
private fun Fold(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(Motion.enter()) + fadeIn(Motion.enter()),
        exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
    ) { content() }
}

/** The stop confirmation names the interruption when the session is working (session-lifecycle spec). */
fun stopWarning(s: Session): String =
    if (s.state == SessionState.Working) "它正在工作，停止会打断当前任务。\n对话记录会保留，之后发消息可以唤醒。"
    else "对话记录会保留，之后发消息可以唤醒。"

@Composable
private fun GroupHeader(group: SessionGroup, onRename: () -> Unit, onToggle: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Row(
        // .gh: 贴纸 sits it closer to its card, 格栅 lines it up with the rule 16dp in.
        Modifier.fillMaxWidth().padding(
            start = if (Clauderc.style == UiStyle.Grid) sp.sp4 else sp.page,
            end = if (Clauderc.style == UiStyle.Grid) sp.sp1 else sp.sp2,
            top = if (Clauderc.look.cards) sp.sp5 else sp.sp6,
            bottom = if (Clauderc.look.cards) sp.sp2 else sp.sp1,
        ),
        horizontalArrangement = Arrangement.spacedBy(sp.sp2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val press = remember { MutableInteractionSource() }
        Row(
            Modifier.then(
                if (group.canRename) {
                    Modifier.pressShrink(press).clickable(press, LocalIndication.current, role = Role.Button, onClickLabel = "改档位名", onClick = onRename)
                } else {
                    Modifier
                },
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val grid = Clauderc.style == UiStyle.Grid
            Text(
                group.title,
                fontSize = if (grid) 12.sp else if (group.titleIsHost) Clauderc.fontSizes.mono else Clauderc.fontSizes.label,
                fontFamily = if (group.titleIsHost || grid) MonoFamily else null,
                fontWeight = if (Clauderc.style == UiStyle.Sticker) FontWeight.Bold else FontWeight.Medium,
            )
            if (group.canRename) Icon(Symbols.Edit, contentDescription = null, tint = c.fg3, modifier = Modifier.size(16.dp))
        }
        if (group.collapsed) {
            // Folded: the counts take the note's place, so a session waiting for an answer is not missed.
            Text(
                buildAnnotatedString {
                    append("${group.sessions.size} 个会话")
                    if (group.pending > 0) {
                        append(" · ")
                        withStyle(SpanStyle(color = c.accent)) { append("${group.pending} 个等你") }
                    }
                },
                modifier = Modifier.weight(1f),
                fontSize = Clauderc.fontSizes.caption,
                color = c.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                group.note.orEmpty(),
                modifier = Modifier.weight(1f),
                fontSize = if (group.profile.kind == clauderc.app.protocol.ProfileKind.Other) Clauderc.fontSizes.caption else Clauderc.fontSizes.monoSm,
                fontFamily = if (group.profile.kind == clauderc.app.protocol.ProfileKind.Other) null else MonoFamily,
                color = c.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val turn by animateFloatAsState(if (group.collapsed) 0f else 180f, Motion.turn(), label = "fold")
        SymbolButton(
            Symbols.ExpandMore,
            if (group.collapsed) "展开${group.title}档位" else "折叠${group.title}档位",
            onClick = onToggle,
            small = true,
            modifier = Modifier.rotate(turn),
        )
    }
}

/**
 * A working directory inside a group: its name, its branch and counts; tapping folds its sessions. The
 * branch (.dbr) is a button of its own that opens the branch sheet; it turns accent while the rows are
 * narrowed to it.
 */
@Composable
private fun DirRow(dir: DirGroup, onToggle: () -> Unit, modifier: Modifier = Modifier, onBranches: () -> Unit = {}) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val turn by animateFloatAsState(if (dir.collapsed) 0f else 180f, Motion.turn(), label = "dirFold")
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (dir.collapsed) "展开目录" else "折叠目录", onClick = onToggle)
            .padding(start = Clauderc.look.rowStart, end = sp.sp4, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(sp.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Symbols.Folder, contentDescription = null, tint = c.fg3, modifier = Modifier.size(20.dp))
        DirTitleLayout(Modifier.weight(1f)) {
            Text(dir.title, fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val branch = dir.branch
            if (branch != null) {
                // The button's touch area reaches 8dp left and 4dp right of its 8dp and 4dp padding, so the text sits 16dp after the name.
                val on = dir.branchFilter != null
                Row(
                    Modifier
                        .padding(start = sp.sp2, end = sp.sp1)
                        .clip(RoundedCornerShape(Clauderc.radii.sm))
                        .clickable(role = Role.Button, onClickLabel = "${dir.title} 的分支", onClick = onBranches)
                        .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(branch, fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, color = if (on) c.accent else c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Symbols.ExpandMore, contentDescription = null, tint = if (on) c.accent else c.fg3, modifier = Modifier.size(16.dp))
                }
            }
            Text(
                buildAnnotatedString {
                    append("${dir.shown.size} 个")
                    if (dir.pending > 0) {
                        append(" · ")
                        withStyle(SpanStyle(color = c.accent)) { append("${dir.pending} 个等你") }
                    }
                },
                modifier = Modifier.padding(start = if (branch == null) sp.sp3 else 0.dp),
                fontSize = Clauderc.fontSizes.caption,
                color = c.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Symbols.ExpandMore, contentDescription = null, tint = c.fg3, modifier = Modifier.size(20.dp).rotate(turn))
    }
}

/**
 * A directory title's name, branch button (when there is one) and counts in a row. As in the mockup the
 * name and branch keep their width and the counts give way first. When the name and branch alone do not
 * fit, the branch keeps at least half the row and the name shortens.
 */
@Composable
private fun DirTitleLayout(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { parts, c ->
        val loose = c.copy(minWidth = 0)
        val branch = if (parts.size == 3) {
            // Both whole when they fit; else the branch keeps half the row, or what the whole name leaves if more.
            val room = c.maxWidth - parts[0].maxIntrinsicWidth(c.maxHeight)
            val want = parts[1].maxIntrinsicWidth(c.maxHeight)
            parts[1].measure(loose.copy(maxWidth = if (want <= room) want else maxOf(c.maxWidth / 2, room).coerceAtMost(c.maxWidth)))
        } else {
            null
        }
        val bw = branch?.width ?: 0
        val name = parts[0].measure(loose.copy(maxWidth = (c.maxWidth - bw).coerceAtLeast(0)))
        val count = parts.last().measure(loose.copy(maxWidth = (c.maxWidth - bw - name.width).coerceAtLeast(0)))
        val h = maxOf(name.height, branch?.height ?: 0, count.height)
        layout(c.maxWidth, h) {
            var x = 0
            for (p in listOfNotNull(name, branch, count)) {
                p.placeRelative(x, (h - p.height) / 2)
                x += p.width
            }
        }
    }
}

/**
 * The plan usage under the machine's name: a thin bar per limit with its share and when it starts
 * over. Old numbers, kept while the login is expired or the read failed, turn grey under a note.
 */
@Composable
private fun UsageBlock(usage: UsageResponse) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val stale = usage.stale != null
    val rows = listOfNotNull(usage.fiveHour?.let { "5 小时" to it }, usage.sevenDay?.let { "每周" to it })
    val note = when (usage.stale) {
        null -> null
        UsageStale.Expired -> usage.fetchedAt?.let { "登录凭证已过期，显示的是 ${TimeText.clock(it)} 的数据" } ?: "登录凭证已过期"
        UsageStale.Failed -> usage.fetchedAt?.let { "用量读取失败，显示的是 ${TimeText.clock(it)} 的数据" } ?: "用量读取失败"
    }
    val resets = rows.map { (_, w) -> w.resetsAt?.let { TimeText.resets(it) }.orEmpty() }
    val caption = TextStyle(fontSize = Clauderc.fontSizes.caption)
    val measurer = rememberTextMeasurer()
    // Every row gives its reset time the room of the widest, so the bars line up.
    val resetWidth = with(LocalDensity.current) { (resets.maxOfOrNull { measurer.measure(it, caption).size.width } ?: 0).toDp() }
    val ui = Clauderc.style
    val fill = when {
        stale -> c.fg3
        ui == UiStyle.Sticker -> c.accent
        ui == UiStyle.Grid -> c.fg
        else -> c.fg2
    }
    // 格栅 sets the whole block in monospace.
    val mono = if (ui == UiStyle.Grid) MonoFamily else null
    val labelSize = if (ui == UiStyle.Grid) 12.sp else Clauderc.fontSizes.label
    GroupBlock(Modifier.padding(bottom = sp.sp2), padding = sp.sp4) {
        Column(
            Modifier.padding(horizontal = Clauderc.look.blockGutter),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rows.forEachIndexed { i, (label, w) ->
                val pct = w.utilization.roundToInt().coerceIn(0, 100)
                Row(horizontalArrangement = Arrangement.spacedBy(sp.sp3), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.width(44.dp), fontSize = labelSize, fontFamily = mono, color = if (stale) c.fg3 else c.fg2, maxLines = 1)
                    UsageBar(pct / 100f, fill, Modifier.weight(1f))
                    Text(
                        "$pct%",
                        modifier = Modifier.width(40.dp),
                        fontSize = labelSize,
                        fontFamily = mono,
                        fontWeight = if (ui == UiStyle.Sticker) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (stale) c.fg3 else c.fg,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                    Text(resets[i], modifier = Modifier.width(resetWidth), style = caption, fontFamily = mono, color = c.fg3, maxLines = 1)
                }
            }
            if (note != null) Text(note, fontSize = Clauderc.fontSizes.caption, fontFamily = mono, color = c.fg3)
        }
    }
}

/**
 * One limit's bar (.u-bar). 清玻: a 3dp hairline track. 格栅: a 6dp square bar filled solid. 贴纸: a
 * 14dp ink-outlined capsule, filled flat and closed off with an ink stroke.
 */
@Composable
private fun UsageBar(share: Float, fill: Color, modifier: Modifier) {
    val c = Clauderc.colors
    when (Clauderc.style) {
        UiStyle.Clear -> Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
            Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(fill))
        }
        UiStyle.Grid -> Box(modifier.height(6.dp).background(c.surface)) {
            Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(fill))
        }
        UiStyle.Sticker -> Box(modifier.height(14.dp).sticker(c.bg, c.ink, 7.dp).padding(2.dp)) {
            val ink = c.ink
            Box(
                Modifier.fillMaxWidth(share).fillMaxHeight().background(fill).drawWithContent {
                    drawContent()
                    if (share in 0.001f..0.999f) drawRect(ink, Offset(size.width, 0f), Size(2.dp.toPx(), size.height))
                },
            )
        }
    }
}

/** The subagents of [s] that run and can be opened; a stopped session has none. */
fun runningAgents(s: Session): List<Subagent> =
    if (s.state == SessionState.Stopped) emptyList() else s.agents.orEmpty().filter { it.running && it.id != null }

/**
 * A session's running subagents, indented under it: the spinner lines up with the session's title, then
 * the description and the type. The block grows in and shrinks out as subagents start and end.
 */
@Composable
private fun AgentRows(agents: List<Subagent>, enabled: Boolean, onOpen: (Subagent) -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    // Kept while the block shrinks away, so the last rows leave with it.
    var shown by remember { mutableStateOf(agents) }
    if (agents.isNotEmpty()) shown = agents
    Fold(visible = agents.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().animateContentSize(Motion.enter()).padding(bottom = 6.dp).testTag("agentRows")) {
            shown.forEach { a ->
                key(a.id) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "打开子代理") { onOpen(a) }
                            .padding(start = Clauderc.look.rowStart + DirIndent + GlyphWidth + sp.sp4, end = sp.sp4, top = 7.dp, bottom = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(sp.sp3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spinner(size = 12.dp, color = c.fg3)
                        Text(a.description.ifEmpty { a.type }, Modifier.weight(1f), fontSize = Clauderc.fontSizes.label, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.type, fontFamily = MonoFamily, fontSize = 11.sp, color = c.fg3, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The width of a session row's status symbol. */
private val GlyphWidth = 10.dp

/** Forks sit 22dp further in than the session they came from (.fkids). */
private val ForkIndent = 22.dp

/** A session with forks moves its status symbol 6dp left, so the tree line runs clear of the forks' symbols. */
private val ForkShift = 6.dp

/** Where a session row's status symbol sits: its centre from the row's top left, and its half width. */
private data class GlyphSpot(val x: Dp, val y: Dp, val half: Dp)

@Composable
private fun glyphSpot(): GlyphSpot {
    // 格栅 sets a 14 x 16dp terminal character; the others a 10dp symbol. Both sit 6dp under the row's 14dp top gap.
    val (w, h) = if (Clauderc.style == UiStyle.Grid) 14.dp to 16.dp else GlyphWidth to GlyphWidth
    return GlyphSpot(Clauderc.look.rowStart + DirIndent + w / 2, 14.dp + 6.dp + h / 2, w / 2)
}

/**
 * The fork tree's line (.dkids --tree): 清玻 a faint hairline with round elbows, 格栅 a solid line with
 * square elbows, 贴纸 a 2dp ink line.
 */
private data class TreeLine(val color: Color, val width: Dp, val corner: Dp)

@Composable
private fun treeLine(): TreeLine {
    val c = Clauderc.colors
    return when (Clauderc.style) {
        UiStyle.Clear -> TreeLine(c.fg.copy(alpha = 0.26f), 1.5.dp, 6.dp)
        UiStyle.Grid -> TreeLine(c.fg, 1.5.dp, 0.dp)
        UiStyle.Sticker -> TreeLine(c.ink, 2.dp, 6.dp)
    }
}

/**
 * What a session row can do besides opening: slide open to 「停止」 or 「移出」, and fold away once its
 * removal is confirmed. [removing] is the screen's: false while a row folds away, true once its removal went out.
 */
private class RowActs(
    val swipe: SwipeHub,
    val removing: Map<String, Boolean>,
    /** Session ids whose session.stop is still in flight; they offer no swipe action until it finishes. */
    val stopping: Set<String>,
    /** The slid-open button was tapped: ask before stopping or removing. */
    val onAct: (Session) -> Unit,
    /** The row has folded away: send the removal. */
    val onLeft: (Session) -> Unit,
)

/** The row slid open, by session id; sliding another closes it, as does a tap on any row. */
@Stable
private class SwipeHub {
    var open by mutableStateOf<String?>(null)
}

/** How far a row slides open: the width of its 「停止」 or 「移出」. */
private val SwipeWidth = 76.dp

/**
 * A session and the forks hung under it. No rule between them: a line runs down from the session's
 * symbol and turns into each fork just short of the fork's symbol. Unfolding first draws the line down
 * from the symbol in 30% of durM, then the forks grow in along it on the enter rule, cut off at their
 * bottom edge and not fading, so the line never breaks; folding shrinks the forks on the leaving rule,
 * then takes the line back up into the symbol.
 */
@Composable
private fun SessionNodeRows(
    node: SessionNode,
    enabled: Boolean,
    onOpen: (Session) -> Unit,
    acts: RowActs,
    onOpenAgent: (Session, Subagent) -> Unit,
    onToggleForks: () -> Unit,
    showBranch: Boolean = false,
    modifier: Modifier = Modifier,
    drafts: Map<String, String> = emptyMap(),
) = Leaving(node.session, acts, modifier) {
    val s = node.session
    val forks = node.forks
    val spot = glyphSpot()
    val tree = treeLine()
    val m = Clauderc.motion
    val stub = (m.durM * 0.3f).roundToInt()
    val line = remember { Animatable(if (node.forksCollapsed) 0f else 1f) }
    val grown = remember { Animatable(if (node.forksCollapsed) 0f else 1f) }
    LaunchedEffect(node.forksCollapsed) {
        if (node.forksCollapsed) {
            grown.animateTo(0f, tween(m.durS, easing = m.easeEmphIn))
            line.animateTo(0f, tween(stub, easing = LinearEasing))
        } else {
            line.animateTo(1f, tween(stub, easing = LinearEasing))
            grown.animateTo(1f, tween(m.durM, easing = m.easeEmph))
        }
    }
    val lineX = spot.x - ForkShift
    Box(Modifier.groupDivider()) {
        Column {
            val agents = runningAgents(s)
            SessionRow(
                s, enabled = enabled, onOpen = { onOpen(s) }, acts = acts, tight = agents.isNotEmpty(),
                forks = forks.size, forksCollapsed = node.forksCollapsed, onToggleForks = onToggleForks, showBranch = showBranch, draft = drafts[s.id],
            )
            AgentRows(agents, enabled = enabled, onOpen = { onOpenAgent(s, it) })
        }
        // The line from just under the symbol to the bottom, grown down as far as [line] says.
        if (forks.isNotEmpty()) {
            Box(Modifier.matchParentSize().padding(top = spot.y + spot.half + 4.dp)) {
                Box(
                    Modifier
                        .offset(x = lineX - tree.width / 2)
                        .layout { measurable, constraints ->
                            val p = measurable.measure(Constraints.fixed(tree.width.roundToPx(), (constraints.maxHeight * line.value).roundToInt()))
                            layout(p.width, p.height) { p.place(0, 0) }
                        }
                        .background(tree.color)
                        .testTag("treeLine:${s.id}"),
                )
            }
        }
    }
    val shown by remember { derivedStateOf { grown.value > 0f } }
    if (forks.isNotEmpty() && shown) {
        Column(
            Modifier
                .testTag("forks:${s.id}")
                .clipToBounds()
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    layout(p.width, (p.height * grown.value).roundToInt()) { p.place(0, 0) }
                }
                .padding(start = ForkIndent),
        ) {
            forks.forEachIndexed { i, f ->
                key(f.id) {
                    val last = i == forks.lastIndex
                    Leaving(
                        f, acts,
                        Modifier.drawBehind {
                            val x = (lineX - ForkIndent).toPx()
                            val y = spot.y.toPx()
                            val r = tree.corner.toPx()
                            val end = (spot.x - spot.half - 2.dp).toPx()
                            val stroke = Stroke(tree.width.toPx())
                            drawPath(
                                Path().apply {
                                    moveTo(x, 0f)
                                    lineTo(x, y - r)
                                    if (r > 0f) quadraticTo(x, y, x + r, y)
                                    lineTo(end, y)
                                },
                                tree.color,
                                style = stroke,
                            )
                            if (!last) drawLine(tree.color, Offset(x, y), Offset(x, size.height), tree.width.toPx())
                        },
                    ) {
                        val agents = runningAgents(f)
                        SessionRow(f, enabled = enabled, onOpen = { onOpen(f) }, acts = acts, tight = agents.isNotEmpty(), showBranch = showBranch, draft = drafts[f.id])
                        AgentRows(agents, enabled = enabled, onOpen = { onOpenAgent(f, it) })
                    }
                }
            }
        }
    }
}

/**
 * A row whose removal was confirmed folds away, its height and its colour going together on the enter
 * rule, and only then is the removal sent (demo.html do-remove). Rows not leaving get no extra layer.
 */
@Composable
private fun Leaving(s: Session, acts: RowActs, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val state = acts.removing[s.id]
    val left = remember { Animatable(1f) }
    val leaving = state != null
    val onLeft by rememberUpdatedState(acts.onLeft)
    LaunchedEffect(leaving) {
        when {
            !leaving -> left.snapTo(1f)
            state == false -> {
                left.animateTo(0f, Motion.enter())
                onLeft(s)
            }
            else -> left.snapTo(0f)
        }
    }
    Column(
        modifier.then(
            if (leaving) {
                Modifier
                    .testTag("leaving:${s.id}")
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        layout(p.width, (p.height * left.value).roundToInt()) { p.place(0, 0) }
                    }
                    .graphicsLayer { alpha = left.value }
            } else {
                Modifier
            },
        ),
        content = content,
    )
}

/**
 * [tight] leaves out most of the bottom gap, for the subagent rows that follow. A session with
 * [forks] moves its symbol left for the tree line and gets a button that folds them.
 *
 * Sliding the row left brings in 「停止」 for a running session or 「移出」 for a stopped one, a small
 * round button over a word; the status symbol stays where it is and only what is right of it moves, cut
 * off just after the symbol. Let go past half the button's width it stays open; else it slides back.
 * Pulled past the button it drags with a third of the finger's travel. A tap on any row while one is
 * open only closes it.
 */
@Composable
private fun SessionRow(
    s: Session,
    enabled: Boolean,
    onOpen: () -> Unit,
    acts: RowActs,
    modifier: Modifier = Modifier,
    tight: Boolean = false,
    forks: Int = 0,
    forksCollapsed: Boolean = false,
    onToggleForks: () -> Unit = {},
    showBranch: Boolean = false,
    /** Text typed into the session and not sent: it takes the second line's place. */
    draft: String? = null,
) {
    val sp = Clauderc.spacing
    val hub = acts.swipe
    val width = with(LocalDensity.current) { SwipeWidth.toPx() }
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val enter = Motion.enter<Float>()
    var pulled by remember { mutableFloatStateOf(0f) }
    val canSwipe = enabled && s.id !in acts.stopping
    // Another row slid open, a tap closed this one, or a stop is now in flight: back it goes.
    LaunchedEffect(hub.open, canSwipe) { if ((hub.open != s.id || !canSwipe) && slide.value != 0f) slide.animateTo(0f, enter) }
    val drag = rememberDraggableState { d ->
        pulled = (pulled + d).coerceAtMost(0f)
        // Past the button's width the row drags with a third of the finger's travel.
        val x = if (pulled < -width) -width + (pulled + width) / 3 else pulled
        scope.launch { slide.snapTo(x) }
    }
    val act = if (s.state == SessionState.Stopped) "移出" else "停止"
    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .draggable(
                drag,
                Orientation.Horizontal,
                enabled = canSwipe,
                onDragStarted = {
                    pulled = slide.value
                    hub.open = s.id
                },
                onDragStopped = { v ->
                    val open = slide.value < -width / 2 || v < -1000f
                    if (!open && hub.open == s.id) hub.open = null
                    slide.animateTo(if (open) -width else 0f, enter)
                },
            )
            .semantics {
                isTraversalGroup = true
                if (canSwipe) customActions = listOf(CustomAccessibilityAction(act) { acts.onAct(s); true })
            }
            .testTag("sessionRow:${s.id}"),
    ) {
        Row(Modifier.fillMaxWidth().padding(end = sp.sp2), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .clickable(enabled = enabled, role = Role.Button) {
                        if (hub.open != null) hub.open = null else onOpen()
                    }
                    // Without the fold button the time keeps the page margin from the right edge.
                    .padding(start = Clauderc.look.rowStart + DirIndent, end = if (forks > 0) 0.dp else sp.page - sp.sp2, top = 14.dp, bottom = if (tight) 4.dp else 14.dp),
                horizontalArrangement = Arrangement.spacedBy(sp.sp4),
            ) {
                // A change of state crossfades: the old symbol leaves in durS while the new one comes in over durM.
                AnimatedContent(
                    if (needsReply(s)) Glyph.Need else glyphOf(s.state),
                    Modifier.padding(top = 6.dp).offset(x = if (forks > 0) -ForkShift else 0.dp),
                    transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) using null },
                    label = "glyph",
                ) { StatusGlyph(it) }
                Column(Modifier.weight(1f).clipToBounds()) {
                    Column(Modifier.graphicsLayer { translationX = slide.value }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        SessionRowText(s, showBranch, draft)
                    }
                }
            }
            if (forks > 0) {
                val turn by animateFloatAsState(if (forksCollapsed) 0f else 180f, Motion.turn(), label = "forkFold")
                SymbolButton(
                    Symbols.ExpandMore,
                    if (forksCollapsed) "展开 $forks 个 fork" else "收起 $forks 个 fork",
                    onClick = onToggleForks,
                    small = true,
                    modifier = Modifier.graphicsLayer { translationX = slide.value }.rotate(turn),
                )
            }
        }
        val out by remember { derivedStateOf { slide.value < 0f && canSwipe } }
        if (out) {
            Box(Modifier.matchParentSize()) {
                SwipeAction(
                    act,
                    if (s.state == SessionState.Stopped) Symbols.Delete else Symbols.PowerSettingsNew,
                    onClick = {
                        hub.open = null
                        acts.onAct(s)
                    },
                    modifier = Modifier.align(Alignment.CenterEnd).width(SwipeWidth).fillMaxHeight().graphicsLayer { translationX = width + slide.value },
                )
            }
        }
    }
}

/** A session row's title and time over its state line; the part of the row that slides. */
@Composable
private fun SessionRowText(s: Session, showBranch: Boolean, draft: String?) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Row(horizontalArrangement = Arrangement.spacedBy(sp.sp3), verticalAlignment = Alignment.Bottom) {
        Text(
            display(s),
            modifier = Modifier.weight(1f),
            fontSize = Clauderc.fontSizes.body,
            color = if (s.state == SessionState.Stopped) c.fg2 else c.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val since = TimeText.relative(s.lastActiveAt)
        // 格栅 sets the time and the second line in small monospace.
        val grid = Clauderc.style == UiStyle.Grid
        Text(contextPercent(s.context)?.let { "上下文 $it% · $since" } ?: since, fontSize = if (grid) 11.5.sp else Clauderc.fontSizes.caption, fontFamily = if (grid) MonoFamily else null, color = c.fg3, maxLines = 1)
    }
    // The directory is the row above, so the second line is the state and Claude Code's one-line progress, as in its agent view.
    // A worktree session leads with the worktree mark and its branch; others name their branch when [showBranch].
    // A draft takes the whole line: 「草稿：」 and its first line (.draft).
    val branch = s.branch?.takeIf { s.worktree != null || showBranch }
    val drafted = draft?.trim()?.takeIf { it.isNotEmpty() }?.lineSequence()?.first()
    Text(
        buildAnnotatedString {
            if (drafted != null) {
                withStyle(SpanStyle(color = c.danger)) { append("草稿：") }
                append(drafted)
                return@buildAnnotatedString
            }
            if (branch != null) {
                if (s.worktree != null) appendInlineContent(WorktreeMark, "worktree")
                withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = if (Clauderc.style == UiStyle.Grid) 11.5.sp else Clauderc.fontSizes.monoSm)) { append(branch) }
                append(" · ")
            }
            val word = sessionStateText(s)
            if (s.state == SessionState.Pending || needsReply(s)) withStyle(SpanStyle(color = c.accent)) { append(word) } else append(word)
            s.detail?.takeIf { it.isNotBlank() }?.let { append(" · ${it.trim()}") }
        },
        fontSize = if (Clauderc.style == UiStyle.Grid) 11.5.sp else Clauderc.fontSizes.label,
        fontFamily = if (Clauderc.style == UiStyle.Grid) MonoFamily else null,
        color = c.fg2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        inlineContent = worktreeMark,
    )
}

/**
 * The button a slid row brings in (.swipe-act): one danger-red symbol and no word under it, [text]
 * read out instead, drawn as each style draws its rewind button. 清玻 the bare 24dp symbol; 格栅 a 40dp
 * square in a 1.5dp red line; 贴纸 a 40dp card-white circle in a 2dp ink line, no shadow. A press shows
 * at once and lets go in durS: 清玻's symbol shrinks and turns left like a knob, 格栅 fills red under a
 * white symbol, 贴纸 fills red under a white symbol as it shrinks and tilts left.
 */
@Composable
private fun SwipeAction(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Clauderc.colors
    val press = remember { MutableInteractionSource() }
    // The turn springs back; the colours fade back evenly.
    val turn by snapPressed(press, Clauderc.motion.easeSpring)
    val fill by snapPressed(press, LinearEasing)
    val symbol = @Composable { size: Dp, tint: Color -> Icon(icon.styled, contentDescription = null, tint = tint, modifier = Modifier.size(size)) }
    Box(
        modifier
            .clickable(press, null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = text }
            .testTag("swipeAction"),
        contentAlignment = Alignment.Center,
    ) {
        when (Clauderc.style) {
            UiStyle.Clear -> Box(Modifier.graphicsLayer { turned(turn, 0.82f, -24f) }) { symbol(24.dp, c.danger) }
            UiStyle.Grid -> Box(
                Modifier.size(40.dp).background(c.danger.copy(alpha = fill)).border(1.5.dp, c.danger),
                contentAlignment = Alignment.Center,
            ) { symbol(20.dp, lerp(c.danger, Color.White, fill)) }
            UiStyle.Sticker -> Box(
                Modifier.graphicsLayer { turned(turn, 0.9f, -10f) }.size(40.dp).sticker(lerp(c.card, c.danger, fill), c.ink, 20.dp),
                contentAlignment = Alignment.Center,
            ) { symbol(20.dp, lerp(c.danger, Color.White, fill)) }
        }
    }
}

/** Scales the layer toward [scale] and turns it toward [turn]° as [p] goes from 0 to 1. */
private fun GraphicsLayerScope.turned(p: Float, scale: Float, turn: Float) {
    scaleX = 1f + (scale - 1f) * p
    scaleY = scaleX
    rotationZ = turn * p
}

private const val WorktreeMark = "worktree"

/** .wt: the alt_route symbol, 14dp in the faint text colour, 2dp before the worktree's branch. */
private val worktreeMark = mapOf(
    WorktreeMark to InlineTextContent(Placeholder(16.sp, 14.sp, PlaceholderVerticalAlign.TextCenter)) {
        Icon(Symbols.AltRoute, contentDescription = "worktree", tint = Clauderc.colors.fg3, modifier = Modifier.padding(end = 2.dp).size(14.dp))
    },
)

@Composable
private fun RenameDialog(group: SessionGroup, machineName: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(group.title) }
    ClaudercDialog(
        onDismiss = onDismiss,
        title = "档位名",
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp2)) {
                FieldLabel(if (group.profile.kind == clauderc.app.protocol.ProfileKind.Default) "不传 --settings 的档位" else group.profile.id, mono = group.profile.kind != clauderc.app.protocol.ProfileKind.Default)
                UnderlineField(text, { text = it }, placeholder = group.profile.id)
            }
            Text(
                "只保存在这台手机上，只对$machineName 生效。",
                fontSize = Clauderc.fontSizes.label,
                color = Clauderc.colors.fg2,
            )
        },
        buttons = {
            TextAction("恢复默认", onClick = { onSave(null) }, tone = TextTone.Quiet)
            Spacer(Modifier.weight(1f))
            TextAction("取消", onClick = onDismiss)
            TextAction("保存", onClick = { onSave(text) })
        },
    )
}

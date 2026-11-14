package clauderc.app.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import clauderc.app.machines.NavClearance
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionState
import clauderc.app.ui.TimeText
import clauderc.app.ui.baseName
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.ClaudercDialog
import clauderc.app.ui.components.ClaudercSheet
import clauderc.app.ui.components.FieldLabel
import clauderc.app.ui.components.Glyph
import clauderc.app.ui.components.Hero
import clauderc.app.ui.components.MenuItem
import clauderc.app.ui.components.SheetHead
import clauderc.app.ui.components.SnackHost
import clauderc.app.ui.components.SnackState
import clauderc.app.ui.components.StatusGlyph
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.components.TextAction
import clauderc.app.ui.components.TextTone
import clauderc.app.ui.components.UnderlineField
import clauderc.app.ui.stateText
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.pressShrink

fun glyphOf(state: SessionState) = when (state) {
    SessionState.Pending -> Glyph.Need
    SessionState.Working -> Glyph.Work
    SessionState.Idle -> Glyph.Idle
    SessionState.Stopped -> Glyph.Stop
}

private sealed interface Overlay {
    data class Menu(val session: Session) : Overlay
    data class ConfirmStop(val session: Session) : Overlay
    data class ConfirmRemove(val session: Session) : Overlay
    data class Rename(val group: SessionGroup) : Overlay
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    state: SessionsUiState,
    onRefresh: () -> Unit,
    onOpenChat: (Session) -> Unit,
    onCreate: (SessionGroup) -> Unit,
    onOpenGit: (Session) -> Unit,
    onRename: (profileId: String, name: String?) -> Unit,
    onStop: (Session) -> Unit,
    onRemove: (Session) -> Unit,
    onToggle: (SessionGroup) -> Unit = {},
    snack: SnackState = remember { SnackState() },
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val machine = state.machine
    val online = machine?.online == true
    val pull = rememberPullToRefreshState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppBar()
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
                LazyColumn(Modifier.fillMaxSize().testTag("sessionList"), contentPadding = PaddingValues(bottom = NavClearance)) {
                    item {
                        Hero(machine?.name ?: "会话", bottom = sp.sp2) {
                            if (machine?.claudeVersion?.isNotEmpty() == true) Text("claude ${machine.claudeVersion}")
                        }
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
                                GroupHeader(group, enabled = online, onRename = { overlay = Overlay.Rename(group) }, onCreate = { onCreate(group) }, onToggle = { onToggle(group) })
                            }
                            if (group.sessions.isEmpty()) {
                                item(key = "e:" + group.profile.id) {
                                    Text(
                                        "没有会话",
                                        modifier = Modifier.padding(start = sp.page, end = sp.page, top = sp.sp2, bottom = sp.sp3),
                                        fontSize = Clauderc.fontSizes.label,
                                        color = c.fg3,
                                    )
                                }
                            }
                            // One item per group, so folding can shrink the rows together like the mockup.
                            if (group.sessions.isNotEmpty()) {
                                item(key = "g:" + group.profile.id) {
                                    Fold(visible = !group.collapsed) {
                                        Column {
                                            group.sessions.forEach { s ->
                                                key(s.id) { SessionRow(s, enabled = online, onOpen = { onOpenChat(s) }, onMenu = { overlay = Overlay.Menu(s) }) }
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
        is Overlay.Menu -> SessionMenu(
            session = o.session,
            onDismiss = { overlay = null },
            onOpen = { overlay = null; onOpenChat(o.session) },
            onGit = { overlay = null; onOpenGit(o.session) },
            onStop = { overlay = Overlay.ConfirmStop(o.session) },
            onRemove = { overlay = Overlay.ConfirmRemove(o.session) },
        )
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
                TextAction("移出", onClick = { overlay = null; onRemove(o.session) }, tone = TextTone.Danger)
            },
        )
        is Overlay.Rename -> RenameDialog(
            group = o.group,
            machineName = machine?.name.orEmpty(),
            onDismiss = { overlay = null },
            onSave = { name -> overlay = null; onRename(o.group.profile.id, name) },
        )
        null -> Unit
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
private fun GroupHeader(group: SessionGroup, enabled: Boolean, onRename: () -> Unit, onCreate: () -> Unit, onToggle: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Row(
        Modifier.fillMaxWidth().padding(start = sp.page, end = sp.sp2, top = sp.sp6, bottom = sp.sp1),
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
            Text(
                group.title,
                fontSize = if (group.titleIsHost) Clauderc.fontSizes.mono else Clauderc.fontSizes.label,
                fontFamily = if (group.titleIsHost) MonoFamily else null,
                fontWeight = FontWeight.Medium,
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
        if (group.sessions.isNotEmpty()) {
            val turn by animateFloatAsState(if (group.collapsed) 0f else 180f, Motion.turn(), label = "fold")
            SymbolButton(
                Symbols.ExpandMore,
                if (group.collapsed) "展开${group.title}档位" else "折叠${group.title}档位",
                onClick = onToggle,
                small = true,
                modifier = Modifier.rotate(turn),
            )
        }
        if (group.canCreate) {
            SymbolButton(Symbols.Add, "在${group.title}档位新建会话", onClick = onCreate, small = true, enabled = enabled)
        } else {
            Spacer(Modifier.size(40.dp))
        }
    }
}

@Composable
private fun SessionRow(s: Session, enabled: Boolean, onOpen: () -> Unit, onMenu: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Row(Modifier.fillMaxWidth().padding(end = sp.sp2).semantics { isTraversalGroup = true }, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
                .padding(start = sp.page, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(sp.sp4),
        ) {
            StatusGlyph(glyphOf(s.state), Modifier.padding(top = 6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(sp.sp3), verticalAlignment = Alignment.Bottom) {
                    Text(
                        display(s),
                        modifier = Modifier.weight(1f),
                        fontSize = Clauderc.fontSizes.body,
                        color = if (s.state == SessionState.Stopped) c.fg2 else c.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(TimeText.relative(s.lastActiveAt), fontSize = Clauderc.fontSizes.caption, color = c.fg3, maxLines = 1)
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm)) { append(baseName(s.cwd)) }
                        append(" · ")
                        if (s.state == SessionState.Pending) withStyle(SpanStyle(color = c.accent)) { append(stateText(s.state)) } else append(stateText(s.state))
                    },
                    fontSize = Clauderc.fontSizes.label,
                    color = c.fg2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SymbolButton(Symbols.MoreVert, "会话操作", onClick = onMenu, small = true, enabled = enabled)
    }
}

@Composable
private fun SessionMenu(
    session: Session,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onGit: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
) {
    val stopped = session.state == SessionState.Stopped
    ClaudercSheet(onDismiss = onDismiss) {
        SheetHead(display(session)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm)) { append(baseName(session.cwd)) }
                    append(" · ${stateText(session.state)}")
                },
                fontSize = Clauderc.fontSizes.label,
                color = Clauderc.colors.fg2,
            )
        }
        MenuItem("打开", onClick = onOpen, icon = Symbols.Description, supporting = if (stopped) "发消息时自动唤醒" else null)
        MenuItem("git", onClick = onGit, icon = Symbols.AccountTree)
        // A running session cannot be removed (session-lifecycle spec), so only one of these shows.
        if (stopped) {
            MenuItem("移出列表", onClick = onRemove, icon = Symbols.Delete, danger = true)
        } else {
            MenuItem("停止", onClick = onStop, icon = Symbols.PowerSettingsNew, danger = true)
        }
    }
}

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

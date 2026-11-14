package clauderc.app.git

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.machines.NavClearance
import clauderc.app.protocol.Commit
import clauderc.app.protocol.FileChange
import clauderc.app.protocol.FileStatus
import clauderc.app.protocol.RefKind
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
import clauderc.app.ui.components.SectionHeading
import clauderc.app.ui.components.SheetHead
import clauderc.app.ui.components.SheetSection
import clauderc.app.ui.components.SnackHost
import clauderc.app.ui.components.SnackState
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.components.StatusGlyph
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.components.TextAction
import clauderc.app.ui.components.TextTone
import clauderc.app.ui.components.UnderlineField
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Symbols

class GitActions(
    val onRefresh: () -> Unit = {},
    val onPickDir: (String) -> Unit = {},
    val onTab: (GitTab) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onOpenCommit: (String) -> Unit = {},
    val onOpenDiff: (path: String, staged: Boolean) -> Unit = { _, _ -> },
    val onLoadBranches: () -> Unit = {},
    val onRequest: (GitOp) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onDismissFailure: () -> Unit = {},
    val onMessage: (String) -> Unit = {},
)

private enum class GitSheet { Repo, Branches }

@Composable
fun GitScreen(state: GitUiState, actions: GitActions, snack: SnackState = remember { SnackState() }) {
    val c = Clauderc.colors
    var sheet by remember { mutableStateOf<GitSheet?>(null) }
    val dir = state.dir

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            AppBar(
                topLevel = true,
                title = {
                    BarTitle(dir?.let(::baseName) ?: "git") {
                        BarSubButton(dir?.let(::shortPath) ?: "选择目录", onClick = { sheet = GitSheet.Repo }, mono = true)
                    }
                },
                actions = { SymbolButton(Symbols.Refresh, "刷新", onClick = actions.onRefresh, enabled = dir != null) },
            )
            when {
                dir == null -> Empty("还没有目录", null, "选择目录") { sheet = GitSheet.Repo }
                state.notRepo -> Empty("不是 git 仓库", shortPath(dir), "换一个目录") { sheet = GitSheet.Repo }
                else -> {
                    Tabs(state, actions.onTab)
                    BranchBar(state) { actions.onLoadBranches(); sheet = GitSheet.Branches }
                    if (state.tab == GitTab.Log) Timeline(state, actions) else StatusPage(state, actions) { actions.onLoadBranches(); sheet = GitSheet.Branches }
                }
            }
        }
        SnackHost(snack, Modifier.align(Alignment.BottomCenter), bottom = 88.dp)
    }

    when (sheet) {
        GitSheet.Repo -> ClaudercSheet(onDismiss = { sheet = null }) {
            SheetHead("最近的目录")
            if (state.dirs.isEmpty()) {
                Text("目标机上还没有最近用过的目录", Modifier.padding(horizontal = Clauderc.spacing.page), color = c.fg3, fontSize = Clauderc.fontSizes.label)
            }
            state.dirs.forEach { d ->
                MenuItem(baseName(d), onClick = { sheet = null; actions.onPickDir(d) }, icon = Symbols.Folder, supporting = shortPath(d), supportingMono = true, checked = d == dir)
            }
        }
        GitSheet.Branches -> ClaudercSheet(onDismiss = { sheet = null }) {
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
        null -> Unit
    }

    when (val confirm = state.confirm) {
        is GitConfirm.Busy -> ClaudercDialog(
            onDismiss = actions.onDismissConfirm,
            title = "claude 正在这个目录工作",
            text = "${confirm.sessionName?.let { "「$it」" } ?: "有会话"}正在改 ${dir?.let(::baseName).orEmpty()} 里的代码，现在${confirm.op.verb}可能带上它写到一半的改动。",
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
private fun Tabs(state: GitUiState, onTab: (GitTab) -> Unit) {
    val c = Clauderc.colors
    val count = state.status?.let { it.staged.size + it.unstaged.size }
    Column {
        Row(Modifier.padding(horizontal = Clauderc.spacing.page), horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp8)) {
            listOf(GitTab.Log to "时间线", GitTab.Status to "改动").forEach { (tab, label) ->
                val on = state.tab == tab
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .clickable(role = Role.Tab) { onTab(tab) }
                        .semantics { selected = on },
                ) {
                    Text(
                        buildAnnotatedString {
                            append(label)
                            if (tab == GitTab.Status && count != null && count > 0) append("($count)")
                        },
                        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                        fontSize = Clauderc.fontSizes.label,
                        fontWeight = FontWeight.Medium,
                        color = if (on) c.fg else c.fg3,
                    )
                    Box(Modifier.height(2.dp).fillMaxWidth().background(if (on) c.fg else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

@Composable
private fun BranchBar(state: GitUiState, onBranches: () -> Unit) {
    val c = Clauderc.colors
    val st = state.status
    Row(
        Modifier.fillMaxWidth().padding(start = Clauderc.spacing.page, end = Clauderc.spacing.page, top = Clauderc.spacing.sp4, bottom = Clauderc.spacing.sp3),
        horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.clickable(role = Role.Button, onClickLabel = "切换分支", onClick = onBranches), verticalAlignment = Alignment.CenterVertically) {
            Text(st?.branch?.ifEmpty { "分离的 HEAD" } ?: "…", fontFamily = MonoFamily, fontSize = 15.sp)
            Icon(Symbols.ExpandMore, contentDescription = null, tint = c.fg3, modifier = Modifier.size(18.dp))
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
private fun Timeline(state: GitUiState, actions: GitActions) {
    val c = Clauderc.colors
    val list = rememberLazyListState()
    val geometry = remember(state.commits, state.status?.branch) { timelineGeometry(state.commits, state.status?.branch) }
    val textStart = remember(state.commits) { graphWidth(state.commits) }
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && total > 0 && last >= total - 2) actions.onLoadMore() }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("timeline"), state = list, contentPadding = PaddingValues(bottom = NavClearance)) {
        itemsIndexed(state.commits, key = { _, it -> it.hash }) { i, commit ->
            geometry.getOrNull(i)?.let { CommitRow(commit, it, textStart, state.status?.branch) { actions.onOpenCommit(commit.hash) } }
        }
        if (state.hasMore || state.loadingMore) {
            item(key = "more") {
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
        if (state.commits.isEmpty() && state.loading) {
            item { Box(Modifier.fillMaxWidth().padding(Clauderc.spacing.sp8), contentAlignment = Alignment.Center) { Spinner(color = c.fg3) } }
        }
    }
}

@Composable
private fun CommitRow(commit: Commit, g: RowGeometry, textStart: Float, head: String?, onClick: () -> Unit) {
    val c = Clauderc.colors
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth().height(64.dp).clickable(role = Role.Button, onClick = onClick)) {
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
private fun StatusPage(state: GitUiState, actions: GitActions, onBranches: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val st = state.status
    Column(Modifier.fillMaxSize()) {
        state.busySession?.let { name ->
            Row(Modifier.padding(start = sp.page, end = sp.page, bottom = sp.sp3), horizontalArrangement = Arrangement.spacedBy(sp.sp3), verticalAlignment = Alignment.CenterVertically) {
                StatusGlyph(Glyph.Work)
                Text("「$name」正在这个目录工作", fontSize = Clauderc.fontSizes.caption, color = c.fg2)
            }
        }
        Row(Modifier.padding(start = sp.page, end = sp.page, bottom = sp.sp4), horizontalArrangement = Arrangement.spacedBy(sp.sp2)) {
            PillButton("拉取", onClick = { actions.onRequest(GitOp.Pull) }, style = PillStyle.Tonal, icon = Symbols.Download, height = 40.dp, enabled = !state.running, modifier = Modifier.weight(1f))
            PillButton("推送", onClick = { actions.onRequest(GitOp.Push()) }, style = PillStyle.Tonal, icon = Symbols.Upload, height = 40.dp, enabled = !state.running, modifier = Modifier.weight(1f))
            PillButton("切换", onClick = onBranches, style = PillStyle.Tonal, icon = Symbols.AltRoute, height = 40.dp, enabled = !state.running, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f).testTag("changes")) {
            item { SectionHeading("已暂存") }
            if (st?.staged.isNullOrEmpty()) item { Hint("没有已暂存的文件") }
            st?.staged.orEmpty().forEach { f ->
                item(key = "s:" + f.path) { FileRow(f, "取消暂存", { actions.onOpenDiff(f.path, true) }) { actions.onRequest(GitOp.Unstage(f.path)) } }
            }
            item { SectionHeading("未暂存") }
            if (st?.unstaged.isNullOrEmpty()) item { Hint("工作目录是干净的") }
            st?.unstaged.orEmpty().forEach { f ->
                item(key = "u:" + f.path) { FileRow(f, "暂存", { actions.onOpenDiff(f.path, false) }) { actions.onRequest(GitOp.Stage(f.path)) } }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.bg)
                .padding(start = sp.page, end = sp.page, top = sp.sp3, bottom = NavClearance),
            verticalArrangement = Arrangement.spacedBy(sp.sp3),
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            UnderlineField(state.message, actions.onMessage, placeholder = "提交说明", singleLine = false, maxLines = 4)
            PillButton(
                if (state.running) "正在处理" else "提交",
                onClick = { actions.onRequest(GitOp.Commit(state.message)) },
                enabled = state.canCommit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = Clauderc.spacing.page, vertical = Clauderc.spacing.sp2), fontSize = Clauderc.fontSizes.label, color = Clauderc.colors.fg3)
}

fun changeLetter(change: FileChange) = when (change) {
    FileChange.Added -> "A"
    FileChange.Modified -> "M"
    FileChange.Deleted -> "D"
    FileChange.Renamed -> "R"
    FileChange.Untracked -> "?"
}

@Composable
private fun FileRow(f: FileStatus, action: String, onOpen: () -> Unit, onAction: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Row(Modifier.fillMaxWidth().padding(end = sp.sp2).semantics { isTraversalGroup = true }, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen).padding(start = sp.page, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(sp.sp4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(changeLetter(f.change), Modifier.width(14.dp), fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, color = c.fg3, textAlign = TextAlign.Center)
            FilePath(f.path, Modifier.weight(1f))
            if (f.change == FileChange.Untracked) Text("未跟踪", fontSize = Clauderc.fontSizes.caption, color = c.fg3, modifier = Modifier.padding(end = sp.sp4))
        }
        TextAction(action, onClick = onAction, tone = TextTone.Quiet)
    }
}

/** .fpath: file name above its directory. */
@Composable
fun FilePath(path: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    val c = Clauderc.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(baseName(path), fontSize = if (mono) Clauderc.fontSizes.mono else 15.sp, fontFamily = if (mono) MonoFamily else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(path.substringBeforeLast('/', "./"), fontFamily = MonoFamily, fontSize = 11.sp, color = c.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


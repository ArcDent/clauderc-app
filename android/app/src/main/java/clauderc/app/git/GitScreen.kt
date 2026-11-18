package clauderc.app.git

import clauderc.app.ui.components.LoadingArt
import clauderc.app.ui.components.LoadingCover
import clauderc.app.ui.components.StickerCorner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
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
import clauderc.app.ui.components.StickerOnYellow
import clauderc.app.ui.components.groupItem
import clauderc.app.ui.components.sticker
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.pressShrink
import kotlin.math.roundToInt

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
                // The first read of a directory draws a timeline over the page until the history arrives.
                else -> LoadingCover(state.loading && state.status == null && state.commits.isEmpty(), LoadingArt.Trace, Modifier.weight(1f), label = "正在读取提交") {
                    Column(Modifier.fillMaxSize()) {
                        Tabs(state, actions.onTab)
                        BranchBar(state) { actions.onLoadBranches(); sheet = GitSheet.Branches }
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
                            if (tab == GitTab.Log) Timeline(state, actions) else StatusPage(state, actions) { actions.onLoadBranches(); sheet = GitSheet.Branches }
                        }
                    }
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
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    // 清玻 underlines the picked tab; 格栅 and 贴纸 split the width evenly and fill the picked one.
    val filled = ui != UiStyle.Clear
    val count = state.status?.let { it.staged.size + it.unstaged.size }
    // Left edge and width of each tab, for the one indicator that slides between them.
    val spans = remember { mutableStateMapOf<GitTab, Pair<Int, Int>>() }
    val track = when (ui) {
        UiStyle.Sticker -> Modifier.padding(start = sp.sp4, end = sp.sp4, top = sp.sp1).sticker(c.panel, c.ink, 999.dp).padding(4.dp)
        UiStyle.Grid -> Modifier.padding(horizontal = sp.sp4)
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
                        UiStyle.Sticker -> Box(at.fillMaxHeight().sticker(c.hue2, c.ink, 999.dp, drop = 0.dp).testTag("tabUnderline"))
                        UiStyle.Grid -> Box(at.fillMaxHeight().padding(vertical = 6.dp).clip(RoundedCornerShape(2.dp)).background(c.fg).testTag("tabUnderline"))
                        UiStyle.Clear -> Box(at.height(2.dp).background(c.fg).testTag("tabUnderline"))
                    }
                }
            }
            // The fill sits under the labels; the underline is drawn over their bottom edge.
            if (filled) Box(Modifier.matchParentSize()) { indicator(Modifier) }
            Row(if (filled) Modifier.fillMaxWidth() else Modifier, horizontalArrangement = Arrangement.spacedBy(if (ui == UiStyle.Sticker) 4.dp else if (filled) 0.dp else sp.sp8)) {
                listOf(GitTab.Log to "时间线", GitTab.Status to "改动").forEach { (tab, label) ->
                    val on = state.tab == tab
                    Column(
                        Modifier
                            .then(if (filled) Modifier.weight(1f).clip(if (ui == UiStyle.Grid) RoundedCornerShape(2.dp) else CircleShape) else Modifier.width(IntrinsicSize.Max))
                            .onPlaced { spans[tab] = it.positionInParent().x.roundToInt() to it.size.width }
                            .clickable(role = Role.Tab) { onTab(tab) }
                            .semantics { selected = on },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            buildAnnotatedString {
                                append(label)
                                if (tab == GitTab.Status && count != null && count > 0) append("($count)")
                            },
                            modifier = Modifier.padding(top = if (ui == UiStyle.Sticker) 9.dp else 12.dp, bottom = if (ui == UiStyle.Sticker) 9.dp else if (filled) 14.dp else 12.dp),
                            fontSize = if (ui == UiStyle.Grid) 13.sp else Clauderc.fontSizes.label,
                            fontFamily = if (ui == UiStyle.Grid) MonoFamily else null,
                            fontWeight = if (ui == UiStyle.Sticker) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                on && ui == UiStyle.Sticker -> StickerOnYellow
                                on && ui == UiStyle.Grid -> c.bg
                                on -> c.fg
                                ui == UiStyle.Sticker -> c.fg2
                                else -> c.fg3
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
            UiStyle.Grid -> Box(Modifier.padding(horizontal = sp.sp4).fillMaxWidth().height(1.5.dp).background(c.fg))
            UiStyle.Sticker -> Unit
        }
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
        val press = remember { MutableInteractionSource() }
        Row(
            Modifier.pressShrink(press).clickable(press, LocalIndication.current, role = Role.Button, onClickLabel = "切换分支", onClick = onBranches),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            // One card for the whole graph in the card style; dividers would cut its lanes.
            Box(Modifier.groupItem(first = i == 0, last = i == state.commits.lastIndex, divider = false)) {
                geometry.getOrNull(i)?.let { CommitRow(commit, it, textStart, state.status?.branch) { actions.onOpenCommit(commit.hash) } }
            }
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
        val card = Clauderc.look.cards
        Row(
            Modifier.padding(start = if (card) sp.sp4 else sp.page, end = if (card) sp.sp4 else sp.page, bottom = if (card) sp.sp3 else sp.sp4),
            horizontalArrangement = Arrangement.spacedBy(sp.sp2),
        ) {
            PillButton("拉取", onClick = { actions.onRequest(GitOp.Pull) }, style = PillStyle.Tonal, icon = Symbols.Download, height = 40.dp, enabled = !state.running, modifier = Modifier.weight(1f))
            PillButton("推送", onClick = { actions.onRequest(GitOp.Push()) }, style = PillStyle.Tonal, icon = Symbols.Upload, height = 40.dp, enabled = !state.running, modifier = Modifier.weight(1f))
            PillButton("切换", onClick = onBranches, style = PillStyle.Tonal, icon = Symbols.AltRoute, height = 40.dp, enabled = !state.running, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.weight(1f).testTag("changes")) {
            item { SectionHeading("已暂存") }
            if (st?.staged.isNullOrEmpty()) item { Hint("没有已暂存的文件", Modifier.groupItem(first = true, last = true)) }
            val staged = st?.staged.orEmpty()
            staged.forEachIndexed { i, f ->
                item(key = "s:" + f.path) {
                    FileRow(f, "取消暂存", { actions.onOpenDiff(f.path, true) }, Modifier.groupItem(i == 0, i == staged.lastIndex)) { actions.onRequest(GitOp.Unstage(f.path)) }
                }
            }
            item { SectionHeading("未暂存") }
            if (st?.unstaged.isNullOrEmpty()) item { Hint("工作目录是干净的", Modifier.groupItem(first = true, last = true)) }
            val unstaged = st?.unstaged.orEmpty()
            unstaged.forEachIndexed { i, f ->
                item(key = "u:" + f.path) {
                    FileRow(f, "暂存", { actions.onOpenDiff(f.path, false) }, Modifier.groupItem(i == 0, i == unstaged.lastIndex)) { actions.onRequest(GitOp.Stage(f.path)) }
                }
            }
        }
        // The card style lifts the commit box onto its own card above the nav bar.
        val box = if (card) {
            // 贴纸 floats the box over its paper as an ink-outlined sticker (.commit-box).
            Modifier
                .fillMaxWidth()
                .padding(start = sp.sp4, end = sp.sp4, top = sp.sp2, bottom = 88.dp)
                .sticker(c.float, c.ink, StickerCorner)
                .padding(start = sp.sp4, end = sp.sp4, top = sp.sp3, bottom = sp.sp4)
        } else {
            Modifier
                .fillMaxWidth()
                .background(c.bg)
                .padding(start = sp.page, end = sp.page, top = sp.sp3, bottom = NavClearance)
        }
        Column(box, verticalArrangement = Arrangement.spacedBy(sp.sp3)) {
            if (!card) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
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
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(horizontal = Clauderc.look.rowStart, vertical = if (Clauderc.look.cards) Clauderc.spacing.sp4 else Clauderc.spacing.sp2),
        fontSize = Clauderc.fontSizes.label,
        color = Clauderc.colors.fg3,
    )
}

fun changeLetter(change: FileChange) = when (change) {
    FileChange.Added -> "A"
    FileChange.Modified -> "M"
    FileChange.Deleted -> "D"
    FileChange.Renamed -> "R"
    FileChange.Untracked -> "?"
}

@Composable
private fun FileRow(f: FileStatus, action: String, onOpen: () -> Unit, modifier: Modifier = Modifier, onAction: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Row(modifier.fillMaxWidth().padding(end = sp.sp2).semantics { isTraversalGroup = true }, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen).padding(start = Clauderc.look.rowStart, top = 10.dp, bottom = 10.dp),
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


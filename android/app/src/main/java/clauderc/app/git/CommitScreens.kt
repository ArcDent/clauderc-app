package clauderc.app.git

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.data.UiStyle
import clauderc.app.net.ConnectionState
import clauderc.app.net.Relay
import clauderc.app.net.RemoteException
import clauderc.app.net.call
import clauderc.app.protocol.CommitFile
import clauderc.app.protocol.ErrorCode
import clauderc.app.protocol.GitDiffRequest
import clauderc.app.protocol.GitDiffResponse
import clauderc.app.protocol.GitLimits
import clauderc.app.protocol.GitShowRequest
import clauderc.app.protocol.GitShowResponse
import clauderc.app.protocol.Method
import clauderc.app.ui.baseName
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.BarTitle
import clauderc.app.ui.components.CardCorner
import clauderc.app.ui.components.GroupBlock
import clauderc.app.ui.components.LocalMorphClose
import clauderc.app.ui.components.MorphOverlay
import clauderc.app.ui.components.PillButton
import clauderc.app.ui.components.PillStyle
import clauderc.app.ui.components.SectionHeading
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.components.captureAnchor
import clauderc.app.ui.components.groupItem
import clauderc.app.ui.components.inkSurface
import clauderc.app.ui.components.plateBox
import clauderc.app.ui.components.rememberAnchorBox
import clauderc.app.ui.components.tileSurface
import androidx.compose.ui.text.style.TextOverflow
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercColors
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.page
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads a value once; holds an error message when the request fails. */
data class Loadable<T>(val value: T? = null, val error: String? = null)

private fun isTransient(e: RemoteException) =
    e.code == ErrorCode.MACHINE_OFFLINE || e.code == ErrorCode.MACHINE_BUSY || e.code == ErrorCode.TIMEOUT

class CommitViewModel(machineId: String, dir: String, hash: String, relay: Relay) : ViewModel() {
    private val _state = MutableStateFlow(Loadable<GitShowResponse>())
    val state: StateFlow<Loadable<GitShowResponse>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var terminal = false
            relay.stateOf(machineId).collect { conn ->
                if (conn != ConnectionState.Connected || _state.value.value != null || terminal) return@collect
                _state.value = Loadable()
                try {
                    _state.value = Loadable(relay.call(Method.GIT_SHOW, machineId, GitShowRequest(dir, hash), GitShowRequest.serializer(), GitShowResponse.serializer()))
                } catch (e: RemoteException) {
                    if (!isTransient(e)) terminal = true
                    _state.value = Loadable(error = e.message ?: "读不到这个提交")
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    _state.value = Loadable(error = e.message ?: "读不到这个提交")
                }
            }
        }
    }
}

class DiffViewModel(machineId: String, request: GitDiffRequest, relay: Relay) : ViewModel() {
    private val _state = MutableStateFlow(Loadable<GitDiffResponse>())
    val state: StateFlow<Loadable<GitDiffResponse>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            var terminal = false
            relay.stateOf(machineId).collect { conn ->
                if (conn != ConnectionState.Connected || _state.value.value != null || terminal) return@collect
                _state.value = Loadable()
                try {
                    _state.value = Loadable(relay.call(Method.GIT_DIFF, machineId, request, GitDiffRequest.serializer(), GitDiffResponse.serializer()))
                } catch (e: RemoteException) {
                    if (!isTransient(e)) terminal = true
                    _state.value = Loadable(error = e.message ?: "读不到差异")
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    _state.value = Loadable(error = e.message ?: "读不到差异")
                }
            }
        }
    }
}

private val commitTime = DateTimeFormatter.ofPattern("M 月 d 日 HH:mm")

/**
 * 浮墨's morphing floating commit detail panel (demo.html `A.openCommit` for Ink):
 * blooms from the tapped timeline row, supports in-panel parent-commit traversal with a back arrow,
 * and opens a nested [DiffMorphPanel] when any changed file row is tapped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommitMorphPanel(
    dir: String,
    hash: String,
    canGoBack: Boolean,
    state: Loadable<GitShowResponse>,
    anchor: Rect?,
    onBackParent: () -> Unit,
    onClose: () -> Unit,
    onOpenParent: (String) -> Unit,
    onOpenFileAt: (CommitFile, Rect?) -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    MorphOverlay(onDismiss = onClose, anchor = anchor, fromTop = false, tall = true, smoothResize = false, tag = "inkCommitMorph") {
        val close = LocalMorphClose.current
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawLine(c.edge, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx()) }
                    .padding(start = if (canGoBack) 8.dp else 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canGoBack) {
                    SymbolButton(Symbols.ArrowBack, "上一条提交", onClick = onBackParent, small = true)
                }
                Text(
                    "${hash.take(7)} · ${baseName(dir)}",
                    modifier = Modifier.weight(1f),
                    fontFamily = MonoFamily,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SymbolButton(Symbols.Close, "关闭", onClick = { close?.invoke(null) ?: onClose() }, small = true)
            }
            AnimatedContent(
                targetState = state,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                contentKey = { st ->
                    when {
                        st.error != null -> "err:${st.error}"
                        st.value == null -> "loading"
                        else -> "show:${st.value.hash}"
                    }
                },
                transitionSpec = {
                    (fadeIn(tween(240, delayMillis = 50, easing = Motion.inkEase)) togetherWith fadeOut(tween(110)))
                        .using(SizeTransform(clip = true) { _, _ -> tween(Motion.INK_MORPH_RESIZE_MS, easing = Motion.inkEase) })
                },
                label = "inkCommitBody",
            ) { current ->
                val show = current.value
                when {
                    current.error != null -> Text(current.error, Modifier.padding(20.dp), color = c.danger, fontSize = Clauderc.fontSizes.label)
                    show == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner(color = c.fg3) }
                    else -> LazyColumn(
                        Modifier.fillMaxWidth().testTag("inkCommitList"),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        item(key = "subj") {
                            val subject = show.message.substringBefore('\n')
                            val body = show.message.substringAfter('\n', "").trim()
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(subject, fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 1.35.em, color = c.fg)
                                if (body.isNotEmpty()) Text(body, fontSize = Clauderc.fontSizes.label, color = c.fg2, lineHeight = 1.65.em)
                            }
                        }
                        item(key = "meta") {
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp), thickness = 1.dp, color = c.edge)
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Meta("作者", show.author)
                                Meta("时间", commitTime.format(show.time.atZone(ZoneId.systemDefault())))
                                if (show.parents.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(sp.sp4), verticalAlignment = Alignment.CenterVertically) {
                                        Text("父提交", Modifier.width(56.dp), fontSize = Clauderc.fontSizes.label, color = c.fg3)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            show.parents.forEach { ph ->
                                                Text(
                                                    ph.take(7),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(c.surface2)
                                                        .clickable(role = Role.Button) { onOpenParent(ph) }
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                        .testTag("commitParent:${ph.take(7)}"),
                                                    fontFamily = MonoFamily,
                                                    fontSize = Clauderc.fontSizes.monoSm,
                                                    color = c.fg,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "files-head") {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${show.files.size} 个改动文件", fontSize = 13.sp, color = c.fg2, fontWeight = FontWeight.Medium)
                                Counts(show.files.sumOf { it.added }, show.files.sumOf { it.deleted })
                            }
                        }
                        itemsIndexed(show.files, key = { _, it -> it.path }) { _, f ->
                            val rowAnchor = rememberAnchorBox()
                            CommitFileRow(f, Modifier.captureAnchor(rowAnchor)) { onOpenFileAt(f, rowAnchor.rect) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 浮墨's morphing floating diff panel (demo.html `A.openDiff` for Ink):
 * shares the exact background-free red/green bordered diff renderer with [DiffScreen] and adds an
 * optional pinned footer to stage / unstage working-tree files right from the panel.
 */
@Composable
fun DiffMorphPanel(
    path: String,
    subtitle: String,
    state: Loadable<GitDiffResponse>,
    anchor: Rect?,
    fromTop: Boolean = false,
    staged: Boolean? = null,
    busy: Boolean = false,
    onToggleStage: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val c = Clauderc.colors
    val diff = state.value
    val lines = remember(diff?.patch) { parsePatch(diff?.patch.orEmpty()) }
    MorphOverlay(onDismiss = onClose, anchor = anchor, fromTop = fromTop, tall = true, smoothResize = false, tag = "inkDiffMorph") {
        val close = LocalMorphClose.current
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind { drawLine(c.edge, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx()) }
                    .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(path.substringAfterLast('/'), fontFamily = MonoFamily, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = c.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, fontFamily = MonoFamily, fontSize = 11.5.sp, color = c.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (diff != null && !diff.binary) {
                    Counts(lines.count { it.kind == DiffKind.Add }, lines.count { it.kind == DiffKind.Del })
                }
                SymbolButton(Symbols.Close, "关闭", onClick = { close?.invoke(null) ?: onClose() }, small = true)
            }
            AnimatedContent(
                targetState = state,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                contentKey = { st ->
                    val d = st.value
                    when {
                        st.error != null -> "err:${st.error}"
                        d == null -> "loading"
                        d.binary -> "binary:${d.path}"
                        else -> "diff:${d.path}:${d.patch.orEmpty().length}"
                    }
                },
                transitionSpec = {
                    (fadeIn(tween(240, delayMillis = 50, easing = Motion.inkEase)) togetherWith fadeOut(tween(110)))
                        .using(SizeTransform(clip = true) { _, _ -> tween(Motion.INK_MORPH_RESIZE_MS, easing = Motion.inkEase) })
                },
                label = "inkDiffBody",
            ) { current ->
                val curLines = remember(current.value?.patch) { parsePatch(current.value?.patch.orEmpty()) }
                DiffBody(state = current, lines = curLines, framed = false)
            }
            if (onToggleStage != null && staged != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { drawLine(c.edge, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    PillButton(
                        if (staged) "取消暂存" else "暂存此文件",
                        onClick = onToggleStage,
                        style = PillStyle.Ink,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
fun CommitScreen(hash: String, state: Loadable<GitShowResponse>, onBack: () -> Unit, onOpenFile: (CommitFile) -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val card = Clauderc.look.cards
    val plate = Clauderc.style == UiStyle.Plate
    Column(Modifier.fillMaxSize().page()) {
        AppBar(navigation = { SymbolButton(Symbols.ArrowBack, "返回", onClick = onBack) }, title = { BarTitle(hash.take(7), mono = true) })
        val show = state.value
        when {
            state.error != null -> Text(state.error, Modifier.padding(sp.page), color = c.danger, fontSize = Clauderc.fontSizes.label)
            show == null -> Box(Modifier.fillMaxWidth().padding(sp.sp8), contentAlignment = Alignment.Center) { Spinner(color = c.fg3) }
            else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = sp.sp8)) {
                item {
                    val subject = show.message.substringBefore('\n')
                    val body = show.message.substringAfter('\n', "").trim()
                    GroupBlock(Modifier.padding(top = sp.sp2, bottom = if (card) sp.sp3 else sp.sp5), padding = sp.sp5) {
                        Column(if (card) Modifier else Modifier.padding(horizontal = if (plate) sp.sp4 else sp.page)) {
                            Text(
                                subject,
                                fontSize = if (card) Clauderc.fontSizes.title else Clauderc.fontSizes.headline,
                                fontWeight = if (card || plate) FontWeight.Bold else FontWeight.Light,
                                lineHeight = 1.35.em,
                                letterSpacing = (-0.01).em,
                            )
                            if (body.isNotEmpty()) Text(body, Modifier.padding(top = sp.sp3), fontSize = Clauderc.fontSizes.label, color = c.fg2, lineHeight = 1.75.em)
                        }
                    }
                }
                item {
                    GroupBlock(Modifier.padding(bottom = sp.sp2)) {
                        Column(
                            Modifier.padding(start = if (card) sp.sp5 else if (plate) sp.sp4 else sp.page, end = if (card) sp.sp5 else if (plate) sp.sp4 else sp.page, top = if (card) sp.sp4 else 0.dp, bottom = if (card) sp.sp4 else 0.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Meta("作者", show.author)
                            Meta("时间", commitTime.format(show.time.atZone(ZoneId.systemDefault())))
                            if (show.parents.isNotEmpty()) Meta("父提交", show.parents.joinToString(" ") { it.take(7) }, mono = true)
                        }
                    }
                }
                item {
                    SectionHeading("${show.files.size} 个文件") {
                        Counts(show.files.sumOf { it.added }, show.files.sumOf { it.deleted })
                    }
                }
                itemsIndexed(show.files, key = { _, it -> it.path }) { i, f ->
                    CommitFileRow(f, Modifier.groupItem(i == 0, i == show.files.lastIndex)) { onOpenFile(f) }
                }
            }
        }
    }
}

@Composable
private fun Meta(label: String, value: String, mono: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp4)) {
        Text(label, Modifier.width(56.dp), fontSize = Clauderc.fontSizes.label, color = Clauderc.colors.fg3)
        Text(value, fontSize = if (mono) Clauderc.fontSizes.monoSm else Clauderc.fontSizes.label, fontFamily = if (mono) MonoFamily else null)
    }
}

/** .num: green added count and red deleted count; zero sides are left out. */
@Composable
fun Counts(added: Int, deleted: Int) {
    val c = Clauderc.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (added > 0 || deleted == 0) Text("+%,d".format(added), fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, color = c.addFg)
        if (deleted > 0) Text("−%,d".format(deleted), fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, color = c.delFg)
    }
}

@Composable
private fun CommitFileRow(f: CommitFile, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = !f.binary, role = Role.Button, onClick = onClick)
            .padding(start = Clauderc.look.rowStart, end = if (Clauderc.look.cards) sp.sp4 else sp.sp4 + sp.sp2, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(sp.sp4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                f.binary -> "B"
                f.oldPath != null -> "R"
                else -> ""
            },
            Modifier.width(14.dp),
            fontFamily = MonoFamily,
            fontSize = Clauderc.fontSizes.monoSm,
            color = c.fg3,
            textAlign = TextAlign.Center,
        )
        FilePath(f.path, Modifier.weight(1f))
        if (f.binary) Text("二进制文件已更改", fontSize = Clauderc.fontSizes.caption, color = c.fg3) else Counts(f.added, f.deleted)
    }
}

/**
 * Background and text color of a diff line (demo.html .dline):
 * background is always transparent — deletions and additions are distinguished by bordered boxes + 3dp left rail + text colour.
 */
fun diffLineColors(kind: DiffKind, c: ClaudercColors): Pair<Color, Color> = when (kind) {
    DiffKind.Add -> Color.Transparent to c.addFg
    DiffKind.Del -> Color.Transparent to c.delFg
    DiffKind.Hunk -> Color.Transparent to c.fg3
    DiffKind.Context -> Color.Transparent to c.fg
}

/** Maximum expanded display characters per diff line before truncating with an omitted-count suffix. */
const val MAX_DIFF_LINE_CHARS = 800

/** Maximum horizontal width in pixels for the diff LazyColumn, safely below Compose Constraints' 15-bit bound (32766). */
const val MAX_DIFF_WIDTH_PX = 24_000

/** Maximum consecutive add/del lines per lazy diff group chunk so huge diffs stay virtualized and within height limits. */
const val MAX_DIFF_GROUP_CHUNK_LINES = 64

/** Expands tabs to 4 spaces and caps ultra-long single lines so minified/base64 diffs never stall or overflow layout. */
fun formatDiffLine(raw: String, maxChars: Int = MAX_DIFF_LINE_CHARS): String {
    if (raw.isEmpty()) return ""
    val limit = maxChars.coerceAtLeast(1)
    val out = StringBuilder(minOf(raw.length, limit))
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '\t') {
            if (out.length + 4 > limit) break
            out.append("    ")
            i++
        } else if (ch.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate()) {
            if (out.length + 2 > limit) break
            out.append(ch).append(raw[i + 1])
            i += 2
        } else {
            if (out.length + 1 > limit) break
            out.append(ch)
            i++
        }
    }
    val omitted = raw.length - i
    if (omitted <= 0) return out.toString()
    return "$out ${"…（已省略后 %,d 字符）".format(omitted)}"
}

/** Clamps the diff list's pixel width so Compose Constraints can never overflow on ultra-wide lines. */
fun clampDiffContentWidthPx(
    viewportWidthPx: Int,
    measuredLineWidthPx: Int,
    extraWidthPx: Int,
    maxWidthPx: Int = MAX_DIFF_WIDTH_PX,
): Int {
    val floor = viewportWidthPx.coerceAtLeast(0)
    val cap = maxOf(floor, maxWidthPx.coerceAtLeast(0))
    val desired = (measuredLineWidthPx.coerceAtLeast(0).toLong() + extraWidthPx.coerceAtLeast(0).toLong())
        .coerceAtMost(cap.toLong())
        .toInt()
    return maxOf(floor, desired)
}

/** Structured blocks for bordered, background-free diff rendering (demo.html K.diffHTML). */
sealed interface DiffBlock {
    data class Hunk(val line: DiffLine, val first: Boolean) : DiffBlock
    data class Context(val line: DiffLine) : DiffBlock
    data class Group(
        val kind: DiffKind,
        val lines: List<DiffLine>,
        val joinPrev: Boolean = false,
        val joinNext: Boolean = false,
        val continuesFromPrev: Boolean = false,
        val continuesToNext: Boolean = false,
    ) : DiffBlock
}

/**
 * Groups consecutive Del lines and consecutive Add lines, linking directly adjacent Del+Add seams,
 * and splits oversized consecutive groups into seamless [chunkSize]-line slices for LazyColumn virtualization.
 */
fun groupDiffLines(lines: List<DiffLine>, chunkSize: Int = MAX_DIFF_GROUP_CHUNK_LINES): List<DiffBlock> {
    val raw = mutableListOf<DiffBlock>()
    var i = 0
    while (i < lines.size) {
        val l = lines[i]
        when (l.kind) {
            DiffKind.Hunk -> {
                raw += DiffBlock.Hunk(l, first = raw.isEmpty())
                i++
            }
            DiffKind.Context -> {
                raw += DiffBlock.Context(l)
                i++
            }
            DiffKind.Del, DiffKind.Add -> {
                val kind = l.kind
                val batch = mutableListOf<DiffLine>()
                while (i < lines.size && lines[i].kind == kind) {
                    batch += lines[i]
                    i++
                }
                raw += DiffBlock.Group(kind, batch)
            }
        }
    }
    val step = chunkSize.coerceAtLeast(1)
    val out = ArrayList<DiffBlock>(raw.size)
    raw.forEachIndexed { idx, block ->
        if (block !is DiffBlock.Group) {
            out += block
            return@forEachIndexed
        }
        val prev = raw.getOrNull(idx - 1) as? DiffBlock.Group
        val next = raw.getOrNull(idx + 1) as? DiffBlock.Group
        val joinPrev = block.kind == DiffKind.Add && prev?.kind == DiffKind.Del
        val joinNext = block.kind == DiffKind.Del && next?.kind == DiffKind.Add
        if (block.lines.size <= step) {
            out += block.copy(joinPrev = joinPrev, joinNext = joinNext)
        } else {
            val chunks = block.lines.chunked(step)
            chunks.forEachIndexed { ci, slice ->
                val first = ci == 0
                val last = ci == chunks.lastIndex
                out += DiffBlock.Group(
                    kind = block.kind,
                    lines = slice,
                    joinPrev = if (first) joinPrev else false,
                    joinNext = if (last) joinNext else false,
                    continuesFromPrev = !first,
                    continuesToNext = !last,
                )
            }
        }
    }
    return out
}

private val LineNoWidth = 34.dp
private val GutterSignGap = 6.dp
private val SignWidth = 14.dp
private val SignCodeGap = 4.dp

@Composable
fun DiffScreen(path: String, subtitle: String, state: Loadable<GitDiffResponse>, onBack: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val diff = state.value
    val lines = remember(diff?.patch) { parsePatch(diff?.patch.orEmpty()) }
    Column(Modifier.fillMaxSize().page()) {
        AppBar(
            navigation = { SymbolButton(Symbols.ArrowBack, "返回", onClick = onBack) },
            title = {
                BarTitle(path.substringAfterLast('/'), mono = true) {
                    Text(subtitle, fontFamily = MonoFamily, fontSize = 11.sp, color = c.fg2)
                }
            },
            actions = {
                if (diff != null && !diff.binary) {
                    Box(Modifier.padding(end = sp.sp4)) { Counts(lines.count { it.kind == DiffKind.Add }, lines.count { it.kind == DiffKind.Del }) }
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            DiffBody(state = state, lines = lines, framed = true)
        }
    }
}

@Composable
private fun DiffBody(state: Loadable<GitDiffResponse>, lines: List<DiffLine>, framed: Boolean) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val card = Clauderc.look.cards
    val diff = state.value
    val formatted = remember(lines) { lines.map { it.copy(text = formatDiffLine(it.text)) } }
    val blocks = remember(formatted) { groupDiffLines(formatted) }
    var expanded by remember { mutableStateOf(false) }
    val changed = lines.count { it.kind != DiffKind.Hunk }
    when {
        state.error != null -> Text(state.error, Modifier.padding(sp.page), color = c.danger, fontSize = Clauderc.fontSizes.label)
        diff == null -> Box(Modifier.fillMaxWidth().padding(sp.sp8), contentAlignment = Alignment.Center) { Spinner(color = c.fg3) }
        diff.binary -> Text("二进制文件已更改，不显示内容", Modifier.padding(sp.page), fontSize = Clauderc.fontSizes.label, color = c.fg2)
        (diff.collapsed || changed > GitLimits.DIFF_COLLAPSE_LINES) && !expanded -> GroupBlock(Modifier.padding(top = if (card && framed) sp.sp4 else 0.dp), padding = sp.sp5) {
            Column(
                if (card && framed) Modifier else Modifier.padding(horizontal = sp.page, vertical = sp.sp6),
                verticalArrangement = Arrangement.spacedBy(sp.sp2),
            ) {
                Text("%,d 行差异，已折叠".format(changed), fontSize = Clauderc.fontSizes.title, fontWeight = if (card) FontWeight.SemiBold else FontWeight.Light)
                Text("超过 1000 行的差异默认不展开。", fontSize = Clauderc.fontSizes.label, color = c.fg2, lineHeight = 1.6.em)
                PillButton("展开", onClick = { expanded = true }, style = PillStyle.Tonal, modifier = Modifier.padding(top = sp.sp3))
            }
        }
        lines.isEmpty() -> Text("没有差异", Modifier.padding(sp.page), fontSize = Clauderc.fontSizes.label, color = c.fg3)
        else -> {
            val shown = remember { MutableTransitionState(!expanded) }.apply { targetState = true }
            val list = rememberLazyListState()
            val frame = if (!framed) Modifier else when (ui) {
                UiStyle.Ink -> Modifier.padding(start = sp.sp4, end = sp.sp4, top = sp.sp2, bottom = sp.sp4).inkSurface(c.card, c.edge, CardCorner)
                UiStyle.Tile -> Modifier.padding(start = sp.sp4, end = sp.sp4, top = sp.sp2, bottom = sp.sp4).tileSurface(c.card, CardCorner)
                UiStyle.Plate -> Modifier.padding(start = sp.sp4, end = sp.sp4, top = sp.sp2, bottom = sp.sp4).plateBox(c.bg, c.fg, 1.5.dp)
                UiStyle.Clear -> Modifier.drawBehind {
                    drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                }
            }
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val longestLine = remember(formatted) {
                formatted.maxByOrNull { it.text.length }?.text.orEmpty()
            }
            val longestCodePx = remember(longestLine, density) {
                if (longestLine.isEmpty()) 0
                else measurer.measure(
                    longestLine,
                    TextStyle(fontFamily = MonoFamily, fontSize = 11.5.sp),
                    softWrap = false,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = MAX_DIFF_WIDTH_PX),
                ).size.width
            }
            AnimatedVisibility(shown, enter = fadeIn(Motion.enter())) {
                BoxWithConstraints(frame.fillMaxWidth().then(if (framed) Modifier.fillMaxHeight() else Modifier)) {
                    val contentWidth = with(density) {
                        clampDiffContentWidthPx(
                            viewportWidthPx = maxWidth.roundToPx(),
                            measuredLineWidthPx = longestCodePx,
                            extraWidthPx = 136.dp.roundToPx(),
                        ).toDp()
                    }
                    Box(Modifier.fillMaxWidth().then(if (framed) Modifier.fillMaxHeight() else Modifier).horizontalScroll(rememberScrollState())) {
                        LazyColumn(
                            Modifier
                                .width(contentWidth)
                                .then(if (framed) Modifier.fillMaxHeight().navigationBarsPadding() else Modifier)
                                .testTag("diff"),
                            state = list,
                            contentPadding = PaddingValues(bottom = sp.sp4),
                        ) {
                            item(key = "diff-head") { DiffColumnHeader() }
                            items(blocks) { block ->
                                when (block) {
                                    is DiffBlock.Hunk -> DiffHunkRow(block.line.text, first = block.first)
                                    is DiffBlock.Context -> DiffLineRow(block.line, inGroup = false, groupColor = null)
                                    is DiffBlock.Group -> DiffGroupBox(block)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Column header aligned pixel-for-pixel with the line number gutters and code column (demo.html .diff-head). */
@Composable
private fun DiffColumnHeader() {
    val c = Clauderc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .drawBehind {
                drawLine(c.line, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
            }
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 5.dp)
            .testTag("diffHeader"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GutterCell("旧", c.line.copy(alpha = 0.65f))
        GutterCell("新", c.line)
        Spacer(Modifier.width(GutterSignGap + SignWidth + SignCodeGap))
        Text("代码差异", fontFamily = MonoFamily, fontSize = 10.5.sp, color = c.fg3, softWrap = false)
    }
}

@Composable
private fun DiffHunkRow(text: String, first: Boolean) {
    val c = Clauderc.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else 4.dp, bottom = 4.dp)
            .drawBehind {
                val w = 1.dp.toPx()
                if (!first) drawLine(c.line, Offset(0f, w / 2f), Offset(size.width, w / 2f), w)
                drawLine(c.line, Offset(0f, size.height - w / 2f), Offset(size.width, size.height - w / 2f), w)
            }
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text, fontFamily = MonoFamily, fontSize = 11.sp, color = c.fg3, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Consecutive deleted or added lines framed in a background-free bordered box with a 3dp solid left rail.
 * Adjacent Del + Add groups meet along a single hairline divider with zero vertical gap (demo.html .dgrp),
 * and oversized groups chunk into seamless continuations without breaking borders or the left rail.
 */
@Composable
private fun DiffGroupBox(block: DiffBlock.Group) {
    val c = Clauderc.colors
    val accent = if (block.kind == DiffKind.Del) c.delFg else c.addFg
    val tag = if (block.continuesFromPrev) null else if (block.kind == DiffKind.Del) "diffDelGroup" else "diffAddGroup"

    Column(
        Modifier
            .fillMaxWidth()
            .diffGroupFrame(
                add = block.kind == DiffKind.Add,
                openTop = block.joinPrev || block.continuesFromPrev,
                openBottom = block.joinNext || block.continuesToNext,
                seamTop = block.joinPrev,
            )
            .padding(
                top = if (block.continuesFromPrev) 0.dp else 2.dp,
                bottom = if (block.continuesToNext) 0.dp else 2.dp,
            )
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        block.lines.forEach { line ->
            DiffLineRow(line, inGroup = true, groupColor = accent)
        }
    }
}

/**
 * The frame of a run of deleted or added lines (demo.html .dgrp), shared by the git diff page and the
 * chat's Edit and Write diffs: a 6dp margin, a 1dp border in the line colour and a solid 3dp rail on
 * the left, no fill. [openTop] and [openBottom] square that edge off and drop its margin and border,
 * where a deleted run meets the added run below it or a long run goes on in the next slice;
 * [seamTop] draws the single divider where an added run meets the deleted run above.
 */
@Composable
internal fun Modifier.diffGroupFrame(add: Boolean, openTop: Boolean, openBottom: Boolean, seamTop: Boolean): Modifier {
    val c = Clauderc.colors
    val accent = if (add) c.addFg else c.delFg
    val borderColor = accent.copy(alpha = 0.58f)
    val radius = if (Clauderc.style == UiStyle.Plate) 0.dp else 8.dp
    val topR = if (openTop) 0.dp else radius
    val bottomR = if (openBottom) 0.dp else radius
    val shape = RoundedCornerShape(topStart = topR, topEnd = topR, bottomStart = bottomR, bottomEnd = bottomR)
    return padding(start = 6.dp, end = 6.dp, top = if (openTop) 0.dp else 4.dp, bottom = if (openBottom) 0.dp else 4.dp)
        .clip(shape)
        .drawBehind {
            val w = size.width
            val h = size.height
            val rail = 3.dp.toPx()
            val stroke = 1.dp.toPx()
            val tr = topR.toPx()
            val br = bottomR.toPx()
            val rr = RoundRect(
                rect = Rect(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f),
                topLeft = CornerRadius(tr, tr),
                topRight = CornerRadius(tr, tr),
                bottomRight = CornerRadius(br, br),
                bottomLeft = CornerRadius(br, br),
            )
            val outline = Path().apply { addRoundRect(rr) }
            // Outer 1dp border clipped so joining Del+Add seams and chunked continuations never double-stroke.
            val clipTop = if (openTop) stroke else 0f
            val clipBottom = if (openBottom) h - stroke else h
            val clipRect = Path().apply { addRect(Rect(0f, clipTop, w, clipBottom)) }
            clipPath(clipRect) {
                drawPath(outline, borderColor, style = Stroke(stroke))
            }
            // Single crisp seam divider between adjacent Del and Add groups.
            if (seamTop) {
                drawLine(c.line, Offset(rail, stroke / 2f), Offset(w - stroke, stroke / 2f), stroke)
            }
            // Solid 3dp left rail in red/green.
            drawRect(accent, Offset.Zero, Size(rail, h))
        }
}

@Composable
private fun DiffLineRow(l: DiffLine, inGroup: Boolean, groupColor: Color?) {
    val c = Clauderc.colors
    val (_, fg) = diffLineColors(l.kind, c)
    val oldSep = groupColor?.copy(alpha = 0.22f) ?: c.line.copy(alpha = 0.55f)
    val newSep = groupColor?.copy(alpha = 0.36f) ?: c.line
    // 6dp outer group margin + 6dp inner start padding == 12dp context/header start padding -> exact column alignment!
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = if (inGroup) 6.dp else 12.dp, end = if (inGroup) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GutterCell(l.oldNo?.toString().orEmpty(), oldSep)
        GutterCell(l.newNo?.toString().orEmpty(), newSep)
        Spacer(Modifier.width(GutterSignGap))
        Text(
            when (l.kind) {
                DiffKind.Add -> "+"
                DiffKind.Del -> "−"
                else -> ""
            },
            Modifier.width(SignWidth),
            fontFamily = MonoFamily,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (l.kind == DiffKind.Context) c.fg3 else fg,
            textAlign = TextAlign.Center,
            lineHeight = 1.68.em,
        )
        Spacer(Modifier.width(SignCodeGap))
        Text(
            l.text,
            modifier = Modifier.padding(end = 12.dp),
            fontFamily = MonoFamily,
            fontSize = 11.5.sp,
            color = fg,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 1.68.em,
        )
    }
}

@Composable
private fun GutterCell(text: String, dividerColor: Color) {
    Box(
        Modifier
            .width(LineNoWidth)
            .fillMaxHeight()
            .drawBehind {
                val x = size.width - 0.5.dp.toPx()
                drawLine(dividerColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
            .padding(end = 6.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text,
            fontFamily = MonoFamily,
            fontSize = 11.sp,
            color = Clauderc.colors.fg3,
            textAlign = TextAlign.End,
            lineHeight = 1.68.em,
            softWrap = false,
        )
    }
}

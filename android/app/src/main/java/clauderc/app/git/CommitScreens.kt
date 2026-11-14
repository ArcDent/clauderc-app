package clauderc.app.git

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import clauderc.app.net.Relay
import clauderc.app.net.call
import clauderc.app.protocol.CommitFile
import clauderc.app.protocol.GitDiffRequest
import clauderc.app.protocol.GitDiffResponse
import clauderc.app.protocol.GitLimits
import clauderc.app.protocol.GitShowRequest
import clauderc.app.protocol.GitShowResponse
import clauderc.app.protocol.Method
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.BarTitle
import clauderc.app.ui.components.PillButton
import clauderc.app.ui.components.PillStyle
import clauderc.app.ui.components.SectionHeading
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.ClaudercColors
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads a value once; holds an error message when the request fails. */
data class Loadable<T>(val value: T? = null, val error: String? = null)

class CommitViewModel(machineId: String, dir: String, hash: String, relay: Relay) : ViewModel() {
    private val _state = MutableStateFlow(Loadable<GitShowResponse>())
    val state: StateFlow<Loadable<GitShowResponse>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = runCatching {
                relay.call(Method.GIT_SHOW, machineId, GitShowRequest(dir, hash), GitShowRequest.serializer(), GitShowResponse.serializer())
            }.fold({ Loadable(it) }, { Loadable(error = it.message ?: "读不到这个提交") })
        }
    }
}

class DiffViewModel(machineId: String, request: GitDiffRequest, relay: Relay) : ViewModel() {
    private val _state = MutableStateFlow(Loadable<GitDiffResponse>())
    val state: StateFlow<Loadable<GitDiffResponse>> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = runCatching {
                relay.call(Method.GIT_DIFF, machineId, request, GitDiffRequest.serializer(), GitDiffResponse.serializer())
            }.fold({ Loadable(it) }, { Loadable(error = it.message ?: "读不到差异") })
        }
    }
}

private val commitTime = DateTimeFormatter.ofPattern("M 月 d 日 HH:mm")

@Composable
fun CommitScreen(hash: String, state: Loadable<GitShowResponse>, onBack: () -> Unit, onOpenFile: (CommitFile) -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Column(Modifier.fillMaxSize().background(c.bg)) {
        AppBar(navigation = { SymbolButton(Symbols.ArrowBack, "返回", onClick = onBack) }, title = { BarTitle(hash.take(7), mono = true) })
        val show = state.value
        when {
            state.error != null -> Text(state.error, Modifier.padding(sp.page), color = c.danger, fontSize = Clauderc.fontSizes.label)
            show == null -> Box(Modifier.fillMaxWidth().padding(sp.sp8), contentAlignment = Alignment.Center) { Spinner(color = c.fg3) }
            else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), contentPadding = PaddingValues(bottom = sp.sp8)) {
                item {
                    val subject = show.message.substringBefore('\n')
                    val body = show.message.substringAfter('\n', "").trim()
                    Column(Modifier.padding(start = sp.page, end = sp.page, top = sp.sp2, bottom = sp.sp5)) {
                        Text(subject, fontSize = Clauderc.fontSizes.headline, fontWeight = FontWeight.Light, lineHeight = 1.35.em, letterSpacing = (-0.01).em)
                        if (body.isNotEmpty()) Text(body, Modifier.padding(top = sp.sp3), fontSize = Clauderc.fontSizes.label, color = c.fg2, lineHeight = 1.75.em)
                    }
                }
                item {
                    Column(Modifier.padding(start = sp.page, end = sp.page, bottom = sp.sp2), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Meta("作者", show.author)
                        Meta("时间", commitTime.format(show.time.atZone(ZoneId.systemDefault())))
                        if (show.parents.isNotEmpty()) Meta("父提交", show.parents.joinToString(" ") { it.take(7) }, mono = true)
                    }
                }
                item {
                    SectionHeading("${show.files.size} 个文件") {
                        Counts(show.files.sumOf { it.added }, show.files.sumOf { it.deleted })
                    }
                }
                items(show.files, key = { it.path }) { f -> CommitFileRow(f) { onOpenFile(f) } }
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
private fun CommitFileRow(f: CommitFile, onClick: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !f.binary, role = Role.Button, onClick = onClick)
            .padding(start = sp.page, end = sp.sp4 + sp.sp2, top = 10.dp, bottom = 10.dp),
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

/** Background and text color of a diff line: green for added, red for deleted. */
fun diffLineColors(kind: DiffKind, c: ClaudercColors): Pair<Color, Color> = when (kind) {
    DiffKind.Add -> c.addBg to c.addFg
    DiffKind.Del -> c.delBg to c.delFg
    DiffKind.Hunk -> Color.Transparent to c.fg3
    DiffKind.Context -> Color.Transparent to c.fg
}

@Composable
fun DiffScreen(path: String, subtitle: String, state: Loadable<GitDiffResponse>, onBack: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val diff = state.value
    val lines = remember(diff?.patch) { parsePatch(diff?.patch.orEmpty()) }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.bg)) {
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
        val changed = lines.count { it.kind != DiffKind.Hunk }
        when {
            state.error != null -> Text(state.error, Modifier.padding(sp.page), color = c.danger, fontSize = Clauderc.fontSizes.label)
            diff == null -> Box(Modifier.fillMaxWidth().padding(sp.sp8), contentAlignment = Alignment.Center) { Spinner(color = c.fg3) }
            diff.binary -> Text("二进制文件已更改，不显示内容", Modifier.padding(sp.page), fontSize = Clauderc.fontSizes.label, color = c.fg2)
            (diff.collapsed || changed > GitLimits.DIFF_COLLAPSE_LINES) && !expanded -> Column(
                Modifier.padding(horizontal = sp.page, vertical = sp.sp6),
                verticalArrangement = Arrangement.spacedBy(sp.sp2),
            ) {
                Text("%,d 行差异，已折叠".format(changed), fontSize = Clauderc.fontSizes.title, fontWeight = FontWeight.Light)
                Text("超过 1000 行的差异默认不展开。", fontSize = Clauderc.fontSizes.label, color = c.fg2, lineHeight = 1.6.em)
                PillButton("展开", onClick = { expanded = true }, style = PillStyle.Tonal, modifier = Modifier.padding(top = sp.sp3))
            }
            lines.isEmpty() -> Text("没有差异", Modifier.padding(sp.page), fontSize = Clauderc.fontSizes.label, color = c.fg3)
            else -> {
                // A long diff opened with 展开 only fades in: growing a thousand lines in height would stutter.
                val shown = remember { MutableTransitionState(!expanded) }.apply { targetState = true }
                AnimatedVisibility(shown, enter = fadeIn(Motion.enter())) {
                    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().testTag("diff"), contentPadding = PaddingValues(bottom = sp.sp6)) {
                            items(lines) { l -> DiffRow(l) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffRow(l: DiffLine) {
    val c = Clauderc.colors
    val (bg, fg) = diffLineColors(l.kind, c)
    if (l.kind == DiffKind.Hunk) {
        Text(
            l.text,
            Modifier.padding(start = Clauderc.spacing.sp4, end = Clauderc.spacing.sp6, top = Clauderc.spacing.sp3, bottom = Clauderc.spacing.sp1),
            fontFamily = MonoFamily, fontSize = 11.5.sp, color = fg,
        )
        return
    }
    Row(Modifier.background(bg).padding(end = Clauderc.spacing.sp6)) {
        listOf(l.oldNo, l.newNo).forEach { n ->
            Text(n?.toString().orEmpty(), Modifier.width(34.dp).padding(end = 8.dp), fontFamily = MonoFamily, fontSize = 11.5.sp, color = c.fg3, textAlign = TextAlign.End, lineHeight = 1.75.em)
        }
        Text(
            when (l.kind) { DiffKind.Add -> "+"; DiffKind.Del -> "-"; else -> "" },
            Modifier.width(14.dp), fontFamily = MonoFamily, fontSize = 11.5.sp, color = if (l.kind == DiffKind.Context) c.fg3 else fg, textAlign = TextAlign.Center, lineHeight = 1.75.em,
        )
        Text(l.text.replace("\t", "    "), fontFamily = MonoFamily, fontSize = 11.5.sp, color = fg, softWrap = false, lineHeight = 1.75.em)
    }
}

package clauderc.app.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.ui.baseName
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.BarTitle
import clauderc.app.ui.components.FieldError
import clauderc.app.ui.components.FieldLabel
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.components.TextAction
import clauderc.app.ui.components.UnderlineField
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Symbols

/** Shows /Users/me/x and /home/me/x as ~/x; requests always carry the full path. */
fun shortPath(path: String): String = Regex("""^/(Users|home)/[^/]+""").replace(path, "~")

@Composable
fun CreateSessionScreen(
    state: CreateUiState,
    onClose: () -> Unit,
    onSubmit: () -> Unit,
    onDir: (String) -> Unit,
    onPickDir: (String) -> Unit,
    onShowAllDirs: () -> Unit,
    onName: (String) -> Unit,
    onPrompt: (String) -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Column(Modifier.fillMaxSize().imePadding()) {
        AppBar(
            navigation = { SymbolButton(Symbols.Close, "关闭", onClick = onClose) },
            title = { BarTitle("新建会话") },
            actions = { TextAction(if (state.submitting) "正在启动" else "启动", onClick = onSubmit, enabled = state.canSubmit) },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = sp.page, end = sp.page, bottom = sp.sp8),
            verticalArrangement = Arrangement.spacedBy(sp.sp6),
        ) {
            Text(
                buildAnnotatedString {
                    append("${state.profileTitle}档位")
                    state.profileHost?.takeIf { it != state.profileTitle }?.let {
                        append(" ")
                        withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, color = c.fg3)) { append(it) }
                    }
                    append("\n")
                    append(state.templateName?.let { "沿用「$it」的启动参数" } ?: "沿用这个档位最近一次的启动参数")
                },
                fontSize = Clauderc.fontSizes.label,
                color = c.fg2,
                lineHeight = 1.6.em,
            )
            if (!state.canCreate) {
                FieldError("「其他」档位读不到启动参数，不能新建会话")
            }
            Column(verticalArrangement = Arrangement.spacedBy(sp.sp2)) {
                FieldLabel("工作目录")
                UnderlineField(state.dir, onDir, mono = true, error = state.dirError != null, placeholder = "/path/to/project")
                state.dirError?.let { FieldError(it) }
                Column(Modifier.padding(top = sp.sp2)) {
                    state.visibleDirs.forEach { dir ->
                        val checked = dir == state.dir.trim()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = checked, role = Role.RadioButton, onClick = { onPickDir(dir) })
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(sp.sp4),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (checked) Symbols.RadioChecked else Symbols.RadioUnchecked,
                                contentDescription = null,
                                tint = if (checked) c.accent else c.fg3,
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(baseName(dir), fontSize = 15.sp)
                                Text(shortPath(dir), fontFamily = MonoFamily, fontSize = 11.sp, color = c.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (!state.showAllDirs && state.recent.size > 5) {
                        Text(
                            "全部 ${state.recent.size} 个",
                            modifier = Modifier
                                .clickable(role = Role.Button, onClick = onShowAllDirs)
                                .padding(start = 40.dp, top = sp.sp2),
                            fontSize = Clauderc.fontSizes.label,
                            color = c.fg2,
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(sp.sp2)) {
                FieldLabel("会话名，可不填")
                UnderlineField(state.name, onName)
            }
            Column(verticalArrangement = Arrangement.spacedBy(sp.sp2)) {
                FieldLabel("第一句话，可不填")
                UnderlineField(state.prompt, onPrompt, singleLine = false, minLines = 2, placeholder = "不填就只启动会话")
            }
            state.error?.let { FieldError(it) }
        }
    }
}

package clauderc.app.sessions

import clauderc.app.ui.components.StickerCorner
import clauderc.app.ui.components.sticker
import clauderc.app.ui.theme.page
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
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
import clauderc.app.ui.components.ClaudercSheet
import clauderc.app.ui.components.SheetHead
import clauderc.app.data.UiStyle
import androidx.compose.foundation.layout.size
import clauderc.app.ui.components.FieldError
import clauderc.app.ui.components.FieldLabel
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.components.TextAction
import clauderc.app.ui.components.UnderlineField
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Lift
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.styled

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
    onTrust: () -> Unit = {},
    onDismissTrust: () -> Unit = {},
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Column(Modifier.fillMaxSize().page().imePadding()) {
        AppBar(
            navigation = { SymbolButton(Symbols.Close, "关闭", onClick = onClose) },
            title = { BarTitle("新建会话") },
            actions = { TextAction(if (state.submitting) "正在启动" else "启动", onClick = onSubmit, enabled = state.canSubmit) },
        )
        val card = Clauderc.look.cards
        // 贴纸 puts each field on its own ink-outlined card, closer together.
        val field = if (card) {
            Modifier.sticker(c.panel, c.ink, StickerCorner).padding(start = sp.sp4, end = sp.sp4, top = sp.sp3, bottom = sp.sp2)
        } else {
            Modifier
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = if (card) sp.sp4 else sp.page, end = if (card) sp.sp4 else sp.page, bottom = sp.sp8),
            verticalArrangement = Arrangement.spacedBy(if (card) sp.sp3 else sp.sp6),
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
                modifier = if (card) Modifier.padding(start = sp.sp2, end = sp.sp2, top = sp.sp1, bottom = sp.sp2) else Modifier,
                fontSize = Clauderc.fontSizes.label,
                color = c.fg2,
                lineHeight = 1.6.em,
            )
            if (!state.canCreate) {
                FieldError("「其他」档位读不到启动参数，不能新建会话")
            }
            Column(field.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.sp2)) {
                FieldLabel("工作目录")
                UnderlineField(state.dir, onDir, mono = true, error = state.dirError != null, placeholder = "/path/to/project")
                state.dirError?.let { FieldError(it) }
                Column(Modifier.padding(top = sp.sp2).testTag("dirs")) {
                    val head = state.recent.take(5)
                    head.forEach { DirRow(it, it == state.dir.trim(), onPickDir) }
                    // 「全部 N 个」 grows the rest in with the lift rule instead of popping them in.
                    Lift(visible = state.showAllDirs) {
                        Column { state.recent.drop(head.size).forEach { DirRow(it, it == state.dir.trim(), onPickDir) } }
                    }
                    Lift(visible = !state.showAllDirs && state.recent.size > head.size) {
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
            Column(field.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.sp2)) {
                FieldLabel("会话名，可不填")
                UnderlineField(state.name, onName)
            }
            Column(field.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.sp2)) {
                FieldLabel("第一句话，可不填")
                UnderlineField(state.prompt, onPrompt, singleLine = false, minLines = 2, placeholder = "不填就只启动会话")
            }
            state.error?.let { FieldError(it) }
        }
    }
    state.untrusted?.let { TrustSheet(it, state.trusting, onTrust, onDismissTrust) }
}

/**
 * Claude Code does not trust the directory yet: the sheet names it and starts the session after
 * saying yes to Claude Code's own trust prompt, in one step.
 */
@Composable
private fun TrustSheet(dir: String, trusting: Boolean, onTrust: () -> Unit, onDismiss: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    ClaudercSheet(onDismiss = onDismiss) {
        SheetHead("这个目录还没被信任", "Claude Code 信任目录后才能在里面启动会话，之后它能读写和运行这里的文件。")
        // .trust-dir
        Row(
            Modifier
                .padding(start = sp.page, end = sp.page, top = sp.sp2)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Clauderc.radii.sm))
                .background(c.surface)
                .padding(horizontal = sp.sp4, vertical = sp.sp3),
            horizontalArrangement = Arrangement.spacedBy(sp.sp3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Symbols.Folder.styled, contentDescription = null, tint = c.fg3, modifier = Modifier.size(20.dp))
            Text(shortPath(dir), fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, color = c.fg)
        }
        // .sheet .btns
        Row(
            Modifier.fillMaxWidth().padding(start = sp.sp3, end = sp.sp3, top = sp.sp3),
            horizontalArrangement = Arrangement.spacedBy(if (Clauderc.style == UiStyle.Sticker) sp.sp2 else sp.sp1, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 「取消」 looks the same while it works, as in the mockup; the view model ignores it until the answer.
            TextAction("取消", onClick = onDismiss)
            TextAction(if (trusting) "正在信任" else "信任并新建", onClick = onTrust, busy = trusting)
        }
    }
}

@Composable
private fun DirRow(dir: String, checked: Boolean, onPickDir: (String) -> Unit) {
    val c = Clauderc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = checked, role = Role.RadioButton, onClick = { onPickDir(dir) })
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp4),
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

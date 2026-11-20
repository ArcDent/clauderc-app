package clauderc.app.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.protocol.ModelTier
import clauderc.app.ui.baseName
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.BarTitle
import clauderc.app.ui.components.ClaudercSheet
import clauderc.app.ui.components.FieldLabel
import clauderc.app.ui.components.SheetHead
import clauderc.app.ui.components.StickerCorner
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.components.TextAction
import clauderc.app.ui.components.UnderlineField
import clauderc.app.ui.components.sticker
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Lift
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.page
import clauderc.app.ui.theme.styled
import kotlinx.coroutines.delay

/** Shows /Users/me/x and /home/me/x as ~/x; requests always carry the full path. */
fun shortPath(path: String): String = Regex("""^/(Users|home)/[^/]+""").replace(path, "~")

/** Each tier's label and, as its placeholder, the environment variable it sets on the target machine. */
private val TIERS = listOf(
    Triple(ModelTier.Main, "主模型", "ANTHROPIC_MODEL"),
    Triple(ModelTier.Default, "默认", "ANTHROPIC_DEFAULT_MODEL"),
    Triple(ModelTier.Opus, "Opus", "ANTHROPIC_DEFAULT_OPUS_MODEL"),
    Triple(ModelTier.Sonnet, "Sonnet", "ANTHROPIC_DEFAULT_SONNET_MODEL"),
    Triple(ModelTier.Haiku, "Haiku", "ANTHROPIC_DEFAULT_HAIKU_MODEL"),
    Triple(ModelTier.Fable, "Fable", "ANTHROPIC_DEFAULT_FABLE_MODEL"),
    Triple(ModelTier.Subagent, "子代理", "CLAUDE_CODE_SUBAGENT_MODEL"),
)

/**
 * The new-session form (design D8, mockup round 7): the working directory, the 「档位」 to start on with
 * 「自己填」's address and key growing in under it, each tier's model folded under one row, the name and
 * the first message. A mistake marks the field red and slides a card down from under the bar.
 */
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
    onPreset: (String) -> Unit = {},
    onUrl: (String) -> Unit = {},
    onKey: (String) -> Unit = {},
    onToggleKey: () -> Unit = {},
    onToggleModels: () -> Unit = {},
    onModel: (ModelTier, String) -> Unit = { _, _ -> },
    onDismissNote: () -> Unit = {},
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Column(Modifier.fillMaxSize().page().imePadding()) {
        AppBar(
            navigation = { SymbolButton(Symbols.Close, "关闭", onClick = onClose) },
            title = { BarTitle("新建会话") },
            actions = { TextAction(if (state.submitting) "正在启动" else "启动", onClick = onSubmit, enabled = state.canSubmit) },
        )
        Box(Modifier.fillMaxSize()) {
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
                Column(field.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.sp2)) {
                    FieldLabel("工作目录")
                    UnderlineField(state.dir, onDir, mono = true, error = CreateField.Dir in state.badFields, placeholder = "/path/to/project")
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
                    FieldLabel("档位")
                    Column(Modifier.padding(top = sp.sp2).testTag("presets")) {
                        state.presets.forEach { p ->
                            ChoiceRow(p.title, p.sub, p.id == state.preset, titleMono = p.titleIsHost, subMono = false) { onPreset(p.id) }
                        }
                        AnimatedVisibility(state.custom, enter = Motion.growIn, exit = Motion.growOut) {
                            Column(Modifier.padding(start = 40.dp)) {
                                SubField("接口地址") {
                                    UnderlineField(
                                        state.baseUrl, onUrl, mono = true, error = CreateField.Url in state.badFields, placeholder = "https://",
                                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                                    )
                                }
                                SubField("API key") {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(sp.sp1)) {
                                        UnderlineField(
                                            state.apiKey, onKey, Modifier.weight(1f).testTag("apiKey"), mono = true, error = CreateField.Key in state.badFields,
                                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                                            visualTransformation = if (state.keyShown) VisualTransformation.None else PasswordVisualTransformation(),
                                        )
                                        SymbolButton(
                                            if (state.keyShown) Symbols.VisibilityOff else Symbols.Visibility,
                                            if (state.keyShown) "隐藏 key" else "显示 key",
                                            onClick = onToggleKey,
                                            small = true,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                ModelsField(state, field, onToggleModels, onModel)
                Column(field.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.sp2)) {
                    FieldLabel("会话名，可不填")
                    UnderlineField(state.name, onName)
                }
                Column(field.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sp.sp2)) {
                    FieldLabel("第一句话，可不填")
                    UnderlineField(state.prompt, onPrompt, singleLine = false, minLines = 2, placeholder = "不填就只启动会话")
                }
            }
            NoteCard(state.note, onDismissNote, Modifier.align(Alignment.TopCenter))
        }
    }
    state.untrusted?.let { TrustSheet(it, state.trusting, onTrust, onDismissTrust) }
}

/** .csub: a small label over its field, under 「自己填」. */
@Composable
private fun SubField(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = Clauderc.spacing.sp3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FieldLabel(label)
        content()
    }
}

/** .mfield: one row that says how the models stand; tapping it grows the seven tiers in under it. */
@Composable
private fun ModelsField(state: CreateUiState, field: Modifier, onToggle: () -> Unit, onModel: (ModelTier, String) -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val grid = Clauderc.style == UiStyle.Grid
    Column(field.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Clauderc.radii.sm))
                .clickable(role = Role.Button, onClickLabel = if (state.modelsOpen) "收起模型" else "展开模型", onClick = onToggle)
                .padding(vertical = 2.dp)
                .testTag("modelsFold"),
            horizontalArrangement = Arrangement.spacedBy(sp.sp2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("模型", Modifier.weight(1f), fontSize = Clauderc.fontSizes.caption, color = c.fg2)
            Text(state.modelsSummary, fontSize = Clauderc.fontSizes.caption, color = c.fg3)
            val turn by animateFloatAsState(if (state.modelsOpen) 180f else 0f, Motion.enter(), label = "models")
            Icon(Symbols.ExpandMore.styled, contentDescription = null, tint = c.fg3, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(state.modelsOpen, enter = Motion.growIn, exit = Motion.growOut) {
            Column(Modifier.padding(top = sp.sp2)) {
                TIERS.forEach { (tier, label, env) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(sp.sp4), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.width(52.dp), fontSize = Clauderc.fontSizes.label, color = c.fg2, fontFamily = if (grid) MonoFamily else null)
                        UnderlineField(
                            state.models[tier].orEmpty(), { onModel(tier, it) }, Modifier.weight(1f).testTag("model:${tier.name}"),
                            mono = true, fontSize = 13.sp, error = tier in state.badModels, placeholder = env,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The form's mistakes: a card that slides down from under the bar, clipped by its lower edge, with a red
 * icon on a faintly red ground. It goes after four seconds, on a tap, or once the red fields are fixed;
 * raised again while it shows, it shakes once.
 */
@Composable
private fun NoteCard(note: FormNote?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val style = Clauderc.style
    LaunchedEffect(note?.serial) {
        if (note != null) {
            delay(4000)
            onDismiss()
        }
    }
    // Keep the text while it leaves.
    var shown by remember { mutableStateOf("") }
    note?.let { shown = it.text }
    val shake = remember { Animatable(1f) }
    var last by remember { mutableStateOf<FormNote?>(null) }
    LaunchedEffect(note) {
        val before = last
        last = note
        if (before != null && note != null && note.serial != before.serial) {
            shake.snapTo(0f)
            shake.animateTo(1f, Motion.nudge())
        }
    }
    AnimatedVisibility(
        visible = note != null,
        modifier = modifier.fillMaxWidth().clipToBounds(),
        enter = Motion.noteIn { it },
        exit = Motion.noteOut { it },
    ) {
        val shape = if (style == UiStyle.Grid) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp)
        val tint = c.danger.copy(alpha = if (style == UiStyle.Sticker) 0.18f else 0.12f).compositeOver(c.sheet)
        val ground = when (style) {
            UiStyle.Sticker -> Modifier.sticker(tint, c.ink, StickerCorner)
            UiStyle.Grid -> Modifier
                .drawBehind { drawRect(c.danger.copy(alpha = 0.35f), topLeft = Offset(4.dp.toPx(), 4.dp.toPx()), size = size) }
                .background(tint)
                .border(1.dp, c.danger)
            UiStyle.Clear -> Modifier
                .shadow(6.dp, shape)
                .clip(shape)
                .background(tint)
                .border(1.dp, c.danger.copy(alpha = 0.3f), shape)
        }
        Row(
            Modifier
                .padding(start = sp.sp3, end = sp.sp3, top = sp.sp2, bottom = 14.dp)
                .offset(x = Motion.nudgeDp(shake.value).dp)
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(ground)
                .clickable(onClickLabel = "关闭提示", onClick = onDismiss)
                .padding(horizontal = sp.sp4, vertical = sp.sp3)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("formNote"),
            horizontalArrangement = Arrangement.spacedBy(sp.sp3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Symbols.ErrorFilled, contentDescription = null, tint = c.danger, modifier = Modifier.size(22.dp))
            Text(
                shown,
                color = c.fg,
                fontSize = Clauderc.fontSizes.label,
                lineHeight = 1.45.em,
                fontWeight = if (style == UiStyle.Sticker) FontWeight.Bold else null,
                fontFamily = if (style == UiStyle.Grid) MonoFamily else null,
            )
        }
    }
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
private fun DirRow(dir: String, checked: Boolean, onPickDir: (String) -> Unit) =
    ChoiceRow(baseName(dir), shortPath(dir), checked, titleMono = false, subMono = true) { onPickDir(dir) }

/** .recent row: a radio, a name and a small second line; the 「档位」 list writes plain words on that line. */
@Composable
private fun ChoiceRow(title: String, sub: String, checked: Boolean, titleMono: Boolean, subMono: Boolean, onClick: () -> Unit) {
    val c = Clauderc.colors
    val grid = Clauderc.style == UiStyle.Grid
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = checked, role = Role.RadioButton, onClick = onClick)
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
            Text(title, fontSize = if (titleMono) 14.sp else 15.sp, fontFamily = if (titleMono) MonoFamily else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sub,
                fontFamily = if (subMono || grid) MonoFamily else null,
                fontSize = if (subMono || grid) 11.sp else Clauderc.fontSizes.caption,
                color = c.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

package clauderc.app.chat

import clauderc.app.ui.components.inkSurface
import clauderc.app.ui.components.plateBox
import clauderc.app.ui.components.tileSurface
import clauderc.app.ui.components.glassPane
import clauderc.app.ui.components.LocalGlassSource
import clauderc.app.data.UiStyle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.protocol.Answer
import clauderc.app.protocol.Pending
import clauderc.app.protocol.PendingKind
import clauderc.app.protocol.Question
import clauderc.app.ui.components.Glyph
import clauderc.app.ui.components.PillButton
import clauderc.app.ui.components.PillStyle
import clauderc.app.ui.components.StatusGlyph
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.components.UnderlineField
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.theme.PressRow
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols

/** .panel: the bottom sheet that replaces the composer while the session waits for the user. */
@Composable
fun PendingPanel(
    pending: Pending,
    enabled: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onAnswer: (List<Answer>) -> Unit,
    inIsland: Boolean = false,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val corner = when (ui) {
        UiStyle.Plate -> 0.dp
        UiStyle.Ink -> 30.dp
        UiStyle.Tile -> 26.dp
        UiStyle.Clear -> Clauderc.radii.lg
    }
    val shape = if (ui == UiStyle.Plate) RectangleShape else RoundedCornerShape(topStart = corner, topEnd = corner)
    val surface = if (inIsland) Modifier else when (ui) {
        UiStyle.Clear -> Modifier.shadow(10.dp, shape, ambientColor = c.scrim, spotColor = c.scrim).clip(shape).glassPane(LocalGlassSource.current, edgeBottom = false)
        UiStyle.Plate -> Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .drawBehind {
                // Four corner crop marks around the letterpress confirmation card (demo.html).
                val t = 8.dp.toPx()
                val o = 5.dp.toPx()
                val w = 1.5.dp.toPx()
                drawLine(c.fg, Offset(-o, -o), Offset(-o + t, -o), w)
                drawLine(c.fg, Offset(-o, -o), Offset(-o, -o + t), w)
                drawLine(c.fg, Offset(size.width + o - t, -o), Offset(size.width + o, -o), w)
                drawLine(c.fg, Offset(size.width + o, -o), Offset(size.width + o, -o + t), w)
                drawLine(c.fg, Offset(-o, size.height + o), Offset(-o + t, size.height + o), w)
                drawLine(c.fg, Offset(-o, size.height + o - t), Offset(-o, size.height + o), w)
                drawLine(c.fg, Offset(size.width + o - t, size.height + o), Offset(size.width + o, size.height + o), w)
                drawLine(c.fg, Offset(size.width + o, size.height + o - t), Offset(size.width + o, size.height + o), w)
            }
            .plateBox(c.sheet, c.fg, 2.dp)
        UiStyle.Ink -> Modifier.shadow(12.dp, shape, ambientColor = c.scrim, spotColor = c.scrim).inkSurface(c.sheet, c.edge, corner, bottom = false)
        UiStyle.Tile -> Modifier.shadow(10.dp, shape, ambientColor = c.scrim, spotColor = c.scrim).tileSurface(c.sheet, corner, bottom = false)
    }
    val safeVertical = 14.dp
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .then(surface)
            .testTag("pendingPanel")
            .padding(vertical = safeVertical)
            .verticalScroll(rememberScrollState())
            .padding(
                start = if (inIsland) 18.dp else sp.page,
                end = if (inIsland) 18.dp else sp.page,
                top = (if (inIsland) 16.dp else sp.sp6) - safeVertical,
                bottom = (if (inIsland) 14.dp else sp.sp5) - safeVertical,
            ),
        verticalArrangement = Arrangement.spacedBy(sp.sp3),
    ) {
        when (pending.kind) {
            PendingKind.QUESTION -> QuestionContent(pending.toolUseId, pending.questions.orEmpty(), enabled, onAnswer)
            // A dialog shows its title and options as on the computer's screen; a tap picks, nothing can be typed.
            PendingKind.DIALOG -> QuestionContent(pending.toolUseId, pending.questions.orEmpty().take(1), enabled, onAnswer, label = "等你选择", custom = false)
            else -> PermissionContent(pending, enabled, onAllow, onDeny)
        }
    }
}

@Composable
private fun NeedLabel(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp2), verticalAlignment = Alignment.CenterVertically) {
        StatusGlyph(Glyph.Need)
        Text(text, fontSize = Clauderc.fontSizes.caption, fontWeight = FontWeight.Medium, color = Clauderc.colors.accent)
    }
}

@Composable
private fun PermissionContent(pending: Pending, enabled: Boolean, onAllow: () -> Unit, onDeny: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val tool = pending.toolName.orEmpty()
    val (title, code) = permissionPrompt(tool, pending.input)
    NeedLabel("等你允许")
    Text(title, fontSize = Clauderc.fontSizes.title, lineHeight = 1.4.em)
    val ui = Clauderc.style
    val codeShape = if (ui == UiStyle.Plate) RectangleShape else RoundedCornerShape(Clauderc.radii.sm)
    Text(
        code,
        modifier = Modifier
            .fillMaxWidth()
            .clip(codeShape)
            .background(c.surface)
            .then(
                when (ui) {
                    UiStyle.Plate -> Modifier.border(1.dp, c.fg, codeShape)
                    UiStyle.Ink -> Modifier.border(1.dp, c.edge, codeShape)
                    else -> Modifier
                },
            )
            .padding(horizontal = sp.sp4, vertical = sp.sp3),
        fontFamily = MonoFamily,
        fontSize = Clauderc.fontSizes.mono,
        lineHeight = 1.6.em,
    )
    Text(tool, fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.caption, color = c.fg3)
    Row(Modifier.fillMaxWidth().padding(top = sp.sp2), horizontalArrangement = Arrangement.spacedBy(sp.sp3)) {
        PillButton(
            "拒绝",
            onClick = onDeny,
            style = if (Clauderc.style == UiStyle.Plate) PillStyle.Danger else PillStyle.Outline,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        PillButton("允许", onClick = onAllow, style = PillStyle.Accent, enabled = enabled, modifier = Modifier.weight(1f))
    }
}

/**
 * One question with one choice submits on tap. Several questions, or a
 * multi-select one, collect answers first and submit with the button.
 * 「其他」 is the last row of the choices: picked, it opens a field in its row. In a single-choice
 * question it and the options exclude each other; in a multi-select one it is ticked along with them.
 */
@Composable
private fun QuestionContent(key: String, questions: List<Question>, enabled: Boolean, onAnswer: (List<Answer>) -> Unit, label: String? = null, custom: Boolean = true) {
    val sp = Clauderc.spacing
    val instant = questions.size == 1 && !questions[0].multiSelect
    val count = questions.size
    val selected = rememberSaveable(
        key, count,
        saver = listSaver(
            save = { map -> List(count) { i -> map[i].orEmpty().toIntArray() } },
            restore = { saved ->
                mutableStateMapOf<Int, List<Int>>().apply {
                    saved.forEachIndexed { i, arr -> if (arr.isNotEmpty()) put(i, arr.toList()) }
                }
            },
        ),
    ) { mutableStateMapOf<Int, List<Int>>() }
    val other = rememberSaveable(
        key, count,
        saver = listSaver(
            save = { map -> List(count) { i -> map[i] == true } },
            restore = { saved ->
                mutableStateMapOf<Int, Boolean>().apply {
                    saved.forEachIndexed { i, on -> if (on) put(i, true) }
                }
            },
        ),
    ) { mutableStateMapOf<Int, Boolean>() }
    val typed = rememberSaveable(
        key, count,
        saver = listSaver(
            save = { list -> list.toList() },
            restore = { saved ->
                mutableStateListOf<String>().apply {
                    addAll(saved)
                    while (size < count) add("")
                }
            },
        ),
    ) { mutableStateListOf(*Array(count) { "" }) }
    // What is typed stays in the field while 「其他」 is off, but only goes out with it on.
    fun written(i: Int) = typed[i].trim().takeIf { other[i] == true && it.isNotEmpty() }
    fun answers() = questions.indices.map { i -> Answer(selected = selected[i].orEmpty().sorted().ifEmpty { null }, custom = written(i)) }
    val complete = questions.indices.all { written(it) != null || selected[it].orEmpty().isNotEmpty() }

    NeedLabel(label ?: if (questions.size > 1) "等你回答 · ${questions.size} 题" else "等你回答")
    questions.forEachIndexed { qi, q ->
        Text(
            buildAnnotatedString {
                append(q.question)
                if (q.multiSelect) withStyle(SpanStyle(fontSize = Clauderc.fontSizes.caption, color = Clauderc.colors.fg3)) { append("  可多选") }
            },
            fontSize = Clauderc.fontSizes.title,
            lineHeight = 1.4.em,
        )
        Column(Modifier.padding(horizontal = 0.dp)) {
            q.options.forEachIndexed { oi, option ->
                val on = selected[qi].orEmpty().contains(oi)
                ChoiceRow(
                    label = option.label,
                    description = option.description,
                    multi = q.multiSelect,
                    on = on,
                    enabled = enabled,
                    onClick = {
                        if (instant) {
                            onAnswer(listOf(Answer(selected = listOf(oi))))
                        } else if (q.multiSelect) {
                            selected[qi] = if (on) selected[qi].orEmpty() - oi else selected[qi].orEmpty() + oi
                        } else {
                            selected[qi] = listOf(oi)
                            other[qi] = false
                        }
                    },
                )
            }
            if (custom) {
                val on = other[qi] == true
                ChoiceRow(
                    label = "其他",
                    description = "",
                    multi = q.multiSelect,
                    on = on,
                    enabled = enabled,
                    onClick = {
                        if (q.multiSelect) {
                            other[qi] = !on
                        } else {
                            other[qi] = true
                            selected[qi] = emptyList()
                        }
                    },
                )
                // The field opens in the row like a tool row's detail (durM in, durS out), lined up with the labels.
                AnimatedVisibility(
                    on,
                    enter = expandVertically(Motion.enter()) + fadeIn(Motion.enter()),
                    exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
                ) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    Row(
                        Modifier.padding(start = 24.dp + sp.sp4, end = sp.sp1, bottom = sp.sp3),
                        horizontalArrangement = Arrangement.spacedBy(sp.sp2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UnderlineField(typed[qi], { typed[qi] = it }, placeholder = "写下你的回答", fontSize = 15.sp, modifier = Modifier.weight(1f).focusRequester(focus), enabled = enabled)
                        if (instant) {
                            SymbolButton(Symbols.ArrowUpward, "发送回答", onClick = { onAnswer(answers()) }, small = true, enabled = enabled && written(qi) != null)
                        }
                    }
                }
            }
        }
    }
    if (!instant) {
        PillButton("提交", onClick = { onAnswer(answers()) }, enabled = enabled && complete, modifier = Modifier.fillMaxWidth().padding(top = sp.sp2))
    }
}

@Composable
private fun ChoiceRow(label: String, description: String, multi: Boolean, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val rowModifier = if (multi) {
        Modifier.toggleable(value = on, interactionSource = null, indication = PressIndication(row = PressRow.Choice), enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = on, interactionSource = null, indication = PressIndication(row = PressRow.Choice), enabled = enabled, role = Role.RadioButton, onClick = onClick)
    }
    Row(
        Modifier.fillMaxWidth().then(rowModifier).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(sp.sp4),
    ) {
        Icon(
            when {
                multi && on -> Symbols.CheckBox
                multi -> Symbols.CheckBoxOutlineBlank
                on -> Symbols.RadioChecked
                else -> Symbols.RadioUnchecked
            },
            contentDescription = null,
            tint = if (on) c.accent else c.fg3,
        )
        Column {
            Text(label, fontSize = 15.sp, lineHeight = 1.5.em)
            if (description.isNotEmpty()) Text(description, fontSize = Clauderc.fontSizes.caption, color = c.fg2, lineHeight = 1.5.em)
        }
    }
}

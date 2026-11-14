package clauderc.app.chat

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Symbols

/** .panel: the bottom sheet that replaces the composer while the session waits for the user. */
@Composable
fun PendingPanel(
    pending: Pending,
    enabled: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onAnswer: (List<Answer>) -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .shadow(10.dp, RoundedCornerShape(topStart = Clauderc.radii.lg, topEnd = Clauderc.radii.lg), ambientColor = c.scrim, spotColor = c.scrim)
            .clip(RoundedCornerShape(topStart = Clauderc.radii.lg, topEnd = Clauderc.radii.lg))
            .background(c.sheet)
            .verticalScroll(rememberScrollState())
            .padding(start = sp.page, end = sp.page, top = sp.sp6, bottom = sp.sp5)
            .testTag("pendingPanel"),
        verticalArrangement = Arrangement.spacedBy(sp.sp3),
    ) {
        if (pending.kind == PendingKind.QUESTION) {
            QuestionContent(pending.questions.orEmpty(), enabled, onAnswer)
        } else {
            PermissionContent(pending, enabled, onAllow, onDeny)
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
    Text(
        code,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Clauderc.radii.sm))
            .background(c.surface)
            .padding(horizontal = sp.sp4, vertical = sp.sp3),
        fontFamily = MonoFamily,
        fontSize = Clauderc.fontSizes.mono,
        lineHeight = 1.6.em,
    )
    Text(tool, fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.caption, color = c.fg3)
    Row(Modifier.fillMaxWidth().padding(top = sp.sp2), horizontalArrangement = Arrangement.spacedBy(sp.sp3)) {
        PillButton("拒绝", onClick = onDeny, style = PillStyle.Outline, enabled = enabled, modifier = Modifier.weight(1f))
        PillButton("允许", onClick = onAllow, style = PillStyle.Accent, enabled = enabled, modifier = Modifier.weight(1f))
    }
}

/**
 * One question with one choice submits on tap. Several questions, or a
 * multi-select one, collect answers first and submit with the button.
 */
@Composable
private fun QuestionContent(questions: List<Question>, enabled: Boolean, onAnswer: (List<Answer>) -> Unit) {
    val sp = Clauderc.spacing
    val instant = questions.size == 1 && !questions[0].multiSelect
    val selected = remember(questions) { mutableStateMapOf<Int, List<Int>>() }
    val custom = remember(questions) { mutableStateListOf(*Array(questions.size) { "" }) }
    fun answers() = questions.indices.map { i ->
        if (custom[i].isNotBlank()) Answer(custom = custom[i].trim()) else Answer(selected = selected[i].orEmpty().sorted())
    }
    val complete = questions.indices.all { custom[it].isNotBlank() || selected[it].orEmpty().isNotEmpty() }

    NeedLabel(if (questions.size > 1) "等你回答 · ${questions.size} 题" else "等你回答")
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
                        }
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(sp.sp2), verticalAlignment = Alignment.CenterVertically) {
            UnderlineField(custom[qi], { custom[qi] = it }, placeholder = "其他回答", fontSize = 15.sp, modifier = Modifier.weight(1f), enabled = enabled)
            if (instant) {
                SymbolButton(Symbols.ArrowUpward, "发送回答", onClick = { onAnswer(answers()) }, small = true, enabled = enabled && custom[qi].isNotBlank())
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
        Modifier.toggleable(value = on, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = onClick)
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

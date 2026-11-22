package clauderc.app.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.protocol.ChatTask
import clauderc.app.protocol.TaskStatus
import clauderc.app.ui.components.LocalGlassSource
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.components.glassPane
import clauderc.app.ui.components.sticker
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols

/** At most this many task rows show at once; more scroll inside the list. */
private const val TASK_ROWS = 6
private val TaskRowHeight = 32.dp

/**
 * Round 10: the session's Claude Code task list over the input. Folded, one row: the task in progress,
 * or the next one, and how many are done; a tap unfolds the whole list upwards, done tasks fading.
 * Once all are done the row says so, until Claude Code clears the list and the bar leaves.
 */
@Composable
internal fun TaskBar(tasks: List<ChatTask>, modifier: Modifier = Modifier) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val grid = ui == UiStyle.Grid
    var open by rememberSaveable { mutableStateOf(false) }
    val surface = when (ui) {
        UiStyle.Clear -> Modifier.clip(RoundedCornerShape(20.dp)).glassPane(LocalGlassSource.current)
        UiStyle.Grid -> Modifier.clip(RoundedCornerShape(6.dp)).background(c.bg).border(1.dp, c.fg, RoundedCornerShape(6.dp))
        UiStyle.Sticker -> Modifier.sticker(c.card, c.ink, 18.dp)
    }
    val size = if (grid) 12.sp else Clauderc.fontSizes.label
    val family = if (grid) MonoFamily else null
    val weight = if (ui == UiStyle.Sticker) FontWeight.Bold else null
    val done = tasks.count { it.status == TaskStatus.COMPLETED }
    val all = done == tasks.size
    val head = tasks.firstOrNull { it.status == TaskStatus.IN_PROGRESS } ?: tasks.firstOrNull { it.status == TaskStatus.PENDING }
    Column(modifier.fillMaxWidth().then(surface).testTag("taskBar")) {
        AnimatedVisibility(
            open,
            enter = fadeIn(Motion.enter()) + expandVertically(Motion.enter(), expandFrom = Alignment.Bottom),
            exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.exit(), shrinkTowards = Alignment.Bottom),
        ) {
            Column(Modifier.heightIn(max = TaskRowHeight * TASK_ROWS + sp.sp2).verticalScroll(rememberScrollState()).padding(top = sp.sp2).testTag("taskList")) {
                for (t in tasks) {
                    val tone = when (t.status) {
                        TaskStatus.COMPLETED -> c.fg3
                        TaskStatus.IN_PROGRESS -> c.fg
                        else -> c.fg2
                    }
                    Row(
                        Modifier.fillMaxWidth().height(TaskRowHeight).padding(horizontal = sp.sp4).testTag("taskRow:${t.id}"),
                        horizontalArrangement = Arrangement.spacedBy(sp.sp3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TaskGlyph(t.status, if (ui == UiStyle.Sticker && t.status == TaskStatus.IN_PROGRESS) c.accent else tone)
                        Text(t.subject, fontSize = size, fontFamily = family, fontWeight = weight, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val press = remember { MutableInteractionSource() }
        // The chevron points up while folded, the list unfolding that way.
        val turn by animateFloatAsState(if (open) 0f else 180f, Motion.enter(), label = "taskChevron")
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable(press, PressIndication(c.fg2), role = Role.Button, onClickLabel = if (open) "收起任务清单" else "展开任务清单") { open = !open }
                .semantics { stateDescription = if (open) "已展开" else "已收起" }
                .padding(start = sp.sp4, end = sp.sp2)
                .testTag("taskHead"),
            horizontalArrangement = Arrangement.spacedBy(sp.sp3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskGlyph(if (all) TaskStatus.COMPLETED else head?.status ?: TaskStatus.PENDING, if (ui == UiStyle.Sticker) c.accent else c.fg)
            Text(
                if (all) "全部完成" else head?.subject.orEmpty(),
                Modifier.weight(1f),
                fontSize = size, fontFamily = family, fontWeight = weight, color = c.fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("$done/${tasks.size}", fontSize = size, fontFamily = family, fontWeight = weight, color = c.fg3, modifier = Modifier.testTag("taskCount"))
            Icon(Symbols.ExpandMore, contentDescription = null, tint = c.fg3, modifier = Modifier.size(20.dp).rotate(turn))
        }
    }
}

/**
 * A task's state as the terminal marks it, drawn rather than set in a font, which may lack the
 * characters or draw them as emoji: a filled square in progress, an empty one to do, a tick once done.
 */
@Composable
private fun TaskGlyph(status: String, color: Color) {
    val name = when (status) {
        TaskStatus.COMPLETED -> "做完"
        TaskStatus.IN_PROGRESS -> "进行中"
        else -> "没开始"
    }
    Box(Modifier.size(14.dp).semantics { contentDescription = name }.testTag("taskGlyph:$status"), contentAlignment = Alignment.Center) {
        when (status) {
            TaskStatus.COMPLETED -> Icon(Symbols.Check, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            TaskStatus.IN_PROGRESS -> Box(Modifier.size(9.dp).background(color))
            else -> Box(Modifier.size(9.dp).border(1.25.dp, color))
        }
    }
}

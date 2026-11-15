package clauderc.app.machines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import clauderc.app.net.ConnectionState
import clauderc.app.net.RelayLink
import clauderc.app.ui.TimeText
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.ClaudercSheet
import clauderc.app.ui.components.Glyph
import clauderc.app.ui.components.Hero
import clauderc.app.ui.components.MenuItem
import clauderc.app.ui.components.PillButton
import clauderc.app.ui.components.PillStyle
import clauderc.app.ui.components.SnackHost
import clauderc.app.ui.components.SnackState
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.components.StatusGlyph
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.components.cardItem
import clauderc.app.ui.components.isCardStyle
import clauderc.app.ui.components.rowStart
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Symbols
import kotlin.math.ceil
import kotlinx.coroutines.delay

/** Bottom space so the last row clears the floating nav bar (.screen.has-nav .content). */
val NavClearance = 92.dp

@Composable
fun MachinesScreen(
    state: MachinesUiState,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onClearAll: () -> Unit = {},
    snack: SnackState = remember { SnackState() },
) {
    var menu by remember { mutableStateOf(false) }
    val sp = Clauderc.spacing
    val paired = state.relays.isNotEmpty()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppBar(actions = {
                SymbolButton(Symbols.Add, "添加目标机", onClick = onAdd)
                if (paired) SymbolButton(Symbols.MoreVert, "更多", onClick = { menu = true })
            })
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = NavClearance)) {
                item { Hero("目标机", moreLines = state.relays.map<RelayLink, @Composable RowScope.() -> Unit> { link -> { RelayLine(link) } }) }
                val rows = state.rows
                when {
                    !paired -> item { EmptyMachines(onAdd) }
                    rows == null -> item { Spinner(Modifier.padding(start = sp.page), size = 16.dp, color = Clauderc.colors.fg3) }
                    else -> {
                        itemsIndexed(rows, key = { _, it -> it.machine.id }) { i, row ->
                            MachineRowView(row, Modifier.cardItem(first = i == 0, last = i == rows.lastIndex)) {
                                if (row.machine.online) onOpen(row.machine.id) else snack.show("${row.machine.name} 已离线，联网后才能查看会话")
                            }
                        }
                        item {
                            Text(
                                "新目标机接入后会自动出现在这里。",
                                modifier = Modifier.padding(horizontal = sp.page, vertical = sp.sp8),
                                fontSize = Clauderc.fontSizes.caption,
                                color = Clauderc.colors.fg3,
                                lineHeight = 1.6.em,
                            )
                        }
                    }
                }
            }
        }
        SnackHost(snack, Modifier.align(Alignment.BottomCenter), bottom = 88.dp)
    }
    if (menu) {
        ClaudercSheet(onDismiss = { menu = false }) {
            MenuItem("清除全部配对", onClick = { menu = false; onClearAll() }, icon = Symbols.Delete, supporting = "这台手机会忘记所有中转服务器的凭证", danger = true)
        }
    }
}

/** .empty: shown until the first relay is added. */
@Composable
private fun EmptyMachines(onAdd: () -> Unit) {
    val sp = Clauderc.spacing
    Column(
        Modifier.fillMaxWidth().padding(sp.sp8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(sp.sp3),
    ) {
        Icon(Symbols.Computer, contentDescription = null, tint = Clauderc.colors.fg3, modifier = Modifier.size(40.dp))
        Text("还没有目标机", fontSize = Clauderc.fontSizes.title)
        PillButton("添加", onClick = onAdd, style = PillStyle.Ink)
    }
}

/** One relay under the title: its host when connected, otherwise how the reconnect goes. */
@Composable
private fun RelayLine(link: RelayLink) {
    val c = Clauderc.colors
    when (val conn = link.state) {
        ConnectionState.Connected -> {
            StatusGlyph(Glyph.On)
            Text(link.host)
        }
        is ConnectionState.Waiting -> {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(conn) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(250)
                }
            }
            val left = ceil((conn.retryAtMillis - now).coerceAtLeast(0) / 1000.0).toInt()
            Spinner(size = 12.dp, color = c.fg2)
            Text(if (left > 0) "${link.host} 正在重连，$left 秒后重试" else "${link.host} 正在重连")
        }
        else -> {
            Spinner(size = 12.dp, color = c.fg2)
            Text("正在连接 ${link.host}")
        }
    }
}

@Composable
private fun MachineRowView(row: MachineRow, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val m = row.machine
    val card = isCardStyle
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = rowStart, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(sp.sp4),
    ) {
        if (card) {
            // The card style sets the icon in a small rounded tile.
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                Icon(Symbols.Computer, contentDescription = null, tint = if (m.online) c.fg else c.fg3, modifier = Modifier.size(22.dp))
            }
        } else {
            Icon(Symbols.Computer, contentDescription = null, tint = if (m.online) c.fg2 else c.fg3)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(m.name, fontSize = Clauderc.fontSizes.body, color = if (m.online) c.fg else c.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildAnnotatedString {
                    when {
                        !m.online -> append("离线 · 最后在线 ${TimeText.clock(m.lastSeenAt)}")
                        row.sessions == null -> append(if (m.claudeVersion.isNotEmpty()) "claude ${m.claudeVersion}" else "在线")
                        row.sessions == 0 -> append("没有会话")
                        else -> {
                            append("${row.sessions} 个会话")
                            if (row.pending > 0) {
                                append(" · ")
                                withStyle(SpanStyle(color = c.accent)) { append("${row.pending} 个等你") }
                            }
                        }
                    }
                },
                fontSize = Clauderc.fontSizes.label,
                color = c.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (m.online) {
            Icon(
                Symbols.ChevronRight,
                contentDescription = null,
                tint = c.fg3,
                modifier = Modifier.align(Alignment.CenterVertically).padding(end = sp.sp3),
            )
        } else {
            Spacer(Modifier.padding(end = sp.sp3))
        }
    }
}

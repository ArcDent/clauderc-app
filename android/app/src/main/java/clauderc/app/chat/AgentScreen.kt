package clauderc.app.chat

import clauderc.app.ui.components.LoadingArt
import clauderc.app.ui.components.LoadingCover
import clauderc.app.ui.theme.page
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import clauderc.app.protocol.SessionState
import clauderc.app.sessions.display
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.BarTitle
import clauderc.app.ui.components.SnackHost
import clauderc.app.ui.components.SnackState
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Lift
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Symbols

/** What the subagent page names in its bar, from the row it was opened from. */
data class AgentHeader(val description: String, val type: String, val startedAtMillis: Long? = null)

/** The note at the end of a subagent's page once it no longer runs. */
const val AGENT_ENDED = "子代理已结束"

/**
 * A subagent's transcript, read only: no composer, no interrupt, nothing to answer. While it runs the
 * bottom line counts 「工作中」 from when it was started; once it ended that line goes down and the list
 * ends in a grey 「子代理已结束」.
 */
@Composable
fun AgentScreen(state: ChatUiState, header: AgentHeader, actions: ChatActions, snack: SnackState = remember { SnackState() }) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    val openImage: OpenImage = { v ->
        viewing = v
        val id = v.image.id
        if (v.local == null && id !in state.wholeImages && id !in state.fetchingImages) actions.onImage(id)
    }
    val working = state.state == SessionState.Working
    val ended = !state.loading && !working
    Box(Modifier.fillMaxSize().page()) {
        Column(Modifier.fillMaxSize()) {
            AppBar(
                navigation = { SymbolButton(Symbols.ArrowBack, "返回", onClick = actions.onBack) },
                title = {
                    BarTitle(header.description.ifEmpty { "子代理" }) {
                        Text(
                            buildAnnotatedString {
                                append("子代理")
                                if (header.type.isNotEmpty()) {
                                    append(" · ")
                                    withStyle(SpanStyle(fontFamily = MonoFamily)) { append(header.type) }
                                }
                                state.session?.let { append(" · " + display(it)) }
                            },
                            fontSize = Clauderc.fontSizes.caption,
                            color = c.fg2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
            // Opening, blocks light up over the messages until the record is read.
            LoadingCover(state.loading, LoadingArt.Blocks, Modifier.weight(1f), label = "正在读取子代理记录") {
                Column(Modifier.fillMaxSize()) {
                    MessageList(
                        state.copy(notice = if (ended) AGENT_ENDED else null, waking = false, outgoing = emptyList()),
                        actions,
                        openImage,
                        Modifier.weight(1f),
                        snack = snack,
                    )
                    Lift(visible = working) {
                        // Drawn whole as its room opens, over the messages: it paints the page behind it.
                        Box(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(start = sp.sp3, end = sp.sp3, top = sp.sp2, bottom = sp.sp4).heightIn(min = 40.dp).testTag("agentWorking"), contentAlignment = Alignment.CenterStart) {
                            WorkingRow(header.startedAtMillis ?: state.workingSinceMillis, enabled = true, onInterrupt = null)
                        }
                    }
                    if (!working) Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
        viewing?.let { v -> ChatImageViewer(v, state, actions, onClosed = { viewing = null }) }
        // Drawn after the viewer, so a snackbar shows over it.
        SnackHost(snack, Modifier.align(Alignment.BottomCenter), bottom = 24.dp, onCamera = viewing != null)
    }
}

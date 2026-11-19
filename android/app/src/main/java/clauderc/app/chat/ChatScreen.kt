package clauderc.app.chat

import clauderc.app.ui.components.LoadingArt
import clauderc.app.ui.components.LoadingCover
import clauderc.app.ui.theme.PushDepth
import clauderc.app.ui.components.topHighlight
import clauderc.app.ui.components.LocalGlassSource
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource
import clauderc.app.ui.components.glassPane
import clauderc.app.ui.theme.page
import clauderc.app.ui.components.sticker
import clauderc.app.data.UiStyle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.clipToBounds
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import clauderc.app.ui.theme.LOOP_FRAME_MILLIS
import clauderc.app.ui.theme.LocalLoopClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.protocol.ChatItem
import clauderc.app.protocol.ChatLimits
import clauderc.app.protocol.Command
import clauderc.app.protocol.ItemKind
import clauderc.app.protocol.ModelOption
import clauderc.app.protocol.PermissionMode
import clauderc.app.protocol.SessionState
import clauderc.app.protocol.Subagent
import clauderc.app.protocol.ToolCall
import clauderc.app.protocol.ToolResult
import clauderc.app.sessions.display
import clauderc.app.ui.contextPercent
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.BarSubButton
import clauderc.app.ui.components.BarTitle
import clauderc.app.ui.components.ClaudercSheet
import clauderc.app.ui.components.Glyph
import clauderc.app.ui.components.MenuItem
import clauderc.app.ui.components.SheetHead
import clauderc.app.ui.components.SnackHost
import clauderc.app.ui.components.SnackState
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.components.StatusGlyph
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.components.TextAction
import clauderc.app.ui.components.TextTone
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Lift
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.pressShrink
import clauderc.app.ui.theme.stillLayer
import java.util.Base64
import kotlinx.coroutines.delay

class ChatActions(
    val onBack: () -> Unit = {},
    val onOpenGit: (String) -> Unit = {},
    val onLoadOlder: () -> Unit = {},
    val onSend: (String) -> Boolean = { true },
    val onInterrupt: () -> Unit = {},
    val onAllow: () -> Unit = {},
    val onDeny: () -> Unit = {},
    val onAnswer: (List<clauderc.app.protocol.Answer>) -> Unit = {},
    val onLoadModels: () -> Unit = {},
    val onSetModel: (ModelOption) -> Unit = {},
    val onSetMode: (String) -> Unit = {},
    val onPickImages: () -> Unit = {},
    val onTakePhoto: () -> Unit = {},
    val onRemoveImage: (Int) -> Unit = {},
    /** Asks for the whole result of a tool call whose text or diff came cut to 8 KB. */
    val onToolOutput: (String) -> Unit = {},
    /** Asks for a picture of a tool result, by its id, for the viewer. */
    val onImage: (String) -> Unit = {},
    /** Opens the session's terminal view. */
    val onOpenTerm: () -> Unit = {},
    /** Opens the read-only page of a subagent from its Agent row. */
    val onOpenAgent: (Subagent) -> Unit = {},
    /** The composer's text was edited; kept as the session's draft. */
    val onDraft: (String) -> Unit = {},
)

private enum class ChatSheet { Attach, Model, Mode }

/** The light tick when a message goes out or a permission is answered. */
private fun HapticFeedback.tap() = performHapticFeedback(HapticFeedbackType.VirtualKey)

@Composable
fun ChatScreen(state: ChatUiState, actions: ChatActions, snack: SnackState = remember { SnackState() }) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    var sheet by remember { mutableStateOf<ChatSheet?>(null) }
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    val openImage: OpenImage = { img, from ->
        viewing = Viewing(img, from)
        if (img.id !in state.wholeImages && img.id !in state.fetchingImages) actions.onImage(img.id)
    }
    val openModels = { actions.onLoadModels(); sheet = ChatSheet.Model }
    val haptic = LocalHapticFeedback.current
    val title = state.session?.let(::display) ?: "会话"

    // 清玻 floats the bar and the input area over the messages as glass, the messages sliding under them;
    // the other styles stack them above and below the messages.
    val floating = Clauderc.style == UiStyle.Clear
    val glass = if (floating) rememberHazeState() else null
    val bar: @Composable () -> Unit = {
        AppBar(
            navigation = { SymbolButton(Symbols.ArrowBack, "返回", onClick = actions.onBack) },
            title = {
                BarTitle(title) {
                    BarSubButton(modelLabel(state.model).ifEmpty { "模型" }, onClick = openModels, enabled = state.online)
                    BarSubButton(permissionModeLabel(state.permissionMode), onClick = { sheet = ChatSheet.Mode }, enabled = state.online)
                    contextPercent(state.session?.context)?.let {
                        Text("上下文 $it%", fontSize = Clauderc.fontSizes.caption, color = c.fg2, maxLines = 1)
                    }
                }
            },
            actions = {
                SymbolButton(Symbols.Terminal, "打开终端", onClick = actions.onOpenTerm, enabled = state.online && state.state != SessionState.Stopped)
                SymbolButton(Symbols.AccountTree, "打开 git", onClick = { state.session?.cwd?.let(actions.onOpenGit) }, enabled = state.online && state.session != null)
            },
        )
        Lift(visible = !state.online, fromTop = true) {
            Row(
                Modifier.fillMaxWidth().background(if (Clauderc.look.cards) c.card else c.surface).padding(horizontal = sp.page, vertical = sp.sp3).testTag("offlineLine"),
                horizontalArrangement = Arrangement.spacedBy(sp.sp3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Symbols.CloudOff, contentDescription = null, tint = c.fg3, modifier = Modifier.size(20.dp))
                Text(state.offlineText, fontSize = Clauderc.fontSizes.label, color = c.fg2)
            }
    }
    }
    val dock: @Composable () -> Unit = {
        // The panel is drawn whole from the first frame and comes up from the bottom edge over the fading
        // composer and the messages, while the room it takes grows to its height and the messages move up
        // with it. Closing is the reverse, the panel staying on top as it goes back down.
        AnimatedContent(
            targetState = state.pending,
            contentAlignment = Alignment.BottomStart,
            contentKey = { it?.toolUseId },
            transitionSpec = {
                val room = SizeTransform(clip = false) { _, _ -> Motion.enter() }
                if (targetState != null) (Motion.panelIn + fadeIn(Motion.enter())).togetherWith(fadeOut(Motion.exit())).using(room)
                else (fadeIn(Motion.enter()) togetherWith Motion.panelOut + fadeOut(Motion.exit())).using(room).apply { targetContentZIndex = -1f }
            },
            label = "panel",
        ) { pending ->
            if (pending != null) {
                Box(Modifier.navigationBarsPadding().testTag("pendingPanel")) {
                    PendingPanel(
                        pending,
                        enabled = state.online,
                        onAllow = { haptic.tap(); actions.onAllow() },
                        onDeny = { haptic.tap(); actions.onDeny() },
                        onAnswer = actions.onAnswer,
                    )
                }
            } else {
                Composer(state, actions, onAttach = { sheet = ChatSheet.Attach }, onModels = openModels)
            }
    }
    }
    Box(Modifier.fillMaxSize().page()) {
        if (floating) {
            var barHeight by remember { mutableIntStateOf(0) }
            var dockHeight by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().imePadding()) {
                // Opening, empty bubbles stand in for the messages and the input until the record is read.
                LoadingCover(state.loading, LoadingArt.Skeleton, Modifier.fillMaxSize(), top = with(density) { barHeight.toDp() }) {
                    Box(Modifier.fillMaxSize()) {
                        MessageList(
                            state, actions, openImage,
                            Modifier.fillMaxSize().then(if (glass != null) Modifier.hazeSource(glass) else Modifier),
                            padTop = with(density) { barHeight.toDp() },
                            padBottom = with(density) { dockHeight.toDp() },
                        )
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().onSizeChanged { dockHeight = it.height }) {
                            CompositionLocalProvider(LocalGlassSource provides glass) { dock() }
                        }
                    }
                }
                Column(Modifier.align(Alignment.TopStart).fillMaxWidth().onSizeChanged { barHeight = it.height }.glassPane(glass, rimTop = false)) { bar() }
            }
        } else {
            Column(Modifier.fillMaxSize().imePadding()) {
                bar()
                LoadingCover(state.loading, LoadingArt.Skeleton, Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize()) {
                        MessageList(state, actions, openImage, Modifier.weight(1f))
                        dock()
                    }
                }
            }
        }
        SnackHost(snack, Modifier.align(Alignment.BottomCenter), bottom = 84.dp)
        viewing?.let { v ->
            ImageViewer(v, state.wholeImages[v.image.id], v.image.id in state.failedImages, onClosed = { viewing = null })
        }
    }

    when (sheet) {
        ChatSheet.Attach -> ClaudercSheet(onDismiss = { sheet = null }) {
            SheetHead("添加图片", "最多 ${ChatLimits.MAX_IMAGES} 张，每张不超过 10 MB")
            MenuItem("拍照", onClick = { sheet = null; actions.onTakePhoto() }, icon = Symbols.PhotoCamera)
            MenuItem("从相册选择", onClick = { sheet = null; actions.onPickImages() }, icon = Symbols.Image)
        }
        ChatSheet.Model -> ClaudercSheet(onDismiss = { sheet = null }) {
            SheetHead("模型")
            val models = state.models
            when {
                models == null -> Row(Modifier.padding(horizontal = sp.page, vertical = sp.sp4)) { Spinner(size = 16.dp, color = c.fg3) }
                state.modelsError != null -> Text(state.modelsError, Modifier.padding(horizontal = sp.page, vertical = sp.sp3), fontSize = Clauderc.fontSizes.label, color = c.fg2)
                else -> models.forEach { m ->
                    MenuItem(
                        m.label.ifEmpty { m.id },
                        onClick = { sheet = null; if (!m.current) actions.onSetModel(m) },
                        supporting = m.description.ifEmpty { null },
                        checked = m.current,
                    )
                }
            }
            Text("从下一条回复开始生效", Modifier.padding(start = sp.page, end = sp.page, top = sp.sp3), fontSize = Clauderc.fontSizes.caption, color = c.fg3)
        }
        ChatSheet.Mode -> ClaudercSheet(onDismiss = { sheet = null }) {
            SheetHead("权限模式")
            val current = state.permissionMode
            if (current.isNotEmpty() && current !in PermissionMode.switchable) {
                MenuItem(permissionModeLabel(current), onClick = {}, supporting = "不能从手机切换到这个模式", checked = true, enabled = false, danger = current == PermissionMode.BYPASS)
            }
            PermissionMode.switchable.forEach { mode ->
                MenuItem(
                    permissionModeLabel(mode),
                    onClick = { sheet = null; if (mode != current) actions.onSetMode(mode) },
                    supporting = permissionModeHint(mode),
                    checked = mode == current,
                )
            }
        }
        null -> Unit
    }
}

@Composable
internal fun MessageList(state: ChatUiState, actions: ChatActions, openImage: OpenImage, modifier: Modifier, padTop: Dp = 0.dp, padBottom: Dp = 0.dp) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val slack = with(LocalDensity.current) { 24.dp.toPx() }
    val scroll = remember(list, slack) { ChatScroll(list, slack) }
    // The reply drawn on the computer's screen sits under the last item as a reply of its own, under its
    // own key; the written reply takes that key over, so it carries on typing in the same bubble.
    val partial = state.partial
    val items = remember(state.items, partial?.text) {
        if (partial == null) state.items else state.items + ChatItem(PARTIAL_SEQ, ItemKind.ASSISTANT, text = partial.text)
    }
    val keys = if (partial == null) state.keys else state.keys + (PARTIAL_SEQ to partial.key)
    // Runs of searches, reads and commands are one entry, keyed by their first item.
    val runs = remember(items) { chatRuns(items) }.asReversed()
    // The list is drawn bottom-up: the newest item is index 0, so the first page opens at the bottom
    // and older pages are appended at the top without moving what the user is reading.
    LaunchedEffect(list, state.hasMore) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && total > 0 && last >= total - 3) actions.onLoadOlder() }
    }
    val note = when {
        state.waking -> "正在唤醒会话"
        else -> state.notice
    }
    // A new bottom item that arrives while the list sits at the bottom is pinned there in the same
    // frame. Left alone, the list keeps the item that was at the bottom in place, so the new one lands
    // below the edge and a scroll has to catch up: the jump of plans.md 1. Away from the bottom
    // nothing moves.
    val pin = remember { Pin() }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 }.collect { pin.atBottom = it }
    }
    val bottomKey: Any? = when {
        note != null -> "notice"
        state.outgoing.isNotEmpty() -> outgoingKey(state.outgoing.last())
        else -> items.lastOrNull()?.let { keys[it.seq] ?: it.seq }
    }
    if (bottomKey != pin.key) {
        if (pin.key != null && pin.atBottom) list.requestScrollToItem(0)
        pin.key = bottomKey
    }
    // Replies that arrive while the page is open grow in and type out; what was already there, what
    // chat.open brought, older pages and items scrolling back into view just show. Null until chat.open
    // answered and its items have been drawn.
    val newest = remember { Newest() }
    val growAfter = newest.seq
    SideEffect {
        if (state.opened) {
            val last = state.items.lastOrNull()?.seq ?: 0
            if (last > (newest.seq ?: Long.MIN_VALUE)) newest.seq = last
        }
    }
    val tools = ToolOutputs(state.fullResults, state.fetchingResults, actions.onToolOutput, openImage, actions.onOpenAgent, live = state.state != SessionState.Stopped)
    CompositionLocalProvider(LocalChatScroll provides scroll) {
    LazyColumn(
        // Bars floating over the list (清玻) take [padTop] and [padBottom] off what counts as in view.
        modifier.fillMaxWidth().stillLayer(list).testTag("chatList").onPlaced {
            val b = it.boundsInRoot()
            scroll.top = b.top + padTop.value * density.density
            scroll.bottom = b.bottom - padBottom.value * density.density
        },
        state = list,
        reverseLayout = true,
        contentPadding = PaddingValues(start = sp.sp4, end = sp.sp4, top = sp.sp4 + padTop, bottom = sp.sp5 + padBottom),
        verticalArrangement = Arrangement.spacedBy(sp.sp2, Alignment.Bottom),
    ) {
        if (note != null) {
            item(key = "notice") { Note(note, spinner = state.waking) }
        }
        // contentType lets the list reuse a bubble's composition for the next bubble of the same kind.
        // No placement animation: with a reply growing in, the items above would trail behind its
        // growth and catch up, which reads as a bounce. A delivered message keeps its bubble's key.
        items(state.outgoing.asReversed(), key = ::outgoingKey, contentType = { ItemKind.USER }) { out ->
            Box(Modifier.animateItem(fadeInSpec = Motion.enter(), placementSpec = null, fadeOutSpec = Motion.exit())) { OutgoingBubble(out) }
        }
        items(
            runs,
            key = { keys[it.first().seq] ?: it.first().seq },
            contentType = { run -> run.first().let { if (run.size > 1) "group" else if (it.tool?.let(::isCommand) == true) "command" else it.kind } },
        ) { run ->
            val item = run.first()
            // The screen's reply is new only the first time its bubble is drawn, not when it scrolls back into view.
            val grow = remember {
                item.kind == ItemKind.ASSISTANT && growAfter != null &&
                    if (item.seq == PARTIAL_SEQ) newest.partials.add(partial!!.key) else item.seq > growAfter
            }
            Box(Modifier.animateItem(fadeInSpec = if (grow) null else Motion.enter(), placementSpec = null, fadeOutSpec = Motion.exit())) {
                val working = state.state == SessionState.Working
                when {
                    run.size > 1 -> ToolGroupRow(run.mapNotNull { it.tool }, working, tools)
                    grow -> GrowIn { ChatItemView(item, working, tools, typed = true) }
                    else -> ChatItemView(item, working, tools)
                }
            }
        }
        if (state.hasMore || state.loadingOlder) {
            item(key = "older") {
                Box(Modifier.fillMaxWidth().padding(top = sp.sp1, bottom = sp.sp2), contentAlignment = Alignment.Center) {
                    Spinner(size = 14.dp, color = c.fg3)
                }
            }
        }
    }
    }
}

/**
 * Whole tool results fetched with chat.toolOutput and the ones on their way, what fetches one, what opens a
 * picture and a subagent's page, and whether the session still runs, without which no subagent can.
 */
private class ToolOutputs(
    val full: Map<String, ToolResult>,
    val fetching: Set<String>,
    val fetch: (String) -> Unit,
    val open: OpenImage,
    val openAgent: (Subagent) -> Unit = {},
    val live: Boolean = true,
)

/** The newest seq a chat page has drawn and the screen replies it has drawn; a plain holder, so updating it redraws nothing. */
private class Newest {
    var seq: Long? = null
    val partials = HashSet<String>()
}

/** The seq of the reply drawn on the computer's screen, after every item's. */
private const val PARTIAL_SEQ = Long.MAX_VALUE

/** The key of the bottom item last drawn and whether the list sat at the bottom; a plain holder like [Newest]. */
private class Pin {
    var key: Any? = null
    var atBottom = true
}

/**
 * A new reply, or a picture that just came in, grows from nothing to its height and fades in, on the enter
 * rule (durM, emphasized). It shows a growing share of whatever its content measures, so a reply typing
 * on while it grows is never cut short of its last line: a size animation would start over at each new line.
 */
@Composable
private fun GrowIn(content: @Composable () -> Unit) {
    val grown = remember { Animatable(0f) }
    LaunchedEffect(grown) { grown.animateTo(1f, Motion.enter()) }
    Box(
        Modifier
            .clipToBounds()
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, (p.height * grown.value).roundToInt()) { p.place(0, 0) }
            }
            .graphicsLayer { alpha = grown.value },
    ) { content() }
}

/** [typed] marks a reply that came in while the page is open: it types out from the first character. */
@Composable
private fun ChatItemView(item: ChatItem, working: Boolean, tools: ToolOutputs, typed: Boolean = false) {
    when (item.kind) {
        ItemKind.USER -> UserBubble(item)
        ItemKind.ASSISTANT -> AssistantItem(item.text.orEmpty(), typed)
        ItemKind.TOOL -> item.tool?.let {
            when {
                isSendMessage(it) -> {
                    val to = it.input.stringField("to").orEmpty()
                    val message = it.input.stringField("message").orEmpty()
                    if (message.isEmpty()) Note("等「$to」空闲") else PeerNote("发给 $to", message)
                }
                it.agent != null -> AgentRow(it.agent, tools.live, tools.openAgent)
                isCommand(it) -> CommandView(it)
                isShow(it) -> ShownImages(it.result!!.images!!, tools.open)
                else -> ToolRow(it, working, tools.full[it.toolUseId], it.toolUseId in tools.fetching, tools.fetch, tools.open)
            }
        }
        ItemKind.NOTICE -> item.title.let { if (it.isNullOrEmpty()) Note(item.text.orEmpty()) else PeerNote(it, item.text.orEmpty()) }
        ItemKind.INTERRUPTED -> Note("已中断")
    }
}

/** Where the text of a reply starts inside its bubble; grey lines start there too. */
private val ReplyTextStart = 16.dp

/** A short grey line in the message flow, like Claude Code's 「Interrupted」, its left edge lined up with Claude's text. */
@Composable
private fun Note(text: String, spinner: Boolean = false) {
    val sp = Clauderc.spacing
    Row(Modifier.fillMaxWidth().padding(start = ReplyTextStart, end = ReplyTextStart, top = sp.sp2, bottom = sp.sp2), verticalAlignment = Alignment.CenterVertically) {
        if (spinner) Spinner(size = 12.dp, color = Clauderc.colors.fg3)
        Text(text, Modifier.padding(start = if (spinner) sp.sp2 else 0.dp), fontSize = Clauderc.fontSizes.caption, color = Clauderc.colors.fg3)
    }
}

/**
 * A message from another session: its title as a [Note], the message in full under it in grey,
 * set like a reply so its left edge lines up with Claude's text. No background or border.
 */
@Composable
private fun PeerNote(title: String, body: String) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Column(Modifier.fillMaxWidth().padding(horizontal = ReplyTextStart, vertical = sp.sp2), verticalArrangement = Arrangement.spacedBy(sp.sp1)) {
        Text(title, fontSize = Clauderc.fontSizes.caption, color = c.fg3)
        Text(body, color = c.fg2, fontSize = 15.sp, lineHeight = 1.55.em)
    }
}

/**
 * A reply: its bubble, then the job-list lines a background session ends with, "result:",
 * "needs input:" or "failed:", taken out of the bubble and shown as written in grey, as the CLI prints them.
 */
@Composable
private fun AssistantItem(text: String, typed: Boolean = false) {
    val (body, marks) = remember(text) { splitMarkers(text) }
    if (marks.isEmpty()) return AssistantBubble(text, typed)
    Column(Modifier.fillMaxWidth()) {
        if (body.isNotBlank()) AssistantBubble(body, typed)
        marks.forEach { Note(it) }
    }
}

@Composable
private fun UserBubble(item: ChatItem) {
    val images = item.images.orEmpty()
    UserBubbleLayout(item.text.orEmpty(), images.size, faded = false, queued = item.queued == true) { i ->
        val data = images[i].data
        ChatPicture(rememberChatImage(data) { Base64.getDecoder().decode(data) })
    }
}

/**
 * A message on its way: faded until delivered, then marked queued until the transcript shows it. A panel
 * command shows in the transcript only once its panel closes, so a delivered one is drawn plainly meanwhile.
 */
@Composable
private fun OutgoingBubble(out: Outgoing) {
    UserBubbleLayout(out.text, out.attachments.size, faded = !out.queued && !out.delivered, queued = out.queued) { i ->
        val a = out.attachments[i]
        ChatPicture(rememberChatImage(a) { a.jpeg })
    }
}

@Composable
private fun UserBubbleLayout(text: String, pictures: Int, faded: Boolean, queued: Boolean = false, picture: @Composable (Int) -> Unit) {
    val c = Clauderc.colors
    // 贴纸 speaks in its accent; the other two in black on white.
    val sticker = Clauderc.style == UiStyle.Sticker
    Box(Modifier.fillMaxWidth().padding(top = Clauderc.spacing.sp2).alpha(if (faded) 0.5f else 1f), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.fillMaxWidth(0.82f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pictures > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(pictures) { picture(it) } }
            }
            if (text.isNotEmpty()) {
                Text(
                    text,
                    modifier = Modifier.bubble(mine = true).padding(horizontal = 16.dp, vertical = 10.dp),
                    color = if (sticker) c.onAccent else c.bg,
                    fontSize = 15.sp,
                    lineHeight = 1.55.em,
                )
            }
            if (queued) {
                Text("排队中", Modifier.padding(end = Clauderc.spacing.sp2), fontSize = Clauderc.fontSizes.caption, color = c.fg3)
            }
        }
    }
}

/**
 * A chat bubble (.bubble), the user's [mine] or a reply. 清玻: 22dp corners, black for the user and grey
 * for replies. 格栅: 6dp corners, and a reply has no bubble, only a heavy rule down its left edge.
 * 贴纸: ink-outlined stickers on a hard shadow, the user's in the accent, a reply white, each with
 * an 8dp corner at the bottom on its own side.
 */
@Composable
private fun Modifier.bubble(mine: Boolean): Modifier {
    val c = Clauderc.colors
    return when (Clauderc.style) {
        UiStyle.Clear -> clip(RoundedCornerShape(22.dp)).background(if (mine) c.fg else c.surface)
        UiStyle.Grid -> if (mine) clip(RoundedCornerShape(6.dp)).background(c.fg) else drawBehind { drawRect(c.fg, size = Size(2.dp.toPx(), size.height)) }
        UiStyle.Sticker -> if (mine) {
            sticker(c.accent, c.ink, 22.dp, drop = 3.dp, bottomEnd = 8.dp)
        } else {
            sticker(c.panel, c.ink, 22.dp, bottomStart = 8.dp)
        }
    }
}

/** A 96dp picture, a blank tile while it decodes. */
@Composable
private fun ChatPicture(bitmap: ImageBitmap?) {
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(Clauderc.colors.surface)) {
        if (bitmap != null) Image(bitmap, contentDescription = "图片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/**
 * A reply's bubble. One that came in while the page is open, [typed], types out its formatted text from
 * the first character; any reply whose text grows, as the screen's preview does, types out what is new.
 */
@Composable
private fun AssistantBubble(text: String, typed: Boolean = false) {
    val c = Clauderc.colors
    // Parsed once per text, not on every recomposition as the reply scrolls in.
    val rich = remember(text, c.fg) { richText(text, c.fg) }
    // Kept across text changes: the written reply carries on from where its screen preview got to.
    val typer = remember { Typer(if (typed) 0f else rich.length.toFloat()) }
    val target by rememberUpdatedState(rich.length)
    if (typer.shown < rich.length) LaunchedEffect(typer) { typer.typeOut { target } }
    val n = typer.shown.toInt()
    Box(Modifier.fillMaxWidth(0.82f)) {
        Text(
            if (n >= rich.length) rich else rich.subSequence(0, n),
            modifier = Modifier.bubble(mine = false).padding(start = if (Clauderc.style == UiStyle.Grid) 14.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp).testTag("reply"),
            color = c.fg,
            fontSize = 15.sp,
            lineHeight = 1.55.em,
        )
    }
}

/** How much of a reply shows while it types out, in characters of its formatted text. */
@Stable
private class Typer(start: Float) {
    var shown by mutableFloatStateOf(start)

    /**
     * Types on toward [target] off the loop rate, at least [TYPE_MIN] characters a second and faster the
     * further behind, what is left in [TYPE_CATCH] seconds, so it eases to typing speed as it catches up
     * (demo.html typeOn).
     */
    suspend fun typeOut(target: () -> Int) {
        var last = withFrameMillis { it }
        while (shown < target()) {
            delay(LOOP_FRAME_MILLIS)
            val now = withFrameMillis { it }
            val dt = ((now - last) / 1000f).coerceAtMost(0.1f)
            last = now
            val goal = target().toFloat()
            shown = minOf(goal, shown + maxOf(TYPE_MIN * dt, (goal - shown) * dt / TYPE_CATCH))
        }
    }
}

private const val TYPE_MIN = 40f
private const val TYPE_CATCH = 0.8f

/** A slash command the user ran: their own bubble, with its output, if any, folded into a row under it. */
@Composable
private fun CommandView(tool: ToolCall) {
    val output = tool.result?.content.orEmpty().trim()
    Column(Modifier.fillMaxWidth()) {
        UserBubbleLayout(tool.summary, 0, faded = false) {}
        if (output.isNotEmpty()) {
            val fg = Clauderc.colors.fg
            FoldRow(tool.toolUseId, "输出", output.lineSequence().first { it.isNotBlank() }) {
                FoldText(buildAnnotatedString { withStyle(SpanStyle(color = fg)) { append(output) } })
            }
        }
    }
}

/**
 * A tool call: a fold row whose detail is the diff view for an Edit, Write or Read and the input
 * and output for the rest, all of it in a box that scrolls past [ToolBoxMax]. It starts folded; a
 * result cut to 8 KB fetches its whole text when opened. [full] is that text once chat.toolOutput brought it in.
 */
@Composable
private fun ToolRow(tool: ToolCall, working: Boolean, full: ToolResult?, fetching: Boolean, onFetch: (String) -> Unit, onOpen: OpenImage) {
    val c = Clauderc.colors
    val (verb, arg) = splitSummary(tool)
    val result = full ?: tool.result
    val rows = remember(result) { patchRows(result) }
    val fold = rememberFold(tool.toolUseId)
    LaunchedEffect(fold.expanded, tool.result?.truncated) {
        if (fold.expanded && tool.result?.truncated == true && full == null) onFetch(tool.toolUseId)
    }
    // A read picture is titled in the viewer by the file it was read from.
    val file = tool.input.stringField("file_path")?.substringAfterLast('/')
    val images = tool.result?.images.orEmpty().map { if (it.name == null && file != null) it.copy(name = file) else it }
    // Pictures that come in while the page is open grow in under the row; ones already there just show.
    val ran = remember { tool.result == null }
    Column(Modifier.fillMaxWidth()) {
    FoldRow(
        verb, arg, fold,
        status = {
            if (result != null && rows != null) patchStat(result, rows)?.let { PatchStatText(it) }
            // When the result comes in, the spinner fades out and gives its room back (durS, leaving curve).
            AnimatedVisibility(
                tool.result == null && working,
                Modifier.testTag("toolSpinner"),
                enter = fadeIn(Motion.enter()),
                exit = fadeOut(Motion.exit()) + shrinkHorizontally(Motion.exit()),
            ) {
                Spinner(Modifier.padding(start = Clauderc.spacing.sp2), size = 12.dp, color = c.fg3)
            }
            failWord(tool)?.let { Text(it, Modifier.padding(start = Clauderc.spacing.sp2), fontSize = Clauderc.fontSizes.caption, color = c.danger) }
        },
    ) {
        if (result != null && rows != null) {
            PatchDetail(fold, rows, fetching)
        } else {
            FoldText(
                buildAnnotatedString {
                    toolDetail(tool.copy(result = result)).forEachIndexed { i, (k, v) ->
                        if (i > 0) append("\n")
                        withStyle(SpanStyle(color = c.fg3)) { append(k) }
                        append("\n")
                        withStyle(SpanStyle(color = c.fg)) { append(v) }
                    }
                },
                fetching = fetching,
            )
        }
    }
    images.forEach { img ->
        val thumb = @Composable { ToolThumb(img, 200.dp, 0.62f, onOpen, Modifier.padding(start = Clauderc.spacing.sp2, end = Clauderc.spacing.sp2, top = 2.dp, bottom = Clauderc.spacing.sp2)) }
        if (ran) GrowIn(thumb) else thumb()
    }
    }
}

/**
 * Two or more tool calls in a row as one line, as in Claude's desktop app (tool view A): what was
 * done, the lines its edits and writes changed, and the calls one by one once opened, each folded
 * with its own result.
 */
@Composable
private fun ToolGroupRow(tools: List<ToolCall>, working: Boolean, outputs: ToolOutputs) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val fold = rememberFold("group-" + tools.first().toolUseId)
    val scope = rememberCoroutineScope()
    val scroll = LocalChatScroll.current
    val turn by animateFloatAsState(if (fold.expanded) 90f else 0f, Motion.turn(), label = "chevron")
    val grid = Clauderc.style == UiStyle.Grid
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Clauderc.radii.sm))
                .clickable(role = Role.Button, onClickLabel = if (fold.expanded) "收起" else "展开") { fold.toggle(scope, scroll) }
                .padding(horizontal = sp.sp2, vertical = 6.dp)
                .testTag("toolGroup"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                remember(tools, c.fg, c.danger) { groupSummary(tools, c.fg, c.danger) },
                Modifier.weight(1f),
                fontSize = if (grid) 12.sp else 13.sp,
                fontFamily = if (grid) MonoFamily else null,
                lineHeight = 1.5.em,
                color = c.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            remember(tools) { groupStat(tools) }?.let { PatchStatText(it) }
            AnimatedVisibility(
                tools.any { it.result == null } && working,
                enter = fadeIn(Motion.enter()),
                exit = fadeOut(Motion.exit()) + shrinkHorizontally(Motion.exit()),
            ) {
                Spinner(Modifier.padding(start = sp.sp2), size = 12.dp, color = c.fg3)
            }
            Icon(Symbols.ChevronRight, contentDescription = null, tint = c.fg3, modifier = Modifier.padding(start = sp.sp2).size(18.dp).rotate(turn))
        }
        if (fold.open) {
            Column(fold.modifier().padding(start = sp.sp3)) {
                tools.forEachIndexed { i, t ->
                    Box(
                        Modifier.graphicsLayer {
                            alpha = fold.rowAlpha(i)
                            translationY = -4.dp.toPx() * fold.rowLift(i)
                        },
                    ) { ToolRow(t, working, outputs.full[t.toolUseId], t.toolUseId in outputs.fetching, outputs.fetch, outputs.open) }
                }
            }
        }
    }
}

/**
 * An Agent call: 「子代理 · 描述」, spinning while the subagent runs; the whole row opens its read-only
 * page instead of folding. A subagent the agent has no id for yet cannot be opened.
 */
@Composable
private fun AgentRow(agent: Subagent, live: Boolean, onOpen: (Subagent) -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Clauderc.radii.sm))
            .clickable(enabled = agent.id != null, role = Role.Button, onClickLabel = "打开子代理") { onOpen(agent) }
            .padding(horizontal = sp.sp2, vertical = 6.dp)
            .testTag("agentRow"),
        horizontalArrangement = Arrangement.spacedBy(sp.sp2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("子代理 ·", fontSize = 13.sp, color = c.fg3)
        Text(agent.description.ifEmpty { agent.type }, Modifier.weight(1f), fontSize = 13.sp, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // When it ends, the spinner fades out and gives its room back, as on a tool row.
        AnimatedVisibility(
            agent.running && live,
            Modifier.testTag("agentSpinner"),
            enter = fadeIn(Motion.enter()),
            exit = fadeOut(Motion.exit()) + shrinkHorizontally(Motion.exit()),
        ) {
            Spinner(size = 12.dp, color = c.fg3)
        }
        Icon(Symbols.ChevronRight, contentDescription = null, tint = c.fg3, modifier = Modifier.size(18.dp))
    }
}

/** A fold row that folds by its own key, for rows with no diff view. */
@Composable
private fun FoldRow(key: String, verb: String?, arg: String, detail: @Composable () -> Unit) =
    FoldRow(verb, arg, rememberFold(key), detail = detail)

/**
 * A one-line row, a grey [verb] and a mono [arg], that unfolds [detail] under it; [detail] is composed only while open.
 * [status] sits right after [arg] and brings its own gap, so a part of it that shrinks away leaves none behind.
 */
@Composable
private fun FoldRow(verb: String?, arg: String, fold: Fold, status: @Composable RowScope.() -> Unit = {}, detail: @Composable () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val scope = rememberCoroutineScope()
    val scroll = LocalChatScroll.current
    val turn by animateFloatAsState(if (fold.expanded) 90f else 0f, Motion.turn(), label = "chevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Clauderc.radii.sm))
                .clickable(role = Role.Button, onClickLabel = if (fold.expanded) "收起" else "展开") { fold.toggle(scope, scroll) }
                .padding(horizontal = sp.sp2, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(sp.sp2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (verb != null) Text(verb, fontSize = 13.sp, color = c.fg3)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(arg, Modifier.weight(1f), fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                status()
            }
            Icon(Symbols.ChevronRight, contentDescription = null, tint = c.fg3, modifier = Modifier.size(18.dp).rotate(turn))
        }
        if (fold.open) Box(fold.modifier()) { detail() }
    }
}

/** The plain detail of a fold row: mono text on the surface, in a box that scrolls past [ToolBoxMax]. */
@Composable
private fun FoldText(text: AnnotatedString, fetching: Boolean = false) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Column(
        Modifier
            .padding(start = sp.sp2, end = sp.sp2, top = 2.dp, bottom = sp.sp2)
            .fillMaxWidth()
            .toolDetail()
            .toolBox()
            .padding(horizontal = sp.sp4, vertical = sp.sp3),
    ) {
        Text(text, fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, lineHeight = 1.6.em, color = c.fg2)
        if (fetching) Fetching(Modifier.padding(top = sp.sp2))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(state: ChatUiState, actions: ChatActions, onAttach: () -> Unit, onModels: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val haptic = LocalHapticFeedback.current
    // 贴纸 floats an ink-outlined input on its paper and sends with the accent; 清玻 floats glass over the
    // messages; 格栅 squares the corners.
    val ui = Clauderc.style
    val card = ui == UiStyle.Sticker
    val wrap = if (ui == UiStyle.Grid) c.bg else Color.Transparent
    // The cursor is kept with the text, so a picked command can put it after itself.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val text = field.text
    // A failed send hands its text back, unless something new was typed meanwhile.
    LaunchedEffect(state.restore) { state.restore?.let { if (field.text.isBlank()) field = TextFieldValue(it.text, TextRange(it.text.length)) } }
    // With the keyboard put away there is nothing to type into, so the field lets go and its cursor stops
    // blinking: two frames a second that the phone draws slowly while it idles. A tap takes it back. Only
    // once the keyboard was up: clearing focus as the page opens would stir the input method mid-slide.
    val keyboard = WindowInsets.isImeVisible
    val focus = LocalFocusManager.current
    val wasUp = remember { BooleanArray(1) }
    LaunchedEffect(keyboard) {
        if (!keyboard && wasUp[0]) focus.clearFocus()
        wasUp[0] = keyboard
    }
    val word = if (field.selection.collapsed) slashWord(text, field.selection.end) else null
    val hits = filterCommands(state.commands, word)
    val enabled = state.online
    // The blocks over the input rise whole over the messages as their room opens, so each brings the gap
    // under it into that room and paints the page behind it, out over the side padding too.
    val block = Modifier.coverBehind(wrap, sp.sp3).padding(bottom = sp.sp2)
    Column(Modifier.fillMaxWidth().background(wrap).navigationBarsPadding().padding(start = sp.sp3, end = sp.sp3, top = sp.sp2, bottom = sp.sp3)) {
        // Keep the last matches while the popup leaves.
        var shown by remember { mutableStateOf(emptyList<Command>() to "") }
        if (hits != null && word != null) shown = hits to word.query
        // The popup floats over the messages with its bottom just over the input area: it takes no room.
        val overlap = with(LocalDensity.current) { (sp.sp2 - 2.dp).roundToPx() }
        Lift(visible = hits != null && enabled, modifier = Modifier.floatAbove(overlap)) {
            SlashPopup(shown.first, query = shown.second) { cmd ->
                val w = word ?: return@SlashPopup
                when {
                    cmd.name.equals("model", ignoreCase = true) -> {
                        // /model would change the computer's default model; the phone's picker changes only this session.
                        field = TextFieldValue(text.removeRange(w.start, w.end), TextRange(w.start))
                        onModels()
                    }
                    // A panel command goes out as is, and its panel is seen in the terminal.
                    cmd.panel == true -> if (actions.onSend("/" + cmd.name)) {
                        haptic.tap()
                        val rest = text.removeRange(w.start, w.end).trim()
                        field = TextFieldValue(rest, TextRange(rest.length))
                        actions.onDraft(rest)
                        actions.onOpenTerm()
                    }
                    else -> {
                        field = pickCommand(field, w, cmd.name)
                        actions.onDraft(field.text)
                    }
                }
            }
        }
        Lift(visible = state.state == SessionState.Working) {
            Box(block) { WorkingRow(state.workingSinceMillis, enabled, actions.onInterrupt, state.activity) }
        }
        // Where 「工作中」 sits: the session waits on something only the terminal can show.
        Lift(visible = state.unknownDialog && state.pending == null) {
            Box(block) { BlockedRow(enabled, actions.onOpenTerm) }
        }
        Lift(visible = state.attachments.isNotEmpty()) {
            Row(
                block.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = sp.sp1),
                horizontalArrangement = Arrangement.spacedBy(sp.sp2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.attachments.forEachIndexed { i, a ->
                    val bitmap = rememberChatImage(a) { a.jpeg }
                    val press = remember { MutableInteractionSource() }
                    Box(Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)).background(c.surface)) {
                        if (bitmap != null) Image(bitmap, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .pressShrink(press)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                                .clickable(press, PressIndication(androidx.compose.ui.graphics.Color.White), role = Role.Button, onClickLabel = "移除") { actions.onRemoveImage(i) }
                                .semantics { contentDescription = "移除 ${a.name}" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Symbols.Close, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                if (state.attachments.size < ChatLimits.MAX_IMAGES) {
                    val press = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .pressShrink(press)
                            .size(60.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (card) c.card else c.surface)
                            .clickable(press, PressIndication(c.fg2), role = Role.Button, onClickLabel = "再添加图片", onClick = onAttach),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Symbols.Add, contentDescription = "再添加图片", tint = c.fg2) }
                }
                Spacer(Modifier.weight(1f))
                Text("${state.attachments.size} / ${ChatLimits.MAX_IMAGES}", fontSize = Clauderc.fontSizes.caption, color = c.fg3, modifier = Modifier.padding(start = sp.sp2))
            }
        }
        var shownError by remember { mutableStateOf("") }
        state.attachError?.let { shownError = it }
        Lift(visible = state.attachError != null) {
            Text(shownError, block.fillMaxWidth().padding(horizontal = sp.sp2), fontSize = Clauderc.fontSizes.caption, color = c.danger, lineHeight = 1.5.em)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    when (ui) {
                        UiStyle.Sticker -> Modifier.sticker(c.float, c.ink, 28.dp)
                        UiStyle.Grid -> Modifier.clip(RoundedCornerShape(6.dp)).background(c.surface)
                        UiStyle.Clear -> Modifier.clip(RoundedCornerShape(28.dp)).glassPane(LocalGlassSource.current)
                    },
                )
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(sp.sp1),
            verticalAlignment = Alignment.Bottom,
        ) {
            val attachPress = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .pressShrink(attachPress)
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(attachPress, PressIndication(c.fg2), enabled = enabled, role = Role.Button, onClick = onAttach),
                contentAlignment = Alignment.Center,
            ) { Icon(Symbols.Image, contentDescription = "添加图片", tint = if (enabled) c.fg2 else c.fg3) }
            BasicTextField(
                value = field,
                onValueChange = {
                    if (it.text != field.text) actions.onDraft(it.text)
                    field = it
                },
                enabled = enabled,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 12.dp).testTag("composer"),
                textStyle = TextStyle(color = c.fg, fontSize = Clauderc.fontSizes.body, lineHeight = 1.4.em),
                cursorBrush = SolidColor(c.accent),
                maxLines = 5,
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                when {
                                    !enabled -> "离线，不能发送"
                                    state.state == SessionState.Stopped -> "发消息会先唤醒会话"
                                    else -> "发消息"
                                },
                                color = c.fg3,
                                fontSize = Clauderc.fontSizes.body,
                            )
                        }
                        inner()
                    }
                },
            )
            val canSend = enabled && !state.sending && (text.isNotBlank() || state.attachments.isNotEmpty())
            val sendPress = remember { MutableInteractionSource() }
            val sendFill = when {
                canSend && card -> c.accent
                canSend -> c.fg
                card -> c.surface2
                else -> c.surface2
            }
            Box(
                Modifier
                    .pressShrink(sendPress)
                    .size(44.dp)
                    .then(
                        when (ui) {
                            UiStyle.Sticker -> Modifier.sticker(sendFill, if (canSend) c.ink else c.fg3, 22.dp, drop = if (canSend) PushDepth else 0.dp, source = sendPress)
                            UiStyle.Grid -> Modifier.clip(RoundedCornerShape(4.dp)).background(sendFill)
                            UiStyle.Clear -> Modifier.clip(CircleShape).background(sendFill).then(if (canSend) Modifier.topHighlight(Color.White.copy(alpha = 0.22f)) else Modifier)
                        },
                    )
                    .clickable(sendPress, PressIndication(if (card) c.onAccent else c.bg), enabled = canSend, role = Role.Button, onClickLabel = "发送") {
                        when {
                            isModelCommand(text) -> {
                                field = TextFieldValue()
                                actions.onDraft("")
                                onModels()
                            }
                            actions.onSend(text) -> {
                                haptic.tap()
                                field = TextFieldValue()
                                if (isPanelCommand(state.commands, text)) actions.onOpenTerm()
                            }
                        }
                    }
                    .semantics { contentDescription = "发送" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Symbols.ArrowUpward, contentDescription = null, tint = if (!canSend) c.fg3 else if (card) c.onAccent else c.bg, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** Paints [color] behind a block that rises over the messages, out over [sides] of padding on either side. */
private fun Modifier.coverBehind(color: Color, sides: Dp) = drawBehind {
    val s = sides.toPx()
    drawRect(color, topLeft = Offset(-s, 0f), size = Size(size.width + 2 * s, size.height))
}

/** Takes no room: the content sits over the top edge, its bottom [overlap] pixels below it. */
private fun Modifier.floatAbove(overlap: Int) = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    layout(p.width, 0) { p.place(0, overlap - p.height) }
}

/**
 * Claude Code's status line, [activity], as the computer's screen shows it: the turning star, the verb with
 * a highlight passing over it, the time and tokens in grey. Without it, 「工作中 · N 秒」 with the star's room
 * left blank. 「中断」 sits at the end; a subagent's page has no [onInterrupt].
 */
@Composable
internal fun WorkingRow(sinceMillis: Long?, enabled: Boolean, onInterrupt: (() -> Unit)?, activity: String? = null) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    // 清玻 and 贴纸 float the line as a pill: glass, or an ink-outlined sticker.
    val pill = when (Clauderc.style) {
        UiStyle.Clear -> Modifier.heightIn(min = 40.dp).clip(CircleShape).glassPane(LocalGlassSource.current).padding(start = sp.sp4, end = sp.sp1)
        UiStyle.Sticker -> Modifier.heightIn(min = 40.dp).sticker(c.float, c.ink, 999.dp).padding(start = sp.sp4, end = sp.sp1)
        UiStyle.Grid -> Modifier.padding(start = sp.sp3, end = sp.sp2)
    }
    Row(
        Modifier.fillMaxWidth().then(pill),
        horizontalArrangement = Arrangement.spacedBy(sp.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CliStar(visible = activity != null)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (activity != null) {
                val (verb, detail) = remember(activity) { splitActivity(activity) }
                ShimmerText(verb, c.fg2, c.fg)
                Text(detail, fontSize = Clauderc.fontSizes.label, color = c.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(sinceMillis) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(1000)
                    }
                }
                val seconds = sinceMillis?.let { ((now - it) / 1000).coerceAtLeast(0) }
                Text(if (seconds != null) "工作中 · ${elapsed(seconds)}" else "工作中", fontSize = Clauderc.fontSizes.label, color = c.fg2, maxLines = 1)
            }
        }
        if (onInterrupt != null) TextAction("中断", onClick = onInterrupt, tone = TextTone.Ink, enabled = enabled)
    }
}

/** The status line's verb, up to its 「…」, and the rest: 「Whirring…」 and 「 (20s · ↓ 2.4k tokens)」. */
internal fun splitActivity(activity: String): Pair<String, String> {
    val cut = activity.indexOf('…').takeIf { it >= 0 }?.plus(1) ?: activity.indexOf(" (").takeIf { it >= 0 } ?: activity.length
    return activity.substring(0, cut) to activity.substring(cut)
}

/** Claude Code's star, one glyph every 120ms, back and forth (demo.html SPIN_FRAMES). Text presentation, never emoji. */
private val StarFrames = listOf("·", "✢", "✳", "✶", "✻", "✽", "✻", "✶", "✳", "✢").map { it + "︎" }
private const val STAR_FRAME_MILLIS = 120

/**
 * The star in its 14dp room, blank when not [visible]. The glyphs are laid out once and the frame is
 * picked while drawing off the shared loop clock, so the turning never recomposes or lays out the row,
 * and it redraws only when the glyph changes, not at every step of the clock.
 */
@Composable
private fun CliStar(visible: Boolean) {
    val c = Clauderc.colors
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = c.accent, fontSize = 15.sp)
    val frames = remember(measurer, style) { StarFrames.map { measurer.measure(it, style) } }
    Box(Modifier.size(14.dp, 20.dp).testTag("cliStar")) {
        if (visible) {
            val clock = LocalLoopClock.current
            clock.Use()
            val frame by remember(clock) { derivedStateOf { ((clock.millis / STAR_FRAME_MILLIS) % frames.size).toInt() } }
            Canvas(Modifier.matchParentSize()) {
                val f = frames[frame]
                drawText(f, topLeft = Offset((size.width - f.size.width) / 2, (size.height - f.size.height) / 2))
            }
        }
    }
}

/**
 * [verb] in [base] with a band of [light] sweeping over it every 2s, left to right, as over the CLI's verb
 * (demo.html .w-verb: a gradient three times as wide as the text, light at its middle 20%, sliding from its
 * right end to its left). The band is drawn once and only its layer moves, off the shared loop clock, so a
 * frame neither records nor lays out anything again.
 */
@Composable
private fun ShimmerText(verb: String, base: Color, light: Color) {
    val clock = LocalLoopClock.current
    clock.Use()
    Box(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        Text(verb, fontSize = Clauderc.fontSizes.label, color = base, maxLines = 1, softWrap = false)
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    // The band is 0.6 of the text wide; its middle runs from half a width before the text to half after.
                    val t = (clock.millis % SHIMMER_MILLIS) / SHIMMER_MILLIS.toFloat()
                    translationX = (2 * t - 0.8f) * size.width
                }
                .drawBehind {
                    val w = size.width * 0.6f
                    drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.5f to light, 1f to Color.Transparent, endX = w), size = Size(w, size.height), blendMode = BlendMode.SrcAtop)
                },
        )
    }
}

private const val SHIMMER_MILLIS = 2000

/** The session is blocked on a panel or dialog the agent does not know: the terminal shows it. */
@Composable
private fun BlockedRow(enabled: Boolean, onOpenTerm: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = Clauderc.spacing.sp3, end = Clauderc.spacing.sp2).testTag("blockedRow"),
        horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(Glyph.Need)
        Text("会话停在一个对话框上", fontSize = Clauderc.fontSizes.label, color = Clauderc.colors.fg, modifier = Modifier.weight(1f))
        TextAction("打开终端", onClick = onOpenTerm, enabled = enabled)
    }
}

private fun elapsed(seconds: Long) = if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分 ${seconds % 60} 秒"

@Composable
private fun SlashPopup(hits: List<Command>, query: String, onPick: (Command) -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(Clauderc.radii.md))
            .clip(RoundedCornerShape(Clauderc.radii.md))
            .background(c.sheet)
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = sp.sp2)
            .testTag("slashPopup"),
    ) {
        if (hits.isEmpty()) {
            Text("没有匹配的命令", Modifier.padding(horizontal = sp.sp4, vertical = 10.dp), fontSize = Clauderc.fontSizes.label, color = c.fg3)
        }
        hits.forEach { cmd ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(cmd) }.padding(horizontal = sp.sp4, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(sp.sp3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildAnnotatedString {
                        append("/")
                        val at = if (query.isEmpty()) -1 else cmd.name.lowercase().indexOf(query.lowercase())
                        if (at < 0) append(cmd.name) else {
                            append(cmd.name.substring(0, at))
                            withStyle(SpanStyle(color = c.accent)) { append(cmd.name.substring(at, at + query.length)) }
                            append(cmd.name.substring(at + query.length))
                        }
                    },
                    fontFamily = MonoFamily,
                    fontSize = Clauderc.fontSizes.mono,
                    color = c.fg,
                )
                Text(cmd.description, Modifier.weight(1f), fontSize = Clauderc.fontSizes.caption, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // A panel command is sent as is and opens the terminal.
                if (cmd.panel == true) Icon(Symbols.Terminal, contentDescription = "会打开终端", tint = c.fg3, modifier = Modifier.size(16.dp))
            }
        }
    }
}

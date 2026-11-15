package clauderc.app.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import clauderc.app.protocol.ToolCall
import clauderc.app.sessions.display
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
import clauderc.app.ui.components.isCardStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.Lift
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.PressIndication
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.pressShrink
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
)

private enum class ChatSheet { Attach, Model, Mode }

@Composable
fun ChatScreen(state: ChatUiState, actions: ChatActions, snack: SnackState = remember { SnackState() }) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    var sheet by remember { mutableStateOf<ChatSheet?>(null) }
    val title = state.session?.let(::display) ?: "会话"

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            AppBar(
                navigation = { SymbolButton(Symbols.ArrowBack, "返回", onClick = actions.onBack) },
                title = {
                    BarTitle(title) {
                        BarSubButton(modelLabel(state.model).ifEmpty { "模型" }, onClick = { actions.onLoadModels(); sheet = ChatSheet.Model }, enabled = state.online)
                        BarSubButton(permissionModeLabel(state.permissionMode), onClick = { sheet = ChatSheet.Mode }, enabled = state.online)
                    }
                },
                actions = {
                    SymbolButton(Symbols.AccountTree, "打开 git", onClick = { state.session?.cwd?.let(actions.onOpenGit) }, enabled = state.online && state.session != null)
                },
            )
            Lift(visible = !state.online, fromTop = true) {
                Row(
                    Modifier.fillMaxWidth().background(if (isCardStyle) c.card else c.surface).padding(horizontal = sp.page, vertical = sp.sp3).testTag("offlineLine"),
                    horizontalArrangement = Arrangement.spacedBy(sp.sp3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Symbols.CloudOff, contentDescription = null, tint = c.fg3, modifier = Modifier.size(20.dp))
                    Text(state.offlineText, fontSize = Clauderc.fontSizes.label, color = c.fg2)
                }
            }
            MessageList(state, actions.onLoadOlder, Modifier.weight(1f))
            // The panel comes up from the bottom edge over the composer, and goes back down.
            AnimatedContent(
                targetState = state.pending,
                contentKey = { it?.toolUseId },
                transitionSpec = {
                    if (targetState != null) (Motion.panelIn + fadeIn(Motion.enter())).togetherWith(fadeOut(Motion.exit()))
                    else fadeIn(Motion.enter()).togetherWith(Motion.panelOut + fadeOut(Motion.exit()))
                },
                label = "panel",
            ) { pending ->
                if (pending != null) {
                    Box(Modifier.navigationBarsPadding().testTag("pendingPanel")) {
                        PendingPanel(pending, enabled = state.online, onAllow = actions.onAllow, onDeny = actions.onDeny, onAnswer = actions.onAnswer)
                    }
                } else {
                    Composer(state, actions, onAttach = { sheet = ChatSheet.Attach })
                }
            }
        }
        SnackHost(snack, Modifier.align(Alignment.BottomCenter), bottom = 84.dp)
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
private fun MessageList(state: ChatUiState, onLoadOlder: () -> Unit, modifier: Modifier) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val list = rememberLazyListState()
    val reversed = state.items.asReversed()
    // The list is drawn bottom-up: the newest item is index 0, so the first page opens at the bottom
    // and older pages are appended at the top without moving what the user is reading.
    LaunchedEffect(list, state.hasMore) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && total > 0 && last >= total - 3) onLoadOlder() }
    }
    LaunchedEffect(state.items.lastOrNull()?.seq, state.outgoing.lastOrNull()?.id) {
        if (list.firstVisibleItemIndex <= 2) list.animateScrollToItem(0)
    }
    LazyColumn(
        modifier.fillMaxWidth().testTag("chatList"),
        state = list,
        reverseLayout = true,
        contentPadding = PaddingValues(start = sp.sp4, end = sp.sp4, top = sp.sp4, bottom = sp.sp5),
        verticalArrangement = Arrangement.spacedBy(sp.sp2, Alignment.Bottom),
    ) {
        val note = when {
            state.waking -> "正在唤醒会话"
            else -> state.notice
        }
        if (note != null) {
            item(key = "notice") { Note(note, spinner = state.waking) }
        }
        items(state.outgoing.asReversed(), key = { "out-${it.id}" }) { out ->
            Box(Modifier.animateItem(fadeInSpec = Motion.enter(), placementSpec = Motion.enter(), fadeOutSpec = Motion.exit())) { OutgoingBubble(out) }
        }
        items(reversed, key = { it.seq }) { item ->
            Box(Modifier.animateItem(fadeInSpec = Motion.enter(), placementSpec = Motion.enter(), fadeOutSpec = Motion.exit())) {
                ChatItemView(item, working = state.state == SessionState.Working)
            }
        }
        if (state.hasMore || state.loadingOlder) {
            item(key = "older") {
                Box(Modifier.fillMaxWidth().padding(top = sp.sp1, bottom = sp.sp2), contentAlignment = Alignment.Center) {
                    Spinner(size = 14.dp, color = c.fg3)
                }
            }
        }
        if (state.loading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(sp.sp8), contentAlignment = Alignment.Center) { Spinner(size = 16.dp, color = c.fg3) }
            }
        }
    }
}

@Composable
private fun ChatItemView(item: ChatItem, working: Boolean) {
    when (item.kind) {
        ItemKind.USER -> UserBubble(item)
        ItemKind.ASSISTANT -> AssistantBubble(item.text.orEmpty())
        ItemKind.TOOL -> item.tool?.let { ToolRow(it, working) }
        ItemKind.INTERRUPTED -> Note("已中断")
    }
}

/** A short centered line in the message flow, like Claude Code's 「Interrupted」. */
@Composable
private fun Note(text: String, spinner: Boolean = false) {
    val sp = Clauderc.spacing
    Row(Modifier.fillMaxWidth().padding(vertical = sp.sp2), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (spinner) Spinner(size = 12.dp, color = Clauderc.colors.fg3)
        Text(text, Modifier.padding(start = if (spinner) sp.sp2 else 0.dp), fontSize = Clauderc.fontSizes.caption, color = Clauderc.colors.fg3)
    }
}

@Composable
private fun UserBubble(item: ChatItem) {
    val images = item.images.orEmpty()
    UserBubbleLayout(item.text.orEmpty(), images.size, faded = false) { i ->
        val data = images[i].data
        ChatPicture(rememberChatImage(data) { Base64.getDecoder().decode(data) })
    }
}

/** A message on its way: faded until the transcript shows it. */
@Composable
private fun OutgoingBubble(out: Outgoing) {
    UserBubbleLayout(out.text, out.attachments.size, faded = true) { i ->
        val a = out.attachments[i]
        ChatPicture(rememberChatImage(a) { a.jpeg })
    }
}

@Composable
private fun UserBubbleLayout(text: String, pictures: Int, faded: Boolean, picture: @Composable (Int) -> Unit) {
    val c = Clauderc.colors
    // The card style speaks in its accent; the minimal one in black on white.
    val card = isCardStyle
    Box(Modifier.fillMaxWidth().padding(top = Clauderc.spacing.sp2).alpha(if (faded) 0.5f else 1f), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.fillMaxWidth(0.82f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pictures > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(pictures) { picture(it) } }
            }
            if (text.isNotEmpty()) {
                Text(
                    text,
                    modifier = Modifier.clip(RoundedCornerShape(if (card) 20.dp else 22.dp)).background(if (card) c.accent else c.fg).padding(horizontal = 16.dp, vertical = 10.dp),
                    color = if (card) c.onAccent else c.bg,
                    fontSize = 15.sp,
                    lineHeight = 1.55.em,
                )
            }
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

@Composable
private fun AssistantBubble(text: String) {
    val c = Clauderc.colors
    val card = isCardStyle
    Box(Modifier.fillMaxWidth(0.82f)) {
        Text(
            richText(text, c.fg),
            modifier = Modifier.clip(RoundedCornerShape(if (card) 20.dp else 22.dp)).background(if (card) c.card else c.surface).padding(horizontal = 16.dp, vertical = 10.dp),
            color = c.fg,
            fontSize = 15.sp,
            lineHeight = 1.55.em,
        )
    }
}

@Composable
private fun ToolRow(tool: ToolCall, working: Boolean) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    var open by rememberSaveable(tool.toolUseId) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, Motion.turn(), label = "chevron")
    val (verb, arg) = splitSummary(tool)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Clauderc.radii.sm))
                .clickable(role = Role.Button, onClickLabel = if (open) "收起" else "展开") { open = !open }
                .padding(horizontal = sp.sp2, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(sp.sp2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (verb != null) Text(verb, fontSize = 13.sp, color = c.fg3)
            Text(arg, Modifier.weight(1f), fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                tool.result == null && working -> Spinner(size = 12.dp, color = c.fg3)
                isRefusal(tool) -> Text("已拒绝", fontSize = Clauderc.fontSizes.caption, color = c.danger)
                tool.result?.isError == true -> Text("出错", fontSize = Clauderc.fontSizes.caption, color = c.danger)
            }
            Icon(Symbols.ChevronRight, contentDescription = null, tint = c.fg3, modifier = Modifier.size(18.dp).rotate(turn))
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(Motion.enter()) + fadeIn(Motion.enter()),
            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
        ) {
            Text(
                buildAnnotatedString {
                    toolDetail(tool).forEachIndexed { i, (k, v) ->
                        if (i > 0) append("\n")
                        withStyle(SpanStyle(color = c.fg3)) { append(k) }
                        append("\n")
                        withStyle(SpanStyle(color = c.fg)) { append(v) }
                    }
                },
                modifier = Modifier
                    .padding(start = sp.sp2, end = sp.sp2, top = 2.dp, bottom = sp.sp2)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Clauderc.radii.sm))
                    .background(if (isCardStyle) c.card else c.surface)
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = sp.sp4, vertical = sp.sp3),
                fontFamily = MonoFamily,
                fontSize = Clauderc.fontSizes.monoSm,
                lineHeight = 1.6.em,
                color = c.fg2,
            )
        }
    }
}

@Composable
private fun Composer(state: ChatUiState, actions: ChatActions, onAttach: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    // The card style floats a card on its grey page and sends with the accent.
    val card = isCardStyle
    var text by rememberSaveable { mutableStateOf("") }
    // A failed send hands its text back, unless something new was typed meanwhile.
    LaunchedEffect(state.restore) { state.restore?.let { if (text.isBlank()) text = it.text } }
    val hits = filterCommands(state.commands, text)
    val enabled = state.online
    Column(
        Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(start = sp.sp3, end = sp.sp3, top = sp.sp2, bottom = sp.sp3),
        verticalArrangement = Arrangement.spacedBy(sp.sp2),
    ) {
        // Keep the last matches while the popup leaves.
        var shownHits by remember { mutableStateOf(emptyList<Command>()) }
        if (hits != null) shownHits = hits
        Lift(visible = hits != null && enabled) {
            SlashPopup(shownHits, query = text.drop(1)) { cmd -> text = "/${cmd.name} " }
        }
        Lift(visible = state.state == SessionState.Working) {
            WorkingRow(state.workingSinceMillis, enabled, actions.onInterrupt)
        }
        Lift(visible = state.attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = sp.sp1),
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
            Text(shownError, Modifier.padding(horizontal = sp.sp2), fontSize = Clauderc.fontSizes.caption, color = c.danger, lineHeight = 1.5.em)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(if (card) c.card else c.surface)
                .then(if (card) Modifier.border(0.5.dp, c.line, RoundedCornerShape(28.dp)) else Modifier)
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
                value = text,
                onValueChange = { text = it },
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
            Box(
                Modifier
                    .pressShrink(sendPress)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            canSend && card -> c.accent
                            canSend -> c.fg
                            card -> c.surface
                            else -> c.surface2
                        },
                    )
                    .clickable(sendPress, PressIndication(if (card) c.onAccent else c.bg), enabled = canSend, role = Role.Button, onClickLabel = "发送") { if (actions.onSend(text)) text = "" }
                    .semantics { contentDescription = "发送" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Symbols.ArrowUpward, contentDescription = null, tint = if (!canSend) c.fg3 else if (card) c.onAccent else c.bg, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun WorkingRow(sinceMillis: Long?, enabled: Boolean, onInterrupt: () -> Unit) {
    val c = Clauderc.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sinceMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val seconds = sinceMillis?.let { ((now - it) / 1000).coerceAtLeast(0) }
    Row(
        Modifier.fillMaxWidth().padding(start = Clauderc.spacing.sp3, end = Clauderc.spacing.sp2),
        horizontalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(Glyph.Work)
        Text(if (seconds != null) "工作中 · ${elapsed(seconds)}" else "工作中", fontSize = Clauderc.fontSizes.label, color = c.fg2, modifier = Modifier.weight(1f))
        TextAction("中断", onClick = onInterrupt, tone = TextTone.Ink, enabled = enabled)
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
            }
        }
    }
}

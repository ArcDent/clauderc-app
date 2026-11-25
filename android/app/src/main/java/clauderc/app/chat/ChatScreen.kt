package clauderc.app.chat

import clauderc.app.ui.components.topHighlight
import clauderc.app.ui.components.LocalGlassSource
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource
import clauderc.app.ui.components.glassPane
import clauderc.app.ui.theme.page
import clauderc.app.data.UiStyle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import clauderc.app.ui.components.ClearDropPanel
import clauderc.app.ui.components.PlateSlipPanel
import clauderc.app.ui.components.TileBottomSheet
import clauderc.app.ui.components.PlateSweep
import clauderc.app.ui.components.plateHardShadow
import clauderc.app.ui.components.plateSweepFill
import clauderc.app.ui.components.topLine
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
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.text.font.FontWeight
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
import clauderc.app.protocol.ToolImage
import clauderc.app.protocol.ToolResult
import clauderc.app.sessions.display
import clauderc.app.ui.baseName
import clauderc.app.ui.contextPercent
import clauderc.app.ui.components.AppBar
import clauderc.app.ui.components.BarSubButton
import clauderc.app.ui.components.BarTitle
import clauderc.app.ui.components.ClaudercSheet
import clauderc.app.ui.components.ClaudercDialog
import clauderc.app.ui.components.FieldError
import clauderc.app.ui.components.FieldLabel
import clauderc.app.ui.components.Glyph
import clauderc.app.ui.components.LocalMorphClose
import clauderc.app.ui.components.MorphOverlay
import clauderc.app.ui.components.PopMenu
import clauderc.app.ui.components.PopMenuItem
import clauderc.app.ui.components.Switch
import clauderc.app.ui.components.UnderlineField
import clauderc.app.ui.components.captureAnchor
import clauderc.app.ui.components.rememberAnchorBox
import androidx.compose.foundation.selection.toggleable
import clauderc.app.ui.components.MenuItem
import clauderc.app.ui.components.SheetHead
import clauderc.app.ui.components.SnackHost
import clauderc.app.ui.components.SnackState
import clauderc.app.ui.components.LocalSnack
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
import clauderc.app.ui.components.inkSurface
import clauderc.app.machines.PendingEntry
import clauderc.app.ui.components.plateBox
import clauderc.app.ui.components.plateStamp
import clauderc.app.ui.components.tileSurface
import clauderc.app.ui.theme.PressKind
import clauderc.app.ui.theme.stillLayer
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import java.util.Base64
import kotlinx.coroutines.delay

class ChatActions(
    val onBack: () -> Unit = {},
    val onOpenGit: (String) -> Unit = {},
    val onLoadOlder: () -> Unit = {},
    val onSend: (String) -> Boolean = { true },
    val onInterrupt: () -> Unit = {},
    /** The panel's answers carry the tool_use id of the request the panel was drawn for. */
    val onAllow: (String) -> Unit = {},
    val onDeny: (String) -> Unit = {},
    val onAnswer: (String, List<clauderc.app.protocol.Answer>) -> Unit = { _, _ -> },
    val onLoadModels: () -> Unit = {},
    val onSetModel: (ModelOption) -> Unit = {},
    val onSetMode: (String) -> Unit = {},
    val onPickImages: () -> Unit = {},
    val onTakePhoto: () -> Unit = {},
    val onRemoveImage: (Long) -> Unit = {},
    /** Asks for the whole result of a tool call whose text or diff came cut to 8 KB. */
    val onToolOutput: (String) -> Unit = {},
    /** Asks for a picture of a tool result, by its id, for the viewer. */
    val onImage: (String) -> Unit = {},
    /** Saves the picture in the viewer into the phone's gallery, from its bytes on the phone when it has them. */
    val onSaveImage: (ToolImage, ByteArray?) -> Unit = { _, _ -> },
    /** The viewer shows the picture by this id, or closed with null. */
    val onViewer: (String?) -> Unit = {},
    /** Opens the session's terminal view. */
    val onOpenTerm: () -> Unit = {},
    /** Opens the read-only page of a subagent from its Agent row. */
    val onOpenAgent: (Subagent) -> Unit = {},
    /** The composer's text was edited; kept as the session's draft. */
    val onDraft: (String) -> Unit = {},
    /** The composer has dealt with the [Restore] of this id: it goes, so it is not put back again. */
    val onRestored: (Long) -> Unit = {},
    /** Sends `/fork` with the instruction typed in the Fork dialog; false when it could not go out. */
    val onFork: (String) -> Boolean = { false },
    /** Starts a session in a new worktree by this name, with this first message, from the Worktree dialog. */
    val onCreateWorktree: (String, String) -> Unit = { _, _ -> },
    /** The Worktree dialog closed or its name was edited; its last failure goes. */
    val onClearWorktreeError: () -> Unit = {},
    /** Turns on or off the message sent once the usage limit is lifted. */
    val onAutoResume: (Boolean) -> Unit = {},
    /** The text sent once the usage limit is lifted was edited. */
    val onResumeText: (String) -> Unit = {},
    /** Rewinds to before the user's message, taking back one of RewindRestore. */
    val onRewind: (ChatItem, String) -> Unit = { _, _ -> },
    /** Jumps directly to the next pending session shown after answering the current one. */
    val onOpenNextPending: (PendingEntry) -> Unit = {},
)

/** The sheets and dialogs the chat page opens, one at a time. */
private enum class ChatSheet { Attach, Model, Mode, Control, Fork, Worktree }

/** The light tick when a message goes out or a permission is answered. */
private fun HapticFeedback.tap() = performHapticFeedback(HapticFeedbackType.VirtualKey)

@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatActions,
    snack: SnackState = remember { SnackState() },
    nextPending: PendingEntry? = null,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    // The KaTeX fonts load in the background; starting with the page, a formula that turns up is
    // measured in them from the first frame instead of once more when they arrive.
    rememberLatexMeasurer(remember { LatexConfig() })
    var sheet by rememberSaveable { mutableStateOf<ChatSheet?>(null) }
    var ctlAnchor by remember { mutableStateOf<Rect?>(null) }
    var attachAnchor by remember { mutableStateOf<Rect?>(null) }
    // The seq of the user's message whose rewind sheet is open; saved across rotation.
    var rewindingSeq by rememberSaveable { mutableStateOf<Long?>(null) }
    val rewinding = rewindingSeq?.let { seq -> state.items.firstOrNull { it.seq == seq } }
    var viewing by remember { mutableStateOf<Viewing?>(null) }
    val openImage: OpenImage = { v ->
        viewing = v
        val id = v.image.id
        if (v.local == null && id !in state.wholeImages && id !in state.fetchingImages) actions.onImage(id)
    }
    val openModels = { actions.onLoadModels(); sheet = ChatSheet.Model }
    val haptic = LocalHapticFeedback.current
    val title = state.session?.let(::display) ?: "会话"

    // 清玻 floats frosted glass over messages; 浮墨 floats its 3-piece top bar & 30dp bottom island over messages;
    // 素卡 and 印版 stack the bar and dock above/below the messages.
    val ui = Clauderc.style
    val floating = ui == UiStyle.Clear || ui == UiStyle.Ink
    val glass = if (ui == UiStyle.Clear) rememberHazeState() else null
    val bar: @Composable () -> Unit = {
        if (ui == UiStyle.Ink) {
            val pillAnchor = rememberAnchorBox()
            val branch = state.session?.branch?.takeIf { it.isNotBlank() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to c.bg,
                            0.65f to c.bg,
                            1f to Color.Transparent,
                        ),
                    )
                    .statusBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val backPress = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .pressShrink(backPress, PressKind.Icon)
                        .size(48.dp)
                        .shadow(if (c.isDark) 0.dp else 8.dp, CircleShape, ambientColor = c.scrim, spotColor = c.scrim)
                        .inkSurface(c.float, c.edge, 24.dp)
                        .clickable(backPress, PressIndication(c.fg), role = Role.Button, onClick = actions.onBack)
                        .semantics { contentDescription = "返回" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Symbols.ArrowBack, contentDescription = null, tint = c.fg, modifier = Modifier.size(20.dp)) }

                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val pillPress = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .captureAnchor(pillAnchor)
                            .widthIn(max = 246.dp)
                            .height(40.dp)
                            .pressShrink(pillPress, PressKind.Plain)
                            .shadow(if (c.isDark) 0.dp else 8.dp, RoundedCornerShape(20.dp), ambientColor = c.scrim, spotColor = c.scrim)
                            .inkSurface(c.float, c.edge, 20.dp)
                            .clickable(pillPress, PressIndication(c.fg), role = Role.Button, onClickLabel = "会话控制") {
                                ctlAnchor = pillAnchor.rect
                                actions.onLoadModels()
                                sheet = ChatSheet.Control
                            }
                            .padding(start = 14.dp, end = 10.dp)
                            .testTag("inkChatCtlPill"),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildAnnotatedString {
                                append(title)
                                if (branch != null) {
                                    withStyle(SpanStyle(color = c.fg3, fontWeight = FontWeight.Normal)) { append(" · $branch") }
                                }
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = c.fg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(Symbols.ExpandMore, contentDescription = null, tint = c.fg2, modifier = Modifier.size(18.dp))
                    }
                }

                val termEnabled = state.online && state.state != SessionState.Stopped
                val termPress = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .pressShrink(termPress, PressKind.Icon)
                        .size(48.dp)
                        .shadow(if (c.isDark) 0.dp else 8.dp, CircleShape, ambientColor = c.scrim, spotColor = c.scrim)
                        .inkSurface(c.float, c.edge, 24.dp)
                        .clickable(termPress, PressIndication(c.fg), enabled = termEnabled, role = Role.Button, onClick = actions.onOpenTerm)
                        .semantics { contentDescription = "打开终端" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Symbols.Terminal, contentDescription = null, tint = if (termEnabled) c.fg else c.fg3, modifier = Modifier.size(20.dp)) }
            }
        } else if (ui == UiStyle.Plate) {
            val dirName = state.session?.cwd?.let(::baseName).orEmpty()
            val branch = state.session?.branch?.takeIf { it.isNotBlank() }
            val ctxPct = contextPercent(state.session?.context)
            val meta = listOfNotNull(
                dirName.takeIf { it.isNotEmpty() },
                branch,
                ctxPct?.let { "上下文 $it%" },
            ).joinToString(" · ").ifEmpty {
                listOfNotNull(
                    modelLabel(state.model).takeIf { it.isNotEmpty() },
                    permissionModeLabel(state.permissionMode).takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
            }
            val targetFill = (ctxPct?.div(100f) ?: 1f).coerceIn(0f, 1f)
            val ctxRuleFill = remember { Animatable(0f) }
            LaunchedEffect(targetFill) {
                ctxRuleFill.animateTo(targetFill, tween(700, delayMillis = 140, easing = Motion.plateEasePress))
            }
            Column(Modifier.fillMaxWidth().background(c.bg).statusBarsPadding()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .padding(start = 2.dp, end = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SymbolButton(Symbols.ArrowBack, "返回", onClick = actions.onBack)
                    val ctlPress = remember { MutableInteractionSource() }
                    Column(
                        Modifier
                            .weight(1f)
                            .pressShrink(ctlPress, PressKind.Plain)
                            .clickable(ctlPress, LocalIndication.current, role = Role.Button, onClickLabel = "会话控制") {
                                actions.onLoadModels()
                                sheet = ChatSheet.Control
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag("plateChatCtl"),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = c.fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(Symbols.ExpandMore, contentDescription = null, tint = c.fg2, modifier = Modifier.size(18.dp))
                        }
                        if (meta.isNotEmpty()) {
                            Text(
                                meta,
                                fontFamily = MonoFamily,
                                fontSize = 11.5.sp,
                                color = c.fg2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    SymbolButton(Symbols.Terminal, "打开终端", onClick = actions.onOpenTerm, enabled = state.online && state.state != SessionState.Stopped)
                    var gitMenu by remember { mutableStateOf(false) }
                    Box {
                        SymbolButton(Symbols.AccountTree, "git", onClick = { gitMenu = true }, enabled = state.online && state.session != null)
                        val canAct = state.online && state.session != null
                        PopMenu(gitMenu && canAct, onDismiss = { gitMenu = false }) {
                            PopMenuItem("Fork", Symbols.CallSplit, onClick = { gitMenu = false; sheet = ChatSheet.Fork }, enabled = canAct, first = true)
                            PopMenuItem("Worktree", Symbols.AltRoute, onClick = { gitMenu = false; actions.onClearWorktreeError(); sheet = ChatSheet.Worktree }, enabled = canAct)
                            PopMenuItem("看 git", Symbols.AccountTree, onClick = { gitMenu = false; state.session?.cwd?.let(actions.onOpenGit) }, enabled = canAct)
                        }
                    }
                }
                // 印版 ctxrule: solid-ink context progress bar along the bottom edge of the chat top bar (demo.html .ctxrule).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(c.line)
                        .drawBehind {
                            val w = size.width * ctxRuleFill.value.coerceIn(0f, 1f)
                            if (w > 0f) drawRect(c.fg, Offset.Zero, Size(w, size.height))
                        }
                        .testTag("plateCtxRule"),
                )
            }
        } else {
            AppBar(
                navigation = { SymbolButton(Symbols.ArrowBack, "返回", onClick = actions.onBack) },
                title = {
                    if (ui == UiStyle.Clear) {
                        Column(Modifier.weight(1f).padding(start = sp.sp1), verticalArrangement = Arrangement.Center) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(role = Role.Button, onClickLabel = "会话控制") {
                                        actions.onLoadModels()
                                        sheet = ChatSheet.Control
                                    }
                                    .testTag("clearChatCtl"),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    title,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Icon(Symbols.ExpandMore, contentDescription = null, tint = c.fg3, modifier = Modifier.size(16.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(sp.sp3), verticalAlignment = Alignment.CenterVertically) {
                                BarSubButton(
                                    modelLabel(state.model).ifEmpty { "模型" }, onClick = openModels, enabled = state.online,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                BarSubButton(permissionModeLabel(state.permissionMode), onClick = { sheet = ChatSheet.Mode }, enabled = state.online)
                                contextPercent(state.session?.context)?.let {
                                    Text("上下文 $it%", fontSize = Clauderc.fontSizes.caption, color = c.fg2, maxLines = 1)
                                }
                            }
                        }
                    } else {
                        BarTitle(title) {
                            // Short of room, the model's name gives way: the mode and the context show whole.
                            BarSubButton(
                                modelLabel(state.model).ifEmpty { "模型" }, onClick = openModels, enabled = state.online,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            BarSubButton(permissionModeLabel(state.permissionMode), onClick = { sheet = ChatSheet.Mode }, enabled = state.online)
                            contextPercent(state.session?.context)?.let {
                                Text("上下文 $it%", fontSize = Clauderc.fontSizes.caption, color = c.fg2, maxLines = 1)
                            }
                        }
                    }
                },
                actions = {
                    if (ui == UiStyle.Tile) {
                        // 素卡 grouped right white pill (.pillgroup): Terminal + Git together.
                        var gitMenu by remember { mutableStateOf(false) }
                        val canAct = state.online && state.session != null
                        Row(
                            Modifier
                                .tileSurface(c.card, 999.dp)
                                .padding(horizontal = 4.dp)
                                .testTag("tileChatPillGroup"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SymbolButton(Symbols.Terminal, "打开终端", onClick = actions.onOpenTerm, enabled = state.online && state.state != SessionState.Stopped, small = true)
                            Box {
                                SymbolButton(Symbols.AccountTree, "git", onClick = { gitMenu = true }, enabled = canAct, small = true)
                                PopMenu(gitMenu && canAct, onDismiss = { gitMenu = false }) {
                                    PopMenuItem("Fork", Symbols.CallSplit, onClick = { gitMenu = false; sheet = ChatSheet.Fork }, enabled = canAct, first = true)
                                    PopMenuItem("Worktree", Symbols.AltRoute, onClick = { gitMenu = false; actions.onClearWorktreeError(); sheet = ChatSheet.Worktree }, enabled = canAct)
                                    PopMenuItem("看 git", Symbols.AccountTree, onClick = { gitMenu = false; state.session?.cwd?.let(actions.onOpenGit) }, enabled = canAct)
                                }
                            }
                        }
                    } else {
                        SymbolButton(Symbols.Terminal, "打开终端", onClick = actions.onOpenTerm, enabled = state.online && state.state != SessionState.Stopped)
                        // The git icon opens a small menu: fork this session, start one in a new worktree, or see git.
                        var gitMenu by remember { mutableStateOf(false) }
                        Box {
                            SymbolButton(Symbols.AccountTree, "git", onClick = { gitMenu = true }, enabled = state.online && state.session != null)
                            val canAct = state.online && state.session != null
                            PopMenu(gitMenu && canAct, onDismiss = { gitMenu = false }) {
                                PopMenuItem("Fork", Symbols.CallSplit, onClick = { gitMenu = false; sheet = ChatSheet.Fork }, enabled = canAct, first = true)
                                PopMenuItem("Worktree", Symbols.AltRoute, onClick = { gitMenu = false; actions.onClearWorktreeError(); sheet = ChatSheet.Worktree }, enabled = canAct)
                                PopMenuItem("看 git", Symbols.AccountTree, onClick = { gitMenu = false; state.session?.cwd?.let(actions.onOpenGit) }, enabled = canAct)
                            }
                        }
                    }
                },
            )
        }
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
    // The composer's text, with its cursor so a picked command can put it after itself, is kept here and not
    // in the composer, which leaves while the panel takes its place.
    val input = rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    // A failed send hands its text back, unless something new was typed meanwhile.
    // A rewind puts its message back whatever was typed: that message is what the conversation goes on from.
    // What comes back into the composer is saved as the draft too, as if typed. Taken or not, it is then let
    // go, so the page shown again after the terminal or a rebuild does not put it back over what is there.
    LaunchedEffect(state.restore) {
        val r = state.restore ?: return@LaunchedEffect
        val next = when {
            input.value.text.isBlank() || r.replace -> r.text
            r.append -> "${input.value.text}\n${r.text}"
            else -> null
        }
        if (next != null) {
            input.value = TextFieldValue(next, TextRange(next.length))
            actions.onDraft(next)
        }
        actions.onRestored(r.id)
    }
    // Track answering a pending request in this chat so we can offer a one-tap jump to the next pending session.
    var sawPending by rememberSaveable(state.session?.id) { mutableStateOf(false) }
    var showNextPrompt by rememberSaveable(state.session?.id) { mutableStateOf(false) }
    LaunchedEffect(state.pending) {
        if (state.pending != null) {
            sawPending = true
            showNextPrompt = false
        } else if (sawPending) {
            showNextPrompt = true
        }
    }
    var lastNext by remember { mutableStateOf<PendingEntry?>(null) }
    if (nextPending != null) lastNext = nextPending

    val dock: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (ui == UiStyle.Ink) {
                        Modifier.background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to c.bg))
                    } else {
                        Modifier
                    },
                ),
        ) {
            Lift(visible = showNextPrompt && state.pending == null && nextPending != null) {
                lastNext?.let { next ->
                    NextPendingBanner(
                        entry = next,
                        onOpen = {
                            showNextPrompt = false
                            actions.onOpenNextPending(next)
                        },
                        onDismiss = { showNextPrompt = false },
                    )
                }
            }
            if (ui == UiStyle.Ink || ui == UiStyle.Tile) {
                InkChatIsland(
                    state = state,
                    actions = actions,
                    input = input,
                    onAttach = { rect -> attachAnchor = rect; sheet = ChatSheet.Attach },
                    onModels = openModels,
                    onAllow = { id -> haptic.tap(); actions.onAllow(id) },
                    onDeny = { id -> haptic.tap(); actions.onDeny(id) },
                    onAnswer = { id, ans -> actions.onAnswer(id, ans) },
                )
            } else {
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
                                onAllow = { haptic.tap(); actions.onAllow(pending.toolUseId) },
                                onDeny = { haptic.tap(); actions.onDeny(pending.toolUseId) },
                                onAnswer = { actions.onAnswer(pending.toolUseId, it) },
                            )
                        }
                    } else {
                        Composer(state, actions, input, onAttach = { sheet = ChatSheet.Attach }, onModels = openModels)
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().page()) {
        if (floating) {
            var barHeight by remember { mutableIntStateOf(0) }
            var dockHeight by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().imePadding()) {
                Box(Modifier.fillMaxSize()) {
                    if (state.loading && state.items.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(top = with(density) { barHeight.toDp() }, bottom = with(density) { dockHeight.toDp() }), contentAlignment = Alignment.Center) {
                            Spinner(color = c.fg3)
                        }
                    } else {
                        MessageList(
                            state, actions, openImage,
                            Modifier.fillMaxSize().then(if (glass != null) Modifier.hazeSource(glass) else Modifier),
                            padTop = with(density) { barHeight.toDp() },
                            padBottom = with(density) { dockHeight.toDp() },
                            snack = snack,
                            onRewind = { rewindingSeq = it.seq },
                        )
                    }
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().onSizeChanged { dockHeight = it.height }) {
                        CompositionLocalProvider(LocalGlassSource provides glass) { dock() }
                    }
                }
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .onSizeChanged { barHeight = it.height }
                        .then(if (ui == UiStyle.Clear) Modifier.glassPane(glass, rimTop = false) else Modifier),
                ) { bar() }
            }
        } else {
            Column(Modifier.fillMaxSize().imePadding()) {
                bar()
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.loading && state.items.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Spinner(color = c.fg3) }
                    } else {
                        MessageList(state, actions, openImage, Modifier.weight(1f), snack = snack, onRewind = { rewindingSeq = it.seq })
                    }
                    dock()
                }
            }
        }
        viewing?.let { v -> ChatImageViewer(v, state, actions, onClosed = { viewing = null }) }
        // Drawn after the viewer, so a snackbar shows over it.
        SnackHost(snack, Modifier.align(Alignment.BottomCenter), bottom = if (viewing != null) 24.dp else 84.dp, onCamera = viewing != null)
    }

    rewinding?.let { item ->
        val files = remember(item, state.items) { filesChangedSince(state.items, item) }
        RewindSheet(item, files, onDismiss = { rewindingSeq = null }) { mode ->
            rewindingSeq = null
            actions.onRewind(item, mode)
        }
    }
    when (sheet) {
        ChatSheet.Attach -> ClaudercSheet(onDismiss = { sheet = null }, anchor = attachAnchor) {
            SheetHead("添加图片", "单张不超过 10 MB，压缩后合计不超过 ${ChatLimits.MAX_IMAGES_TOTAL_BYTES shr 20} MB")
            MenuItem("拍照", onClick = { sheet = null; actions.onTakePhoto() }, icon = Symbols.PhotoCamera)
            MenuItem("从相册选择", onClick = { sheet = null; actions.onPickImages() }, icon = Symbols.Image)
        }
        ChatSheet.Control -> when (ui) {
            UiStyle.Plate -> PlateChatControlSlip(
                title = title,
                state = state,
                onDismiss = { sheet = null },
                onSetModel = actions.onSetModel,
                onSetMode = actions.onSetMode,
                onOpenGit = { state.session?.cwd?.let(actions.onOpenGit) },
                onOpenTerm = actions.onOpenTerm,
                onFork = { sheet = ChatSheet.Fork },
                onWorktree = { actions.onClearWorktreeError(); sheet = ChatSheet.Worktree },
            )
            UiStyle.Clear -> ClearChatControlDrop(
                title = title,
                state = state,
                onDismiss = { sheet = null },
                onSetModel = actions.onSetModel,
                onSetMode = actions.onSetMode,
                onOpenGit = { state.session?.cwd?.let(actions.onOpenGit) },
                onOpenTerm = actions.onOpenTerm,
                onFork = { sheet = ChatSheet.Fork },
                onWorktree = { actions.onClearWorktreeError(); sheet = ChatSheet.Worktree },
            )
            else -> InkChatControlPanel(
                title = title,
                state = state,
                anchor = ctlAnchor,
                onDismiss = { sheet = null },
                onSetModel = actions.onSetModel,
                onSetMode = actions.onSetMode,
                onOpenGit = { state.session?.cwd?.let(actions.onOpenGit) },
                onOpenTerm = actions.onOpenTerm,
                onFork = { sheet = ChatSheet.Fork },
                onWorktree = { actions.onClearWorktreeError(); sheet = ChatSheet.Worktree },
            )
        }
        ChatSheet.Model -> ClaudercSheet(onDismiss = { sheet = null }, anchor = ctlAnchor, fromTop = ui == UiStyle.Ink) {
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
        ChatSheet.Fork -> ForkDialog(
            onDismiss = { sheet = null },
            onFork = { instruction ->
                if (actions.onFork(instruction)) {
                    haptic.tap()
                    sheet = null
                    snack.show("已发出 /fork，新会话会挂在会话页「$title」下面")
                } else {
                    snack.show("现在发不出去，稍后再试")
                }
            },
        )
        ChatSheet.Worktree -> {
            // Closes once session.create succeeded; the page then moves on to the new session.
            var asked by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(state.creatingWorktree) {
                if (state.creatingWorktree) asked = true else if (asked && state.worktreeError == null) sheet = null
            }
            WorktreeDialog(
                dir = state.session?.let { it.dir ?: it.cwd }.orEmpty(),
                busy = state.creatingWorktree,
                error = state.worktreeError,
                enabled = state.online,
                onDismiss = { if (!state.creatingWorktree) { actions.onClearWorktreeError(); sheet = null } },
                onEdit = actions.onClearWorktreeError,
                onCreate = actions.onCreateWorktree,
            )
        }
        null -> Unit
    }
}

/**
 * 清玻's unified chat control top-falling frosted glass panel (demo.html `A.openChatCtl` in `.drop.glass`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClearChatControlDrop(
    title: String,
    state: ChatUiState,
    onDismiss: () -> Unit,
    onSetModel: (ModelOption) -> Unit,
    onSetMode: (String) -> Unit,
    onOpenGit: () -> Unit,
    onOpenTerm: () -> Unit,
    onFork: () -> Unit,
    onWorktree: () -> Unit,
) {
    val c = Clauderc.colors
    val dirName = state.session?.cwd?.let(::baseName).orEmpty()
    val branch = state.session?.branch?.takeIf { it.isNotBlank() }
    val ctxPct = contextPercent(state.session?.context)
    val meta = listOfNotNull(
        dirName.takeIf { it.isNotEmpty() },
        branch,
        ctxPct?.let { "上下文 $it%" },
    ).joinToString(" · ")
    val canAct = state.online && state.session != null
    val canTerm = state.online && state.state != SessionState.Stopped

    ClearDropPanel(onDismiss = onDismiss, tag = "clearChatCtlDrop") {
        val close = LocalMorphClose.current
        fun dismissThen(action: () -> Unit) {
            if (close != null) close(action) else { onDismiss(); action() }
        }
        SheetHead(title, meta.ifEmpty { null })
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("模型", fontSize = 12.5.sp, color = c.fg3, fontWeight = FontWeight.Medium)
            val models = state.models
            when {
                models == null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val fallback = modelLabel(state.model)
                    if (fallback.isNotEmpty()) InkCtlChip(fallback, selected = true, enabled = false, onClick = {})
                    Spinner(size = 14.dp, color = c.fg3)
                }
                state.modelsError != null -> Text(state.modelsError, fontSize = 13.sp, color = c.fg2)
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    models.forEach { m ->
                        InkCtlChip(
                            label = m.label.ifEmpty { m.id },
                            selected = m.current,
                            enabled = state.online,
                            onClick = { if (!m.current) onSetModel(m) },
                        )
                    }
                }
            }
            Spacer(Modifier.size(2.dp))
            Text("权限模式", fontSize = 12.5.sp, color = c.fg3, fontWeight = FontWeight.Medium)
            val current = state.permissionMode
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.isNotEmpty() && current !in PermissionMode.switchable) {
                    InkCtlChip(permissionModeLabel(current), selected = true, enabled = false, onClick = {})
                }
                PermissionMode.switchable.forEach { mode ->
                    InkCtlChip(
                        label = permissionModeLabel(mode),
                        selected = mode == current,
                        enabled = state.online,
                        onClick = { if (mode != current) onSetMode(mode) },
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 4.dp), thickness = 1.dp, color = c.line)
        MenuItem("查看 git 仓库", onClick = { dismissThen(onOpenGit) }, icon = Symbols.AccountTree, supporting = dirName.ifEmpty { null }, enabled = canAct)
        MenuItem("打开终端视图", onClick = { dismissThen(onOpenTerm) }, icon = Symbols.Terminal, supporting = "实时 TUI", enabled = canTerm)
        MenuItem("Fork 新会话", onClick = { dismissThen(onFork) }, icon = Symbols.CallSplit, supporting = "从当前上下文分叉", enabled = canAct)
        MenuItem("新建 Worktree 会话", onClick = { dismissThen(onWorktree) }, icon = Symbols.AltRoute, supporting = "独立分支隔离改动", enabled = canAct)
    }
}

/**
 * 印版's unified chat control slip (demo.html `A.openChatCtl` in `.slip`):
 * opens as a left-printing letterpress slip with crisp square ink model chips, permission mode
 * chips, and 4 quick actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlateChatControlSlip(
    title: String,
    state: ChatUiState,
    onDismiss: () -> Unit,
    onSetModel: (ModelOption) -> Unit,
    onSetMode: (String) -> Unit,
    onOpenGit: () -> Unit,
    onOpenTerm: () -> Unit,
    onFork: () -> Unit,
    onWorktree: () -> Unit,
) {
    val c = Clauderc.colors
    val dirName = state.session?.cwd?.let(::baseName).orEmpty()
    val branch = state.session?.branch?.takeIf { it.isNotBlank() }
    val ctxPct = contextPercent(state.session?.context)
    val meta = listOfNotNull(
        dirName.takeIf { it.isNotEmpty() },
        branch,
        ctxPct?.let { "上下文 $it%" },
    ).joinToString(" · ")
    val canAct = state.online && state.session != null
    val canTerm = state.online && state.state != SessionState.Stopped

    PlateSlipPanel(onDismiss = onDismiss, tag = "plateChatCtlSlip") {
        val close = LocalMorphClose.current
        fun dismissThen(action: () -> Unit) {
            if (close != null) close(action) else { onDismiss(); action() }
        }
        SheetHead(title, meta.ifEmpty { null })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("模型", fontFamily = MonoFamily, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = c.fg2)
            val models = state.models
            when {
                models == null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val fallback = modelLabel(state.model)
                    if (fallback.isNotEmpty()) {
                        PlateCtlChip(fallback, selected = true, enabled = false, onClick = {})
                    }
                    Spinner(size = 14.dp, color = c.fg3)
                }
                state.modelsError != null -> Text(state.modelsError, fontSize = 13.sp, color = c.fg2)
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    models.forEach { m ->
                        PlateCtlChip(
                            label = m.label.ifEmpty { m.id },
                            selected = m.current,
                            enabled = state.online,
                            onClick = { if (!m.current) onSetModel(m) },
                        )
                    }
                }
            }
            Spacer(Modifier.size(2.dp))
            Text("权限模式", fontFamily = MonoFamily, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = c.fg2)
            val current = state.permissionMode
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.isNotEmpty() && current !in PermissionMode.switchable) {
                    PlateCtlChip(permissionModeLabel(current), selected = true, enabled = false, onClick = {})
                }
                PermissionMode.switchable.forEach { mode ->
                    PlateCtlChip(
                        label = permissionModeLabel(mode),
                        selected = mode == current,
                        enabled = state.online,
                        onClick = { if (mode != current) onSetMode(mode) },
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), thickness = 1.5.dp, color = c.fg)
        MenuItem("查看 git 仓库", onClick = { dismissThen(onOpenGit) }, icon = Symbols.AccountTree, supporting = dirName.ifEmpty { null }, enabled = canAct)
        MenuItem("打开终端视图", onClick = { dismissThen(onOpenTerm) }, icon = Symbols.Terminal, supporting = "实时 TUI", enabled = canTerm)
        MenuItem("Fork 新会话", onClick = { dismissThen(onFork) }, icon = Symbols.CallSplit, supporting = "从当前上下文分叉", enabled = canAct)
        MenuItem("新建 Worktree 会话", onClick = { dismissThen(onWorktree) }, icon = Symbols.AltRoute, supporting = "独立分支隔离改动", enabled = canAct)
    }
}

@Composable
private fun PlateCtlChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Clauderc.colors
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val sweep by animateFloatAsState(
        targetValue = if (!selected && enabled && pressed) 1f else 0f,
        animationSpec = tween(if (pressed) 220 else 160, easing = Motion.plateEasePress),
        label = "plateCtlChipSweep",
    )
    val fg = when {
        selected -> c.bg
        !enabled -> c.fg3
        else -> lerp(c.fg, c.bg, sweep)
    }
    Box(
        Modifier
            .pressShrink(press, PressKind.Plain)
            .background(if (selected) c.fg else c.bg, RectangleShape)
            .plateSweepFill(sweep, c.fg, PlateSweep.LeftToRight)
            .border(1.5.dp, if (enabled || selected) c.fg else c.fg3, RectangleShape)
            .clickable(press, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = MonoFamily,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.ExtraBold,
            color = fg,
            maxLines = 1,
        )
    }
}

/**
 * 浮墨's unified chat control panel (demo.html `A.openChatCtl`): blooms downward from the top
 * title/branch pill with session meta, model chips, permission mode chips, and 4 quick actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InkChatControlPanel(
    title: String,
    state: ChatUiState,
    anchor: Rect?,
    onDismiss: () -> Unit,
    onSetModel: (ModelOption) -> Unit,
    onSetMode: (String) -> Unit,
    onOpenGit: () -> Unit,
    onOpenTerm: () -> Unit,
    onFork: () -> Unit,
    onWorktree: () -> Unit,
) {
    val c = Clauderc.colors
    val dirName = state.session?.cwd?.let(::baseName).orEmpty()
    val branch = state.session?.branch?.takeIf { it.isNotBlank() }
    val ctxPct = contextPercent(state.session?.context)
    val meta = listOfNotNull(
        dirName.takeIf { it.isNotEmpty() },
        branch,
        ctxPct?.let { "上下文 $it%" },
    ).joinToString(" · ")
    val canAct = state.online && state.session != null
    val canTerm = state.online && state.state != SessionState.Stopped

    MorphOverlay(onDismiss = onDismiss, anchor = anchor, fromTop = true, tag = "inkChatCtlPanel") {
        val close = LocalMorphClose.current
        fun dismissThen(action: () -> Unit) {
            if (close != null) close(action) else { onDismiss(); action() }
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = c.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta.isNotEmpty()) {
                    Text(meta, fontSize = 13.sp, color = c.fg2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模型", fontSize = 12.5.sp, color = c.fg3, fontWeight = FontWeight.Medium)
                val models = state.models
                when {
                    models == null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val fallback = modelLabel(state.model)
                        if (fallback.isNotEmpty()) {
                            InkCtlChip(fallback, selected = true, enabled = false, onClick = {})
                        }
                        Spinner(size = 14.dp, color = c.fg3)
                    }
                    state.modelsError != null -> Text(state.modelsError, fontSize = 13.sp, color = c.fg2)
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        models.forEach { m ->
                            InkCtlChip(
                                label = m.label.ifEmpty { m.id },
                                selected = m.current,
                                enabled = state.online,
                                onClick = { if (!m.current) onSetModel(m) },
                            )
                        }
                    }
                }
                Spacer(Modifier.size(2.dp))
                Text("权限模式", fontSize = 12.5.sp, color = c.fg3, fontWeight = FontWeight.Medium)
                val current = state.permissionMode
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current.isNotEmpty() && current !in PermissionMode.switchable) {
                        InkCtlChip(permissionModeLabel(current), selected = true, enabled = false, onClick = {})
                    }
                    PermissionMode.switchable.forEach { mode ->
                        InkCtlChip(
                            label = permissionModeLabel(mode),
                            selected = mode == current,
                            enabled = state.online,
                            onClick = { if (mode != current) onSetMode(mode) },
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 6.dp), thickness = 1.dp, color = c.edge)
            Text("快捷操作", modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 4.dp), fontSize = 12.5.sp, color = c.fg3)
            InkCtlActionRow(Symbols.AccountTree, "查看 git 仓库", dirName, enabled = canAct) { dismissThen(onOpenGit) }
            InkCtlActionRow(Symbols.Terminal, "打开终端视图", "实时 TUI", enabled = canTerm) { dismissThen(onOpenTerm) }
            InkCtlActionRow(Symbols.CallSplit, "Fork 新会话", "从当前上下文分叉", enabled = canAct) { dismissThen(onFork) }
            InkCtlActionRow(Symbols.AltRoute, "新建 Worktree 会话", "独立分支隔离改动", enabled = canAct) { dismissThen(onWorktree) }
        }
    }
}

@Composable
private fun InkCtlChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Clauderc.colors
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) c.inverse else c.surface2)
            .then(if (selected) Modifier else Modifier.border(1.dp, c.edge, CircleShape))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                selected -> c.onInverse
                enabled -> c.fg
                else -> c.fg3
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun InkCtlActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = Clauderc.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) c.fg2 else c.fg3, modifier = Modifier.size(20.dp))
        Text(title, modifier = Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (enabled) c.fg else c.fg3)
        if (trailing.isNotEmpty()) {
            Text(trailing, fontSize = 13.sp, color = c.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Symbols.ChevronRight, contentDescription = null, tint = c.fg3, modifier = Modifier.size(18.dp))
    }
}

/**
 * 浮墨's unified 30dp floating bottom island (demo.html `.island`):
 * hosts tasks, working status, blocked/limit rows, and composer OR pending confirmation inside ONE
 * seamlessly resizing floating card with hairline dividers between active sections.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InkChatIsland(
    state: ChatUiState,
    actions: ChatActions,
    input: MutableState<TextFieldValue>,
    onAttach: (Rect?) -> Unit,
    onModels: () -> Unit,
    onAllow: (String) -> Unit,
    onDeny: (String) -> Unit,
    onAnswer: (String, List<clauderc.app.protocol.Answer>) -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val tile = ui == UiStyle.Tile
    val haptic = LocalHapticFeedback.current
    var field by input
    val text = field.text
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
    val attachAnchor = rememberAnchorBox()
    val divColor = if (tile) c.line else c.edge

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 14.dp),
    ) {
        var shown by remember { mutableStateOf(emptyList<Command>() to "") }
        if (hits != null && word != null) shown = hits to word.query
        val overlap = with(LocalDensity.current) { (sp.sp2 - 2.dp).roundToPx() }
        Lift(visible = hits != null && enabled && state.pending == null, modifier = Modifier.floatAbove(overlap)) {
            SlashPopup(shown.first, query = shown.second) { cmd ->
                val w = word ?: return@SlashPopup
                when {
                    cmd.name.equals("model", ignoreCase = true) -> {
                        field = TextFieldValue(text.removeRange(w.start, w.end), TextRange(w.start))
                        onModels()
                    }
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

        val trayRadius = if (tile) 28.dp else 30.dp
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (tile) {
                        Modifier
                            .shadow(if (c.isDark) 0.dp else 8.dp, RoundedCornerShape(trayRadius), ambientColor = c.scrim, spotColor = c.scrim)
                            .tileSurface(c.card, trayRadius)
                    } else {
                        Modifier
                            .shadow(if (c.isDark) 0.dp else 10.dp, RoundedCornerShape(trayRadius), ambientColor = c.scrim, spotColor = c.scrim)
                            .inkSurface(c.float, c.edge, trayRadius)
                    },
                )
                .animateContentSize(tween(260, easing = Motion.inkEase))
                .testTag(if (tile) "tileChatTray" else "inkChatIsland"),
        ) {
            var hasAbove = false
            if (state.tasks.isNotEmpty()) {
                TaskBar(state.tasks, inIsland = true)
                hasAbove = true
            }
            if (state.state == SessionState.Working) {
                if (hasAbove) HorizontalDivider(thickness = 1.dp, color = divColor)
                WorkingRow(state.workingSinceMillis, enabled, actions.onInterrupt, state.activity, inIsland = true)
                hasAbove = true
            }
            if (state.unknownDialog && state.pending == null && !state.rewinding) {
                if (hasAbove) HorizontalDivider(thickness = 1.dp, color = divColor)
                Box(Modifier.padding(vertical = 8.dp)) { BlockedRow(enabled, actions.onOpenTerm) }
                hasAbove = true
            }
            state.limit?.let { l ->
                if (hasAbove) HorizontalDivider(thickness = 1.dp, color = divColor)
                LimitRow(l, state.resumeText, enabled, actions.onResumeText, actions.onAutoResume, inIsland = true)
                hasAbove = true
            }
            if (hasAbove) HorizontalDivider(thickness = 1.dp, color = divColor)

            AnimatedContent(
                targetState = state.pending,
                contentAlignment = Alignment.BottomStart,
                contentKey = { it?.toolUseId },
                transitionSpec = {
                    val room = SizeTransform(clip = false) { _, _ -> Motion.enter() }
                    if (targetState != null) (Motion.panelIn + fadeIn(Motion.enter())).togetherWith(fadeOut(Motion.exit())).using(room)
                    else (fadeIn(Motion.enter()) togetherWith Motion.panelOut + fadeOut(Motion.exit())).using(room).apply { targetContentZIndex = -1f }
                },
                label = "inkIslandPanel",
            ) { pending ->
                if (pending != null) {
                    PendingPanel(
                        pending = pending,
                        enabled = enabled,
                        onAllow = { onAllow(pending.toolUseId) },
                        onDeny = { onDeny(pending.toolUseId) },
                        onAnswer = { onAnswer(pending.toolUseId, it) },
                        inIsland = true,
                    )
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        if (state.attachments.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(
                                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(sp.sp2),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    state.attachments.forEach { a ->
                                        androidx.compose.runtime.key(a.id) {
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
                                                        .background(Color.Black.copy(alpha = 0.55f))
                                                        .clickable(press, PressIndication(Color.White), role = Role.Button, onClickLabel = "移除") { actions.onRemoveImage(a.id) }
                                                        .semantics { contentDescription = "移除 ${a.name}" },
                                                    contentAlignment = Alignment.Center,
                                                ) { Icon(Symbols.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                                            }
                                        }
                                    }
                                    val press = remember { MutableInteractionSource() }
                                    Box(
                                        Modifier
                                            .pressShrink(press)
                                            .size(60.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(c.surface2)
                                            .border(1.dp, c.edge, RoundedCornerShape(12.dp))
                                            .clickable(press, PressIndication(c.fg2), role = Role.Button, onClickLabel = "再添加图片") { onAttach(attachAnchor.rect) },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Symbols.Add, contentDescription = "再添加图片", tint = c.fg2) }
                                }
                                Text(
                                    "%.1f / %d MB".format(state.attachments.bytes / 1048576.0, ChatLimits.MAX_IMAGES_TOTAL_BYTES shr 20),
                                    fontSize = Clauderc.fontSizes.caption,
                                    color = c.fg3,
                                    modifier = Modifier.padding(start = sp.sp2),
                                )
                            }
                        }
                        state.attachError?.let { err ->
                            Text(err, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), fontSize = Clauderc.fontSizes.caption, color = c.danger, lineHeight = 1.5.em)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            val attachPress = remember { MutableInteractionSource() }
                            Box(
                                Modifier
                                    .captureAnchor(attachAnchor)
                                    .pressShrink(attachPress, PressKind.Icon)
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(c.surface2)
                                    .clickable(attachPress, PressIndication(c.fg2), enabled = enabled, role = Role.Button) { onAttach(attachAnchor.rect) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Symbols.Image, contentDescription = "添加图片", tint = if (enabled) c.fg2 else c.fg3, modifier = Modifier.size(20.dp)) }
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
                            val canSend = enabled && !state.sending && !state.rewinding && (text.isNotBlank() || state.attachments.isNotEmpty())
                            val sendPress = remember { MutableInteractionSource() }
                            val sendFill = when {
                                !canSend -> c.surface2
                                tile -> c.accent
                                else -> c.inverse
                            }
                            val sendFg = when {
                                !canSend -> c.fg3
                                tile -> c.onAccent
                                else -> c.onInverse
                            }
                            Box(
                                Modifier
                                    .pressShrink(sendPress, PressKind.Send)
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(sendFill)
                                    .clickable(sendPress, PressIndication(sendFg), enabled = canSend, role = Role.Button, onClickLabel = "发送") {
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
                            ) { Icon(Symbols.ArrowUpward, contentDescription = null, tint = sendFg, modifier = Modifier.size(22.dp)) }
                        }
                    }
                }
            }
        }
    }
}

/** Fork: an optional instruction for the copy, which goes out as `/fork <instruction>`. */
@Composable
private fun ForkDialog(onDismiss: () -> Unit, onFork: (String) -> Unit) {
    var instruction by rememberSaveable { mutableStateOf("") }
    ClaudercDialog(
        onDismiss = onDismiss,
        title = "Fork 这个会话",
        text = "把到这里为止的对话复制成一个新的后台会话，你留在这里。",
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(Clauderc.spacing.sp1)) {
                FieldLabel("给它的指令")
                UnderlineField(instruction, { instruction = it }, Modifier.testTag("forkInstruction"), placeholder = "可以不填")
            }
        },
        buttons = {
            TextAction("取消", onClick = onDismiss)
            TextAction("Fork", onClick = { onFork(instruction) })
        },
    )
}

/**
 * Worktree: a new session in a new worktree of [dir] with this session's profile. The name comes filled in
 * as 「wt-」 and the month, day and time; one the agent would refuse is marked before anything is sent.
 */
@Composable
private fun WorktreeDialog(
    dir: String,
    busy: Boolean,
    error: String?,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    var name by rememberSaveable { mutableStateOf(worktreeName(java.time.LocalDateTime.now())) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var bad by rememberSaveable { mutableStateOf(false) }
    ClaudercDialog(
        onDismiss = onDismiss,
        title = "在新 worktree 里开会话",
        body = {
            Text(
                buildAnnotatedString {
                    append("在 ")
                    withStyle(SpanStyle(fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.monoSm)) { append(dir) }
                    append(" 下另开一个 worktree，用这个会话的档位启动。")
                },
                fontSize = Clauderc.fontSizes.label,
                lineHeight = 1.65.em,
                color = c.fg2,
            )
            Column(verticalArrangement = Arrangement.spacedBy(sp.sp1)) {
                FieldLabel("worktree 名")
                UnderlineField(
                    name,
                    { name = it; bad = false; onEdit() },
                    Modifier.testTag("worktreeName"),
                    mono = true,
                    error = bad,
                    enabled = !busy,
                )
                Text(
                    if (bad) WORKTREE_NAME_RULE else "只能用字母、数字、点、横线和下划线",
                    fontSize = Clauderc.fontSizes.caption,
                    color = if (bad) c.danger else c.fg3,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(sp.sp1)) {
                FieldLabel("第一句话")
                UnderlineField(prompt, { prompt = it }, Modifier.testTag("worktreePrompt"), placeholder = "可以不填", enabled = !busy)
            }
            if (error != null) FieldError(error)
        },
        buttons = {
            TextAction("取消", onClick = onDismiss, enabled = !busy)
            TextAction(
                if (busy) "正在新建" else "新建",
                onClick = {
                    val n = name.trim()
                    if (WORKTREE_NAME.matches(n)) onCreate(n, prompt) else bad = true
                },
                enabled = enabled,
                busy = busy,
            )
        },
    )
}

@Composable
internal fun MessageList(
    state: ChatUiState,
    actions: ChatActions,
    openImage: OpenImage,
    modifier: Modifier,
    padTop: Dp = 0.dp,
    padBottom: Dp = 0.dp,
    snack: SnackState? = null,
    /** Opens the rewind sheet for a user message slid open; null where nothing rewinds. */
    onRewind: ((ChatItem) -> Unit)? = null,
) {
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
    val autoLoadOlder by rememberUpdatedState(state.hasMore && !state.loadingOlder && !state.olderFailed && state.online)
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (autoLoadOlder && last != null && total > 0 && last >= total - 3) actions.onLoadOlder() }
    }
    val note = when {
        state.waking -> "正在唤醒会话"
        state.rewinding -> "正在回溯…"
        else -> state.notice
    }
    // A session at work or waiting on the user does not rewind, so its messages do not slide.
    val rewind = onRewind?.let { ask -> remember(ask) { RewindHub(ask) } }
    rewind?.enabled = state.online && state.state == SessionState.Idle && state.pending == null && !state.rewinding
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
    // The snackbar is where a copied link says so.
    CompositionLocalProvider(LocalChatScroll provides scroll, LocalSnack provides snack, LocalRewind provides rewind) {
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
            item(key = "notice") { Note(note, spinner = state.waking || state.rewinding) }
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
                if (state.olderFailed && !state.loadingOlder) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClick = actions.onLoadOlder)
                            .padding(top = sp.sp1, bottom = sp.sp2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("加载更早消息失败，点此重试", fontSize = Clauderc.fontSizes.caption, color = c.fg3)
                    }
                } else {
                    Box(Modifier.fillMaxWidth().padding(top = sp.sp1, bottom = sp.sp2), contentAlignment = Alignment.Center) {
                        Spinner(size = 14.dp, color = c.fg3)
                    }
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
        ItemKind.USER -> UserBubble(item, tools.open)
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

/**
 * The user's message. Its pictures open in the viewer from the bytes the phone has, so nothing is fetched; with
 * no file name they are titled by their format.
 */
@Composable
private fun UserBubble(item: ChatItem, onOpen: OpenImage) {
    val images = item.images.orEmpty()
    // The whole row slides left to lay bare the rewind button (ChatRewind.kt).
    RewindRow(item) { slid ->
        UserBubbleLayout(item.text.orEmpty(), images.size, faded = false, queued = item.queued == true, modifier = slid) { i ->
            val image = images[i]
            val bitmap = rememberChatImage(image.data) { Base64.getDecoder().decode(image.data) }
            ChatPicture(bitmap) { from ->
                if (image.id != null) {
                    val shown = ToolImage(
                        id = image.id,
                        mediaType = image.mediaType,
                        width = image.width ?: bitmap?.width,
                        height = image.height ?: bitmap?.height,
                        size = image.size ?: 0L,
                        thumb = image.data.ifEmpty { null },
                    )
                    onOpen(Viewing(shown, from, local = null))
                } else {
                    val bytes = Base64.getDecoder().decode(image.data)
                    val shown = ToolImage("msg:${item.uuid ?: item.seq}.$i", image.mediaType, width = bitmap?.width, height = bitmap?.height, size = bytes.size.toLong())
                    onOpen(Viewing(shown, from, local = bytes))
                }
            }
        }
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
private fun UserBubbleLayout(text: String, pictures: Int, faded: Boolean, queued: Boolean = false, modifier: Modifier = Modifier, picture: @Composable (Int) -> Unit) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val userFg = when (ui) {
        UiStyle.Tile -> c.onAccent
        UiStyle.Ink -> c.onInverse
        UiStyle.Plate, UiStyle.Clear -> c.bg
    }
    Box(modifier.fillMaxWidth().padding(top = Clauderc.spacing.sp2).alpha(if (faded) 0.5f else 1f), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.fillMaxWidth(0.82f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (pictures > 0) {
                Row(if (text.isEmpty()) Modifier.rewindBand() else Modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(pictures) { picture(it) } }
            }
            if (text.isNotEmpty()) {
                val runs = remember(text) { linkRuns(text) }
                BubbleSelection(enabled = true, mine = true) {
                    RunsText(
                        runs, Int.MAX_VALUE, userFg,
                        Modifier.rewindBand().testTag("userBubble").bubble(mine = true).padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            if (queued) {
                Text("排队中", Modifier.padding(end = Clauderc.spacing.sp2), fontSize = Clauderc.fontSizes.caption, color = c.fg3)
            }
        }
    }
}

@Composable
private fun Modifier.bubble(mine: Boolean): Modifier {
    val c = Clauderc.colors
    return when (Clauderc.style) {
        UiStyle.Clear -> clip(RoundedCornerShape(22.dp)).background(if (mine) c.fg else c.surface)
        UiStyle.Plate -> if (mine) background(c.fg) else drawBehind { drawRect(c.fg, size = Size(2.dp.toPx(), size.height)) }
        UiStyle.Tile -> if (mine) {
            clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)).background(c.accent)
        } else {
            clip(RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)).background(c.card)
        }
        UiStyle.Ink -> if (mine) {
            clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)).background(c.inverse)
        } else {
            clip(RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)).background(c.card).border(1.dp, c.edge, RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp))
        }
    }
}

/** A 96dp picture, a blank tile while it decodes; [onOpen], when set, opens it from where it sits on screen. */
@Composable
private fun ChatPicture(bitmap: ImageBitmap?, onOpen: ((Rect?) -> Unit)? = null) {
    val placed = remember { Placed() }
    val open = if (onOpen == null) Modifier else Modifier
        .onPlaced { placed.coords = it }
        .clickable(role = Role.Button, onClickLabel = "查看图片") { onOpen(placed.coords?.takeIf { it.isAttached }?.boundsInRoot()) }
        .testTag("userPicture")
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(Clauderc.colors.surface).then(open)) {
        if (bitmap != null) Image(bitmap, contentDescription = "图片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/**
 * A reply's bubble, its text cut into blocks (ReplyBlocks). One that came in while the page is open,
 * [typed], types out from the first character; any reply whose text grows, as the screen's preview
 * does, types out what is new. It is as wide as its widest line, up to 82%; code and tables wider than
 * that scroll inside it.
 */
@Composable
private fun AssistantBubble(text: String, typed: Boolean = false) {
    // Parsed once per text, not on every recomposition as the reply scrolls in.
    val blocks = remember(text) { mdBlocks(text) }
    val total = remember(blocks) { blocks.sumOf { it.shownLength() } }
    // Kept across text changes: the written reply carries on from where its screen preview got to.
    val typer = remember { Typer(if (typed) 0f else total.toFloat()) }
    val target by rememberUpdatedState(total)
    if (typer.shown < total) LaunchedEffect(typer) { typer.typeOut { target } }
    Box(Modifier.fillMaxWidth(0.82f)) {
        // While it types out, nothing in it can be selected.
        BubbleSelection(enabled = typer.shown >= total, mine = false) {
            ReplyBlocks(
                blocks,
                typer.shown.toInt(),
                Clauderc.colors.fg,
                Modifier.width(IntrinsicSize.Max).bubble(mine = false)
                    .padding(start = if (Clauderc.style == UiStyle.Plate) 14.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp).testTag("reply"),
            )
        }
    }
}

/** How much of a reply shows while it types out, in characters of its blocks as shown. */
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
    val plate = Clauderc.style == UiStyle.Plate
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
                fontSize = if (plate) 12.sp else 13.sp,
                fontFamily = if (plate) MonoFamily else null,
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

/**
 * Post-answer banner floating above the composer when another online session is still waiting for confirmation
 * (demo.html showNextBar / .next-slot): lets the user hop across pending approvals in one tap.
 */
@Composable
private fun NextPendingBanner(
    entry: PendingEntry,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val arrowShift by animateDpAsState(
        targetValue = if (ui == UiStyle.Plate && pressed) 6.dp else 0.dp,
        animationSpec = tween(160, easing = Motion.plateEasePress),
        label = "nextArrowShift",
    )
    val surface = when (ui) {
        UiStyle.Ink -> Modifier.inkSurface(c.float, c.edge, 22.dp)
        UiStyle.Tile -> Modifier.tileSurface(c.card, 20.dp)
        UiStyle.Clear -> Modifier.clip(RoundedCornerShape(22.dp)).glassPane(LocalGlassSource.current)
        UiStyle.Plate -> Modifier.plateHardShadow(c.fg, 3.dp, pressed).plateBox(c.bg, c.fg, 2.dp)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (ui == UiStyle.Plate) 18.dp else sp.sp3, vertical = sp.sp1)
            .pressShrink(press, if (ui == UiStyle.Plate) PressKind.Primary else PressKind.Card)
            .then(surface)
            .clickable(press, PressIndication(c.fg), role = Role.Button, onClick = onOpen)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
            .testTag("nextPendingBanner"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(Glyph.Need)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "下一个待确认 · ${entry.machineName}",
                fontSize = 11.5.sp,
                fontFamily = if (ui == UiStyle.Plate) MonoFamily else null,
                color = c.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                display(entry.session),
                fontSize = 14.sp,
                fontWeight = if (ui == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = c.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val tagBg = when (ui) {
            UiStyle.Ink -> c.inverse
            UiStyle.Plate -> c.fg
            else -> c.accent
        }
        val tagFg = when (ui) {
            UiStyle.Ink -> c.onInverse
            UiStyle.Plate -> c.bg
            else -> c.onAccent
        }
        Row(
            Modifier
                .clip(if (ui == UiStyle.Plate) RectangleShape else CircleShape)
                .background(tagBg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "下一个",
                fontSize = 12.5.sp,
                fontFamily = if (ui == UiStyle.Plate) MonoFamily else null,
                fontWeight = FontWeight.Bold,
                color = tagFg,
            )
            if (ui == UiStyle.Plate) {
                Text(
                    "→",
                    fontSize = 12.5.sp,
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.Bold,
                    color = tagFg,
                    modifier = Modifier.offset(x = arrowShift),
                )
            }
        }
        SymbolButton(Symbols.Close, "关闭提示", onClick = onDismiss, small = true, tint = c.fg3)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(state: ChatUiState, actions: ChatActions, input: MutableState<TextFieldValue>, onAttach: () -> Unit, onModels: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val haptic = LocalHapticFeedback.current
    val ui = Clauderc.style
    val wrap = if (ui == UiStyle.Plate) c.bg else Color.Transparent
    var field by input
    val text = field.text
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
    val plate = ui == UiStyle.Plate
    val sidePad = if (plate) 18.dp else sp.sp3
    val block = if (plate) Modifier.coverBehind(wrap, sidePad) else Modifier.coverBehind(wrap, sp.sp3).padding(bottom = sp.sp2)
    Column(
        Modifier
            .fillMaxWidth()
            .background(wrap)
            .then(if (plate) Modifier.topLine(c.fg, 2.dp) else Modifier)
            .navigationBarsPadding()
            .padding(start = sidePad, end = sidePad, top = if (plate) 2.dp else sp.sp2, bottom = if (plate) 14.dp else sp.sp3),
    ) {
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
        // Round 10: the task list sits over the status line. Keep the last list while the bar leaves.
        var tasks by remember { mutableStateOf(state.tasks) }
        if (state.tasks.isNotEmpty()) tasks = state.tasks
        Lift(visible = state.tasks.isNotEmpty()) {
            Column(block) {
                TaskBar(tasks, inIsland = plate)
                if (plate) HorizontalDivider(thickness = 1.dp, color = c.line)
            }
        }
        Lift(visible = state.state == SessionState.Working) {
            Column(block) {
                WorkingRow(state.workingSinceMillis, enabled, actions.onInterrupt, state.activity, inIsland = plate)
                if (plate) HorizontalDivider(thickness = 1.dp, color = c.line)
            }
        }
        // Where 「工作中」 sits: the session waits on something only the terminal can show. Not while the
        // agent rewinds: the panel it has open is what reads as a dialog.
        Lift(visible = state.unknownDialog && state.pending == null && !state.rewinding) {
            Column(block) {
                BlockedRow(enabled, actions.onOpenTerm)
                if (plate) HorizontalDivider(thickness = 1.dp, color = c.line)
            }
        }
        // Keep the last limit while the row leaves.
        var limit by remember { mutableStateOf(state.limit) }
        state.limit?.let { limit = it }
        Lift(visible = state.limit != null) {
            limit?.let { l ->
                Column(block) {
                    LimitRow(l, state.resumeText, enabled, actions.onResumeText, actions.onAutoResume, inIsland = plate)
                    if (plate) HorizontalDivider(thickness = 1.dp, color = c.line)
                }
            }
        }
        Lift(visible = state.attachments.isNotEmpty()) {
            // The pictures scroll; the size used stays at the end, however many there are.
            Row(block.fillMaxWidth().padding(horizontal = sp.sp1), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(sp.sp2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.attachments.forEach { a ->
                        androidx.compose.runtime.key(a.id) {
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
                                        .clickable(press, PressIndication(androidx.compose.ui.graphics.Color.White), role = Role.Button, onClickLabel = "移除") { actions.onRemoveImage(a.id) }
                                        .semantics { contentDescription = "移除 ${a.name}" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Symbols.Close, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                    val press = remember { MutableInteractionSource() }
                    val ui = Clauderc.style
                    val tileShape = RoundedCornerShape(if (ui == UiStyle.Plate) 0.dp else 12.dp)
                    Box(
                        Modifier
                            .pressShrink(press)
                            .size(60.dp)
                            .clip(tileShape)
                            .background(when (ui) { UiStyle.Tile -> c.card; UiStyle.Ink -> c.float; else -> c.surface })
                            .then(
                                when (ui) {
                                    UiStyle.Plate -> Modifier.border(1.dp, c.fg, tileShape)
                                    UiStyle.Ink -> Modifier.border(1.dp, c.edge, tileShape)
                                    else -> Modifier
                                },
                            )
                            .clickable(press, PressIndication(c.fg2), role = Role.Button, onClickLabel = "再添加图片", onClick = onAttach),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Symbols.Add, contentDescription = "再添加图片", tint = c.fg2) }
                }
                Text(
                    "%.1f / %d MB".format(state.attachments.bytes / 1048576.0, ChatLimits.MAX_IMAGES_TOTAL_BYTES shr 20),
                    fontSize = Clauderc.fontSizes.caption,
                    color = c.fg3,
                    modifier = Modifier.padding(start = sp.sp2),
                )
            }
        }
        var shownError by remember { mutableStateOf("") }
        state.attachError?.let { shownError = it }
        Lift(visible = state.attachError != null) {
            Text(shownError, block.fillMaxWidth().padding(horizontal = sp.sp2), fontSize = Clauderc.fontSizes.caption, color = c.danger, lineHeight = 1.5.em)
        }
        if (plate) {
            // 印版 input row (demo.html .input): bottom-sweeping 90°-spinning + button, animated underline field, and double-stamp Send button with upward arrow nudge.
            var focused by remember { mutableStateOf(false) }
            val focusRule by animateFloatAsState(
                targetValue = if (focused) 1f else 0f,
                animationSpec = tween(420, easing = Motion.plateEasePress),
                label = "plateInputFocusRule",
            )
            val attachPress = remember { MutableInteractionSource() }
            val attachPressed by attachPress.collectIsPressedAsState()
            val attachSweep by animateFloatAsState(
                targetValue = if (enabled && attachPressed) 1f else 0f,
                animationSpec = tween(if (attachPressed) 220 else 160, easing = Motion.plateEasePress),
                label = "plateAttachSweep",
            )
            val attachRot by animateFloatAsState(
                targetValue = if (enabled && attachPressed) 90f else 0f,
                animationSpec = tween(if (attachPressed) 220 else 180, easing = Motion.plateEasePress),
                label = "plateAttachRot",
            )
            val attachTint = if (enabled) lerp(c.fg, c.bg, attachSweep) else c.fg3
            val canSend = enabled && !state.sending && !state.rewinding && (text.isNotBlank() || state.attachments.isNotEmpty())
            val sendPress = remember { MutableInteractionSource() }
            val sendPressed by sendPress.collectIsPressedAsState()
            val arrowLift by animateDpAsState(
                targetValue = if (canSend && sendPressed) (-4).dp else 0.dp,
                animationSpec = tween(160, easing = Motion.plateEasePress),
                label = "plateSendLift",
            )
            val sendFill = if (canSend) c.fg else c.surface2
            val sendFg = if (canSend) c.bg else c.fg3
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    Modifier
                        .pressShrink(attachPress, PressKind.Plain)
                        .size(36.dp)
                        .background(c.bg, RectangleShape)
                        .plateSweepFill(attachSweep, c.fg, PlateSweep.BottomToTop)
                        .border(1.5.dp, if (enabled) c.fg else c.fg3, RectangleShape)
                        .clickable(attachPress, indication = null, enabled = enabled, role = Role.Button, onClick = onAttach)
                        .semantics { contentDescription = "添加图片" }
                        .testTag("plateAttachBtn"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Symbols.Add,
                        contentDescription = null,
                        tint = attachTint,
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = attachRot },
                    )
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        if (it.text != field.text) actions.onDraft(it.text)
                        field = it
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp)
                        .onFocusChanged { focused = it.isFocused }
                        .drawBehind {
                            val baseH = 1.5.dp.toPx()
                            drawRect(c.fg, Offset(0f, size.height - baseH), Size(size.width, baseH))
                            if (focusRule > 0.005f) {
                                val thickH = 3.dp.toPx()
                                val w = size.width * focusRule
                                drawRect(c.fg, Offset((size.width - w) / 2f, size.height - thickH), Size(w, thickH))
                            }
                        }
                        .padding(vertical = 8.dp)
                        .testTag("composer"),
                    textStyle = TextStyle(color = c.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 1.4.em),
                    cursorBrush = SolidColor(c.fg),
                    maxLines = 5,
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                Text(
                                    when {
                                        !enabled -> "离线，不能发送"
                                        state.state == SessionState.Stopped -> "发消息会先唤醒会话"
                                        else -> "发消息"
                                    },
                                    color = c.fg3,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Normal,
                                )
                            }
                            inner()
                        }
                    },
                )
                Box(
                    Modifier
                        .pressShrink(sendPress, PressKind.Primary)
                        .size(36.dp)
                        .plateStamp(sendFill, c.bg, c.fg, source = sendPress, enabled = canSend)
                        .clickable(sendPress, PressIndication(sendFg), enabled = canSend, role = Role.Button, onClickLabel = "发送") {
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
                    Icon(
                        Symbols.ArrowUpward,
                        contentDescription = null,
                        tint = sendFg,
                        modifier = Modifier.size(20.dp).offset(y = arrowLift),
                    )
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        when (ui) {
                            UiStyle.Tile -> Modifier.tileSurface(c.float, 28.dp)
                            UiStyle.Ink -> Modifier.inkSurface(c.float, c.edge, 28.dp)
                            UiStyle.Plate -> Modifier.plateBox(c.bg, c.fg, 1.5.dp)
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
                        .pressShrink(attachPress, PressKind.Icon)
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
                val canSend = enabled && !state.sending && !state.rewinding && (text.isNotBlank() || state.attachments.isNotEmpty())
                val sendPress = remember { MutableInteractionSource() }
                val sendFill = when {
                    !canSend -> c.surface2
                    ui == UiStyle.Tile -> c.accent
                    ui == UiStyle.Ink -> c.inverse
                    else -> c.fg
                }
                val sendFg = when {
                    !canSend -> c.fg3
                    ui == UiStyle.Tile -> c.onAccent
                    ui == UiStyle.Ink -> c.onInverse
                    else -> c.bg
                }
                Box(
                    Modifier
                        .pressShrink(sendPress, PressKind.Send)
                        .size(44.dp)
                        .then(
                            when (ui) {
                                UiStyle.Plate -> Modifier.background(sendFill)
                                UiStyle.Tile, UiStyle.Ink -> Modifier.clip(CircleShape).background(sendFill)
                                UiStyle.Clear -> Modifier.clip(CircleShape).background(sendFill).then(if (canSend) Modifier.topHighlight(Color.White.copy(alpha = 0.22f)) else Modifier)
                            },
                        )
                        .clickable(sendPress, PressIndication(sendFg), enabled = canSend, role = Role.Button, onClickLabel = "发送") {
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
                    Icon(Symbols.ArrowUpward, contentDescription = null, tint = sendFg, modifier = Modifier.size(22.dp))
                }
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
internal fun WorkingRow(sinceMillis: Long?, enabled: Boolean, onInterrupt: (() -> Unit)?, activity: String? = null, inIsland: Boolean = false) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val pill = if (inIsland) {
        Modifier.heightIn(min = 44.dp).padding(start = 18.dp, end = 8.dp)
    } else {
        when (Clauderc.style) {
            UiStyle.Clear -> Modifier.heightIn(min = 40.dp).clip(CircleShape).glassPane(LocalGlassSource.current).padding(start = sp.sp4, end = sp.sp1)
            UiStyle.Tile -> Modifier.heightIn(min = 40.dp).tileSurface(c.float, 999.dp).padding(start = sp.sp4, end = sp.sp1)
            UiStyle.Ink -> Modifier.heightIn(min = 40.dp).inkSurface(c.float, c.edge, 999.dp).padding(start = sp.sp4, end = sp.sp1)
            UiStyle.Plate -> Modifier.plateBox(c.bg, c.fg, 1.5.dp).padding(start = sp.sp3, end = sp.sp2)
        }
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
        if (onInterrupt != null) {
            if (Clauderc.style == UiStyle.Plate) {
                PlateStopButton(enabled = enabled, onClick = onInterrupt)
            } else {
                TextAction("中断", onClick = onInterrupt, tone = TextTone.Ink, enabled = enabled)
            }
        }
    }
}

/**
 * 印版's differentiated vermilion danger stop button (demo.html `.working .stop`):
 * 32dp square with 1.5dp vermilion border, 2dp vermilion hard shadow collapsing on press,
 * 10dp inner square inverting to white on vermilion press fill, and an infinite pulsing outer
 * vermilion alert frame driven off the shared [LocalLoopClock].
 */
@Composable
private fun PlateStopButton(enabled: Boolean, onClick: () -> Unit) {
    val c = Clauderc.colors
    val clock = LocalLoopClock.current
    if (enabled) clock.Use()
    val pulse by remember(clock, enabled) {
        derivedStateOf {
            if (!enabled) 0f else (clock.millis % Motion.PLATE_DANGER_PULSE_MS) / Motion.PLATE_DANGER_PULSE_MS.toFloat()
        }
    }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val accent = if (enabled) c.danger else c.fg3
    val boxFill = if (enabled && pressed) c.danger else c.bg
    val innerFill = if (enabled && pressed) Color.White else accent
    Box(
        Modifier
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .pressShrink(press, PressKind.Primary)
            .size(32.dp)
            .drawBehind {
                // Infinite pulsing vermilion outer alert ring.
                if (enabled) {
                    val p = pulse.coerceIn(0f, 1f)
                    val scale = 1f + 0.22f * p
                    val alpha = 0.72f * (1f - p)
                    val base = 4.dp.toPx()
                    val rw = (size.width + base * 2f) * scale
                    val rh = (size.height + base * 2f) * scale
                    drawRect(
                        color = c.danger.copy(alpha = alpha),
                        topLeft = Offset((size.width - rw) / 2f, (size.height - rh) / 2f),
                        size = Size(rw, rh),
                        style = Stroke(1.dp.toPx()),
                    )
                }
            }
            .plateHardShadow(accent, 2.dp, pressed = pressed || !enabled)
            .background(boxFill, RectangleShape)
            .border(1.5.dp, accent, RectangleShape)
            .clickable(press, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "中断" }
            .testTag("plateStopButton"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(10.dp).background(innerFill, RectangleShape))
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

/**
 * .limit: the usage limit the session waits out and when it is lifted, then 「到点发送」, the text to send
 * then and the switch that has the agent send it; with the switch on, when it will go out, in the accent.
 */
@Composable
private fun LimitRow(limit: clauderc.app.protocol.Limit, text: String, enabled: Boolean, onText: (String) -> Unit, onSwitch: (Boolean) -> Unit, inIsland: Boolean = false) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val ui = Clauderc.style
    val plate = ui == UiStyle.Plate
    val surface = if (inIsland) Modifier else when (ui) {
        UiStyle.Clear -> Modifier.clip(RoundedCornerShape(22.dp)).glassPane(LocalGlassSource.current)
        UiStyle.Plate -> Modifier.plateBox(c.bg, c.fg, 1.5.dp)
        UiStyle.Tile -> Modifier.tileSurface(c.card, 22.dp)
        UiStyle.Ink -> Modifier.inkSurface(c.float, c.edge, 22.dp)
    }
    val small = if (plate) 12.sp else Clauderc.fontSizes.label
    val on = limit.autoResume != null
    Column(
        Modifier.fillMaxWidth().then(surface).padding(start = sp.sp4, end = sp.sp2, top = sp.sp3, bottom = sp.sp2).testTag("limitRow"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val kind = when (limit.window) {
            "fiveHour" -> "5 小时"
            "weekly" -> "每周"
            else -> "用量"
        }
        Text(
            buildAnnotatedString {
                append("${kind}限额，${clauderc.app.ui.TimeText.resets(limit.resetsAt)}")
                limit.autoResume?.let { r -> withStyle(SpanStyle(color = c.accent)) { append(" · ${clauderc.app.ui.TimeText.ahead(r.at)} 自动发出") } }
            },
            fontSize = small,
            fontFamily = if (plate) MonoFamily else null,
            color = c.fg2,
            modifier = Modifier.testTag("limitText"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(sp.sp3), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "到点发送",
                fontSize = if (plate) 13.sp else Clauderc.fontSizes.label,
                fontFamily = if (plate) MonoFamily else null,
                fontWeight = if (plate) androidx.compose.ui.text.font.FontWeight.Bold else null,
                color = c.fg,
            )
            val field = when (ui) {
                UiStyle.Clear -> Modifier.clip(RoundedCornerShape(Clauderc.radii.sm)).background(c.fg.copy(alpha = 0.06f))
                UiStyle.Plate -> Modifier.background(c.bg).border(1.5.dp, c.fg)
                UiStyle.Tile -> Modifier.clip(RoundedCornerShape(12.dp)).background(c.surface)
                UiStyle.Ink -> Modifier.clip(RoundedCornerShape(12.dp)).background(c.surface2).border(1.dp, c.edge, RoundedCornerShape(12.dp))
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                enabled = enabled,
                singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 36.dp).then(field).padding(horizontal = sp.sp3).testTag("limitInput"),
                textStyle = TextStyle(color = c.fg, fontSize = if (plate) 14.sp else Clauderc.fontSizes.body, fontFamily = if (plate) MonoFamily else null),
                cursorBrush = SolidColor(c.accent),
                decorationBox = { inner ->
                    Box(Modifier.heightIn(min = 36.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("要发的话", color = c.fg3, fontSize = if (plate) 14.sp else Clauderc.fontSizes.body)
                        inner()
                    }
                },
            )
            // Off with nothing typed there is nothing to send; on, it can always be turned off.
            val can = enabled && (on || text.isNotBlank())
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .alpha(if (can) 1f else 0.4f)
                    .toggleable(on, press, null, enabled = can, role = Role.Switch, onValueChange = onSwitch)
                    .padding(4.dp)
                    .semantics { contentDescription = "限额重置后自动发送" }
                    .testTag("limitSwitch"),
            ) { Switch(on, press) }
        }
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

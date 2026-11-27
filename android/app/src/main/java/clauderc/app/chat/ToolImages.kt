package clauderc.app.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import clauderc.app.protocol.ToolImage
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.components.SymbolButtonFrame
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.PressKind
import clauderc.app.ui.theme.controlPress
import clauderc.app.ui.theme.pressProgress
import clauderc.app.ui.theme.pressShrink
import clauderc.app.data.UiStyle
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import clauderc.app.ui.theme.Symbols
import clauderc.app.ui.theme.styled
import java.util.Base64
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Opens a picture in the viewer. */
typealias OpenImage = (Viewing) -> Unit

internal class Placed { var coords: LayoutCoordinates? = null }

/** A thumbnail's decoded bitmap, shared by the chat and the viewer. */
@Composable
private fun rememberThumb(image: ToolImage): ImageBitmap? {
    val thumb = image.thumb ?: return null
    return rememberChatImage("thumb:" + image.id) { Base64.getDecoder().decode(thumb) }
}

/**
 * A picture under its tool row, as small as its thumbnail at most [maxHeight] tall and [widthShare]
 * of the row wide, with a hairline edge so a white screenshot does not melt into the page. Without
 * a thumbnail it is a tile saying 「点开查看」 with the format and size.
 */
@Composable
fun ToolThumb(image: ToolImage, maxHeight: Dp, widthShare: Float, onOpen: OpenImage, modifier: Modifier = Modifier) {
    val c = Clauderc.colors
    val placed = remember { Placed() }
    val ui = Clauderc.style
    val shape = RoundedCornerShape(if (ui == UiStyle.Plate) 0.dp else 12.dp)
    val press = remember { MutableInteractionSource() }
    val edge = if (Clauderc.look.cards) c.ink else c.line
    val hit = pressProgress(press, PressKind.ToolImage).value
    val tile = Modifier
        .onPlaced { placed.coords = it }
        .pressShrink(press, PressKind.ToolImage)
        .clip(shape)
        .background(if (Clauderc.look.cards) c.panel else c.surface)
        .border(if (Clauderc.look.cards) 1.5.dp else 1.dp, if (ui == UiStyle.Ink) lerp(edge, c.fg3, hit) else edge, shape)
        .clickable(press, if (ui == UiStyle.Ink) null else controlPress(PressKind.ToolImage, fill = c.surface, corner = if (ui == UiStyle.Plate) 0.dp else 12.dp), role = Role.Button, onClickLabel = "查看 ${image.name ?: "图片"}") { onOpen(Viewing(image, placed.coords?.takeIf { it.isAttached }?.boundsInRoot())) }
        .testTag("toolThumb")
    if (image.thumb == null) {
        Column(modifier.then(tile).size(148.dp, 96.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Symbols.Image, contentDescription = null, tint = c.fg3, modifier = Modifier.padding(bottom = 4.dp).size(22.dp))
            Text("点开查看", fontSize = Clauderc.fontSizes.caption, color = c.fg2)
            Text(listOf(imageType(image.mediaType), byteSize(image.size)).joinToString(" · "), fontFamily = MonoFamily, fontSize = 10.5.sp, color = c.fg3)
        }
        return
    }
    val bitmap = rememberThumb(image)
    val (tw, th) = thumbPixels(image) ?: bitmap?.let { it.width to it.height } ?: (200 to 200)
    val ratio = tw.toFloat() / th
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = minOf(tw.dp, maxWidth * widthShare, maxHeight * ratio)
        Box(tile.size(w, w / ratio)) {
            if (bitmap != null) Image(bitmap, contentDescription = image.name ?: "图片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** What `clauderc show` sent: each picture with its file name under it, on the left like a reply, no command row. */
@Composable
fun ShownImages(images: List<ToolImage>, onOpen: OpenImage) {
    val sp = Clauderc.spacing
    Column(Modifier.fillMaxWidth(0.82f).padding(vertical = sp.sp1), verticalArrangement = Arrangement.spacedBy(sp.sp1)) {
        images.forEach {
            ToolThumb(it, 320.dp, 1f, onOpen)
            Text(it.name.orEmpty(), Modifier.padding(horizontal = sp.sp1), fontFamily = MonoFamily, fontSize = 11.sp, color = Clauderc.colors.fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The picture in the viewer and where its thumbnail was. [local] is the picture itself when the phone has it, as
 * for a picture in the user's own message: nothing is fetched and it can be saved at once.
 */
class Viewing(val image: ToolImage, val from: Rect?, val local: ByteArray? = null)

/**
 * [viewing] in the viewer over a chat page, with what [state] has of it: its bytes, from the phone or from
 * chat.image, and how saving it goes. [actions] hears which picture the viewer shows, until it closes, and saves it.
 */
@Composable
internal fun ChatImageViewer(viewing: Viewing, state: ChatUiState, actions: ChatActions, onClosed: () -> Unit) {
    val id = viewing.image.id
    DisposableEffect(id) {
        actions.onViewer(id)
        onDispose { actions.onViewer(null) }
    }
    ImageViewer(
        viewing, viewing.local ?: state.wholeImages[id]?.bytes, id in state.failedImages,
        saving = id in state.savingImages,
        saved = id in state.savedImages,
        onSave = { actions.onSaveImage(viewing.image, viewing.local) },
        onClosed = onClosed,
    )
}

/**
 * The picture full screen on black, which takes every touch: a tap anywhere but its buttons closes it. It
 * grows from its thumbnail as the black fades in and shrinks back when closed. Until the picture's bytes
 * are [whole], the thumbnail stands in for it, with 「正在取原图」 under it; without a thumbnail that line
 * spins in the middle. The save button on the right of the bar saves the bytes (see [SaveButton]).
 */
@Composable
fun ImageViewer(
    viewing: Viewing,
    whole: ByteArray?,
    failed: Boolean,
    saving: Boolean = false,
    saved: Boolean = false,
    onSave: () -> Unit = {},
    onClosed: () -> Unit,
) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val img = viewing.image
    val scope = rememberCoroutineScope()
    val shown = remember(viewing) { Animatable(0f) }
    LaunchedEffect(viewing) { shown.animateTo(1f, Motion.enter()) }
    val close: () -> Unit = { scope.launch { shown.animateTo(0f, Motion.enter()); onClosed() } }
    val tapped by rememberUpdatedState(close)
    BackHandler(onBack = close)
    val thumb = rememberThumb(img)
    // The phone's own bytes are a key of their own: a picture id is unique only within its session.
    val full = if (whole != null) rememberChatImage(viewing.local ?: ("whole:" + img.id), ImageCache.whole) { whole } else null
    val fullIn by animateFloatAsState(if (full != null) 1f else 0f, Motion.enter(), label = "whole")
    val ratio = thumbPixels(img)?.let { (w, h) -> w.toFloat() / h } ?: (full ?: thumb)?.let { it.width.toFloat() / it.height }
    val radius = with(LocalDensity.current) { 12.dp.toPx() }
    // Nothing under the viewer gets a touch, such as the permission panel's buttons.
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { tapped() } }.testTag("imageViewer")) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(c.camera))
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = shown.value }.statusBarsPadding().height(56.dp).padding(start = sp.sp1, end = sp.sp4),
                horizontalArrangement = Arrangement.spacedBy(sp.sp2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SymbolButton(Symbols.Close, "关闭", onClick = close, tint = c.onCamera, darkPressAlpha = 0.24f)
                // Without a file name the format is the title, so the line under it leaves the format out.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(img.name ?: imageType(img.mediaType), fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.mono, color = c.onCamera, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(imageLabel(img), fontSize = Clauderc.fontSizes.caption, color = c.onCamera.copy(alpha = 0.6f))
                }
                SaveButton(ready = whole != null, saving = saving, saved = saved, onSave = onSave)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(), contentAlignment = Alignment.Center) {
                if (ratio != null && (thumb != null || full != null)) {
                    val w = minOf(maxWidth, maxHeight * ratio)
                    val placed = remember { Placed() }
                    Box(
                        Modifier
                            .size(w, w / ratio)
                            .onPlaced { placed.coords = it }
                            .graphicsLayer {
                                val p = shown.value
                                val at = placed.coords?.takeIf { it.isAttached }?.boundsInRoot()
                                val from = viewing.from
                                if (from != null && at != null && at.width > 0f) {
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    val sx = from.width / at.width
                                    val sy = from.height / at.height
                                    scaleX = sx + (1f - sx) * p
                                    scaleY = sy + (1f - sy) * p
                                    translationX = (from.left - at.left) * (1f - p)
                                    translationY = (from.top - at.top) * (1f - p)
                                    shape = RoundedCornerShape(radius / sx * (1f - p))
                                    clip = true
                                } else {
                                    alpha = p
                                    scaleX = 0.92f + 0.08f * p
                                    scaleY = scaleX
                                }
                            },
                    ) {
                        if (thumb != null) Image(thumb, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                        if (full != null) {
                            Image(full, contentDescription = img.name ?: "图片", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (thumb != null) fullIn else 1f })
                        }
                    }
                }
                val inMiddle = thumb == null
                // Goes once the bytes are in, even ones that cannot be decoded.
                ViewerNote(
                    whole == null, failed, inMiddle,
                    { shown.value },
                    Modifier.align(if (inMiddle) Alignment.Center else Alignment.BottomCenter),
                )
            }
        }
    }
}

/**
 * The viewer's save button (.v-save), shaped as its close button. Until the picture's bytes are [ready] it is
 * dimmed to 38% of onCamera and does nothing, then brightens (durS). A tap shrinks the download icon away for an
 * 18dp spinner while [saving]; once [saved] the spinner fades, a tick draws itself and springs to size, and the
 * button stays so, done with, until the viewer closes. A failed save fades the spinner and grows the download
 * icon back. The tick is the code block copy button's (TickSwap).
 */
@Composable
private fun SaveButton(ready: Boolean, saving: Boolean, saved: Boolean, onSave: () -> Unit) {
    val c = Clauderc.colors
    val m = Clauderc.motion
    val tint by animateColorAsState(if (ready || saved) c.onCamera else c.onCamera.copy(alpha = 0.38f), tween(m.durS, easing = m.easeEmph), label = "save")
    val swap = remember { TickSwap(iconShown = !saving && !saved, ticked = saved) }
    val late = (m.durS * 0.5f).toInt()
    val phase = if (saved) 2 else if (saving) 1 else 0
    var last by remember { mutableIntStateOf(phase) }
    LaunchedEffect(phase) {
        val from = last
        last = phase
        when {
            phase == 2 -> coroutineScope {
                launch { swap.iconOut(m) }
                swap.tickIn(m, late)
            }
            phase == 1 -> swap.iconOut(m)
            from != 0 -> swap.back(m, iconFrom = 0.6f)
        }
    }
    SymbolButtonFrame(
        onClick = onSave,
        modifier = Modifier.semantics {
            contentDescription = "保存到相册"
            if (saved) stateDescription = "已保存"
        },
        enabled = ready && !saving && !saved,
        darkPressAlpha = 0.24f,
    ) { hit ->
        val iconTint = if (Clauderc.style == UiStyle.Plate) lerp(tint, Color.Black, hit.value) else tint
        swap.Icons(Symbols.Download.styled, iconTint, iconSize = 24.dp, tickSize = 24.dp)
        AnimatedVisibility(saving, enter = fadeIn(tween(m.durS, late, m.easeEmph)), exit = fadeOut(tween(m.durS, easing = m.easeEmphIn))) {
            Spinner(size = 18.dp, color = tint)
        }
    }
}

/**
 * 「正在取原图」 under the stand-in thumbnail, or spinning in the middle without one, or
 * 「图片取不到」; it fades out once the picture is in (durS, leaving curve).
 */
@Composable
private fun ViewerNote(visible: Boolean, failed: Boolean, inMiddle: Boolean, shown: () -> Float, modifier: Modifier) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    AnimatedVisibility(visible, modifier, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
        Row(
            Modifier
                .padding(bottom = if (inMiddle) 0.dp else sp.sp4)
                .graphicsLayer { alpha = 0.7f * shown() }
                .testTag("viewerNote"),
            horizontalArrangement = Arrangement.spacedBy(sp.sp2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (failed) {
                Icon(Symbols.Error, contentDescription = null, tint = c.onCamera, modifier = Modifier.size(16.dp))
                Text("图片取不到", fontSize = Clauderc.fontSizes.caption, color = c.onCamera)
            } else {
                Spinner(size = if (inMiddle) 24.dp else 12.dp, color = c.onCamera)
                Text("正在取原图", fontSize = Clauderc.fontSizes.caption, color = c.onCamera)
            }
        }
    }
}

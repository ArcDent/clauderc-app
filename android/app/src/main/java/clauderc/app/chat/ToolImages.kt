package clauderc.app.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import clauderc.app.protocol.ToolImage
import clauderc.app.ui.components.Spinner
import clauderc.app.ui.components.SymbolButton
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily
import clauderc.app.ui.theme.Motion
import clauderc.app.ui.theme.Symbols
import java.util.Base64
import kotlinx.coroutines.launch

/** Opens a picture in the viewer; [from] is where its thumbnail sits on screen, to grow from. */
typealias OpenImage = (image: ToolImage, from: Rect?) -> Unit

private class Placed { var coords: LayoutCoordinates? = null }

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
    val shape = RoundedCornerShape(12.dp)
    val tile = Modifier
        .onPlaced { placed.coords = it }
        .clip(shape)
        .background(if (Clauderc.look.cards) c.panel else c.surface)
        .border(if (Clauderc.look.cards) 1.5.dp else 1.dp, if (Clauderc.look.cards) c.ink else c.line, shape)
        .clickable(role = Role.Button, onClickLabel = "查看 ${image.name ?: "图片"}") { onOpen(image, placed.coords?.takeIf { it.isAttached }?.boundsInRoot()) }
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

/** The picture in the viewer and where its thumbnail was. */
class Viewing(val image: ToolImage, val from: Rect?)

/**
 * The picture full screen on black. It grows from its thumbnail as the black fades in and shrinks
 * back when closed. Until the picture itself is in, the thumbnail stands in for it, with
 * 「正在取原图」 under it; without a thumbnail that line spins in the middle.
 */
@Composable
fun ImageViewer(viewing: Viewing, whole: ByteArray?, failed: Boolean, onClosed: () -> Unit) {
    val c = Clauderc.colors
    val sp = Clauderc.spacing
    val img = viewing.image
    val scope = rememberCoroutineScope()
    val shown = remember(viewing) { Animatable(0f) }
    LaunchedEffect(viewing) { shown.animateTo(1f, Motion.enter()) }
    val close: () -> Unit = { scope.launch { shown.animateTo(0f, Motion.enter()); onClosed() } }
    BackHandler(onBack = close)
    val thumb = rememberThumb(img)
    val full = if (whole != null) rememberChatImage("whole:" + img.id, ImageCache.whole) { whole } else null
    val fullIn by animateFloatAsState(if (full != null) 1f else 0f, Motion.enter(), label = "whole")
    val ratio = thumbPixels(img)?.let { (w, h) -> w.toFloat() / h } ?: (full ?: thumb)?.let { it.width.toFloat() / it.height }
    val radius = with(LocalDensity.current) { 12.dp.toPx() }
    Box(Modifier.fillMaxSize().testTag("imageViewer")) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }.background(c.camera))
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = shown.value }.statusBarsPadding().height(56.dp).padding(start = sp.sp1, end = sp.sp4),
                horizontalArrangement = Arrangement.spacedBy(sp.sp2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SymbolButton(Symbols.Close, "关闭", onClick = close, tint = c.onCamera)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(img.name ?: imageType(img.mediaType), fontFamily = MonoFamily, fontSize = Clauderc.fontSizes.mono, color = c.onCamera, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(imageLabel(img), fontSize = Clauderc.fontSizes.caption, color = c.onCamera.copy(alpha = 0.6f))
                }
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
                ViewerNote(
                    full == null || failed, failed, inMiddle,
                    { shown.value },
                    Modifier.align(if (inMiddle) Alignment.Center else Alignment.BottomCenter),
                )
            }
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

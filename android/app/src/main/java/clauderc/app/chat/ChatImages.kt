package clauderc.app.chat

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded chat pictures. Decoding runs on [background], never while a frame is drawn,
 * and each picture is decoded once, so scrolling back over it costs nothing.
 */
class ImageCache(
    private val capacity: Int = 48,
    private val background: CoroutineContext = Dispatchers.Default,
    private val decode: (ByteArray) -> ImageBitmap? = { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() },
) {
    private val entries = object : LinkedHashMap<Any, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, ImageBitmap>?) = size > capacity
    }

    @Synchronized fun peek(key: Any): ImageBitmap? = entries[key]

    /** The picture for [key], decoding [bytes] the first time. Null when it cannot be decoded. */
    suspend fun load(key: Any, bytes: () -> ByteArray?): ImageBitmap? {
        peek(key)?.let { return it }
        val bitmap = withContext(background) { runCatching { bytes()?.let(decode) }.getOrNull() } ?: return null
        synchronized(this) { entries[key] = bitmap }
        return bitmap
    }

    companion object {
        val shared = ImageCache()

        /** Whole pictures for the viewer: a few, each scaled down to fit a texture. */
        val whole = ImageCache(capacity = 4, decode = ::decodeFitting)
    }
}

/** Decodes [b] at a power-of-two scale that keeps its longest side within 4096 pixels. */
private fun decodeFitting(b: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096) sample *= 2
    return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** A chat picture, null until it is decoded. [key] identifies the picture, e.g. its base64 text. */
@Composable
fun rememberChatImage(key: Any, cache: ImageCache = ImageCache.shared, bytes: () -> ByteArray?): ImageBitmap? =
    produceState(cache.peek(key), key, cache) {
        val hit = cache.peek(key)
        value = hit
        if (hit == null) value = cache.load(key, bytes)
    }.value

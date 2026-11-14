package clauderc.app.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import clauderc.app.protocol.ChatLimits
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Resizes pictures so the long edge is at most 2048 px and re-encodes them as JPEG (design D2). */
object ImageCompressor {
    /** The size after scaling down; pictures already small enough keep their size. */
    fun targetSize(width: Int, height: Int, maxEdge: Int = ChatLimits.MAX_IMAGE_EDGE): Pair<Int, Int> {
        val edge = max(width, height)
        if (edge <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / edge
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    fun compress(original: ByteArray, maxEdge: Int = ChatLimits.MAX_IMAGE_EDGE, quality: Int = 85): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
        // Decode at a power-of-two reduction first so huge photos do not fill memory.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("cannot decode image")
        val (w, h) = targetSize(decoded.width, decoded.height, maxEdge)
        val scaled = if (w != decoded.width || h != decoded.height) Bitmap.createScaledBitmap(decoded, w, h, true) else decoded
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }
}

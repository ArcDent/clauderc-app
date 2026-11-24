package clauderc.app.chat

import android.content.ContentResolver
import android.content.ContentValues
import android.provider.MediaStore
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The picture formats chat pictures come in, each with the extensions its files may have, the usual one first. */
enum class ImageFormat(val mime: String, val exts: List<String>) {
    Png("image/png", listOf("png")),
    Jpeg("image/jpeg", listOf("jpg", "jpeg")),
    Gif("image/gif", listOf("gif")),
    WebP("image/webp", listOf("webp"));

    companion object {
        /** The format the bytes' header says, else the one [mediaType] names; null when neither is known. */
        fun of(bytes: ByteArray, mediaType: String? = null): ImageFormat? = sniff(bytes) ?: entries.firstOrNull { it.mime == mediaType }

        /** The format by the bytes' header alone. */
        fun sniff(b: ByteArray): ImageFormat? = when {
            b.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> Png
            b.startsWith(0xFF, 0xD8, 0xFF) -> Jpeg
            b.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code) && b.size >= 6 && (b[4] == '7'.code.toByte() || b[4] == '9'.code.toByte()) && b[5] == 'a'.code.toByte() -> Gif
            b.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && b.size >= 12 && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> WebP
            else -> null
        }

        private fun ByteArray.startsWith(vararg head: Int) = size >= head.size && head.indices.all { this[it] == head[it].toByte() }
    }
}

/** Where saved pictures go, under the phone's Pictures folder. */
const val GALLERY_DIR = "Pictures/clauderc"

private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/**
 * The gallery file name for a picture called [name]: without path separators, control characters or leading
 * dots (a hidden file to galleries), its extension the one [format] has, so `shot.png` holding a JPEG is saved
 * as `shot.jpg`. With no name left, `clauderc-<date-time>`.
 */
fun saveFileName(name: String?, format: ImageFormat, now: LocalDateTime): String {
    val clean = name.orEmpty().filterNot { it == '/' || it == '\\' || it.isISOControl() }.trim()
    val dot = clean.lastIndexOf('.')
    val ext = if (dot >= 0) clean.substring(dot + 1) else ""
    val pictureExt = ImageFormat.entries.any { ext.lowercase() in it.exts }
    val base = (if (pictureExt) clean.substring(0, dot) else clean).trimStart('.').trim().take(120)
    val keep = if (ext.lowercase() in format.exts) ext else format.exts.first()
    return base.ifEmpty { "clauderc-" + now.format(STAMP) } + "." + keep
}

/** Puts pictures into the phone's gallery. */
fun interface ImageSaver {
    /** Saves [bytes] as they are, not encoded again; throws when it cannot. */
    suspend fun save(bytes: ByteArray, name: String?, mediaType: String?)
}

/**
 * Saves into [GALLERY_DIR] through MediaStore, which needs no permission from Android 10 on. The file stays
 * hidden from the gallery while it is written, and one written halfway is removed.
 */
class GallerySaver(private val resolver: ContentResolver, private val now: () -> LocalDateTime = LocalDateTime::now) : ImageSaver {
    override suspend fun save(bytes: ByteArray, name: String?, mediaType: String?) {
        withContext(Dispatchers.IO) {
            val format = ImageFormat.of(bytes, mediaType) ?: throw IOException("不认识的图片格式")
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, saveFileName(name, format, now()))
                put(MediaStore.Images.Media.MIME_TYPE, format.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("相册没有接受这张图")
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("打不开相册里的文件")).use { it.write(bytes) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                withContext(NonCancellable) { runCatching { resolver.delete(uri, null, null) } }
                throw e
            }
        }
    }
}

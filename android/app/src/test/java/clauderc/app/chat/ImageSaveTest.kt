package clauderc.app.chat

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** Saving a chat picture into the gallery: its format by its header, its file name, and the MediaStore row. */
@RunWith(AndroidJUnit4::class)
class ImageSaveTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
    private val gif = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0)
    private val webp = "RIFF".toByteArray() + byteArrayOf(0x24, 0, 0, 0) + "WEBPVP8 ".toByteArray()
    private val at = LocalDateTime.of(2026, 11, 23, 9, 5, 7)

    @Test fun formatsAreReadFromTheHeader() {
        assertEquals(ImageFormat.Png, ImageFormat.sniff(png))
        assertEquals(ImageFormat.Jpeg, ImageFormat.sniff(jpeg))
        assertEquals(ImageFormat.Gif, ImageFormat.sniff(gif))
        assertEquals(ImageFormat.Gif, ImageFormat.sniff("GIF87a".toByteArray()))
        assertEquals(ImageFormat.WebP, ImageFormat.sniff(webp))
        assertNull(ImageFormat.sniff("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
        assertNull(ImageFormat.sniff(byteArrayOf(1, 2, 3)))
        // The header wins over what the message said; the message's type covers bytes with no known header.
        assertEquals(ImageFormat.Jpeg, ImageFormat.of(jpeg, "image/png"))
        assertEquals(ImageFormat.Png, ImageFormat.of(byteArrayOf(1, 2, 3), "image/png"))
        assertNull(ImageFormat.of(byteArrayOf(1, 2, 3), "image/bmp"))
    }

    @Test fun fileNames() {
        assertEquals("shot.jpg", saveFileName("shot.png", ImageFormat.Jpeg, at))
        assertEquals("photo.JPEG", saveFileName("photo.JPEG", ImageFormat.Jpeg, at))
        assertEquals("README.png", saveFileName("README", ImageFormat.Png, at))
        assertEquals("v1.2.png", saveFileName("v1.2", ImageFormat.Png, at))
        assertEquals("clauderc-20261123-090507.webp", saveFileName("", ImageFormat.WebP, at))
        assertEquals("clauderc-20261123-090507.gif", saveFileName(null, ImageFormat.Gif, at))
        assertEquals("clauderc-20261123-090507.png", saveFileName(".png", ImageFormat.Png, at))
        assertEquals("etcpasswd.png", saveFileName("../etc/pass\u0000wd\n.png", ImageFormat.Png, at))
        assertEquals("notes.txt.png", saveFileName(".notes.txt", ImageFormat.Png, at))
        assertTrue(saveFileName("a/b\\c\u0007.png", ImageFormat.Png, at).none { it == '/' || it == '\\' || it.isISOControl() })
    }

    @Test fun savesIntoPicturesClaudercAndPublishesTheRow() = runTest {
        val media = Robolectric.setupContentProvider(FakeMedia::class.java, MediaStore.AUTHORITY)
        GallerySaver(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver) { at }.save(png, "chat.png", "image/png")
        val row = media.rows.values.single()
        assertEquals("chat.png", row.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
        assertEquals("image/png", row.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("Pictures/clauderc", row.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals(0, row.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(listOf(1, 0), media.pending)
        assertArrayEquals(png, media.files.values.single().readBytes())
    }

    @Test fun aFailedWriteRemovesTheRowAndStillThrows() = runTest {
        val media = Robolectric.setupContentProvider(FakeMedia::class.java, MediaStore.AUTHORITY)
        media.failOpen = true
        try {
            GallerySaver(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver) { at }.save(jpeg, "a.jpg", null)
            fail("saved although the write failed")
        } catch (e: FileNotFoundException) {
            assertEquals("disk full", e.message)
        }
        assertEquals(1, media.inserted)
        assertTrue(media.rows.isEmpty())
    }

    /** MediaStore as far as saving needs it: rows by id, a file behind each, IS_PENDING as written. */
    class FakeMedia : ContentProvider() {
        val rows = linkedMapOf<Long, ContentValues>()
        val files = linkedMapOf<Long, File>()
        val pending = mutableListOf<Int>()
        var inserted = 0
        var failOpen = false

        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = (++inserted).toLong()
            rows[id] = ContentValues(values)
            values?.getAsInteger(MediaStore.Images.Media.IS_PENDING)?.let(pending::add)
            return ContentUris.withAppendedId(uri, id)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val row = rows[ContentUris.parseId(uri)] ?: return 0
            values?.let(row::putAll)
            values?.getAsInteger(MediaStore.Images.Media.IS_PENDING)?.let(pending::add)
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            if (rows.remove(ContentUris.parseId(uri)) != null) 1 else 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failOpen) throw FileNotFoundException("disk full")
            val f = File.createTempFile("media", ".bin").also { it.deleteOnExit() }
            files[ContentUris.parseId(uri)] = f
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
    }
}

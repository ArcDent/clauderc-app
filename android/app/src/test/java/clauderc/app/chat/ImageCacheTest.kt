package clauderc.app.chat

import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Chat pictures are decoded off the main thread once and reused after that. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ImageCacheTest {
    @Test fun decodesOnceAndReuses() = runTest(UnconfinedTestDispatcher()) {
        var decodes = 0
        var thread: String? = null
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        val cache = ImageCache(background = kotlinx.coroutines.Dispatchers.Default, decode = { decodes++; thread = Thread.currentThread().name; bitmap })
        val main = Thread.currentThread().name
        assertSame(bitmap, cache.load("a") { byteArrayOf(1) })
        assertSame(bitmap, cache.load("a") { byteArrayOf(1) })
        assertEquals(1, decodes)
        assert(thread != main) { "decoded on the calling thread" }
        assertSame(bitmap, cache.peek("a"))
    }

    @Test fun undecodablePicturesAreNotCached() = runTest(UnconfinedTestDispatcher()) {
        val cache = ImageCache(decode = { null })
        assertNull(cache.load("b") { byteArrayOf(0) })
        assertNull(cache.peek("b"))
    }
}

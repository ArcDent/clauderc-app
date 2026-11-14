package clauderc.app.pair

import com.google.zxing.BarcodeFormat
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

/** The scanner reads codes drawn either way round, so light-on-dark terminal output still pairs. */
class QrDecoderTest {
    private val uri = "clauderc://pair?relay=wss://relay.example.com&code=9f2c4e1a7b3d5f6081a2c3e4f5061728"

    private fun image(inverted: Boolean): RGBLuminanceSource {
        val m = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 200, 200)
        val dark = if (inverted) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val light = if (inverted) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) dark else light }
        return RGBLuminanceSource(m.width, m.height, pixels)
    }

    /** The mixed decoder alternates between plain and inverted, so give each image two frames. */
    private fun decode(inverted: Boolean): String? {
        val decoder = qrDecoderFactory().createDecoder(emptyMap<com.google.zxing.DecodeHintType, Any>())
        return (1..2).firstNotNullOfOrNull { decoder.decode(image(inverted))?.text }
    }

    @Test fun readsDarkOnLight() = assertEquals(uri, decode(inverted = false))

    @Test fun readsLightOnDark() = assertEquals(uri, decode(inverted = true))
}

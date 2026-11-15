package clauderc.app.ui

import clauderc.app.protocol.SessionContext
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test fun contextPercentRoundsAndCaps() {
        assertEquals(0, contextPercent(SessionContext(0, 200_000)))
        assertEquals(25, contextPercent(SessionContext(254_185, 1_000_000)))
        assertEquals(25, contextPercent(SessionContext(2_495, 10_000)))
        assertEquals(24, contextPercent(SessionContext(2_449, 10_000)))
        assertEquals("past the window shows full", 100, contextPercent(SessionContext(624_092, 200_000)))
        assertNull(contextPercent(null))
        assertNull(contextPercent(SessionContext(10, 0)))
    }

    @Test fun resetTimes() {
        val zone = ZoneId.of("Asia/Shanghai")
        // A Saturday afternoon.
        val now = Instant.parse("2026-11-14T06:00:00Z")
        assertEquals("22:40 重置", TimeText.resets(Instant.parse("2026-11-14T14:40:00Z"), now, zone))
        assertEquals("明天 09:00 重置", TimeText.resets(Instant.parse("2026-11-15T01:00:00Z"), now, zone))
        assertEquals("周二 11:00 重置", TimeText.resets(Instant.parse("2026-11-17T03:00:00Z"), now, zone))
        assertEquals("11 月 21 日 重置", TimeText.resets(Instant.parse("2026-11-21T03:00:00Z"), now, zone))
    }
}

package clauderc.app.ui

import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionContext
import clauderc.app.protocol.SessionState
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** Claude Code 2.1.326 reports blocked for a reply too ("needs input:"), done for "result:". */
    @Test fun stateWordFollowsClaudesOwnReading() {
        fun s(state: SessionState, job: String?) = Session("s", lastActiveAt = Instant.EPOCH, state = state, jobState = job)
        assertEquals("需要你回复", sessionStateText(s(SessionState.Idle, "blocked")))
        assertEquals("已完成", sessionStateText(s(SessionState.Idle, "done")))
        assertEquals("失败", sessionStateText(s(SessionState.Idle, "failed")))
        assertEquals("空闲", sessionStateText(s(SessionState.Idle, null)))
        assertEquals("待确认", sessionStateText(s(SessionState.Pending, "blocked")))
        assertEquals("工作中", sessionStateText(s(SessionState.Working, "done")))
        assertEquals("已停止", sessionStateText(s(SessionState.Stopped, "blocked")))
        assertTrue(needsYou(s(SessionState.Idle, "blocked")))
        assertTrue(needsYou(s(SessionState.Pending, "blocked")))
        assertFalse(needsYou(s(SessionState.Idle, "done")))
        assertFalse(needsYou(s(SessionState.Stopped, "blocked")))
    }
}

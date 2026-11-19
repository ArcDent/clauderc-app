package clauderc.app.ui

import clauderc.app.protocol.Profile
import clauderc.app.protocol.ProfileKind
import clauderc.app.protocol.Session
import clauderc.app.protocol.SessionContext
import clauderc.app.protocol.SessionState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Short Chinese time labels used across lists, e.g. 「2 分钟前」. */
object TimeText {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    fun relative(t: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Duration.between(t, now)
        val day = t.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when {
            d.seconds < 60 -> "刚刚"
            d.toMinutes() < 60 -> "${d.toMinutes()} 分钟前"
            day == today -> "${d.toHours()} 小时前"
            day == today.minusDays(1) -> "昨天"
            day.isAfter(today.minusDays(7)) -> "${Duration.between(day.atStartOfDay(), today.atStartOfDay()).toDays()} 天前"
            else -> monthDay(day)
        }
    }

    /** Clock time with a day prefix when not today, e.g. 「昨天 23:41」. */
    fun clock(t: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val z = t.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val prefix = when (z.toLocalDate()) {
            today -> ""
            today.minusDays(1) -> "昨天 "
            else -> monthDay(z.toLocalDate()) + " "
        }
        return prefix + hm.format(z)
    }

    /** When a usage limit starts over: 「22:40 重置」 today, 「明天 09:00 重置」, 「周二 11:00 重置」 within the week, else the date. */
    fun resets(t: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String = ahead(t, now, zone) + " 重置"

    /** A time to come: 「22:40」 today, 「明天 09:00」, 「周二 11:00」 within the week, else the date. */
    fun ahead(t: Instant, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val z = t.atZone(zone)
        val day = z.toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when {
            day == today -> hm.format(z)
            day == today.plusDays(1) -> "明天 " + hm.format(z)
            day.isAfter(today) && day.isBefore(today.plusDays(7)) -> WEEKDAYS[day.dayOfWeek.value - 1] + " " + hm.format(z)
            else -> monthDay(day)
        }
    }

    private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private fun monthDay(d: LocalDate) = "${d.monthValue} 月 ${d.dayOfMonth} 日"
}

/** How full a session's context window is, 0–100, or null when unknown. */
fun contextPercent(ctx: SessionContext?): Int? {
    if (ctx == null || ctx.windowTokens <= 0) return null
    return (ctx.usedTokens * 100.0 / ctx.windowTokens).roundToInt().coerceIn(0, 100)
}

/** The last path segment, e.g. ~/github/infra -> infra. */
fun baseName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

fun stateText(state: SessionState): String = when (state) {
    SessionState.Working -> "工作中"
    SessionState.Idle -> "空闲"
    SessionState.Pending -> "待确认"
    SessionState.Stopped -> "已停止"
}

/**
 * Whether the session asked the user for a reply: Claude Code reads its last message as blocked
 * (a "needs input:" line, measured with 2.1.326), yet nothing waits for a decision.
 */
fun needsReply(s: Session): Boolean = s.state == SessionState.Idle && s.jobState == "blocked"

/** Whether the session waits for the user, for a decision or for a reply; the 会话 tab shows a dot then. */
fun needsYou(s: Session): Boolean = s.state == SessionState.Pending || needsReply(s)

/** A session's state word: [stateText], with Claude Code's own reading of an idle session where it says more. */
fun sessionStateText(s: Session): String = when {
    s.state != SessionState.Idle -> stateText(s.state)
    s.jobState == "blocked" -> "需要你回复"
    s.jobState == "done" -> "已完成"
    s.jobState == "failed" -> "失败"
    else -> stateText(s.state)
}

/** The group title: the user's name, else 「默认」, 「其他」 or the API host. */
fun profileTitle(profile: Profile, custom: String?): String = custom ?: when (profile.kind) {
    ProfileKind.Default -> "默认"
    ProfileKind.Other -> "其他"
    ProfileKind.Endpoint -> profile.id
}

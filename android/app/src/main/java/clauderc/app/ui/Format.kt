package clauderc.app.ui

import clauderc.app.protocol.Profile
import clauderc.app.protocol.ProfileKind
import clauderc.app.protocol.SessionState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    private fun monthDay(d: LocalDate) = "${d.monthValue} 月 ${d.dayOfMonth} 日"
}

/** The last path segment, e.g. ~/github/infra -> infra. */
fun baseName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

fun stateText(state: SessionState): String = when (state) {
    SessionState.Working -> "工作中"
    SessionState.Idle -> "空闲"
    SessionState.Pending -> "待确认"
    SessionState.Stopped -> "已停止"
}

/** The group title: the user's name, else 「默认」, 「其他」 or the API host. */
fun profileTitle(profile: Profile, custom: String?): String = custom ?: when (profile.kind) {
    ProfileKind.Default -> "默认"
    ProfileKind.Other -> "其他"
    ProfileKind.Endpoint -> profile.id
}

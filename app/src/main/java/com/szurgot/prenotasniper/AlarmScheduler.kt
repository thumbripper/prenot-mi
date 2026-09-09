package com.szurgot.prenotasniper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Schedules an exact "open the app" alarm a few minutes before the next
 * release window (default Mon & Wed 17:00 Europe/London).
 */
object AlarmScheduler {
    private const val REQ = 4201
    private val LONDON = ZoneId.of("Europe/London")

    fun nextRelease(prefs: Prefs, from: ZonedDateTime = ZonedDateTime.now(LONDON)): ZonedDateTime {
        val days = prefs.releaseDays.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .mapNotNull { runCatching { DayOfWeek.of(it) }.getOrNull() }
            .toSet()
            .ifEmpty { setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY) }
        val time = LocalTime.of(prefs.releaseHour, prefs.releaseMinute)

        var date: LocalDate = from.toLocalDate()
        for (i in 0..14) {
            val candidateDate = date.plusDays(i.toLong())
            if (candidateDate.dayOfWeek in days) {
                val zdt = ZonedDateTime.of(candidateDate, time, LONDON)
                if (zdt.isAfter(from)) return zdt
            }
        }
        // Fallback: same time tomorrow.
        return ZonedDateTime.of(date.plusDays(1), time, LONDON)
    }

    fun schedule(ctx: Context, prefs: Prefs) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = ZonedDateTime.now(LONDON)
        var release = nextRelease(prefs, now)
        var fireAt = release.minusMinutes(prefs.prewarmMinutes.toLong())
        // If the pre-warn time is already here/past (e.g. we're re-scheduling from the
        // alarm that just fired during the pre-release window), skip to the NEXT window
        // so we don't immediately re-fire in a loop.
        if (fireAt.isBefore(now.plusSeconds(60))) {
            release = nextRelease(prefs, release.plusMinutes(1))
            fireAt = release.minusMinutes(prefs.prewarmMinutes.toLong())
        }
        val triggerMs = fireAt.toInstant().toEpochMilli()

        val pi = PendingIntent.getBroadcast(
            ctx, REQ,
            Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_PREWARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            }
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, REQ,
            Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_PREWARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }
}

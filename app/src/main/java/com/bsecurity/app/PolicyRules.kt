package com.bsecurity.app

import java.time.Instant
import java.time.ZoneId

data class AppRule(val blocked: Boolean = false, val minutes: Int = 0) {
    fun merge(other: AppRule): AppRule = AppRule(
        blocked || other.blocked,
        listOf(minutes, other.minutes).filter { it > 0 }.minOrNull() ?: 0
    )
}

object BudgetMath {
    fun exhausted(usedMs: Long, minutes: Int) = minutes > 0 && usedMs >= minutes * 60_000L
    fun overlap(start: Long, end: Long, dayStart: Long, now: Long) =
        (minOf(end, now) - maxOf(start, dayStart)).coerceAtLeast(0)

    fun dayStart(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}

/** Counts the foreground intervals reported by Android, clipped to the local day. */
class UsageTimeline(private val dayStart: Long, private val now: Long) {
    private val totals = mutableMapOf<String, Long>()
    private var foreground: String? = null
    private var opened = dayStart

    fun resume(pkg: String, at: Long) {
        if (foreground == pkg) return
        close(at)
        foreground = pkg
        opened = at
    }

    fun pause(pkg: String, at: Long) {
        if (foreground == pkg) idle(at)
    }

    fun idle(at: Long) {
        close(at)
        foreground = null
    }

    fun result(): Map<String, Long> {
        val result = totals.toMutableMap()
        foreground?.let { result[it] = (result[it] ?: 0) + BudgetMath.overlap(opened, now, dayStart, now) }
        return result
    }

    private fun close(at: Long) {
        foreground?.let { totals[it] = (totals[it] ?: 0) + BudgetMath.overlap(opened, at, dayStart, now) }
    }
}

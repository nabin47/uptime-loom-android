package io.github.nabin47.uptimeloom.stats

import kotlin.math.abs
import kotlin.math.roundToLong

/** "8h", "16m", "7h 56m" */
fun fmtHM(ms: Long): String {
    val totalMin = (ms.coerceAtLeast(0) / 60_000.0).roundToLong()
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0L -> "${m}m"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

/** "40h", "31.7h" */
fun fmtH(ms: Long): String {
    val tenths = (ms / 360_000.0).roundToLong()
    return if (tenths % 10 == 0L) "${tenths / 10}h" else "${tenths / 10}.${abs(tenths % 10)}h"
}

/** "05:12:09" for the live timer */
fun fmtHMS(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/** Minutes after midnight -> "9:05 AM" or "09:05" */
fun fmtClock(minutes: Int?, use24h: Boolean): String {
    if (minutes == null) return "--:--"
    val m = ((minutes % 1440) + 1440) % 1440
    val h = m / 60
    val mm = m % 60
    if (use24h) return "%02d:%02d".format(h, mm)
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "%d:%02d %s".format(h12, mm, if (h < 12) "AM" else "PM")
}

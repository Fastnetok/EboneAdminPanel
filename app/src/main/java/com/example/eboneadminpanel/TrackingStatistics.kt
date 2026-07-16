package com.example.eboneadminpanel

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DailyStats(
    val totalDistanceKm: Double,
    val workingDurationMillis: Long,
    val firstLocationTime: Long,
    val lastLocationTime: Long,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val idleDurationMillis: Long,
    val totalPoints: Int
)

object TrackingStatistics {

    // Gap between two consecutive points bigger than this = employee was idle/stationary
    private const val IDLE_GAP_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutes

    fun computeStats(points: List<MovementPoint>): DailyStats {
        if (points.isEmpty()) {
            return DailyStats(0.0, 0L, 0L, 0L, 0.0, 0.0, 0L, 0)
        }

        val sorted = points.sortedBy { it.timestamp }
        val distanceKm = DistanceCalculator.totalDistanceKm(sorted)
        val firstTime = sorted.first().timestamp
        val lastTime = sorted.last().timestamp
        val durationMs = lastTime - firstTime

        var idleMs = 0L
        var maxSpeedMps = 0f
        for (i in 0 until sorted.size - 1) {
            val gap = sorted[i + 1].timestamp - sorted[i].timestamp
            if (gap >= IDLE_GAP_THRESHOLD_MS) {
                idleMs += gap
            }
            if (sorted[i].speed > maxSpeedMps) maxSpeedMps = sorted[i].speed
        }
        if (sorted.last().speed > maxSpeedMps) maxSpeedMps = sorted.last().speed

        val durationHours = durationMs / 3600000.0
        val avgSpeedKmh = if (durationHours > 0) distanceKm / durationHours else 0.0
        val maxSpeedKmh = maxSpeedMps * 3.6

        return DailyStats(
            totalDistanceKm = distanceKm,
            workingDurationMillis = durationMs,
            firstLocationTime = firstTime,
            lastLocationTime = lastTime,
            averageSpeedKmh = avgSpeedKmh,
            maxSpeedKmh = maxSpeedKmh.toDouble(),
            idleDurationMillis = idleMs,
            totalPoints = sorted.size
        )
    }

    fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return "${hours}h ${minutes}m"
    }

    fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
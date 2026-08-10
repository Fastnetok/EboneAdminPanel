package com.example.eboneadminpanel

// Admin-side safety net. The Employee app already filters bad points
// (LocationTrackingHistory.kt), but this protects against old data saved
// before that filter existed, or any future data source that skips it.
object MovementFilter {

    private const val MAX_ACCURACY_METERS = 50f       // looser than employee-side (20m) — this is a last-resort net
    private const val MAX_REALISTIC_SPEED_MPS = 55f    // ~198 km/h — anything above this is a bad GPS jump

    fun filterValidPoints(points: List<MovementPoint>): List<MovementPoint> {
        if (points.isEmpty()) return points

        val sorted = points.sortedBy { it.timestamp }
        val result = mutableListOf<MovementPoint>()

        for (point in sorted) {
            if (point.accuracy > 0f && point.accuracy > MAX_ACCURACY_METERS) continue

            val previous = result.lastOrNull()
            if (previous != null) {
                val distanceMeters = DistanceCalculator.distanceBetween(
                    previous.lat, previous.lng, point.lat, point.lng
                )
                val timeDiffSec = (point.timestamp - previous.timestamp) / 1000.0
                if (timeDiffSec > 0) {
                    val impliedSpeedMps = distanceMeters / timeDiffSec
                    if (impliedSpeedMps > MAX_REALISTIC_SPEED_MPS) continue
                }
            }

            result.add(point)
        }

        return result
    }
}
package com.example.eboneadminpanel

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object DistanceCalculator {

    private const val EARTH_RADIUS_METERS = 6371000.0

    // Haversine formula — straight-line distance between 2 lat/lng points, in meters
    fun distanceBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    // Total distance across an ordered (by time) list of points, in kilometers
    fun totalDistanceKm(points: List<MovementPoint>): Double {
        if (points.size < 2) return 0.0

        var totalMeters = 0.0
        for (i in 0 until points.size - 1) {
            totalMeters += distanceBetween(
                points[i].lat, points[i].lng,
                points[i + 1].lat, points[i + 1].lng
            )
        }
        return totalMeters / 1000.0
    }
}
package com.example.eboneadminpanel

// Mirrors one entry under tracking/{employeeId}/{date}/{timestamp} in Firebase
data class MovementPoint(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val accuracy: Float = 0f,
    val speed: Float = 0f,      // meters/second, as saved by the Employee app
    val bearing: Float = 0f,
    val timestamp: Long = 0L,
    var address: String = ""    // filled in later via ReverseGeocoder (optional)
)
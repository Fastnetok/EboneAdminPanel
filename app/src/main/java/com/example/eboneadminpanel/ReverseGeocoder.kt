package com.example.eboneadminpanel

import android.content.Context
import android.location.Geocoder
import java.util.Locale

// Converts lat/lng into a short readable address like "Street 1, Okara".
// IMPORTANT: call this from a background thread (coroutine/executor), never
// directly on the main thread — Geocoder can block on network access.
object ReverseGeocoder {

    fun getAddress(context: Context, lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())

            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(lat, lng, 1)

            if (!results.isNullOrEmpty()) {
                val address = results[0]
                val parts = mutableListOf<String>()
                address.thoroughfare?.let { parts.add(it) }   // street
                address.subLocality?.let { parts.add(it) }    // area/locality
                address.locality?.let { parts.add(it) }        // city

                if (parts.isNotEmpty()) {
                    parts.joinToString(", ")
                } else {
                    "Lat: %.4f, Lng: %.4f".format(lat, lng)
                }
            } else {
                "Lat: %.4f, Lng: %.4f".format(lat, lng)
            }
        } catch (e: Exception) {
            // No internet / no geocoder service on device — fall back to coordinates
            "Lat: %.4f, Lng: %.4f".format(lat, lng)
        }
    }
}
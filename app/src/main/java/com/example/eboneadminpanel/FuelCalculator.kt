package com.example.eboneadminpanel

object FuelCalculator {

    // fuelUsed = distance ÷ bike's average (km per litre)
    fun fuelUsedLitres(distanceKm: Double, bikeAverageKmPerLitre: Double): Double {
        if (bikeAverageKmPerLitre <= 0) return 0.0
        return distanceKm / bikeAverageKmPerLitre
    }

    // fuelCost = fuelUsed × price per litre
    fun fuelCost(fuelUsedLitres: Double, pricePerLitre: Double): Double {
        return fuelUsedLitres * pricePerLitre
    }
}
package com.example.eboneadminpanel.utils

import com.example.eboneadminpanel.core.FirestorePaths
import com.google.firebase.firestore.FirebaseFirestore

object CompanyIdGenerator {

    private val db = FirebaseFirestore.getInstance()

    interface IdGeneratedCallback {
        fun onIdGenerated(companyId: String)
        fun onError(message: String)
    }

    fun generateNextId(cityPrefix: String, callback: IdGeneratedCallback) {
        val counterRef = db.collection(FirestorePaths.COUNTERS).document(cityPrefix)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(counterRef)
            val lastNumber = if (snapshot.exists()) {
                snapshot.getLong(FirestorePaths.FIELD_LAST_NUMBER) ?: 0L
            } else {
                0L
            }
            val nextNumber = lastNumber + 1

            transaction.set(
                counterRef,
                mapOf(FirestorePaths.FIELD_LAST_NUMBER to nextNumber)
            )

            nextNumber
        }.addOnSuccessListener { nextNumber ->
            val paddedNumber = nextNumber.toString().padStart(4, '0')
            val companyId = "$cityPrefix-$paddedNumber"
            callback.onIdGenerated(companyId)
        }.addOnFailureListener { e ->
            callback.onError(e.message ?: "Company ID banate waqt error aaya")
        }
    }
}
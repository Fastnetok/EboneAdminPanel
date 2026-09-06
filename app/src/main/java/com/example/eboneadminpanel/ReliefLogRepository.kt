package com.example.eboneadminpanel

import com.google.firebase.firestore.FirebaseFirestore

/**
 * NEW: standalone, self-contained log writer for the Relief Log screen.
 * Writes to its OWN Firestore collection ("reliefLogs") — completely
 * separate from the "customers" collection and its reliefStatus/
 * graceDeadline fields, so nothing about the existing relief/suspend/
 * enable logic is touched or risked by adding this.
 *
 * One document per relief cycle:
 *   customerId, company, activatedAt, reliefDays, expectedExpiryAt,
 *   deactivatedAt (null until the customer is actually suspended), isTest
 */
object ReliefLogRepository {

    private val db by lazy { FirebaseFirestore.getInstance() }

    /** Called once a relief activation is confirmed successful. */
    fun logActivation(
        customerId: String,
        company: String,
        reliefDays: Int,
        expectedExpiryAt: Long,
        isTest: Boolean = false
    ) {
        val data = hashMapOf(
            "customerId" to customerId,
            "company" to company.uppercase(),
            "activatedAt" to System.currentTimeMillis(),
            "reliefDays" to reliefDays,
            "expectedExpiryAt" to expectedExpiryAt,
            "deactivatedAt" to null,
            "isTest" to isTest
        )
        db.collection("reliefLogs").add(data)
    }

    /**
     * Called once a SUSPEND completes successfully. Finds this
     * customer's most recent still-open log entry (deactivatedAt ==
     * null) and stamps it with the actual deactivation time. If no open
     * entry exists (e.g. a manual DISABLE NOW test with no prior
     * "Activate on Relief" call), it's simply skipped — this log is a
     * best-effort history, not a source of truth for anything else.
     */
    fun logDeactivation(customerId: String) {
        db.collection("reliefLogs")
            .whereEqualTo("customerId", customerId)
            .whereEqualTo("deactivatedAt", null)
            .get()
            .addOnSuccessListener { snapshot ->
                val latestOpenDoc = snapshot.documents
                    .maxByOrNull { (it.getLong("activatedAt") ?: 0L) }
                latestOpenDoc?.reference?.update(
                    "deactivatedAt",
                    System.currentTimeMillis()
                )
            }
    }
}
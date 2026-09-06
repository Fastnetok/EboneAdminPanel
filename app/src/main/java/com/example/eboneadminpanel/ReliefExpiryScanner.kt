package com.example.eboneadminpanel

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date

class ReliefExpiryScanner(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    private val customersCollection = "customers"

    private val jobRepository =
        ReliefDisableJobRepository(db)

    /**
     * Finds customers whose ACTIVE relief deadline has expired
     * and creates a QUEUED disable job.
     *
     * IMPORTANT:
     * This class does NOT disable the ISP user.
     *
     * It only creates the Firebase automation job.
     */
    fun scanExpiredReliefs(
        onComplete: (
            scanned: Int,
            queued: Int,
            skipped: Int,
            error: String?
        ) -> Unit
    ) {

        val now = Timestamp(Date())

        db.collection(customersCollection)
            .whereEqualTo(
                "reliefStatus",
                ReliefAutomationContract.CUSTOMER_RELIEF_ACTIVE
            )
            .get()
            .addOnSuccessListener { snapshot ->

                if (snapshot.isEmpty) {
                    onComplete(
                        0,
                        0,
                        0,
                        null
                    )
                    return@addOnSuccessListener
                }

                val documents = snapshot.documents

                var scanned = 0
                var queued = 0
                var skipped = 0
                var completed = 0

                documents.forEach { document ->

                    scanned++

                    val customerId =
                        getCustomerId(document)

                    val company =
                        getCompany(document)

                    val deadline =
                        document.getTimestamp("graceDeadline")

                    /*
                     * Customer ID, company and deadline
                     * are all required.
                     */
                    if (
                        customerId.isBlank() ||
                        company.isBlank() ||
                        deadline == null
                    ) {

                        skipped++
                        completed++

                        checkComplete(
                            documents.size,
                            completed,
                            scanned,
                            queued,
                            skipped,
                            onComplete
                        )

                        return@forEach
                    }

                    /*
                     * Relief has NOT expired yet.
                     */
                    if (deadline.compareTo(now) > 0) {

                        skipped++
                        completed++

                        checkComplete(
                            documents.size,
                            completed,
                            scanned,
                            queued,
                            skipped,
                            onComplete
                        )

                        return@forEach
                    }

                    /*
                     * Relief deadline has expired.
                     *
                     * Create a Firebase QUEUED job.
                     */
                    jobRepository.createQueuedJob(
                        customerId = customerId,
                        company = company,
                        graceDeadline = deadline
                    ) { success, _ ->

                        if (success) {
                            queued++
                        } else {
                            skipped++
                        }

                        completed++

                        checkComplete(
                            documents.size,
                            completed,
                            scanned,
                            queued,
                            skipped,
                            onComplete
                        )
                    }
                }
            }
            .addOnFailureListener { error ->

                onComplete(
                    0,
                    0,
                    0,
                    error.message
                        ?: "Could not scan relief customers."
                )
            }
    }

    /**
     * Calls the final callback after every customer
     * has been processed.
     */
    private fun checkComplete(
        total: Int,
        completed: Int,
        scanned: Int,
        queued: Int,
        skipped: Int,
        onComplete: (
            scanned: Int,
            queued: Int,
            skipped: Int,
            error: String?
        ) -> Unit
    ) {

        if (completed >= total) {

            onComplete(
                scanned,
                queued,
                skipped,
                null
            )
        }
    }

    /**
     * Gets Customer ID.
     *
     * Priority:
     * 1. customerId field
     * 2. Firestore document ID
     */
    private fun getCustomerId(
        document: DocumentSnapshot
    ): String {

        val explicitId =
            document.getString("customerId")
                ?.trim()
                .orEmpty()

        if (explicitId.isNotEmpty()) {
            return explicitId
        }

        return document.id.trim()
    }

    /**
     * Gets ISP/company.
     *
     * Priority:
     * 1. ispProvider
     * 2. company
     */
    private fun getCompany(
        document: DocumentSnapshot
    ): String {

        val provider =
            document.getString("ispProvider")
                ?.trim()
                .orEmpty()

        if (provider.isNotEmpty()) {
            return provider.uppercase()
        }

        val company =
            document.getString("company")
                ?.trim()
                .orEmpty()

        return company.uppercase()
    }
}
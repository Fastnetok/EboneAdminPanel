package com.example.eboneadminpanel

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class ReliefDisableJobRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    private val jobsCollection =
        db.collection(ReliefAutomationContract.COLLECTION_DISABLE_JOBS)

    /**
     * Creates a queued disable job for a customer.
     *
     * The customer ID itself is used as the document ID so that
     * the same customer cannot accidentally receive multiple
     * queued disable jobs.
     */
    fun createQueuedJob(
        customerId: String,
        company: String,
        graceDeadline: Any?,
        onResult: (Boolean, String?) -> Unit
    ) {

        val cleanCustomerId = customerId.trim()
        val cleanCompany = company.trim().uppercase()

        if (cleanCustomerId.isEmpty()) {
            onResult(false, "Customer ID is empty.")
            return
        }

        if (cleanCompany.isEmpty()) {
            onResult(false, "Company is empty.")
            return
        }

        val jobRef = jobsCollection.document(cleanCustomerId)

        db.runTransaction { transaction ->

            val snapshot = transaction.get(jobRef)

            /*
             * If an existing job is already queued or processing,
             * do not create another one.
             */
            if (snapshot.exists()) {

                val existingStatus =
                    snapshot.getString(
                        ReliefAutomationContract.FIELD_STATUS
                    ) ?: ""

                if (
                    existingStatus == ReliefAutomationContract.STATUS_QUEUED ||
                    existingStatus == ReliefAutomationContract.STATUS_PROCESSING
                ) {
                    return@runTransaction
                }
            }

            val data = hashMapOf<String, Any?>(
                ReliefAutomationContract.FIELD_CUSTOMER_ID to cleanCustomerId,

                ReliefAutomationContract.FIELD_COMPANY to cleanCompany,

                ReliefAutomationContract.FIELD_ACTION to
                        ReliefAutomationContract.ACTION_DISABLE,

                ReliefAutomationContract.FIELD_STATUS to
                        ReliefAutomationContract.STATUS_QUEUED,

                ReliefAutomationContract.FIELD_ATTEMPTS to 0,

                ReliefAutomationContract.FIELD_LAST_ERROR to "",

                ReliefAutomationContract.FIELD_CREATED_AT to
                        FieldValue.serverTimestamp(),

                ReliefAutomationContract.FIELD_UPDATED_AT to
                        FieldValue.serverTimestamp(),

                ReliefAutomationContract.FIELD_STARTED_AT to null,

                ReliefAutomationContract.FIELD_COMPLETED_AT to null,

                ReliefAutomationContract.FIELD_GRACE_DEADLINE to graceDeadline
            )

            transaction.set(
                jobRef,
                data,
                SetOptions.merge()
            )

        }.addOnSuccessListener {

            onResult(true, null)

        }.addOnFailureListener { error ->

            onResult(
                false,
                error.message ?: "Could not create relief disable job."
            )
        }
    }

    /**
     * Updates a job status.
     */
    fun updateStatus(
        customerId: String,
        status: String,
        errorMessage: String? = null,
        onResult: (Boolean, String?) -> Unit
    ) {

        val cleanCustomerId = customerId.trim()

        if (cleanCustomerId.isEmpty()) {
            onResult(false, "Customer ID is empty.")
            return
        }

        val updates = hashMapOf<String, Any>(
            ReliefAutomationContract.FIELD_STATUS to status,

            ReliefAutomationContract.FIELD_UPDATED_AT to
                    FieldValue.serverTimestamp()
        )

        if (errorMessage != null) {
            updates[
                ReliefAutomationContract.FIELD_LAST_ERROR
            ] = errorMessage
        }

        if (status == ReliefAutomationContract.STATUS_PROCESSING) {
            updates[
                ReliefAutomationContract.FIELD_STARTED_AT
            ] = FieldValue.serverTimestamp()
        }

        if (
            status == ReliefAutomationContract.STATUS_SUCCESS ||
            status == ReliefAutomationContract.STATUS_FAILED
        ) {
            updates[
                ReliefAutomationContract.FIELD_COMPLETED_AT
            ] = FieldValue.serverTimestamp()
        }

        jobsCollection
            .document(cleanCustomerId)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener {
                onResult(true, null)
            }
            .addOnFailureListener { error ->
                onResult(
                    false,
                    error.message ?: "Could not update relief job."
                )
            }
    }

    /**
     * Increments the retry/attempt counter.
     */
    fun incrementAttempts(
        customerId: String,
        onResult: (Boolean, String?) -> Unit
    ) {

        val cleanCustomerId = customerId.trim()

        if (cleanCustomerId.isEmpty()) {
            onResult(false, "Customer ID is empty.")
            return
        }

        jobsCollection
            .document(cleanCustomerId)
            .set(
                mapOf(
                    ReliefAutomationContract.FIELD_ATTEMPTS
                            to FieldValue.increment(1),

                    ReliefAutomationContract.FIELD_UPDATED_AT
                            to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnSuccessListener {
                onResult(true, null)
            }
            .addOnFailureListener { error ->
                onResult(
                    false,
                    error.message ?: "Could not update attempt count."
                )
            }
    }

    /**
     * Reads a single job.
     */
    fun getJob(
        customerId: String,
        onResult: (Map<String, Any>?, String?) -> Unit
    ) {

        val cleanCustomerId = customerId.trim()

        if (cleanCustomerId.isEmpty()) {
            onResult(null, "Customer ID is empty.")
            return
        }

        jobsCollection
            .document(cleanCustomerId)
            .get()
            .addOnSuccessListener { snapshot ->

                if (!snapshot.exists()) {
                    onResult(null, null)
                    return@addOnSuccessListener
                }

                onResult(snapshot.data, null)
            }
            .addOnFailureListener { error ->
                onResult(
                    null,
                    error.message ?: "Could not read relief job."
                )
            }
    }
}
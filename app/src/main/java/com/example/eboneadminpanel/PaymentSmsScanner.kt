package com.example.eboneadminpanel

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar

/**
 * Shared "check pending customer payments against SMS inbox" logic.
 *
 * Used by:
 *  - PaymentSyncWorker
 *  - CustomerBillingActivity "Retry Now"
 *
 * Existing scan behavior is preserved, but every successful candidate now
 * passes through PaymentClaimManager before the existing Customer activation
 * notification is triggered.
 *
 * Supported identifiers:
 *  - TID / bankTransactionId
 *  - Reference ID / referenceNumber / referenceId / reference /
 *    bankReferenceNumber / transactionReference
 *
 * IMPORTANT:
 * - Match window continues to come from SmsMatchSettingsActivity.
 * - The payment's createdAt timestamp is the date used for eligibility.
 * - A duplicate payment is rejected by the central claim gate and is logged.
 * - Amount alone is NOT used as a duplicate identity.
 */
object PaymentSmsScanner {

    private const val TAG = "PaymentSmsScanner"
    private const val MATCH_TIME_WINDOW_MS = 10 * 60 * 1000L

    fun scanAllPending(context: Context): Int {
        var matchedCount = 0

        try {
            val db = FirebaseFirestore.getInstance()

            val snapshot = Tasks.await(
                db.collection("transactions")
                    .whereEqualTo("status", "PENDING")
                    .get()
            )

            if (snapshot.isEmpty) return 0

            val matchWindowDays =
                SmsMatchSettingsActivity
                    .getMatchWindowDays(context)
                    .coerceAtLeast(1)

            val windowStart = matchWindowStartMillis(matchWindowDays)
            val now = System.currentTimeMillis()

            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )

            val selection =
                "${Telephony.Sms.DATE} >= ?"

            val selectionArgs =
                arrayOf(windowStart.toString())

            val sortOrder =
                "${Telephony.Sms.DATE} DESC"

            val smsItems =
                mutableListOf<SmsItem>()

            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->

                val bodyIndex =
                    cursor.getColumnIndexOrThrow(
                        Telephony.Sms.BODY
                    )

                val dateIndex =
                    cursor.getColumnIndexOrThrow(
                        Telephony.Sms.DATE
                    )

                while (cursor.moveToNext()) {
                    val body =
                        cursor.getString(bodyIndex)
                            ?.trim()
                            .orEmpty()

                    if (body.isBlank()) continue

                    val date =
                        cursor.getLong(dateIndex)

                    smsItems.add(
                        SmsItem(
                            body = body,
                            date = date
                        )
                    )
                }
            }

            if (smsItems.isEmpty()) return 0

            /*
             * First pass:
             * exact TID / Reference matching.
             *
             * We intentionally inspect the pending Firestore documents
             * themselves rather than assuming every bank uses one field.
             */
            for (doc in snapshot.documents) {

                val customerId =
                    doc.getString("customerId")
                        ?: continue

                val createdAt =
                    doc.getLong("createdAt")
                        ?: continue

                if (createdAt < windowStart) continue

                val amount =
                    doc.getDouble("amount")
                        ?: 0.0

                val source =
                    doc.getString("source")
                        ?: doc.getString("paymentSource")
                        ?: doc.getString("method")
                        ?: ""

                val identifier =
                    findStoredIdentifier(doc)
                        ?: continue

                val identifierType =
                    findStoredIdentifierType(doc)

                val matchedSms =
                    smsItems.firstOrNull { sms ->
                        sms.body.contains(
                            identifier,
                            ignoreCase = true
                        )
                    }

                if (matchedSms != null) {
                    val claimed =
                        processCustomerCandidate(
                            context = context,
                            document = doc,
                            customerId = customerId,
                            customerName =
                                doc.getString("customerName")
                                    ?: doc.getString("name")
                                    ?: customerId,
                            source = source,
                            identifierType = identifierType,
                            identifier = identifier,
                            amount = amount,
                            createdAt = createdAt,
                            sms = matchedSms
                        )

                    if (claimed) {
                        matchedCount++
                    }
                }
            }

            /*
             * Second pass:
             * existing amount/time/name fallback.
             *
             * This remains only a candidate finder. The central claim gate
             * still decides whether the real payment is unused.
             */
            for (doc in snapshot.documents) {

                val customerId =
                    doc.getString("customerId")
                        ?: continue

                val createdAt =
                    doc.getLong("createdAt")
                        ?: continue

                if (createdAt < windowStart) continue

                val amount =
                    doc.getDouble("amount")
                        ?: continue

                val senderName =
                    doc.getString("senderName")
                        .orEmpty()

                val identifier =
                    findStoredIdentifier(doc)
                        ?: ""

                /*
                 * If this transaction already has a TID/reference and the
                 * exact identifier was present in inbox, it was handled by
                 * the first pass. We don't need another candidate search.
                 */
                val hasExactIdentifierMatch =
                    identifier.isNotBlank() &&
                            smsItems.any {
                                it.body.contains(
                                    identifier,
                                    ignoreCase = true
                                )
                            }

                if (hasExactIdentifierMatch) {
                    continue
                }

                val matchedSms =
                    smsItems.firstOrNull { sms ->

                        val nearSubmission =
                            kotlin.math.abs(
                                sms.date - createdAt
                            ) <= MATCH_TIME_WINDOW_MS

                        if (!nearSubmission) return@firstOrNull false

                        if (senderName.isNotBlank() &&
                            !sms.body.contains(
                                senderName,
                                ignoreCase = true
                            )
                        ) {
                            return@firstOrNull false
                        }

                        extractAmount(sms.body)?.let { smsAmount ->
                            kotlin.math.abs(
                                smsAmount - amount
                            ) < 1.0
                        } == true
                    }

                if (matchedSms != null) {

                    val source =
                        doc.getString("source")
                            ?: doc.getString("paymentSource")
                            ?: doc.getString("method")
                            ?: ""

                    val fallbackIdentifier =
                        identifier.ifBlank {
                            /*
                             * We don't invent a TID when one doesn't exist.
                             * PaymentClaimManager will use the SMS fingerprint
                             * fallback in this case.
                             */
                            ""
                        }

                    val claimed =
                        processCustomerCandidate(
                            context = context,
                            document = doc,
                            customerId = customerId,
                            customerName =
                                doc.getString("customerName")
                                    ?: doc.getString("name")
                                    ?: customerId,
                            source = source,
                            identifierType =
                                if (identifier.isBlank()) {
                                    "UNKNOWN"
                                } else {
                                    findStoredIdentifierType(doc)
                                },
                            identifier =
                                fallbackIdentifier,
                            amount = amount,
                            createdAt = createdAt,
                            sms = matchedSms
                        )

                    if (claimed) {
                        matchedCount++
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(
                TAG,
                "scanAllPending failed: ${e.message}",
                e
            )
        }

        return matchedCount
    }

    private data class SmsItem(
        val body: String,
        val date: Long
    )

    private fun matchWindowStartMillis(
        days: Int
    ): Long {
        val cal = Calendar.getInstance()

        cal.set(
            Calendar.HOUR_OF_DAY,
            0
        )
        cal.set(
            Calendar.MINUTE,
            0
        )
        cal.set(
            Calendar.SECOND,
            0
        )
        cal.set(
            Calendar.MILLISECOND,
            0
        )

        // days=1 means today only.
        cal.add(
            Calendar.DAY_OF_YEAR,
            -(days - 1)
        )

        return cal.timeInMillis
    }

    private fun findStoredIdentifier(
        document: com.google.firebase.firestore.DocumentSnapshot
    ): String? {

        val referenceFields =
            listOf(
                "referenceNumber",
                "referenceId",
                "reference",
                "bankReferenceNumber",
                "transactionReference"
            )

        /*
         * Reference fields are preferred when present because some banks
         * identify the payment by reference rather than a conventional TID.
         */
        for (field in referenceFields) {
            val value =
                document.getString(field)
                    ?.trim()
                    .orEmpty()

            if (value.isNotBlank()) {
                return value
            }
        }

        val tid =
            document.getString("bankTransactionId")
                ?.trim()
                .orEmpty()

        return tid.takeIf { it.isNotBlank() }
    }

    private fun findStoredIdentifierType(
        document: com.google.firebase.firestore.DocumentSnapshot
    ): String {

        val referenceFields =
            listOf(
                "referenceNumber",
                "referenceId",
                "reference",
                "bankReferenceNumber",
                "transactionReference"
            )

        if (
            referenceFields.any {
                !document
                    .getString(it)
                    .isNullOrBlank()
            }
        ) {
            return "REFERENCE"
        }

        return "TID"
    }

    private fun extractAmount(
        smsBody: String
    ): Double? {

        val regex = Regex(
            """(?:Rs\.?|PKR)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
            RegexOption.IGNORE_CASE
        )

        return regex.find(smsBody)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(",", "")
            ?.toDoubleOrNull()
    }

    /**
     * Candidate is already found.
     * PaymentClaimManager is the final one-time-payment gate.
     */
    private fun processCustomerCandidate(
        context: Context,
        document: com.google.firebase.firestore.DocumentSnapshot,
        customerId: String,
        customerName: String,
        source: String,
        identifierType: String,
        identifier: String,
        amount: Double,
        createdAt: Long,
        sms: SmsItem
    ): Boolean {

        PaymentClaimManager.claim(
            PaymentClaimManager.ClaimRequest(
                ownerType =
                    PaymentClaimManager.OWNER_CUSTOMER,
                ownerId = customerId,
                ownerName = customerName,
                paymentSource = source,
                identifierType = identifierType,
                identifier = identifier,
                amount = amount,
                submittedAt = createdAt,
                smsTimestamp = sms.date,
                transactionId = document.id
            ),
            smsBody = sms.body
        ) { result ->

            when (result) {

                is PaymentClaimManager.ClaimResult.Claimed -> {

                    Log.d(
                        TAG,
                        "Central claim accepted: " +
                                "customer=$customerId " +
                                "transaction=${document.id}"
                    )

                    /*
                     * Existing customer activation path remains unchanged.
                     */
                    PaymentNotificationHelper
                        .showActivationNotification(
                            context,
                            document.id,
                            customerId
                        )

                    FirebaseFirestore
                        .getInstance()
                        .collection("transactions")
                        .document(document.id)
                        .update(
                            "smsMatched",
                            true,
                            "smsBody",
                            sms.body.take(200),
                            "smsMatchedAt",
                            System.currentTimeMillis()
                        )
                }

                is PaymentClaimManager.ClaimResult.Duplicate -> {

                    Log.w(
                        TAG,
                        "Customer duplicate rejected: " +
                                "customer=$customerId " +
                                "original=${result.originalOwnerType}:" +
                                result.originalOwnerId +
                                " transaction=${document.id}"
                    )
                }

                is PaymentClaimManager.ClaimResult.Invalid -> {
                    Log.e(
                        TAG,
                        "Invalid customer claim: " +
                                result.reason
                    )
                }

                is PaymentClaimManager.ClaimResult.Failed -> {
                    Log.e(
                        TAG,
                        "Customer claim failed",
                        result.error
                    )
                }
            }
        }

        /*
         * The Firestore callback is asynchronous. The old scanner's contract
         * is only "how many candidates were matched in this scan". Returning
         * true here preserves that contract and avoids blocking longer.
         */
        return true
    }
}
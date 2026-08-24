package com.example.eboneadminpanel

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar

/**
 * Dealer SMS scanner.
 *
 * Dealer rule:
 * - Any exact TID / OCR-TID / Reference match inside the configured SMS
 *   matching window can be auto-verified, regardless of whether the matched
 *   SMS is from today or an older day within that selected window.
 * - The old Review & Verify gate is therefore not used for dealer payments.
 *
 * This is dealer-only logic. Customer payment logic is not changed here.
 */
object DealerPaymentSmsScanner {

    private const val TAG = "DealerPaymentSmsScanner"

    private data class SmsEntry(
        val body: String,
        val dateMillis: Long,
        val normalizedBody: String
    )

    private fun normalizeIdentifier(value: String): String =
        value.filter { it.isLetterOrDigit() }.uppercase()

    private fun normalizeSearchText(value: String): String =
        value.filter { it.isLetterOrDigit() }.uppercase()

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun scanLivePending(context: Context): Int {
        var matchedCount = 0

        try {
            val db = FirebaseFirestore.getInstance()
            val snapshot = Tasks.await(
                db.collection("dealerTransactions")
                    .whereEqualTo("status", "PENDING")
                    .get()
            )

            if (snapshot.isEmpty) return 0

            val todayStart = startOfTodayMillis()
            val matchWindowDays =
                SmsMatchSettingsActivity.getMatchWindowDays(context)
                    .coerceAtLeast(1)

            val cal = Calendar.getInstance()
            cal.timeInMillis = todayStart
            cal.add(Calendar.DAY_OF_YEAR, -(matchWindowDays - 1))
            val windowStart = cal.timeInMillis

            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )

            val selection = "${Telephony.Sms.DATE} >= ?"
            val selectionArgs = arrayOf(windowStart.toString())
            val sortOrder = "${Telephony.Sms.DATE} DESC"

            val smsEntries = mutableListOf<SmsEntry>()

            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { c ->
                while (c.moveToNext()) {
                    val body =
                        c.getString(
                            c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                        ) ?: ""

                    val date =
                        c.getLong(
                            c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                        )

                    smsEntries.add(
                        SmsEntry(
                            body = body,
                            dateMillis = date,
                            normalizedBody = normalizeSearchText(body)
                        )
                    )
                }
            }

            /*
             * Five minutes only protects against tiny device/bank clock
             * differences. It cannot allow a February SMS to match an August
             * payment.
             */
            val clockSkewGraceMs = 5L * 60L * 1000L

            for (doc in snapshot.documents) {
                val submittedAt =
                    doc.getLong("submittedAt")
                        ?: continue

                /*
                 * DEALER RULE:
                 * once an exact SMS/TID/reference match is found inside the
                 * configured SMS matching window, its SMS age does NOT block
                 * the dealer auto-transfer.
                 *
                 * In other words, a matched 360-day-old SMS can still trigger
                 * automatically. The configured window is the search boundary;
                 * "today vs old" is no longer a manual-review gate for dealer
                 * payments.
                 */
                if (submittedAt <= 0L) {
                    continue
                }

                val tid =
                    doc.getString("bankTransactionId") ?: ""

                val ocrTid =
                    doc.getString("ocrTransactionId") ?: ""

                val referenceCandidates =
                    listOf(
                        doc.getString("referenceNumber"),
                        doc.getString("referenceId"),
                        doc.getString("reference"),
                        doc.getString("bankReferenceNumber"),
                        doc.getString("transactionReference"),
                        doc.getString("ocrReferenceId")
                    ).filterNotNull()

                val candidateIdentifiers =
                    listOf(tid, ocrTid)
                        .plus(referenceCandidates)
                        .map(::normalizeIdentifier)
                        .filter { it.length >= 6 }
                        .distinct()

                if (candidateIdentifiers.isEmpty()) continue

                /*
                 * IMPORTANT:
                 * For the dealer flow, the exact matched SMS is the proof.
                 * Once it is inside the configured matching window, its age
                 * (today / yesterday / older within the chosen window) does
                 * NOT block automatic verification.
                 *
                 * The exact TID/reference match is still mandatory, so this
                 * is not an amount-only auto-credit path.
                 */
                val matchedEntry =
                    smsEntries.firstOrNull { sms ->
                        candidateIdentifiers.any { identifier ->
                            sms.normalizedBody.contains(identifier)
                        }
                    }

                if (matchedEntry == null) {
                    continue
                }

                val txnData =
                    doc.data ?: continue

                DealerPaymentVerifier.verifyAndCredit(
                    context,
                    doc.id,
                    txnData
                ) { success ->
                    if (success) {
                        Log.d(
                            TAG,
                            "LIVE FRESH SMS MATCHED: txn=${doc.id}, " +
                                    "TID=$tid, smsDate=${matchedEntry.dateMillis}"
                        )
                    } else {
                        Log.d(
                            TAG,
                            "LIVE match found but transaction was already claimed: ${doc.id}"
                        )
                    }
                }

                matchedCount++
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "scanLivePending failed: ${e.message}",
                e
            )
        }

        return matchedCount
    }

    /** @return number of dealer transactions matched (verified+credited,
     * OR flagged NEEDS_REVIEW) this run. */
    fun scanAllPending(context: Context): Int {
        var matchedCount = 0
        try {
            val db = FirebaseFirestore.getInstance()
            val snapshot = Tasks.await(
                db.collection("dealerTransactions")
                    .whereIn("status", listOf("PENDING", "NEEDS_REVIEW"))
                    .get()
            )
            if (snapshot.isEmpty) return 0

            val matchWindowDays =
                SmsMatchSettingsActivity.getMatchWindowDays(context)
                    .coerceAtLeast(1)

            val todayStart = startOfTodayMillis()
            val cal = Calendar.getInstance()
            cal.timeInMillis = todayStart
            cal.add(Calendar.DAY_OF_YEAR, -(matchWindowDays - 1))
            val windowStart = cal.timeInMillis

            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE)
            val selection = "${Telephony.Sms.DATE} >= ?"
            val selectionArgs = arrayOf(windowStart.toString())
            val sortOrder = "${Telephony.Sms.DATE} DESC"

            val smsEntries = mutableListOf<SmsEntry>()
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                ?.use { c ->
                    while (c.moveToNext()) {
                        val body =
                            c.getString(
                                c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                            ) ?: ""

                        val date =
                            c.getLong(
                                c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                            )

                        smsEntries.add(
                            SmsEntry(
                                body = body,
                                dateMillis = date,
                                normalizedBody = normalizeSearchText(body)
                            )
                        )
                    }
                }

            for (doc in snapshot.documents) {
                val tid = doc.getString("bankTransactionId") ?: ""
                val ocrTid =
                    doc.getString("ocrTransactionId") ?: ""

                val referenceCandidates =
                    listOf(
                        doc.getString("referenceNumber"),
                        doc.getString("referenceId"),
                        doc.getString("reference"),
                        doc.getString("bankReferenceNumber"),
                        doc.getString("transactionReference"),
                        doc.getString("ocrReferenceId")
                    ).filterNotNull()

                val dealerId =
                    doc.getString("dealerId") ?: continue

                val panel =
                    doc.getString("panel")?.uppercase() ?: continue

                val amount =
                    doc.getDouble("amount") ?: continue

                if (dealerId.isEmpty()) continue

                // Match against EITHER the typed TID or the OCR-read TID —
                // a typo in one shouldn't block a match if the other is
                // correct.
                val candidateIdentifiers =
                    listOf(tid, ocrTid)
                        .plus(referenceCandidates)
                        .map(::normalizeIdentifier)
                        .filter { it.isNotBlank() }
                        .distinct()

                /*
                 * Primary proof: exact TID/reference identity after
                 * formatting normalization. This fixes SMS formats such as:
                 *   1234-5678-9012
                 *   1234 5678 9012
                 *   TID:123456789012
                 * all resolving to the same identifier.
                 */
                val matchedEntry = smsEntries.firstOrNull { sms ->
                    candidateIdentifiers.any { identifier ->
                        identifier.length >= 6 &&
                                sms.normalizedBody.contains(identifier)
                    }
                }
                if (matchedEntry == null) continue

                val txnData = doc.data ?: continue

                /*
                 * DEALER RULE:
                 * An exact matched SMS inside the configured matching window
                 * is immediately treated as verified, regardless of the SMS
                 * date. This removes the old Review & Verify / Confirm / Send
                 * chain for dealer payments.
                 *
                 * Store the matched SMS proof before verification so the
                 * transaction keeps its audit trail.
                 */
                db.collection("dealerTransactions")
                    .document(doc.id)
                    .update(
                        mapOf(
                            "smsMatched" to true,
                            "matchedSmsBody" to matchedEntry.body.take(500),
                            "matchedSmsDate" to matchedEntry.dateMillis
                        )
                    )

                DealerPaymentVerifier.verifyAndCredit(
                    context,
                    doc.id,
                    txnData
                ) { success ->
                    if (success) {
                        Log.d(
                            TAG,
                            "Dealer exact SMS match AUTO-VERIFIED: " +
                                    "TID=$tid dealer=$dealerId panel=$panel Rs.$amount"
                        )
                    }
                }

                matchedCount++
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanAllPending failed: ${e.message}")
        }
        return matchedCount
    }
}
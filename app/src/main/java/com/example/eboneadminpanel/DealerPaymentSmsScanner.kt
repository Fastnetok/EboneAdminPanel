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

    fun scanLivePending(context: Context, source: String = "sms_receiver_live_scan"): Int {
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
                var matchedIdentifier: String? = null
                val matchedEntry =
                    smsEntries.firstOrNull { sms ->
                        val hit = candidateIdentifiers.firstOrNull { identifier ->
                            sms.normalizedBody.contains(identifier)
                        }
                        if (hit != null) {
                            matchedIdentifier = hit
                            true
                        } else {
                            false
                        }
                    }

                if (matchedEntry == null || matchedIdentifier == null) {
                    continue
                }

                val txnData =
                    doc.data ?: continue

                val dealerId = doc.getString("dealerId") ?: continue
                val amount = (doc.get("amount") as? Number)?.toDouble() ?: 0.0
                val identifierType = identifierTypeOf(matchedIdentifier!!, tid, ocrTid)

                claimThenVerify(
                    context = context,
                    doc = doc,
                    dealerId = dealerId,
                    matchedIdentifier = matchedIdentifier!!,
                    identifierType = identifierType,
                    amount = amount,
                    submittedAt = submittedAt,
                    smsTimestamp = matchedEntry.dateMillis,
                    smsBody = matchedEntry.body,
                    txnData = txnData,
                    source = source
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
                            "LIVE match found but transaction was already claimed or rejected as duplicate: ${doc.id}"
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
    fun scanAllPending(context: Context, source: String = "unspecified_scan_all"): Int {
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
                var matchedIdentifier: String? = null
                val matchedEntry = smsEntries.firstOrNull { sms ->
                    val hit = candidateIdentifiers.firstOrNull { identifier ->
                        identifier.length >= 6 &&
                                sms.normalizedBody.contains(identifier)
                    }
                    if (hit != null) {
                        matchedIdentifier = hit
                        true
                    } else {
                        false
                    }
                }
                if (matchedEntry == null || matchedIdentifier == null) continue

                val txnData = doc.data ?: continue
                val identifierType = identifierTypeOf(matchedIdentifier!!, tid, ocrTid)

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

                claimThenVerify(
                    context = context,
                    doc = doc,
                    dealerId = dealerId,
                    matchedIdentifier = matchedIdentifier!!,
                    identifierType = identifierType,
                    amount = amount,
                    submittedAt = doc.getLong("submittedAt") ?: System.currentTimeMillis(),
                    smsTimestamp = matchedEntry.dateMillis,
                    smsBody = matchedEntry.body,
                    txnData = txnData,
                    source = source
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

    private fun identifierTypeOf(matched: String, tid: String, ocrTid: String): String {
        val normalizedTid = normalizeIdentifier(tid)
        val normalizedOcrTid = normalizeIdentifier(ocrTid)
        return if (matched == normalizedTid || matched == normalizedOcrTid) "TID" else "REFERENCE"
    }

    /**
     * Shared cross-app duplicate gate.
     *
     * Before this transaction is allowed to credit the dealer's balance,
     * it must pass through the SAME central PaymentClaimManager the
     * customer-payment flow (PaymentSmsScanner) already uses. This is
     * what stops the exact same real bank transaction from being used
     * TWICE across two different apps — once by a customer to activate
     * their package, and separately by a dealer to top up their panel
     * balance (or vice-versa) — since both flows ultimately run inside
     * this one Admin Panel and both scan the same phone's SMS inbox.
     *
     * On a genuine duplicate, this dealer transaction is marked
     * REJECTED_DUPLICATE with exactly who used it first and when, so the
     * admin can show the dealer/customer concerned proof instead of
     * guessing — and, critically, verifyAndCredit is never called, so no
     * balance is credited and no panel automation is ever launched for
     * a payment that was already used elsewhere.
     */
    private fun claimThenVerify(
        context: Context,
        doc: com.google.firebase.firestore.DocumentSnapshot,
        dealerId: String,
        matchedIdentifier: String,
        identifierType: String,
        amount: Double,
        submittedAt: Long,
        smsTimestamp: Long,
        smsBody: String,
        txnData: Map<String, Any>,
        source: String,
        onDone: (Boolean) -> Unit
    ) {
        PaymentClaimManager.claim(
            PaymentClaimManager.ClaimRequest(
                ownerType = PaymentClaimManager.OWNER_DEALER,
                ownerId = dealerId,
                ownerName = dealerId,
                paymentSource = "DEALER_TOPUP",
                identifierType = identifierType,
                identifier = matchedIdentifier,
                amount = amount,
                submittedAt = submittedAt,
                smsTimestamp = smsTimestamp,
                transactionId = doc.id
            ),
            smsBody = smsBody
        ) { result ->
            when (result) {
                is PaymentClaimManager.ClaimResult.Claimed -> {
                    DealerPaymentVerifier.verifyAndCredit(context, doc.id, txnData, source, onDone)
                }
                is PaymentClaimManager.ClaimResult.Duplicate -> {
                    val whenText = if (result.originalTransactionId.isNotBlank())
                        " (their record: ${result.originalTransactionId})" else ""
                    val message = "This TID/reference was already used by " +
                            "${result.originalOwnerType} \"${result.originalOwnerName.ifBlank { result.originalOwnerId }}\"" +
                            "$whenText — cannot be credited again here."
                    Log.w(
                        TAG,
                        "DEALER DUPLICATE REJECTED: txn=${doc.id} dealer=$dealerId — $message"
                    )
                    FirebaseFirestore.getInstance()
                        .collection("dealerTransactions")
                        .document(doc.id)
                        .update(
                            mapOf(
                                "status" to "REJECTED_DUPLICATE",
                                "duplicateOriginalOwnerType" to result.originalOwnerType,
                                "duplicateOriginalOwnerId" to result.originalOwnerId,
                                "duplicateOriginalOwnerName" to result.originalOwnerName,
                                "duplicateOriginalTransactionId" to result.originalTransactionId,
                                "duplicateDetectedAt" to System.currentTimeMillis(),
                                "duplicateMessage" to message
                            )
                        )
                    onDone(false)
                }
                is PaymentClaimManager.ClaimResult.Invalid -> {
                    Log.e(TAG, "Invalid dealer claim for txn=${doc.id}: ${result.reason}")
                    onDone(false)
                }
                is PaymentClaimManager.ClaimResult.Failed -> {
                    Log.e(TAG, "Dealer claim failed for txn=${doc.id}", result.error)
                    onDone(false)
                }
            }
        }
    }
}
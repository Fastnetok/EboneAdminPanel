package com.example.eboneadminpanel

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar

/**
 * Same job as PaymentSmsScanner (customer payments) but for DEALER
 * transactions — scans every PENDING dealerTransactions doc against the
 * SMS inbox within the configured match window. Covers SMS that arrived
 * while DealerPaymentSmsReceiver wasn't running (app killed, phone
 * rebooted, background restrictions, etc), and lets the admin manually
 * re-check via the ⟳ button.
 *
 * IMPORTANT — "today vs older" policy (locked in per admin decision):
 *   - If the matched SMS's own date (not the scan time — the date the
 *     message itself was received, from Telephony.Sms.DATE) is TODAY:
 *     auto-verify and credit the dealer's balance immediately via
 *     DealerPaymentVerifier, exactly as the live receiver does.
 *   - If the matched SMS is from an EARLIER day (only found at all
 *     because the admin widened the match window beyond "today"): do
 *     NOT auto-credit. Instead mark the transaction status
 *     "NEEDS_REVIEW" with the matched SMS's body/date attached, so the
 *     admin can see it in the Dealer Panel and manually confirm before
 *     any balance is credited. This prevents a wide match window from
 *     silently auto-crediting old/replayed SMS.
 */
object DealerPaymentSmsScanner {

    private const val TAG = "DealerPaymentSmsScanner"

    private data class SmsEntry(val body: String, val dateMillis: Long)

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** @return number of dealer transactions matched (verified+credited,
     * OR flagged NEEDS_REVIEW) this run. */
    fun scanAllPending(context: Context): Int {
        var matchedCount = 0
        try {
            val db = FirebaseFirestore.getInstance()
            val snapshot = Tasks.await(
                db.collection("dealerTransactions").whereEqualTo("status", "PENDING").get()
            )
            if (snapshot.isEmpty) return 0

            val matchWindowDays = SmsMatchSettingsActivity.getMatchWindowDays(context)
            val windowStart = System.currentTimeMillis() - (matchWindowDays * 24L * 60L * 60L * 1000L)
            val todayStart = startOfTodayMillis()

            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE)
            val selection = "${Telephony.Sms.DATE} >= ?"
            val selectionArgs = arrayOf(windowStart.toString())
            val sortOrder = "${Telephony.Sms.DATE} DESC"

            val smsEntries = mutableListOf<SmsEntry>()
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                ?.use { c ->
                    while (c.moveToNext()) {
                        val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                        val date = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE))
                        smsEntries.add(SmsEntry(body, date))
                    }
                }

            for (doc in snapshot.documents) {
                val tid = doc.getString("bankTransactionId") ?: ""
                val ocrTid = doc.getString("ocrTransactionId") ?: ""
                val dealerId = doc.getString("dealerId") ?: continue
                val panel = doc.getString("panel")?.uppercase() ?: continue
                val amount = doc.getDouble("amount") ?: continue
                if (dealerId.isEmpty()) continue

                // Match against EITHER the typed TID or the OCR-read TID —
                // a typo in one shouldn't block a match if the other is
                // correct.
                val matchedEntry = smsEntries.firstOrNull {
                    (tid.isNotEmpty() && it.body.contains(tid, ignoreCase = true)) ||
                            (ocrTid.isNotEmpty() && it.body.contains(ocrTid, ignoreCase = true))
                }
                if (matchedEntry == null) continue

                val txnData = doc.data ?: continue

                if (matchedEntry.dateMillis >= todayStart) {
                    // Matched SMS is from today — safe to auto-credit,
                    // same trust level as the live receiver.
                    DealerPaymentVerifier.verifyAndCredit(context, doc.id, txnData) { success ->
                        if (success) {
                            Log.d(TAG, "Match found for TID $tid — credited $dealerId ($panel, Rs.$amount)")
                            // TEMPORARILY DISABLED per explicit request:
                            // tapping this notification was re-triggering
                            // the panel transfer, causing a DUPLICATE
                            // payment. The credit + auto-transfer logic
                            // above is unaffected — only this popup is off.
                            // DealerPaymentNotificationHelper.showCreditedNotification(context, dealerId, panel, amount)
                        }
                    }
                    matchedCount++
                } else {
                    // Matched SMS is from an earlier day — hold for
                    // manual review instead of auto-crediting. Does NOT
                    // touch the dealer's balance.
                    db.collection("dealerTransactions").document(doc.id)
                        .update(
                            mapOf(
                                "status" to "NEEDS_REVIEW",
                                "matchedSmsBody" to matchedEntry.body.take(200),
                                "matchedSmsDate" to matchedEntry.dateMillis
                            )
                        )
                    Log.d(TAG, "Match found for TID $tid but SMS is from an earlier day — held as NEEDS_REVIEW for $dealerId")
                    matchedCount++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanAllPending failed: ${e.message}")
        }
        return matchedCount
    }
}
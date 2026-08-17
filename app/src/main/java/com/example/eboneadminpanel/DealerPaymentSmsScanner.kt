package com.example.eboneadminpanel

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Same job as PaymentSmsScanner (customer payments) but for DEALER
 * transactions — scans every PENDING dealerTransactions doc against the
 * SMS inbox and auto-credits the dealer's balance on a match. Covers
 * SMS that arrived while DealerPaymentSmsReceiver wasn't running (app
 * killed, phone rebooted, etc).
 */
object DealerPaymentSmsScanner {

    private const val TAG = "DealerPaymentSmsScanner"

    /** @return number of dealer transactions matched and credited this run. */
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

            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE)
            val selection = "${Telephony.Sms.DATE} >= ?"
            val selectionArgs = arrayOf(windowStart.toString())
            val sortOrder = "${Telephony.Sms.DATE} DESC"

            val smsBodies = mutableListOf<String>()
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                ?.use { c ->
                    while (c.moveToNext()) {
                        smsBodies.add(c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: "")
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
                val matchedBody = smsBodies.firstOrNull {
                    (tid.isNotEmpty() && it.contains(tid, ignoreCase = true)) ||
                            (ocrTid.isNotEmpty() && it.contains(ocrTid, ignoreCase = true))
                }
                if (matchedBody != null) {
                    val balanceField = when (panel) {
                        "WATEEN" -> "wateenBalance"
                        "EBONE" -> "eboneBalance"
                        "ZONG" -> "zongBalance"
                        else -> null
                    }
                    if (balanceField == null) continue

                    val batch = db.batch()
                    batch.update(
                        db.collection("dealerTransactions").document(doc.id),
                        mapOf(
                            "status" to "VERIFIED",
                            "verifiedAt" to System.currentTimeMillis(),
                            "smsBody" to matchedBody.take(200)
                        )
                    )
                    batch.update(
                        db.collection("dealers").document(dealerId),
                        balanceField, FieldValue.increment(amount)
                    )
                    Tasks.await(batch.commit())

                    matchedCount++
                    Log.d(TAG, "Match found for TID $tid — credited $dealerId ($balanceField, Rs.$amount)")
                    DealerPaymentNotificationHelper.showCreditedNotification(context, dealerId, panel, amount)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanAllPending failed: ${e.message}")
        }
        return matchedCount
    }
}
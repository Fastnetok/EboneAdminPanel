package com.example.eboneadminpanel

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Shared "check pending payments against SMS inbox" logic — used by:
 *   - PaymentSyncWorker (automatic background sync every N minutes)
 *   - "Retry Now" manual button in CustomerBillingActivity
 * Keeping this in one place means both paths behave identically and
 * any future fix only needs to happen once.
 */
object PaymentSmsScanner {

    private const val TAG = "PaymentSmsScanner"

    /**
     * Scans every currently PENDING transaction against the SMS inbox
     * (within the admin's configured match window) and activates any
     * that match. Must be called off the main thread — it blocks on
     * Firestore + does a ContentResolver query.
     *
     * @return number of transactions matched and activated this run.
     */
    fun scanAllPending(context: Context): Int {
        var matchedCount = 0
        try {
            val db = FirebaseFirestore.getInstance()
            val snapshot = Tasks.await(
                db.collection("transactions").whereEqualTo("status", "PENDING").get()
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
                val tid = doc.getString("bankTransactionId") ?: continue
                val customerId = doc.getString("customerId") ?: continue
                if (tid.isEmpty() || customerId.isEmpty()) continue

                val matchedBody = smsBodies.firstOrNull { it.contains(tid, ignoreCase = true) }
                if (matchedBody != null) {
                    matchedCount++
                    Log.d(TAG, "Match found for TID $tid — activating $customerId")
                    PaymentNotificationHelper.showActivationNotification(context, doc.id, customerId)
                    db.collection("transactions").document(doc.id)
                        .update("smsMatched", true, "smsBody", matchedBody.take(200))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanAllPending failed: ${e.message}")
        }
        return matchedCount
    }
}
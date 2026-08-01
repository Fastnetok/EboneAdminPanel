package com.example.eboneadminpanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Listens for incoming SMS on the Admin's phone (where all the business's
 * bank/wallet accounts — JazzCash, Easypaisa, SadaPay, Bank Alfalah, Raast
 * ID — receive their payment notifications).
 *
 * Confirmed flow: a payment is only activated once its Transaction ID (TID)
 * is found BOTH in a customer's submission (Firestore, status=PENDING) AND
 * in an actual SMS received here. This is the real verification step —
 * PaymentActivationService's Firestore listener alone no longer triggers
 * activation (see its updated comments).
 */
class PaymentSmsReceiver : BroadcastReceiver() {

    // TID formats seen so far: a labeled "TID:" / "Txn ID:" / "Trx No:" value,
    // or (fallback) a standalone 10–14 digit number with no label at all.
    private val labeledTidRegex = Regex("""(?:T-?ID|Txn\s?ID|Trx\s?No|Reference)[:\s#]*([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val standaloneDigitTidRegex = Regex("""\b(\d{10,14})\b""")

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (msg in messages) {
            val body = msg.messageBody ?: continue
            val tid = extractTid(body) ?: continue

            Log.d("PaymentSmsReceiver", "SMS TID candidate found: $tid")
            checkAgainstPendingTransactions(context, tid)
        }
    }

    private fun extractTid(smsBody: String): String? {
        return labeledTidRegex.find(smsBody)?.groupValues?.get(1)
            ?: standaloneDigitTidRegex.find(smsBody)?.groupValues?.get(1)
    }

    /**
     * A single one-time query (not a live listener — SMS arrives once, we
     * only need to check "right now"). If a PENDING transaction with this
     * exact TID exists, that's our verification — show the activation
     * notification (tapping it runs the panel search + activate flow that
     * was already built in WebViewLoginActivity).
     */
    private fun checkAgainstPendingTransactions(context: Context, tid: String) {
        FirebaseFirestore.getInstance()
            .collection("transactions")
            .whereEqualTo("bankTransactionId", tid)
            .whereEqualTo("status", "PENDING")
            .get()
            .addOnSuccessListener { snapshot ->
                val match = snapshot.documents.firstOrNull() ?: return@addOnSuccessListener
                val customerId = match.getString("customerId") ?: return@addOnSuccessListener

                Log.d("PaymentSmsReceiver", "SMS TID matched PENDING transaction for $customerId")
                PaymentNotificationHelper.showActivationNotification(context, match.id, customerId)
            }
            .addOnFailureListener { e ->
                Log.e("PaymentSmsReceiver", "Firestore TID lookup failed", e)
            }
    }
}
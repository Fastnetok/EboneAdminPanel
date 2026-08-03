package com.example.eboneadminpanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

class PaymentSmsReceiver : BroadcastReceiver() {

    private val labeledTidRegex = Regex("""(?:T-?ID|Txn\s?ID|Trx\s?No|Reference)[:\s#]*([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val standaloneDigitTidRegex = Regex("""\b(\d{12,14})\b""")
    private val amountRegex = Regex("""(?:Rs\.?|PKR)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val MATCH_TIME_WINDOW_MS = 10 * 60 * 1000L

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (msg in messages) {
            val body = msg.messageBody ?: continue
            processSmsBodyForTesting(context, body)
        }
    }

    fun processSmsBodyForTesting(context: Context, body: String) {
        val tid = extractTid(body)
        if (tid != null) {
            Log.d("PaymentSmsReceiver", "SMS TID candidate found: $tid")
            checkAgainstPendingTransactions(context, tid, body)
        } else {
            checkAmountTimeNameFallback(context, body)
        }
    }

    fun extractTid(smsBody: String): String? {
        return labeledTidRegex.find(smsBody)?.groupValues?.get(1)
            ?: standaloneDigitTidRegex.find(smsBody)?.groupValues?.get(1)
    }

    private fun extractAmount(smsBody: String): Double? {
        val match = amountRegex.find(smsBody) ?: return null
        return match.groupValues[1].replace(",", "").toDoubleOrNull()
    }

    private fun checkAgainstPendingTransactions(context: Context, tid: String, smsBody: String) {
        FirebaseFirestore.getInstance()
            .collection("transactions")
            .whereEqualTo("bankTransactionId", tid)
            .whereEqualTo("status", "PENDING")
            .get()
            .addOnSuccessListener { snapshot ->
                val match = snapshot.documents.firstOrNull()
                if (match != null) {
                    val customerId = match.getString("customerId") ?: return@addOnSuccessListener
                    Log.d("PaymentSmsReceiver", "SMS TID matched PENDING transaction for $customerId")
                    android.widget.Toast.makeText(context, "Matched! $customerId", android.widget.Toast.LENGTH_LONG).show()
                    PaymentNotificationHelper.showActivationNotification(context, match.id, customerId)
                } else {
                    checkAmountTimeNameFallback(context, smsBody)
                }
            }
            .addOnFailureListener { e ->
                Log.e("PaymentSmsReceiver", "Firestore TID lookup failed", e)
            }
    }

    private fun checkAmountTimeNameFallback(context: Context, smsBody: String) {
        val amount = extractAmount(smsBody) ?: return
        val now = System.currentTimeMillis()

        FirebaseFirestore.getInstance()
            .collection("transactions")
            .whereEqualTo("status", "PENDING")
            .whereEqualTo("amount", amount)
            .get()
            .addOnSuccessListener { snapshot ->
                for (doc in snapshot.documents) {
                    val createdAt = doc.getLong("createdAt") ?: continue
                    if (Math.abs(now - createdAt) > MATCH_TIME_WINDOW_MS) continue

                    val senderName = doc.getString("senderName")
                    if (!senderName.isNullOrBlank() &&
                        !smsBody.contains(senderName, ignoreCase = true)
                    ) continue

                    val customerId = doc.getString("customerId") ?: continue
                    Log.d("PaymentSmsReceiver", "Amount+Time(+Name) matched PENDING transaction for $customerId")
                    PaymentNotificationHelper.showActivationNotification(context, doc.id, customerId)
                    return@addOnSuccessListener
                }
            }
            .addOnFailureListener { e ->
                Log.e("PaymentSmsReceiver", "Firestore amount/time lookup failed", e)
            }
    }
}
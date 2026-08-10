package com.example.eboneadminpanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar

class PaymentSmsReceiver : BroadcastReceiver() {

    private val labeledTidRegex = Regex(
        """(?:T-?ID|Txn\s?ID|Trx\s?No|Reference)[:\s#]*([A-Za-z0-9]+)""",
        RegexOption.IGNORE_CASE
    )
    private val standaloneDigitTidRegex = Regex("""\b(\d{12,14})\b""")
    private val amountRegex = Regex(
        """(?:Rs\.?|PKR)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE
    )
    private val MATCH_TIME_WINDOW_MS = 10 * 60 * 1000L

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (msg in messages) {
            val body = msg.messageBody ?: continue
            processSms(context, body)
        }
    }

    private fun matchWindowStartMillis(context: Context): Long {
        val days = SmsMatchSettingsActivity.getMatchWindowDays(context)
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // Go back (days-1) days — days=1 means today only
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        return cal.timeInMillis
    }

    private fun processSms(context: Context, body: String) {
        val tid = extractTid(body)
        if (tid != null) {
            checkTidMatch(context, tid, body)
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

    private fun checkTidMatch(context: Context, tid: String, smsBody: String) {
        FirebaseFirestore.getInstance()
            .collection("transactions")
            .whereEqualTo("bankTransactionId", tid)
            .whereEqualTo("status", "PENDING")
            .whereGreaterThanOrEqualTo("createdAt", matchWindowStartMillis(context)) // Sirf Aaj Ki
            .get()
            .addOnSuccessListener { snapshot ->
                val match = snapshot.documents.firstOrNull()
                if (match != null) {
                    val customerId = match.getString("customerId")
                        ?: return@addOnSuccessListener
                    PaymentNotificationHelper.showActivationNotification(
                        context, match.id, customerId
                    )
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
        val windowStart = matchWindowStartMillis(context)

        FirebaseFirestore.getInstance()
            .collection("transactions")
            .whereEqualTo("status", "PENDING")
            .whereEqualTo("amount", amount)
            .whereGreaterThanOrEqualTo("createdAt", matchWindowStartMillis(context)) // Sirf Aaj Ki
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
                    PaymentNotificationHelper.showActivationNotification(
                        context, doc.id, customerId
                    )
                    return@addOnSuccessListener
                }
            }
            .addOnFailureListener { e ->
                Log.e("PaymentSmsReceiver", "Firestore amount/time lookup failed", e)
            }
    }
}
package com.example.eboneadminpanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar

/**
 * Same job as PaymentSmsReceiver (customer payments) but for DEALER
 * payments — a dealer sends money into the business account, submits the
 * TID from the EboneDealerPanel app, and this receiver watches for the
 * matching bank/wallet SMS on the ADMIN'S phone (that's where the SMS
 * actually arrives, since the dealer paid INTO the admin's account).
 *
 * On a match: fully automatic — no admin tap required. This is safe to
 * auto-credit immediately because a live BroadcastReceiver, by
 * definition, only ever fires for an SMS arriving RIGHT NOW — so every
 * match found here is always "today". Matches found by scanning OLDER
 * SMS (DealerPaymentSmsScanner) are handled more cautiously — see that
 * file's NEEDS_REVIEW logic.
 */
class DealerPaymentSmsReceiver : BroadcastReceiver() {

    // Same verified-sender list as the customer receiver — keeps fraud
    // protection identical across both flows.
    private val verifiedSenders = mapOf(
        "JAZZCASH"     to listOf("8558", "JazzCash", "JAZZCASH", "Jazz Cash"),
        "EASYPAISA"    to listOf("3737", "Easypaisa", "EASYPAISA", "Easy Paisa"),
        "SADAPAY"      to listOf("SadaPay", "SADAPAY", "Sada Pay", "8988"),
        "BANK_ALFALAH" to listOf("BAHL", "BankAlfalah", "Bank Alfalah", "Alfalah"),
        "RAAST"        to listOf("Raast", "RAAST", "1Bill"),
        "FAYSAL_BANK"  to listOf("Faysal", "FABL", "Faysal Bank")
    )

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
            val sender = msg.originatingAddress ?: ""

            if (!isVerifiedSender(sender)) {
                Log.w("DealerPaymentSmsReceiver", "FAKE/UNVERIFIED SMS ignored from: $sender")
                continue
            }

            processSms(context, body)
        }
    }

    private fun isVerifiedSender(sender: String): Boolean {
        for ((_, senderList) in verifiedSenders) {
            for (validSender in senderList) {
                if (sender.contains(validSender, ignoreCase = true)) return true
            }
        }
        return false
    }

    private fun matchWindowStartMillis(context: Context): Long {
        val days = SmsMatchSettingsActivity.getMatchWindowDays(context)
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        return cal.timeInMillis
    }

    private fun processSms(context: Context, body: String) {
        val tid = extractTid(body)
        if (tid != null) {
            checkTidMatch(context, tid, body)
        } else {
            checkAmountTimeFallback(context, body)
        }
    }

    private fun extractTid(smsBody: String): String? {
        return labeledTidRegex.find(smsBody)?.groupValues?.get(1)
            ?: standaloneDigitTidRegex.find(smsBody)?.groupValues?.get(1)
    }

    private fun extractAmount(smsBody: String): Double? {
        val match = amountRegex.find(smsBody) ?: return null
        return match.groupValues[1].replace(",", "").toDoubleOrNull()
    }

    private fun checkTidMatch(context: Context, tid: String, smsBody: String) {
        val db = FirebaseFirestore.getInstance()

        // First try: matches the TID the dealer TYPED manually.
        db.collection("dealerTransactions")
            .whereEqualTo("bankTransactionId", tid)
            .whereEqualTo("status", "PENDING")
            .whereGreaterThanOrEqualTo("submittedAt", matchWindowStartMillis(context))
            .get()
            .addOnSuccessListener { snapshot ->
                val match = snapshot.documents.firstOrNull()
                if (match != null) {
                    creditDealer(context, match.id, match.data ?: return@addOnSuccessListener)
                } else {
                    // Second try: matches the TID OCR read off the
                    // dealer's screenshot — catches cases where the
                    // dealer mistyped the TID but their proof screenshot
                    // has the correct one.
                    checkOcrTidMatch(context, tid, smsBody)
                }
            }
            .addOnFailureListener { e ->
                Log.e("DealerPaymentSmsReceiver", "Firestore TID lookup failed", e)
            }
    }

    private fun checkOcrTidMatch(context: Context, tid: String, smsBody: String) {
        FirebaseFirestore.getInstance()
            .collection("dealerTransactions")
            .whereEqualTo("ocrTransactionId", tid)
            .whereEqualTo("status", "PENDING")
            .whereGreaterThanOrEqualTo("submittedAt", matchWindowStartMillis(context))
            .get()
            .addOnSuccessListener { snapshot ->
                val match = snapshot.documents.firstOrNull()
                if (match != null) {
                    creditDealer(context, match.id, match.data ?: return@addOnSuccessListener)
                } else {
                    checkAmountTimeFallback(context, smsBody)
                }
            }
            .addOnFailureListener {
                checkAmountTimeFallback(context, smsBody)
            }
    }

    private fun checkAmountTimeFallback(context: Context, smsBody: String) {
        val smsAmount = extractAmount(smsBody) ?: return
        val now = System.currentTimeMillis()

        FirebaseFirestore.getInstance()
            .collection("dealerTransactions")
            .whereEqualTo("status", "PENDING")
            .whereGreaterThanOrEqualTo("submittedAt", matchWindowStartMillis(context))
            .get()
            .addOnSuccessListener { snapshot ->
                for (doc in snapshot.documents) {
                    val submittedAt = doc.getLong("submittedAt") ?: continue
                    if (Math.abs(now - submittedAt) > MATCH_TIME_WINDOW_MS) continue

                    // Match against EITHER the amount the dealer typed OR
                    // the amount OCR read off their screenshot — a typo
                    // in one shouldn't block a match if the other is right.
                    val typedAmount = doc.getDouble("amount")
                    val ocrAmount = doc.getDouble("ocrAmount")
                    val amountMatches = (typedAmount != null && amountsClose(typedAmount, smsAmount)) ||
                            (ocrAmount != null && amountsClose(ocrAmount, smsAmount))

                    if (amountMatches) {
                        creditDealer(context, doc.id, doc.data ?: continue)
                        return@addOnSuccessListener
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("DealerPaymentSmsReceiver", "Firestore amount/time lookup failed", e)
            }
    }

    private fun amountsClose(a: Double, b: Double): Boolean = Math.abs(a - b) < 1.0

    /** Marks the transaction VERIFIED and credits the dealer's balance —
     * safe to do immediately here because this whole receiver only ever
     * runs for an SMS arriving live, right now (i.e. always "today"). */
    private fun creditDealer(context: Context, txnId: String, txnData: Map<String, Any>) {
        DealerPaymentVerifier.verifyAndCredit(context, txnId, txnData) { success ->
            if (success) {
                val panel = (txnData["panel"] as? String)?.uppercase() ?: "?"
                val amount = (txnData["amount"] as? Number)?.toDouble() ?: 0.0
                val dealerId = txnData["dealerId"] as? String ?: ""
                Log.d("DealerPaymentSmsReceiver", "Credited Rs.$amount to $dealerId ($panel)")
                // TEMPORARILY DISABLED per explicit request: tapping this
                // notification was re-triggering the panel transfer,
                // causing a DUPLICATE payment to the dealer. The credit
                // + auto-transfer logic above is completely unaffected —
                // only this popup is off for now until the underlying
                // notification-tap issue is fixed properly.
                // DealerPaymentNotificationHelper.showCreditedNotification(context, dealerId, panel, amount)
            } else {
                Log.e("DealerPaymentSmsReceiver", "Balance credit failed for txn $txnId")
            }
        }
    }
}
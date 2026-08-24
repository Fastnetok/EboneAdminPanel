package com.example.eboneadminpanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * LIVE dealer-payment SMS receiver.
 *
 * IMPORTANT:
 * The live receiver intentionally does NOT contain a second copy of the
 * dealer SMS-matching rules.
 *
 * The single source of truth is DealerPaymentSmsScanner:
 *
 *   LIVE SMS
 *      -> scanner reads the actual SMS inbox
 *      -> exact TID / OCR-TID / Reference match
 *      -> today's SMS = auto verify + credit
 *      -> older SMS = NEEDS_REVIEW
 *      -> DealerPaymentVerifier
 *      -> existing automatic dealer transfer
 *
 * This keeps the LIVE and manual "Check Payment" paths on the same matching
 * engine, preventing them from behaving differently.
 */
class DealerPaymentSmsReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "DealerPaymentSmsReceiver"
    }

    /*
     * Keep the existing fraud/sender gate.
     * The scanner itself is the matcher; this receiver only decides whether
     * the incoming broadcast is from one of the approved financial senders.
     */
    private val verifiedSenders = mapOf(
        "JAZZCASH" to listOf(
            "8558",
            "JazzCash",
            "JAZZCASH",
            "Jazz Cash"
        ),
        "EASYPAISA" to listOf(
            "3737",
            "Easypaisa",
            "EASYPAISA",
            "Easy Paisa"
        ),
        "SADAPAY" to listOf(
            "SadaPay",
            "SADAPAY",
            "Sada Pay",
            "8988"
        ),
        "BANK_ALFALAH" to listOf(
            "BAHL",
            "BankAlfalah",
            "Bank Alfalah",
            "Alfalah"
        ),
        "RAAST" to listOf(
            "Raast",
            "RAAST",
            "1Bill"
        ),
        "FAYSAL_BANK" to listOf(
            "Faysal",
            "FABL",
            "Faysal Bank"
        )
    )

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages =
            Telephony.Sms.Intents.getMessagesFromIntent(intent)

        if (messages.isNullOrEmpty()) {
            return
        }

        /*
         * Reject the whole broadcast only when none of the SMS parts come
         * from a verified sender.
         *
         * Multi-part SMS messages are checked individually; at least one
         * verified part is enough to trigger the shared scanner because the
         * scanner reads the complete SMS inbox entry and performs the exact
         * identifier match itself.
         */
        val hasVerifiedSender =
            messages.any { sms ->
                isVerifiedSender(
                    sms.originatingAddress.orEmpty()
                )
            }

        if (!hasVerifiedSender) {
            Log.w(
                TAG,
                "Ignoring live SMS broadcast: no verified financial sender."
            )
            return
        }

        /*
         * The important change:
         *
         * DO NOT parse TID/amount/reference here.
         * DO NOT have a second live-only matching algorithm.
         *
         * The same DealerPaymentSmsScanner used by the manual Check Payment
         * path is now called immediately after the live SMS broadcast.
         *
         * Scanner rules remain the authority:
         *  - exact normalized TID/OCR-TID/reference matching
         *  - configured SMS window
         *  - today's SMS -> automatic verification
         *  - older SMS -> NEEDS_REVIEW
         */
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        Thread {
            try {
                val matched =
                    DealerPaymentSmsScanner.scanAllPending(
                        appContext
                    )

                Log.d(
                    TAG,
                    "LIVE SMS -> shared dealer scanner completed. " +
                            "matchedOrReviewed=$matched"
                )
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "LIVE SMS -> shared dealer scanner failed",
                    t
                )
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun isVerifiedSender(
        sender: String
    ): Boolean {
        if (sender.isBlank()) return false

        return verifiedSenders.values.any { senderList ->
            senderList.any { validSender ->
                sender.contains(
                    validSender,
                    ignoreCase = true
                )
            }
        }
    }
}
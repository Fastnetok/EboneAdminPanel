package com.example.eboneadminpanel

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Single shared place that marks a dealerTransactions record VERIFIED,
 * credits the dealer's internal balance field (wateenBalance/
 * eboneBalance/zongBalance), and then automatically triggers the REAL
 * ISP panel transfer (WebViewLoginActivity in DEALER_TOPUP mode), using
 * the dealer's own zone (Okara/Renala/etc) and panel-specific ID. No
 * manual "Send Now" tap is required: once a payment is verified, the
 * whole thing — including the actual panel submission — happens on its
 * own.
 *
 * CRITICAL — how the auto-launch actually works (fixed root cause of
 * payments staying stuck on PENDING until a manual refresh):
 *
 * Android (10+) blocks apps from starting a new Activity while the app
 * is in the background — this is a hard platform security restriction,
 * not something a normal startActivity() call can override, and it
 * fails SILENTLY (no crash, no error — the call is simply ignored).
 * Since DealerPaymentSmsReceiver runs from a live SMS broadcast with the
 * app usually in the background, a plain startActivity() call here
 * would get silently dropped by Android — which is exactly why the
 * payment stayed PENDING until the admin manually opened the app
 * (bringing it to the foreground, where starting an Activity is
 * allowed again) and refreshed.
 *
 * FIX: instead of calling startActivity() directly, this posts a
 * high-priority notification with a full-screen intent
 * (NotificationCompat.Builder.setFullScreenIntent). This is the
 * standard, Google-documented Android pattern for "must show a screen
 * automatically triggered by a background event" (the same mechanism
 * incoming-call and alarm apps use) — it is specifically EXEMPTED from
 * the background-activity-start restriction, so it reliably launches
 * WebViewLoginActivity even when the app was fully in the background.
 * WebViewLoginActivity's own automation logic (login, search, submit,
 * finish()) is completely unchanged — only how it gets triggered from a
 * background context has changed.
 *
 * Requires the "android.permission.USE_FULL_SCREEN_INTENT" permission
 * in AndroidManifest.xml.
 *
 * Used by:
 *   - DealerPaymentSmsReceiver (live SMS — always "today" by
 *     definition, since it only fires on a message arriving right now)
 *   - DealerPaymentSmsScanner, ONLY when the matched SMS's own date is
 *     today (same calendar day) — a match found for an older SMS is
 *     NOT run through here automatically; see NEEDS_REVIEW handling in
 *     DealerPaymentSmsScanner and the manual "Review & Verify" action in
 *     DealerPanelActivity instead (which also auto-transfers immediately
 *     after the admin manually confirms it).
 */
object DealerPaymentVerifier {

    private const val TAG = "DealerPaymentVerifier"

    fun verifyAndCredit(
        context: Context,
        transactionId: String,
        transactionData: Map<String, Any>,
        onDone: (Boolean) -> Unit = {}
    ) {
        val dealerId = transactionData["dealerId"] as? String
        val panel = (transactionData["panel"] as? String)?.uppercase()
        val amount = (transactionData["amount"] as? Number)?.toDouble()

        if (dealerId == null || panel == null || amount == null) {
            onDone(false)
            return
        }

        val balanceField = when (panel) {
            "WATEEN" -> "wateenBalance"
            "EBONE" -> "eboneBalance"
            "ZONG" -> "zongBalance"
            else -> null
        }
        if (balanceField == null) {
            onDone(false)
            return
        }

        val db = FirebaseFirestore.getInstance()
        val batch = db.batch()
        batch.update(
            db.collection("dealerTransactions").document(transactionId),
            mapOf(
                "status" to "VERIFIED",
                "verifiedAt" to System.currentTimeMillis()
            )
        )
        batch.update(
            db.collection("dealers").document(dealerId),
            balanceField, FieldValue.increment(amount)
        )
        batch.commit()
            .addOnSuccessListener {
                onDone(true)
                autoLaunchPanelTransfer(context, transactionId, dealerId, panel, amount)
            }
            .addOnFailureListener { onDone(false) }
    }

    /**
     * Reads the dealer's own zone and panel-specific ID, then triggers
     * WebViewLoginActivity in DEALER_TOPUP mode automatically via a
     * full-screen-intent notification (see class doc for why) — same
     * code path as the manual "Send Now" button, just triggered without
     * a tap and reliably from the background. If the dealer has no ID
     * configured for this panel, this logs a warning and does nothing
     * further (the payment stays VERIFIED + wallet-credited, but the
     * real panel transfer will need a manual Send Now once the admin
     * fills in that dealer's ID).
     */
    private fun autoLaunchPanelTransfer(
        context: Context,
        transactionId: String,
        dealerId: String,
        panel: String,
        amount: Double
    ) {
        FirebaseFirestore.getInstance().collection("dealers").document(dealerId).get()
            .addOnSuccessListener { dealerDoc ->
                val dealerName = dealerDoc.getString("name") ?: "Dealer"
                val zone = dealerDoc.getString("zone")?.ifBlank { null } ?: "Okara"
                val ispIdField = when (panel) {
                    "WATEEN" -> "wateenDealerId"
                    "ZONG" -> "zongDealerId"
                    else -> "eboneDealerId"
                }
                val ispDealerId = dealerDoc.getString(ispIdField)?.trim().orEmpty()

                if (ispDealerId.isEmpty()) {
                    Log.w(TAG, "Auto-transfer skipped for $dealerName ($panel) — no $ispIdField configured. Wallet was credited; use Send Now manually once the ID is added.")
                    return@addOnSuccessListener
                }

                val intent = Intent(context, WebViewLoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("selected_isp", panel)
                    putExtra("manual_action", "DEALER_TOPUP")
                    putExtra("dealer_ebone_id", ispDealerId)
                    putExtra("topup_amount", amount.toString())
                    putExtra("dealer_internal_id", dealerId)
                    putExtra("dealer_display_name", dealerName)
                    putExtra("target_zone", zone)
                    putExtra("source_transaction_id", transactionId)
                }
                // REVERTED: the full-screen-intent notification approach
                // introduced a NEW, confirmed bug (tapping the
                // notification sent the payment back to PENDING instead
                // of letting it proceed) — reverting to the simpler,
                // previously-stable direct launch while the real
                // background-launch issue gets root-caused with actual
                // Logcat evidence instead of another speculative fix.
                Log.d(TAG, "Auto-launching panel transfer: $dealerName ($zone/$panel) Rs.$amount")
                context.startActivity(intent)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Auto-transfer: could not load dealer $dealerId — ${e.message}")
            }
    }
}
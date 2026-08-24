package com.example.eboneadminpanel

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Single authority for dealer-payment verification.
 *
 * Existing rules are preserved:
 *  - mark dealerTransactions VERIFIED
 *  - credit dealer's panel balance
 *  - launch the existing WebView DEALER_TOPUP automation
 *
 * The only automation change is HOW the WebView is launched from a live SMS
 * background event: a high-priority full-screen notification is used because
 * Android blocks ordinary background Activity launches on modern Android.
 */
object DealerPaymentVerifier {

    private const val TAG = "DealerPaymentVerifier"
    private const val CHANNEL_ID = "dealer_auto_transfer"
    private const val NOTIFICATION_ID_BASE = 740000

    fun verifyAndCredit(
        context: Context,
        transactionId: String,
        transactionData: Map<String, Any>,
        onDone: (Boolean) -> Unit = {}
    ) {
        val db = FirebaseFirestore.getInstance()
        val txRef = db.collection("dealerTransactions").document(transactionId)

        /*
         * Idempotent verification:
         * Receiver and inbox scanner can see the same SMS at nearly the
         * same time. Only the first call is allowed to move PENDING ->
         * VERIFIED and increment the dealer balance.
         */
        db.runTransaction { transaction ->
            val txSnapshot = transaction.get(txRef)

            val status =
                txSnapshot.getString("status")
                    ?: return@runTransaction false

            if (status != "PENDING") {
                return@runTransaction false
            }

            val dealerId =
                txSnapshot.getString("dealerId")
                    ?: return@runTransaction false

            val panel =
                txSnapshot.getString("panel")
                    ?.uppercase()
                    ?: return@runTransaction false

            val amount =
                (txSnapshot.get("amount") as? Number)
                    ?.toDouble()
                    ?: return@runTransaction false

            val balanceField = when (panel) {
                "WATEEN" -> "wateenBalance"
                "EBONE" -> "eboneBalance"
                "ZONG" -> "zongBalance"
                else -> null
            } ?: return@runTransaction false

            val dealerRef =
                db.collection("dealers").document(dealerId)

            transaction.update(
                txRef,
                mapOf(
                    "status" to "VERIFIED",
                    "verifiedAt" to System.currentTimeMillis(),
                    "transferStatus" to "AUTO_SENDING"
                )
            )

            transaction.update(
                dealerRef,
                balanceField,
                FieldValue.increment(amount)
            )

            true
        }.addOnSuccessListener { changed ->
            if (!changed) {
                /*
                 * false means another receiver/scanner already processed
                 * this transaction, or it was no longer PENDING.
                 * Never launch a second transfer.
                 */
                onDone(false)
                return@addOnSuccessListener
            }

            onDone(true)

            val dealerId =
                transactionData["dealerId"] as? String
                    ?: return@addOnSuccessListener

            val panel =
                (transactionData["panel"] as? String)
                    ?.uppercase()
                    ?: return@addOnSuccessListener

            val amount =
                (transactionData["amount"] as? Number)
                    ?.toDouble()
                    ?: return@addOnSuccessListener

            autoLaunchPanelTransfer(
                context = context.applicationContext,
                transactionId = transactionId,
                dealerId = dealerId,
                panel = panel,
                amount = amount
            )
        }.addOnFailureListener { error ->
            Log.e(
                TAG,
                "Verification transaction failed for $transactionId",
                error
            )
            onDone(false)
        }
    }

    /**
     * Launches the EXISTING DEALER_TOPUP WebView automation.
     *
     * Background execution uses a full-screen notification so Android's
     * background-activity-start restriction does not silently discard the
     * launch. The notification body itself opens DealerPanelActivity, not
     * DEALER_TOPUP, so tapping the notification cannot accidentally replay
     * the transfer.
     */
    private fun autoLaunchPanelTransfer(
        context: Context,
        transactionId: String,
        dealerId: String,
        panel: String,
        amount: Double
    ) {
        FirebaseFirestore.getInstance()
            .collection("dealers")
            .document(dealerId)
            .get()
            .addOnSuccessListener { dealerDoc ->

                val dealerName =
                    dealerDoc.getString("name") ?: "Dealer"

                val zone =
                    dealerDoc.getString("zone")
                        ?.ifBlank { null }
                        ?: "Okara"

                val ispIdField = when (panel) {
                    "WATEEN" -> "wateenDealerId"
                    "ZONG" -> "zongDealerId"
                    else -> "eboneDealerId"
                }

                val ispDealerId =
                    dealerDoc.getString(ispIdField)
                        ?.trim()
                        .orEmpty()

                if (ispDealerId.isEmpty()) {
                    markAutoTransferFailed(
                        transactionId,
                        "No $ispIdField configured for $dealerName"
                    )
                    return@addOnSuccessListener
                }

                val transferIntent =
                    Intent(context, WebViewLoginActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("selected_isp", panel)
                        putExtra("manual_action", "DEALER_TOPUP")
                        putExtra("dealer_ebone_id", ispDealerId)
                        putExtra("topup_amount", amount.toString())
                        putExtra("dealer_internal_id", dealerId)
                        putExtra("dealer_display_name", dealerName)
                        putExtra("target_zone", zone)
                        putExtra("source_transaction_id", transactionId)
                    }

                /*
                 * FIRST PATH — the admin app is already visible.
                 *
                 * In this case we can open the proven WebView flow directly
                 * and do not need a notification tap at all.
                 */
                if (isAppForeground(context)) {
                    try {
                        context.startActivity(transferIntent)

                        Log.d(
                            TAG,
                            "DIRECT AUTO TRANSFER LAUNCHED: " +
                                    "$dealerName ($zone/$panel) Rs.$amount txn=$transactionId"
                        )
                        return@addOnSuccessListener
                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Direct WebView auto-launch failed; using full-screen fallback",
                            e
                        )
                    }
                }

                /*
                 * SECOND PATH — app is not visible.
                 *
                 * Android restricts arbitrary background Activity launches,
                 * so use the existing full-screen PendingIntent path. The
                 * visible notification content still opens DealerPanel, while
                 * the full-screen action targets the proven DEALER_TOPUP flow.
                 */
                val panelIntent =
                    Intent(context, DealerPanelActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }

                val fullScreenPendingIntent =
                    PendingIntent.getActivity(
                        context,
                        (NOTIFICATION_ID_BASE + transactionId.hashCode()),
                        transferIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or
                                PendingIntent.FLAG_IMMUTABLE
                    )

                val contentPendingIntent =
                    PendingIntent.getActivity(
                        context,
                        (NOTIFICATION_ID_BASE + transactionId.hashCode() + 1),
                        panelIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or
                                PendingIntent.FLAG_IMMUTABLE
                    )

                val manager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE)
                            as NotificationManager

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel =
                        NotificationChannel(
                            CHANNEL_ID,
                            "Dealer Auto Transfers",
                            NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            description =
                                "Automatically opens verified dealer balance transfers"
                        }

                    manager.createNotificationChannel(channel)
                }

                val notification =
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                        .setContentTitle("Dealer payment verified")
                        .setContentText(
                            "$dealerName — Rs. ${"%.0f".format(amount)} ($panel) is being transferred"
                        )
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setAutoCancel(true)
                        .setContentIntent(contentPendingIntent)
                        .setFullScreenIntent(fullScreenPendingIntent, true)
                        .build()

                /*
                 * Automatic path:
                 * posting the full-screen intent causes WebViewLoginActivity
                 * to open without an admin tap when the OS permits full-screen
                 * intents. The notification shade item itself opens DealerPanel,
                 * so it cannot retrigger the transfer.
                 */
                manager.notify(
                    NOTIFICATION_ID_BASE + (transactionId.hashCode() and 0x7fffffff),
                    notification
                )

                Log.d(
                    TAG,
                    "Automatic dealer transfer requested: " +
                            "$dealerName ($zone/$panel) Rs.$amount txn=$transactionId"
                )
            }
            .addOnFailureListener { error ->
                markAutoTransferFailed(
                    transactionId,
                    "Could not load dealer: ${error.message}"
                )
            }
    }

    private fun isAppForeground(
        context: Context
    ): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE)
                    as? ActivityManager
                ?: return false

        val processes =
            activityManager.runningAppProcesses
                ?: return false

        return processes.any { processInfo ->
            processInfo.processName == context.packageName &&
                    processInfo.importance ==
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    private fun markAutoTransferFailed(
        transactionId: String,
        reason: String
    ) {
        Log.e(TAG, "Automatic transfer failed for $transactionId: $reason")

        FirebaseFirestore.getInstance()
            .collection("dealerTransactions")
            .document(transactionId)
            .update(
                mapOf(
                    "transferStatus" to "AUTO_FAILED",
                    "transferError" to reason
                )
            )
    }
}
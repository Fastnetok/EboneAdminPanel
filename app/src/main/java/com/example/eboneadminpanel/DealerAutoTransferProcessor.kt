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
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Durable dealer transfer trigger.
 *
 * Firestore is the source of truth:
 *   VERIFIED + AUTO_REQUESTED -> AUTO_PROCESSING -> TRANSFERRED
 *
 * This class does NOT contain ISP navigation. It only prepares the exact
 * existing DEALER_TOPUP intent and starts WebViewLoginActivity.
 *
 * WebViewLoginActivity remains untouched.
 */
object DealerAutoTransferProcessor {

    private const val TAG = "DealerAutoTransferProcessor"
    private const val CHANNEL_ID = "dealer_auto_transfer_v2"
    private const val NOTIFICATION_ID_BASE = 841000

    fun process(
        context: Context,
        transactionId: String
    ) {
        val db = FirebaseFirestore.getInstance()
        val txRef = db.collection("dealerTransactions").document(transactionId)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(txRef)

            if (!snapshot.exists()) {
                return@runTransaction false
            }

            val status = snapshot.getString("status") ?: ""
            val transferStatus = snapshot.getString("transferStatus") ?: ""

            /*
             * Only a durable AUTO_REQUESTED record can be claimed.
             * This prevents live receiver + scanner + retry from launching
             * the same dealer transfer twice.
             */
            if (status != "VERIFIED" || transferStatus != "AUTO_REQUESTED") {
                return@runTransaction false
            }

            transaction.update(
                txRef,
                mapOf(
                    "transferStatus" to "AUTO_PROCESSING",
                    "transferStartedAt" to System.currentTimeMillis()
                )
            )

            true
        }.addOnSuccessListener { claimed ->
            if (!claimed) {
                Log.d(
                    TAG,
                    "Transfer $transactionId was already claimed or is not ready."
                )
                return@addOnSuccessListener
            }

            loadAndLaunch(
                context.applicationContext,
                transactionId
            )
        }.addOnFailureListener { error ->
            Log.e(
                TAG,
                "Could not claim automatic transfer: $transactionId",
                error
            )
        }
    }

    private fun loadAndLaunch(
        context: Context,
        transactionId: String
    ) {
        val db = FirebaseFirestore.getInstance()
        val txRef = db.collection("dealerTransactions").document(transactionId)

        txRef.get()
            .addOnSuccessListener { tx ->

                if (!tx.exists()) {
                    markFailed(
                        transactionId,
                        "Dealer transaction not found"
                    )
                    return@addOnSuccessListener
                }

                val dealerId =
                    tx.getString("dealerId")?.trim().orEmpty()

                val panel =
                    tx.getString("panel")
                        ?.trim()
                        ?.uppercase()
                        .orEmpty()

                val amount =
                    (tx.get("amount") as? Number)
                        ?.toDouble()
                        ?: 0.0

                if (
                    dealerId.isBlank() ||
                    panel.isBlank() ||
                    amount <= 0.0
                ) {
                    markFailed(
                        transactionId,
                        "Invalid dealer transaction data"
                    )
                    return@addOnSuccessListener
                }

                db.collection("dealers")
                    .document(dealerId)
                    .get()
                    .addOnSuccessListener { dealer ->

                        if (!dealer.exists()) {
                            markFailed(
                                transactionId,
                                "Dealer document not found"
                            )
                            return@addOnSuccessListener
                        }

                        val dealerName =
                            dealer.getString("name")
                                ?.trim()
                                .orEmpty()
                                .ifBlank { "Dealer" }

                        val zone =
                            dealer.getString("zone")
                                ?.trim()
                                ?.ifBlank { null }
                                ?: "Okara"

                        val dealerIdField = when (panel) {
                            "EBONE" -> "eboneDealerId"
                            "WATEEN" -> "wateenDealerId"
                            "ZONG" -> "zongDealerId"
                            else -> null
                        }

                        if (dealerIdField == null) {
                            markFailed(
                                transactionId,
                                "Unsupported dealer panel: $panel"
                            )
                            return@addOnSuccessListener
                        }

                        val ispDealerId =
                            dealer.getString(dealerIdField)
                                ?.trim()
                                .orEmpty()

                        if (ispDealerId.isBlank()) {
                            markFailed(
                                transactionId,
                                "No $dealerIdField configured for $dealerName"
                            )
                            return@addOnSuccessListener
                        }

                        val launchIntent =
                            Intent(
                                context,
                                WebViewLoginActivity::class.java
                            ).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                )

                                /*
                                 * EXACTLY the entry-point extras already used
                                 * by the stable WebView automation.
                                 */
                                putExtra(
                                    "selected_isp",
                                    panel
                                )
                                putExtra(
                                    "manual_action",
                                    "DEALER_TOPUP"
                                )
                                putExtra(
                                    "dealer_ebone_id",
                                    ispDealerId
                                )
                                putExtra(
                                    "topup_amount",
                                    amount.toString()
                                )
                                putExtra(
                                    "dealer_internal_id",
                                    dealerId
                                )
                                putExtra(
                                    "dealer_display_name",
                                    dealerName
                                )
                                putExtra(
                                    "target_zone",
                                    zone
                                )
                                putExtra(
                                    "source_transaction_id",
                                    transactionId
                                )
                            }

                        /*
                         * When the admin app is already in the foreground,
                         * launch directly. This is the most reliable path and
                         * does not depend on notification/full-screen intent.
                         */
                        if (isAppForeground(context)) {
                            try {
                                context.startActivity(
                                    launchIntent
                                )

                                Log.d(
                                    TAG,
                                    "DIRECT AUTO TRIGGER STARTED: " +
                                            "$panel / $dealerName / Rs.$amount / " +
                                            "zone=$zone / txn=$transactionId"
                                )

                                /*
                                 * Keep the state AUTO_PROCESSING. The existing
                                 * WebView will change it to TRANSFERRED after
                                 * a successful submission.
                                 */
                                return@addOnSuccessListener

                            } catch (error: Exception) {
                                Log.e(
                                    TAG,
                                    "Direct WebView launch failed",
                                    error
                                )
                            }
                        }

                        /*
                         * Background fallback.
                         *
                         * Android may allow a full-screen notification only
                         * when the app/user has the corresponding privilege.
                         * We never claim the transfer completed here; the
                         * WebView itself must eventually write TRANSFERRED.
                         */
                        postTransferNotification(
                            context = context,
                            launchIntent = launchIntent,
                            transactionId = transactionId,
                            dealerName = dealerName,
                            panel = panel,
                            amount = amount
                        )
                    }
                    .addOnFailureListener { error ->
                        markFailed(
                            transactionId,
                            "Could not load dealer: ${error.message}"
                        )
                    }
            }
            .addOnFailureListener { error ->
                markFailed(
                    transactionId,
                    "Could not load transaction: ${error.message}"
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

        return processes.any { process ->
            process.processName == context.packageName &&
                    process.importance ==
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    private fun postTransferNotification(
        context: Context,
        launchIntent: Intent,
        transactionId: String,
        dealerName: String,
        panel: String,
        amount: Double
    ) {
        val manager =
            context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Dealer automatic transfers",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description =
                        "Verified dealer payment transfer requests"
                }
            )
        }

        val requestCode =
            NOTIFICATION_ID_BASE +
                    (transactionId.hashCode() and 0x7fffffff)

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val notification =
            NotificationCompat.Builder(
                context,
                CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.stat_sys_upload_done
                )
                .setContentTitle(
                    "Dealer payment verified"
                )
                .setContentText(
                    "$dealerName — Rs.${"%.0f".format(amount)} ($panel)"
                )
                .setPriority(
                    NotificationCompat.PRIORITY_MAX
                )
                .setCategory(
                    NotificationCompat.CATEGORY_ALARM
                )
                .setAutoCancel(true)
                .setContentIntent(
                    pendingIntent
                )
                .setFullScreenIntent(
                    pendingIntent,
                    true
                )
                .build()

        manager.notify(
            requestCode,
            notification
        )

        Log.w(
            TAG,
            "App was backgrounded; automatic WebView launch was handed " +
                    "to Android full-screen notification policy. txn=$transactionId"
        )
    }

    private fun markFailed(
        transactionId: String,
        reason: String
    ) {
        Log.e(
            TAG,
            "Automatic transfer failed: $transactionId / $reason"
        )

        FirebaseFirestore.getInstance()
            .collection("dealerTransactions")
            .document(transactionId)
            .update(
                mapOf(
                    "transferStatus" to "AUTO_FAILED",
                    "transferError" to reason,
                    "transferFailedAt" to System.currentTimeMillis()
                )
            )
    }
}
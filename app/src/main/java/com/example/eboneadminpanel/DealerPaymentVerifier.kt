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

    // Removed recentlyProcessedTids to prevent blocking after a Reset
    private val dealerInfoCache = HashMap<String, Pair<String, String>>() // dealerId -> (name, zone)
    private val dealerIspIdCache = HashMap<String, Map<String, String>>() // dealerId -> Map<isp, ispDealerId>

    fun verifyAndCredit(
        context: Context,
        transactionId: String,
        transactionData: Map<String, Any>,
        source: String = "unspecified",
        onDone: (Boolean) -> Unit = {}
    ) {
        val db = FirebaseFirestore.getInstance()
        val txRef = db.collection("dealerTransactions").document(transactionId)
        val tid = (transactionData["bankTransactionId"] as? String)?.trim() ?: ""

        // 1. STRICT DUPLICATE GUARD: Check if any other doc with SAME TID is already processed
        if (tid.isNotEmpty()) {
            db.collection("dealerTransactions")
                .whereEqualTo("bankTransactionId", tid)
                .whereIn("status", listOf("VERIFIED", "COMPLETED"))
                .get()
                .addOnSuccessListener { snp ->
                    if (!snp.isEmpty && snp.documents.any { it.id != transactionId }) {
                        Log.w(TAG, "Duplicate TID detected globally! Rejecting $transactionId")
                        txRef.update(mapOf(
                            "status" to "REJECTED_DUPLICATE",
                            "duplicateMessage" to "This TID ($tid) was already verified in another record."
                        ))
                        onDone(false)
                        return@addOnSuccessListener
                    }
                    // No duplicate found, proceed to normal verification
                    performActualVerification(context, transactionId, transactionData, source, onDone)
                }
                .addOnFailureListener {
                    performActualVerification(context, transactionId, transactionData, source, onDone)
                }
        } else {
            performActualVerification(context, transactionId, transactionData, source, onDone)
        }
    }

    private fun performActualVerification(
        context: Context,
        transactionId: String,
        transactionData: Map<String, Any>,
        source: String,
        onDone: (Boolean) -> Unit
    ) {
        val db = FirebaseFirestore.getInstance()
        val txRef = db.collection("dealerTransactions").document(transactionId)

        txRef.update(
            "verifyAttemptLog",
            FieldValue.arrayUnion(
                mapOf(
                    "source" to source,
                    "at" to System.currentTimeMillis()
                )
            )
        ).addOnFailureListener {
            // Ignore — logging must never block or fail the real flow.
        }

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
                    "verifiedBySource" to source,
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
                Log.w(
                    TAG,
                    "verifyAndCredit REFUSED (already processed or not PENDING) — source=$source, txn=$transactionId"
                )
                onDone(false)
                return@addOnSuccessListener
            }

            Log.d(TAG, "verifyAndCredit GRANTED — source=$source, txn=$transactionId")

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

            claimAndQueueTransfer(
                context = context.applicationContext,
                transactionId = transactionId,
                dealerId = dealerId,
                panel = panel,
                amount = amount,
                source = source
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
     * THE FIX for the duplicate/repeated real-money transfer:
     *
     * This app has SEVERAL independent triggers that can all decide, at
     * almost the same moment, that "this transaction should be sent to
     * the panel now" — the live SMS BroadcastReceiver, the always-on
     * PaymentActivationService Firestore listener (which re-runs a full
     * scan any time the PENDING result set changes at all, not only for
     * the exact document that changed), the periodic background worker,
     * and the manual 🔄 retry button. verifyAndCredit()'s own Firestore
     * transaction only guarantees the *balance credit* happens once —
     * it says nothing about how many times the WebView panel automation
     * itself gets opened afterwards, and DealerTransferQueue's old
     * in-memory-only guard could not protect against two of those
     * triggers calling in from separate scans/threads once the first
     * transfer had already finished and been forgotten.
     *
     * The panel-side top-up (the real transaction that moves real
     * money) is NOT undoable and NOT naturally idempotent, so it needs
     * its own permanent, atomic "has this already been sent" flag —
     * stored in Firestore, not in app memory. This transaction reads
     * transferStatus and only proceeds past the "AUTO_SENDING" value
     * exactly once, ever, for a given transactionId: the first caller
     * to win the atomic compare-and-set moves it to "AUTO_CLAIMED" and
     * is the only one allowed to actually open WebViewLoginActivity.
     * Every other caller — even one arriving from a completely
     * different scan, a different thread, or after the app restarted —
     * is turned away right here, permanently.
     */
    private fun claimAndQueueTransfer(
        context: Context,
        transactionId: String,
        dealerId: String,
        panel: String,
        amount: Double,
        source: String
    ) {
        val db = FirebaseFirestore.getInstance()
        val txRef = db.collection("dealerTransactions").document(transactionId)

        db.runTransaction { transaction ->
            val snap = transaction.get(txRef)
            val currentTransferStatus = snap.getString("transferStatus") ?: ""

            // Only a transaction that is still exactly at "AUTO_SENDING"
            // (the value verifyAndCredit sets the moment it verifies) can
            // be claimed. Anything else — AUTO_CLAIMED, TRANSFERRED,
            // AUTO_FAILED, or already missing — means some other caller
            // already claimed or finished this transfer, so refuse.
            if (currentTransferStatus != "AUTO_SENDING") {
                return@runTransaction false
            }

            transaction.update(
                txRef,
                mapOf(
                    "transferStatus" to "AUTO_CLAIMED",
                    "transferClaimedAt" to System.currentTimeMillis(),
                    "transferClaimedBySource" to source
                )
            )

            true
        }.addOnSuccessListener { claimed ->
            if (!claimed) {
                Log.w(
                    TAG,
                    "Refusing duplicate launch for $transactionId — a transfer was already claimed/sent for it. (attempted by source=$source)"
                )
                return@addOnSuccessListener
            }

            Log.d(TAG, "Transfer CLAIMED for $transactionId by source=$source")

            prepareAndQueueTransfer(
                context = context,
                transactionId = transactionId,
                dealerId = dealerId,
                panel = panel,
                amount = amount
            )
        }.addOnFailureListener { error ->
            Log.e(TAG, "Could not claim transfer for $transactionId", error)
        }
    }

    /**
     * Looks up the dealer's ISP-specific dealer ID/zone, then hands the
     * resolved transfer off to DealerTransferQueue instead of launching
     * WebViewLoginActivity directly. The queue guarantees that if another
     * dealer transfer is already mid-automation, this one waits its turn
     * instead of opening a second WebViewLoginActivity at the same time
     * (see DealerTransferQueue.kt for why that used to cause silent
     * failures via the shared CookieManager).
     *
     * By the time this runs, claimAndQueueTransfer has already made sure
     * it is the only caller allowed to do this for this transactionId.
     */
    private fun prepareAndQueueTransfer(
        context: Context,
        transactionId: String,
        dealerId: String,
        panel: String,
        amount: Double
    ) {
        // QUICK CACHE CHECK to speed up Navigation Trigger
        val cachedInfo = dealerInfoCache[dealerId]
        val cachedIspIds = dealerIspIdCache[dealerId]
        
        if (cachedInfo != null && cachedIspIds != null) {
            val ispDealerId = cachedIspIds[panel]
            if (!ispDealerId.isNullOrEmpty()) {
                Log.d(TAG, "Using cached dealer info for $dealerId to trigger navigation immediately.")
                DealerTransferQueue.enqueue(
                    context,
                    DealerTransferQueue.PendingTransfer(
                        transactionId = transactionId,
                        dealerId = dealerId,
                        dealerName = cachedInfo.first,
                        panel = panel,
                        ispDealerId = ispDealerId,
                        zone = cachedInfo.second,
                        amount = amount
                    )
                )
                return
            }
        }

        FirebaseFirestore.getInstance()
            .collection("dealers")
            .document(dealerId)
            .get()
            .addOnSuccessListener { dealerDoc ->
                val dealerName = dealerDoc.getString("name") ?: "Dealer"
                val zone = dealerDoc.getString("zone")?.takeIf { it.isNotBlank() } ?: "Okara"
                
                // Update Cache
                dealerInfoCache[dealerId] = dealerName to zone
                val ids = mapOf(
                    "EBONE" to (dealerDoc.getString("eboneDealerId") ?: ""),
                    "WATEEN" to (dealerDoc.getString("wateenDealerId") ?: ""),
                    "ZONG" to (dealerDoc.getString("zongDealerId") ?: "")
                )
                dealerIspIdCache[dealerId] = ids

                val ispIdField = when (panel) {
                    "WATEEN" -> "wateenDealerId"
                    "ZONG" -> "zongDealerId"
                    else -> "eboneDealerId"
                }

                val ispDealerId = dealerDoc.getString(ispIdField)?.trim().orEmpty()

                if (ispDealerId.isEmpty()) {
                    markAutoTransferFailed(transactionId, "No $ispIdField configured for $dealerName")
                    return@addOnSuccessListener
                }

                DealerTransferQueue.enqueue(
                    context,
                    DealerTransferQueue.PendingTransfer(
                        transactionId = transactionId,
                        dealerId = dealerId,
                        dealerName = dealerName,
                        panel = panel,
                        ispDealerId = ispDealerId,
                        zone = zone,
                        amount = amount
                    )
                )
            }
            .addOnFailureListener { error ->
                markAutoTransferFailed(transactionId, "Could not load dealer: ${error.message}")
            }
    }

    /**
     * Actually launches the EXISTING DEALER_TOPUP WebView automation for
     * one transfer. Called ONLY by DealerTransferQueue, exactly once per
     * transfer, only when no other transfer is currently in-flight.
     *
     * Background execution uses a full-screen notification so Android's
     * background-activity-start restriction does not silently discard the
     * launch. The notification body itself opens DealerPanelActivity, not
     * DEALER_TOPUP, so tapping the notification cannot accidentally replay
     * the transfer.
     */
    fun launchTransferIntent(
        context: Context,
        transfer: DealerTransferQueue.PendingTransfer
    ) {
        val transactionId = transfer.transactionId
        val dealerId = transfer.dealerId
        val dealerName = transfer.dealerName
        val panel = transfer.panel
        val ispDealerId = transfer.ispDealerId
        val zone = transfer.zone
        val amount = transfer.amount

        val targetActivity = WebViewRouter.getTargetActivity(panel, zone)
        
        // FORMAT: Remove trailing .0 from amount (e.g. 1500.0 -> 1500)
        val formattedAmount = if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            amount.toString()
        }

        val transferIntent =
            Intent(context, targetActivity).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("selected_isp", panel)
                putExtra("manual_action", "DEALER_TOPUP")
                putExtra("dealer_ebone_id", ispDealerId)
                putExtra("topup_amount", formattedAmount)
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
        // MOST IMPORTANT AUDIT ENTRY: this fires exactly once per call to
        // this function — i.e. once per REAL WebViewLoginActivity launch
        // attempt for this transaction. If two panel submissions ever
        // happen again, this field will show either (a) one entry here
        // and the rest of the duplication is happening inside the panel
        // automation itself, or (b) two+ entries here, proving this
        // function really was called twice despite the claim guard —
        // telling us exactly which layer to fix next, instead of
        // guessing.
        FirebaseFirestore.getInstance()
            .collection("dealerTransactions")
            .document(transactionId)
            .update(
                "panelLaunchAttemptLog",
                FieldValue.arrayUnion(
                    mapOf(
                        "at" to System.currentTimeMillis(),
                        "panel" to panel,
                        "amount" to amount
                    )
                )
            )
            .addOnFailureListener { /* logging must never block the real flow */ }

        val topActivity = EboneAdminApp.currentForegroundActivity
        if (topActivity != null && !topActivity.isFinishing) {
            try {
                topActivity.startActivity(transferIntent)
                Log.d(
                    TAG,
                    "DIRECT AUTO TRANSFER LAUNCHED via Top Activity (${topActivity.javaClass.simpleName}): " +
                            "$dealerName ($zone/$panel) Rs.$amount txn=$transactionId"
                )
                return
            } catch (e: Exception) {
                Log.e(TAG, "Launch via top activity failed, trying context", e)
            }
        }

        if (isAppForeground(context)) {
            try {
                context.startActivity(transferIntent)

                Log.d(
                    TAG,
                    "DIRECT AUTO TRANSFER LAUNCHED: " +
                            "$dealerName ($zone/$panel) Rs.$amount txn=$transactionId"
                )
                return
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

    /**
     * THE ACTUAL BUG this fixes:
     *
     * amount here comes from Firestore as a Number (Double). Kotlin's
     * Double.toString() ALWAYS appends a decimal point — 1500.0 becomes
     * the literal string "1500.0" — and that string used to be typed
     * directly into the panel's amount field by WebViewLoginActivity
     * (fillDealerTopupAmountAndSubmit / searchAndCreditZongDealer), which
     * never touches the value itself, it just types whatever it is given.
     *
     * A manual "Send Dealer Payment" never showed this because the admin
     * types a plain amount like "1" or "1500" by hand — no trailing
     * ".0" ever exists in that path. Every SMS/dealer-app-triggered
     * transfer, on the other hand, always carried the extra ".0",
     * which the panel could reject or misread — exactly matching "manual
     * send 100% sahi, auto-trigger kabhi sahi nahi jaata."
     *
     * This turns 1500.0 -> "1500" (matches what an admin would type) and
     * only keeps a decimal for genuinely fractional amounts, e.g.
     * 1500.5 -> "1500.5".
     */
    private fun isAppForeground(
        context: Context
    ): Boolean {
        if (EboneAdminApp.isForeground) return true

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
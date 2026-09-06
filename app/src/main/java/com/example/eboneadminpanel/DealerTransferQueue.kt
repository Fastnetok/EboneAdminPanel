package com.example.eboneadminpanel

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.LinkedList

/**
 * Serializes every DEALER_TOPUP WebViewLoginActivity automation so that
 * AT MOST ONE panel-automation WebView is ever running at a time.
 *
 * WHY THIS EXISTS (do not remove without re-reading this):
 *
 * android.webkit.CookieManager is a single, process-wide cookie jar —
 * NOT one per WebView instance. DealerPaymentVerifier used to call
 * context.startActivity(WebViewLoginActivity) once per matched dealer
 * transaction with zero coordination between calls. DealerPaymentSmsScanner
 * (scanLivePending / scanAllPending) loops over every matching pending
 * transaction in a single pass and can trigger several matches back to
 * back, so two (or more) WebViewLoginActivity instances could end up
 * running at the same time, stacked on top of each other.
 *
 * Because those instances share the same CookieManager, whichever one
 * logs into a panel second silently replaces the session cookie the
 * first instance was mid-transaction on — so the first instance's
 * remaining steps (search dealer / fill amount / submit) can run against
 * the wrong session, or fail outright, with no crash and no visible
 * error. A manually-triggered single "Send Dealer Payment" never hit
 * this because the admin naturally sends one at a time and waits for it
 * to finish — that path stayed reliable while several auto-verified
 * dealer-app payments arriving close together were not.
 *
 * This file does NOT change WebViewLoginActivity.kt's automation logic
 * at all. It only decides WHEN each transfer intent is allowed to
 * launch, using the transferStatus field WebViewLoginActivity already
 * writes (TRANSFERRED on success) to know the previous one is done.
 */
object DealerTransferQueue {

    private const val TAG = "DealerTransferQueue"

    // Safety valve: if a launched transfer never reports a terminal
    // transferStatus (app killed mid-flow, panel hung, unexpected page,
    // etc.) don't let the whole queue stall forever — release the lock
    // and let the next payment go through, and flag this one for the
    // admin to check manually.
    //
    // IMPORTANT: this timeout NEVER causes a second submission. It only
    // stops THIS queue from waiting forever before it will consider the
    // NEXT different payment. The transfer that was already launched
    // keeps running in its own WebViewLoginActivity exactly as before —
    // this just decides when the queue gives up waiting on it.
    // Set generously above normal completion time (login + search +
    // fill + submit + balance-check reasonably takes under a minute;
    // 6 minutes leaves large headroom for a slow network) so a merely
    // slow-but-working transfer is never mistaken for a stuck one.
    private const val STUCK_TIMEOUT_MS = 6 * 60 * 1000L

    data class PendingTransfer(
        val transactionId: String,
        val dealerId: String,
        val dealerName: String,
        val panel: String,
        val ispDealerId: String,
        val zone: String,
        val amount: Double
    )

    private val queue = LinkedList<PendingTransfer>()
    private val queuedIds = HashSet<String>()
    private var busy = false
    private var activeListener: ListenerRegistration? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    @Synchronized
    fun enqueue(context: Context, transfer: PendingTransfer) {
        if (queuedIds.contains(transfer.transactionId)) {
            Log.d(TAG, "Ignoring duplicate enqueue for ${transfer.transactionId} (already queued/running)")
            return
        }
        queuedIds.add(transfer.transactionId)
        queue.add(transfer)
        Log.d(
            TAG,
            "Queued ${transfer.transactionId} (${transfer.dealerName}/${transfer.panel}/Rs.${transfer.amount}) " +
                    "— queue size now ${queue.size}, busy=$busy"
        )
        processNextIfIdle(context.applicationContext)
    }

    @Synchronized
    private fun processNextIfIdle(context: Context) {
        if (busy) return
        val next = queue.poll() ?: return
        busy = true

        Log.d(TAG, "Launching transfer for ${next.transactionId}; ${queue.size} still queued behind it")
        DealerPaymentVerifier.launchTransferIntent(context, next)
        watchForCompletion(context, next.transactionId)

        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { onTransferTimedOut(context, next.transactionId) }
        timeoutRunnable = runnable
        mainHandler.postDelayed(runnable, STUCK_TIMEOUT_MS)
    }

    private fun watchForCompletion(context: Context, transactionId: String) {
        activeListener?.remove()
        val docRef = FirebaseFirestore.getInstance()
            .collection("dealerTransactions")
            .document(transactionId)

        activeListener = docRef.addSnapshotListener { snap, error ->
            if (error != null) {
                Log.e(TAG, "Listener error while watching $transactionId", error)
                return@addSnapshotListener
            }
            val status = snap?.getString("transferStatus") ?: return@addSnapshotListener
            if (status == "TRANSFERRED" || status == "AUTO_FAILED") {
                onTransferFinished(context, transactionId, timedOut = false)
            }
        }
    }

    @Synchronized
    private fun onTransferTimedOut(context: Context, transactionId: String) {
        // If it already finished normally in the meantime, this is a no-op
        // because onTransferFinished already cleared queuedIds/busy.
        if (!queuedIds.contains(transactionId)) return

        Log.w(TAG, "Transfer $transactionId did not report a terminal status within ${STUCK_TIMEOUT_MS}ms — unblocking queue.")
        FirebaseFirestore.getInstance()
            .collection("dealerTransactions")
            .document(transactionId)
            .update(
                mapOf(
                    "transferStatus" to "AUTO_FAILED",
                    "transferError" to "This payment was sent to the panel only once, but did not confirm completion in time — please check the panel manually before resending."
                )
            )
        onTransferFinished(context, transactionId, timedOut = true)
    }

    @Synchronized
    private fun onTransferFinished(context: Context, transactionId: String, timedOut: Boolean) {
        activeListener?.remove()
        activeListener = null
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        val wasTracked = queuedIds.remove(transactionId)
        if (!wasTracked) return

        busy = false
        Log.d(TAG, "Transfer finished (timedOut=$timedOut): $transactionId — ${queue.size} left in queue")
        processNextIfIdle(context)
    }
}
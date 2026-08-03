package com.example.eboneadminpanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Background listener: watches Firestore "transactions" for new PENDING
 * entries (written by CustomerIDApp when a customer submits a payment).
 *
 * Per the confirmed design, this alone does NOT trigger activation — it
 * only tracks pending transactions. The real trigger is PaymentSmsReceiver
 * matching an incoming SMS's TID against one of these PENDING transactions.
 */
class PaymentActivationService : Service() {

    private var listener: ListenerRegistration? = null
    private val db = FirebaseFirestore.getInstance()
    private val notifiedTransactionIds = mutableSetOf<String>()

    companion object {
        const val CHANNEL_ID = "payment_activation_channel_v2"
        const val FOREGROUND_ID = 5001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(FOREGROUND_ID, buildForegroundNotification())
        startListening()
        scanExistingInboxForTesting() // TODO: remove after testing is done
    }

    /**
     * TESTING ONLY — remove once confirmed working end-to-end. Normally
     * PaymentSmsReceiver only reacts to freshly-arriving SMS. This scans the
     * inbox (regardless of age) and runs matching against it, so old test
     * SMS can be re-tried without needing a brand new payment. Uses a Toast
     * (not a Dialog) for the summary — a Service has no window/theme, so an
     * AlertDialog from here throws "Theme.AppCompat required".
     */
    private fun scanExistingInboxForTesting() {
        try {
            val cursor: Cursor? = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("body"),
                null, null,
                "date DESC"
            )
            var scanned = 0
            var tidsFound = 0
            cursor?.use {
                val receiver = PaymentSmsReceiver()
                while (it.moveToNext()) {
                    val body = it.getString(it.getColumnIndexOrThrow("body")) ?: continue
                    scanned++
                    if (receiver.extractTid(body) != null) tidsFound++
                    receiver.processSmsBodyForTesting(this, body)
                }
            }
            android.widget.Toast.makeText(
                this, "Scanned $scanned SMS, $tidsFound had a TID", android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("PaymentActivationService", "Testing SMS scan failed", e)
            android.widget.Toast.makeText(this, "SMS scan error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        listener = db.collection("transactions")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, _ ->
                snapshot?.documentChanges?.forEach { change ->
                    val transactionId = change.document.id
                    notifiedTransactionIds.add(transactionId)
                }
            }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ebone Admin Panel")
            .setContentText("Listening for new customer payments…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Payment Activations", NotificationManager.IMPORTANCE_HIGH
            )
            val soundUri = android.net.Uri.parse("android.resource://$packageName/${R.raw.payment_success}")
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            channel.setSound(soundUri, audioAttributes)

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listener?.remove()
    }
}
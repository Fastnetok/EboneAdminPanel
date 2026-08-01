package com.example.eboneadminpanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
        // v2: changed from "payment_activation_channel" because Android locks a
        // notification channel's settings (like sound) after first creation —
        // a new ID was needed for the custom cha-ching sound to apply.
        const val CHANNEL_ID = "payment_activation_channel_v2"
        const val FOREGROUND_ID = 5001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(FOREGROUND_ID, buildForegroundNotification())
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        listener = db.collection("transactions")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, _ ->
                // NOTE: This no longer auto-notifies. A PENDING transaction only
                // triggers activation once its TID is found in an incoming SMS
                // — see PaymentSmsReceiver.kt. This listener is kept for
                // potential future use (e.g. a "Pending Payments" list screen)
                // but takes no action itself.
                snapshot?.documentChanges?.forEach { change ->
                    val transactionId = change.document.id
                    notifiedTransactionIds.add(transactionId) // tracked, not acted on
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
            // Custom "cha-ching" sound for payment activation alerts.
            // Requires the file to exist at: app/src/main/res/raw/payment_success.mp3
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
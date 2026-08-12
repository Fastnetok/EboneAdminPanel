package com.example.eboneadminpanel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class PaymentActivationService : Service() {

    private var listener: ListenerRegistration? = null
    private val db = FirebaseFirestore.getInstance()
    private val processedTransactionIds = mutableSetOf<String>()

    companion object {
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
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("PaymentActivationService", "Listener error: ${error.message}")
                    return@addSnapshotListener
                }
                snapshot?.documents?.forEach { doc ->
                    val transactionId = doc.id
                    if (!processedTransactionIds.contains(transactionId)) {
                        processedTransactionIds.add(transactionId)
                        val tid = doc.getString("bankTransactionId") ?: ""
                        val amount = doc.getDouble("amount") ?: 0.0
                        val customerId = doc.getString("customerId") ?: ""
                        if (tid.isNotEmpty() && customerId.isNotEmpty()) {
                            // SMS Inbox Scan Karo
                            scanSmsInboxForTid(tid, transactionId, customerId)
                        }
                    }
                }
            }
    }

    private fun scanSmsInboxForTid(tid: String, transactionId: String, customerId: String) {
        try {
            val matchWindowDays = SmsMatchSettingsActivity.getMatchWindowDays(this)
            val windowStart = System.currentTimeMillis() - (matchWindowDays * 24L * 60L * 60L * 1000L)

            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )
            val selection = "${Telephony.Sms.DATE} >= ?"
            val selectionArgs = arrayOf(windowStart.toString())
            val sortOrder = "${Telephony.Sms.DATE} DESC"

            val cursor = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)

            cursor?.use { c ->
                while (c.moveToNext()) {
                    val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: continue

                    // TID Match Check
                    if (body.contains(tid, ignoreCase = true)) {
                        Log.d("PaymentActivationService", "TID MATCH FOUND in SMS: $tid")

                        // Match Mila → Notification Show Karo
                        PaymentNotificationHelper.showActivationNotification(
                            this, transactionId, customerId
                        )

                        // Firestore Mein Mark Karo
                        db.collection("transactions").document(transactionId)
                            .update("smsMatched", true, "smsBody", body.take(200))

                        return
                    }
                }
                Log.d("PaymentActivationService", "TID not found in SMS inbox: $tid")
            }
        } catch (e: Exception) {
            Log.e("PaymentActivationService", "SMS scan error: ${e.message}")
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
            val soundUri = android.net.Uri.parse(
                "android.resource://$packageName/${R.raw.payment_success}"
            )
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
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
        private const val TAG = "PaymentActivationService"
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

    /**
     * Android 15+ foreground-service timeout callback.
     *
     * The service MUST stop when the system reaches the
     * foreground-service timeout, otherwise Android can
     * throw ForegroundServiceDidNotStopInTimeException.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(
            TAG,
            "Foreground service timeout reached. Stopping PaymentActivationService."
        )

        listener?.remove()
        listener = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf(startId)
    }

    private fun startListening() {
        listener = db.collection("transactions")
            .whereEqualTo("status", "PENDING")
            .addSnapshotListener { snapshot, error ->

                if (error != null) {
                    Log.e(
                        TAG,
                        "Listener error: ${error.message}"
                    )
                    return@addSnapshotListener
                }

                snapshot?.documents?.forEach { doc ->

                    val transactionId = doc.id

                    if (!processedTransactionIds.contains(transactionId)) {

                        processedTransactionIds.add(transactionId)

                        val tid =
                            doc.getString("bankTransactionId") ?: ""

                        val amount =
                            doc.getDouble("amount") ?: 0.0

                        val customerId =
                            doc.getString("customerId") ?: ""

                        /*
                         * Keep existing logic unchanged.
                         * Amount is read as before.
                         */
                        @Suppress("UNUSED_VARIABLE")
                        val unusedAmount = amount

                        if (
                            tid.isNotEmpty() &&
                            customerId.isNotEmpty()
                        ) {
                            // SMS Inbox Scan Karo
                            scanSmsInboxForTid(
                                tid,
                                transactionId,
                                customerId
                            )
                        }
                    }
                }
            }
    }

    private fun scanSmsInboxForTid(
        tid: String,
        transactionId: String,
        customerId: String
    ) {
        try {

            val matchWindowDays =
                SmsMatchSettingsActivity
                    .getMatchWindowDays(this)

            val windowStart =
                System.currentTimeMillis() -
                        (
                                matchWindowDays *
                                        24L *
                                        60L *
                                        60L *
                                        1000L
                                )

            val uri =
                Telephony.Sms.Inbox.CONTENT_URI

            val projection = arrayOf(
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )

            val selection =
                "${Telephony.Sms.DATE} >= ?"

            val selectionArgs =
                arrayOf(
                    windowStart.toString()
                )

            val sortOrder =
                "${Telephony.Sms.DATE} DESC"

            val cursor =
                contentResolver.query(
                    uri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )

            cursor?.use { c ->

                while (c.moveToNext()) {

                    val body =
                        c.getString(
                            c.getColumnIndexOrThrow(
                                Telephony.Sms.BODY
                            )
                        ) ?: continue

                    // TID Match Check
                    if (
                        body.contains(
                            tid,
                            ignoreCase = true
                        )
                    ) {

                        Log.d(
                            TAG,
                            "TID MATCH FOUND in SMS: $tid"
                        )

                        // Match Mila → Notification Show Karo
                        PaymentNotificationHelper
                            .showActivationNotification(
                                this,
                                transactionId,
                                customerId
                            )

                        // Firestore Mein Mark Karo
                        db.collection("transactions")
                            .document(transactionId)
                            .update(
                                "smsMatched",
                                true,
                                "smsBody",
                                body.take(200)
                            )

                        return
                    }
                }

                Log.d(
                    TAG,
                    "TID not found in SMS inbox: $tid"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "SMS scan error: ${e.message}",
                e
            )
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "Ebone Admin Panel"
            )
            .setContentText(
                "Listening for new customer payments…"
            )
            .setSmallIcon(
                android.R.drawable.stat_notify_sync
            )
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Payment Activations",
                    NotificationManager.IMPORTANCE_HIGH
                )

            val soundUri =
                android.net.Uri.parse(
                    "android.resource://$packageName/${R.raw.payment_success}"
                )

            val audioAttributes =
                android.media.AudioAttributes.Builder()
                    .setUsage(
                        android.media.AudioAttributes.USAGE_NOTIFICATION
                    )
                    .setContentType(
                        android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION
                    )
                    .build()

            channel.setSound(
                soundUri,
                audioAttributes
            )

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    override fun onDestroy() {

        listener?.remove()
        listener = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }
}
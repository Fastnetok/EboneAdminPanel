package com.example.eboneadminpanel

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Shared by PaymentActivationService (foreground listener) and
 * PaymentSmsReceiver (the actual trigger, once an SMS's TID matches a
 * PENDING transaction) so both can show the same "Tap to activate"
 * notification without duplicating the logic.
 */
object PaymentNotificationHelper {

    fun showActivationNotification(context: Context, transactionId: String, customerId: String) {
        FirebaseFirestore.getInstance()
            .collection("customers").document(customerId).get()
            .addOnSuccessListener { customerDoc ->
                val isp = customerDoc.getString("ispProvider") ?: "EBONE"

                val intent = Intent(context, WebViewLoginActivity::class.java).apply {
                    putExtra("selected_isp", isp)
                    putExtra("auto_activate_customer_id", customerId)
                    putExtra("transaction_id", transactionId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, transactionId.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, PaymentActivationService.CHANNEL_ID)
                    .setContentTitle("Payment Verified via SMS")
                    .setContentText("$customerId — Tap to Activate")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()

                val manager = context.getSystemService(NotificationManager::class.java)
                manager.notify(transactionId.hashCode(), notification)
            }
    }
}
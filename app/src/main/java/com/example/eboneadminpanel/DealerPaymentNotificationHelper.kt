package com.example.eboneadminpanel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore

/** Purely informational — the balance is already credited automatically
 * by the time this fires. This just lets the admin SEE it happened,
 * without needing to tap anything. */
object DealerPaymentNotificationHelper {

    private const val CHANNEL_ID = "dealer_payment_channel"

    fun showCreditedNotification(context: Context, dealerId: String, panel: String, amount: Double) {
        ensureChannel(context)

        // Look up the dealer's name for a friendlier notification —
        // falls back to the ID if the lookup is slow/fails.
        FirebaseFirestore.getInstance()
            .collection("dealers")
            .document(dealerId)
            .get()
            .addOnSuccessListener { doc ->
                val name = doc.getString("name") ?: dealerId
                post(context, name, panel, amount)
            }
            .addOnFailureListener {
                post(context, dealerId, panel, amount)
            }
    }

    private fun post(context: Context, dealerName: String, panel: String, amount: Double) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Dealer balance credited")
            .setContentText("$dealerName — $panel Rs. ${"%.0f".format(amount)}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dealer Payments",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies when a dealer's payment is auto-verified and balance credited"
        }
        manager.createNotificationChannel(channel)
    }
}
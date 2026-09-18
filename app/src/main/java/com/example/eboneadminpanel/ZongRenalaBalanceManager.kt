package com.example.eboneadminpanel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

object ZongRenalaBalanceManager {
    private const val BALANCE_FIELD = "zongBalance_Renala"
    private const val THRESHOLD_FIELD = "zongLowBalanceThreshold_Renala"
    private const val CHANNEL_ID = "zong_renala_low_balance"

    private val doc by lazy {
        FirebaseFirestore.getInstance()
            .collection("franchiseSettings").document("balances")
    }

    fun updateBalance(balance: Double, onDone: (Boolean) -> Unit = {}) {
        doc.set(mapOf(BALANCE_FIELD to balance), SetOptions.merge())
            .addOnSuccessListener { onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    fun getBalance(onResult: (Double?) -> Unit) {
        doc.get()
            .addOnSuccessListener { onResult(it.getDouble(BALANCE_FIELD)) }
            .addOnFailureListener { onResult(null) }
    }

    fun checkAndNotifyLowBalance(context: Context, balance: Double) {
        doc.get().addOnSuccessListener { snap ->
            val threshold = snap.getDouble(THRESHOLD_FIELD) ?: return@addOnSuccessListener
            if (threshold <= 0.0 || balance >= threshold) return@addOnSuccessListener

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Zong Renala Low Balance",
                            NotificationManager.IMPORTANCE_HIGH)
                    )
                }
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Zong (Renala) Balance Low")
                .setContentText("Only Rs. ${"%,.0f".format(balance)} left — threshold Rs. ${"%,.0f".format(threshold)}.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify("zong_renala_low_balance".hashCode(), notification)
        }
    }
}

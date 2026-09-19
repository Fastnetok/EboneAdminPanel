package com.example.eboneadminpanel

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

object FranchiseBalanceManager {

    private const val UPDATE_CHANNEL_ID = "auto_update_status_v4"
    private const val LOW_BALANCE_CHANNEL_ID = "franchise_low_balance"

    private val doc by lazy {
        FirebaseFirestore.getInstance()
            .collection("franchiseSettings")
            .document("balances")
    }

    private fun panelKey(panel: String) = panel.trim().lowercase()

    private fun balanceField(panel: String, zone: String): String {
        val base = "${panelKey(panel)}Balance"
        return if (zone.equals("Okara", ignoreCase = true)) base else "${base}_$zone"
    }

    private fun thresholdField(panel: String, zone: String): String {
        val base = "${panelKey(panel)}LowBalanceThreshold"
        return if (zone.equals("Okara", ignoreCase = true)) base else "${base}_$zone"
    }

    fun showUpdateNotification(context: Context, panel: String, balance: Double, zone: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(UPDATE_CHANNEL_ID) == null) {
                val channel = NotificationChannel(UPDATE_CHANNEL_ID, "Update Status", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableLights(true)
                    lightColor = Color.GREEN
                    enableVibration(true)
                    setBypassDnd(true)
                    setSound(Settings.System.DEFAULT_NOTIFICATION_URI, AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                }
                manager.createNotificationChannel(channel)
            }
        }

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.stat_notify_sync)
            .setContentTitle("$panel Balance Updated")
            .setContentText("نیا بیلنس: Rs. ${"%,.0f".format(balance)} ($zone)")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()
            
        manager.notify(7005 + (panel + zone).hashCode(), notification)
    }

    fun updateBalance(panel: String, balance: Double, zone: String = "Okara", onDone: (Boolean) -> Unit = {}) {
        doc.set(mapOf(balanceField(panel, zone) to balance), SetOptions.merge())
            .addOnSuccessListener { onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    fun checkAndNotifyLowBalance(context: Context, panel: String, balance: Double, zone: String = "Okara") {
        doc.get().addOnSuccessListener { snapshot ->
            val zoneThreshold = snapshot.getDouble(thresholdField(panel, zone))
            val baseThreshold = snapshot.getDouble("${panelKey(panel)}LowBalanceThreshold")
            val threshold = zoneThreshold ?: baseThreshold ?: return@addOnSuccessListener
            if (threshold <= 0.0) return@addOnSuccessListener
            if (balance < threshold) {
                showLowBalanceNotification(context, panel, zone, balance, threshold)
            }
        }
    }

    private fun showLowBalanceNotification(context: Context, panel: String, zone: String, balance: Double, threshold: Double) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(LOW_BALANCE_CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(LOW_BALANCE_CHANNEL_ID, "Franchise Low Balance", NotificationManager.IMPORTANCE_HIGH))
            }
        }
        val zoneLabel = if (zone.equals("Okara", ignoreCase = true)) panel else "$panel ($zone)"
        val notification = NotificationCompat.Builder(context, LOW_BALANCE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dialog_alert)
            .setContentTitle("$zoneLabel Balance Low")
            .setContentText("Only Rs. ${"%,.0f".format(balance)} left — threshold Rs. ${"%,.0f".format(threshold)}.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(("${panelKey(panel)}_$zone").hashCode(), notification)
    }

    fun loadAll(callback: (Map<String, Any>) -> Unit) {
        doc.get().addOnSuccessListener { snap -> callback(snap.data ?: emptyMap()) }.addOnFailureListener { callback(emptyMap()) }
    }
}

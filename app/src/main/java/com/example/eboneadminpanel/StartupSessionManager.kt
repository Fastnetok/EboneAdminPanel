package com.example.eboneadminpanel

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Handles background session "pokes" on app startup.
 * Staggers 4 panels to ensure cookies are fresh.
 */
object StartupSessionManager {
    private const val TAG = "StartupSession"
    private const val NOTIF_ID = 8001
    private const val CHANNEL_ID = "startup_sync"

    fun startRefresh(context: Context) {
        Log.d(TAG, "Starting staggered session refresh...")
        
        showProgressNotification(context, "Updating Panel Sessions...", "Ebill ریفریش ہو رہا ہے...")

        val handler = Handler(Looper.getMainLooper())
        
        // 1. EBILL (Immediate)
        BackgroundBalanceUpdater.checkBalance(context, "EBONE", "Okara") {
            Log.d(TAG, "Ebill poke done")
        }

        // 2. WATEEN (After 5s)
        handler.postDelayed({
            updateNotification(context, "Updating Panel Sessions...", "Wateen ریفریش ہو رہا ہے...")
            BackgroundBalanceUpdater.checkBalance(context, "WATEEN", "Okara") {
                Log.d(TAG, "Wateen poke done")
            }
        }, 5000)

        // 3. ZONG OKARA (After 10s)
        handler.postDelayed({
            updateNotification(context, "Updating Panel Sessions...", "Zong (Okara) ریفریش ہو رہا ہے...")
            BackgroundBalanceUpdater.checkBalance(context, "ZONG", "Okara") {
                Log.d(TAG, "Zong Okara poke done")
            }
        }, 10000)

        // 4. ZONG RENALA (After 15s)
        handler.postDelayed({
            updateNotification(context, "Updating Panel Sessions...", "Zong (Renala) ریفریش ہو رہا ہے...")
            BackgroundBalanceUpdater.checkBalance(context, "ZONG", "Renala") {
                Log.d(TAG, "Zong Renala poke done. All fresh!")
                cancelNotification(context)
            }
        }, 15000)
    }

    private fun showProgressNotification(context: Context, title: String, msg: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Startup Sync", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(msg)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(NOTIF_ID, notif)
    }

    private fun updateNotification(context: Context, title: String, msg: String) {
        showProgressNotification(context, title, msg)
    }

    private fun cancelNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIF_ID)
    }
}

package com.example.eboneadminpanel

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Runs on the SAME schedule as PaymentSyncWorker (piggybacks off the same
 * WorkManager periodic tick — see PaymentSyncSettingsActivity). Checks
 * every customer with a graceDeadline in the past and activationStatus
 * still ACTIVE, and raises a "Tap to suspend" notification for each —
 * mirroring PaymentNotificationHelper's "Tap to activate" pattern.
 *
 * FIX: added whereEqualTo("reliefStatus", "ACTIVE") so this ONLY ever
 * fires for customers actually placed on Relief from Unpaid Package
 * Activation — never for an ordinary customer whose graceDeadline field
 * happened to be stale/set for an unrelated reason.
 */
class GraceDeadlineWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                checkGraceDeadlines()
                Result.success()
            } catch (e: Exception) {
                Result.retry()
            }
        }
    }

    private suspend fun checkGraceDeadlines() {
        val db = FirebaseFirestore.getInstance()
        val now = System.currentTimeMillis()

        val snapshot = db.collection("customers")
            .whereEqualTo("activationStatus", "ACTIVE")
            .whereEqualTo("reliefStatus", "ACTIVE")
            .whereLessThan("graceDeadline", now)
            .get()
            .await()

        for (doc in snapshot.documents) {
            val customerId = doc.getString("customerId") ?: doc.id
            val isp = doc.getString("ispProvider") ?: "EBONE"
            showSuspendNotification(customerId, isp)
        }
    }

    private fun showSuspendNotification(customerId: String, isp: String) {
        val context = applicationContext
        val intent = Intent(context, WebViewLoginActivity::class.java).apply {
            putExtra("selected_isp", isp)
            putExtra("auto_activate_customer_id", customerId)
            putExtra("manual_action", "SUSPEND")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, ("suspend_$customerId").hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, PaymentActivationService.CHANNEL_ID)
            .setContentTitle("Grace period expired")
            .setContentText("$customerId hasn't paid — tap to suspend")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(("suspend_$customerId").hashCode(), notification)
    }
}
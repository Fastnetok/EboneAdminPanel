package com.example.eboneadminpanel

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Runs on a schedule (default every 15 min — Android's WorkManager does
 * not allow periodic intervals shorter than 15 minutes for battery-life
 * reasons; that limit is a system restriction, not something this app
 * can change) and re-checks every PENDING transaction against the SMS
 * inbox. Survives phone restarts automatically — WorkManager persists
 * scheduled periodic work across reboots on its own, no extra
 * BOOT_COMPLETED receiver needed.
 */
class PaymentSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                PaymentSmsScanner.scanAllPending(applicationContext)
                Result.success()
            } catch (e: Exception) {
                Result.retry()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "payment_auto_sync_work"

        /** Schedules (or reschedules) the periodic sync at the given interval. */
        fun schedule(context: Context, intervalMinutes: Long) {
            val safeInterval = intervalMinutes.coerceAtLeast(15) // WorkManager minimum
            val request = PeriodicWorkRequestBuilder<PaymentSyncWorker>(
                safeInterval, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** Turns auto-sync off. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
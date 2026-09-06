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
 * PENDING transaction) so both can show the same "Payment verified"
 * notification without duplicating the logic.
 *
 * FIX: previously this only ever built a notification whose
 * PendingIntent launched WebViewLoginActivity when TAPPED — if the
 * admin never tapped it, the customer's ID was never actually
 * recharged, even though the SMS had already matched. Recharge must
 * not depend on a manual tap. This now starts WebViewLoginActivity
 * directly and automatically the moment a match is found; the
 * notification is kept only as an informational confirmation (and as
 * a way to re-open the same screen manually if needed), not as the
 * trigger.
 */
object PaymentNotificationHelper {

    // FIX: this customer ID belongs to the EBONE "Akmal" DEALER panel,
    // not the Franchise. Without passing dealer_account_name,
    // WebViewLoginActivity's tryAutoLogin() falls through to
    // IspPanelSettingsActivity.getSavedUsername(), which is
    // Franchise-first by design — so recharge was silently opening the
    // wrong (Franchise) login instead of Akmal's dealer panel. Same
    // fixed dealer name already used for EBONE relief actions in
    // UnpaidPackageActivationActivity (RELIEF_DEALER_NAME = "Akmal").
    private const val EBONE_DEALER_NAME = "Akmal"

    fun showActivationNotification(context: Context, transactionId: String, customerId: String) {
        FirebaseFirestore.getInstance()
            .collection("customers").document(customerId).get()
            .addOnSuccessListener { customerDoc ->
                val isp = customerDoc.getString("ispProvider") ?: "EBONE"

                val intent = Intent(context, WebViewLoginActivity::class.java).apply {
                    putExtra("selected_isp", isp)
                    putExtra("auto_activate_customer_id", customerId)
                    putExtra("transaction_id", transactionId)
                    // Force the correct dealer login for EBONE so this
                    // customer's ID recharges via Akmal's dealer panel
                    // instead of the Franchise account. WATEEN/ZONG are
                    // unchanged — they still use whatever Franchise/
                    // account is already configured for them in ISP
                    // Panel Settings.
                    if (isp.equals("EBONE", ignoreCase = true)) {
                        putExtra("dealer_account_name", EBONE_DEALER_NAME)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                // FIX: launch immediately — this is what makes the
                // recharge fully automatic. No tap required.
                context.startActivity(intent)

                // Notification is now purely informational — confirms to
                // the admin that a match was found and recharge started,
                // and can still be tapped to re-open the same screen
                // (e.g. if it needs to be checked again), but is no
                // longer required for the recharge to happen at all.
                val pendingIntent = PendingIntent.getActivity(
                    context, transactionId.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, PaymentActivationService.CHANNEL_ID)
                    .setContentTitle("Payment Verified via SMS")
                    .setContentText("$customerId — Recharging automatically…")
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
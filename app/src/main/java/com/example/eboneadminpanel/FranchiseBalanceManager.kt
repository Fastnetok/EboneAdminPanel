package com.example.eboneadminpanel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Tracks each ISP's own Franchise Balance — the balance shown INSIDE
 * partner.ebill.pk / panel.wateen.com / turbonet.zong.com.pk itself, per
 * ZONE (franchise) — e.g. Okara and Renala each have their own separate
 * Zong login/panel/balance, and must never mix.
 *
 * NOT the same as a dealer's internal wateenBalance/eboneBalance/
 * zongBalance on the `dealers` collection (that's the dealer's own
 * wallet, credited by DealerPaymentSmsReceiver/Scanner). This tracks the
 * FRANCHISE side — how much is left before the real panel runs dry.
 *
 * Firestore doc: franchiseSettings/balances
 *   For zone == "Okara" (the original/default zone, kept as the plain
 *   field names for backward compatibility with everything already
 *   built): eboneBalance / wateenBalance / zongBalance,
 *   eboneLowBalanceThreshold / wateenLowBalanceThreshold /
 *   zongLowBalanceThreshold
 *
 *   For any OTHER zone (e.g. "Renala"): the zone name is appended —
 *   zongBalance_Renala, zongLowBalanceThreshold_Renala, etc. — so a
 *   second (or third) franchise's balance is tracked completely
 *   separately from Okara's.
 */
object FranchiseBalanceManager {

    private val doc by lazy {
        FirebaseFirestore.getInstance()
            .collection("franchiseSettings")
            .document("balances")
    }

    private fun panelKey(panel: String) = panel.trim().lowercase()

    /** "Okara" keeps the original field name for backward compatibility.
     * Other zones get the zone name appended. */
    private fun balanceField(panel: String, zone: String): String {
        val base = "${panelKey(panel)}Balance"
        return if (zone.equals("Okara", ignoreCase = true)) {
            base
        } else {
            "${base}_$zone"
        }
    }

    /** "Okara" keeps the original threshold field.
     * Other zones use their own threshold when configured. */
    private fun thresholdField(panel: String, zone: String): String {
        val base = "${panelKey(panel)}LowBalanceThreshold"
        return if (zone.equals("Okara", ignoreCase = true)) {
            base
        } else {
            "${base}_$zone"
        }
    }

    /**
     * Saves the latest known franchise balance for [panel] in [zone].
     * This only writes the balance field; thresholds and all other
     * Firestore settings remain untouched.
     */
    fun updateBalance(
        panel: String,
        balance: Double,
        zone: String = "Okara",
        onDone: (Boolean) -> Unit = {}
    ) {
        doc.set(
            mapOf(balanceField(panel, zone) to balance),
            SetOptions.merge()
        )
            .addOnSuccessListener { onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    /**
     * Checks [balance] against the configured low-balance threshold
     * for [panel]/[zone].
     *
     * For non-Okara zones:
     *   1. Use the zone-specific threshold if it exists.
     *   2. If it does not exist, fall back to the main panel threshold.
     *
     * Example:
     *   zongLowBalanceThreshold_Renala -> if present, use it.
     *   Otherwise zongLowBalanceThreshold -> use this value.
     *
     * This keeps Renala alerts working with the existing Firestore
     * configuration and does not require manually adding a new field.
     */
    fun checkAndNotifyLowBalance(
        context: Context,
        panel: String,
        balance: Double,
        zone: String = "Okara"
    ) {
        doc.get()
            .addOnSuccessListener { snapshot ->

                val zoneThreshold =
                    snapshot.getDouble(thresholdField(panel, zone))

                val baseThreshold =
                    snapshot.getDouble("${panelKey(panel)}LowBalanceThreshold")

                val threshold = zoneThreshold ?: baseThreshold
                ?: return@addOnSuccessListener

                if (threshold <= 0.0) return@addOnSuccessListener

                if (balance < threshold) {
                    showLowBalanceNotification(
                        context = context,
                        panel = panel,
                        zone = zone,
                        balance = balance,
                        threshold = threshold
                    )
                }
            }
    }

    private fun showLowBalanceNotification(
        context: Context,
        panel: String,
        zone: String,
        balance: Double,
        threshold: Double
    ) {
        val channelId = "franchise_low_balance"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Franchise Low Balance",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        }

        val zoneLabel =
            if (zone.equals("Okara", ignoreCase = true)) {
                panel
            } else {
                "$panel ($zone)"
            }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("$zoneLabel Balance Low")
            .setContentText(
                "Only Rs. ${"%,.0f".format(balance)} left — " +
                        "threshold Rs. ${"%,.0f".format(threshold)}."
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // Panel + zone have separate notification IDs, so:
        // Zong Okara and Zong Renala cannot overwrite each other.
        val notificationId =
            ("${panelKey(panel)}_$zone").hashCode()

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(notificationId, notification)
    }

    /** Loads the full balances/thresholds document. */
    fun loadAll(callback: (Map<String, Any>) -> Unit) {
        doc.get()
            .addOnSuccessListener { snap ->
                callback(snap.data ?: emptyMap())
            }
            .addOnFailureListener {
                callback(emptyMap())
            }
    }
}
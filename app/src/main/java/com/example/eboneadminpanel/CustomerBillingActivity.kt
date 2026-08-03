package com.example.eboneadminpanel

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.eboneadminpanel.databinding.ActivityCustomerBillingBinding
import com.example.eboneadminpanel.databinding.ItemNetworkRowBinding
import com.example.eboneadminpanel.databinding.ItemTransactionRowBinding
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Customer Billing dashboard — shows today's payment activity across all
 * three ISP panels (Ebone/Wateen/Zong), synced LIVE from Firestore (real-time
 * listeners, not one-time fetches) — the screen updates itself the instant a
 * new payment/mismatch appears, without needing to close and reopen the app.
 */
class CustomerBillingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomerBillingBinding
    private val db = FirebaseFirestore.getInstance()

    private var statsListener: ListenerRegistration? = null
    private var accountsListener: ListenerRegistration? = null
    private var recentTxnListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomerBillingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvToday.text = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(Date())

        startTodaysTransactionStatsListener()
        startAccountsOverviewListener()
        startRecentTransactionsListener()

        binding.btnMenu.setOnClickListener { finish() }
        binding.btnViewAllTransactions.setOnClickListener {
            // TODO: full Transactions list screen (future)
        }
    }

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Today's Verified/Failed counts + earnings — live, updates automatically. */
    private fun startTodaysTransactionStatsListener() {
        statsListener = db.collection("transactions")
            .whereGreaterThanOrEqualTo("createdAt", startOfTodayMillis())
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                var verified = 0
                var failed = 0
                var earnings = 0.0

                for (doc in snapshot.documents) {
                    val status = doc.getString("status") ?: ""
                    val amount = doc.getDouble("amount") ?: 0.0
                    when (status) {
                        "VERIFIED" -> {
                            verified++
                            earnings += amount
                        }
                        "FAILED", "INSUFFICIENT", "OVERPAID" -> failed++
                    }
                }

                binding.tvPackagesToday.text = verified.toString()
                binding.tvVerifiedToday.text = verified.toString()
                binding.tvFailedToday.text = failed.toString()
                binding.tvEarningsToday.text = "Rs %,.0f".format(earnings)
            }
    }

    /**
     * Total/Active/Disabled accounts + package-speed breakdown + network
     * (ISP provider) breakdown — live, from the "customers" collection.
     */
    private fun startAccountsOverviewListener() {
        accountsListener = db.collection("customers")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                var active = 0
                var disabled = 0
                val speedCounts = mutableMapOf<String, Int>()
                val networkCounts = mutableMapOf<String, Pair<Int, Double>>() // isp -> (count, earnings)

                val nowCal = startOfTodayMillis()

                for (doc in snapshot.documents) {
                    val activationStatus = doc.getString("activationStatus") ?: "ACTIVE"
                    val lastPaymentDate = doc.getLong("lastPaymentDate")
                    val billingCycleDays = (doc.getLong("billingCycleDays") ?: 30L).toInt()
                    val packageId = doc.getString("packageId") ?: "Unknown"
                    val packagePrice = doc.getDouble("packagePrice") ?: 0.0
                    val ispProvider = doc.getString("ispProvider") ?: "EBONE"

                    val isActive = when {
                        activationStatus == "PENDING_APPROVAL" -> false
                        lastPaymentDate == null -> false
                        else -> {
                            val expiresAt = lastPaymentDate + (billingCycleDays * 24L * 60L * 60L * 1000L)
                            System.currentTimeMillis() < expiresAt
                        }
                    }
                    if (isActive) active++ else disabled++

                    if (lastPaymentDate != null && lastPaymentDate >= nowCal) {
                        speedCounts[packageId] = (speedCounts[packageId] ?: 0) + 1
                        val current = networkCounts[ispProvider] ?: (0 to 0.0)
                        networkCounts[ispProvider] = (current.first + 1) to (current.second + packagePrice)
                    }
                }

                binding.tvTotalAccounts.text = (active + disabled).toString()
                binding.tvActiveAccounts.text = active.toString()
                binding.tvDisabledAccounts.text = disabled.toString()

                renderSpeedBreakdown(speedCounts)
                renderNetworkBreakdown(networkCounts)
            }
    }

    private fun renderSpeedBreakdown(speedCounts: Map<String, Int>) {
        val entries = speedCounts.entries.sortedByDescending { it.value }.take(3)
        val colors = listOf("#378ADD", "#1D9E75", "#BA7517")
        val total = speedCounts.values.sum().coerceAtLeast(1)

        val segments = entries.mapIndexed { i, e ->
            DonutChartView.Segment(e.value.toFloat(), Color.parseColor(colors.getOrElse(i) { "#9E9E9E" }))
        }
        binding.donutChart.setData(segments, total.toString())

        val labels = listOf(binding.tv6MbpsCount, binding.tv8MbpsCount, binding.tv10MbpsCount)
        entries.forEachIndexed { i, e ->
            if (i < labels.size) labels[i].text = "${e.key}: ${e.value}"
        }
    }

    private fun renderNetworkBreakdown(networkCounts: Map<String, Pair<Int, Double>>) {
        binding.networkBreakdownContainer.removeAllViews()
        val order = listOf("EBONE", "WATEEN", "ZONG")
        order.forEach { isp ->
            val (count, earnings) = networkCounts[isp] ?: (0 to 0.0)
            val row = ItemNetworkRowBinding.inflate(LayoutInflater.from(this), binding.networkBreakdownContainer, false)
            row.tvNetworkName.text = isp.lowercase().replaceFirstChar { it.uppercase() }
            row.tvNetworkPackages.text = "$count packages"
            row.tvNetworkEarnings.text = "Rs %,.0f".format(earnings)
            binding.networkBreakdownContainer.addView(row.root)
        }
    }

    /** Live-updating "Recent Transactions" list — refreshes itself the instant Firestore changes. */
    private fun startRecentTransactionsListener() {
        recentTxnListener = db.collection("transactions")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(5)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                binding.recentTransactionsContainer.removeAllViews()
                for (doc in snapshot.documents) {
                    val customerId = doc.getString("customerId") ?: "—"
                    val source = doc.getString("source") ?: ""
                    val status = doc.getString("status") ?: "PENDING"
                    val createdAt = doc.getLong("createdAt") ?: 0L
                    val timeText = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(createdAt))

                    val row = ItemTransactionRowBinding.inflate(LayoutInflater.from(this), binding.recentTransactionsContainer, false)
                    row.tvTxnCustomerId.text = customerId
                    row.tvTxnMeta.text = "$source · $timeText"

                    when (status) {
                        "VERIFIED" -> {
                            row.tvTxnStatus.text = getString(R.string.status_verified)
                            row.tvTxnStatus.setBackgroundResource(R.drawable.bg_chip_verified)
                            row.tvTxnStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
                        }
                        "PENDING" -> {
                            row.tvTxnStatus.text = "Waiting for SMS"
                            row.tvTxnStatus.setBackgroundResource(R.drawable.bg_stat_card)
                            row.tvTxnStatus.setTextColor(Color.parseColor("#5F5E5A"))

                            row.root.setOnClickListener {
                                androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle("Clear this transaction?")
                                    .setMessage("$customerId — $source, $timeText\n\nThis will delete the entry so the customer can submit their payment again.")
                                    .setPositiveButton("Delete") { _, _ ->
                                        db.collection("transactions").document(doc.id).delete()
                                            .addOnSuccessListener {
                                                android.widget.Toast.makeText(this, "Cleared — customer can retry now", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            .addOnFailureListener { e ->
                                                android.widget.Toast.makeText(this, "Delete failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                        else -> {
                            row.tvTxnStatus.text = getString(R.string.status_mismatch)
                            row.tvTxnStatus.setBackgroundResource(R.drawable.bg_chip_mismatch)
                            row.tvTxnStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error_text))

                            row.root.setOnClickListener {
                                androidx.appcompat.app.AlertDialog.Builder(this)
                                    .setTitle("Clear this transaction?")
                                    .setMessage("$customerId — $source, $timeText\n\nThis will delete the failed entry so the customer can submit their payment again.")
                                    .setPositiveButton("Delete") { _, _ ->
                                        db.collection("transactions").document(doc.id).delete()
                                            .addOnSuccessListener {
                                                android.widget.Toast.makeText(this, "Cleared — customer can retry now", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            .addOnFailureListener { e ->
                                                android.widget.Toast.makeText(this, "Delete failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                        }
                    }
                    binding.recentTransactionsContainer.addView(row.root)
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        statsListener?.remove()
        accountsListener?.remove()
        recentTxnListener?.remove()
    }
}
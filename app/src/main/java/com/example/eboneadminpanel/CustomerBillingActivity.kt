package com.example.eboneadminpanel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.eboneadminpanel.databinding.ActivityCustomerBillingBinding
import com.example.eboneadminpanel.databinding.ItemNetworkRowBinding
import com.example.eboneadminpanel.databinding.ItemTransactionRowBinding
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CustomerBillingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomerBillingBinding
    private val db = FirebaseFirestore.getInstance()

    private var statsListener: ListenerRegistration? = null
    private var accountsListener: ListenerRegistration? = null
    private var recentTxnListener: ListenerRegistration? = null

    private val failedDocs = mutableListOf<DocumentSnapshot>()
    private val allCustomerDocs = mutableListOf<DocumentSnapshot>()

    private val companies = listOf("EBONE", "WATEEN", "ZONG")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomerBillingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvToday.text = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(Date())

        startTodaysTransactionStatsListener()
        startAccountsOverviewListener()
        startRecentTransactionsListener()

        binding.btnMenu.setOnClickListener { finish() }
        binding.btnViewAllTransactions.setOnClickListener { }

        // Failed entries card
        binding.tvFailedToday.setOnClickListener { showFailedEntriesDialog() }
        try {
            (binding.tvFailedToday.parent as? View)?.setOnClickListener { showFailedEntriesDialog() }
        } catch (_: Exception) {}

        // Total/Active/Disabled cards → open CustomerListActivity
        binding.cardTotalAccounts.setOnClickListener {
            val i = Intent(this, CustomerListActivity::class.java)
            i.putExtra("filter", "ALL")
            startActivity(i)
        }
        binding.cardActiveAccounts.setOnClickListener {
            val i = Intent(this, CustomerListActivity::class.java)
            i.putExtra("filter", "ACTIVE")
            startActivity(i)
        }
        binding.cardDisabledAccounts.setOnClickListener {
            val i = Intent(this, CustomerListActivity::class.java)
            i.putExtra("filter", "DISABLED")
            startActivity(i)
        }
    }

    // ===================== FAILED ENTRIES DIALOG =====================

    private fun showFailedEntriesDialog() {
        if (failedDocs.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Failed / Fake Entries")
                .setMessage("No failed or pending entries at the moment.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }
        scrollView.addView(container)

        failedDocs.forEach { doc ->
            val customerId = doc.getString("customerId") ?: "—"
            val source = doc.getString("source") ?: ""
            val status = doc.getString("status") ?: "PENDING"
            val createdAt = doc.getLong("createdAt") ?: 0L
            val timeText = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(createdAt))

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F8F8F8"))
                setPadding(24, 20, 24, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, 12) }
            }

            val nameView = TextView(this).apply {
                text = customerId
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#0D2E5C"))
            }
            val metaView = TextView(this).apply {
                text = "$source · $timeText · $status"
                textSize = 12f
                setTextColor(Color.parseColor("#757575"))
            }
            val clearBtn = Button(this).apply {
                text = "Clear Entry"
                setBackgroundColor(Color.parseColor("#C62828"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = 10 }
            }

            clearBtn.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Clear this entry?")
                    .setMessage("This will remove the failed entry so the customer can submit their payment again.")
                    .setPositiveButton("Yes, Clear") { _, _ ->
                        db.collection("transactions").document(doc.id).delete()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            card.addView(nameView)
            card.addView(metaView)
            card.addView(clearBtn)
            container.addView(card)
        }

        AlertDialog.Builder(this)
            .setTitle("Failed / Fake Entries (${failedDocs.size})")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    // ===================== HELPER =====================

    private fun isCustomerActive(doc: DocumentSnapshot): Boolean {
        val activationStatus = doc.getString("activationStatus") ?: "ACTIVE"
        val lastPaymentDate = doc.getLong("lastPaymentDate") ?: return false
        val billingCycleDays = (doc.getLong("billingCycleDays") ?: 30L).toInt()
        if (activationStatus == "PENDING_APPROVAL") return false
        val expiresAt = lastPaymentDate + (billingCycleDays * 24L * 60L * 60L * 1000L)
        return System.currentTimeMillis() < expiresAt
    }

    // ===================== FIRESTORE LISTENERS =====================

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun startTodaysTransactionStatsListener() {
        // Show ALL transactions (no date filter) so verified payments always appear
        statsListener = db.collection("transactions")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                var verified = 0; var failed = 0; var earnings = 0.0
                failedDocs.clear()
                val todayStart = startOfTodayMillis()
                for (doc in snapshot.documents) {
                    val status = doc.getString("status") ?: ""
                    val amount = doc.getDouble("amount") ?: 0.0
                    val createdAt = doc.getLong("createdAt") ?: 0L
                    when (status) {
                        "VERIFIED" -> {
                            verified++
                            // Earnings only from today
                            if (createdAt >= todayStart) earnings += amount
                        }
                        "FAILED", "INSUFFICIENT", "OVERPAID", "PENDING" -> {
                            // Only show today's failed/pending in failed list
                            if (createdAt >= todayStart) {
                                failed++
                                failedDocs.add(doc)
                            }
                        }
                    }
                }
                binding.tvPackagesToday.text = verified.toString()
                binding.tvVerifiedToday.text = verified.toString()
                binding.tvFailedToday.text = failed.toString()
                binding.tvEarningsToday.text = "Rs %,.0f".format(earnings)
            }
    }

    private fun startAccountsOverviewListener() {
        accountsListener = db.collection("customers")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                allCustomerDocs.clear()
                allCustomerDocs.addAll(snapshot.documents)

                var active = 0; var disabled = 0
                val speedCounts = mutableMapOf<String, Int>()
                val networkCounts = mutableMapOf<String, Pair<Int, Double>>()
                val nowCal = startOfTodayMillis()

                for (doc in snapshot.documents) {
                    val packageId = doc.getString("packageId") ?: "Unknown"
                    val packagePrice = doc.getDouble("packagePrice") ?: 0.0
                    val ispProvider = doc.getString("ispProvider") ?: "EBONE"
                    val lastPaymentDate = doc.getLong("lastPaymentDate")
                    val isActive = isCustomerActive(doc)
                    if (isActive) active++ else disabled++

                    // Speed breakdown: ALL customers (not just today)
                    speedCounts[packageId] = (speedCounts[packageId] ?: 0) + 1

                    // Network breakdown: only today's payments
                    if (lastPaymentDate != null && lastPaymentDate >= nowCal) {
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

    private fun normalizeSpeed(packageId: String): String {
        // Extract speed number from any format:
        // "Bronze (6mbps)", "6 Mbps", "6mbps", "6MB" etc. → "6 Mbps"
        val match = Regex("""(\d+)\s*[Mm][Bb]""").find(packageId)
        return if (match != null) "${match.groupValues[1]} Mbps" else packageId
    }

    private fun renderSpeedBreakdown(rawSpeedCounts: Map<String, Int>) {
        // Normalize all package names and merge duplicates
        val speedCounts = mutableMapOf<String, Int>()
        for ((pkg, count) in rawSpeedCounts) {
            val normalized = normalizeSpeed(pkg)
            speedCounts[normalized] = (speedCounts[normalized] ?: 0) + count
        }
        val entries = speedCounts.entries.sortedByDescending { it.value }.take(3)
        val colors = listOf("#378ADD", "#1D9E75", "#BA7517")
        val total = speedCounts.values.sum().coerceAtLeast(1)
        val segments = entries.mapIndexed { i, e ->
            DonutChartView.Segment(e.value.toFloat(), Color.parseColor(colors.getOrElse(i) { "#9E9E9E" }))
        }
        binding.donutChart.setData(segments, total.toString())
        val labels = listOf(binding.tv6MbpsCount, binding.tv8MbpsCount, binding.tv10MbpsCount)
        entries.forEachIndexed { i, e -> if (i < labels.size) labels[i].text = "${e.key}: ${e.value}" }
    }

    private fun renderNetworkBreakdown(networkCounts: Map<String, Pair<Int, Double>>) {
        binding.networkBreakdownContainer.removeAllViews()
        listOf("EBONE", "WATEEN", "ZONG").forEach { isp ->
            val (count, earnings) = networkCounts[isp] ?: (0 to 0.0)
            val row = ItemNetworkRowBinding.inflate(LayoutInflater.from(this), binding.networkBreakdownContainer, false)
            row.tvNetworkName.text = isp.lowercase().replaceFirstChar { it.uppercase() }
            row.tvNetworkPackages.text = "$count packages"
            row.tvNetworkEarnings.text = "Rs %,.0f".format(earnings)
            binding.networkBreakdownContainer.addView(row.root)
        }
    }

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
                            row.root.setOnClickListener { showClearDialog(doc.id, customerId, source, timeText) }
                        }
                        else -> {
                            row.tvTxnStatus.text = getString(R.string.status_mismatch)
                            row.tvTxnStatus.setBackgroundResource(R.drawable.bg_chip_mismatch)
                            row.tvTxnStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error_text))
                            row.root.setOnClickListener { showClearDialog(doc.id, customerId, source, timeText) }
                        }
                    }
                    binding.recentTransactionsContainer.addView(row.root)
                }
            }
    }

    private fun showClearDialog(docId: String, customerId: String, source: String, timeText: String) {
        AlertDialog.Builder(this)
            .setTitle("Clear this entry?")
            .setMessage("Customer: $customerId\nMethod: $source · $timeText\n\nThis will remove the failed entry so the customer can submit their payment again.")
            .setPositiveButton("Yes, Clear") { _, _ ->
                db.collection("transactions").document(docId).delete()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        statsListener?.remove()
        accountsListener?.remove()
        recentTxnListener?.remove()
    }
}
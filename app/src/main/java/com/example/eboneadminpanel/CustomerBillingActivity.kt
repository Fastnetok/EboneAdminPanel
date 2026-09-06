package com.example.eboneadminpanel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.*
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
    private val providerByCustomerId = mutableMapOf<String, String>()
    private val packagePriceByCustomerId = mutableMapOf<String, Double>()
    private val packageIdByCustomerId = mutableMapOf<String, String>()
    private var latestVerifiedTodayTransactions: List<DocumentSnapshot> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomerBillingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvToday.text =
            SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(Date())

        startTodaysTransactionStatsListener()
        startAccountsOverviewListener()
        startRecentTransactionsListener()

        // The top-left ☰ menu button now opens the Payment Methods
        // enable/disable settings screen (Easypaisa/JazzCash/Faysal
        // Bank/etc. — which ones show up on the Customer ID App's
        // payment screen). Previously this just closed the screen
        // (finish()); that behavior is intentionally replaced per
        // explicit instruction — this settings screen must be reachable
        // ONLY from here, not from the main Admin Menu.
        binding.btnMenu.setOnClickListener {
            startActivity(Intent(this, PaymentMethodSettingsActivity::class.java))
        }
        binding.btnViewAllTransactions.setOnClickListener { }

        // Unpaid Today = currently active Relief customers / currently
        // disabled (suspended) relief customers — see renderUnpaidTodayCard().
        binding.tvUnpaidToday.setOnClickListener {
            startActivity(Intent(this, UnpaidPackageActivationActivity::class.java))
        }
        (binding.tvUnpaidToday.parent as? View)?.setOnClickListener {
            startActivity(Intent(this, UnpaidPackageActivationActivity::class.java))
        }

        binding.tvFailedToday.setOnClickListener { showFailedEntriesDialog() }
        try {
            (binding.tvFailedToday.parent as? View)?.setOnClickListener {
                showFailedEntriesDialog()
            }
        } catch (_: Exception) {}

        binding.cardTotalAccounts.setOnClickListener {
            startActivity(Intent(this, CustomerListActivity::class.java).apply {
                putExtra("filter", "ALL")
            })
        }

        binding.cardActiveAccounts.setOnClickListener {
            startActivity(Intent(this, CustomerListActivity::class.java).apply {
                putExtra("filter", "ACTIVE")
            })
        }

        binding.cardDisabledAccounts.setOnClickListener {
            startActivity(Intent(this, CustomerListActivity::class.java).apply {
                putExtra("filter", "DISABLED")
            })
        }
    }

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
            val timeText =
                SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(createdAt))

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

    private fun isCustomerActive(doc: DocumentSnapshot): Boolean {
        val activationStatus = doc.getString("activationStatus") ?: "ACTIVE"
        val lastPaymentDate = longValue(doc, "lastPaymentDate").takeIf { it > 0 } ?: return false
        val billingCycleDays = longValue(doc, "billingCycleDays").takeIf { it > 0 }?.toInt() ?: 30

        if (activationStatus == "PENDING_APPROVAL") return false

        val expiresAt =
            lastPaymentDate + (billingCycleDays * 24L * 60L * 60L * 1000L)

        return System.currentTimeMillis() < expiresAt
    }

    private fun numberValue(doc: DocumentSnapshot, field: String): Double {
        return (doc.get(field) as? Number)?.toDouble() ?: 0.0
    }

    private fun longValue(doc: DocumentSnapshot, field: String): Long {
        return (doc.get(field) as? Number)?.toLong() ?: 0L
    }

    private fun stringValue(doc: DocumentSnapshot, field: String): String {
        return doc.get(field)?.toString()?.trim().orEmpty()
    }

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun startTodaysTransactionStatsListener() {
        statsListener = db.collection("transactions")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener

                val todayStart = startOfTodayMillis()

                var verifiedToday = 0
                var verifiedTodayAmount = 0.0
                var failedToday = 0

                failedDocs.clear()

                val verifiedTodayDocs = mutableListOf<DocumentSnapshot>()

                for (doc in snapshot.documents) {
                    val status = stringValue(doc, "status")
                        .uppercase(Locale.getDefault())

                    val transactionAmount =
                        numberValue(doc, "amount")

                    val createdAt =
                        longValue(doc, "createdAt")

                    if (createdAt < todayStart) continue

                    when (status) {
                        "VERIFIED" -> {
                            verifiedToday++
                            verifiedTodayDocs.add(doc)

                            val customerId =
                                stringValue(doc, "customerId")

                            // The registered customer's packagePrice is the
                            // billing amount. Transaction amount is used only
                            // as a legacy fallback if no customer price exists.
                            val packagePrice =
                                packagePriceByCustomerId[customerId]
                                    ?: transactionAmount

                            verifiedTodayAmount += packagePrice
                        }

                        "FAILED",
                        "INSUFFICIENT",
                        "OVERPAID",
                        "PENDING" -> {
                            failedToday++
                            failedDocs.add(doc)
                        }
                    }
                }

                binding.tvPackagesToday.text =
                    verifiedToday.toString()

                binding.tvVerifiedToday.text =
                    verifiedToday.toString()

                binding.tvUnpaidPackagesToday.text =
                    "Packages Activated: $verifiedToday"
                // Unpaid Today is rendered from the customers collection.
                // Transaction amounts/statuses are not used for the Relief count.

                binding.tvFailedToday.text =
                    failedToday.toString()

                latestVerifiedTodayTransactions =
                    verifiedTodayDocs

                renderNetworkActivationBreakdown()
            }
    }

    private fun startAccountsOverviewListener() {
        accountsListener = db.collection("customers")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener

                allCustomerDocs.clear()
                allCustomerDocs.addAll(snapshot.documents)

                var active = 0
                var disabled = 0
                var activeRelief = 0
                // NEW: live count of everyone currently DISABLED because of
                // relief (reliefStatus == "SUSPENDED") — the same set shown
                // in UnpaidPackageActivationActivity's "Disabled Users" list.
                // Paired with activeRelief below to render the "X / Y" count
                // on the Unpaid Today card.
                var disabledRelief = 0
                val speedCounts = mutableMapOf<String, Int>()

                providerByCustomerId.clear()
                packagePriceByCustomerId.clear()
                packageIdByCustomerId.clear()

                for (doc in snapshot.documents) {
                    val packageId =
                        stringValue(doc, "packageId")
                            .ifEmpty { "Unknown" }

                    val isActive =
                        isCustomerActive(doc)

                    if (isActive) active++ else disabled++

                    val reliefStatus =
                        stringValue(doc, "reliefStatus").uppercase(Locale.getDefault())
                    val graceDeadline =
                        longValue(doc, "graceDeadline")

                    if (reliefStatus == "ACTIVE" &&
                        graceDeadline > System.currentTimeMillis()
                    ) {
                        activeRelief++
                    }

                    if (reliefStatus == "SUSPENDED") {
                        disabledRelief++
                    }

                    speedCounts[packageId] =
                        (speedCounts[packageId] ?: 0) + 1

                    val provider =
                        normalizeProvider(
                            stringValue(doc, "ispProvider")
                                .ifEmpty { "EBONE" }
                        )

                    val customerId =
                        stringValue(doc, "customerId")
                            .ifEmpty { doc.id }

                    // Store both the Firestore document ID and the explicit
                    // customerId so either transaction format can match.
                    providerByCustomerId[customerId] =
                        provider
                    providerByCustomerId[doc.id] =
                        provider

                    val packagePrice =
                        numberValue(doc, "packagePrice")

                    packagePriceByCustomerId[customerId] =
                        packagePrice
                    packagePriceByCustomerId[doc.id] =
                        packagePrice

                    packageIdByCustomerId[customerId] =
                        packageId
                    packageIdByCustomerId[doc.id] =
                        packageId
                }

                binding.tvTotalAccounts.text =
                    (active + disabled).toString()

                binding.tvActiveAccounts.text =
                    active.toString()

                binding.tvDisabledAccounts.text =
                    disabled.toString()

                renderUnpaidTodayCard(activeRelief, disabledRelief)

                renderSpeedBreakdown(speedCounts)
                renderNetworkActivationBreakdown()
            }
    }

    /**
     * NEW: renders the "Unpaid Today" card's big number as
     * "<activeRelief> / <disabledRelief>" in a single TextView, with the
     * left (currently active / on relief) count colored green and the
     * right (currently disabled / suspended) count colored red, so both
     * numbers are visible at a glance without opening
     * UnpaidPackageActivationActivity's Relief Active / Disabled Users
     * lists.
     */
    private fun renderUnpaidTodayCard(activeRelief: Int, disabledRelief: Int) {
        val activeText = activeRelief.toString()
        val separator = " / "
        val disabledText = disabledRelief.toString()

        val fullText = activeText + separator + disabledText
        val spannable = SpannableString(fullText)

        val activeStart = 0
        val activeEnd = activeText.length
        val separatorStart = activeEnd
        val separatorEnd = separatorStart + separator.length
        val disabledStart = separatorEnd
        val disabledEnd = disabledStart + disabledText.length

        // Left number (active relief, currently "on") — green.
        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor("#2E7D32")),
            activeStart,
            activeEnd,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(android.graphics.Typeface.BOLD),
            activeStart,
            activeEnd,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Separator — neutral navy, matches the app's other text.
        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor("#0D2E5C")),
            separatorStart,
            separatorEnd,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Right number (currently disabled / suspended) — red.
        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor("#C62828")),
            disabledStart,
            disabledEnd,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(android.graphics.Typeface.BOLD),
            disabledStart,
            disabledEnd,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.tvUnpaidToday.text = spannable
    }

    private fun normalizeProvider(provider: String): String {
        return when (
            provider.uppercase(Locale.getDefault()).trim()
        ) {
            "WATEEN" -> "WATEEN"
            "ZONG" -> "ZONG"
            "EBONE" -> "EBONE"
            else -> "EBONE"
        }
    }

    private fun renderNetworkActivationBreakdown() {
        val networkCounts = companies.associateWith {
            0 to 0.0
        }.toMutableMap()

        for (txn in latestVerifiedTodayTransactions) {
            val customerId =
                stringValue(txn, "customerId")

            if (customerId.isEmpty()) continue

            val provider =
                providerByCustomerId[customerId]
                    ?: normalizeProvider(
                        stringValue(txn, "ispProvider")
                            .ifEmpty { "EBONE" }
                    )

            val transactionAmount =
                numberValue(txn, "amount")

            // IMPORTANT:
            // The registered customer's packagePrice is the source of truth.
            // This handles Firestore Integer/Long/Double because numberValue()
            // reads any Number type.
            val packagePrice =
                packagePriceByCustomerId[customerId]
                    ?: transactionAmount

            val current =
                networkCounts[provider] ?: (0 to 0.0)

            networkCounts[provider] =
                (current.first + 1) to
                        (current.second + packagePrice)
        }

        renderNetworkBreakdown(networkCounts)
    }

    private fun normalizeSpeed(packageId: String): String {
        val match = Regex("""(\d+)\s*[Mm][Bb]""").find(packageId)

        return if (match != null) {
            "${match.groupValues[1]} Mbps"
        } else {
            packageId
        }
    }

    private fun renderSpeedBreakdown(rawSpeedCounts: Map<String, Int>) {
        val speedCounts = mutableMapOf<String, Int>()

        for ((pkg, count) in rawSpeedCounts) {
            val normalized = normalizeSpeed(pkg)
            speedCounts[normalized] =
                (speedCounts[normalized] ?: 0) + count
        }

        val entries = speedCounts.entries
            .sortedByDescending { it.value }
            .take(3)

        val colors = listOf("#378ADD", "#1D9E75", "#BA7517")
        val total = speedCounts.values.sum().coerceAtLeast(1)

        val segments = entries.mapIndexed { i, e ->
            DonutChartView.Segment(
                e.value.toFloat(),
                Color.parseColor(
                    colors.getOrElse(i) { "#9E9E9E" }
                )
            )
        }

        binding.donutChart.setData(
            segments,
            total.toString()
        )

        val labels = listOf(
            binding.tv6MbpsCount,
            binding.tv8MbpsCount,
            binding.tv10MbpsCount
        )

        labels.forEach { it.text = "0" }

        entries.forEachIndexed { i, e ->
            if (i < labels.size) {
                labels[i].text = "${e.key}: ${e.value}"
            }
        }
    }

    private fun renderNetworkBreakdown(
        networkCounts: Map<String, Pair<Int, Double>>
    ) {
        binding.networkBreakdownContainer.removeAllViews()

        companies.forEach { isp ->
            val (count, amount) =
                networkCounts[isp] ?: (0 to 0.0)

            val row = ItemNetworkRowBinding.inflate(
                LayoutInflater.from(this),
                binding.networkBreakdownContainer,
                false
            )

            row.tvNetworkName.text =
                isp.lowercase(Locale.getDefault())
                    .replaceFirstChar {
                        it.uppercase()
                    }

            row.tvNetworkPackages.text =
                "$count packages"

            row.tvNetworkEarnings.text =
                "Rs %,.0f".format(amount)

            binding.networkBreakdownContainer.addView(row.root)
        }
    }

    private fun startRecentTransactionsListener() {
        recentTxnListener = db.collection("transactions")
            .orderBy(
                "createdAt",
                com.google.firebase.firestore.Query.Direction.DESCENDING
            )
            .limit(5)
            .addSnapshotListener { snapshot, _ ->

                if (snapshot == null) return@addSnapshotListener

                binding.recentTransactionsContainer.removeAllViews()

                for (doc in snapshot.documents) {
                    val customerId =
                        doc.getString("customerId") ?: "—"
                    val source =
                        doc.getString("source") ?: ""
                    val status =
                        doc.getString("status") ?: "PENDING"
                    val createdAt =
                        doc.getLong("createdAt") ?: 0L

                    val timeText =
                        SimpleDateFormat(
                            "h:mm a",
                            Locale.getDefault()
                        ).format(Date(createdAt))

                    val row =
                        ItemTransactionRowBinding.inflate(
                            LayoutInflater.from(this),
                            binding.recentTransactionsContainer,
                            false
                        )

                    row.tvTxnCustomerId.text = customerId
                    row.tvTxnMeta.text = "$source · $timeText"

                    when (status) {
                        "VERIFIED" -> {
                            row.tvTxnStatus.text =
                                getString(R.string.status_verified)
                            row.tvTxnStatus.setBackgroundResource(
                                R.drawable.bg_chip_verified
                            )
                            row.tvTxnStatus.setTextColor(
                                ContextCompat.getColor(
                                    this,
                                    R.color.status_success_text
                                )
                            )
                        }

                        "PENDING" -> {
                            row.tvTxnStatus.text = "Waiting for SMS"
                            row.tvTxnStatus.setBackgroundResource(
                                R.drawable.bg_stat_card
                            )
                            row.tvTxnStatus.setTextColor(
                                Color.parseColor("#5F5E5A")
                            )
                            row.root.setOnClickListener {
                                showClearDialog(
                                    doc.id,
                                    customerId,
                                    source,
                                    timeText
                                )
                            }
                        }

                        else -> {
                            row.tvTxnStatus.text =
                                getString(R.string.status_mismatch)
                            row.tvTxnStatus.setBackgroundResource(
                                R.drawable.bg_chip_mismatch
                            )
                            row.tvTxnStatus.setTextColor(
                                ContextCompat.getColor(
                                    this,
                                    R.color.status_error_text
                                )
                            )
                            row.root.setOnClickListener {
                                showClearDialog(
                                    doc.id,
                                    customerId,
                                    source,
                                    timeText
                                )
                            }
                        }
                    }

                    binding.recentTransactionsContainer.addView(row.root)
                }
            }
    }

    private fun showClearDialog(
        docId: String,
        customerId: String,
        source: String,
        timeText: String
    ) {
        AlertDialog.Builder(this)
            .setTitle("Clear this entry?")
            .setMessage(
                "Customer: $customerId\n" +
                        "Method: $source · $timeText\n\n" +
                        "This will remove the failed entry so the customer can submit their payment again."
            )
            .setPositiveButton("Yes, Clear") { _, _ ->
                db.collection("transactions")
                    .document(docId)
                    .delete()
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
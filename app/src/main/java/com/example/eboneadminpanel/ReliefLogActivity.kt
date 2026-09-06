package com.example.eboneadminpanel

import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * NEW: standalone Relief Log screen. Reads ONLY from the separate
 * "reliefLogs" collection (written by ReliefLogRepository) — never
 * touches "customers" or any existing relief/suspend/enable logic.
 * Opened via the ⋮ menu button on Unpaid Package Activation.
 */
class ReliefLogActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var filterChipsContainer: LinearLayout
    private lateinit var tvRangeLabel: TextView
    private lateinit var tvStatActivated: TextView
    private lateinit var tvStatDeactivated: TextView
    private lateinit var tvStatPending: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var rv: RecyclerView

    private val displayFormat = SimpleDateFormat("dd-MMM-yyyy hh:mm a", Locale.getDefault())
    private val chipLabelFormat = SimpleDateFormat("dd MMM", Locale.getDefault())

    private var rangeStart: Long = 0L
    private var rangeEnd: Long = 0L
    private var selectedChip: TextView? = null

    // NEW: when non-null, the list is filtered to only that status
    // within the current date range. Set by tapping one of the three
    // summary stat cards; tapping the same one again clears it.
    private var activeStatusFilter: String? = null // "ACTIVATED" | "DEACTIVATED" | "PENDING" | null
    private var lastLoadedDocs: List<DocumentSnapshot> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relief_log)

        filterChipsContainer = findViewById(R.id.filterChipsContainer)
        tvRangeLabel = findViewById(R.id.tvLogRangeLabel)
        tvStatActivated = findViewById(R.id.tvStatActivated)
        tvStatDeactivated = findViewById(R.id.tvStatDeactivated)
        tvStatPending = findViewById(R.id.tvStatPending)

        // NEW: tapping a summary card filters the list to that status;
        // tapping the same one again clears the filter.
        (tvStatActivated.parent as View).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onStatCardTapped("ACTIVATED") }
        }
        (tvStatDeactivated.parent as View).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onStatCardTapped("DEACTIVATED") }
        }
        (tvStatPending.parent as View).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onStatCardTapped("PENDING") }
        }
        tvEmpty = findViewById(R.id.tvLogEmpty)
        rv = findViewById(R.id.rvReliefLog)
        rv.layoutManager = LinearLayoutManager(this)

        findViewById<ImageButton>(R.id.btnLogBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnLogCalendar).setOnClickListener { openCustomRangePicker() }

        buildQuickFilterChips()
        selectQuickRange(daysBack = 0, label = "Today")
    }

    private fun buildQuickFilterChips() {
        val options = listOf(
            "Today" to 0,
            "Yesterday" to 1,
            "2 Days" to 2,
            "3 Days" to 3,
            "7 Days" to 7
        )

        options.forEach { (label, daysBack) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(chipTextColor(false))
                setPadding(36, 18, 36, 18)
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@ReliefLogActivity, R.drawable.bg_log_filter_chip
                )
                isSelected = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 20 }

                setOnClickListener {
                    if (label == "Yesterday") {
                        selectQuickRange(daysBack = 1, label = label, singleDay = true, selectedView = this)
                    } else {
                        selectQuickRange(daysBack = daysBack, label = label, selectedView = this)
                    }
                }
            }
            filterChipsContainer.addView(chip)
        }

        // NEW: "This Month" — right after 7 Days, so checking a FULL
        // month (all of January, all of February, etc.) doesn't require
        // stitching together several 7-day windows.
        val monthChip = TextView(this).apply {
            text = "This Month"
            textSize = 13f
            setTextColor(chipTextColor(false))
            setPadding(36, 18, 36, 18)
            background = androidx.core.content.ContextCompat.getDrawable(
                this@ReliefLogActivity, R.drawable.bg_log_filter_chip
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = 20 }

            setOnClickListener { selectThisMonth(this) }
        }
        filterChipsContainer.addView(monthChip)

        // NEW: calendar icon chip, placed directly in this row (right
        // after This Month) — same action as the header's calendar
        // button, just impossible to miss since it sits right alongside
        // the other range options instead of tucked in the header.
        val calendarChip = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_my_calendar)
            background = androidx.core.content.ContextCompat.getDrawable(
                this@ReliefLogActivity, R.drawable.bg_log_filter_chip
            )
            setColorFilter(Color.parseColor("#4C5FD5"))
            contentDescription = "Pick any custom date range"
            val sizePx = (40 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            setPadding(10, 10, 10, 10)
            setOnClickListener { openCustomRangePicker() }
        }
        filterChipsContainer.addView(calendarChip)
    }

    private fun chipTextColor(selected: Boolean) =
        if (selected) Color.WHITE else Color.parseColor("#444444")

    /**
     * NEW: shows the FULL current calendar month (1st to today, or 1st
     * to the last day if a past month is later picked via the calendar
     * button) — this is what makes "check all of January/February/etc."
     * actually possible, instead of only ever seeing 7-day windows.
     */
    private fun selectThisMonth(selectedView: TextView) {
        val start = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }

        rangeStart = start.timeInMillis
        rangeEnd = end.timeInMillis
        tvRangeLabel.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(start.time)

        selectedChip?.isSelected = false
        selectedChip?.setTextColor(chipTextColor(false))
        selectedView.isSelected = true
        selectedView.setTextColor(chipTextColor(true))
        selectedChip = selectedView

        activeStatusFilter = null
        loadLogs()
    }

    /**
     * daysBack = 0 -> today only. daysBack = N (not "Yesterday") -> from
     * N days ago through today (inclusive). "Yesterday" is a single
     * exact day, handled via singleDay=true.
     */
    private fun selectQuickRange(
        daysBack: Int,
        label: String,
        singleDay: Boolean = false,
        selectedView: TextView? = null
    ) {
        val endCal = Calendar.getInstance()
        endCal.set(Calendar.HOUR_OF_DAY, 23)
        endCal.set(Calendar.MINUTE, 59)
        endCal.set(Calendar.SECOND, 59)
        endCal.set(Calendar.MILLISECOND, 999)

        val startCal = Calendar.getInstance()
        startCal.add(Calendar.DAY_OF_YEAR, -daysBack)
        startCal.set(Calendar.HOUR_OF_DAY, 0)
        startCal.set(Calendar.MINUTE, 0)
        startCal.set(Calendar.SECOND, 0)
        startCal.set(Calendar.MILLISECOND, 0)

        if (singleDay) {
            endCal.timeInMillis = startCal.timeInMillis
            endCal.set(Calendar.HOUR_OF_DAY, 23)
            endCal.set(Calendar.MINUTE, 59)
            endCal.set(Calendar.SECOND, 59)
            endCal.set(Calendar.MILLISECOND, 999)
        }

        rangeStart = startCal.timeInMillis
        rangeEnd = endCal.timeInMillis
        tvRangeLabel.text = label

        selectedChip?.isSelected = false
        selectedChip?.setTextColor(chipTextColor(false))
        selectedView?.isSelected = true
        selectedView?.setTextColor(chipTextColor(true))
        selectedChip = selectedView

        activeStatusFilter = null
        loadLogs()
    }

    /**
     * FIX: MaterialDatePicker (Google's Material Components range picker)
     * was crashing with a SecurityException deep inside Google Play
     * Services ("Failed to get service from broker" /
     * "Unknown calling package name 'com.google.android.gms'") on some
     * devices — a known GMS-broker compatibility issue, not a bug in
     * this app's own code (no app frames appear anywhere in that crash).
     * Replaced with the plain android.app.DatePickerDialog already used
     * elsewhere in this app (e.g. Unpaid Package Activation's own
     * calendar button) — zero Google Material Components dependency, so
     * this class of crash cannot happen here anymore. The admin picks a
     * start date, then an end date, one after another.
     */
    private fun openCustomRangePicker() {
        val now = Calendar.getInstance()

        val startPicker = DatePickerDialog(
            this,
            { _, startYear, startMonth, startDay ->
                val startCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, startYear)
                    set(Calendar.MONTH, startMonth)
                    set(Calendar.DAY_OF_MONTH, startDay)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                val endPicker = DatePickerDialog(
                    this,
                    { _, endYear, endMonth, endDay ->
                        val endCal = Calendar.getInstance().apply {
                            set(Calendar.YEAR, endYear)
                            set(Calendar.MONTH, endMonth)
                            set(Calendar.DAY_OF_MONTH, endDay)
                            set(Calendar.HOUR_OF_DAY, 23)
                            set(Calendar.MINUTE, 59)
                            set(Calendar.SECOND, 59)
                            set(Calendar.MILLISECOND, 999)
                        }

                        if (endCal.timeInMillis < startCal.timeInMillis) {
                            Toast.makeText(
                                this,
                                "End date must be on or after the start date.",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@DatePickerDialog
                        }

                        rangeStart = startCal.timeInMillis
                        rangeEnd = endCal.timeInMillis

                        selectedChip?.isSelected = false
                        selectedChip?.setTextColor(chipTextColor(false))
                        selectedChip = null

                        tvRangeLabel.text =
                            "${chipLabelFormat.format(startCal.time)} – ${chipLabelFormat.format(endCal.time)}"

                        activeStatusFilter = null
                        loadLogs()
                    },
                    startYear, startMonth, startDay
                )
                endPicker.setTitle("Select end date")
                endPicker.show()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        )
        startPicker.setTitle("Select start date")
        startPicker.show()
    }

    private fun loadLogs() {
        db.collection("reliefLogs")
            .whereGreaterThanOrEqualTo("activatedAt", rangeStart)
            .whereLessThanOrEqualTo("activatedAt", rangeEnd)
            .orderBy("activatedAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                lastLoadedDocs = snapshot.documents
                renderLogs()
            }
            .addOnFailureListener {
                lastLoadedDocs = emptyList()
                renderLogs()
            }
    }

    /**
     * FIX/NEW: the three summary cards (Activated / Deactivated / Still
     * Active) are now tappable filters, not just counts. Tapping one
     * shows only that subset of the CURRENT date range's list; tapping
     * the same card again clears the filter and shows everyone again.
     * Counts themselves are always computed from the FULL range
     * regardless of which filter is active, so the numbers stay
     * meaningful as a reference while browsing a filtered subset.
     */
    private fun renderLogs() {
        val docs = lastLoadedDocs

        var activatedCount = 0
        var deactivatedCount = 0
        var pendingCount = 0

        docs.forEach { doc ->
            activatedCount++
            if (doc.get("deactivatedAt") != null) deactivatedCount++ else pendingCount++
        }

        tvStatActivated.text = activatedCount.toString()
        tvStatDeactivated.text = deactivatedCount.toString()
        tvStatPending.text = pendingCount.toString()

        val filtered = when (activeStatusFilter) {
            "DEACTIVATED" -> docs.filter { it.get("deactivatedAt") != null }
            "PENDING" -> docs.filter { it.get("deactivatedAt") == null }
            else -> docs // null or "ACTIVATED" -> everyone in range
        }

        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE

        rv.adapter = LogAdapter(filtered)

        updateStatCardHighlight()
    }

    /** Visually highlights whichever stat card filter is currently active. */
    private fun updateStatCardHighlight() {
        val cards = listOf(
            "ACTIVATED" to (tvStatActivated.parent as View),
            "DEACTIVATED" to (tvStatDeactivated.parent as View),
            "PENDING" to (tvStatPending.parent as View)
        )
        cards.forEach { (key, cardView) ->
            cardView.alpha = if (activeStatusFilter == null || activeStatusFilter == key) 1f else 0.45f
        }
    }

    /** Toggles a stat-card filter: tap again to clear it. */
    private fun onStatCardTapped(key: String) {
        activeStatusFilter = if (activeStatusFilter == key) null else key
        renderLogs()
    }

    private inner class LogAdapter(
        private val items: List<DocumentSnapshot>
    ) : RecyclerView.Adapter<LogAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val strip: View = view.findViewById(R.id.viewLogStatusStrip)
            val customerId: TextView = view.findViewById(R.id.tvLogCustomerId)
            val company: TextView = view.findViewById(R.id.tvLogCompany)
            val activatedAt: TextView = view.findViewById(R.id.tvLogActivatedAt)
            val reliefDays: TextView = view.findViewById(R.id.tvLogReliefDays)
            val expiryAt: TextView = view.findViewById(R.id.tvLogExpiryAt)
            val deactivatedAt: TextView = view.findViewById(R.id.tvLogDeactivatedAt)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_relief_log, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val doc = items[position]

            val id = doc.getString("customerId") ?: "—"
            val company = doc.getString("company") ?: "EBONE"
            val isTest = doc.getBoolean("isTest") == true
            val reliefDays = (doc.get("reliefDays") as? Number)?.toInt() ?: 0
            val activatedAt = (doc.get("activatedAt") as? Number)?.toLong() ?: 0L
            val expectedExpiryAt = (doc.get("expectedExpiryAt") as? Number)?.toLong() ?: 0L
            val deactivatedAtRaw = doc.get("deactivatedAt")
            val deactivatedAt = (deactivatedAtRaw as? Number)?.toLong()

            holder.customerId.text = id
            holder.company.text = if (isTest) "$company · TEST" else company

            holder.activatedAt.text =
                "Activated: ${if (activatedAt > 0) displayFormat.format(activatedAt) else "—"}"

            holder.reliefDays.text = when {
                isTest -> "Promised Relief: TEST (manual)"
                reliefDays > 0 -> "Promised Relief: $reliefDays day${if (reliefDays == 1) "" else "s"}"
                else -> "Promised Relief: —"
            }

            holder.expiryAt.text =
                "Expected Expiry: ${if (expectedExpiryAt > 0) displayFormat.format(expectedExpiryAt) else "—"}"

            if (deactivatedAt != null && deactivatedAt > 0) {
                holder.deactivatedAt.text =
                    "Deactivated: ${displayFormat.format(deactivatedAt)}"
                holder.strip.setBackgroundColor(Color.parseColor("#C62828"))
            } else {
                holder.deactivatedAt.text = "Deactivated: Still active"
                holder.strip.setBackgroundColor(Color.parseColor("#43A047"))
            }
        }
    }
}
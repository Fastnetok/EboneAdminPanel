package com.example.eboneadminpanel

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class AllComplaintsViewMode { CUSTOMER, DATE, AREA }

data class GenericGroupItem(
    val groupTitle: String,
    val subStats: String,
    val totalCount: Int,
    val resolvedCount: Int,
    val pendingCount: Int,
    val complaints: List<Map<String, String>>,
    var isExpanded: Boolean = false
)

class AllComplaintsActivity : AppCompatActivity() {

    private lateinit var topHeaderBar: View
    private lateinit var btnBack: ImageButton
    private lateinit var btnDateRangePicker: Button
    private lateinit var btnViewCustomer: Button
    private lateinit var btnViewDate: Button
    private lateinit var btnViewArea: Button
    private lateinit var searchAreaEditText: AutoCompleteTextView
    private lateinit var tvStatTotal: TextView
    private lateinit var tvStatResolved: TextView
    private lateinit var tvStatPending: TextView
    private lateinit var tvStatAreas: TextView
    private lateinit var tvSelectedRangeLabel: TextView
    private lateinit var rvAreaComplaints: RecyclerView

    private val masterComplaintsList = mutableListOf<Map<String, String>>()
    private val masterCustomerGroups = mutableListOf<GenericGroupItem>()
    private val masterAreaGroups = mutableListOf<GenericGroupItem>()
    
    private val displayGroupList = mutableListOf<GenericGroupItem>()
    private val displayDateComplaints = mutableListOf<Map<String, String>>()

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var singleCardAdapter: SingleCardAdapter

    private var currentViewMode = AllComplaintsViewMode.CUSTOMER

    private var selectedStartTime: Long = 0L
    private var selectedEndTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_complaints)

        topHeaderBar = findViewById(R.id.topHeaderBar)
        btnBack = findViewById(R.id.btnBack)
        btnDateRangePicker = findViewById(R.id.btnDateRangePicker)
        btnViewCustomer = findViewById(R.id.btnViewCustomer)
        btnViewDate = findViewById(R.id.btnViewDate)
        btnViewArea = findViewById(R.id.btnViewArea)
        searchAreaEditText = findViewById(R.id.searchAreaEditText)
        tvStatTotal = findViewById(R.id.tvStatTotal)
        tvStatResolved = findViewById(R.id.tvStatResolved)
        tvStatPending = findViewById(R.id.tvStatPending)
        tvStatAreas = findViewById(R.id.tvStatAreas)
        tvSelectedRangeLabel = findViewById(R.id.tvSelectedRangeLabel)
        rvAreaComplaints = findViewById(R.id.rvAreaComplaints)

        // Dynamic WindowInsets for Status Bar to prevent overlap on Google Pixel 8 Pro or any device
        applyStatusBarInsets(topHeaderBar)

        rvAreaComplaints.layoutManager = LinearLayoutManager(this)
        groupAdapter = GroupAdapter(displayGroupList)
        singleCardAdapter = SingleCardAdapter(displayDateComplaints)
        
        rvAreaComplaints.adapter = groupAdapter

        btnBack.setOnClickListener {
            finish()
        }

        btnDateRangePicker.setOnClickListener {
            showDateRangePicker()
        }

        btnViewCustomer.setOnClickListener {
            currentViewMode = AllComplaintsViewMode.CUSTOMER
            updateViewModeButtons()
            applySearchFilter()
        }

        btnViewDate.setOnClickListener {
            currentViewMode = AllComplaintsViewMode.DATE
            updateViewModeButtons()
            applySearchFilter()
        }

        btnViewArea.setOnClickListener {
            currentViewMode = AllComplaintsViewMode.AREA
            updateViewModeButtons()
            applySearchFilter()
        }

        // Default to current month
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        selectedStartTime = cal.timeInMillis

        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        selectedEndTime = cal.timeInMillis

        val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        tvSelectedRangeLabel.text = "📅 Showing complaints for ${sdf.format(Date(selectedStartTime))}"

        updateViewModeButtons()
        loadComplaintsData()

        searchAreaEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applySearchFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun applyStatusBarInsets(targetView: View) {
        val root = findViewById<View>(R.id.allComplaintsRoot) ?: targetView
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val topPadding = statusBarInset.top + (8 * resources.displayMetrics.density).toInt()
            targetView.setPadding(
                targetView.paddingLeft,
                topPadding,
                targetView.paddingRight,
                targetView.paddingBottom
            )
            insets
        }
    }

    private fun updateViewModeButtons() {
        val normalBg = Color.parseColor("#FFFFFF") // Neutral White background
        val normalBorderColor = Color.parseColor("#CFD8DC")
        val selectedBorderColor = Color.parseColor("#D32F2F") // Red border when selected
        val textColor = Color.parseColor("#212121") // Sharp Black / Dark text

        val density = resources.displayMetrics.density
        val cornerRadiusPx = 4 * density

        val custDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(normalBg)
            val strokeWidth = if (currentViewMode == AllComplaintsViewMode.CUSTOMER) (2.5 * density).toInt() else (1 * density).toInt()
            val strokeColor = if (currentViewMode == AllComplaintsViewMode.CUSTOMER) selectedBorderColor else normalBorderColor
            setStroke(strokeWidth, strokeColor)
        }

        val dateDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(normalBg)
            val strokeWidth = if (currentViewMode == AllComplaintsViewMode.DATE) (2.5 * density).toInt() else (1 * density).toInt()
            val strokeColor = if (currentViewMode == AllComplaintsViewMode.DATE) selectedBorderColor else normalBorderColor
            setStroke(strokeWidth, strokeColor)
        }

        val areaDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(normalBg)
            val strokeWidth = if (currentViewMode == AllComplaintsViewMode.AREA) (2.5 * density).toInt() else (1 * density).toInt()
            val strokeColor = if (currentViewMode == AllComplaintsViewMode.AREA) selectedBorderColor else normalBorderColor
            setStroke(strokeWidth, strokeColor)
        }

        btnViewCustomer.background = custDrawable
        btnViewCustomer.setTextColor(textColor)

        btnViewDate.background = dateDrawable
        btnViewDate.setTextColor(textColor)

        btnViewArea.background = areaDrawable
        btnViewArea.setTextColor(textColor)
    }

    private fun showDateRangePicker() {
        try {
            val builder = MaterialDatePicker.Builder.dateRangePicker()
            builder.setTitleText("Select Start Date & End Date")
            val picker = builder.build()
            picker.addOnPositiveButtonClickListener { range ->
                val startMs = range.first
                val endMs = range.second
                if (startMs != null && endMs != null) {
                    val calStart = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                    calStart.timeInMillis = startMs
                    val localStart = Calendar.getInstance()
                    localStart.set(
                        calStart.get(Calendar.YEAR),
                        calStart.get(Calendar.MONTH),
                        calStart.get(Calendar.DAY_OF_MONTH),
                        0, 0, 0
                    )
                    localStart.set(Calendar.MILLISECOND, 0)
                    selectedStartTime = localStart.timeInMillis

                    val calEnd = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                    calEnd.timeInMillis = endMs
                    val localEnd = Calendar.getInstance()
                    localEnd.set(
                        calEnd.get(Calendar.YEAR),
                        calEnd.get(Calendar.MONTH),
                        calEnd.get(Calendar.DAY_OF_MONTH),
                        23, 59, 59
                    )
                    localEnd.set(Calendar.MILLISECOND, 999)
                    selectedEndTime = localEnd.timeInMillis

                    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    tvSelectedRangeLabel.text = "📅 Showing complaints for ${sdf.format(Date(selectedStartTime))} → ${sdf.format(Date(selectedEndTime))}"

                    loadComplaintsData()
                }
            }
            picker.show(supportFragmentManager, "all_complaints_date_picker")
        } catch (_: Exception) {
            showFallbackDatePicker()
        }
    }

    private fun showFallbackDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, fromYear, fromMonth, fromDay ->
                val localStart = Calendar.getInstance()
                localStart.set(fromYear, fromMonth, fromDay, 0, 0, 0)
                localStart.set(Calendar.MILLISECOND, 0)
                selectedStartTime = localStart.timeInMillis

                DatePickerDialog(
                    this,
                    { _, toYear, toMonth, toDay ->
                        val localEnd = Calendar.getInstance()
                        localEnd.set(toYear, toMonth, toDay, 23, 59, 59)
                        localEnd.set(Calendar.MILLISECOND, 999)
                        selectedEndTime = localEnd.timeInMillis

                        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        tvSelectedRangeLabel.text = "📅 Showing complaints for ${sdf.format(Date(selectedStartTime))} → ${sdf.format(Date(selectedEndTime))}"

                        loadComplaintsData()
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun extractAreaName(address: String): String {
        if (address.isBlank()) return "Unknown Area"
        var area = address.split("/").firstOrNull()?.split(",")?.firstOrNull()?.trim() ?: address.trim()
        area = area
            .replace(Regex("(?i)\\bphase\\s*\\d+\\b"), "")
            .replace(Regex("(?i)\\bst(reet)?\\.?\\s*(no\\.?|number)?\\s*\\d+\\b"), "")
            .replace(Regex("(?i)\\brd\\.?\\s*\\d+\\b"), "")
            .replace(Regex("(?i)\\bno\\.?\\s*\\d+\\b"), "")
            .replace(Regex("\\d+"), "")
            .replace(Regex("[,.\\-]+$"), "")
            .trim()
        return if (area.isEmpty()) "Unknown Area" else area
    }

    private fun getCustomerGroupingKey(userId: String, phone: String, address: String): String {
        val normId = userId.trim().lowercase(Locale.getDefault())
        val cleanPhone = phone.replace(Regex("[^0-9]"), "")
        if (normId.isNotEmpty() && !normId.equals("new connection", true)) {
            if (cleanPhone.length >= 7) return "$normId|$cleanPhone"
            return normId
        }
        if (cleanPhone.length >= 7) return "phone|$cleanPhone"
        if (address.isNotEmpty()) return "addr|${address.trim().lowercase(Locale.getDefault())}"
        return "unknown"
    }

    private fun getCustomerDisplayName(items: List<Map<String, String>>): String {
        val first = items.firstOrNull() ?: return "Customer"
        val rawId = first["title"] ?: ""
        if (rawId.isNotEmpty() && !rawId.equals("new connection", true) && !rawId.startsWith("Complaint #")) {
            return rawId.trim()
        }
        val phone = first["phoneNumber"] ?: ""
        if (phone.isNotEmpty()) return "Customer ($phone)"
        val addr = first["address"] ?: ""
        if (addr.isNotEmpty()) return "Customer (${extractAreaName(addr)})"
        return "Customer"
    }

    private fun loadComplaintsData() {
        masterComplaintsList.clear()

        FirebaseDatabase.getInstance()
            .getReference("resolvedComplaints")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(resolvedSnapshot: DataSnapshot) {

                    FirebaseDatabase.getInstance()
                        .getReference("complaints")
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(complaintSnapshot: DataSnapshot) {

                                val complaintIds = mutableSetOf<String>()

                                for (cs in complaintSnapshot.children) {
                                    val isNewConn = cs.child("isNewConnection").getValue(Boolean::class.java) ?: false
                                    if (isNewConn) continue

                                    val createdTime = cs.child("createdTime").getValue(Long::class.java) ?: 0L
                                    if (createdTime !in selectedStartTime..selectedEndTime) continue

                                    val cId = cs.child("complaintId").getValue(String::class.java) ?: cs.key ?: ""
                                    if (cId.isNotEmpty()) complaintIds.add(cId)

                                    val userId = cs.child("userId").getValue(String::class.java) ?: ""
                                    val address = cs.child("address").getValue(String::class.java) ?: ""
                                    val phone = cs.child("phoneNumber").getValue(String::class.java) ?: ""
                                    val status = cs.child("status").getValue(String::class.java) ?: "Pending"
                                    val assignedTo = cs.child("assignedTo").getValue(String::class.java) ?: "Unassigned"
                                    val details = cs.child("details").getValue(String::class.java) ?: ""

                                    masterComplaintsList.add(
                                        mapOf(
                                            "complaintId" to cId,
                                            "title" to if (userId.isNotEmpty()) userId else "Complaint #$cId",
                                            "address" to address,
                                            "phoneNumber" to phone,
                                            "status" to status,
                                            "assignedTo" to assignedTo,
                                            "createdTime" to createdTime.toString(),
                                            "details" to details
                                        )
                                    )
                                }

                                for (cs in resolvedSnapshot.children) {
                                    val isNewConn = cs.child("isNewConnection").getValue(Boolean::class.java) ?: false
                                    if (isNewConn) continue

                                    val createdTime = cs.child("createdTime").getValue(Long::class.java) ?: 0L
                                    if (createdTime !in selectedStartTime..selectedEndTime) continue

                                    val cId = cs.child("complaintId").getValue(String::class.java) ?: cs.key ?: ""
                                    if (cId.isNotEmpty() && complaintIds.contains(cId)) continue

                                    val userId = cs.child("userId").getValue(String::class.java) ?: ""
                                    val address = cs.child("address").getValue(String::class.java) ?: ""
                                    val phone = cs.child("phoneNumber").getValue(String::class.java) ?: ""
                                    val assignedTo = cs.child("assignedTo").getValue(String::class.java) ?: "Unassigned"
                                    val details = cs.child("details").getValue(String::class.java) ?: ""

                                    masterComplaintsList.add(
                                        mapOf(
                                            "complaintId" to cId,
                                            "title" to if (userId.isNotEmpty()) userId else "Complaint #$cId",
                                            "address" to address,
                                            "phoneNumber" to phone,
                                            "status" to "Resolved",
                                            "assignedTo" to assignedTo,
                                            "createdTime" to createdTime.toString(),
                                            "details" to details
                                        )
                                    )
                                }

                                processGroupings()
                                updateSearchSuggestions()
                                applySearchFilter()
                            }

                            override fun onCancelled(error: DatabaseError) {
                                Toast.makeText(this@AllComplaintsActivity, "Failed to load complaints: ${error.message}", Toast.LENGTH_SHORT).show()
                            }
                        })
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@AllComplaintsActivity, "Failed to load resolved complaints: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun processGroupings() {
        // 1. Process Customer Groupings
        val customerMap = masterComplaintsList.groupBy {
            getCustomerGroupingKey(it["title"] ?: "", it["phoneNumber"] ?: "", it["address"] ?: "")
        }
        masterCustomerGroups.clear()

        for ((_, itemsList) in customerMap) {
            val sortedItems = itemsList.sortedBy { it["createdTime"]?.toLongOrNull() ?: 0L }
            val resolved = itemsList.count { (it["status"] ?: "").equals("Resolved", true) }
            val pending = itemsList.size - resolved
            val custName = getCustomerDisplayName(itemsList)
            val subStats = itemsList.firstOrNull()?.get("address") ?: ""

            masterCustomerGroups.add(
                GenericGroupItem(
                    groupTitle = custName,
                    subStats = subStats,
                    totalCount = itemsList.size,
                    resolvedCount = resolved,
                    pendingCount = pending,
                    complaints = sortedItems
                )
            )
        }
        // Sort Customer Groups: Highest Complaints First
        masterCustomerGroups.sortByDescending { it.totalCount }

        // 2. Process Area Groupings
        val areaMap = masterComplaintsList.groupBy { extractAreaName(it["address"] ?: "") }
        masterAreaGroups.clear()

        for ((areaName, itemsList) in areaMap) {
            val sortedItems = itemsList.sortedBy { it["createdTime"]?.toLongOrNull() ?: 0L }
            val resolved = itemsList.count { (it["status"] ?: "").equals("Resolved", true) }
            val pending = itemsList.size - resolved

            masterAreaGroups.add(
                GenericGroupItem(
                    groupTitle = areaName,
                    subStats = "Resolved: $resolved | Pending: $pending",
                    totalCount = itemsList.size,
                    resolvedCount = resolved,
                    pendingCount = pending,
                    complaints = sortedItems
                )
            )
        }
        // Sort Area Groups: Highest Complaints First
        masterAreaGroups.sortByDescending { it.totalCount }

        // Global Stats
        val grandTotal = masterComplaintsList.size
        val grandResolved = masterComplaintsList.count { (it["status"] ?: "").equals("Resolved", true) }
        val grandPending = grandTotal - grandResolved

        tvStatTotal.text = "Total: $grandTotal"
        tvStatResolved.text = "Resolved: $grandResolved"
        tvStatPending.text = "Pending: $grandPending"
    }

    private fun updateSearchSuggestions() {
        val suggestions = mutableSetOf<String>()
        masterComplaintsList.forEach { item ->
            item["title"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
            item["address"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
            item["phoneNumber"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
        }
        masterAreaGroups.forEach { suggestions.add(it.groupTitle) }

        val suggestionAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions.toList())
        searchAreaEditText.setAdapter(suggestionAdapter)
    }

    private fun applySearchFilter() {
        val query = searchAreaEditText.text.toString().trim()

        when (currentViewMode) {
            AllComplaintsViewMode.CUSTOMER -> {
                rvAreaComplaints.adapter = groupAdapter
                displayGroupList.clear()

                for (group in masterCustomerGroups) {
                    val matchesTitle = query.isNotEmpty() && group.groupTitle.contains(query, true)

                    val filteredComplaints = if (query.isEmpty() || matchesTitle) {
                        group.complaints
                    } else {
                        group.complaints.filter { item ->
                            (item["title"] ?: "").contains(query, true) ||
                            (item["address"] ?: "").contains(query, true) ||
                            (item["phoneNumber"] ?: "").contains(query, true) ||
                            (item["assignedTo"] ?: "").contains(query, true)
                        }
                    }

                    if (filteredComplaints.isNotEmpty()) {
                        val resolved = filteredComplaints.count { (it["status"] ?: "").equals("Resolved", true) }
                        val pending = filteredComplaints.size - resolved
                        displayGroupList.add(
                            GenericGroupItem(
                                groupTitle = group.groupTitle,
                                subStats = filteredComplaints.firstOrNull()?.get("address") ?: "",
                                totalCount = filteredComplaints.size,
                                resolvedCount = resolved,
                                pendingCount = pending,
                                complaints = filteredComplaints,
                                isExpanded = query.isNotEmpty()
                            )
                        )
                    }
                }

                tvStatAreas.text = "Customers: ${displayGroupList.size}"
                groupAdapter.notifyDataSetChanged()
            }

            AllComplaintsViewMode.DATE -> {
                rvAreaComplaints.adapter = singleCardAdapter
                displayDateComplaints.clear()

                val filtered = if (query.isEmpty()) {
                    masterComplaintsList
                } else {
                    masterComplaintsList.filter { item ->
                        (item["title"] ?: "").contains(query, true) ||
                        (item["address"] ?: "").contains(query, true) ||
                        (item["phoneNumber"] ?: "").contains(query, true) ||
                        (item["assignedTo"] ?: "").contains(query, true)
                    }
                }

                // Date-wise: Oldest Date -> Newest Date
                displayDateComplaints.addAll(filtered.sortedBy { it["createdTime"]?.toLongOrNull() ?: 0L })
                tvStatAreas.text = "Cards: ${displayDateComplaints.size}"
                singleCardAdapter.notifyDataSetChanged()
            }

            AllComplaintsViewMode.AREA -> {
                rvAreaComplaints.adapter = groupAdapter
                displayGroupList.clear()

                for (group in masterAreaGroups) {
                    val matchesArea = query.isNotEmpty() && group.groupTitle.contains(query, true)

                    val filteredComplaints = if (query.isEmpty() || matchesArea) {
                        group.complaints
                    } else {
                        group.complaints.filter { item ->
                            (item["title"] ?: "").contains(query, true) ||
                            (item["address"] ?: "").contains(query, true) ||
                            (item["phoneNumber"] ?: "").contains(query, true) ||
                            (item["assignedTo"] ?: "").contains(query, true)
                        }
                    }

                    if (filteredComplaints.isNotEmpty()) {
                        val resolved = filteredComplaints.count { (it["status"] ?: "").equals("Resolved", true) }
                        val pending = filteredComplaints.size - resolved
                        displayGroupList.add(
                            GenericGroupItem(
                                groupTitle = group.groupTitle,
                                subStats = "Resolved: $resolved | Pending: $pending",
                                totalCount = filteredComplaints.size,
                                resolvedCount = resolved,
                                pendingCount = pending,
                                complaints = filteredComplaints,
                                isExpanded = query.isNotEmpty()
                            )
                        )
                    }
                }

                tvStatAreas.text = "Areas: ${displayGroupList.size}"
                groupAdapter.notifyDataSetChanged()
            }
        }
    }

    // ---------- Group Adapter (CUSTOMER / AREA Views) ----------

    private inner class GroupAdapter(
        private val list: List<GenericGroupItem>
    ) : RecyclerView.Adapter<GroupAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvRankBadge: TextView = view.findViewById(R.id.tvCustomerRankBadge)
            val tvCustomerName: TextView = view.findViewById(R.id.tvCustomerName)
            val tvCustomerSubStats: TextView = view.findViewById(R.id.tvCustomerSubStats)
            val tvCustomerCountBadge: TextView = view.findViewById(R.id.tvCustomerCountBadge)
            val tvCustomerNcBadge: TextView? = view.findViewById(R.id.tvCustomerNcBadge)
            val tvCustomerExpandIndicator: TextView = view.findViewById(R.id.tvCustomerExpandIndicator)
            val customerHeaderLayout: View = view.findViewById(R.id.customerHeaderLayout)
            val llCustomerComplaintList: LinearLayout = view.findViewById(R.id.llCustomerComplaintList)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_decent_customer_group, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = list[position]

            holder.tvRankBadge.text = (position + 1).toString()
            holder.tvCustomerName.text = group.groupTitle
            holder.tvCustomerSubStats.text = group.subStats

            val repeatCount = group.complaints.count { it["isNcCard"] != "true" }
            val ncCount = group.complaints.count { it["isNcCard"] == "true" }

            val formatCircle: (Int) -> String = { num ->
                when (num) {
                    1 -> "①"
                    2 -> "②"
                    3 -> "③"
                    4 -> "④"
                    5 -> "⑤"
                    6 -> "⑥"
                    7 -> "⑦"
                    8 -> "⑧"
                    9 -> "⑨"
                    10 -> "⑩"
                    else -> "($num)"
                }
            }

            if (currentViewMode == AllComplaintsViewMode.CUSTOMER) {
                if (repeatCount > 0) {
                    holder.tvCustomerCountBadge.text = "Repeat ${formatCircle(repeatCount)}"
                    holder.tvCustomerCountBadge.visibility = View.VISIBLE
                } else {
                    holder.tvCustomerCountBadge.visibility = View.GONE
                }

                if (ncCount > 0 && holder.tvCustomerNcBadge != null) {
                    holder.tvCustomerNcBadge.text = "NC ${formatCircle(ncCount)}"
                    holder.tvCustomerNcBadge.visibility = View.VISIBLE
                } else {
                    holder.tvCustomerNcBadge?.visibility = View.GONE
                }
            } else {
                holder.tvCustomerCountBadge.text = "${group.totalCount} Complaints"
                holder.tvCustomerCountBadge.visibility = View.VISIBLE
                holder.tvCustomerNcBadge?.visibility = View.GONE
            }

            holder.tvCustomerExpandIndicator.text = if (group.isExpanded) "▲" else "▼"
            holder.llCustomerComplaintList.visibility = if (group.isExpanded) View.VISIBLE else View.GONE

            holder.customerHeaderLayout.setOnClickListener {
                group.isExpanded = !group.isExpanded
                notifyItemChanged(position)
            }

            holder.llCustomerComplaintList.removeAllViews()
            if (group.isExpanded) {
                val inflater = LayoutInflater.from(holder.itemView.context)
                val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())

                for (item in group.complaints) {
                    val cardView = inflater.inflate(R.layout.item_decent_complaint_card, holder.llCustomerComplaintList, false)

                    val tvTitle = cardView.findViewById<TextView>(R.id.tvComplaintTitle)
                    val tvStatus = cardView.findViewById<TextView>(R.id.tvComplaintStatus)
                    val tvAddress = cardView.findViewById<TextView>(R.id.tvComplaintAddress)
                    val tvPhone = cardView.findViewById<TextView>(R.id.tvComplaintPhone)
                    val tvDate = cardView.findViewById<TextView>(R.id.tvComplaintDate)
                    val tvEmployee = cardView.findViewById<TextView>(R.id.tvComplaintEmployee)

                    tvTitle.text = item["title"] ?: "Complaint"

                    val status = item["status"] ?: "Pending"
                    tvStatus.text = status
                    val statusColor = when (status) {
                        "Resolved" -> Color.parseColor("#2E7D32")
                        "Progress" -> Color.parseColor("#1565C0")
                        else -> Color.parseColor("#E65100")
                    }
                    tvStatus.setTextColor(statusColor)

                    tvAddress.text = "📍 Address: ${item["address"]}"

                    val phone = item["phoneNumber"] ?: ""
                    tvPhone.text = if (phone.isNotEmpty()) "📞 Phone: $phone (Tap to Call)" else ""

                    val createdTime = item["createdTime"]?.toLongOrNull() ?: 0L
                    tvDate.text = if (createdTime > 0) "📅 " + sdf.format(Date(createdTime)) else "📅 N/A"

                    val empName = item["assignedTo"] ?: "Unassigned"
                    tvEmployee.text = "🧑‍🔧 Employee: $empName"

                    // Clickable Phone -> Open Phone Dialer
                    tvPhone.setOnClickListener {
                        if (phone.isNotEmpty()) {
                            try {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                holder.itemView.context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(holder.itemView.context, "Unable to open dialer", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    holder.llCustomerComplaintList.addView(cardView)
                }
            }
        }

        override fun getItemCount() = list.size
    }

    // ---------- Single Card Adapter (DATE View) ----------

    private inner class SingleCardAdapter(
        private val list: List<Map<String, String>>
    ) : RecyclerView.Adapter<SingleCardAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tvComplaintTitle)
            val tvStatus: TextView = view.findViewById(R.id.tvComplaintStatus)
            val tvAddress: TextView = view.findViewById(R.id.tvComplaintAddress)
            val tvPhone: TextView = view.findViewById(R.id.tvComplaintPhone)
            val tvDate: TextView = view.findViewById(R.id.tvComplaintDate)
            val tvEmployee: TextView = view.findViewById(R.id.tvComplaintEmployee)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_decent_complaint_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            val sdf = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())

            holder.tvTitle.text = item["title"] ?: "Complaint"

            val status = item["status"] ?: "Pending"
            holder.tvStatus.text = status
            val statusColor = when (status) {
                "Resolved" -> Color.parseColor("#2E7D32")
                "Progress" -> Color.parseColor("#1565C0")
                else -> Color.parseColor("#E65100")
            }
            holder.tvStatus.setTextColor(statusColor)

            holder.tvAddress.text = "📍 Address: ${item["address"]}"

            val phone = item["phoneNumber"] ?: ""
            holder.tvPhone.text = if (phone.isNotEmpty()) "📞 Phone: $phone (Tap to Call)" else ""

            val createdTime = item["createdTime"]?.toLongOrNull() ?: 0L
            holder.tvDate.text = if (createdTime > 0) "📅 " + sdf.format(Date(createdTime)) else "📅 N/A"

            val empName = item["assignedTo"] ?: "Unassigned"
            holder.tvEmployee.text = "🧑‍🔧 Employee: $empName"

            holder.tvPhone.setOnClickListener {
                if (phone.isNotEmpty()) {
                    try {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                        holder.itemView.context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(holder.itemView.context, "Unable to open dialer", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        override fun getItemCount() = list.size
    }
}

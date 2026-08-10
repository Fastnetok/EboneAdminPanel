package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class ReportsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchEmployeeEditText: EditText
    private lateinit var sortToggleButton: Button
    private lateinit var todayButton: Button
    private lateinit var filterButton: Button
    private val reportList = mutableListOf<ReportItem>()
    private val allReports = mutableListOf<ReportItem>()
    private val allAreaCards = mutableListOf<AreaReportItem>()
    private var isAreaMode = false
    private var isAlphabeticalSort = false
    private lateinit var adapter: ReportAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reports)

        recyclerView = findViewById(R.id.reportsRecyclerView)
        searchEmployeeEditText = findViewById(R.id.searchEmployeeEditText)
        sortToggleButton = findViewById(R.id.sortToggleButton)
        sortToggleButton.setOnClickListener {
            isAlphabeticalSort = !isAlphabeticalSort
            sortToggleButton.text = if (isAlphabeticalSort) "🔤 A-Z" else "🔢 Total"
            renderAreaCards(searchEmployeeEditText.text.toString().trim())
        }
        todayButton = findViewById(R.id.todayButton)
        filterButton = findViewById(R.id.filterButton)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ReportAdapter(reportList) {
            // Header "REPEAT COMPLAINTS" box clicked -> show full repeat list across ALL employees
            val intent = Intent(this, EmployeeReportDetailsActivity::class.java)
            intent.putExtra("employeeName", ALL_EMPLOYEES_MARKER)
            intent.putExtra("showRepeat", true)
            startActivity(intent)
        }
        recyclerView.adapter = adapter
        // NOTE: no nestedScrollingEnabled=false and no outer ScrollView anymore —
        // recyclerView owns its own scrolling now (see activity_reports.xml).

        setActiveButton(todayButton)
        loadTodayDashboard()

        todayButton.setOnClickListener {
            setActiveButton(todayButton)
            filterButton.text = "Filter ▾"
            loadTodayDashboard()
        }

        filterButton.setOnClickListener {
            showFilterPopup(it)
        }

        searchEmployeeEditText.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val searchText = s.toString().trim()

                    if (isAreaMode) {
                        renderAreaCards(searchText)
                    } else {
                        // Normal mode: filter employee cards by employee name
                        reportList.clear()
                        if (searchText.isEmpty()) {
                            reportList.addAll(allReports)
                        } else {
                            for (report in allReports) {
                                if (report.employeeName.contains(searchText, true)) {
                                    reportList.add(report)
                                }
                            }
                        }
                        adapter.notifyDataSetChanged()
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
    }

    private fun showFilterPopup(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Week")
        popup.menu.add(0, 2, 1, "Month")
        popup.menu.add(0, 3, 2, "Custom Date")
        popup.menu.add(0, 4, 3, "Area Reports")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    setActiveButton(filterButton)
                    filterButton.text = "Week ▾"
                    val fromTime = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
                    // showTopEmployees = false -> WEEK shows ONLY employee cards
                    loadFilteredReport("WEEK", fromTime, showTopEmployees = false)
                    true
                }
                2 -> {
                    setActiveButton(filterButton)
                    filterButton.text = "Month ▾"
                    val fromTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                    // showTopEmployees = true -> MONTH shows Top Employees + Repeat box too
                    loadFilteredReport("MONTH", fromTime, showTopEmployees = true)
                    true
                }
                3 -> {
                    setActiveButton(filterButton)
                    filterButton.text = "Custom ▾"
                    showCustomDatePicker()
                    true
                }
                4 -> {
                    setActiveButton(filterButton)
                    filterButton.text = "Area ▾"
                    loadAreaReport()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showCustomDatePicker() {
        val cal = java.util.Calendar.getInstance()
        android.app.DatePickerDialog(
            this,
            { _, fromYear, fromMonth, fromDay ->
                val fromDate = String.format(
                    "%02d/%02d/%04d", fromDay, fromMonth + 1, fromYear
                )
                android.app.DatePickerDialog(
                    this,
                    { _, toYear, toMonth, toDay ->
                        val toDate = String.format(
                            "%02d/%02d/%04d", toDay, toMonth + 1, toYear
                        )
                        val sdf = java.text.SimpleDateFormat(
                            "dd/MM/yyyy", java.util.Locale.getDefault()
                        )
                        val fromMs = sdf.parse(fromDate)?.time ?: 0L
                        val toMs = (sdf.parse(toDate)?.time ?: 0L) + (24 * 60 * 60 * 1000)
                        if (fromMs > toMs) {
                            Toast.makeText(
                                this,
                                "From date pehle honi chahiye",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            loadCustomReport(fromDate, toDate, fromMs, toMs)
                        }
                    },
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH),
                    cal.get(java.util.Calendar.DAY_OF_MONTH)
                ).show()
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        ).show()
    }

    // Address free-text hai (fixed area field nahi), is liye "/" se pehle wala
    // hissa hi area ka andaza lagane ke liye use karte hain. Exact nahi hoga,
    // lekin hotspot pattern dikhane ke liye kaafi hai.
    private fun extractArea(address: String): String {
        if (address.isBlank()) return "Unknown Area"

        // "/" se pehle wala hissa lein (address ka pehla, mukhtasir hissa)
        var area = address.split("/").firstOrNull()?.trim() ?: address.trim()

        // Street/Phase/No/number wale hisse hata dein — taake "Bajwa Colony St 1"
        // aur "Bajwa Colony St 4" dono sirf "Bajwa Colony" ban kar ek sath count hon
        area = area
            .replace(Regex("(?i)\\bphase\\s*\\d+\\b"), "")
            .replace(Regex("(?i)\\bst(reet)?\\.?\\s*(no\\.?|number)?\\s*\\d+\\b"), "")
            .replace(Regex("(?i)\\brd\\.?\\s*\\d+\\b"), "")
            .replace(Regex("(?i)\\bno\\.?\\s*\\d+\\b"), "")
            .replace(Regex("\\d+"), "")           // koi bhi baaki bacha number
            .replace(Regex("[,.\\-]+$"), "")       // aakhri comma/dot/dash
            .trim()

        return if (area.isEmpty()) "Unknown Area" else area
    }

    // AREA REPORTS — kis area se sabse zyada complaints aa rahi hain (all-time)
    private fun loadAreaReport() {
        reportList.clear()
        allReports.clear()
        adapter.updateAreaList(emptyList())
        isAreaMode = true
        isAlphabeticalSort = false
        sortToggleButton.text = "🔢 Total"
        sortToggleButton.visibility = android.view.View.VISIBLE
        adapter.updateHeader(
            ReportHeaderData(
                summaryText = "📍 AREA-WISE COMPLAINTS\n\nLoading...",
                summaryVisible = true,
                topEmployeesVisible = false,
                repeatVisible = false,
                areaReportVisible = false
            )
        )
        adapter.notifyDataSetChanged()

        FirebaseDatabase.getInstance()
            .getReference("resolvedComplaints")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(resolvedSnapshot: DataSnapshot) {

                    FirebaseDatabase.getInstance()
                        .getReference("complaints")
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {

                                val areaTotal = mutableMapOf<String, Int>()
                                val areaResolved = mutableMapOf<String, Int>()
                                val areaDisplayName = mutableMapOf<String, String>()
                                val complaintsIds = mutableSetOf<String>()

                                // complaints node
                                for (cs in snapshot.children) {
                                    val cId = cs.child("complaintId")
                                        .getValue(String::class.java) ?: cs.key ?: ""
                                    if (cId.isNotEmpty()) complaintsIds.add(cId)

                                    val address = cs.child("address")
                                        .getValue(String::class.java) ?: ""
                                    val rawArea = extractArea(address)
                                    val area = rawArea.lowercase().trim() // case-insensitive grouping key
                                    if (!areaDisplayName.containsKey(area)) {
                                        areaDisplayName[area] = rawArea
                                    }
                                    val status = cs.child("status")
                                        .getValue(String::class.java) ?: ""

                                    areaTotal[area] = (areaTotal[area] ?: 0) + 1
                                    if (status.equals("Resolved", true)) {
                                        areaResolved[area] = (areaResolved[area] ?: 0) + 1
                                    }
                                }

                                // resolvedComplaints node (skip duplicates already counted above)
                                for (cs in resolvedSnapshot.children) {
                                    val complaintId = cs.child("complaintId")
                                        .getValue(String::class.java) ?: cs.key ?: ""
                                    if (complaintsIds.contains(complaintId)) continue

                                    val address = cs.child("address")
                                        .getValue(String::class.java) ?: ""
                                    val rawArea = extractArea(address)
                                    val area = rawArea.lowercase().trim()
                                    if (!areaDisplayName.containsKey(area)) {
                                        areaDisplayName[area] = rawArea
                                    }

                                    areaTotal[area] = (areaTotal[area] ?: 0) + 1
                                    areaResolved[area] = (areaResolved[area] ?: 0) + 1
                                }

                                val sortedAreas = areaTotal.entries.sortedByDescending { it.value }
                                val totalComplaints = areaTotal.values.sum()

                                val areaCards = sortedAreas.mapIndexed { index, entry ->
                                    val area = entry.key
                                    val total = entry.value
                                    val resolved = areaResolved[area] ?: 0
                                    val pending = total - resolved
                                    val rate = if (total > 0) (resolved * 100) / total else 0

                                    AreaReportItem(
                                        rank = index + 1,
                                        areaName = areaDisplayName[area] ?: area,
                                        totalComplaints = total,
                                        resolvedCount = resolved,
                                        pendingCount = pending,
                                        successRate = rate
                                    )
                                }

                                adapter.updateHeader(
                                    ReportHeaderData(
                                        summaryText = "📍 AREA-WISE COMPLAINTS\n\n" +
                                                "Total Complaints : $totalComplaints\n" +
                                                "Total Areas      : ${areaTotal.size}",
                                        summaryVisible = true,
                                        topEmployeesVisible = false,
                                        repeatVisible = false,
                                        areaReportVisible = false
                                    )
                                )
                                allAreaCards.clear()
                                allAreaCards.addAll(areaCards)
                                renderAreaCards(searchEmployeeEditText.text.toString().trim())
                            }

                            override fun onCancelled(error: DatabaseError) {}
                        })
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    // Filters allAreaCards by search query, then sorts by chosen mode
    // (highest-total-first, OR alphabetical so similar-spelled addresses
    // sit next to each other), and re-numbers the rank badges accordingly
    private fun renderAreaCards(query: String) {
        val filtered = if (query.isEmpty()) {
            allAreaCards
        } else {
            allAreaCards.filter { it.areaName.contains(query, true) }
        }

        val sorted = if (isAlphabeticalSort) {
            filtered.sortedBy { it.areaName.lowercase() }
        } else {
            filtered.sortedByDescending { it.totalComplaints }
        }

        val reRanked = sorted.mapIndexed { index, item -> item.copy(rank = index + 1) }
        adapter.updateAreaList(reRanked)
    }

    private fun setActiveButton(activeBtn: Button) {
        val buttons = listOf(todayButton, filterButton)
        for (btn in buttons) {
            btn.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    if (btn == activeBtn)
                        android.graphics.Color.parseColor("#1976D2")
                    else
                        android.graphics.Color.parseColor("#455A64")
                )
        }
    }

    private fun getTodayStart(): Long {
        return java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    // TODAY — sirf 4 box (header row only, no employee cards)
    private fun loadTodayDashboard() {
        reportList.clear()
        allReports.clear()
        adapter.updateAreaList(emptyList())
        isAreaMode = false
        sortToggleButton.visibility = android.view.View.GONE
        adapter.updateHeader(
            ReportHeaderData(
                summaryText = "📅 TODAY\n\nLoading...",
                topEmployeesVisible = false,
                repeatVisible = false
            )
        )
        adapter.notifyDataSetChanged()

        FirebaseDatabase.getInstance()
            .getReference("complaints")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var progress: Int
                    var resolved = 0
                    val progressEmployees = HashSet<String>()
                    val todayStart = getTodayStart()

                    for (item in snapshot.children) {
                        val status = item.child("status")
                            .getValue(String::class.java) ?: ""
                        if (status.equals("Resolved", true)) {
                            val resolvedTime = item.child("resolvedTime")
                                .getValue(Long::class.java) ?: 0L
                            if (resolvedTime >= todayStart) resolved++
                        } else {
                            val assignedTo = item.child("assignedTo")
                                .getValue(String::class.java) ?: ""
                            if (assignedTo.isNotEmpty()) {
                                progressEmployees.add(assignedTo)
                            }
                        }
                    }

                    progress = progressEmployees.size
                    var pending = snapshot.childrenCount.toInt() -
                            progress -
                            snapshot.children.count {
                                it.child("status").getValue(String::class.java)
                                    ?.equals("Resolved", true) == true
                            }
                    if (pending < 0) pending = 0
                    val total = pending + progress

                    adapter.updateHeader(
                        ReportHeaderData(
                            summaryText = "📅 TODAY\n\n" +
                                    "Total     : $total\n" +
                                    "Pending   : $pending\n" +
                                    "Progress  : $progress\n" +
                                    "Resolved  : $resolved",
                            topEmployeesVisible = false,
                            repeatVisible = false
                        )
                    )
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    // WEEK / MONTH — header (summary + top employees + repeat) + employee cards
    private fun loadFilteredReport(filter: String, fromTime: Long, showTopEmployees: Boolean) {
        reportList.clear()
        allReports.clear()
        adapter.updateAreaList(emptyList())
        isAreaMode = false
        sortToggleButton.visibility = android.view.View.GONE

        val label = if (filter == "WEEK") "📊 THIS WEEK" else "📈 THIS MONTH"
        adapter.updateHeader(ReportHeaderData(summaryText = "$label\n\nLoading..."))
        adapter.notifyDataSetChanged()

        FirebaseDatabase.getInstance()
            .getReference("resolvedComplaints")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(resolvedSnapshot: DataSnapshot) {

                    FirebaseDatabase.getInstance()
                        .getReference("complaints")
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {

                                var summaryResolved = 0
                                val progressEmployees = HashSet<String>()

                                val empMap = mutableMapOf<String, EmpDataR>()
                                val globalUserIds =
                                    mutableMapOf<String, MutableList<String>>()

                                val complaintsIds = mutableSetOf<String>()
                                for (cs in snapshot.children) {
                                    val cId = cs.child("complaintId")
                                        .getValue(String::class.java) ?: cs.key ?: ""
                                    if (cId.isNotEmpty()) complaintsIds.add(cId)
                                }

                                // complaints node
                                for (cs in snapshot.children) {
                                    val assignedTo = cs.child("assignedTo")
                                        .getValue(String::class.java) ?: ""
                                    val userId = cs.child("userId")
                                        .getValue(String::class.java) ?: ""
                                    val status = cs.child("status")
                                        .getValue(String::class.java) ?: ""
                                    val createdTime = cs.child("createdTime")
                                        .getValue(Long::class.java) ?: 0L
                                    val resolvedTime = cs.child("resolvedTime")
                                        .getValue(Long::class.java) ?: 0L
                                    val assignedTime = cs.child("assignedTime")
                                        .getValue(Long::class.java) ?: 0L

                                    if (status.equals("Resolved", true)) {
                                        if (resolvedTime >= fromTime) summaryResolved++
                                    } else {
                                        if (createdTime >= fromTime) {
                                            if (assignedTo.isNotEmpty())
                                                progressEmployees.add(assignedTo)
                                        }
                                    }

                                    if (assignedTo.isEmpty()) continue

                                    if (userId.isNotEmpty()) {
                                        globalUserIds.getOrPut(assignedTo) { mutableListOf() }
                                            .add(userId)
                                    }

                                    if (status.equals("Resolved", true)) {
                                        if (resolvedTime >= fromTime) {
                                            val emp = empMap.getOrPut(assignedTo) { EmpDataR() }
                                            emp.totalAssigned++
                                            emp.totalResolved++
                                            if (resolvedTime > 0 && assignedTime > 0) {
                                                emp.totalResolveTime += resolvedTime - assignedTime
                                                emp.resolvedWithTime++
                                            }
                                        }
                                    } else {
                                        if (createdTime >= fromTime) {
                                            val emp = empMap.getOrPut(assignedTo) { EmpDataR() }
                                            emp.totalAssigned++
                                            when {
                                                status.equals("Pending", true) -> emp.totalPending++
                                                else -> emp.totalProgress++
                                            }
                                        }
                                    }
                                }

                                // resolvedComplaints node
                                for (cs in resolvedSnapshot.children) {
                                    val complaintId = cs.child("complaintId")
                                        .getValue(String::class.java) ?: cs.key ?: ""
                                    val assignedTo = cs.child("assignedTo")
                                        .getValue(String::class.java) ?: ""
                                    val userId = cs.child("userId")
                                        .getValue(String::class.java) ?: ""
                                    val resolvedTime = cs.child("resolvedTime")
                                        .getValue(Long::class.java) ?: 0L
                                    val assignedTime = cs.child("assignedTime")
                                        .getValue(Long::class.java) ?: 0L

                                    if (assignedTo.isEmpty()) continue
                                    if (complaintsIds.contains(complaintId)) continue

                                    if (userId.isNotEmpty()) {
                                        globalUserIds.getOrPut(assignedTo) { mutableListOf() }
                                            .add(userId)
                                    }

                                    if (resolvedTime >= fromTime) {
                                        summaryResolved++
                                        val emp = empMap.getOrPut(assignedTo) { EmpDataR() }
                                        emp.totalAssigned++
                                        emp.totalResolved++
                                        if (resolvedTime > 0 && assignedTime > 0) {
                                            emp.totalResolveTime += resolvedTime - assignedTime
                                            emp.resolvedWithTime++
                                        }
                                    }
                                }

                                val summaryProgress = progressEmployees.size
                                val summaryPending = snapshot.children.count {
                                    val s = it.child("status")
                                        .getValue(String::class.java) ?: ""
                                    val t = it.child("createdTime")
                                        .getValue(Long::class.java) ?: 0L
                                    t >= fromTime && !s.equals("Resolved", true) &&
                                            (it.child("assignedTo")
                                                .getValue(String::class.java) ?: "").isEmpty()
                                }

                                val summaryTotal =
                                    summaryPending + summaryProgress + summaryResolved

                                // Employee cards
                                val topEmployees = mutableListOf<Pair<String, Int>>()

                                for (entry in empMap) {
                                    val name = entry.key
                                    val emp = entry.value
                                    if (emp.totalAssigned == 0) continue

                                    val allUserIds = globalUserIds[name] ?: mutableListOf()
                                    val repeatComplaints = allUserIds
                                        .filter { it.isNotEmpty() }
                                        .groupBy { it }
                                        .values
                                        .count { it.size > 1 }

                                    val successRate =
                                        if (emp.totalAssigned > 0)
                                            (emp.totalResolved * 100) / emp.totalAssigned
                                        else 0

                                    val avgTime = if (emp.resolvedWithTime > 0) {
                                        val avgMs = emp.totalResolveTime / emp.resolvedWithTime
                                        val hours = avgMs / (1000 * 60 * 60)
                                        val minutes = (avgMs % (1000 * 60 * 60)) / (1000 * 60)
                                        "${hours}h ${minutes}m"
                                    } else "N/A"

                                    topEmployees.add(Pair(name, emp.totalResolved))

                                    val reportItem = ReportItem(
                                        employeeName = name,
                                        assigned = emp.totalAssigned,
                                        pending = emp.totalPending,
                                        progress = emp.totalProgress,
                                        resolved = emp.totalResolved,
                                        successRate = successRate,
                                        repeatComplaints = repeatComplaints,
                                        todayCount = emp.totalAssigned,
                                        weekCount = emp.totalAssigned,
                                        monthCount = emp.totalAssigned,
                                        averageTime = avgTime,
                                        weekAssigned = emp.totalAssigned,
                                        weekResolved = emp.totalResolved,
                                        weekPending = emp.totalPending,
                                        weekRepeat = 0,
                                        monthAssigned = emp.totalAssigned,
                                        monthResolved = emp.totalResolved,
                                        monthPending = emp.totalPending,
                                        monthRepeat = 0,
                                        monthSuccessRate = successRate
                                    )

                                    allReports.add(reportItem)
                                    reportList.add(reportItem)
                                }

                                // Sort by star rating (successRate) descending -> highest stars first, lowest at bottom
                                reportList.sortByDescending { it.successRate }
                                allReports.sortByDescending { it.successRate }

                                val sorted = topEmployees.sortedByDescending { it.second }
                                val medals = listOf("🥇", "🥈", "🥉")
                                val topText = sorted.take(3).mapIndexed { i, e ->
                                    val rate = empMap[e.first]?.let { em ->
                                        if (em.totalAssigned > 0)
                                            (em.totalResolved * 100) / em.totalAssigned
                                        else 0
                                    } ?: 0
                                    "${medals[i]} ${e.first}\n" +
                                            "Resolved : ${e.second}\n" +
                                            "Rate     : $rate%"
                                }.joinToString("\n-----------------\n")

                                val totalRepeat = reportList.sumOf { it.repeatComplaints }

                                adapter.updateHeader(
                                    ReportHeaderData(
                                        topEmployeesText = "🏆 TOP EMPLOYEES\n\n$topText",
                                        topEmployeesVisible = showTopEmployees && sorted.isNotEmpty(),
                                        summaryText = "$label\n\n" +
                                                "Total     : $summaryTotal\n" +
                                                "Pending   : $summaryPending\n" +
                                                "Progress  : $summaryProgress\n" +
                                                "Resolved  : $summaryResolved",
                                        summaryVisible = showTopEmployees,
                                        repeatText = "⚠️ REPEAT COMPLAINTS\n\nTotal Repeat : $totalRepeat",
                                        repeatVisible = showTopEmployees
                                    )
                                )

                                // Full refresh — RecyclerView re-measures correctly now
                                // because it's height=0dp/weight=1, not wrap_content inside a ScrollView.
                                adapter.notifyDataSetChanged()
                            }

                            override fun onCancelled(error: DatabaseError) {}
                        })
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun loadCustomReport(
        fromDate: String,
        toDate: String,
        fromTime: Long,
        toTime: Long
    ) {
        reportList.clear()
        allReports.clear()
        adapter.updateAreaList(emptyList())
        isAreaMode = false
        sortToggleButton.visibility = android.view.View.GONE
        adapter.updateHeader(
            ReportHeaderData(summaryText = "📅 CUSTOM: $fromDate → $toDate\n\nLoading...")
        )
        adapter.notifyDataSetChanged()

        FirebaseDatabase.getInstance()
            .getReference("resolvedComplaints")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(resolvedSnapshot: DataSnapshot) {

                    FirebaseDatabase.getInstance()
                        .getReference("complaints")
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {

                                val empMap = mutableMapOf<String, EmpDataR>()
                                var totalResolved = 0
                                var totalPending = 0
                                var totalProgress = 0

                                val complaintsIds = mutableSetOf<String>()
                                for (cs in snapshot.children) {
                                    val cId = cs.child("complaintId")
                                        .getValue(String::class.java) ?: cs.key ?: ""
                                    if (cId.isNotEmpty()) complaintsIds.add(cId)
                                }

                                for (cs in snapshot.children) {
                                    val assignedTo = cs.child("assignedTo")
                                        .getValue(String::class.java) ?: ""
                                    val status = cs.child("status")
                                        .getValue(String::class.java) ?: ""
                                    val createdTime = cs.child("createdTime")
                                        .getValue(Long::class.java) ?: 0L
                                    val resolvedTime = cs.child("resolvedTime")
                                        .getValue(Long::class.java) ?: 0L

                                    if (assignedTo.isEmpty()) continue

                                    if (status.equals("Resolved", true)) {
                                        if (resolvedTime in fromTime..toTime) {
                                            val emp = empMap.getOrPut(assignedTo) { EmpDataR() }
                                            emp.totalAssigned++
                                            emp.totalResolved++
                                            totalResolved++
                                        }
                                    } else {
                                        if (createdTime in fromTime..toTime) {
                                            val emp = empMap.getOrPut(assignedTo) { EmpDataR() }
                                            emp.totalAssigned++
                                            when {
                                                status.equals("Pending", true) -> {
                                                    emp.totalPending++
                                                    totalPending++
                                                }
                                                else -> {
                                                    emp.totalProgress++
                                                    totalProgress++
                                                }
                                            }
                                        }
                                    }
                                }

                                for (cs in resolvedSnapshot.children) {
                                    val complaintId = cs.child("complaintId")
                                        .getValue(String::class.java) ?: cs.key ?: ""
                                    val assignedTo = cs.child("assignedTo")
                                        .getValue(String::class.java) ?: ""
                                    val resolvedTime = cs.child("resolvedTime")
                                        .getValue(Long::class.java) ?: 0L

                                    if (assignedTo.isEmpty()) continue
                                    if (complaintsIds.contains(complaintId)) continue

                                    if (resolvedTime in fromTime..toTime) {
                                        val emp = empMap.getOrPut(assignedTo) { EmpDataR() }
                                        emp.totalAssigned++
                                        emp.totalResolved++
                                        totalResolved++
                                    }
                                }

                                val total = totalResolved + totalPending + totalProgress

                                val topEmployees = mutableListOf<Pair<String, Int>>()

                                for (entry in empMap) {
                                    val name = entry.key
                                    val emp = entry.value
                                    val successRate =
                                        if (emp.totalAssigned > 0)
                                            (emp.totalResolved * 100) / emp.totalAssigned
                                        else 0

                                    topEmployees.add(Pair(name, emp.totalResolved))

                                    val reportItem = ReportItem(
                                        employeeName = name,
                                        assigned = emp.totalAssigned,
                                        pending = emp.totalPending,
                                        progress = emp.totalProgress,
                                        resolved = emp.totalResolved,
                                        successRate = successRate,
                                        repeatComplaints = 0,
                                        averageTime = "N/A"
                                    )
                                    allReports.add(reportItem)
                                    reportList.add(reportItem)
                                }

                                // Sort by star rating (successRate) descending -> highest stars first, lowest at bottom
                                reportList.sortByDescending { it.successRate }
                                allReports.sortByDescending { it.successRate }

                                val sorted = topEmployees.sortedByDescending { it.second }
                                val medals = listOf("🥇", "🥈", "🥉")
                                val topText = sorted.take(3).mapIndexed { i, e ->
                                    val rate = empMap[e.first]?.let { em ->
                                        if (em.totalAssigned > 0)
                                            (em.totalResolved * 100) / em.totalAssigned
                                        else 0
                                    } ?: 0
                                    "${medals[i]} ${e.first}\n" +
                                            "Resolved : ${e.second}\n" +
                                            "Rate     : $rate%"
                                }.joinToString("\n-----------------\n")

                                adapter.updateHeader(
                                    ReportHeaderData(
                                        topEmployeesText = "🏆 TOP EMPLOYEES\n\n$topText",
                                        topEmployeesVisible = sorted.isNotEmpty(),
                                        summaryText = "📅 CUSTOM: $fromDate → $toDate\n\n" +
                                                "Total     : $total\n" +
                                                "Resolved  : $totalResolved\n" +
                                                "Progress  : $totalProgress\n" +
                                                "Pending   : $totalPending",
                                        repeatVisible = false
                                    )
                                )

                                adapter.notifyDataSetChanged()
                            }

                            override fun onCancelled(error: DatabaseError) {}
                        })
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    data class EmpDataR(
        var totalAssigned: Int = 0,
        var totalResolved: Int = 0,
        var totalPending: Int = 0,
        var totalProgress: Int = 0,
        var totalResolveTime: Long = 0L,
        var resolvedWithTime: Int = 0
    )
}
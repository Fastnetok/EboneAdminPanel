package com.example.eboneadminpanel

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
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

private const val ALL_EMPLOYEES_REPORT_MARKER = "ALL"

class ReportsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var todayButton: Button
    private lateinit var employeeReportsButton: Button
    private lateinit var calendarPickerButton: Button
    private val reportList = mutableListOf<ReportItem>()
    private val allReports = mutableListOf<ReportItem>()
    private lateinit var adapter: ReportAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reports)

        recyclerView = findViewById(R.id.reportsRecyclerView)
        todayButton = findViewById(R.id.todayButton)
        employeeReportsButton = findViewById(R.id.employeeReportsButton)
        calendarPickerButton = findViewById(R.id.calendarPickerButton)

        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val headerView = todayButton.parent as? View
            headerView?.setPadding(
                headerView.paddingLeft,
                statusBarInset.top + (4 * resources.displayMetrics.density).toInt(),
                headerView.paddingRight,
                headerView.paddingBottom
            )
            insets
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ReportAdapter(
            reportList,
            onRepeatClick = {
                // Header "REPEAT COMPLAINTS" box clicked -> show full repeat list across ALL employees
                val intent = Intent(this, EmployeeReportDetailsActivity::class.java)
                intent.putExtra("employeeName", ALL_EMPLOYEES_REPORT_MARKER)
                intent.putExtra("showRepeat", true)
                startActivity(intent)
            },
            onTotalComplaintsClick = {
                // Header Total Complaints / Overall Summary card clicked -> open AllComplaintsActivity
                val intent = Intent(this, AllComplaintsActivity::class.java)
                startActivity(intent)
            }
        )
        recyclerView.adapter = adapter

        setActiveButton(todayButton)
        loadTodayDashboard()

        todayButton.setOnClickListener {
            setActiveButton(todayButton)
            loadTodayDashboard()
        }

        employeeReportsButton.setOnClickListener {
            setActiveButton(employeeReportsButton)
            showMonthSelector()
        }

        calendarPickerButton.setOnClickListener {
            showCustomDateRangePicker()
        }
    }

    private fun showCustomDateRangePicker() {
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
                    val fromMs = localStart.timeInMillis

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
                    val toMs = localEnd.timeInMillis

                    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    val fromStr = sdf.format(Date(fromMs))
                    val toStr = sdf.format(Date(toMs))

                    setActiveButton(calendarPickerButton)
                    loadCustomDateRangeReport(fromStr, toStr, fromMs, toMs)
                }
            }
            picker.show(supportFragmentManager, "date_range_picker")
        } catch (_: Exception) {
            showFallbackDateRangePicker()
        }
    }

    private fun showFallbackDateRangePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, fromYear, fromMonth, fromDay ->
                val localStart = Calendar.getInstance()
                localStart.set(fromYear, fromMonth, fromDay, 0, 0, 0)
                localStart.set(Calendar.MILLISECOND, 0)
                val fromMs = localStart.timeInMillis

                DatePickerDialog(
                    this,
                    { _, toYear, toMonth, toDay ->
                        val localEnd = Calendar.getInstance()
                        localEnd.set(toYear, toMonth, toDay, 23, 59, 59)
                        localEnd.set(Calendar.MILLISECOND, 999)
                        val toMs = localEnd.timeInMillis

                        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        val fromStr = sdf.format(Date(fromMs))
                        val toStr = sdf.format(Date(toMs))

                        if (fromMs > toMs) {
                            Toast.makeText(this, "Start date pehle honi chahiye", Toast.LENGTH_SHORT).show()
                        } else {
                            setActiveButton(calendarPickerButton)
                            loadCustomDateRangeReport(fromStr, toStr, fromMs, toMs)
                        }
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

    // MONTHLY REPORTS — open current calendar month directly.
    private fun showMonthSelector() {
        val currentCalendar = Calendar.getInstance()
        val currentYear = currentCalendar.get(Calendar.YEAR)
        val currentMonth = currentCalendar.get(Calendar.MONTH)

        loadCalendarMonthReport(currentYear, currentMonth)
    }

    private fun loadCalendarMonthReport(
        year: Int,
        month: Int
    ) {
        val startCalendar = Calendar.getInstance()
        startCalendar.set(year, month, 1, 0, 0, 0)
        startCalendar.set(Calendar.MILLISECOND, 0)

        val endCalendar = startCalendar.clone() as Calendar
        endCalendar.add(Calendar.MONTH, 1)
        endCalendar.add(Calendar.MILLISECOND, -1)

        val fromTime = startCalendar.timeInMillis
        val toTime = endCalendar.timeInMillis

        val monthLabel = SimpleDateFormat(
            "MMMM yyyy",
            Locale.getDefault()
        ).format(startCalendar.time)

        loadCustomDateRangeReport(monthLabel, "", fromTime, toTime, isMonthLabel = true)
    }

    private fun loadCustomDateRangeReport(
        fromDate: String,
        toDate: String,
        fromMs: Long,
        toMs: Long,
        isMonthLabel: Boolean = false
    ) {
        reportList.clear()
        allReports.clear()
        adapter.updateAreaList(emptyList())

        val titleText = if (isMonthLabel) "📅 $fromDate" else "📅 $fromDate → $toDate"

        adapter.updateHeader(
            ReportHeaderData(
                summaryText = "$titleText\n\nLoading...",
                topEmployeesVisible = false,
                repeatVisible = false,
                areaReportVisible = false
            )
        )
        adapter.notifyDataSetChanged()

        val refResolved = FirebaseDatabase.getInstance().getReference("resolvedComplaints")
        val refComplaints = FirebaseDatabase.getInstance().getReference("complaints")

        refResolved.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(resolvedSnapshot: DataSnapshot) {
                refComplaints.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(complaintSnapshot: DataSnapshot) {
                        try {
                            val empMap = mutableMapOf<String, EmployeeReportAcc>()
                            val globalUserIds = mutableMapOf<String, MutableList<String>>()

                            fun normalizeEmployeeName(name: String): String {
                                val trimmed = name.trim()
                                if (trimmed.isEmpty()) return "unassigned"
                                return trimmed.lowercase(Locale.getDefault())
                                    .split(" ").joinToString(" ") { it.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } }
                            }

                            fun getAcc(rawName: String): EmployeeReportAcc {
                                val norm = normalizeEmployeeName(rawName)
                                var acc = empMap[norm]
                                if (acc == null) {
                                    val display = if (rawName.isBlank()) "Unassigned" else rawName.trim()
                                    acc = EmployeeReportAcc(display)
                                    empMap[norm] = acc
                                }
                                return acc
                            }

                            val complaintIds = mutableSetOf<String>()
                            for (cs in resolvedSnapshot.children) {
                                val id = cs.child("complaintId").getValue(String::class.java) ?: cs.key ?: ""
                                if (id.isNotEmpty()) complaintIds.add(id)
                            }

                            fun addComplaint(cs: DataSnapshot) {
                                val isNewConnection = cs.child("isNewConnection").getValue(Boolean::class.java)
                                    ?: (cs.child("isNewConnection").getValue(String::class.java)?.toBoolean() ?: false)
                                if (isNewConnection) return

                                val createdTime = cs.child("createdTime").getValue(Long::class.java) ?: 0L
                                val resolvedTime = cs.child("resolvedTime").getValue(Long::class.java) ?: 0L
                                val assignedTime = cs.child("assignedTime").getValue(Long::class.java) ?: 0L

                                // Check date range
                                val hasTimestamp = (createdTime > 0L || resolvedTime > 0L || assignedTime > 0L)
                                val inRange = (createdTime in fromMs..toMs) ||
                                              (resolvedTime in fromMs..toMs) ||
                                              (assignedTime in fromMs..toMs)

                                if (hasTimestamp && !inRange) return

                                val assignedRaw = cs.child("assignedTo").getValue(String::class.java) ?: ""
                                val userId = cs.child("userId").getValue(String::class.java) ?: ""
                                val status = cs.child("status").getValue(String::class.java) ?: ""

                                val emp = getAcc(assignedRaw)
                                emp.totalAssigned++

                                if (userId.isNotEmpty()) {
                                    emp.userIds.add(userId)
                                    val normKey = normalizeEmployeeName(assignedRaw)
                                    globalUserIds.getOrPut(normKey) { mutableListOf() }.add(userId)
                                }

                                if (status.equals("Resolved", true)) {
                                    emp.totalResolved++
                                    if (assignedTime > 0 && resolvedTime >= assignedTime) {
                                        emp.totalResolveTime += (resolvedTime - assignedTime)
                                        emp.resolvedWithTime++
                                    }
                                } else if (status.equals("Pending", true)) {
                                    emp.totalPending++
                                } else {
                                    emp.totalProgress++
                                }
                            }

                            for (cs in complaintSnapshot.children) {
                                addComplaint(cs)
                            }

                            for (cs in resolvedSnapshot.children) {
                                val complaintId = cs.child("complaintId").getValue(String::class.java) ?: cs.key ?: ""
                                if (complaintId.isNotEmpty() && complaintIds.contains(complaintId)) {
                                    continue
                                }
                                addComplaint(cs)
                            }

                            val reports = mutableListOf<ReportItem>()
                            var total = 0
                            var resolved = 0
                            var pending = 0
                            var progress = 0

                            for ((_, emp) in empMap) {
                                if (emp.totalAssigned <= 0) continue

                                val repeat = emp.userIds
                                    .filter { it.isNotEmpty() }
                                    .groupBy { it }
                                    .values
                                    .count { it.size > 1 }

                                val successRate = if (emp.totalAssigned > 0)
                                    (emp.totalResolved * 100) / emp.totalAssigned
                                else 0

                                val averageTime = if (emp.resolvedWithTime > 0) {
                                    val avgMs = emp.totalResolveTime / emp.resolvedWithTime
                                    val hours = avgMs / (1000 * 60 * 60)
                                    val minutes = (avgMs % (1000 * 60 * 60)) / (1000 * 60)
                                    "${hours}h ${minutes}m"
                                } else {
                                    "N/A"
                                }

                                total += emp.totalAssigned
                                resolved += emp.totalResolved
                                pending += emp.totalPending
                                progress += emp.totalProgress

                                reports.add(
                                    ReportItem(
                                        employeeName = emp.displayName,
                                        assigned = emp.totalAssigned,
                                        pending = emp.totalPending,
                                        progress = emp.totalProgress,
                                        resolved = emp.totalResolved,
                                        successRate = successRate,
                                        repeatComplaints = repeat,
                                        todayCount = emp.totalAssigned,
                                        weekCount = emp.totalAssigned,
                                        monthCount = emp.totalAssigned,
                                        averageTime = averageTime,
                                        weekAssigned = emp.totalAssigned,
                                        weekResolved = emp.totalResolved,
                                        weekPending = emp.totalPending,
                                        weekRepeat = repeat,
                                        monthAssigned = emp.totalAssigned,
                                        monthResolved = emp.totalResolved,
                                        monthPending = emp.totalPending,
                                        monthRepeat = repeat,
                                        monthSuccessRate = successRate
                                    )
                                )
                            }

                            reports.sortWith(
                                compareByDescending<ReportItem> { it.assigned }
                                    .thenByDescending { it.resolved }
                                    .thenByDescending { it.successRate }
                                    .thenBy { it.employeeName }
                            )

                            reportList.clear()
                            allReports.clear()
                            reportList.addAll(reports)
                            allReports.addAll(reports)

                            val medals = listOf("🥇", "🥈", "🥉")
                            val topText = reports.take(3)
                                .mapIndexed { index, report ->
                                    "${medals[index]} ${report.employeeName}\n" +
                                            "Total    : ${report.assigned}\n" +
                                            "Resolved : ${report.resolved}\n" +
                                            "Rate     : ${report.successRate}%"
                                }
                                .joinToString("\n-----------------\n")

                            val totalRepeat = reports.sumOf { it.repeatComplaints }

                            adapter.updateHeader(
                                ReportHeaderData(
                                    topEmployeesText = "🏆 TOP EMPLOYEES\n\n$topText",
                                    topEmployeesVisible = reports.isNotEmpty(),
                                    summaryText = "$titleText\n\n" +
                                            "Total Complaints : $total\n" +
                                            "Pending          : $pending\n" +
                                            "Progress         : $progress\n" +
                                            "Resolved         : $resolved",
                                    summaryVisible = true,
                                    repeatText = "⚠️ REPEAT COMPLAINTS\n\nTotal Repeat : $totalRepeat",
                                    repeatVisible = true,
                                    areaReportVisible = false
                                )
                            )

                            adapter.notifyDataSetChanged()
                        } catch (_: Exception) {
                            adapter.updateHeader(
                                ReportHeaderData(
                                    summaryText = "$titleText\n\nTotal Complaints : 0\nPending : 0\nProgress : 0\nResolved : 0",
                                    summaryVisible = true
                                )
                            )
                            adapter.notifyDataSetChanged()
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        adapter.updateHeader(
                            ReportHeaderData(
                                summaryText = "$titleText\n\nFailed to load database",
                                summaryVisible = true
                            )
                        )
                        adapter.notifyDataSetChanged()
                    }
                })
            }

            override fun onCancelled(error: DatabaseError) {
                adapter.updateHeader(
                    ReportHeaderData(
                        summaryText = "$titleText\n\nFailed to load database",
                        summaryVisible = true
                    )
                )
                adapter.notifyDataSetChanged()
            }
        })
}

    private fun getTodayStart(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    // TODAY — loads Today's active complaints & counts using Main Dashboard's exact logic
    private fun loadTodayDashboard() {
        reportList.clear()
        allReports.clear()
        adapter.updateAreaList(emptyList())
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
                    var pending = 0
                    var progress = 0
                    var resolved = 0

                    val progressEmployees = HashSet<String>()
                    val todayStart = getTodayStart()

                    for (item in snapshot.children) {
                        val isNewConnection = item.child("isNewConnection").getValue(Boolean::class.java) ?: false
                        if (isNewConnection) continue

                        val status = item.child("status").getValue(String::class.java) ?: "Pending"

                        if (status.equals("Resolved", true)) {
                            val resolvedTime = item.child("resolvedTime").getValue(Long::class.java) ?: 0L
                            if (resolvedTime >= todayStart) {
                                resolved++
                            }
                        } else {
                            val assignedTo = item.child("assignedTo").getValue(String::class.java) ?: ""
                            if (assignedTo.isNotEmpty()) {
                                progressEmployees.add(assignedTo)
                            }
                        }
                    }

                    progress = progressEmployees.size

                    pending = snapshot.childrenCount.toInt() -
                            progress -
                            snapshot.children.count {
                                it.child("status").getValue(String::class.java)?.equals("Resolved", true) == true
                            }

                    if (pending < 0) {
                        pending = 0
                    }

                    val total = pending + progress

                    adapter.updateHeader(
                        ReportHeaderData(
                            summaryText = "📊 TODAY SUMMARY\n\n" +
                                    "Total Complaints : $total\n" +
                                    "Pending          : $pending\n" +
                                    "Progress         : $progress\n" +
                                    "Resolved         : $resolved",
                            topEmployeesVisible = false,
                            repeatVisible = false
                        )
                    )
                    adapter.notifyDataSetChanged()
                }

                override fun onCancelled(error: DatabaseError) {
                    adapter.updateHeader(
                        ReportHeaderData(
                            summaryText = "📊 TODAY SUMMARY\n\nFailed to load complaints",
                            topEmployeesVisible = false,
                            repeatVisible = false
                        )
                    )
                    adapter.notifyDataSetChanged()
                }
            })
    }

    private fun setActiveButton(activeBtn: View) {
        val buttons = listOf(todayButton, employeeReportsButton, calendarPickerButton)
        for (btn in buttons) {
            if (btn == activeBtn) {
                btn.setBackgroundColor(Color.parseColor("#1976D2"))
                btn.setTextColor(Color.WHITE)
            } else {
                btn.setBackgroundColor(Color.parseColor("#455A64"))
                btn.setTextColor(Color.WHITE)
            }
        }
    }

    private class EmployeeReportAcc(val displayName: String) {
        var totalAssigned = 0
        var totalResolved = 0
        var totalPending = 0
        var totalProgress = 0
        var totalResolveTime = 0L
        var resolvedWithTime = 0
        val userIds = mutableListOf<String>()
    }
}

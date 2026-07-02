package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import android.widget.EditText
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class ReportsActivity : AppCompatActivity() {


    private lateinit var recyclerView: RecyclerView
    private lateinit var topEmployeeText: TextView
    private lateinit var overallSummaryText: TextView
    private lateinit var searchEmployeeEditText: EditText
    private lateinit var weekReportText: TextView
    private lateinit var monthReportText: TextView
    private lateinit var repeatReportText: TextView
    private lateinit var todayButton: Button
    private lateinit var weekButton: Button
    private lateinit var monthButton: Button
    private lateinit var customButton: Button
    private val reportList =
        mutableListOf<ReportItem>()

    private val allReports =
        mutableListOf<ReportItem>()

    private lateinit var adapter: ReportAdapter
    private var selectedFilter = "TODAY"
    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_reports
        )

        recyclerView =
            findViewById(
                R.id.reportsRecyclerView
            )

        topEmployeeText =
            findViewById(
                R.id.topEmployeeText
            )
        overallSummaryText =
            findViewById(
                R.id.overallSummaryText
            )
        searchEmployeeEditText =
            findViewById(
                R.id.searchEmployeeEditText
            )
        weekReportText =
            findViewById(
                R.id.weekReportText
            )

        monthReportText =
            findViewById(
                R.id.monthReportText
            )

        repeatReportText =
            findViewById(
                R.id.repeatReportText
            )

        topEmployeeText.visibility = android.view.View.GONE

        weekReportText.visibility = android.view.View.GONE

        monthReportText.visibility = android.view.View.GONE

        repeatReportText.visibility = android.view.View.GONE

        todayButton =
            findViewById(
                R.id.todayButton
            )

        weekButton =
            findViewById(
                R.id.weekButton
            )

        monthButton =
            findViewById(
                R.id.monthButton
            )

        customButton =
            findViewById(
                R.id.customButton
            )
        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            ReportAdapter(reportList)

        recyclerView.adapter =
            adapter

        loadReports()

        todayButton.setOnClickListener {

            selectedFilter = "TODAY"

            loadReports()
        }

        weekButton.setOnClickListener {

            selectedFilter = "WEEK"

            loadReports()
        }

        monthButton.setOnClickListener {

            selectedFilter = "MONTH"

            loadReports()
        }

        customButton.setOnClickListener {

            selectedFilter = "CUSTOM"

            loadReports()
        }

        searchEmployeeEditText.addTextChangedListener(


            object : android.text.TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {

                    val searchText =
                        s.toString().trim()

                    reportList.clear()

                    if (
                        searchText.isEmpty()
                    ) {

                        reportList.addAll(
                            allReports
                        )

                    } else {

                        for (
                        report
                        in allReports
                        ) {

                            if (
                                report.employeeName
                                    .contains(
                                        searchText,
                                        true
                                    )
                            ) {

                                reportList.add(
                                    report
                                )

                            }
                        }
                    }

                    adapter.notifyDataSetChanged()

                }

                override fun afterTextChanged(
                    s: android.text.Editable?
                ) {
                }
            }
        )
    }

    private fun loadReports() {

        FirebaseDatabase
            .getInstance()
            .getReference("complaints")
            .addValueEventListener(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        reportList.clear()
                        allReports.clear()

                        var totalComplaints = 0

                        var pendingCount = 0

                        var progressCount = 0
                        val progressEmployees =
                            HashSet<String>()

                        var resolvedCount = 0

                        var todayCount = 0

                        var weekCount = 0

                        var monthCount = 0

                        val employeeMap =
                            mutableMapOf<
                                    String,
                                    MutableList<String>
                                    >()
                        val repeatMap =
                            mutableMapOf<
                                    String,
                                    MutableSet<String>
                                    >()

                        for (
                        complaintSnapshot
                        in snapshot.children
                        ) {

                            val assignedTo =
                                complaintSnapshot
                                    .child("assignedTo")
                                    .getValue(
                                        String::class.java
                                    )
                                    ?: ""
                            val userId =
                                complaintSnapshot
                                    .child("userId")
                                    .getValue(
                                        String::class.java
                                    )
                                    ?: ""

                            val status =
                                complaintSnapshot
                                    .child("status")
                                    .getValue(
                                        String::class.java
                                    )
                                    ?: "Pending"

                            val createdTime =
                                complaintSnapshot
                                    .child("createdTime")
                                    .getValue(
                                        Long::class.java
                                    )
                                    ?: 0L

                            totalComplaints++

                            if (
                                status.equals(
                                    "Resolved",
                                    true
                                )
                            ) {
                                val todayStart =
                                    TodayFilter.getTodayStart()

                                val resolvedTime =
                                    complaintSnapshot
                                        .child("resolvedTime")
                                        .getValue(Long::class.java)
                                        ?: 0L

                                if (
                                    resolvedTime >= todayStart
                                ) {

                                    resolvedCount++

                                }

                            } else {

                                if (
                                    assignedTo.isNotEmpty()
                                ) {

                                    progressEmployees.add(
                                        assignedTo
                                    )
                                }
                            }

                            val todayStart =
                                TodayFilter.getTodayStart()
                            progressCount =
                                progressEmployees.size

                            val weekStart =
                                System.currentTimeMillis() -
                                        (7L * 24 * 60 * 60 * 1000)

                            val monthStart =
                                System.currentTimeMillis() -
                                        (30L * 24 * 60 * 60 * 1000)

                            if (
                                createdTime >= todayStart
                            ) {
                                todayCount++
                            }

                            if (
                                createdTime >= weekStart
                            ) {
                                weekCount++
                            }

                            if (
                                createdTime >= monthStart
                            ) {
                                monthCount++
                            }

                            if (
                                assignedTo.isNotEmpty()
                            ) {

                                if (
                                    !employeeMap.containsKey(
                                        assignedTo
                                    )
                                ) {

                                    employeeMap[
                                        assignedTo
                                    ] =
                                        mutableListOf()
                                }

                                employeeMap[
                                    assignedTo
                                ]?.add(
                                    status
                                )
                            }
                            if (
                                !repeatMap.containsKey(
                                    assignedTo
                                )
                            ) {

                                repeatMap[
                                    assignedTo
                                ] = mutableSetOf()

                            }

                            if (
                                userId.isNotEmpty()
                            ) {

                                repeatMap[
                                    assignedTo
                                ]?.add(
                                    userId
                                )

                            }
                        }


                        var topEmployee =
                            ""
                        val topEmployees =
                            mutableListOf<Pair<String, Int>>()
                        var topRate = 0
                        var topRepeatEmployee = ""

                        var topRepeatCount = 0
                        for (
                        entry
                        in employeeMap
                        ) {

                            val employeeName =
                                entry.key

                            val statusList =
                                entry.value

                            var todayEmployeeCount = 0

                            var weekEmployeeCount = 0

                            var monthEmployeeCount = 0

                            val assigned =
                                statusList.size

                            var pending = 0
                            var progress = 0
                            var resolved = 0

                            for (
                            status
                            in statusList
                            ) {

                                when (status) {

                                    "Pending" ->
                                        pending++

                                    "Progress" ->
                                        progress++

                                    "Resolved" ->
                                        resolved++
                                }
                            }

                            todayEmployeeCount = 0

                            weekEmployeeCount = 0

                            monthEmployeeCount = 0

                            todayEmployeeCount =
                                assigned

                            weekEmployeeCount =
                                assigned

                            monthEmployeeCount =
                                assigned
                            val repeatCount =

                                assigned -
                                        (
                                                repeatMap[
                                                    employeeName
                                                ]?.size ?: 0
                                                )
                            if (
                                repeatCount > topRepeatCount
                            ) {

                                topRepeatCount =
                                    repeatCount

                                topRepeatEmployee =
                                    employeeName
                            }
                            val successRate =
                                if (
                                    assigned > 0
                                ) {

                                    (
                                            resolved * 100
                                            ) / assigned

                                } else {

                                    0
                                }

                            if (
                                successRate >
                                topRate
                            ) {

                                topRate =
                                    successRate

                                topEmployee =
                                    employeeName
                            }

                            topEmployees.add(
                                Pair(
                                    employeeName,
                                    successRate
                                )
                            )

                            val reportItem = ReportItem(

                                employeeName =
                                    employeeName,

                                assigned =
                                    assigned,

                                pending =
                                    pending,

                                progress =
                                    progress,

                                resolved =
                                    resolved,

                                successRate =
                                    successRate,

                                repeatComplaints =
                                    repeatCount,

                                todayCount =
                                    todayEmployeeCount,

                                weekCount =
                                    weekEmployeeCount,

                                monthCount =
                                    monthEmployeeCount,

                                averageTime =
                                    "0 Min"

                            )

                            allReports.add(reportItem)

                            reportList.add(reportItem)
                        }

                        val sortedEmployees =

                            topEmployees
                                .sortedByDescending { it.second }
                                .take(3)

                        topEmployeeText.text =

                            "🏆 TOP EMPLOYEES\n\n" +

                                    sortedEmployees.joinToString("\n\n") {

                                        "${it.first} (${it.second}%)"

                                    }


                        overallSummaryText.text =

                            "📅 TODAY REPORT\n\n" +

                                    "Total Complaints : $todayCount\n\n" +

                                    "Pending : $pendingCount\n\n" +

                                    "Progress : $progressCount\n\n" +

                                    "Resolved : $resolvedCount"


                        weekReportText.text =

                            "📊 WEEK REPORT\n\n" +

                                    "Total Complaints : $weekCount\n\n" +

                                    "Pending : $pendingCount\n\n" +

                                    "Progress : $progressCount\n\n" +

                                    "Resolved : $resolvedCount"


                        monthReportText.text =

                            "📈 MONTH REPORT\n\n" +

                                    "Total Complaints : $monthCount\n\n" +

                                    "Pending : $pendingCount\n\n" +

                                    "Progress : $progressCount\n\n" +

                                    "Resolved : $resolvedCount"


                        repeatReportText.text =

                            "⚠️ REPEAT COMPLAINTS\n\n" +

                                    "Total Repeat : ${reportList.sumOf { it.repeatComplaints }}\n\n" +

                                    "Top Employee : $topRepeatEmployee\n\n" +

                                    "Repeat Count : $topRepeatCount"

                        adapter.notifyDataSetChanged()

                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }
                }
            )
    }
}
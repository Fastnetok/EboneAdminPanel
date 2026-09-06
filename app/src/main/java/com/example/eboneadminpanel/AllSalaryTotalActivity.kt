package com.example.eboneadminpanel

import android.widget.NumberPicker
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class AllSalaryTotalActivity : AppCompatActivity() {

    private val db = FirebaseDatabase.getInstance()
    private var selectedYear = Calendar.getInstance().get(Calendar.YEAR)
    private var selectedMonth = Calendar.getInstance().get(Calendar.MONTH)

    private lateinit var tvMonthBtn: TextView
    private lateinit var tvTotalPayable: TextView
    private lateinit var tvTotalDeductions: TextView
    private lateinit var tvTotalPaid: TextView
    private lateinit var tvTotalUnpaid: TextView
    private lateinit var tvBaseSalaries: TextView
    private lateinit var tvSavingDeductions: TextView
    private lateinit var tvAdminSaving: TextView
    private lateinit var employeeListContainer: LinearLayout

    data class EmpSummary(
        val name: String,
        val deviceId: String,
        val baseSalary: Double,
        val netSalary: Double,
        val deductions: Double,
        val paid: Boolean,
        val paidAmount: Double,
        val advanceTotal: Double
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        loadAllData()
    }

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6FA"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0D2E5C"))
            setPadding(px(16,dp), px(48,dp), px(16,dp), px(16,dp))
        }
        ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            background = null; setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(px(28,dp), px(28,dp))
            setOnClickListener { finish() }
        }.also { header.addView(it) }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginStart = px(12,dp) }
        }
        titleBlock.addView(tv("All Salary Summary", 17f, Color.WHITE, bold = true))
        titleBlock.addView(tv("Monthly payroll overview", 12f, Color.parseColor("#B8C6DE")))
        header.addView(titleBlock)

        // Month selector in header
        val monthBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 8f*dp; setColor(Color.parseColor("#1A3F7A")) }
            setPadding(px(10,dp), px(6,dp), px(10,dp), px(6,dp))
            isClickable = true; isFocusable = true
            setOnClickListener { showMonthPicker() }
        }
        tvMonthBtn = tv(getShortMonthLabel(), 10f, Color.WHITE, bold = true)
        monthBtn.addView(tvMonthBtn)
        monthBtn.addView(tv(" ▾", 10f, Color.WHITE))
        header.addView(monthBtn)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12,dp), px(12,dp), px(12,dp), px(12,dp))
        }
        scroll.addView(content)

        // Stats Grid 2x2
        val grid = GridLayout(this).apply {
            columnCount = 2
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = px(10,dp) }
        }

        // Total Payable
        val payableCard = statCard("Total Payable", "Rs —", "#111111", "#FFFFFF", "#E0E0E0", dp)
        tvTotalPayable = payableCard.findViewWithTag("val")
        payableCard.addView(tv("All employees", 9f, Color.parseColor("#9E9E9E")).also { it.gravity = Gravity.CENTER })

        // Total Deductions
        val deductCard = statCard("Total Deductions", "Rs —", "#C62828", "#FFFFFF", "#E0E0E0", dp)
        tvTotalDeductions = deductCard.findViewWithTag("val")
        deductCard.addView(tv("Admin Saving 💰", 9f, Color.parseColor("#2E7D32")).also { it.gravity = Gravity.CENTER })

        // Total Paid
        val paidCard = statCard("Total Paid", "Rs —", "#2E7D32", "#E8F5E9", "#A5D6A7", dp)
        tvTotalPaid = paidCard.findViewWithTag("val")
        paidCard.addView(tv("✅ Done", 9f, Color.parseColor("#2E7D32")).also { it.gravity = Gravity.CENTER })

        // Total Unpaid
        val unpaidCard = statCard("Total Unpaid", "Rs —", "#C62828", "#FFEBEE", "#FFCDD2", dp)
        tvTotalUnpaid = unpaidCard.findViewWithTag("val")
        unpaidCard.addView(tv("⚠️ Pending", 9f, Color.parseColor("#C62828")).also { it.gravity = Gravity.CENTER })

        // Add click listeners for paid/unpaid detail
        paidCard.isClickable = true; paidCard.isFocusable = true
        unpaidCard.isClickable = true; unpaidCard.isFocusable = true

        val gridLp = GridLayout.LayoutParams().apply {
            width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(px(4,dp), px(4,dp), px(4,dp), px(4,dp))
        }
        payableCard.layoutParams = gridLp.also { it.columnSpec = GridLayout.spec(0, 1f) }
        deductCard.layoutParams = GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(px(4,dp), px(4,dp), px(4,dp), px(4,dp)) }
        paidCard.layoutParams = GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(px(4,dp), px(4,dp), px(4,dp), px(4,dp)) }
        unpaidCard.layoutParams = GridLayout.LayoutParams().apply { width = 0; columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(px(4,dp), px(4,dp), px(4,dp), px(4,dp)) }

        grid.addView(payableCard)
        grid.addView(deductCard)
        grid.addView(paidCard)
        grid.addView(unpaidCard)
        content.addView(grid)

        // Employee List Card
        val empCard = card(dp)
        empCard.addView(tv("Per Employee Breakdown", 15f, Color.parseColor("#111111"), bold = true).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(8,dp) }
        })
        empCard.addView(View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")); layoutParams = llp().also { it.height = 1; it.bottomMargin = px(4,dp) } })
        employeeListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        empCard.addView(employeeListContainer)
        content.addView(empCard)

        // Month End Summary
        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*dp; setColor(Color.parseColor("#E8F5E9")); setStroke(px(1,dp), Color.parseColor("#A5D6A7")) }
            setPadding(px(14,dp), px(14,dp), px(14,dp), px(14,dp))
            layoutParams = llp().also { it.topMargin = px(4,dp) }
        }
        summaryCard.addView(tv("Month End Summary 💰", 14f, Color.parseColor("#2E7D32"), bold = true).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(10,dp) }
        })
        summaryCard.addView(summaryRow("Total Base Salaries", "#111111", dp).also { tvBaseSalaries = it.findViewWithTag("val") })
        summaryCard.addView(summaryRow("Total Deductions", "#C62828", dp).also { tvSavingDeductions = it.findViewWithTag("val") })
        summaryCard.addView(View(this).apply { setBackgroundColor(Color.parseColor("#A5D6A7")); layoutParams = llp().also { it.height = 1; it.topMargin = px(6,dp); it.bottomMargin = px(6,dp) } })
        val savingRow = summaryRow("Admin Saving This Month", "#2E7D32", dp).also {
            tvAdminSaving = it.findViewWithTag("val")
            (it.findViewWithTag("label") as? TextView)?.setTypeface(null, android.graphics.Typeface.BOLD)
            (it.findViewWithTag("val") as? TextView)?.textSize = 15f
        }
        summaryCard.addView(savingRow)
        content.addView(summaryCard)

        root.addView(scroll)

        // Wire up paid/unpaid taps after we have empList ref
        paidCard.setOnClickListener { showEmployeeDetail("paid") }
        unpaidCard.setOnClickListener { showEmployeeDetail("unpaid") }

        return root
    }

    // ─── LOAD ALL DATA ───

    private var allSummaries = listOf<EmpSummary>()

    private fun loadAllData() {
        val monthKey = getMonthKey()
        db.getReference("officeSettings").get().addOnSuccessListener { officeSnap ->
            val startH = (officeSnap.child("startHour").value as? Long)?.toInt() ?: 10
            val startM = (officeSnap.child("startMinute").value as? Long)?.toInt() ?: 0
            val endH = (officeSnap.child("endHour").value as? Long)?.toInt() ?: 22
            val endM = (officeSnap.child("endMinute").value as? Long)?.toInt() ?: 0
            val officeMins = ((endH * 60 + endM) - (startH * 60 + startM)).toDouble()

            db.getReference("employees").get().addOnSuccessListener { empSnap ->
                val summaries = mutableListOf<EmpSummary>()
                val total = empSnap.children.count { it.child("employeeName").value != null }
                if (total == 0) return@addOnSuccessListener
                var done = 0

                for (emp in empSnap.children) {
                    val name = emp.child("employeeName").value?.toString() ?: continue
                    val did = emp.key ?: continue
                    val base = (emp.child("salary").value as? Number)?.toDouble() ?: 0.0
                    val dailyRate = if (base > 0) base / 30.0 else 0.0
                    val hourlyRate = if (officeMins > 0) dailyRate / (officeMins / 60.0) else 0.0

                    db.getReference("attendance").child(did)
                        .orderByKey().startAt("${monthKey}-01").endAt("${monthKey}-31")
                        .get().addOnSuccessListener { attSnap ->
                            db.getReference("adminOverrides").child(did).get().addOnSuccessListener { overSnap ->
                                db.getReference("salaryPayments").child(did).child(monthKey).get()
                                    .addOnSuccessListener { paidSnap ->
                                        // Get ALL advances then filter by month in code (avoid index issue)
                                        db.getReference("salaryAdvances").child(did).get()
                                            .addOnSuccessListener { advSnap ->
                                                // Filter advances by month in code
                                                val advSnapFiltered = advSnap.children.filter {
                                                    it.child("month").value?.toString() == monthKey
                                                }

                                                val cal = Calendar.getInstance().also { it.set(selectedYear, selectedMonth, 1) }
                                                val totalDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                                                val now = Calendar.getInstance()
                                                val isCurrentMonth = selectedYear == now.get(Calendar.YEAR) && selectedMonth == now.get(Calendar.MONTH)
                                                val daysToCount = if (isCurrentMonth) now.get(Calendar.DAY_OF_MONTH) else totalDays

                                                val recordMap = attSnap.children.associateBy { it.key ?: "" }
                                                val presentDates = mutableSetOf<String>()
                                                var deductMins = 0L

                                                for (d in 1..totalDays) {
                                                    val dk = "${monthKey}-${String.format(Locale.getDefault(), "%02d", d)}"
                                                    val rec = recordMap[dk]
                                                    val over = overSnap.child(dk)
                                                    // FIX (root cause of wrong Total Payable/Deductions/Unpaid):
                                                    // use readDayAttendance() so both the OLD flat structure and
                                                    // the NEW sessions/ structure are read correctly, instead of
                                                    // only the flat checkInTime field.
                                                    val dayAtt = rec?.let { readDayAttendance(it) }
                                                    val ci = dayAtt?.checkIn ?: ""
                                                    val ciTs = dayAtt?.ciTs ?: 0L
                                                    val coTs = dayAtt?.coTs ?: 0L
                                                    val waive = over.child("waiveDeduction").value as? Boolean ?: false
                                                    val relief = over.child("fullRelief").value as? Boolean ?: false

                                                    if (ci.isNotEmpty()) {
                                                        presentDates.add(dk)
                                                        if (!waive && !relief) {
                                                            if (ciTs > 0) {
                                                                val oStart = Calendar.getInstance().also { it.timeInMillis = ciTs; it.set(Calendar.HOUR_OF_DAY, startH); it.set(Calendar.MINUTE, startM); it.set(Calendar.SECOND, 0) }.timeInMillis
                                                                if (ciTs > oStart) deductMins += (ciTs - oStart) / 60000
                                                            }
                                                            if (coTs > 0) {
                                                                val oEnd = Calendar.getInstance().also { it.timeInMillis = coTs; it.set(Calendar.HOUR_OF_DAY, endH); it.set(Calendar.MINUTE, endM); it.set(Calendar.SECOND, 0) }.timeInMillis
                                                                if (coTs < oEnd) deductMins += (oEnd - coTs) / 60000
                                                            }
                                                        }
                                                    } else if (!relief && d < daysToCount && !waive) {
                                                        deductMins += officeMins.toLong()
                                                    } else if (relief) presentDates.add(dk)
                                                }

                                                val deductAmt = (deductMins / 60.0) * hourlyRate
                                                val netSalary = (base - deductAmt).coerceAtLeast(0.0)
                                                val isPaid = paidSnap.child("paid").value as? Boolean ?: false
                                                val paidAmt = (paidSnap.child("amount").value as? Number)?.toDouble() ?: 0.0
                                                var advTotal = 0.0
                                                for (adv in advSnapFiltered) {
                                                    advTotal += (adv.child("amount").value as? Number)?.toDouble() ?: 0.0
                                                }

                                                summaries.add(EmpSummary(name, did, base, netSalary, deductAmt, isPaid, paidAmt, advTotal))
                                                done++
                                                if (done >= total) {
                                                    allSummaries = summaries.sortedBy { it.name }
                                                    runOnUiThread { renderSummaries(allSummaries) }
                                                }
                                            }.addOnFailureListener {
                                                summaries.add(EmpSummary(name, did, base, 0.0, 0.0, false, 0.0, 0.0))
                                                done++
                                                if (done >= total) {
                                                    allSummaries = summaries.sortedBy { it.name }
                                                    runOnUiThread { renderSummaries(allSummaries) }
                                                }
                                            }
                                    }.addOnFailureListener {
                                        summaries.add(EmpSummary(name, did, base, 0.0, 0.0, false, 0.0, 0.0))
                                        done++; if (done >= total) { allSummaries = summaries.sortedBy { it.name }; runOnUiThread { renderSummaries(allSummaries) } }
                                    }
                            }.addOnFailureListener {
                                summaries.add(EmpSummary(name, did, base, 0.0, 0.0, false, 0.0, 0.0))
                                done++; if (done >= total) { allSummaries = summaries.sortedBy { it.name }; runOnUiThread { renderSummaries(allSummaries) } }
                            }
                        }.addOnFailureListener {
                            summaries.add(EmpSummary(name, did, base, 0.0, 0.0, false, 0.0, 0.0))
                            done++; if (done >= total) { allSummaries = summaries.sortedBy { it.name }; runOnUiThread { renderSummaries(allSummaries) } }
                        }
                }
            }
        }
    }

    private fun renderSummaries(summaries: List<EmpSummary>) {
        val dp = resources.displayMetrics.density
        var totalPayable = 0.0
        var totalDeductions = 0.0
        var totalPaid = 0.0
        var totalBase = 0.0

        summaries.forEach { s ->
            totalPayable += s.netSalary
            totalDeductions += s.deductions
            totalBase += s.baseSalary
            if (s.paid) totalPaid += s.paidAmount
        }
        val totalUnpaid = (totalPayable - totalPaid).coerceAtLeast(0.0)

        tvTotalPayable.text = "Rs ${"%,.0f".format(totalPayable)}"
        tvTotalDeductions.text = "Rs ${"%,.0f".format(totalDeductions)}"
        tvTotalPaid.text = "Rs ${"%,.0f".format(totalPaid)}"
        tvTotalUnpaid.text = "Rs ${"%,.0f".format(totalUnpaid)}"
        tvBaseSalaries.text = "Rs ${"%,.0f".format(totalBase)}"
        tvSavingDeductions.text = "- Rs ${"%,.0f".format(totalDeductions)}"
        tvAdminSaving.text = "Rs ${"%,.0f".format(totalDeductions)}"

        // Employee rows
        employeeListContainer.removeAllViews()
        summaries.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (i % 2 == 0) Color.parseColor("#FAFAFA") else Color.WHITE)
                setPadding(0, px(12,dp), 0, px(12,dp))
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(tv(s.name, 14f, Color.parseColor("#111111"), bold = true))
            info.addView(tv("Net: Rs ${"%,.0f".format(s.netSalary)} · Deduct: Rs ${"%,.0f".format(s.deductions)}", 11f, Color.parseColor("#555555")).also {
                it.layoutParams = llp().also { m -> m.topMargin = px(2,dp) }
            })
            if (s.advanceTotal > 0) {
                info.addView(tv("Advance: Rs ${"%,.0f".format(s.advanceTotal)}", 10f, Color.parseColor("#1565C0")).also {
                    it.layoutParams = llp().also { m -> m.topMargin = px(1,dp) }
                })
            }

            val badge = tv(if (s.paid) "Paid ✅" else "Unpaid", 10f,
                if (s.paid) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"),
                bold = true
            ).also {
                it.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*dp
                    setColor(if (s.paid) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE"))
                }
                it.setPadding(px(8,dp), px(3,dp), px(8,dp), px(3,dp))
            }

            row.addView(info)
            row.addView(badge)
            employeeListContainer.addView(row)
            employeeListContainer.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                layoutParams = llp().also { it.height = 1 }
            })
        }
    }

    private fun showEmployeeDetail(type: String) {
        val filtered = if (type == "paid")
            allSummaries.filter { it.paid }
        else
            allSummaries.filter { !it.paid }

        val dp = resources.displayMetrics.density
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8,dp), px(8,dp), px(8,dp), px(8,dp))
        }
        scroll.addView(container)

        if (filtered.isEmpty()) {
            container.addView(tv("No employees found.", 13f, Color.parseColor("#9E9E9E")).also {
                it.gravity = Gravity.CENTER; it.setPadding(0, px(20,dp), 0, px(20,dp))
            })
        } else {
            val monthKey = getMonthKey()
            filtered.forEach { s ->
                // Load payment date from Firebase
                db.getReference("salaryPayments").child(s.deviceId).child(monthKey).get()
                    .addOnSuccessListener { paidSnap ->
                        val paidAt = (paidSnap.child("paidAt").value as? Long) ?: 0L
                        val paidNote = paidSnap.child("note").value?.toString() ?: ""
                        val remaining = (s.netSalary - s.paidAmount - s.advanceTotal).coerceAtLeast(0.0)

                        val empCard = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = 10f * dp
                                setColor(Color.WHITE)
                                setStroke(px(1,dp), Color.parseColor("#EEEEEE"))
                            }
                            setPadding(px(12,dp), px(12,dp), px(12,dp), px(12,dp))
                            layoutParams = llp().also { it.bottomMargin = px(8,dp) }
                        }

                        empCard.addView(tv(s.name, 14f, Color.parseColor("#111111"), bold = true).also {
                            it.layoutParams = llp().also { m -> m.bottomMargin = px(6,dp) }
                        })

                        // Date/Time row
                        if (s.paid && paidAt > 0) {
                            val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(paidAt))
                            val noteStr = if (paidNote.isNotEmpty()) " · $paidNote" else ""
                            empCard.addView(tv("Paid on: $dateStr$noteStr", 11f, Color.parseColor("#2E7D32")).also {
                                it.layoutParams = llp().also { m -> m.bottomMargin = px(8,dp) }
                            })
                        }

                        empCard.addView(View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")); layoutParams = llp().also { it.height = 1; it.bottomMargin = px(6,dp) } })

                        // Calculation rows
                        addCalcRow(empCard, "Net Salary", "Rs ${"%,.0f".format(s.netSalary)}", "#111111", dp)
                        if (s.paid) addCalcRow(empCard, "Amount Paid", "- Rs ${"%,.0f".format(s.paidAmount)}", "#C62828", dp)
                        if (s.advanceTotal > 0) addCalcRow(empCard, "Advance Taken", "- Rs ${"%,.0f".format(s.advanceTotal)}", "#E65100", dp)

                        empCard.addView(View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")); layoutParams = llp().also { it.height = 1; it.topMargin = px(4,dp); it.bottomMargin = px(4,dp) } })

                        val remainColor = if (remaining > 0) "#C62828" else "#2E7D32"
                        val remainLabel = if (remaining > 0) "Remaining" else "Fully Paid ✅"
                        addCalcRow(empCard, remainLabel, "Rs ${"%,.0f".format(remaining)}", remainColor, dp, bold = true)

                        runOnUiThread { container.addView(empCard) }
                    }
            }
        }

        val title = if (type == "paid")
            "Paid Employees (${filtered.size})"
        else
            "Unpaid Employees (${filtered.size})"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun addCalcRow(container: LinearLayout, label: String, value: String, valColor: String, dp: Float, bold: Boolean = false) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(4,dp), 0, px(4,dp))
        }
        row.addView(tv(label, 12f, Color.parseColor("#555555")).also {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(tv(value, 12f, Color.parseColor(valColor), bold = bold))
        container.addView(row)
    }

    // ─── MONTH PICKER ───

    private fun showMonthPicker() {
        val dp = resources.displayMetrics.density
        val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
        val years = (2024..2027).map { it.toString() }.toTypedArray()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(px(24,dp), px(16,dp), px(24,dp), px(8,dp))
        }
        val mp = NumberPicker(this).apply { minValue = 0; maxValue = 11; displayedValues = months; value = selectedMonth }
        val yp = NumberPicker(this).apply {
            minValue = 0; maxValue = years.size - 1; displayedValues = years
            value = years.indexOf(selectedYear.toString()).coerceAtLeast(0)
        }
        layout.addView(mp); layout.addView(yp)
        AlertDialog.Builder(this).setTitle("Select Month").setView(layout)
            .setPositiveButton("OK") { _, _ ->
                selectedMonth = mp.value
                selectedYear = years[yp.value].toInt()
                tvMonthBtn.text = getShortMonthLabel()
                allSummaries = emptyList()
                employeeListContainer.removeAllViews()
                loadAllData()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ─── HELPERS ───

    private fun getMonthKey() = String.format(Locale.getDefault(), "%04d-%02d", selectedYear, selectedMonth + 1)
    private fun getShortMonthLabel(): String {
        val cal = Calendar.getInstance().also { it.set(selectedYear, selectedMonth, 1) }
        return SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(cal.time)
    }

    private fun statCard(label: String, value: String, valColor: String, bgColor: String, borderColor: String, dp: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*dp; setColor(Color.parseColor(bgColor)); setStroke(px(1,dp), Color.parseColor(borderColor)) }
            setPadding(px(10,dp), px(12,dp), px(10,dp), px(8,dp))
            addView(tv(label, 11f, Color.parseColor("#444444")).also { it.gravity = Gravity.CENTER })
            addView(tv(value, 18f, Color.parseColor(valColor), bold = true).also { it.tag = "val"; it.gravity = Gravity.CENTER; it.layoutParams = llp().also { m -> m.topMargin = px(4,dp); m.bottomMargin = px(4,dp) } })
        }
    }
    private fun summaryRow(label: String, valColor: String, dp: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(6,dp), 0, px(6,dp))
        addView(tv(label, 13f, Color.parseColor("#333333")).also { it.tag = "label"; it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        addView(tv("Rs —", 12f, Color.parseColor(valColor), bold = true).also { it.tag = "val" })
    }
    private fun px(v: Int, dp: Float) = (v * dp).toInt()
    private fun llp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }
    private fun card(dp: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f*dp; setColor(Color.WHITE) }
        setPadding(px(14,dp), px(12,dp), px(14,dp), px(12,dp))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = px(10,dp) }
    }

    // FIX: Same root cause as SalaryHistoryActivity — this screen used to
    // read `checkInTime` directly off the date node, which is empty for the
    // newer `sessions/` based attendance records. That made present days
    // look absent, inflating deductions and shrinking Total Payable, which
    // in turn skewed Total Unpaid (Payable - Paid). This is the same
    // session-aware helper already proven correct in
    // BiometricAttendanceActivity.kt.
    private data class DayAttendance(
        val checkIn: String, val checkOut: String, val isLate: Boolean,
        val ciTs: Long, val coTs: Long, val hasOvertimeSession: Boolean
    )

    private fun readDayAttendance(daySnap: DataSnapshot): DayAttendance? {
        val sessSnap = daySnap.child("sessions")
        if (sessSnap.exists()) {
            val sessions = sessSnap.children.toList()
            val firstWithCheckIn = sessions.firstOrNull {
                (it.child("checkInTime").value?.toString() ?: "").isNotEmpty()
            } ?: return null
            val checkIn = firstWithCheckIn.child("checkInTime").value?.toString() ?: ""
            if (checkIn.isEmpty()) return null
            val lastWithCheckOut = sessions.lastOrNull {
                (it.child("checkOutTime").value?.toString() ?: "").isNotEmpty()
            }
            val checkOut = lastWithCheckOut?.child("checkOutTime")?.value?.toString() ?: ""
            val isLate = sessions.any { it.child("status").value?.toString() == "LATE" }
            val hasOT = sessions.any { it.child("status").value?.toString() == "OVERTIME" }
            val ciTs = (firstWithCheckIn.child("checkInTimestamp").value as? Long) ?: 0L
            val coTs = (lastWithCheckOut?.child("checkOutTimestamp")?.value as? Long) ?: 0L
            return DayAttendance(checkIn, checkOut, isLate, ciTs, coTs, hasOT)
        }
        // Legacy flat structure
        val checkIn = daySnap.child("checkInTime").value?.toString() ?: ""
        if (checkIn.isEmpty()) return null
        val checkOut = daySnap.child("checkOutTime").value?.toString() ?: ""
        val status = daySnap.child("status").value?.toString() ?: ""
        val ciTs = (daySnap.child("checkInTimestamp").value as? Long) ?: 0L
        val coTs = (daySnap.child("checkOutTimestamp").value as? Long) ?: 0L
        return DayAttendance(checkIn, checkOut, status == "LATE", ciTs, coTs, status == "OVERTIME")
    }
}
package com.example.eboneadminpanel

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class SalaryHistoryActivity : AppCompatActivity() {

    private val db = FirebaseDatabase.getInstance()
    private var deviceId = ""
    private var employeeName = ""
    private var baseSalary = 0.0
    private var selectedYear = Calendar.getInstance().get(Calendar.YEAR)
    private var selectedMonth = Calendar.getInstance().get(Calendar.MONTH)

    private val employeeMap = mutableMapOf<String, String>()  // name → deviceId
    private val salaryMap = mutableMapOf<String, Double>()    // deviceId → salary

    private lateinit var tvSelectedEmployee: TextView
    private lateinit var tvSelectedMonth: TextView
    private lateinit var tvNetSalary: TextView
    private lateinit var tvBaseSalary: TextView
    private lateinit var tvDeduction: TextView
    private lateinit var tvOvertimeBonus: TextView
    private lateinit var tvTotalPaid: TextView
    private lateinit var tvPaidStatus: TextView
    private lateinit var btnMarkPaid: Button
    private lateinit var breakdownContainer: LinearLayout
    private lateinit var employeeSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra("deviceId") ?: ""
        employeeName = intent.getStringExtra("employeeName") ?: ""
        baseSalary = intent.getDoubleExtra("baseSalary", 0.0)
        setContentView(buildLayout())
        loadEmployees()
    }

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6FA"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // ── Header ──
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
        titleBlock.addView(tv("Salary History", 16f, Color.WHITE, bold = true))
        titleBlock.addView(tv("Monthly salary breakdown", 12f, Color.parseColor("#B8C6DE")))
        header.addView(titleBlock)

        // All Employees Total Button
        Button(this).apply {
            text = "All Total"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(Color.parseColor("#1A3F7A"))
            }
            setPadding(px(10,dp), px(6,dp), px(10,dp), px(6,dp))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                startActivity(android.content.Intent(this@SalaryHistoryActivity, AllSalaryTotalActivity::class.java))
            }
        }.also { header.addView(it) }
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12,dp), px(12,dp), px(12,dp), px(12,dp))
        }
        scroll.addView(content)

        // ── Employee + Month Selector Card ──
        val selectorCard = card(dp)

        // Employee row
        selectorCard.addView(tv("Employee", 12f, Color.parseColor("#757575")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(4,dp) }
        })
        employeeSpinner = Spinner(this).apply {
            layoutParams = llp().also { it.bottomMargin = px(12,dp) }
        }
        employeeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val name = employeeSpinner.selectedItem?.toString() ?: return
                employeeName = name
                deviceId = employeeMap[name] ?: return
                baseSalary = salaryMap[deviceId] ?: 0.0
                loadMonthData()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        selectorCard.addView(employeeSpinner)

        // Month row - same label style as Employee, same width spinner style
        selectorCard.addView(tv("Month", 12f, Color.parseColor("#757575")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(4,dp) }
        })
        val monthSelector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4f * dp
                setColor(Color.WHITE)
                setStroke(px(1,dp), Color.parseColor("#CCCCCC"))
            }
            setPadding(px(8,dp), px(10,dp), px(8,dp), px(10,dp))
            layoutParams = llp()
            isClickable = true; isFocusable = true
            setOnClickListener { showMonthPicker() }
        }
        tvSelectedMonth = tv(getMonthLabel(), 14f, Color.parseColor("#333333")).also {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        monthSelector.addView(tvSelectedMonth)
        monthSelector.addView(tv("▾", 14f, Color.parseColor("#888888")))
        selectorCard.addView(monthSelector)
        content.addView(selectorCard)

        // ── Net Salary Card (Light) ──
        val netCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f*dp
                setColor(Color.WHITE)
                setStroke(px(1,dp), Color.parseColor("#E0E0E0"))
            }
            setPadding(px(20,dp), px(20,dp), px(20,dp), px(20,dp))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = px(10,dp) }
        }
        netCard.addView(tv("Net Salary This Month", 12f, Color.parseColor("#9E9E9E")).also { it.gravity = Gravity.CENTER })
        tvNetSalary = tv("Rs —", 34f, Color.parseColor("#1565C0"), bold = true).also {
            it.gravity = Gravity.CENTER
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { m -> m.topMargin = px(6,dp); m.bottomMargin = px(6,dp) }
        }
        netCard.addView(tvNetSalary)
        tvPaidStatus = tv("UNPAID", 11f, Color.parseColor("#C62828"), bold = true).also {
            it.gravity = Gravity.CENTER
            it.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f*dp
                setColor(Color.parseColor("#FFEBEE"))
            }
            it.setPadding(px(14,dp), px(4,dp), px(14,dp), px(4,dp))
        }
        netCard.addView(tvPaidStatus)

        content.addView(netCard)

        // ── Breakdown Card ──
        val breakCard = card(dp)
        breakCard.addView(tv("Breakdown", 14f, Color.parseColor("#111111"), bold = true).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(8,dp) }
        })
        breakCard.addView(View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")); layoutParams = llp().also { it.height = 1; it.bottomMargin = px(4,dp) } })
        breakdownContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        breakCard.addView(breakdownContainer)
        content.addView(breakCard)

        // ── Total Paid Card ──
        val totalCard = card(dp)
        val totalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        totalRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(tv("Total Paid to $employeeName", 13f, Color.parseColor("#555555")).also {
                it.id = android.R.id.text1
            })
            addView(tv("All months combined", 11f, Color.parseColor("#9E9E9E")))
        })
        tvTotalPaid = tv("Rs —", 16f, Color.parseColor("#2E7D32"), bold = true)
        totalRow.addView(tvTotalPaid)
        totalCard.addView(totalRow)
        content.addView(totalCard)

        // Mark as Paid — full width pill, same style as UNPAID, below Total Paid
        btnMarkPaid = Button(this).apply {
            text = "Mark as Paid"
            textSize = 11f
            setTextColor(Color.parseColor("#2E7D32"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * dp
                setColor(Color.parseColor("#E8F5E9"))
            }
            setPadding(px(14,dp), px(4,dp), px(14,dp), px(4,dp))
            minHeight = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = px(8,dp) }
            setOnClickListener { showMarkPaidDialog() }
        }
        content.addView(btnMarkPaid)

        // Advance Salary Button
        val advanceBtn = Button(this).apply {
            text = "Pay Advance Salary"
            textSize = 11f
            setTextColor(Color.parseColor("#1565C0"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * dp
                setColor(Color.parseColor("#E3F2FD"))
            }
            setPadding(px(14,dp), px(4,dp), px(14,dp), px(4,dp))
            minHeight = 0
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = px(8,dp) }
            setOnClickListener { showAdvanceDialog() }
        }
        content.addView(advanceBtn)

        // Advance History Card
        val advanceCard = card(dp).also { it.layoutParams = llp().also { m -> m.topMargin = px(8,dp) } }
        advanceCard.addView(tv("Advance Payments", 14f, Color.parseColor("#111111"), bold = true).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(8,dp) }
        })
        val advanceContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; id = android.R.id.list }
        advanceCard.addView(advanceContainer)
        content.addView(advanceCard)
        loadAdvanceHistory(advanceContainer)

        // Mark as Paid moved inside net card above

        root.addView(scroll)
        return root
    }

    // ─── MONTH PICKER ───

    private fun showMonthPicker() {
        val months = arrayOf("January","February","March","April","May","June",
            "July","August","September","October","November","December")
        val years = (2024..2030).map { it.toString() }.toTypedArray()
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(px(24,dp), px(16,dp), px(24,dp), px(8,dp))
        }
        val monthPicker = NumberPicker(this).apply {
            minValue = 0; maxValue = 11
            displayedValues = months
            value = selectedMonth
        }
        val yearPicker = NumberPicker(this).apply {
            minValue = 0; maxValue = years.size - 1
            displayedValues = years
            value = years.indexOf(selectedYear.toString()).coerceAtLeast(0)
        }
        layout.addView(monthPicker)
        layout.addView(yearPicker)
        AlertDialog.Builder(this)
            .setTitle("Select Month")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                selectedMonth = monthPicker.value
                selectedYear = years[yearPicker.value].toInt()
                tvSelectedMonth.text = getMonthLabel()
                loadMonthData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── LOAD DATA ───

    private fun loadEmployees() {
        db.getReference("employees").get().addOnSuccessListener { snap ->
            employeeMap.clear()
            for (emp in snap.children) {
                val name = emp.child("employeeName").value?.toString() ?: continue
                val did = emp.key ?: continue
                val sal = (emp.child("salary").value as? Number)?.toDouble() ?: 0.0
                employeeMap[name] = did
                salaryMap[did] = sal
            }
            val names = employeeMap.keys.sorted()
            if (names.isEmpty()) return@addOnSuccessListener
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            employeeSpinner.adapter = adapter
            // Pre-select passed employee
            val idx = names.indexOf(employeeName)
            if (idx >= 0) employeeSpinner.setSelection(idx)
        }
    }

    private fun loadMonthData() {
        if (deviceId.isEmpty()) return
        baseSalary = salaryMap[deviceId] ?: 0.0
        val monthKey = String.format(Locale.getDefault(), "%04d-%02d", selectedYear, selectedMonth + 1)

        db.getReference("officeSettings").get().addOnSuccessListener { officeSnap ->
            val startH = (officeSnap.child("startHour").value as? Long)?.toInt() ?: 10
            val startM = (officeSnap.child("startMinute").value as? Long)?.toInt() ?: 0
            val endH = (officeSnap.child("endHour").value as? Long)?.toInt() ?: 22
            val endM = (officeSnap.child("endMinute").value as? Long)?.toInt() ?: 0
            val officeMins = ((endH * 60 + endM) - (startH * 60 + startM)).toDouble()
            val dailyRate = if (baseSalary > 0) baseSalary / 30.0 else 0.0
            val hourlyRate = if (officeMins > 0) dailyRate / (officeMins / 60.0) else 0.0

            db.getReference("adminOverrides").child(deviceId).get().addOnSuccessListener { overSnap ->
                db.getReference("attendance").child(deviceId)
                    .orderByKey().startAt("${monthKey}-01").endAt("${monthKey}-31")
                    .get().addOnSuccessListener { attSnap ->

                        val cal = Calendar.getInstance().also {
                            it.set(selectedYear, selectedMonth, 1)
                        }
                        val totalDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        val now = Calendar.getInstance()
                        val isCurrentMonth = selectedYear == now.get(Calendar.YEAR) &&
                                selectedMonth == now.get(Calendar.MONTH)
                        val daysToCount = if (isCurrentMonth) now.get(Calendar.DAY_OF_MONTH) else totalDays

                        val recordMap = attSnap.children.associateBy { it.key ?: "" }
                        val presentDates = mutableSetOf<String>()
                        var deductMins = 0L; var otMins = 0L; var late = 0; var absent = 0

                        for (d in 1..totalDays) {
                            val dk = "${monthKey}-${String.format(Locale.getDefault(), "%02d", d)}"
                            val rec = recordMap[dk]
                            val over = overSnap.child(dk)
                            val ci = rec?.child("checkInTime")?.value?.toString() ?: ""
                            val ciTs = (rec?.child("checkInTimestamp")?.value as? Long) ?: 0L
                            val coTs = (rec?.child("checkOutTimestamp")?.value as? Long) ?: 0L
                            val st = rec?.child("status")?.value?.toString() ?: ""
                            val waive = over.child("waiveDeduction").value as? Boolean ?: false
                            val relief = over.child("fullRelief").value as? Boolean ?: false
                            val otApproved = over.child("overtimeApproved").value as? Boolean ?: false
                            val otHours = (over.child("overtimeHours").value as? Number)?.toDouble() ?: 0.0

                            if (ci.isNotEmpty()) {
                                presentDates.add(dk)
                                if (st == "LATE") late++
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
                                if (d <= daysToCount) absent++
                                deductMins += officeMins.toLong()
                            } else if (relief) presentDates.add(dk)
                            if (otApproved && otHours > 0) otMins += (otHours * 60).toLong()
                        }

                        val present = presentDates.size
                        val deductAmt = (deductMins / 60.0) * hourlyRate
                        val otAmt = (otMins / 60.0) * hourlyRate
                        val netSalary = (baseSalary - deductAmt + otAmt).coerceAtLeast(0.0)

                        // Update UI
                        tvNetSalary.text = "Rs ${"%,.0f".format(netSalary)}"

                        val dp2 = resources.displayMetrics.density
                        breakdownContainer.removeAllViews()
                        addBreakRow("Base Salary", "Rs ${"%,.0f".format(baseSalary)}", "#111111", dp2)
                        addBreakRow("Present Days", "$present days", "#2E7D32", dp2)
                        addBreakRow("Absent Days", "$absent days", "#C62828", dp2)
                        addBreakRow("Late Arrivals", "$late times", "#E65100", dp2)
                        addBreakRow("Late + Early Deduction", "- Rs ${"%,.0f".format(deductAmt)}", "#C62828", dp2)
                        if (otAmt > 0) addBreakRow("Overtime Bonus", "+ Rs ${"%,.0f".format(otAmt)}", "#2E7D32", dp2)
                        breakdownContainer.addView(View(this).apply { setBackgroundColor(Color.parseColor("#EEEEEE")); layoutParams = llp().also { it.height = 1; it.topMargin = px(4,dp2); it.bottomMargin = px(8,dp2) } })
                        val netRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(4,dp2), 0, px(4,dp2)) }
                        netRow.addView(tv("Net Salary", 14f, Color.parseColor("#111111"), bold = true).also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                        netRow.addView(tv("Rs ${"%,.0f".format(netSalary)}", 14f, Color.parseColor("#2E7D32"), bold = true))
                        breakdownContainer.addView(netRow)

                        // Check paid status
                        db.getReference("salaryPayments").child(deviceId).child(monthKey).get()
                            .addOnSuccessListener { paidSnap ->
                                val isPaid = paidSnap.child("paid").value as? Boolean ?: false
                                val paidAmt = (paidSnap.child("amount").value as? Number)?.toDouble() ?: 0.0
                                tvPaidStatus.text = if (isPaid) "PAID — Rs ${"%,.0f".format(paidAmt)}" else "UNPAID"
                                tvPaidStatus.setTextColor(if (isPaid) Color.parseColor("#69F0AE") else Color.parseColor("#FF8A80"))
                                btnMarkPaid.text = if (isPaid) "Update Payment" else "Mark as Paid"
                            }

                        // Total paid all months
                        db.getReference("salaryPayments").child(deviceId).get()
                            .addOnSuccessListener { allPaid ->
                                var total = 0.0
                                for (m in allPaid.children) {
                                    val paid = m.child("paid").value as? Boolean ?: false
                                    if (paid) total += (m.child("amount").value as? Number)?.toDouble() ?: 0.0
                                }
                                tvTotalPaid.text = "Rs ${"%,.0f".format(total)}"
                            }
                    }
            }
        }
    }

    private fun addBreakRow(label: String, value: String, valColor: String, dp: Float) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(6,dp), 0, px(6,dp))
        }
        row.addView(tv(label, 13f, Color.parseColor("#555555")).also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        row.addView(tv(value, 13f, Color.parseColor(valColor), bold = true))
        breakdownContainer.addView(row)
    }

    // ─── MARK PAID ───

    private fun showMarkPaidDialog() {
        if (deviceId.isEmpty()) return
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24,dp), px(16,dp), px(24,dp), px(8,dp))
        }
        layout.addView(tv("Amount Paid (Rs)", 12f, Color.parseColor("#757575")).also { it.layoutParams = llp().also { m -> m.bottomMargin = px(4,dp) } })
        val etAmount = EditText(this).apply {
            hint = "e.g. 24500"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = llp().also { it.bottomMargin = px(10,dp) }
        }
        layout.addView(etAmount)
        layout.addView(tv("Note (optional)", 12f, Color.parseColor("#757575")).also { it.layoutParams = llp().also { m -> m.bottomMargin = px(4,dp) } })
        val etNote = EditText(this).apply { hint = "e.g. Bank transfer, Cash"; inputType = InputType.TYPE_CLASS_TEXT; layoutParams = llp() }
        layout.addView(etNote)

        val monthKey = String.format(Locale.getDefault(), "%04d-%02d", selectedYear, selectedMonth + 1)
        AlertDialog.Builder(this)
            .setTitle("Mark Salary — ${getMonthLabel()}")
            .setView(layout)
            .setPositiveButton("Mark Paid") { _, _ ->
                val amount = etAmount.text.toString().toDoubleOrNull()
                if (amount == null || amount <= 0) { showMsg("Enter valid amount."); return@setPositiveButton }
                val data = mapOf("paid" to true, "amount" to amount, "month" to monthKey,
                    "note" to etNote.text.toString().trim(), "paidAt" to System.currentTimeMillis(),
                    "employeeName" to employeeName)
                db.getReference("salaryPayments").child(deviceId).child(monthKey).setValue(data)
                    .addOnSuccessListener { loadMonthData() }
                    .addOnFailureListener { e -> showMsg("Failed: ${e.message}") }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ─── HELPERS ───

    private fun getMonthLabel(): String {
        val cal = Calendar.getInstance().also { it.set(selectedYear, selectedMonth, 1) }
        return SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
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
    private fun showAdvanceDialog() {
        if (deviceId.isEmpty()) { showMsg("Select an employee first."); return }
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24,dp), px(16,dp), px(24,dp), px(8,dp))
        }

        layout.addView(tv("Amount (Rs)", 12f, Color.parseColor("#757575")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(4,dp) }
        })
        val etAmount = EditText(this).apply {
            hint = "e.g. 5000"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = llp().also { it.bottomMargin = px(12,dp) }
        }
        layout.addView(etAmount)

        layout.addView(tv("Payment Method", 12f, Color.parseColor("#757575")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(4,dp) }
        })
        val methods = arrayOf("Cash", "Online Transfer", "Bank Transfer", "Easypaisa", "JazzCash")
        val methodSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@SalaryHistoryActivity,
                android.R.layout.simple_spinner_dropdown_item, methods)
            layoutParams = llp().also { it.bottomMargin = px(12,dp) }
        }
        layout.addView(methodSpinner)

        layout.addView(tv("Note (optional)", 12f, Color.parseColor("#757575")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(4,dp) }
        })
        val etNote = EditText(this).apply {
            hint = "e.g. Emergency request"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = llp()
        }
        layout.addView(etNote)

        AlertDialog.Builder(this)
            .setTitle("Pay Advance — $employeeName")
            .setView(layout)
            .setPositiveButton("Pay Now") { _, _ ->
                val amount = etAmount.text.toString().toDoubleOrNull()
                if (amount == null || amount <= 0) { showMsg("Enter valid amount."); return@setPositiveButton }
                val method = methodSpinner.selectedItem.toString()
                val note = etNote.text.toString().trim()
                val now = System.currentTimeMillis()
                val data = mapOf(
                    "amount" to amount,
                    "method" to method,
                    "note" to note,
                    "paidAt" to now,
                    "employeeName" to employeeName,
                    "month" to String.format(java.util.Locale.getDefault(), "%04d-%02d", selectedYear, selectedMonth + 1)
                )
                db.getReference("salaryAdvances").child(deviceId).push().setValue(data)
                    .addOnSuccessListener {
                        showMsg("Advance paid: Rs ${"%,.0f".format(amount)} via $method")
                        // Reload advance history
                        val container = findViewById<LinearLayout>(android.R.id.list)
                        if (container != null) loadAdvanceHistory(container)
                    }
                    .addOnFailureListener { e -> showMsg("Failed: ${e.message}") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadAdvanceHistory(container: LinearLayout) {
        if (deviceId.isEmpty()) return
        val monthKey = String.format(java.util.Locale.getDefault(), "%04d-%02d", selectedYear, selectedMonth + 1)
        val dp = resources.displayMetrics.density

        db.getReference("salaryAdvances").child(deviceId)
            .orderByChild("month").equalTo(monthKey)
            .get()
            .addOnSuccessListener { snap ->
                container.removeAllViews()
                if (!snap.exists() || snap.childrenCount == 0L) {
                    container.addView(tv("No advances this month.", 12f, Color.parseColor("#9E9E9E")).also {
                        it.gravity = android.view.Gravity.CENTER
                        it.setPadding(0, px(8,dp), 0, px(8,dp))
                    })
                    return@addOnSuccessListener
                }

                var rowCount = 0
                for (rec in snap.children.sortedByDescending { (it.child("paidAt").value as? Long) ?: 0L }) {
                    val amount = (rec.child("amount").value as? Number)?.toDouble() ?: 0.0
                    val method = rec.child("method").value?.toString() ?: ""
                    val note = rec.child("note").value?.toString() ?: ""
                    val paidAt = (rec.child("paidAt").value as? Long) ?: 0L
                    val dateStr = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(paidAt))

                    rowCount++
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setBackgroundColor(if (rowCount % 2 == 0) Color.parseColor("#FAFAFA") else Color.WHITE)
                        setPadding(0, px(10,dp), 0, px(10,dp))
                    }

                    val infoBlock = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    infoBlock.addView(tv("Rs ${"%,.0f".format(amount)} — $method", 13f, Color.parseColor("#111111"), bold = true))
                    infoBlock.addView(tv(dateStr, 11f, Color.parseColor("#757575")).also {
                        it.layoutParams = llp().also { m -> m.topMargin = px(2,dp) }
                    })
                    if (note.isNotEmpty()) {
                        infoBlock.addView(tv(note, 11f, Color.parseColor("#9E9E9E")).also {
                            it.layoutParams = llp().also { m -> m.topMargin = px(1,dp) }
                        })
                    }

                    val badge = tv("✅ Paid", 10f, Color.parseColor("#2E7D32"), bold = true).also {
                        it.background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 10f * dp
                            setColor(Color.parseColor("#E8F5E9"))
                        }
                        it.setPadding(px(8,dp), px(3,dp), px(8,dp), px(3,dp))
                    }

                    row.addView(infoBlock)
                    row.addView(badge)
                    container.addView(row)
                    container.addView(View(this).apply {
                        setBackgroundColor(Color.parseColor("#EEEEEE"))
                        layoutParams = llp().also { it.height = 1 }
                    })
                }
            }
    }

    private fun showAllEmployeesTotalDialog() {
        val dp = resources.displayMetrics.density
        val scroll = android.widget.ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8,dp), px(8,dp), px(8,dp), px(8,dp))
        }
        scroll.addView(container)

        db.getReference("salaryPayments").get().addOnSuccessListener { snap ->
            var grandTotal = 0.0
            val empTotals = mutableMapOf<String, Double>()

            for (emp in snap.children) {
                val empDeviceId = emp.key ?: continue
                var empTotal = 0.0
                var empName = ""
                for (month in emp.children) {
                    val paid = month.child("paid").value as? Boolean ?: false
                    val amt = (month.child("amount").value as? Number)?.toDouble() ?: 0.0
                    val name = month.child("employeeName").value?.toString() ?: empDeviceId
                    if (paid) { empTotal += amt; grandTotal += amt }
                    if (empName.isEmpty()) empName = name
                }
                if (empTotal > 0) empTotals[empName] = empTotal
            }

            // Grand total card
            val totalCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f * dp
                    setColor(Color.parseColor("#E8F5E9"))
                }
                setPadding(px(16,dp), px(14,dp), px(16,dp), px(14,dp))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = px(12,dp) }
            }
            totalCard.addView(tv("Grand Total Paid (All Employees)", 12f, Color.parseColor("#555555")).also { it.gravity = android.view.Gravity.CENTER })
            totalCard.addView(tv("Rs ${"%,.0f".format(grandTotal)}", 26f, Color.parseColor("#2E7D32"), bold = true).also { it.gravity = android.view.Gravity.CENTER })
            container.addView(totalCard)

            // Per employee
            container.addView(tv("Per Employee", 13f, Color.parseColor("#555555"), bold = true).also {
                it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { m -> m.bottomMargin = px(8,dp) }
            })

            empTotals.entries.sortedByDescending { it.value }.forEachIndexed { i, entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundColor(if (i % 2 == 0) Color.parseColor("#FAFAFA") else Color.WHITE)
                    setPadding(0, px(10,dp), 0, px(10,dp))
                }
                row.addView(tv(entry.key, 13f, Color.parseColor("#111111"), bold = true).also {
                    it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(tv("Rs ${"%,.0f".format(entry.value)}", 13f, Color.parseColor("#2E7D32"), bold = true))
                container.addView(row)
                container.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#EEEEEE"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                })
            }

            if (empTotals.isEmpty()) {
                container.addView(tv("No salary payments recorded yet.", 13f, Color.parseColor("#9E9E9E")).also {
                    it.gravity = android.view.Gravity.CENTER
                    it.setPadding(0, px(20,dp), 0, px(20,dp))
                })
            }

            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Total Salaries Paid")
                    .setView(scroll)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    private fun showMsg(msg: String) { AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show() }
}
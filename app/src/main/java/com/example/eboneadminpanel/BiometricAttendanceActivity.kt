package com.example.eboneadminpanel

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

class BiometricAttendanceActivity : AppCompatActivity() {

    private val db = FirebaseDatabase.getInstance()
    private var selectedDeviceId = ""
    private var selectedEmployee = ""
    private var currentMonthOffset = 0
    private var baseSalary = 0.0
    private var officeStartHour = 10
    private var officeStartMinute = 0
    private var officeEndHour = 22
    private var officeEndMinute = 0

    private lateinit var tvMonth: TextView
    private lateinit var tvPresent: TextView
    private lateinit var tvAbsent: TextView
    private lateinit var tvLate: TextView
    private lateinit var tvScore: TextView
    private lateinit var tvBaseSalary: TextView
    private lateinit var tvDeduction: TextView
    private lateinit var tvOvertime: TextView
    private lateinit var tvNetSalary: TextView
    private lateinit var tvOfficeHours: TextView
    private lateinit var logContainer: LinearLayout
    private lateinit var employeeSpinner: Spinner
    private val employeeMap = mutableMapOf<String, String>()
    private val salaryMap = mutableMapOf<String, Double>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        loadOfficeSettings()
        loadEmployees()
    }

    // ─────────────── LAYOUT ───────────────

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6FA"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0D2E5C"))
            setPadding(px(16, dp), px(48, dp), px(16, dp), px(16, dp))
        }
        ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            background = null
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(px(28, dp), px(28, dp))
            setOnClickListener { finish() }
        }.also { header.addView(it) }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.marginStart = px(12, dp) }
        }
        titleBlock.addView(tv("Bio Attendance", 16f, Color.WHITE, bold = true))
        // tvMonth assigned below in month picker row
        tvMonth = tv("", 1f, Color.TRANSPARENT) // placeholder
        header.addView(titleBlock)

        val geoBtn = Button(this).apply {
            text = "Geofence"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(Color.parseColor("#1A3F7A"))
            }
            setPadding(px(10, dp), px(6, dp), px(10, dp), px(6, dp))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = px(8, dp) }
        }
        geoBtn.setOnClickListener {
            startActivity(android.content.Intent(this, OfficeGeofenceActivity::class.java))
        }
        header.addView(geoBtn)

        val historyBtn = Button(this).apply {
            text = "History"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(Color.parseColor("#2E5C1A"))
            }
            setPadding(px(10, dp), px(6, dp), px(10, dp), px(6, dp))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = px(6, dp) }
        }
        historyBtn.setOnClickListener {
            if (selectedDeviceId.isEmpty()) {
                showMsg("Select an employee first.")
            } else {
                val intent = android.content.Intent(this, SalaryHistoryActivity::class.java)
                intent.putExtra("deviceId", selectedDeviceId)
                intent.putExtra("employeeName", selectedEmployee)
                intent.putExtra("baseSalary", baseSalary)
                startActivity(intent)
            }
        }
        header.addView(historyBtn)

        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12, dp), px(12, dp), px(12, dp), px(12, dp))
        }
        scroll.addView(content)

        // ── Employee + Month Selector ──
        val selectorCard = card(dp)
        selectorCard.addView(
            tv("Select Employee", 12f, Color.parseColor("#757575")).also {
                it.layoutParams = llp().also { m -> m.bottomMargin = px(4, dp) }
            }
        )
        employeeSpinner = Spinner(this).apply {
            layoutParams = llp().also { it.bottomMargin = px(10, dp) }
        }
        employeeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val name = employeeSpinner.selectedItem?.toString() ?: return
                if (name == "── All Employees ──") {
                    selectedEmployee = ""
                    selectedDeviceId = ""
                    loadAllEmployeesLog()
                    return
                }
                selectedEmployee = name
                selectedDeviceId = employeeMap[name] ?: return
                baseSalary = salaryMap[selectedDeviceId] ?: 0.0
                tvBaseSalary.text = if (baseSalary > 0) "Rs ${"%,.0f".format(baseSalary)}" else "Not Set"
                loadAttendanceReport()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        selectorCard.addView(employeeSpinner)

        val monthRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setOnClickListener { showMonthPickerDialog() }
        }
        monthRow.addView(tv("Month", 12f, Color.parseColor("#757575")).also {
            it.layoutParams = LinearLayout.LayoutParams(px(60, dp), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        tvMonth = tv(getMonthLabel(), 14f, Color.parseColor("#1565C0"), bold = true).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        monthRow.addView(tvMonth)
        monthRow.addView(tv("▾", 16f, Color.parseColor("#1565C0")))
        selectorCard.addView(monthRow)
        content.addView(selectorCard)

        // ── Stats ──
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = px(10, dp) }
        }
        tvPresent = tv("—", 22f, Color.parseColor("#2E7D32"), bold = true).also { it.gravity = Gravity.CENTER }
        tvAbsent = tv("—", 22f, Color.parseColor("#C62828"), bold = true).also { it.gravity = Gravity.CENTER }
        tvLate = tv("—", 22f, Color.parseColor("#E65100"), bold = true).also { it.gravity = Gravity.CENTER }
        tvScore = tv("—", 22f, Color.parseColor("#1565C0"), bold = true).also { it.gravity = Gravity.CENTER }
        statsRow.addView(statBox(tvPresent, "Present", dp).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { m -> m.marginEnd = px(4, dp) }
        })
        statsRow.addView(statBox(tvAbsent, "Absent", dp).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { m -> m.marginEnd = px(4, dp) }
        })
        statsRow.addView(statBox(tvLate, "Late", dp).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { m -> m.marginEnd = px(4, dp) }
        })
        statsRow.addView(statBox(tvScore, "Score", dp).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        content.addView(statsRow)

        // ── Salary Card ──
        val salaryCard = card(dp)
        // Salary title — tap to set salary
        val salaryTitle = tv("Salary Breakdown", 14f, Color.parseColor("#111111"), bold = true).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(8, dp) }
            it.isClickable = true
            it.isFocusable = true
            it.setOnClickListener { showSetSalaryDialog() }
        }
        salaryCard.addView(salaryTitle)
        salaryCard.addView(divider(dp))

        // Office Hours row — just title + arrow, no time shown
        val officeHoursRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setPadding(0, px(7, dp), 0, px(7, dp))
            layoutParams = llp().also { it.bottomMargin = px(2, dp) }
            setOnClickListener { showEditOfficeHoursDialog() }
        }
        officeHoursRow.addView(tv("Office Hours", 14f, Color.parseColor("#111111"), bold = true).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        tvOfficeHours = tv("▾", 16f, Color.parseColor("#1565C0"), bold = true)
        officeHoursRow.addView(tvOfficeHours)
        salaryCard.addView(officeHoursRow)
        salaryCard.addView(divider(dp))

        val baseSalaryRow = salaryRow("Base Salary (Monthly)", Color.parseColor("#111111"), dp).also {
            tvBaseSalary = it.findViewWithTag("val")
            it.isClickable = true; it.isFocusable = true
            it.setOnClickListener { showSetSalaryDialog() }
        }
        // Arrow hint on base salary
        (baseSalaryRow.findViewWithTag("val") as? TextView)?.let { v ->
            v.text = "Tap to set ▾"
            v.setTextColor(Color.parseColor("#1565C0"))
        }
        salaryCard.addView(baseSalaryRow)
        salaryCard.addView(salaryRow("Deductions (Late + Absent + Early)", Color.parseColor("#C62828"), dp).also {
            tvDeduction = it.findViewWithTag("val")
        })
        salaryCard.addView(salaryRow("Overtime Bonus", Color.parseColor("#2E7D32"), dp).also {
            tvOvertime = it.findViewWithTag("val")
        })
        salaryCard.addView(divider(dp))
        val netRow = salaryRow("Net Salary", Color.parseColor("#2E7D32"), dp).also {
            tvNetSalary = it.findViewWithTag("val")
        }
        (netRow.findViewWithTag("label") as? TextView)?.setTypeface(null, android.graphics.Typeface.BOLD)
        (netRow.findViewWithTag("val") as? TextView)?.setTypeface(null, android.graphics.Typeface.BOLD)
        (netRow.findViewWithTag("val") as? TextView)?.textSize = 15f
        salaryCard.addView(netRow)
        content.addView(salaryCard)

        // ── Daily Log ──
        val logCard = card(dp)
        logCard.addView(tv("Daily Log — Full Month", 14f, Color.parseColor("#111111"), bold = true).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(8, dp) }
        })
        logContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logCard.addView(logContainer)
        content.addView(logCard)

        root.addView(scroll)
        return root
    }

    // ─────────────── MONTH PICKER ───────────────

    private fun showMonthPickerDialog() {
        val dp = resources.displayMetrics.density
        val now = Calendar.getInstance()
        val currentYear = now.get(Calendar.YEAR)
        val currentMonthIdx = now.get(Calendar.MONTH)

        val months = arrayOf("January","February","March","April","May","June",
            "July","August","September","October","November","December")
        val years = (2024..2027).map { it.toString() }.toTypedArray()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(px(24, dp), px(16, dp), px(24, dp), px(8, dp))
        }
        val monthPicker = NumberPicker(this).apply {
            minValue = 0; maxValue = 11
            displayedValues = months
            value = (currentMonthIdx + currentMonthOffset).coerceIn(0, 11)
        }
        val yearPicker = NumberPicker(this).apply {
            minValue = 0; maxValue = years.size - 1
            displayedValues = years
            value = years.indexOf(currentYear.toString()).coerceAtLeast(0)
        }
        layout.addView(monthPicker)
        layout.addView(yearPicker)

        AlertDialog.Builder(this)
            .setTitle("Select Month")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                val pickedYear = years[yearPicker.value].toInt()
                val pickedMonth = monthPicker.value
                val now2 = Calendar.getInstance()
                val nowYear = now2.get(Calendar.YEAR)
                val nowMonth = now2.get(Calendar.MONTH)
                // Calculate offset
                currentMonthOffset = (pickedYear * 12 + pickedMonth) -
                        (nowYear * 12 + nowMonth)
                tvMonth.text = getMonthLabel()
                loadAttendanceReport()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────── OFFICE HOURS EDIT ───────────────

    private fun showEditOfficeHoursDialog() {
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24, dp), px(16, dp), px(24, dp), px(8, dp))
        }

        layout.addView(tv("Start Hour (0-23)", 12f, Color.parseColor("#757575")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(4, dp) }
        })
        val etStartHour = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("$officeStartHour")
            layoutParams = llp().also { it.bottomMargin = px(10, dp) }
        }
        layout.addView(etStartHour)

        layout.addView(tv("Start Minute (0-59)", 12f, Color.parseColor("#757575")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(4, dp) }
        })
        val etStartMin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("$officeStartMinute")
            layoutParams = llp().also { it.bottomMargin = px(10, dp) }
        }
        layout.addView(etStartMin)

        layout.addView(tv("End Hour (0-23)", 12f, Color.parseColor("#757575")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(4, dp) }
        })
        val etEndHour = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("$officeEndHour")
            layoutParams = llp().also { it.bottomMargin = px(10, dp) }
        }
        layout.addView(etEndHour)

        layout.addView(tv("End Minute (0-59)", 12f, Color.parseColor("#757575")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(4, dp) }
        })
        val etEndMin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("$officeEndMinute")
            layoutParams = llp()
        }
        layout.addView(etEndMin)

        AlertDialog.Builder(this)
            .setTitle("Edit Office Hours")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val sh = etStartHour.text.toString().toIntOrNull() ?: return@setPositiveButton
                val sm = etStartMin.text.toString().toIntOrNull() ?: 0
                val eh = etEndHour.text.toString().toIntOrNull() ?: return@setPositiveButton
                val em = etEndMin.text.toString().toIntOrNull() ?: 0
                val data = mapOf(
                    "startHour" to sh, "startMinute" to sm,
                    "endHour" to eh, "endMinute" to em,
                    "gracePeriodMinutes" to 15
                )
                db.getReference("officeSettings").setValue(data)
                    .addOnSuccessListener {
                        officeStartHour = sh; officeStartMinute = sm
                        officeEndHour = eh; officeEndMinute = em
                        updateOfficeHoursLabel()
                        loadAttendanceReport()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateOfficeHoursLabel() {
        // Keep arrow only — actual time shown inside edit dialog
        tvOfficeHours.text = "▾"
    }

    // ─────────────── ADMIN CONTROLS ───────────────

    private fun showAdminControls(dayKey: String, checkIn: String, status: String) {
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16, dp), px(12, dp), px(16, dp), px(8, dp))
        }

        val dateFmt = try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dayKey)
            SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(d!!)
        } catch (e: Exception) { dayKey }

        layout.addView(tv(dateFmt, 13f, Color.parseColor("#555555")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(12, dp) }
        })

        // Waive Deduction
        addControlButton(layout, "Waive Deduction", "#E65100", "#FFF3E0",
            "Remove late/absent penalty for this day", dp) {
            showWaiveDialog(dayKey, "waiveDeduction")
        }

        // Approve Overtime
        addControlButton(layout, "Approve Overtime", "#2E7D32", "#E8F5E9",
            "Add overtime bonus for extra work", dp) {
            showOvertimeApprovalDialog(dayKey)
        }

        // Full Day Relief
        addControlButton(layout, "Full Day Relief (Paid Leave)", "#1565C0", "#E3F2FD",
            "Mark as paid leave — no deduction, full salary", dp) {
            showWaiveDialog(dayKey, "fullRelief")
        }

        AlertDialog.Builder(this)
            .setTitle("Admin Controls")
            .setView(layout)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addControlButton(
        container: LinearLayout, title: String,
        textColor: String, bgColor: String, subtitle: String,
        dp: Float, onClick: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor(bgColor))
            setPadding(px(12, dp), px(10, dp), px(12, dp), px(10, dp))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setColor(Color.parseColor(bgColor))
            }
            layoutParams = llp().also { it.bottomMargin = px(8, dp) }
        }
        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textBlock.addView(tv(title, 13f, Color.parseColor(textColor), bold = true))
        textBlock.addView(tv(subtitle, 11f, Color.parseColor("#757575")).also {
            it.layoutParams = llp().also { m -> m.topMargin = px(2, dp) }
        })
        Button(this).apply {
            text = "Apply"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * dp
                setColor(Color.parseColor(textColor))
            }
            setPadding(px(12, dp), px(4, dp), px(12, dp), px(4, dp))
            setOnClickListener { onClick() }
        }.also { row.addView(textBlock); row.addView(it) }
        container.addView(row)
    }

    private fun showWaiveDialog(dayKey: String, type: String) {
        val dp = resources.displayMetrics.density
        val etReason = EditText(this).apply {
            hint = "Reason (e.g. Hospital visit, Family emergency)"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(px(12, dp), px(10, dp), px(12, dp), px(10, dp))
        }
        AlertDialog.Builder(this)
            .setTitle(if (type == "fullRelief") "Full Day Relief" else "Waive Deduction")
            .setView(etReason)
            .setPositiveButton("Confirm") { _, _ ->
                val reason = etReason.text.toString().trim().ifEmpty { "Admin approved" }
                val data = mapOf(
                    type to true,
                    "waiveReason" to reason,
                    "approvedAt" to System.currentTimeMillis()
                )
                db.getReference("adminOverrides")
                    .child(selectedDeviceId).child(dayKey)
                    .updateChildren(data)
                    .addOnSuccessListener { loadAttendanceReport() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOvertimeApprovalDialog(dayKey: String) {
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16, dp), px(12, dp), px(16, dp), px(8, dp))
        }
        layout.addView(tv("Overtime Hours", 12f, Color.parseColor("#757575")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(4, dp) }
        })
        val etHours = EditText(this).apply {
            hint = "e.g. 2"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = llp().also { it.bottomMargin = px(10, dp) }
        }
        layout.addView(etHours)
        layout.addView(tv("Reason", 12f, Color.parseColor("#757575")).also {
            it.layoutParams = llp().also { m -> m.bottomMargin = px(4, dp) }
        })
        val etReason = EditText(this).apply {
            hint = "e.g. Emergency complaint resolved"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = llp()
        }
        layout.addView(etReason)

        AlertDialog.Builder(this)
            .setTitle("Approve Overtime")
            .setView(layout)
            .setPositiveButton("Approve") { _, _ ->
                val hours = etHours.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val reason = etReason.text.toString().trim().ifEmpty { "Admin approved" }
                val data = mapOf(
                    "overtimeApproved" to true,
                    "overtimeHours" to hours,
                    "overtimeReason" to reason,
                    "approvedAt" to System.currentTimeMillis()
                )
                db.getReference("adminOverrides")
                    .child(selectedDeviceId).child(dayKey)
                    .updateChildren(data)
                    .addOnSuccessListener { loadAttendanceReport() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────── ALL EMPLOYEES LOG ───────────────

    private fun loadAllEmployeesLog() {
        val monthKey = getMonthKey()
        val dp = resources.displayMetrics.density

        // Reset stats
        tvPresent.text = "—"; tvAbsent.text = "—"
        tvLate.text = "—"; tvScore.text = "—"
        tvBaseSalary.text = "—"; tvDeduction.text = "—"
        tvOvertime.text = "—"; tvNetSalary.text = "—"
        logContainer.removeAllViews()

        // Show loading
        logContainer.addView(tv("Loading all employees...", 13f, Color.parseColor("#9E9E9E")).also {
            it.gravity = android.view.Gravity.CENTER
            it.setPadding(0, px(16,dp), 0, px(16,dp))
        })

        val cal = Calendar.getInstance().also { it.add(Calendar.MONTH, currentMonthOffset) }
        val totalDaysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val todayDay = if (currentMonthOffset == 0)
            Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        else totalDaysInMonth

        val allNames = employeeMap.keys.sorted()
        if (allNames.isEmpty()) return

        // Load all employees attendance
        val attData = mutableMapOf<String, Map<String, DataSnapshot>>() // name -> date -> snap
        var loaded = 0

        allNames.forEach { name ->
            val did = employeeMap[name] ?: run { loaded++; return@forEach }
            db.getReference("attendance").child(did)
                .orderByKey().startAt("${monthKey}-01").endAt("${monthKey}-31")
                .get()
                .addOnSuccessListener { snap ->
                    attData[name] = snap.children.associateBy { it.key ?: "" }
                    loaded++
                    if (loaded >= allNames.size) {
                        runOnUiThread { renderAllEmployeesLog(attData, totalDaysInMonth, todayDay, monthKey, dp) }
                    }
                }
                .addOnFailureListener {
                    attData[name] = emptyMap()
                    loaded++
                    if (loaded >= allNames.size) {
                        runOnUiThread { renderAllEmployeesLog(attData, totalDaysInMonth, todayDay, monthKey, dp) }
                    }
                }
        }
    }

    private fun renderAllEmployeesLog(
        attData: Map<String, Map<String, DataSnapshot>>,
        totalDays: Int, todayDay: Int, monthKey: String, dp: Float
    ) {
        logContainer.removeAllViews()
        val names = attData.keys.sorted()

        // Stats across all employees
        var totalPresent = 0; var totalLate = 0; var totalAbsent = 0

        for (d in 1..totalDays) {
            val dayKey = "${monthKey}-${String.format(Locale.getDefault(), "%02d", d)}"
            val isFuture = currentMonthOffset == 0 && d > todayDay
            if (isFuture) continue

            val dateFmt = try {
                val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dayKey)
                java.text.SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(parsed!!)
            } catch (e: Exception) { dayKey }

            // Date header row
            val dateHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#F0F4F8"))
                setPadding(px(8,dp), px(8,dp), px(8,dp), px(8,dp))
            }
            dateHeader.addView(tv(dateFmt, 12f, Color.parseColor("#333333"), bold = true))
            logContainer.addView(dateHeader)

            // Each employee row for this day
            names.forEach { name ->
                val dayRec = attData[name]?.get(dayKey)
                val checkIn = dayRec?.child("checkInTime")?.value?.toString() ?: ""
                val checkOut = dayRec?.child("checkOutTime")?.value?.toString() ?: ""
                val status = dayRec?.child("status")?.value?.toString() ?: ""

                if (checkIn.isNotEmpty()) totalPresent++
                if (status == "LATE") totalLate++
                if (checkIn.isEmpty()) totalAbsent++

                val empRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px(16,dp), px(8,dp), px(8,dp), px(8,dp))
                    setBackgroundColor(Color.WHITE)
                }
                empRow.addView(tv(name, 12f, Color.parseColor("#444444")).also {
                    it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                val timeText = when {
                    checkIn.isNotEmpty() -> if (checkOut.isNotEmpty()) "$checkIn → $checkOut" else "$checkIn →"
                    else -> "—"
                }
                empRow.addView(tv(timeText, 11f, Color.parseColor("#666666")).also {
                    it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                val badgeText: String; val badgeColor: Int; val badgeBg: Int
                when {
                    status == "ON_TIME" -> { badgeText = "On Time"; badgeColor = Color.parseColor("#2E7D32"); badgeBg = Color.parseColor("#E8F5E9") }
                    status == "LATE" -> { badgeText = "Late"; badgeColor = Color.parseColor("#C62828"); badgeBg = Color.parseColor("#FFEBEE") }
                    status == "OVERTIME" -> { badgeText = "OT"; badgeColor = Color.parseColor("#1565C0"); badgeBg = Color.parseColor("#E3F2FD") }
                    checkIn.isNotEmpty() -> { badgeText = "Present"; badgeColor = Color.parseColor("#2E7D32"); badgeBg = Color.parseColor("#E8F5E9") }
                    else -> { badgeText = "Absent"; badgeColor = Color.parseColor("#9E9E9E"); badgeBg = Color.parseColor("#F5F5F5") }
                }
                empRow.addView(tv(badgeText, 10f, badgeColor, bold = true).also {
                    it.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 8f*dp; setColor(badgeBg) }
                    it.setPadding(px(6,dp), px(2,dp), px(6,dp), px(2,dp))
                })
                logContainer.addView(empRow)
            }

            // Divider
            logContainer.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#DDDDDD"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }

        // Update stats
        val totalDaysCount = if (currentMonthOffset == 0) todayDay else totalDays
        val empCount = names.size.coerceAtLeast(1)
        tvPresent.text = "$totalPresent"
        tvAbsent.text = "$totalAbsent"
        tvLate.text = "$totalLate"
        val avgScore = if (totalDaysCount > 0 && empCount > 0)
            ((totalPresent.toFloat() / (totalDaysCount * empCount)) * 100).toInt() else 0
        tvScore.text = "$avgScore%"
        tvBaseSalary.text = "All (${names.size})"
        tvDeduction.text = "—"; tvOvertime.text = "—"; tvNetSalary.text = "—"
    }

    // ─────────────── LOAD DATA ───────────────

    private fun loadOfficeSettings() {
        db.getReference("officeSettings").get().addOnSuccessListener { snap ->
            officeStartHour = (snap.child("startHour").value as? Long)?.toInt() ?: 10
            officeStartMinute = (snap.child("startMinute").value as? Long)?.toInt() ?: 0
            officeEndHour = (snap.child("endHour").value as? Long)?.toInt() ?: 22
            officeEndMinute = (snap.child("endMinute").value as? Long)?.toInt() ?: 0
            updateOfficeHoursLabel()
        }
    }



    private fun loadEmployees() {
        db.getReference("employees").get().addOnSuccessListener { snap ->
            employeeMap.clear()
            for (emp in snap.children) {
                val name = emp.child("employeeName").value?.toString() ?: continue
                val deviceId = emp.key ?: continue
                val sal = (emp.child("salary").value as? Number)?.toDouble() ?: 0.0
                employeeMap[name] = deviceId
                salaryMap[deviceId] = sal
            }
            val names = employeeMap.keys.sorted().toMutableList()
            names.add("── All Employees ──")
            if (names.size == 1) return@addOnSuccessListener
            employeeSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, names
            )
        }
    }

    private fun loadAttendanceReport() {
        if (selectedDeviceId.isEmpty()) return
        val monthKey = getMonthKey()
        tvMonth.text = getMonthLabel()

        // Load overrides first, then attendance
        db.getReference("adminOverrides").child(selectedDeviceId).get()
            .addOnSuccessListener { overrideSnap ->
                val overrides = overrideSnap.children.associateBy { it.key ?: "" }
                loadAttendanceWithOverrides(monthKey, overrides)
            }
    }

    private fun loadAttendanceWithOverrides(
        monthKey: String,
        overrides: Map<String, DataSnapshot>
    ) {
        db.getReference("attendance").child(selectedDeviceId)
            .orderByKey()
            .startAt("${monthKey}-01")
            .endAt("${monthKey}-31")
            .get()
            .addOnSuccessListener { snap ->
                val dp = resources.displayMetrics.density
                logContainer.removeAllViews()

                val cal = Calendar.getInstance().also { it.add(Calendar.MONTH, currentMonthOffset) }
                val totalDaysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                val todayDay = if (currentMonthOffset == 0)
                    Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
                else totalDaysInMonth

                val officeTotalHours = ((officeEndHour * 60 + officeEndMinute) -
                        (officeStartHour * 60 + officeStartMinute)) / 60.0
                val dailyRate = if (baseSalary > 0) baseSalary / 30.0 else 0.0
                val hourlyRate = if (officeTotalHours > 0) dailyRate / officeTotalHours else 0.0

                val recordMap = snap.children.associateBy { it.key ?: "" }
                val presentDates = mutableSetOf<String>()
                var late = 0
                var totalDeductionMins = 0L
                var totalOvertimeMins = 0L

                for (d in 1..totalDaysInMonth) {
                    val dayKey = "${monthKey}-${String.format(Locale.getDefault(), "%02d", d)}"
                    val dayRecord = recordMap[dayKey]
                    val override = overrides[dayKey]
                    val isFuture = currentMonthOffset == 0 && d > todayDay

                    if (isFuture) continue

                    val checkIn = dayRecord?.child("checkInTime")?.value?.toString() ?: ""
                    val status = dayRecord?.child("status")?.value?.toString() ?: ""
                    val ciTs = (dayRecord?.child("checkInTimestamp")?.value as? Long) ?: 0L
                    val coTs = (dayRecord?.child("checkOutTimestamp")?.value as? Long) ?: 0L

                    val waiveDeduction = override?.child("waiveDeduction")?.value as? Boolean ?: false
                    val fullRelief = override?.child("fullRelief")?.value as? Boolean ?: false
                    val overtimeApproved = override?.child("overtimeApproved")?.value as? Boolean ?: false
                    val overtimeHours = (override?.child("overtimeHours")?.value as? Number)?.toDouble() ?: 0.0

                    if (checkIn.isNotEmpty()) {
                        presentDates.add(dayKey)
                        if (status == "LATE") late++

                        if (!waiveDeduction && !fullRelief) {
                            // Late deduction
                            if (ciTs > 0) {
                                val officeStartMs = Calendar.getInstance().also {
                                    it.timeInMillis = ciTs
                                    it.set(Calendar.HOUR_OF_DAY, officeStartHour)
                                    it.set(Calendar.MINUTE, officeStartMinute)
                                    it.set(Calendar.SECOND, 0)
                                }.timeInMillis
                                if (ciTs > officeStartMs)
                                    totalDeductionMins += (ciTs - officeStartMs) / 60000
                            }
                            // Early leave deduction
                            if (coTs > 0) {
                                val officeEndMs = Calendar.getInstance().also {
                                    it.timeInMillis = coTs
                                    it.set(Calendar.HOUR_OF_DAY, officeEndHour)
                                    it.set(Calendar.MINUTE, officeEndMinute)
                                    it.set(Calendar.SECOND, 0)
                                }.timeInMillis
                                if (coTs < officeEndMs)
                                    totalDeductionMins += (officeEndMs - coTs) / 60000
                            }
                        }

                        // Overtime: admin approved only
                        if (overtimeApproved && overtimeHours > 0) {
                            totalOvertimeMins += (overtimeHours * 60).toLong()
                        }
                    } else if (!fullRelief) {
                        // Absent deduction
                        if (d < todayDay && !waiveDeduction) {
                            totalDeductionMins += (officeTotalHours * 60).toLong()
                        }
                    } else {
                        // fullRelief — count as present, no deduction
                        presentDates.add(dayKey)
                    }
                }

                val present = presentDates.size
                val absent = (1 until todayDay).count { d ->
                    val dk = "${monthKey}-${String.format(Locale.getDefault(), "%02d", d)}"
                    dk !in presentDates
                }
                val score = if (todayDay > 0) ((present.toFloat() / todayDay) * 100).toInt() else 0

                val deductionAmt = (totalDeductionMins / 60.0) * hourlyRate
                val overtimeAmt = (totalOvertimeMins / 60.0) * hourlyRate
                val netSalary = (baseSalary - deductionAmt + overtimeAmt).coerceAtLeast(0.0)

                tvPresent.text = "$present"
                tvAbsent.text = "$absent"
                tvLate.text = "$late"
                tvScore.text = "$score%"

                if (baseSalary > 0) {
                    tvBaseSalary.text = "Rs ${"%,.0f".format(baseSalary)}"
                    tvDeduction.text = "- Rs ${"%,.0f".format(deductionAmt)}"
                    tvOvertime.text = "+ Rs ${"%,.0f".format(overtimeAmt)}"
                    tvNetSalary.text = "Rs ${"%,.0f".format(netSalary)}"
                } else {
                    tvBaseSalary.text = "Not Set"
                    tvDeduction.text = "—"
                    tvOvertime.text = "—"
                    tvNetSalary.text = "—"
                }

                // Build daily log
                for (d in 1..totalDaysInMonth) {
                    val dayKey = "${monthKey}-${String.format(Locale.getDefault(), "%02d", d)}"
                    val dayRecord = recordMap[dayKey]
                    val override = overrides[dayKey]
                    val checkIn = dayRecord?.child("checkInTime")?.value?.toString() ?: ""
                    val checkOut = dayRecord?.child("checkOutTime")?.value?.toString() ?: ""
                    val status = dayRecord?.child("status")?.value?.toString() ?: ""
                    val isFuture = currentMonthOffset == 0 && d > todayDay
                    val fullRelief = override?.child("fullRelief")?.value as? Boolean ?: false
                    val waived = override?.child("waiveDeduction")?.value as? Boolean ?: false
                    val otApproved = override?.child("overtimeApproved")?.value as? Boolean ?: false

                    val dateFmt = try {
                        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dayKey)
                        SimpleDateFormat("EEE dd", Locale.getDefault()).format(parsed!!)
                    } catch (e: Exception) { "$d" }

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, px(10, dp), 0, px(10, dp))
                        setBackgroundColor(if (d % 2 == 0) Color.parseColor("#FAFAFA") else Color.WHITE)
                        isClickable = !isFuture
                        isFocusable = !isFuture
                    }

                    row.addView(tv(dateFmt, 12f,
                        if (isFuture) Color.parseColor("#CCCCCC") else Color.parseColor("#333333"),
                        bold = true
                    ).also {
                        it.layoutParams = LinearLayout.LayoutParams(px(60, dp), ViewGroup.LayoutParams.WRAP_CONTENT)
                    })

                    val timeText = when {
                        isFuture -> "—"
                        fullRelief -> "Paid Leave"
                        checkIn.isNotEmpty() -> if (checkOut.isNotEmpty()) "$checkIn → $checkOut" else "$checkIn → —"
                        else -> "—"
                    }
                    row.addView(tv(timeText, 11f, Color.parseColor(if (isFuture) "#CCCCCC" else "#555555")).also {
                        it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })

                    // Status badge
                    val badgeText: String
                    val badgeColor: Int
                    val badgeBg: Int
                    when {
                        isFuture -> { badgeText = "—"; badgeColor = Color.parseColor("#CCCCCC"); badgeBg = Color.parseColor("#F5F5F5") }
                        fullRelief -> { badgeText = "Paid Leave"; badgeColor = Color.parseColor("#1565C0"); badgeBg = Color.parseColor("#E3F2FD") }
                        otApproved -> { badgeText = "Overtime"; badgeColor = Color.parseColor("#2E7D32"); badgeBg = Color.parseColor("#E8F5E9") }
                        waived -> { badgeText = "Waived"; badgeColor = Color.parseColor("#E65100"); badgeBg = Color.parseColor("#FFF3E0") }
                        status == "ON_TIME" -> { badgeText = "On Time"; badgeColor = Color.parseColor("#2E7D32"); badgeBg = Color.parseColor("#E8F5E9") }
                        status == "LATE" -> { badgeText = "Late"; badgeColor = Color.parseColor("#C62828"); badgeBg = Color.parseColor("#FFEBEE") }
                        status == "OVERTIME" -> { badgeText = "Overtime"; badgeColor = Color.parseColor("#2E7D32"); badgeBg = Color.parseColor("#E8F5E9") }
                        checkIn.isNotEmpty() -> { badgeText = "Present"; badgeColor = Color.parseColor("#2E7D32"); badgeBg = Color.parseColor("#E8F5E9") }
                        else -> { badgeText = "Absent"; badgeColor = Color.parseColor("#9E9E9E"); badgeBg = Color.parseColor("#F5F5F5") }
                    }

                    row.addView(tv(badgeText, 10f, badgeColor, bold = true).also {
                        it.background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 10f * dp
                            setColor(badgeBg)
                        }
                        it.setPadding(px(8, dp), px(3, dp), px(8, dp), px(3, dp))
                    })

                    // Long press = admin controls
                    if (!isFuture) {
                        row.setOnLongClickListener {
                            showAdminControls(dayKey, checkIn, status)
                            true
                        }
                        // Tap hint
                        row.addView(tv("⋮", 14f, Color.parseColor("#CCCCCC")).also {
                            it.setPadding(px(6, dp), 0, 0, 0)
                        })
                    }

                    logContainer.addView(row)
                    logContainer.addView(View(this).apply {
                        setBackgroundColor(Color.parseColor("#EEEEEE"))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    })
                }

                // Hint at bottom
                logContainer.addView(tv("Long press any day to apply admin controls", 11f, Color.parseColor("#AAAAAA")).also {
                    it.gravity = Gravity.CENTER
                    it.setPadding(0, px(8, dp), 0, px(4, dp))
                })
            }
    }

    // ─────────────── DIALOGS ───────────────

    private fun showSetSalaryDialog() {
        if (selectedDeviceId.isEmpty()) { showMsg("Select an employee first."); return }
        val dp = resources.displayMetrics.density
        val etSalary = EditText(this).apply {
            hint = "e.g. 25000"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (baseSalary > 0) setText("%.0f".format(baseSalary))
            setPadding(px(12, dp), px(10, dp), px(12, dp), px(10, dp))
        }
        AlertDialog.Builder(this)
            .setTitle("Set Salary — $selectedEmployee")
            .setView(etSalary)
            .setPositiveButton("Save") { _, _ ->
                val sal = etSalary.text.toString().toDoubleOrNull()
                if (sal == null || sal <= 0) { showMsg("Enter a valid salary."); return@setPositiveButton }
                db.getReference("employees").child(selectedDeviceId).child("salary").setValue(sal)
                    .addOnSuccessListener {
                        baseSalary = sal
                        salaryMap[selectedDeviceId] = sal
                        tvBaseSalary.text = "Rs ${"%,.0f".format(sal)}"
                        loadAttendanceReport()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────── STAT DETAIL ───────────────

    private fun showStatDetail(type: String) {
        if (selectedDeviceId.isEmpty()) { showMsg("Select an employee first."); return }
        val monthKey = getMonthKey()
        db.getReference("attendance").child(selectedDeviceId)
            .orderByKey().startAt("${monthKey}-01").endAt("${monthKey}-31")
            .get()
            .addOnSuccessListener { snap ->
                val dp = resources.displayMetrics.density
                val scroll = android.widget.ScrollView(this)
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(16, dp), px(8, dp), px(16, dp), px(8, dp))
                }
                scroll.addView(container)
                val cal = Calendar.getInstance().also { it.add(Calendar.MONTH, currentMonthOffset) }
                val totalDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                val todayDay = if (currentMonthOffset == 0) Calendar.getInstance().get(Calendar.DAY_OF_MONTH) else totalDays
                val recordMap = snap.children.associateBy { it.key ?: "" }
                var count = 0
                for (d in 1..totalDays) {
                    val dayKey = "${monthKey}-${String.format(Locale.getDefault(), "%02d", d)}"
                    val rec = recordMap[dayKey]
                    val ci = rec?.child("checkInTime")?.value?.toString() ?: ""
                    val co = rec?.child("checkOutTime")?.value?.toString() ?: ""
                    val st = rec?.child("status")?.value?.toString() ?: ""
                    val isFuture = currentMonthOffset == 0 && d > todayDay
                    if (isFuture) continue
                    val show = when (type) {
                        "Present" -> ci.isNotEmpty()
                        "Absent" -> ci.isEmpty() && d < todayDay
                        "Late" -> st == "LATE"
                        "Score" -> true
                        else -> false
                    }
                    if (!show) continue
                    count++
                    val dateFmt = try {
                        val p = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dayKey)
                        SimpleDateFormat("EEE dd MMM", Locale.getDefault()).format(p!!)
                    } catch (e: Exception) { dayKey }
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, px(10, dp), 0, px(10, dp))
                        setBackgroundColor(if (count % 2 == 0) Color.parseColor("#FAFAFA") else Color.WHITE)
                    }
                    row.addView(tv(dateFmt, 13f, Color.parseColor("#333333"), bold = true).also {
                        it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    val detail = when (type) {
                        "Present" -> if (co.isNotEmpty()) "$ci → $co" else "In: $ci"
                        "Absent" -> "No attendance"
                        "Late" -> "In: $ci"
                        "Score" -> when (st) { "ON_TIME" -> "On Time"; "LATE" -> "Late"; "OVERTIME" -> "Overtime"; else -> if (ci.isEmpty()) "Absent" else "Present" }
                        else -> ""
                    }
                    val dc = when { type == "Absent" -> "#C62828"; st == "LATE" -> "#E65100"; else -> "#2E7D32" }
                    row.addView(tv(detail, 12f, Color.parseColor(dc)))
                    container.addView(row)
                    container.addView(View(this).apply {
                        setBackgroundColor(Color.parseColor("#EEEEEE"))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    })
                }
                if (count == 0) container.addView(tv("No records.", 13f, Color.parseColor("#9E9E9E")).also { it.gravity = Gravity.CENTER; it.setPadding(0, px(20, dp), 0, px(20, dp)) })
                AlertDialog.Builder(this).setTitle("$type — $count").setView(scroll).setPositiveButton("Close", null).show()
            }
    }

    // ─────────────── HELPERS ───────────────

    private fun getMonthLabel(): String {
        val cal = Calendar.getInstance().also { it.add(Calendar.MONTH, currentMonthOffset) }
        return SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
    }

    private fun getMonthKey(): String {
        val cal = Calendar.getInstance().also { it.add(Calendar.MONTH, currentMonthOffset) }
        return SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(cal.time)
    }

    private fun px(v: Int, dp: Float) = (v * dp).toInt()
    private fun llp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
    }
    private fun card(dp: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 10f * dp; setColor(Color.WHITE) }
        setPadding(px(14, dp), px(12, dp), px(14, dp), px(12, dp))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = px(10, dp) }
    }
    private fun divider(dp: Float) = View(this).apply {
        setBackgroundColor(Color.parseColor("#EEEEEE"))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = px(4, dp); it.bottomMargin = px(4, dp) }
    }
    private fun salaryRow(label: String, valColor: Int, dp: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, px(7, dp), 0, px(7, dp))
        addView(tv(label, 13f, Color.parseColor("#555555")).also { it.tag = "label"; it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        addView(tv("—", 13f, valColor).also { it.tag = "val" })
    }
    private fun statBox(v: TextView, label: String, dp: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(px(8, dp), px(12, dp), px(8, dp), px(12, dp))
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 8f * dp; setColor(Color.WHITE); setStroke(px(1, dp), Color.parseColor("#DDDDDD")) }
        isClickable = true; isFocusable = true
        setOnClickListener { showStatDetail(label) }
        addView(v)
        addView(tv(label, 11f, Color.parseColor("#555555")).also { it.gravity = Gravity.CENTER })
    }
    private fun showMsg(msg: String) { AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show() }
}
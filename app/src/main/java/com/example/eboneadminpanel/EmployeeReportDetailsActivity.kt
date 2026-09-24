package com.example.eboneadminpanel

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
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
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

const val ALL_EMPLOYEES_MARKER = "ALL"

enum class QuickFilter { NONE, REPEATS_ONLY, NC_ONLY }
enum class RepeatViewMode { CUSTOMER, DATE }

data class EmployeeRepeatGroup(
    val employeeName: String,
    val repeatCount: Int,
    val ncCount: Int,
    val items: List<Map<String, String>>
)

class EmployeeReportDetailsActivity : AppCompatActivity() {

    private lateinit var searchEditText: AutoCompleteTextView
    private lateinit var monthFilterButton: Button
    private lateinit var dateRangeButton: ImageButton
    private lateinit var btnRepeatCustomerView: Button
    private lateinit var btnRepeatDateView: Button
    private lateinit var employeeSpinner: Spinner
    private lateinit var repeatsFilterText: TextView
    private lateinit var ncFilterText: TextView
    private lateinit var adapter: RepeatComplaintAdapter
    private val complaintList = mutableListOf<Map<String, String>>()

    private val employeeNamesList = mutableListOf<String>()
    private lateinit var spinnerAdapter: ArrayAdapter<String>

    // Master (unfiltered) data — search filters from these
    private val masterRepeatGroups = mutableListOf<EmployeeRepeatGroup>()
    private val masterAllComplaints = mutableListOf<Map<String, String>>()

    private var isRepeatMode = false
    private var isAllEmployeesMode = false
    private var employeeName: String = ""

    private var currentQuickFilter = QuickFilter.NONE
    private var currentRepeatViewMode = RepeatViewMode.CUSTOMER

    private var selectedStartTime: Long = 0L
    private var selectedEndTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_employee_report_details)

        employeeName = intent.getStringExtra("employeeName") ?: "Employee"
        val showRepeat = intent.getBooleanExtra("showRepeat", false)

        isRepeatMode = showRepeat
        isAllEmployeesMode = employeeName == ALL_EMPLOYEES_MARKER

        val empNameText = findViewById<TextView>(R.id.employeeNameText)
        empNameText.text = if (isAllEmployeesMode) "👥 All Employees" else "👤 $employeeName"

        // WindowInsets handling for status bar on Pixel 8 Pro & all devices
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            empNameText.setPadding(
                empNameText.paddingLeft,
                statusBarInset.top + (12 * resources.displayMetrics.density).toInt(),
                empNameText.paddingRight,
                empNameText.paddingBottom
            )
            insets
        }

        val recyclerView = findViewById<RecyclerView>(R.id.detailRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = RepeatComplaintAdapter(complaintList) { pos ->
            // Sub-header expansion toggle for Repeat Complaints Customer View
            val item = complaintList[pos]
            val title = item["title"] ?: ""
            // Toggle expansion state of items belonging to this customer
            // For simplicity, we can trigger re-filter or toggle isExpanded
        }
        recyclerView.adapter = adapter

        searchEditText = findViewById(R.id.searchDetailEditText)
        monthFilterButton = findViewById(R.id.monthFilterButton)
        dateRangeButton = findViewById(R.id.dateRangePickerButton)
        btnRepeatCustomerView = findViewById(R.id.btnRepeatCustomerView)
        btnRepeatDateView = findViewById(R.id.btnRepeatDateView)
        employeeSpinner = findViewById(R.id.employeeSpinner)
        repeatsFilterText = findViewById(R.id.repeatsFilterText)
        ncFilterText = findViewById(R.id.ncFilterText)

        setupEmployeeSpinner()
        selectCurrentMonth()

        updateRepeatViewButtons()

        if (showRepeat) {
            loadRepeatComplaints()
        } else {
            loadAllComplaints()
        }

        monthFilterButton.setOnClickListener {
            selectCurrentMonth()
            if (isRepeatMode) loadRepeatComplaints() else loadAllComplaints()
        }

        dateRangeButton.setOnClickListener {
            showDateRangePicker()
        }

        btnRepeatCustomerView.setOnClickListener {
            currentRepeatViewMode = RepeatViewMode.CUSTOMER
            updateRepeatViewButtons()
            if (isRepeatMode) applyRepeatFilter() else applyAllComplaintsFilter()
        }

        btnRepeatDateView.setOnClickListener {
            currentRepeatViewMode = RepeatViewMode.DATE
            updateRepeatViewButtons()
            if (isRepeatMode) applyRepeatFilter() else applyAllComplaintsFilter()
        }

        repeatsFilterText.setOnClickListener {
            currentQuickFilter = if (currentQuickFilter == QuickFilter.REPEATS_ONLY) {
                QuickFilter.NONE
            } else {
                QuickFilter.REPEATS_ONLY
            }
            updateFilterUI()
            if (isRepeatMode) applyRepeatFilter() else applyAllComplaintsFilter()
        }

        ncFilterText.setOnClickListener {
            currentQuickFilter = if (currentQuickFilter == QuickFilter.NC_ONLY) {
                QuickFilter.NONE
            } else {
                QuickFilter.NC_ONLY
            }
            updateFilterUI()
            if (isRepeatMode) applyRepeatFilter() else applyAllComplaintsFilter()
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isRepeatMode) applyRepeatFilter() else applyAllComplaintsFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun updateRepeatViewButtons() {
        val normalBg = Color.parseColor("#FFFFFF") // Neutral White background
        val normalBorderColor = Color.parseColor("#CFD8DC")
        val selectedBorderColor = Color.parseColor("#D32F2F") // Red border when selected
        val textColor = Color.parseColor("#212121") // Sharp Black / Dark text

        val density = resources.displayMetrics.density
        val cornerRadiusPx = 4 * density

        val customerDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(normalBg)
            val strokeWidth = if (currentRepeatViewMode == RepeatViewMode.CUSTOMER) (2.5 * density).toInt() else (1 * density).toInt()
            val strokeColor = if (currentRepeatViewMode == RepeatViewMode.CUSTOMER) selectedBorderColor else normalBorderColor
            setStroke(strokeWidth, strokeColor)
        }

        val dateDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(normalBg)
            val strokeWidth = if (currentRepeatViewMode == RepeatViewMode.DATE) (2.5 * density).toInt() else (1 * density).toInt()
            val strokeColor = if (currentRepeatViewMode == RepeatViewMode.DATE) selectedBorderColor else normalBorderColor
            setStroke(strokeWidth, strokeColor)
        }

        btnRepeatCustomerView.background = customerDrawable
        btnRepeatCustomerView.setTextColor(textColor)

        btnRepeatDateView.background = dateDrawable
        btnRepeatDateView.setTextColor(textColor)
    }

    private fun updateFilterUI() {
        when (currentQuickFilter) {
            QuickFilter.REPEATS_ONLY -> {
                repeatsFilterText.setBackgroundResource(R.drawable.bg_filter_repeats_active)
                repeatsFilterText.setTextColor(Color.WHITE)

                ncFilterText.setBackgroundResource(R.drawable.bg_spinner)
                ncFilterText.setTextColor(Color.parseColor("#455A64"))
            }
            QuickFilter.NC_ONLY -> {
                repeatsFilterText.setBackgroundResource(R.drawable.bg_spinner)
                repeatsFilterText.setTextColor(Color.parseColor("#455A64"))

                ncFilterText.setBackgroundResource(R.drawable.bg_filter_nc_active)
                ncFilterText.setTextColor(Color.WHITE)
            }
            QuickFilter.NONE -> {
                repeatsFilterText.setBackgroundResource(R.drawable.bg_spinner)
                repeatsFilterText.setTextColor(Color.parseColor("#455A64"))

                ncFilterText.setBackgroundResource(R.drawable.bg_spinner)
                ncFilterText.setTextColor(Color.parseColor("#455A64"))
            }
        }
    }

    private fun selectCurrentMonth() {
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
    }

    private fun setupEmployeeSpinner() {
        employeeNamesList.clear()

        val initialDisplayName = if (isAllEmployeesMode) "👥 All Employees" else "👤 ${normalizeName(employeeName)}"
        employeeNamesList.add(initialDisplayName)

        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, employeeNamesList)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        employeeSpinner.adapter = spinnerAdapter

        fetchAllEmployeeNames()

        employeeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = employeeNamesList[position]
                val newName = when {
                    selected.startsWith("👥") -> ALL_EMPLOYEES_MARKER
                    selected.startsWith("👤 ") -> normalizeName(selected.substring(2))
                    else -> normalizeName(selected)
                }

                if (newName != normalizeName(employeeName)) {
                    employeeName = newName
                    isAllEmployeesMode = (employeeName == ALL_EMPLOYEES_MARKER)

                    findViewById<TextView>(R.id.employeeNameText).text =
                        if (isAllEmployeesMode) "👥 All Employees" else "👤 $employeeName"

                    if (isRepeatMode) loadRepeatComplaints() else loadAllComplaints()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun fetchAllEmployeeNames() {
        FirebaseDatabase.getInstance().getReference("complaints")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val names = mutableSetOf<String>()
                    for (cs in snapshot.children) {
                        val name = cs.child("assignedTo").getValue(String::class.java) ?: ""
                        if (name.isNotEmpty()) names.add(normalizeName(name))
                    }

                    FirebaseDatabase.getInstance().getReference("resolvedComplaints")
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(resSnapshot: DataSnapshot) {
                                for (cs in resSnapshot.children) {
                                    val name = cs.child("assignedTo").getValue(String::class.java) ?: ""
                                    if (name.isNotEmpty()) names.add(normalizeName(name))
                                }

                                updateSpinnerList(names.sorted())
                            }
                            override fun onCancelled(error: DatabaseError) {}
                        })
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun normalizeName(name: String): String {
        return name.trim().lowercase(Locale.getDefault())
            .split(" ").joinToString(" ") { it.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } }
    }

    private fun updateSpinnerList(names: List<String>) {
        val targetName = if (isAllEmployeesMode) "👥 All Employees" else "👤 ${normalizeName(employeeName)}"

        employeeNamesList.clear()
        employeeNamesList.add("👥 All Employees")
        names.forEach { employeeNamesList.add("👤 $it") }

        spinnerAdapter.notifyDataSetChanged()

        val index = employeeNamesList.indexOfFirst { it.equals(targetName, ignoreCase = true) }
        if (index >= 0) {
            employeeSpinner.setSelection(index, false)
        } else if (!isAllEmployeesMode) {
            employeeNamesList.add("👤 ${normalizeName(employeeName)}")
            spinnerAdapter.notifyDataSetChanged()
            employeeSpinner.setSelection(employeeNamesList.size - 1, false)
        }
    }

    private fun showDateRangePicker() {
        val builder = MaterialDatePicker.Builder.dateRangePicker()
        builder.setTitleText("Select Start Date & End Date")
        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { range ->
            if (range.first != null && range.second != null) {
                val calStart = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                calStart.timeInMillis = range.first
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
                calEnd.timeInMillis = range.second
                val localEnd = Calendar.getInstance()
                localEnd.set(
                    calEnd.get(Calendar.YEAR),
                    calEnd.get(Calendar.MONTH),
                    calEnd.get(Calendar.DAY_OF_MONTH),
                    23, 59, 59
                )
                localEnd.set(Calendar.MILLISECOND, 999)
                selectedEndTime = localEnd.timeInMillis

                if (isRepeatMode) loadRepeatComplaints() else loadAllComplaints()
            }
        }
        picker.show(supportFragmentManager, "date_picker")
    }

    private fun isNewConnectionNode(cs: DataSnapshot): Boolean {
        // 1. Check isNewConnection boolean field
        val b = cs.child("isNewConnection").getValue(Boolean::class.java)
        if (b == true) return true

        // 2. Check isNewConnection string field
        val s = cs.child("isNewConnection").getValue(String::class.java)
        if (!s.isNullOrEmpty() && s.equals("true", ignoreCase = true)) return true

        // 3. Check is_new_connection field
        val b2 = cs.child("is_new_connection").getValue(Boolean::class.java)
        if (b2 == true) return true
        val s2 = cs.child("is_new_connection").getValue(String::class.java)
        if (!s2.isNullOrEmpty() && s2.equals("true", ignoreCase = true)) return true

        // 4. Check assignedTo field
        val assignedTo = cs.child("assignedTo").getValue(String::class.java) ?: ""
        if (assignedTo.contains("NEW_CONNECTION", ignoreCase = true) ||
            assignedTo.contains("New Connection", ignoreCase = true) ||
            assignedTo.contains("INTAKE", ignoreCase = true) ||
            assignedTo.contains("DASHBOARD", ignoreCase = true)) {
            return true
        }

        // 5. Check company field
        val company = cs.child("company").getValue(String::class.java) ?: ""
        if (company.contains("NEW_CONNECTION", ignoreCase = true) ||
            company.contains("New Connection", ignoreCase = true) ||
            company.equals("NC", ignoreCase = true)) {
            return true
        }

        // 6. Check userId / details / address fields
        val userId = cs.child("userId").getValue(String::class.java) ?: ""
        val details = cs.child("details").getValue(String::class.java) ?: ""
        val address = cs.child("address").getValue(String::class.java) ?: ""

        if (userId.contains("New Connection", ignoreCase = true) ||
            userId.contains("NEW CONNECTION", ignoreCase = true) ||
            details.contains("New Connection", ignoreCase = true) ||
            details.contains("NEW CONNECTION", ignoreCase = true) ||
            address.contains("New Connection", ignoreCase = true) ||
            address.contains("NEW CONNECTION", ignoreCase = true)) {
            return true
        }

        return false
    }

    private fun fetchOfficeSettingsNewConnections(callback: (Map<String, List<Map<String, String>>>) -> Unit) {
        FirebaseDatabase.getInstance()
            .getReference("officeSettings/new_connections")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val ncMap = mutableMapOf<String, MutableList<Map<String, String>>>()

                    fun addNcItem(empNorm: String, empRaw: String, item: DataSnapshot, defaultStatus: String) {
                        val createdTime = item.child("createdTime").getValue(Long::class.java) ?: 0L
                        if (createdTime !in selectedStartTime..selectedEndTime) return
                        if (!isAllEmployeesMode && empNorm.isNotEmpty() && !empNorm.equals(normalizeName(employeeName), true)) return

                        val userId = item.child("customerName").getValue(String::class.java)
                            ?: item.child("userId").getValue(String::class.java)
                            ?: "New Connection"
                        val address = item.child("address").getValue(String::class.java) ?: ""
                        val phone = item.child("phoneNumber").getValue(String::class.java) ?: ""
                        val status = item.child("status").getValue(String::class.java) ?: defaultStatus
                        val comments = item.child("comments").getValue(String::class.java)
                            ?: item.child("details").getValue(String::class.java) ?: "New Connection"

                        val map = mutableMapOf(
                            "type" to "item",
                            "isNcCard" to "true",
                            "ncTag" to "NEW CONNECTION",
                            "userId" to userId,
                            "address" to address,
                            "phoneNumber" to phone,
                            "status" to status,
                            "createdTime" to createdTime.toString(),
                            "employeeName" to empRaw.ifEmpty { "Unassigned" },
                            "details" to comments
                        )

                        val targetKey = if (empNorm.isEmpty()) "unassigned" else empNorm
                        if (!ncMap.containsKey(targetKey)) {
                            ncMap[targetKey] = mutableListOf()
                        }
                        ncMap[targetKey]?.add(map)
                    }

                    // 1. gift_box (assigned new connections)
                    val giftBox = snapshot.child("gift_box")
                    for (empChild in giftBox.children) {
                        val assignedRaw = empChild.key ?: ""
                        val assignedNorm = normalizeName(assignedRaw)

                        for (item in empChild.children) {
                            addNcItem(assignedNorm, assignedRaw, item, "Assigned")
                        }
                    }

                    // 2. completed (installed new connections)
                    val completed = snapshot.child("completed")
                    for (item in completed.children) {
                        val assignedRaw = item.child("assignedTo").getValue(String::class.java) ?: ""
                        val assignedNorm = normalizeName(assignedRaw)
                        addNcItem(assignedNorm, assignedRaw, item, "Installed")
                    }

                    // 3. pending (unassigned intake new connections, if All Employees mode)
                    if (isAllEmployeesMode) {
                        val pending = snapshot.child("pending")
                        for (item in pending.children) {
                            val assignedRaw = item.child("assignedTo").getValue(String::class.java) ?: "Unassigned"
                            val assignedNorm = normalizeName(assignedRaw)
                            addNcItem(assignedNorm, assignedRaw, item, "Pending Intake")
                        }
                    }

                    callback(ncMap)
                }

                override fun onCancelled(error: DatabaseError) {
                    callback(emptyMap())
                }
            })
    }

    // ---------- REPEAT COMPLAINTS (single employee OR all employees) ----------

    private fun loadRepeatComplaints() {
        val empCustomerMap = mutableMapOf<String, MutableMap<String, MutableList<Map<String, String>>>>()
        val empNcItemsMap = mutableMapOf<String, MutableList<Map<String, String>>>()
        val empDisplayNameMap = mutableMapOf<String, String>()

        fetchOfficeSettingsNewConnections { officeNcMap ->
            for ((empNorm, list) in officeNcMap) {
                if (!empNcItemsMap.containsKey(empNorm)) {
                    empNcItemsMap[empNorm] = mutableListOf()
                }
                empNcItemsMap[empNorm]?.addAll(list)
                if (list.isNotEmpty() && !empDisplayNameMap.containsKey(empNorm)) {
                    empDisplayNameMap[empNorm] = list.first()["employeeName"] ?: empNorm
                }
            }

            FirebaseDatabase.getInstance()
                .getReference("complaints")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {

                        for (cs in snapshot.children) {
                            val assignedRaw = cs.child("assignedTo").getValue(String::class.java) ?: ""
                            val assignedNorm = normalizeName(assignedRaw)
                            val createdTime = cs.child("createdTime").getValue(Long::class.java) ?: 0L
                            val isNewConn = isNewConnectionNode(cs)

                            if (createdTime !in selectedStartTime..selectedEndTime) continue
                            if (!isAllEmployeesMode && assignedNorm.isNotEmpty() && !assignedNorm.equals(normalizeName(employeeName), true)) continue

                            val userId = cs.child("userId").getValue(String::class.java) ?: ""
                            val address = cs.child("address").getValue(String::class.java) ?: ""
                            val status = cs.child("status").getValue(String::class.java) ?: ""
                            val phoneNumber = cs.child("phoneNumber").getValue(String::class.java) ?: ""
                            val details = cs.child("details").getValue(String::class.java) ?: ""

                            val targetNorm = if (assignedNorm.isEmpty()) "unassigned" else assignedNorm
                            if (!empDisplayNameMap.containsKey(targetNorm) && assignedRaw.isNotEmpty()) {
                                empDisplayNameMap[targetNorm] = assignedRaw.trim()
                            }

                            if (isNewConn) {
                                if (!empNcItemsMap.containsKey(targetNorm)) {
                                    empNcItemsMap[targetNorm] = mutableListOf()
                                }
                                val itemMap = mutableMapOf(
                                    "type" to "item",
                                    "isNcCard" to "true",
                                    "ncTag" to "NEW CONNECTION",
                                    "userId" to userId.ifEmpty { "New Connection" },
                                    "address" to address,
                                    "status" to status,
                                    "phoneNumber" to phoneNumber,
                                    "createdTime" to createdTime.toString(),
                                    "source" to "complaints",
                                    "employeeName" to assignedRaw.trim(),
                                    "details" to details
                                )
                                empNcItemsMap[targetNorm]?.add(itemMap)
                                continue
                            }

                            if (assignedNorm.isEmpty() || userId.isEmpty()) continue

                            if (!empCustomerMap.containsKey(assignedNorm)) {
                                empCustomerMap[assignedNorm] = mutableMapOf()
                            }

                            val userMap = empCustomerMap[assignedNorm]!!
                            if (!userMap.containsKey(userId)) {
                                userMap[userId] = mutableListOf()
                            }

                            val itemMap = mutableMapOf(
                                "type" to "item",
                                "isNcCard" to "false",
                                "userId" to userId,
                                "address" to address,
                                "status" to status,
                                "phoneNumber" to phoneNumber,
                                "createdTime" to createdTime.toString(),
                                "source" to "complaints",
                                "employeeName" to assignedRaw.trim(),
                                "details" to details
                            )

                            userMap[userId]?.add(itemMap)
                        }

                        FirebaseDatabase.getInstance()
                            .getReference("resolvedComplaints")
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(resolvedSnapshot: DataSnapshot) {

                                    val complaintsIds = mutableSetOf<String>()
                                    for (cs in snapshot.children) {
                                        val cId = cs.child("complaintId")
                                            .getValue(String::class.java) ?: cs.key ?: ""
                                        complaintsIds.add(cId)
                                    }

                                    for (cs in resolvedSnapshot.children) {
                                        val assignedRaw = cs.child("assignedTo").getValue(String::class.java) ?: ""
                                        val assignedNorm = normalizeName(assignedRaw)
                                        val createdTime = cs.child("createdTime").getValue(Long::class.java) ?: 0L
                                        val isNewConn = isNewConnectionNode(cs)

                                        if (createdTime !in selectedStartTime..selectedEndTime) continue
                                        if (!isAllEmployeesMode && assignedNorm.isNotEmpty() && !assignedNorm.equals(normalizeName(employeeName), true)) continue

                                        val complaintId = cs.child("complaintId")
                                            .getValue(String::class.java) ?: cs.key ?: ""
                                        if (complaintId.isNotEmpty() && complaintsIds.contains(complaintId)) continue

                                        val userId = cs.child("userId").getValue(String::class.java) ?: ""
                                        val address = cs.child("address").getValue(String::class.java) ?: ""
                                        val phoneNumber = cs.child("phoneNumber").getValue(String::class.java) ?: ""
                                        val details = cs.child("details").getValue(String::class.java) ?: ""

                                        val targetNorm = if (assignedNorm.isEmpty()) "unassigned" else assignedNorm
                                        if (!empDisplayNameMap.containsKey(targetNorm) && assignedRaw.isNotEmpty()) {
                                            empDisplayNameMap[targetNorm] = assignedRaw.trim()
                                        }

                                        if (isNewConn) {
                                            if (!empNcItemsMap.containsKey(targetNorm)) {
                                                empNcItemsMap[targetNorm] = mutableListOf()
                                            }
                                            val itemMap = mutableMapOf(
                                                "type" to "item",
                                                "isNcCard" to "true",
                                                "ncTag" to "NEW CONNECTION",
                                                "userId" to userId.ifEmpty { "New Connection" },
                                                "address" to address,
                                                "status" to "Resolved",
                                                "phoneNumber" to phoneNumber,
                                                "createdTime" to createdTime.toString(),
                                                "source" to "resolvedComplaints",
                                                "employeeName" to assignedRaw.trim(),
                                                "details" to details
                                            )
                                            empNcItemsMap[targetNorm]?.add(itemMap)
                                            continue
                                        }

                                        if (assignedNorm.isEmpty() || userId.isEmpty()) continue

                                        if (!empCustomerMap.containsKey(assignedNorm)) {
                                            empCustomerMap[assignedNorm] = mutableMapOf()
                                        }

                                        val userMap = empCustomerMap[assignedNorm]!!
                                        if (!userMap.containsKey(userId)) {
                                            userMap[userId] = mutableListOf()
                                        }

                                        val itemMap = mutableMapOf(
                                            "type" to "item",
                                            "isNcCard" to "false",
                                            "userId" to userId,
                                            "address" to address,
                                            "status" to "Resolved",
                                            "phoneNumber" to phoneNumber,
                                            "createdTime" to createdTime.toString(),
                                            "source" to "resolvedComplaints",
                                            "employeeName" to assignedRaw.trim(),
                                            "details" to details
                                        )

                                        userMap[userId]?.add(itemMap)
                                    }

                                    masterRepeatGroups.clear()
                                    var totalRepeatsSum = 0
                                    var totalNcSum = 0

                                    val allEmployeesSet = mutableSetOf<String>()
                                    allEmployeesSet.addAll(empCustomerMap.keys)
                                    allEmployeesSet.addAll(empNcItemsMap.keys)

                                    for (empNorm in allEmployeesSet) {
                                        val userMap = empCustomerMap[empNorm] ?: emptyMap()
                                        val empNcCards = empNcItemsMap[empNorm] ?: emptyList()

                                        val empRepeatCards = mutableListOf<Map<String, String>>()

                                        // Customer-wise grouping for Repeat Complaints (Highest count customer first)
                                        val repeatCustomerList = userMap.filter { it.value.size > 1 }.entries
                                            .sortedByDescending { it.value.size }

                                        for (entry in repeatCustomerList) {
                                            // Sort cards for each customer date ascending
                                            val sortedCustCards = entry.value.sortedBy { it["createdTime"]?.toLongOrNull() ?: 0L }
                                            empRepeatCards.addAll(sortedCustCards)
                                        }

                                        val combinedCards = mutableListOf<Map<String, String>>()
                                        combinedCards.addAll(empRepeatCards)
                                        combinedCards.addAll(empNcCards)

                                        if (combinedCards.isNotEmpty()) {
                                            val rawName = empDisplayNameMap[empNorm] ?: empNorm
                                            val displayName = if (rawName.equals("unassigned", true)) "Unassigned / Intake" else rawName

                                            val repeatCount = empRepeatCards.size
                                            val ncCount = empNcCards.size

                                            masterRepeatGroups.add(
                                                EmployeeRepeatGroup(
                                                    employeeName = displayName,
                                                    repeatCount = repeatCount,
                                                    ncCount = ncCount,
                                                    items = combinedCards
                                                )
                                            )

                                            totalRepeatsSum += repeatCount
                                            totalNcSum += ncCount
                                        }
                                    }

                                    // Sort employees in descending order of Repeat Complaints count
                                    masterRepeatGroups.sortByDescending { it.repeatCount }

                                    repeatsFilterText.text = "Repeats: $totalRepeatsSum"
                                    ncFilterText.text = "NC: $totalNcSum"
                                    updateFilterUI()
                                    updateSearchSuggestions()
                                    applyRepeatFilter()
                                }

                                override fun onCancelled(error: DatabaseError) {}
                            })
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })
        }
    }

    private fun applyRepeatFilter() {
        complaintList.clear()
        val query = searchEditText.text.toString().trim()

        for (group in masterRepeatGroups) {
            val empName = group.employeeName
            val matchesEmployeeName = query.isNotEmpty() && empName.contains(query, true)

            // 1. Filter by Quick Filter (Repeats Only vs NC Only vs Both)
            val quickFilteredItems = when (currentQuickFilter) {
                QuickFilter.REPEATS_ONLY -> group.items.filter { it["isNcCard"] != "true" }
                QuickFilter.NC_ONLY -> group.items.filter { it["isNcCard"] == "true" }
                QuickFilter.NONE -> group.items
            }

            // 2. Search Query Filter
            val filteredItems = if (query.isEmpty() || matchesEmployeeName) {
                quickFilteredItems
            } else {
                quickFilteredItems.filter { item ->
                    (item["userId"] ?: "").contains(query, true) ||
                    (item["address"] ?: "").contains(query, true) ||
                    (item["phoneNumber"] ?: "").contains(query, true) ||
                    (item["employeeName"] ?: "").contains(query, true) ||
                    (item["status"] ?: "").contains(query, true) ||
                    (item["details"] ?: "").contains(query, true) ||
                    (item["ncTag"] ?: "").contains(query, true) ||
                    (item["isNcCard"] == "true" && query.contains("New", true)) ||
                    (item["isNcCard"] == "true" && query.contains("Conn", true)) ||
                    (item["isNcCard"] == "true" && query.equals("NC", true))
                }
            }

            if (filteredItems.isNotEmpty()) {
                val totalRepeats = group.repeatCount
                val totalNc = group.ncCount
                val headerText = when {
                    totalNc > 0 && totalRepeats > 0 -> "👤 $empName — $totalRepeats Repeats | $totalNc NC"
                    totalNc > 0 && totalRepeats == 0 -> "👤 $empName — $totalNc New Connections"
                    else -> "👤 $empName — $totalRepeats Repeat Complaints"
                }

                // Add Employee Group Header
                complaintList.add(
                    mapOf(
                        "type" to "header",
                        "headerText" to headerText,
                        "count" to totalRepeats.toString(),
                        "employeeName" to empName
                    )
                )

                if (currentRepeatViewMode == RepeatViewMode.CUSTOMER) {
                    // Group filtered items by Customer ID / Name
                    val custMap = filteredItems.groupBy { it["userId"] ?: "Unknown" }
                    // Sort Customer Groups by Highest Repeat Complaints Count First
                    val sortedCustEntries = custMap.entries.sortedByDescending { entry ->
                        entry.value.count { it["isNcCard"] != "true" }
                    }

                    for (custEntry in sortedCustEntries) {
                        val custName = custEntry.key
                        val custCards = custEntry.value.sortedBy { it["createdTime"]?.toLongOrNull() ?: 0L }
                        val custRepeats = custCards.count { it["isNcCard"] != "true" }
                        val custNc = custCards.count { it["isNcCard"] == "true" }

                        // Customer Sub-Header Item
                        complaintList.add(
                            mapOf(
                                "type" to "sub_header",
                                "title" to custName,
                                "repeatCount" to custRepeats.toString(),
                                "ncCount" to custNc.toString(),
                                "isExpanded" to "true"
                            )
                        )

                        // Add Customer Cards in date ascending order
                        complaintList.addAll(custCards)
                    }
                } else {
                    // Date View: Oldest Date -> Newest Date
                    val sortedDateItems = filteredItems.sortedBy { it["createdTime"]?.toLongOrNull() ?: 0L }
                    complaintList.addAll(sortedDateItems)
                }
            }
        }

        adapter.notifyDataSetChanged()
    }

    // ---------- ALL COMPLAINTS (single employee or all employees) ----------

    private fun loadAllComplaints() {
        fetchOfficeSettingsNewConnections { officeNcMap ->
            FirebaseDatabase.getInstance()
                .getReference("complaints")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        masterAllComplaints.clear()
                        var ncCount = 0

                        officeNcMap.values.forEach { ncList ->
                            ncCount += ncList.size
                            masterAllComplaints.addAll(ncList)
                        }

                        for (cs in snapshot.children) {
                            val assignedRaw = cs.child("assignedTo").getValue(String::class.java) ?: ""
                            val assignedTo = normalizeName(assignedRaw)
                            val createdTime = cs.child("createdTime").getValue(Long::class.java) ?: 0L
                            val isNewConn = isNewConnectionNode(cs)

                            if (createdTime !in selectedStartTime..selectedEndTime) continue
                            if (!isAllEmployeesMode && assignedTo.isNotEmpty() && !assignedTo.equals(normalizeName(employeeName), true)) continue

                            val userId = cs.child("userId").getValue(String::class.java) ?: ""
                            val address = cs.child("address").getValue(String::class.java) ?: ""
                            val status = cs.child("status").getValue(String::class.java) ?: ""
                            val phoneNumber = cs.child("phoneNumber").getValue(String::class.java) ?: ""
                            val details = cs.child("details").getValue(String::class.java) ?: ""

                            if (isNewConn) {
                                ncCount++
                                masterAllComplaints.add(
                                    mapOf(
                                        "type" to "item",
                                        "isNcCard" to "true",
                                        "ncTag" to "NEW CONNECTION",
                                        "userId" to userId.ifEmpty { "New Connection" },
                                        "address" to address,
                                        "status" to status,
                                        "phoneNumber" to phoneNumber,
                                        "createdTime" to createdTime.toString(),
                                        "employeeName" to assignedRaw,
                                        "details" to details
                                    )
                                )
                                continue
                            }

                            masterAllComplaints.add(
                                mapOf(
                                    "type" to "item",
                                    "isNcCard" to "false",
                                    "userId" to userId,
                                    "address" to address,
                                    "status" to status,
                                    "phoneNumber" to phoneNumber,
                                    "createdTime" to createdTime.toString(),
                                    "employeeName" to assignedRaw,
                                    "details" to details
                                )
                            )
                        }

                        // Sort by createdTime ascending (oldest to newest)
                        masterAllComplaints.sortBy {
                            it["createdTime"]?.toLongOrNull() ?: 0L
                        }

                        val regularCount = masterAllComplaints.count { it["isNcCard"] != "true" }
                        repeatsFilterText.text = "Total: $regularCount"
                        ncFilterText.text = "NC: $ncCount"
                        updateFilterUI()
                        updateSearchSuggestions()
                        applyAllComplaintsFilter()
                    }

                    override fun onCancelled(error: DatabaseError) {}
                })
        }
    }

    private fun updateSearchSuggestions() {
        val suggestions = mutableSetOf<String>()
        suggestions.add("New Connection")
        suggestions.add("NC")

        if (isRepeatMode) {
            for (group in masterRepeatGroups) {
                suggestions.add(group.employeeName)
                group.items.forEach { item ->
                    item["userId"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
                    item["address"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
                    item["phoneNumber"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
                }
            }
        } else {
            masterAllComplaints.forEach { item ->
                item["userId"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
                item["address"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
                item["employeeName"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
            }
        }

        val suggestionAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions.toList())
        searchEditText.setAdapter(suggestionAdapter)
    }

    private fun applyAllComplaintsFilter() {
        complaintList.clear()
        val query = searchEditText.text.toString().trim()

        val quickFilteredItems = when (currentQuickFilter) {
            QuickFilter.REPEATS_ONLY -> masterAllComplaints.filter { it["isNcCard"] != "true" }
            QuickFilter.NC_ONLY -> masterAllComplaints.filter { it["isNcCard"] == "true" }
            QuickFilter.NONE -> masterAllComplaints
        }

        for (item in quickFilteredItems) {
            val address = item["address"] ?: ""
            val userId = item["userId"] ?: ""
            val emp = item["employeeName"] ?: ""
            val isNc = item["isNcCard"] == "true"

            val matches = query.isEmpty() ||
                          userId.contains(query, true) ||
                          address.contains(query, true) ||
                          emp.contains(query, true) ||
                          (isNc && query.contains("New", true)) ||
                          (isNc && query.contains("Conn", true)) ||
                          (isNc && query.equals("NC", true))

            if (matches) {
                complaintList.add(item)
            }
        }
        adapter.notifyDataSetChanged()
    }
}

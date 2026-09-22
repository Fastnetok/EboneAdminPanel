package com.example.eboneadminpanel

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
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

// Special value used when opening this screen from the header's
// "Repeat Complaints" total box (i.e. not scoped to one employee)
const val ALL_EMPLOYEES_MARKER = "ALL"

class EmployeeReportDetailsActivity : AppCompatActivity() {

    private lateinit var searchEditText: AutoCompleteTextView
    private lateinit var dateRangeButton: ImageButton
    private lateinit var employeeSpinner: Spinner
    private lateinit var totalRepeatsTextView: TextView
    private lateinit var adapter: RepeatComplaintAdapter
    private val complaintList = mutableListOf<Map<String, String>>()

    private val employeeNamesList = mutableListOf<String>()
    private lateinit var spinnerAdapter: ArrayAdapter<String>

    // Master (unfiltered) data — search filters from these
    private val masterRepeatGroups = mutableListOf<Pair<String, List<Map<String, String>>>>()
    private val masterAllComplaints = mutableListOf<Map<String, String>>()

    private var isRepeatMode = false
    private var isAllEmployeesMode = false
    private var employeeName: String = ""

    private var selectedStartTime: Long = 0L
    private var selectedEndTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_employee_report_details)

        employeeName = intent.getStringExtra("employeeName") ?: "Employee"
        val showRepeat = intent.getBooleanExtra("showRepeat", false)

        isRepeatMode = showRepeat
        isAllEmployeesMode = employeeName == ALL_EMPLOYEES_MARKER

        findViewById<TextView>(R.id.employeeNameText).text =
            if (isAllEmployeesMode) "👥 All Employees" else "👤 $employeeName"

        val recyclerView = findViewById<RecyclerView>(R.id.detailRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = RepeatComplaintAdapter(complaintList)
        recyclerView.adapter = adapter

        searchEditText = findViewById(R.id.searchDetailEditText)
        dateRangeButton = findViewById(R.id.dateRangePickerButton)
        employeeSpinner = findViewById(R.id.employeeSpinner)
        totalRepeatsTextView = findViewById(R.id.totalRepeatsText)

        setupEmployeeSpinner()

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
        selectedEndTime = cal.timeInMillis

        if (showRepeat) {
            loadRepeatComplaints()
        } else {
            loadAllComplaints()
        }

        dateRangeButton.setOnClickListener {
            showDateRangePicker()
        }

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isRepeatMode) applyRepeatFilter() else applyAllComplaintsFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupEmployeeSpinner() {
        employeeNamesList.clear()

        // Use normalized name for consistency
        val initialDisplayName = if (isAllEmployeesMode) "👥 All Employees" else "👤 ${normalizeName(employeeName)}"
        employeeNamesList.add(initialDisplayName)

        spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, employeeNamesList)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        employeeSpinner.adapter = spinnerAdapter

        // Load all active employee names from Firebase
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

        // Restore or set initial selection based on passed employee name
        val index = employeeNamesList.indexOfFirst { it.equals(targetName, ignoreCase = true) }
        if (index >= 0) {
            employeeSpinner.setSelection(index, false)
        } else if (!isAllEmployeesMode) {
            // If passed employee name not in list yet, add it temporarily or default to ALL
            employeeNamesList.add("👤 ${normalizeName(employeeName)}")
            spinnerAdapter.notifyDataSetChanged()
            employeeSpinner.setSelection(employeeNamesList.size - 1, false)
        }
    }

    private fun showDateRangePicker() {
        val builder = MaterialDatePicker.Builder.dateRangePicker()
        builder.setTitleText("Select Date Range")
        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { range ->
            // Adjust to local time if needed, MaterialDatePicker returns UTC
            selectedStartTime = range.first
            selectedEndTime = range.second + (24 * 60 * 60 * 1000) - 1 // End of day

            if (isRepeatMode) loadRepeatComplaints() else loadAllComplaints()
        }
        picker.show(supportFragmentManager, "date_picker")
    }

    // ---------- REPEAT COMPLAINTS (single employee OR all employees) ----------

    private fun loadRepeatComplaints() {
        val userIdMap = mutableMapOf<String, MutableList<Map<String, String>>>()
        var newConnectionCount = 0

        FirebaseDatabase.getInstance()
            .getReference("complaints")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {

                    for (cs in snapshot.children) {
                        val assignedRaw = cs.child("assignedTo").getValue(String::class.java) ?: ""
                        val assignedTo = normalizeName(assignedRaw)
                        val createdTime = cs.child("createdTime").getValue(Long::class.java) ?: 0L
                        val isNewConnection = cs.child("isNewConnection").getValue(Boolean::class.java) ?: false

                        if (createdTime !in selectedStartTime..selectedEndTime) continue
                        if (!isAllEmployeesMode && !assignedTo.equals(normalizeName(employeeName), true)) continue

                        if (isNewConnection) {
                            newConnectionCount++
                            continue
                        }

                        val userId = cs.child("userId").getValue(String::class.java) ?: ""
                        if (userId.isEmpty()) continue

                        val address = cs.child("address").getValue(String::class.java) ?: ""
                        val status = cs.child("status").getValue(String::class.java) ?: ""
                        val phoneNumber = cs.child("phoneNumber").getValue(String::class.java) ?: ""

                        if (!userIdMap.containsKey(userId))
                            userIdMap[userId] = mutableListOf()

                        val itemMap = mutableMapOf(
                            "userId" to userId,
                            "address" to address,
                            "status" to status,
                            "phoneNumber" to phoneNumber,
                            "createdTime" to createdTime.toString(),
                            "source" to "complaints"
                        )
                        if (isAllEmployeesMode) itemMap["employeeName"] = assignedRaw

                        userIdMap[userId]?.add(itemMap)
                    }

                    // resolvedComplaints node se
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
                                    val assignedTo = normalizeName(assignedRaw)
                                    val createdTime = cs.child("createdTime").getValue(Long::class.java) ?: 0L
                                    val isNewConnection = cs.child("isNewConnection").getValue(Boolean::class.java) ?: false

                                    if (createdTime !in selectedStartTime..selectedEndTime) continue
                                    if (!isAllEmployeesMode && !assignedTo.equals(normalizeName(employeeName), true)) continue

                                    if (isNewConnection) {
                                        newConnectionCount++
                                        continue
                                    }

                                    val complaintId = cs.child("complaintId")
                                        .getValue(String::class.java) ?: cs.key ?: ""
                                    if (complaintsIds.contains(complaintId)) continue

                                    val userId = cs.child("userId").getValue(String::class.java) ?: ""
                                    if (userId.isEmpty()) continue

                                    val address = cs.child("address").getValue(String::class.java) ?: ""
                                    val phoneNumber = cs.child("phoneNumber").getValue(String::class.java) ?: ""

                                    if (!userIdMap.containsKey(userId))
                                        userIdMap[userId] = mutableListOf()

                                    val itemMap = mutableMapOf(
                                        "userId" to userId,
                                        "address" to address,
                                        "status" to "Resolved",
                                        "phoneNumber" to phoneNumber,
                                        "createdTime" to createdTime.toString(),
                                        "source" to "resolvedComplaints"
                                    )
                                    if (isAllEmployeesMode) itemMap["employeeName"] = assignedRaw

                                    userIdMap[userId]?.add(itemMap)
                                }

                                masterRepeatGroups.clear()
                                for (entry in userIdMap) {
                                    if (entry.value.size > 1) {
                                        val sortedItems = entry.value.sortedBy {
                                            it["createdTime"]?.toLongOrNull() ?: 0L
                                        }
                                        masterRepeatGroups.add(Pair(entry.key, sortedItems))
                                    }
                                }

                                masterRepeatGroups.sortByDescending { it.second.size }

                                totalRepeatsTextView.text = "Repeats: ${masterRepeatGroups.size} | NC: $newConnectionCount"
                                updateSearchSuggestions()
                                applyRepeatFilter()
                            }

                            override fun onCancelled(error: DatabaseError) {}
                        })
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun applyRepeatFilter() {
        complaintList.clear()
        val query = searchEditText.text.toString().trim()

        for ((userId, items) in masterRepeatGroups) {
            val matches = query.isEmpty() ||
                          userId.contains(query, true) ||
                          items.any { (it["address"] ?: "").contains(query, true) } ||
                          items.any { (it["employeeName"] ?: "").contains(query, true) }

            if (!matches) continue

            val firstTime = items.minOfOrNull { it["createdTime"]?.toLongOrNull() ?: Long.MAX_VALUE } ?: 0L
            val lastTime = items.maxOfOrNull { it["createdTime"]?.toLongOrNull() ?: 0L } ?: 0L
            val diffMs = lastTime - firstTime
            val days = (diffMs / (1000 * 60 * 60 * 24)) + 1 // Inclusive of the day

            complaintList.add(
                mapOf(
                    "type" to "header",
                    "userId" to userId,
                    "count" to items.size.toString(),
                    "days" to days.toString()
                )
            )
            complaintList.addAll(items.map { it + mapOf("type" to "item") })
        }

        adapter.notifyDataSetChanged()
    }

    // ---------- ALL COMPLAINTS (single employee only) ----------

    private fun loadAllComplaints() {
        FirebaseDatabase.getInstance()
            .getReference("complaints")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    masterAllComplaints.clear()
                    var newConnectionCount = 0

                    for (cs in snapshot.children) {
                        val assignedRaw = cs.child("assignedTo").getValue(String::class.java) ?: ""
                        val assignedTo = normalizeName(assignedRaw)
                        val createdTime = cs.child("createdTime").getValue(Long::class.java) ?: 0L
                        val isNewConnection = cs.child("isNewConnection").getValue(Boolean::class.java) ?: false

                        if (createdTime !in selectedStartTime..selectedEndTime) continue
                        if (!isAllEmployeesMode && !assignedTo.equals(normalizeName(employeeName), true)) continue

                        if (isNewConnection) {
                            newConnectionCount++
                            continue
                        }

                        val userId = cs.child("userId").getValue(String::class.java) ?: ""
                        val address = cs.child("address").getValue(String::class.java) ?: ""
                        val status = cs.child("status").getValue(String::class.java) ?: ""
                        val phoneNumber = cs.child("phoneNumber").getValue(String::class.java) ?: ""

                        masterAllComplaints.add(
                            mapOf(
                                "type" to "item",
                                "userId" to userId,
                                "address" to address,
                                "status" to status,
                                "phoneNumber" to phoneNumber,
                                "createdTime" to createdTime.toString()
                            )
                        )
                    }

                    // Sort by createdTime (newest first for all complaints)
                    masterAllComplaints.sortByDescending {
                        it["createdTime"]?.toLongOrNull() ?: 0L
                    }

                    totalRepeatsTextView.text = "Total: ${masterAllComplaints.size} | NC: $newConnectionCount"
                    updateSearchSuggestions()
                    applyAllComplaintsFilter()
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun updateSearchSuggestions() {
        val suggestions = mutableSetOf<String>()

        if (isRepeatMode) {
            for ((userId, items) in masterRepeatGroups) {
                suggestions.add(userId)
                items.forEach { item ->
                    item["address"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
                    item["employeeName"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
                }
            }
        } else {
            masterAllComplaints.forEach { item ->
                item["userId"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
                item["address"]?.let { if (it.isNotEmpty()) suggestions.add(it) }
            }
        }

        val suggestionAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions.toList())
        searchEditText.setAdapter(suggestionAdapter)
    }

    private fun applyAllComplaintsFilter() {
        complaintList.clear()
        val query = searchEditText.text.toString().trim()

        for (item in masterAllComplaints) {
            val address = item["address"] ?: ""
            val userId = item["userId"] ?: ""

            val matches = query.isEmpty() ||
                          userId.contains(query, true) ||
                          address.contains(query, true)

            if (matches) {
                complaintList.add(item)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun extractArea(address: String): String {
        if (address.isBlank()) return "Unknown"
        return address.split("/").firstOrNull()?.trim() ?: address.trim()
    }
}

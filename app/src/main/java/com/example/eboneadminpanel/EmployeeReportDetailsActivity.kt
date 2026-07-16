package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

// Special value used when opening this screen from the header's
// "Repeat Complaints" total box (i.e. not scoped to one employee)
const val ALL_EMPLOYEES_MARKER = "ALL"

class EmployeeReportDetailsActivity : AppCompatActivity() {

    private lateinit var searchEditText: EditText
    private lateinit var adapter: RepeatComplaintAdapter
    private val complaintList = mutableListOf<Map<String, String>>()

    // Master (unfiltered) data — search filters from these
    private val masterRepeatGroups = mutableListOf<Pair<String, List<Map<String, String>>>>()
    private val masterAllComplaints = mutableListOf<Map<String, String>>()

    private var isRepeatMode = false
    private var isAllEmployeesMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_employee_report_details)

        val employeeName = intent.getStringExtra("employeeName") ?: "Employee"
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
        searchEditText.hint = if (isAllEmployeesMode)
            "🔍 Search by employee name"
        else
            "🔍 Search by area / address"

        val summaryText = findViewById<TextView>(R.id.summaryText)

        if (showRepeat) {
            summaryText.text = if (isAllEmployeesMode)
                "⚠️ Repeat Complaints — All Employees"
            else
                "⚠️ Repeat Complaints"
            loadRepeatComplaints(employeeName)
        } else {
            summaryText.text = "📋 All Complaints"
            loadAllComplaints(employeeName)
        }

        searchEditText.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s.toString().trim()
                    if (isRepeatMode) {
                        applyRepeatFilter(query)
                    } else {
                        applyAllComplaintsFilter(query)
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
    }

    // ---------- REPEAT COMPLAINTS (single employee OR all employees) ----------

    private fun loadRepeatComplaints(employeeName: String) {
        val userIdMap = mutableMapOf<String, MutableList<Map<String, String>>>()

        FirebaseDatabase.getInstance()
            .getReference("complaints")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {

                    for (cs in snapshot.children) {
                        val assignedTo = cs.child("assignedTo")
                            .getValue(String::class.java) ?: ""
                        val userId = cs.child("userId")
                            .getValue(String::class.java) ?: ""
                        val address = cs.child("address")
                            .getValue(String::class.java) ?: ""
                        val status = cs.child("status")
                            .getValue(String::class.java) ?: ""

                        if (!isAllEmployeesMode && !assignedTo.equals(employeeName, true)) continue
                        if (userId.isEmpty()) continue

                        if (!userIdMap.containsKey(userId))
                            userIdMap[userId] = mutableListOf()

                        val itemMap = mutableMapOf(
                            "userId" to userId,
                            "address" to address,
                            "status" to status,
                            "source" to "complaints"
                        )
                        if (isAllEmployeesMode) itemMap["employeeName"] = assignedTo

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
                                    val complaintId = cs.child("complaintId")
                                        .getValue(String::class.java) ?: cs.key ?: ""
                                    val assignedTo = cs.child("assignedTo")
                                        .getValue(String::class.java) ?: ""
                                    val userId = cs.child("userId")
                                        .getValue(String::class.java) ?: ""
                                    val address = cs.child("address")
                                        .getValue(String::class.java) ?: ""

                                    if (!isAllEmployeesMode && !assignedTo.equals(employeeName, true)) continue
                                    if (userId.isEmpty()) continue
                                    if (complaintsIds.contains(complaintId)) continue

                                    if (!userIdMap.containsKey(userId))
                                        userIdMap[userId] = mutableListOf()

                                    val itemMap = mutableMapOf(
                                        "userId" to userId,
                                        "address" to address,
                                        "status" to "Resolved",
                                        "source" to "resolvedComplaints"
                                    )
                                    if (isAllEmployeesMode) itemMap["employeeName"] = assignedTo

                                    userIdMap[userId]?.add(itemMap)
                                }

                                // Sirf repeat wale — 2+ baar aane wale userId
                                masterRepeatGroups.clear()
                                for (entry in userIdMap) {
                                    if (entry.value.size > 1) {
                                        masterRepeatGroups.add(Pair(entry.key, entry.value))
                                    }
                                }
                                // Sabse zyada repeat wale upar
                                masterRepeatGroups.sortByDescending { it.second.size }

                                applyRepeatFilter("")
                            }

                            override fun onCancelled(error: DatabaseError) {}
                        })
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun applyRepeatFilter(query: String) {
        complaintList.clear()

        for ((userId, items) in masterRepeatGroups) {
            val matches = if (query.isEmpty()) {
                true
            } else {
                items.any { (it["employeeName"] ?: "").contains(query, true) } ||
                        userId.contains(query, true) ||
                        items.any { (it["address"] ?: "").contains(query, true) }
            }
            if (!matches) continue

            complaintList.add(
                mapOf(
                    "type" to "header",
                    "userId" to userId,
                    "count" to items.size.toString()
                )
            )
            complaintList.addAll(items.map { it + mapOf("type" to "item") })
        }

        adapter.notifyDataSetChanged()
    }

    // ---------- ALL COMPLAINTS (single employee only) ----------

    private fun loadAllComplaints(employeeName: String) {
        FirebaseDatabase.getInstance()
            .getReference("complaints")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    masterAllComplaints.clear()

                    for (cs in snapshot.children) {
                        val assignedTo = cs.child("assignedTo")
                            .getValue(String::class.java) ?: ""
                        val userId = cs.child("userId")
                            .getValue(String::class.java) ?: ""
                        val address = cs.child("address")
                            .getValue(String::class.java) ?: ""
                        val status = cs.child("status")
                            .getValue(String::class.java) ?: ""

                        if (!assignedTo.equals(employeeName, true)) continue

                        masterAllComplaints.add(
                            mapOf(
                                "type" to "item",
                                "userId" to userId,
                                "address" to address,
                                "status" to status
                            )
                        )
                    }

                    applyAllComplaintsFilter("")
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun applyAllComplaintsFilter(query: String) {
        complaintList.clear()
        if (query.isEmpty()) {
            complaintList.addAll(masterAllComplaints)
        } else {
            for (item in masterAllComplaints) {
                val address = item["address"] ?: ""
                val userId = item["userId"] ?: ""
                if (address.contains(query, true) || userId.contains(query, true)) {
                    complaintList.add(item)
                }
            }
        }
        adapter.notifyDataSetChanged()
    }
}
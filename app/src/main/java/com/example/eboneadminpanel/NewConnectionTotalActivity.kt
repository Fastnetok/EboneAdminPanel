package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

/*
 * Mirrors TotalComplaintsActivity exactly: lists EVERY employee (even
 * those with 0), each showing their current New Connection count.
 * Clicking an employee opens NewConnectionEmployeeDetailsActivity
 * (already built) — same as TotalComplaintAdapter ->
 * EmployeeComplaintsActivity.
 */
class NewConnectionTotalActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: NewConnectionSummaryAdapter
    private val summaryList = mutableListOf<NewConnectionEmployeeSummary>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_connections)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvTitle).text = "TOTAL CONNECTIONS"

        recycler = findViewById(R.id.recyclerNewConnections)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = NewConnectionSummaryAdapter(summaryList)
        recycler.adapter = adapter

        loadEmployeeSummary()
    }

    private fun loadEmployeeSummary() {

        FirebaseDatabase.getInstance()
            .getReference("employees")
            .addValueEventListener(object : ValueEventListener {

                override fun onDataChange(employeeSnapshot: DataSnapshot) {

                    FirebaseDatabase.getInstance()
                        .getReference("officeSettings/new_connections/gift_box")
                        .addValueEventListener(object : ValueEventListener {

                            override fun onDataChange(giftBoxSnapshot: DataSnapshot) {

                                summaryList.clear()

                                val employeeMap = HashMap<String, Int>()

                                // Start every known employee at 0 — same as complaints
                                for (employee in employeeSnapshot.children) {
                                    val employeeName = employee.child("employeeName")
                                        .getValue(String::class.java) ?: continue
                                    employeeMap[employeeName] = 0
                                }

                                // Count current gift_box items per employee
                                for (employeeNode in giftBoxSnapshot.children) {
                                    val employeeName = employeeNode.key ?: continue
                                    val count = employeeNode.childrenCount.toInt()
                                    employeeMap[employeeName] = count
                                }

                                for (entry in employeeMap.entries) {
                                    summaryList.add(
                                        NewConnectionEmployeeSummary(
                                            entry.key,
                                            entry.value
                                        )
                                    )
                                }

                                summaryList.sortByDescending { it.totalConnections }

                                adapter.notifyDataSetChanged()
                            }

                            override fun onCancelled(error: DatabaseError) {}
                        })
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }
}
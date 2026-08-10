package com.example.eboneadminpanel

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class TotalComplaintsActivity :
    AppCompatActivity() {

    private lateinit var recyclerView:
            RecyclerView

    private lateinit var adapter:
            TotalComplaintAdapter

    private val summaryList =
        mutableListOf<EmployeeComplaintSummary>()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_total_complaints
        )

        recyclerView =
            findViewById(
                R.id.recyclerTotalComplaints
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            TotalComplaintAdapter(
                summaryList
            )

        recyclerView.adapter =
            adapter

        loadEmployeeSummary()

    }

    private fun loadEmployeeSummary() {

        FirebaseDatabase
            .getInstance()
            .getReference("employees")
            .addValueEventListener(

                object : ValueEventListener {

                    override fun onDataChange(
                        employeeSnapshot: DataSnapshot
                    ) {

                        FirebaseDatabase
                            .getInstance()
                            .getReference("complaints")
                            .addValueEventListener(

                                object : ValueEventListener {

                                    override fun onDataChange(
                                        complaintSnapshot: DataSnapshot
                                    ) {

                                        summaryList.clear()

                                        val employeeMap =
                                            HashMap<String, Int>()

                                        for (
                                        employee
                                        in employeeSnapshot.children
                                        ) {

                                            val employeeName =
                                                employee.child(
                                                    "employeeName"
                                                ).getValue(
                                                    String::class.java
                                                ) ?: continue

                                            employeeMap[
                                                employeeName
                                            ] = 0
                                        }

                                        for (
                                        complaint
                                        in complaintSnapshot.children
                                        ) {

                                            val assignedTo =
                                                complaint.child(
                                                    "assignedTo"
                                                ).getValue(
                                                    String::class.java
                                                ) ?: continue

                                            val status =
                                                complaint.child(
                                                    "status"
                                                ).getValue(
                                                    String::class.java
                                                ) ?: ""

                                            if (
                                                status.equals(
                                                    "Resolved",
                                                    true
                                                )
                                            ) {

                                                continue
                                            }

                                            employeeMap[
                                                assignedTo
                                            ] =
                                                (
                                                        employeeMap[
                                                            assignedTo
                                                        ] ?: 0
                                                        ) + 1
                                        }

                                        for (
                                        entry
                                        in employeeMap.entries
                                        ) {

                                            summaryList.add(

                                                EmployeeComplaintSummary(

                                                    entry.key,

                                                    entry.value

                                                )

                                            )
                                        }

                                        summaryList.sortByDescending {
                                            it.totalComplaints
                                        }

                                        adapter.notifyDataSetChanged()

                                    }

                                    override fun onCancelled(
                                        error: DatabaseError
                                    ) {
                                    }

                                }

                            )

                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }

                }

            )

    }
}
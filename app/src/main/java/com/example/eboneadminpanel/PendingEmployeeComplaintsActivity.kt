package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class PendingEmployeeComplaintsActivity :
    AppCompatActivity() {

    private lateinit var recyclerView:
            RecyclerView

    private lateinit var adapter:
            EmployeeComplaintAdapter

    private val complaintList =
        mutableListOf<Complaint>()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_pending_employee_complaints
        )

        val employeeName =
            intent.getStringExtra(
                "employeeName"
            ) ?: ""

        val titleText =
            findViewById<TextView>(
                R.id.titleText
            )

        titleText.text =
            employeeName +
                    " Pending Complaints"

        recyclerView =
            findViewById(
                R.id.recyclerPendingEmployeeComplaints
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            EmployeeComplaintAdapter(
                complaintList
            )

        recyclerView.adapter =
            adapter

        loadPendingComplaints(
            employeeName
        )

    }

    private fun loadPendingComplaints(
        employeeName: String
    ) {

        FirebaseDatabase
            .getInstance()
            .getReference(
                "complaints"
            )
            .addValueEventListener(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        complaintList.clear()

                        val employeeComplaints =
                            mutableListOf<Complaint>()

                        for (
                        item in snapshot.children
                        ) {

                            val complaint =
                                item.getValue(
                                    Complaint::class.java
                                ) ?: continue

                            if (
                                complaint.assignedTo ==
                                employeeName
                                &&
                                complaint.status !=
                                "Resolved"
                            ) {

                                employeeComplaints.add(
                                    complaint
                                )

                            }

                        }

                        employeeComplaints.sortByDescending {

                            it.assignedTime

                        }

                        if (
                            employeeComplaints.size > 1
                        ) {

                            for (
                            i in 1 until
                                    employeeComplaints.size
                            ) {

                                complaintList.add(
                                    employeeComplaints[i]
                                )

                            }

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

}
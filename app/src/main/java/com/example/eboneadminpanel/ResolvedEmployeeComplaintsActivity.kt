package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class ResolvedEmployeeComplaintsActivity :
    AppCompatActivity() {

    private lateinit var recyclerView:
            RecyclerView

    private lateinit var adapter:
            ResolvedComplaintAdapter

    private val complaintList =
        mutableListOf<Complaint>()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_resolved_employee_complaints
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
                    " Resolved Complaints"

        recyclerView =
            findViewById(
                R.id.recyclerResolvedEmployeeComplaints
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            ResolvedComplaintAdapter(
                complaintList
            )

        recyclerView.adapter =
            adapter

        loadResolvedComplaints(
            employeeName
        )

    }

    private fun loadResolvedComplaints(
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

                        val todayStart =
                            TodayFilter.getTodayStart()

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
                                complaint.status ==
                                "Resolved"
                                &&
                                complaint.resolvedTime >=
                                todayStart
                            ) {

                                complaintList.add(
                                    complaint
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
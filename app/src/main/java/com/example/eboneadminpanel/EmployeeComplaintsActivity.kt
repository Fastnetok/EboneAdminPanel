package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class EmployeeComplaintsActivity :
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
            R.layout.activity_employee_complaints
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
                    " Complaints"

        recyclerView =
            findViewById(
                R.id.recyclerEmployeeComplaints
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            EmployeeComplaintAdapter(
                complaintList
            )

        recyclerView.adapter =
            adapter

        loadComplaints(
            employeeName
        )

    }

    private fun loadComplaints(
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

                        for (
                        item
                        in snapshot.children
                        ) {

                            val complaint =
                                item.getValue(
                                    Complaint::class.java
                                ) ?: continue

                            if (
                                complaint.assignedTo
                                == employeeName
                                &&
                                !complaint.status.equals(
                                    "Resolved",
                                    true
                                )
                            ) {

                                complaintList.add(
                                    complaint
                                )

                            }
                        }
                        complaintList.sortBy {
                            it.displayOrder
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
package com.example.eboneadminpanel

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class ProgressActivity :
    AppCompatActivity() {

    private lateinit var recyclerView:
            RecyclerView

    private lateinit var adapter:
            ProgressAdapter

    private val complaintList =
        mutableListOf<Complaint>()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_progress
        )

        recyclerView =
            findViewById(
                R.id.recyclerProgress
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            ProgressAdapter(
                complaintList
            )

        recyclerView.adapter =
            adapter

        loadProgressComplaints()

    }

    private fun loadProgressComplaints() {

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

                        val latestComplaintMap =
                            HashMap<String, Complaint>()

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
                                    .isNotEmpty()
                                &&
                                complaint.status
                                != "Resolved"
                            ) {

                                val employeeName =
                                    complaint.assignedTo

                                val oldComplaint =
                                    latestComplaintMap[
                                        employeeName
                                    ]

                                if (
                                    oldComplaint == null
                                    ||
                                    complaint.displayOrder <
                                    oldComplaint.displayOrder
                                ) {

                                    latestComplaintMap[
                                        employeeName
                                    ] = complaint

                                }

                            }

                        }

                        complaintList.addAll(
                            latestComplaintMap.values
                        )

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
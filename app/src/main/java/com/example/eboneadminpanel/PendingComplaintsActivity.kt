package com.example.eboneadminpanel

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class PendingComplaintsActivity :
    AppCompatActivity() {

    private lateinit var recyclerView:
            RecyclerView

    private lateinit var adapter:
            ComplaintAdapter

    private val complaintList =
        mutableListOf<Complaint>()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_pending_complaints
        )

        recyclerView =
            findViewById(
                R.id.recyclerView
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            ComplaintAdapter(
                complaintList
            )

        recyclerView.adapter =
            adapter

        loadComplaints()

    }

    private fun loadComplaints() {

        val filterType =
            intent.getStringExtra(
                "filterType"
            ) ?: "Pending"

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
                        complaintSnapshot
                        in snapshot.children
                        ) {

                            val complaint =
                                complaintSnapshot
                                    .getValue(
                                        Complaint::class.java
                                    )

                            if (
                                complaint == null
                            ) {
                                continue
                            }

                            when (
                                filterType
                            ) {

                                "Pending" -> {

                                    if (
                                        complaint.status ==
                                        "Pending"
                                    ) {

                                        complaintList.add(
                                            complaint
                                        )

                                    }

                                }

                                "Progress" -> {

                                    if (
                                        complaint.status ==
                                        "Progress"
                                    ) {

                                        complaintList.add(
                                            complaint
                                        )

                                    }

                                }

                                "Resolved" -> {

                                    if (
                                        complaint.status ==
                                        "Resolved"
                                    ) {

                                        complaintList.add(
                                            complaint
                                        )

                                    }

                                }

                                "Today" -> {

                                    if (
                                        complaint.createdTime >=
                                        TodayFilter.getTodayStart()
                                    ) {

                                        complaintList.add(
                                            complaint
                                        )

                                    }

                                }

                                "Week" -> {

                                    val weekStart =
                                        System.currentTimeMillis() -
                                                (7L * 24 * 60 * 60 * 1000)

                                    if (
                                        complaint.createdTime >=
                                        weekStart
                                    ) {

                                        complaintList.add(
                                            complaint
                                        )

                                    }

                                }

                                "Month" -> {

                                    val monthStart =
                                        System.currentTimeMillis() -
                                                (30L * 24 * 60 * 60 * 1000)

                                    if (
                                        complaint.createdTime >=
                                        monthStart
                                    ) {

                                        complaintList.add(
                                            complaint
                                        )

                                    }

                                }

                                else -> {

                                    complaintList.add(
                                        complaint
                                    )

                                }

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
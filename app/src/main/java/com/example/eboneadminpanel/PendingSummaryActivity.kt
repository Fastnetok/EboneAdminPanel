package com.example.eboneadminpanel

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class PendingSummaryActivity :
    AppCompatActivity() {

    private lateinit var recyclerView:
            RecyclerView

    private lateinit var adapter:
            PendingSummaryAdapter

    private val employeeList =
        mutableListOf<String>()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_pending_summary
        )

        recyclerView =
            findViewById(
                R.id.recyclerPendingSummary
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            PendingSummaryAdapter(
                employeeList
            )

        recyclerView.adapter =
            adapter

        loadPendingComplaints()

    }

    private fun loadPendingComplaints() {

        FirebaseDatabase
            .getInstance()
            .getReference("complaints")
            .addValueEventListener(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        employeeList.clear()

                        val totalMap =
                            HashMap<String, Int>()

                        for (
                        item in snapshot.children
                        ) {

                            val complaint =
                                item.getValue(
                                    Complaint::class.java
                                ) ?: continue

                            if (
                                complaint.assignedTo.isNotEmpty()
                                &&
                                complaint.status != "Resolved"
                            ) {

                                val employee =
                                    complaint.assignedTo

                                totalMap[employee] =
                                    (totalMap[employee]
                                        ?: 0) + 1

                            }

                        }

                        for (
                        employee in totalMap.keys
                        ) {

                            val total =
                                totalMap[employee]
                                    ?: 0

                            val pending =
                                total - 1

                            if (
                                pending > 0
                            ) {

                                employeeList.add(

                                    employee +
                                            " (" +
                                            pending +
                                            ")"

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
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

                            /*
                             * Pending Summary:
                             *
                             * Employee کی تمام active
                             * assigned complaints count کریں۔
                             *
                             * Resolved complaints شامل نہیں ہوں گی۔
                             */
                            if (
                                complaint.assignedTo.isNotEmpty()
                                &&
                                !complaint.status.equals(
                                    "Resolved",
                                    true
                                )
                            ) {

                                val employee =
                                    complaint.assignedTo

                                totalMap[employee] =
                                    (totalMap[employee] ?: 0) + 1
                            }
                        }

                        /*
                         * Progress کی ایک complaint employee کے
                         * Dashboard/Progress میں رہتی ہے۔
                         *
                         * باقی complaints Pending ہوتی ہیں۔
                         */
                        for (
                        entry in totalMap.entries
                        ) {

                            val employee =
                                entry.key

                            val total =
                                entry.value

                            val pending =
                                if (total > 1) {
                                    total - 1
                                } else {
                                    0
                                }

                            if (pending > 0) {

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
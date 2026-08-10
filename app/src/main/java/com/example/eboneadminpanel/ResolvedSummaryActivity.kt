package com.example.eboneadminpanel

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class ResolvedSummaryActivity :
    AppCompatActivity() {

    private lateinit var recyclerView:
            RecyclerView

    private lateinit var adapter:
            ResolvedSummaryAdapter

    private val employeeList =
        mutableListOf<String>()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_resolved_summary
        )

        recyclerView =
            findViewById(
                R.id.recyclerResolvedSummary
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            ResolvedSummaryAdapter(
                employeeList
            )

        recyclerView.adapter =
            adapter

        loadResolvedComplaints()

    }

    private fun loadResolvedComplaints() {

        FirebaseDatabase
            .getInstance()
            .getReference("complaints")
            .addValueEventListener(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        employeeList.clear()

                        val todayStart =
                            TodayFilter.getTodayStart()

                        val countMap =
                            HashMap<String, Int>()

                        for (
                        item in snapshot.children
                        ) {

                            val complaint =
                                item.getValue(
                                    Complaint::class.java
                                ) ?: continue

                            if (
                                complaint.status ==
                                "Resolved"
                                &&
                                complaint.resolvedTime >=
                                todayStart
                            ) {

                                val employee =
                                    complaint.assignedTo

                                if (
                                    employee.isNotEmpty()
                                ) {

                                    countMap[employee] =
                                        (
                                                countMap[employee]
                                                    ?: 0
                                                ) + 1

                                }

                            }

                        }

                        for (
                        entry in countMap
                        ) {

                            employeeList.add(

                                entry.key +
                                        " (" +
                                        entry.value +
                                        ")"

                            )

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
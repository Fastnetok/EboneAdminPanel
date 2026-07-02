package com.example.eboneadminpanel

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class AssignComplaintsActivity :
    AppCompatActivity() {

    private lateinit var recyclerView:
            RecyclerView

    private lateinit var adapter:
            AssignComplaintAdapter

    private val complaintList =
        mutableListOf<Complaint>()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_assign_complaints
        )

        recyclerView =
            findViewById(
                R.id.recyclerAssignComplaints
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            AssignComplaintAdapter(
                complaintList
            )

        recyclerView.adapter =
            adapter

        loadPendingComplaints()

    }

    private fun loadPendingComplaints() {

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
                                )
                                    ?: continue

                            if (
                                complaint.status ==
                                "Pending"
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
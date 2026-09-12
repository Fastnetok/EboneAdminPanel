package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

/*
 * Exact copy of PendingSummaryActivity.kt's rule (Complaints):
 * for each employee, total items held minus 1 (their front-of-queue
 * item, already shown in Progress) = Pending. Reuses the generic
 * activity_new_connections layout, same as
 * NewConnectionProgressActivity / NewConnectionTotalActivity.
 *
 * Source data is gift_box directly (not a flat "complaints"-style
 * node with a status field) — every item in gift_box is by definition
 * still active (installed items are moved out to "completed"), so no
 * extra status filtering is needed here, unlike PendingSummaryActivity.
 */
class NewConnectionPendingSummaryActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: NewConnectionPendingSummaryAdapter
    private val employeeList = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_connections)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvTitle).text = "PENDING CONNECTIONS"

        recycler = findViewById(R.id.recyclerNewConnections)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = NewConnectionPendingSummaryAdapter(employeeList)
        recycler.adapter = adapter

        loadPendingSummary()
    }

    private fun loadPendingSummary() {
        FirebaseDatabase.getInstance()
            .getReference("officeSettings/new_connections/gift_box")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    employeeList.clear()

                    for (employeeNode in snapshot.children) {
                        val employee = employeeNode.key ?: continue
                        val total = employeeNode.childrenCount.toInt()

                        // SAME RULE: one item per employee is their
                        // Progress (front-of-queue) item — everything
                        // else is Pending.
                        val pending = if (total > 1) total - 1 else 0

                        if (pending > 0) {
                            employeeList.add("$employee ($pending)")
                        }
                    }

                    adapter.notifyDataSetChanged()
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }
}
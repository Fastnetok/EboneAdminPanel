package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

/*
 * Exact copy of PendingSummaryActivity.kt's rule (Complaints):
 * Displays all pending connections — both unassigned intake pool
 * and queued items held by employees beyond their active Progress item.
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
        val root = FirebaseDatabase.getInstance().getReference("officeSettings/new_connections")
        
        root.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                employeeList.clear()

                // 1. Unassigned Intake pending connections
                val pendingNode = snapshot.child("pending")
                var intakePendingCount = 0
                for (child in pendingNode.children) {
                    val conn = child.getValue(NewConnection::class.java)
                    if (conn != null && (!conn.customerName.isNullOrBlank() || !conn.id.isNullOrBlank())) {
                        intakePendingCount++
                    }
                }
                if (intakePendingCount > 0) {
                    employeeList.add("Unassigned Intake ($intakePendingCount)")
                }

                // 2. Queued pending connections held by employees in gift_box
                val giftBoxNode = snapshot.child("gift_box")
                for (employeeNode in giftBoxNode.children) {
                    val employee = employeeNode.key ?: continue
                    var validCount = 0
                    for (child in employeeNode.children) {
                        val conn = child.getValue(NewConnection::class.java)
                        if (conn != null && (!conn.customerName.isNullOrBlank() || !conn.id.isNullOrBlank())) {
                            validCount++
                        }
                    }

                    val pending = if (validCount > 1) validCount - 1 else 0
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

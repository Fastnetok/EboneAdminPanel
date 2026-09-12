package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

/*
 * FIXED to match Complaints' ProgressActivity.kt exactly: shows ONE row
 * per employee — specifically that employee's front-of-queue item
 * (lowest displayOrder among their gift_box items) — not one row per
 * raw item. Previously this screen flattened every employee's entire
 * gift_box into individual rows, which disagreed with the Dashboard's
 * "Progress" count (unique employees) whenever an employee held more
 * than one item at a time.
 */
class NewConnectionProgressActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: NewConnectionProgressAdapter
    private val progressList = mutableListOf<NewConnection>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_connections)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvTitle).text = "PROGRESS CONNECTIONS"

        recycler = findViewById(R.id.recyclerNewConnections)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = NewConnectionProgressAdapter(progressList)
        recycler.adapter = adapter

        loadProgressConnections()
    }

    /*
     * SAME RULE AS ProgressActivity.kt (Complaints): for each employee,
     * pick only the item with the LOWEST displayOrder — the front of
     * their queue. Everything else they're holding is Pending, not
     * Progress.
     */
    private fun loadProgressConnections() {
        val db = FirebaseDatabase.getInstance()
            .getReference("officeSettings/new_connections/gift_box")

        db.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                progressList.clear()

                for (employeeNode in snapshot.children) {
                    var frontOfQueue: NewConnection? = null

                    for (child in employeeNode.children) {
                        val connection = child.getValue(NewConnection::class.java) ?: continue

                        if (frontOfQueue == null || connection.displayOrder < frontOfQueue!!.displayOrder) {
                            frontOfQueue = connection
                        }
                    }

                    frontOfQueue?.let { progressList.add(it) }
                }

                progressList.sortByDescending { it.assignedTime }
                adapter.notifyDataSetChanged()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }
}
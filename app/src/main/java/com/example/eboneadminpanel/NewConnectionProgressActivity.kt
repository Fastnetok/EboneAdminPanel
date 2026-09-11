package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

/*
 * Mirrors ProgressActivity (Complaints) exactly: a direct, read-only
 * list — no employee-picker step, no buttons. Shows every connection
 * currently sitting in any employee's gift_box (i.e. still in
 * progress / not yet installed).
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
     * Flattens every employee's gift_box into one list — every
     * connection currently assigned and not yet installed.
     */
    private fun loadProgressConnections() {
        val db = FirebaseDatabase.getInstance()
            .getReference("officeSettings/new_connections/gift_box")

        db.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                progressList.clear()

                for (employeeNode in snapshot.children) {
                    for (child in employeeNode.children) {
                        child.getValue(NewConnection::class.java)?.let { conn ->
                            progressList.add(conn)
                        }
                    }
                }

                progressList.sortByDescending { it.assignedTime }
                adapter.notifyDataSetChanged()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }
}
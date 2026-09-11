package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class NewConnectionInstalledActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: NewConnectionIntakeAdapter
    private val installedList = mutableListOf<NewConnection>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_connections)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvTitle).text = "INSTALLED CONNECTIONS"

        recycler = findViewById(R.id.recyclerNewConnections)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = NewConnectionIntakeAdapter(installedList,
            onAssign = { /* Not needed */ },
            onMove = { /* Not needed */ }
        )
        recycler.adapter = adapter

        loadInstalledData()
    }

    private fun loadInstalledData() {
        val db = FirebaseDatabase.getInstance().getReference("officeSettings/new_connections/completed")
        
        db.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                installedList.clear()
                
                // completed/{Year}/{Month}/{Day}/{id}
                for (yearNode in snapshot.children) {
                    for (monthNode in yearNode.children) {
                        for (dayNode in monthNode.children) {
                            for (item in dayNode.children) {
                                val conn = item.getValue(NewConnection::class.java)
                                if (conn != null) {
                                    installedList.add(conn)
                                }
                            }
                        }
                    }
                }
                
                // Sort by completion time (newest first)
                installedList.sortByDescending { it.completionTime }
                adapter.notifyDataSetChanged()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }
}

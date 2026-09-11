package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class NewConnectionsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: NewConnectionAdapter
    private val connectionList = mutableListOf<Complaint>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_connections)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        recyclerView = findViewById(R.id.recyclerNewConnections)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        adapter = NewConnectionAdapter(connectionList) { connection ->
            showEmployeeSelectionDialog(connection)
        }
        recyclerView.adapter = adapter

        loadNewConnections()
    }

    private fun loadNewConnections() {
        FirebaseDatabase.getInstance().getReference("new_connections_pending")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    connectionList.clear()
                    for (item in snapshot.children) {
                        val complaint = item.getValue(Complaint::class.java) ?: continue
                        connectionList.add(complaint)
                    }
                    adapter.notifyDataSetChanged()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun showEmployeeSelectionDialog(connection: Complaint) {
        val fb = FirebaseDatabase.getInstance()
        val todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        
        // Step 1: Load all employees
        fb.getReference("employees").get().addOnSuccessListener { empSnapshot ->
            val allEmployees = mutableMapOf<String, String>() // DeviceId to Name
            for (child in empSnapshot.children) {
                val name = child.child("employeeName").getValue(String::class.java) ?: ""
                val deviceId = child.key ?: ""
                if (name.isNotEmpty() && deviceId.isNotEmpty()) {
                    allEmployees[deviceId] = name
                }
            }

            // Step 2: Load attendance to filter only present employees
            fb.getReference("attendance").get().addOnSuccessListener { attSnapshot ->
                val presentEmployeeNames = mutableListOf<String>()
                for (empAtt in attSnapshot.children) {
                    if (empAtt.hasChild(todayKey)) {
                        val deviceId = empAtt.key ?: ""
                        val name = allEmployees[deviceId]
                        if (name != null) {
                            presentEmployeeNames.add(name)
                        }
                    }
                }

                if (presentEmployeeNames.isEmpty()) {
                    Toast.makeText(this, "No employees present today", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                AlertDialog.Builder(this)
                    .setTitle("Select Employee to Install")
                    .setItems(presentEmployeeNames.toTypedArray()) { _, which ->
                        val selectedEmployee = presentEmployeeNames[which]
                        assignToGiftBox(connection, selectedEmployee)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun assignToGiftBox(connection: Complaint, employeeName: String) {
        val fb = FirebaseDatabase.getInstance()
        
        // Update model
        connection.assignedTo = employeeName
        connection.assignedTime = System.currentTimeMillis()
        connection.isNewConnection = true
        connection.status = "Progress"

        // 1. Move to employee_gift_box
        fb.getReference("employee_gift_box").child(employeeName).child(connection.complaintId)
            .setValue(connection)
            .addOnSuccessListener {
                // 2. Remove from pending connections
                fb.getReference("new_connections_pending").child(connection.complaintId).removeValue()
                
                Toast.makeText(this, "Assigned to $employeeName's Gift Box", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Assignment failed", Toast.LENGTH_SHORT).show()
            }
    }
}

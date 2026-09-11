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
import java.text.SimpleDateFormat
import java.util.*

class NewConnectionIntakeActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: NewConnectionIntakeAdapter
    private val combinedList = mutableListOf<NewConnection>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_connection_intake)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvTitle).text = "PENDING CONNECTIONS"
        
        recycler = findViewById(R.id.recyclerIntake)
        recycler.layoutManager = LinearLayoutManager(this)
        
        adapter = NewConnectionIntakeAdapter(combinedList, 
            onAssign = { item -> showAssignmentDialog(item, "ASSIGN") },
            onMove = { item -> showAssignmentDialog(item, "MOVE") }
        )
        recycler.adapter = adapter

        loadAllConnections()
    }

    private fun loadAllConnections() {
        val db = FirebaseDatabase.getInstance().reference.child("officeSettings/new_connections/pending")
        
        db.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                combinedList.clear()
                for (child in snapshot.children) {
                    child.getValue(NewConnection::class.java)?.let { conn -> 
                        combinedList.add(conn) 
                    }
                }
                combinedList.sortByDescending { it.createdTime }
                adapter.notifyDataSetChanged()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun showAssignmentDialog(connection: NewConnection, mode: String) {
        val fb = FirebaseDatabase.getInstance()
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        // Rule: Only employees who have Biometric Attendance today
        fb.getReference("employees").get().addOnSuccessListener { empSnapshot ->
            val allEmps = mutableMapOf<String, String>() // DeviceId to Name
            for (c in empSnapshot.children) {
                val name = c.child("employeeName").getValue(String::class.java) ?: ""
                val id = c.key ?: ""
                if (name.isNotEmpty() && id.isNotEmpty()) allEmps[id] = name
            }

            fb.getReference("attendance").get().addOnSuccessListener { attSnapshot ->
                val presentDeviceIds = mutableSetOf<String>()
                for (att in attSnapshot.children) {
                    if (att.hasChild(todayKey)) {
                        presentDeviceIds.add(att.key ?: "")
                    }
                }

                // Load New Connection counts for present employees
                fb.getReference("officeSettings/new_connections/gift_box").get().addOnSuccessListener { giftSnapshot ->
                    val counts = mutableMapOf<String, Int>()
                    for (empNode in giftSnapshot.children) {
                        counts[empNode.key ?: ""] = empNode.childrenCount.toInt()
                    }

                    val filteredList = mutableListOf<String>()
                    val finalNames = mutableListOf<String>()

                    for ((id, name) in allEmps) {
                        if (presentDeviceIds.contains(id)) {
                            val count = counts[name] ?: 0
                            filteredList.add("$name ($count)")
                            finalNames.add(name)
                        }
                    }

                    if (finalNames.isEmpty()) {
                        Toast.makeText(this, "Aaj koi employee حاضری پر نہیں ہے", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    val title = if (mode == "MOVE") "منتقلی (Move to Employee)" else "تفویض کریں (Assign to Employee)"

                    AlertDialog.Builder(this)
                        .setTitle(title)
                        .setItems(filteredList.toTypedArray()) { _, which ->
                            assignToGiftBox(connection, finalNames[which])
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun assignToGiftBox(connection: NewConnection, employeeName: String) {
        val fb = FirebaseDatabase.getInstance()
        val currentTime = System.currentTimeMillis()

        // Prepare update
        connection.assignedTo = employeeName
        connection.assignedTime = currentTime
        connection.status = "Progress"

        // 1. Save to Employee's Gift Box (Inside officeSettings to ensure Permission)
        fb.getReference("officeSettings/new_connections/gift_box").child(employeeName).child(connection.id)
            .setValue(connection)
            .addOnSuccessListener {
                // 2. Remove from Admin Intake
                fb.getReference("officeSettings/new_connections/pending").child(connection.id).removeValue()
                
                // 3. Send Notification (Optional but recommended)
                fb.getReference("employeeNotifications").child(employeeName).push().setValue(hashMapOf(
                    "title" to "⭐ New Connection Assigned",
                    "message" to "Name: ${connection.customerName}\nAddress: ${connection.address}",
                    "timestamp" to currentTime,
                    "type" to "GIFT_BOX"
                ))
                
                Toast.makeText(this, "$employeeName کے گفٹ باکس میں بھیج دیا گیا", Toast.LENGTH_SHORT).show()
            }
    }
}

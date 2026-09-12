package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class NewConnectionEmployeeDetailsActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: NewConnectionIntakeAdapter
    private val list = mutableListOf<NewConnection>()
    private var employeeName: String = ""

    private var pendingOnly: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_connections)

        employeeName = intent.getStringExtra("employeeName") ?: ""
        // NEW: only true when opened from the Pending summary
        // (NewConnectionPendingSummaryAdapter). When opened from Total
        // (NewConnectionSummaryAdapter), this is absent/false, so every
        // item — including the front-of-queue Progress item — is shown.
        pendingOnly = intent.getBooleanExtra("pendingOnly", false)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvTitle).text = employeeName.uppercase()

        recycler = findViewById(R.id.recyclerNewConnections)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = NewConnectionIntakeAdapter(list,
            onAssign = { /* Not needed here */ },
            onMove = { item -> showMoveDialog(item) }
        )
        recycler.adapter = adapter

        loadEmployeeConnections()
    }

    /*
     * CORRECTED RULE: uses displayOrder (ascending) — the SAME sequence
     * the employee's own app follows (assignment order / drag-and-drop
     * order), and the SAME field NewConnectionProgressActivity.kt uses
     * to pick the front-of-queue item. The lowest displayOrder item is
     * that employee's "Active"/Progress connection (already shown on
     * NewConnectionProgressActivity) — everything else, in the same
     * ascending order, is shown here as Pending.
     *
     * (Previously this used assignedTime descending, copied directly
     * from Complaints' PendingEmployeeComplaintsActivity.kt — but that
     * doesn't match what the employee's own screen considers "Active",
     * since the employee app orders and picks its front item by
     * displayOrder, not assignedTime.)
     */
    private fun loadEmployeeConnections() {
        FirebaseDatabase.getInstance().getReference("officeSettings/new_connections/gift_box")
            .child(employeeName)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    list.clear()

                    val employeeConnections = mutableListOf<NewConnection>()
                    for (child in snapshot.children) {
                        child.getValue(NewConnection::class.java)?.let {
                            it.id = child.key ?: ""
                            employeeConnections.add(it)
                        }
                    }

                    employeeConnections.sortBy { it.displayOrder }

                    if (pendingOnly && employeeConnections.size > 1) {
                        // Opened from Pending — hide the front-of-queue
                        // (Progress) item, show only the rest.
                        for (i in 1 until employeeConnections.size) {
                            list.add(employeeConnections[i])
                        }
                    } else {
                        // Opened from Total — show every item, including
                        // the front-of-queue Progress item.
                        list.addAll(employeeConnections)
                    }

                    adapter.notifyDataSetChanged()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun showMoveDialog(connection: NewConnection) {
        val fb = FirebaseDatabase.getInstance()
        val todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

        // Step 1: Load all employees
        fb.getReference("employees").get().addOnSuccessListener { empSnapshot ->
            val allEmps = mutableMapOf<String, String>()
            for (c in empSnapshot.children) {
                val name = c.child("employeeName").getValue(String::class.java) ?: ""
                val id = c.key ?: ""
                if (name.isNotEmpty() && id.isNotEmpty()) allEmps[id] = name
            }

            // Step 2: Load attendance
            fb.getReference("attendance").get().addOnSuccessListener { attSnapshot ->
                val presentDeviceIds = mutableSetOf<String>()
                for (att in attSnapshot.children) {
                    if (att.hasChild(todayKey)) {
                        presentDeviceIds.add(att.key ?: "")
                    }
                }

                // Step 3: Load New Connection counts for present employees
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
                        android.widget.Toast.makeText(this, "Aaj koi employee حاضری پر نہیں ہے", android.widget.Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("منتقلی (Move to Employee)")
                        .setItems(filteredList.toTypedArray()) { _, which ->
                            val targetEmployee = finalNames[which]
                            moveConnection(connection, targetEmployee)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun moveConnection(connection: NewConnection, targetEmployee: String) {
        val fb = FirebaseDatabase.getInstance()
        val root = fb.getReference("officeSettings/new_connections")
        val oldEmployee = connection.assignedTo

        // Update connection object
        connection.assignedTo = targetEmployee
        connection.assignedTime = System.currentTimeMillis()
        connection.seenByEmployee = false
        connection.seenTime = 0

        // 1. Add to new employee's gift box
        root.child("gift_box").child(targetEmployee).child(connection.id).setValue(connection)
            .addOnSuccessListener {
                // 2. Remove from old employee's gift box
                if (oldEmployee.isNotEmpty()) {
                    root.child("gift_box").child(oldEmployee).child(connection.id).removeValue()
                } else {
                    // If moving from Pending
                    root.child("pending").child(connection.id).removeValue()
                }
                android.widget.Toast.makeText(this, "Moved to $targetEmployee", android.widget.Toast.LENGTH_SHORT).show()
            }
    }
}
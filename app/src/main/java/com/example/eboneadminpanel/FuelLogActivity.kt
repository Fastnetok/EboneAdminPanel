package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class FuelLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyLogText: TextView
    private val adapter = FuelLogAdapter(mutableListOf())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fuel_log)

        recyclerView = findViewById(R.id.fuelLogRecyclerView)
        emptyLogText = findViewById(R.id.emptyLogText)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadAllTransactions()
    }

    private fun loadAllTransactions() {
        // First get employee names (id -> name), then pull each employee's
        // transaction history and merge everything into one sorted list.
        FirebaseDatabase.getInstance().getReference("employees")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(empSnapshot: DataSnapshot) {
                    val employeeNames = mutableMapOf<String, String>()
                    for (emp in empSnapshot.children) {
                        val id = emp.key ?: continue
                        val name = emp.child("employeeName").getValue(String::class.java) ?: "Employee"
                        employeeNames[id] = name
                    }

                    if (employeeNames.isEmpty()) {
                        emptyLogText.visibility = android.view.View.VISIBLE
                        return
                    }

                    val allEntries = mutableListOf<FuelLogEntry>()
                    var pending = employeeNames.size

                    for ((employeeId, employeeName) in employeeNames) {
                        FirebaseDatabase.getInstance()
                            .getReference("fuelWallet")
                            .child(employeeId)
                            .child("transactions")
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(txSnapshot: DataSnapshot) {
                                    for (tx in txSnapshot.children) {
                                        val amount = tx.child("amount").getValue(Double::class.java) ?: 0.0
                                        val type = tx.child("type").getValue(String::class.java) ?: ""
                                        val newBalance = tx.child("newBalance").getValue(Double::class.java) ?: 0.0
                                        val timestamp = tx.child("timestamp").getValue(Long::class.java) ?: 0L

                                        allEntries.add(
                                            FuelLogEntry(
                                                employeeName = employeeName,
                                                amount = amount,
                                                type = type,
                                                newBalance = newBalance,
                                                timestamp = timestamp
                                            )
                                        )
                                    }
                                    pending--
                                    if (pending == 0) showEntries(allEntries)
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    pending--
                                    if (pending == 0) showEntries(allEntries)
                                }
                            })
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    emptyLogText.visibility = android.view.View.VISIBLE
                }
            })
    }

    private fun showEntries(entries: List<FuelLogEntry>) {
        val sorted = entries.sortedByDescending { it.timestamp }
        adapter.replaceAll(sorted)
        emptyLogText.visibility =
            if (sorted.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }
}
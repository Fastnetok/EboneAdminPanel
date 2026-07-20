package com.example.eboneadminpanel

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class FuelLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyLogText: TextView
    private lateinit var resetLogButton: Button
    private lateinit var deleteByDateButton: Button
    private val adapter = FuelLogAdapter(mutableListOf())

    // Holds the "From" date while we wait for the user to pick the "To" date
    private var pendingFromCalendar: Calendar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fuel_log)

        recyclerView = findViewById(R.id.fuelLogRecyclerView)
        emptyLogText = findViewById(R.id.emptyLogText)
        resetLogButton = findViewById(R.id.resetLogButton)
        deleteByDateButton = findViewById(R.id.deleteByDateButton)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // TODO: Role restriction — once the Owner/Manager/Supervisor role
        // system is set up, only show/enable these buttons when the logged-in
        // user is Owner or Manager. Something like:
        //   resetLogButton.visibility = if (isOwnerOrManager()) View.VISIBLE else View.GONE
        //   deleteByDateButton.visibility = if (isOwnerOrManager()) View.VISIBLE else View.GONE
        // For now both buttons are visible to everyone.

        resetLogButton.setOnClickListener {
            confirmResetLog()
        }

        deleteByDateButton.setOnClickListener {
            pickFromDate()
        }

        loadAllTransactions()
    }

    // ---------- Delete by custom date range ----------

    private fun pickFromDate() {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val fromCal = Calendar.getInstance()
                fromCal.set(year, month, dayOfMonth, 0, 0, 0)
                fromCal.set(Calendar.MILLISECOND, 0)
                pendingFromCalendar = fromCal
                pickToDate(fromCal)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply {
            setTitle("Select From Date")
            show()
        }
    }

    private fun pickToDate(fromCal: Calendar) {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val toCal = Calendar.getInstance()
                toCal.set(year, month, dayOfMonth, 23, 59, 59)
                toCal.set(Calendar.MILLISECOND, 999)

                if (toCal.timeInMillis < fromCal.timeInMillis) {
                    Toast.makeText(this, "'To' date cannot be before 'From' date", Toast.LENGTH_SHORT).show()
                    return@DatePickerDialog
                }

                confirmDeleteByDate(fromCal, toCal)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply {
            setTitle("Select To Date")
            show()
        }
    }

    private fun confirmDeleteByDate(fromCal: Calendar, toCal: Calendar) {
        val displayFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        val fromLabel = displayFormat.format(fromCal.time)
        val toLabel = displayFormat.format(toCal.time)

        AlertDialog.Builder(this)
            .setTitle("Delete Log Entries")
            .setMessage("Delete all fuel log entries from $fromLabel to $toLabel?\n\nThis action cannot be undone.")
            .setPositiveButton("Yes") { _, _ ->
                deleteByDateRange(fromCal.timeInMillis, toCal.timeInMillis)
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun deleteByDateRange(startMillis: Long, endMillis: Long) {
        FirebaseDatabase.getInstance().getReference("employees")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(empSnapshot: DataSnapshot) {
                    val employeeIds = empSnapshot.children.mapNotNull { it.key }

                    if (employeeIds.isEmpty()) {
                        Toast.makeText(this@FuelLogActivity, "Log is already empty", Toast.LENGTH_SHORT).show()
                        return
                    }

                    var pending = employeeIds.size
                    var deletedCount = 0
                    var hadFailure = false

                    for (employeeId in employeeIds) {
                        FirebaseDatabase.getInstance()
                            .getReference("fuelWallet")
                            .child(employeeId)
                            .child("transactions")
                            .orderByChild("timestamp")
                            .startAt(startMillis.toDouble())
                            .endAt(endMillis.toDouble())
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(txSnapshot: DataSnapshot) {
                                    val matchingKeys = txSnapshot.children.mapNotNull { it.key }

                                    if (matchingKeys.isEmpty()) {
                                        pending--
                                        if (pending == 0) {
                                            showDeleteByDateResult(deletedCount, hadFailure)
                                        }
                                        return
                                    }

                                    var childPending = matchingKeys.size
                                    for (key in matchingKeys) {
                                        FirebaseDatabase.getInstance()
                                            .getReference("fuelWallet")
                                            .child(employeeId)
                                            .child("transactions")
                                            .child(key)
                                            .removeValue()
                                            .addOnCompleteListener { task ->
                                                if (task.isSuccessful) deletedCount++ else hadFailure = true
                                                childPending--
                                                if (childPending == 0) {
                                                    pending--
                                                    if (pending == 0) {
                                                        showDeleteByDateResult(deletedCount, hadFailure)
                                                    }
                                                }
                                            }
                                    }
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    hadFailure = true
                                    pending--
                                    if (pending == 0) {
                                        showDeleteByDateResult(deletedCount, hadFailure)
                                    }
                                }
                            })
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        this@FuelLogActivity,
                        "⚠️ Delete failed: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    private fun showDeleteByDateResult(deletedCount: Int, hadFailure: Boolean) {
        if (hadFailure) {
            Toast.makeText(
                this,
                "⚠️ Some entries could not be deleted, please try again",
                Toast.LENGTH_LONG
            ).show()
        } else if (deletedCount == 0) {
            Toast.makeText(this, "No entries found in that date range", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "✅ Deleted $deletedCount entr${if (deletedCount == 1) "y" else "ies"}", Toast.LENGTH_SHORT).show()
        }
        loadAllTransactions()
    }

    private fun confirmResetLog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Fuel Log")
            .setMessage("Are you sure you want to delete the entire Fuel Log?\n\nThis action cannot be undone — the transaction history for all employees will be permanently deleted.")
            .setPositiveButton("Yes") { _, _ ->
                resetLog()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun resetLog() {
        FirebaseDatabase.getInstance().getReference("employees")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(empSnapshot: DataSnapshot) {
                    val employeeIds = empSnapshot.children.mapNotNull { it.key }

                    if (employeeIds.isEmpty()) {
                        Toast.makeText(this@FuelLogActivity, "Log is already empty", Toast.LENGTH_SHORT).show()
                        return
                    }

                    var pending = employeeIds.size
                    var hadFailure = false

                    for (employeeId in employeeIds) {
                        FirebaseDatabase.getInstance()
                            .getReference("fuelWallet")
                            .child(employeeId)
                            .child("transactions")
                            .removeValue()
                            .addOnCompleteListener { task ->
                                if (!task.isSuccessful) hadFailure = true
                                pending--
                                if (pending == 0) {
                                    if (hadFailure) {
                                        Toast.makeText(
                                            this@FuelLogActivity,
                                            "⚠️ Some entries could not be deleted, please try again",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            this@FuelLogActivity,
                                            "✅ Fuel Log has been reset",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    // Refresh the list on screen (will show empty state now)
                                    loadAllTransactions()
                                }
                            }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        this@FuelLogActivity,
                        "⚠️ Reset failed: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
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
                        adapter.replaceAll(emptyList())
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
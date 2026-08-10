package com.example.eboneadminpanel

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.eboneadminpanel.databinding.ActivityAddEmployeeBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Everything about managing employees lives on THIS ONE screen:
 *   1. Add a new employee (Name + auto-generated one-time PIN).
 *   2. "Manage Employees" — an expandable list of everyone already
 *      Approved, each with Delete / Block.
 *
 * Delete = removes ONLY from "employees" + "ApprovedDevices". Nothing else
 * (tracking, geofenceLogs, complaints, resolvedComplaints) is ever touched
 * — if the same person comes back later, all that history is intact and
 * will link up again automatically once they're re-approved.
 *
 * Block = flips ApprovedDevices/{androidId}/status to "Blocked" (tap again
 * to Unblock) — nothing is deleted, this is just a temporary pause.
 */
class AddEmployeeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddEmployeeBinding
    private val db = FirebaseDatabase.getInstance()
    private var currentPin: String = ""
    private var isManageListVisible = false
    private var manageListLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEmployeeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        generateNewPin()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRegeneratePin.setOnClickListener { generateNewPin() }
        binding.btnSaveEmployee.setOnClickListener { saveEmployee() }

        binding.btnToggleManage.setOnClickListener { toggleManageList() }
    }

    // ===================== ADD EMPLOYEE (existing) =====================

    private fun generateNewPin() {
        currentPin = (100000..999999).random().toString()
        binding.tvGeneratedPin.text = currentPin
    }

    private fun saveEmployee() {
        val employeeName = binding.etEmployeeName.text.toString().trim()
        if (employeeName.isEmpty()) {
            showResult("Please enter the employee's name.", isError = true)
            return
        }

        binding.btnSaveEmployee.isEnabled = false

        db.getReference("employeePins").child(currentPin).get()
            .addOnSuccessListener { existing ->
                if (existing.exists()) {
                    showResult("This PIN is already in use — tap Regenerate and try again.", isError = true)
                    binding.btnSaveEmployee.isEnabled = true
                    return@addOnSuccessListener
                }

                val data = mapOf(
                    "employeeName" to employeeName,
                    "status" to "PENDING",
                    "linkedAndroidId" to null,
                    "linkedUid" to null,
                    "createdAt" to System.currentTimeMillis()
                )

                db.getReference("employeePins").child(currentPin).setValue(data)
                    .addOnSuccessListener {
                        showResult(
                            "✅ Employee \"$employeeName\" created!\n\n" +
                                    "Send this to them via WhatsApp:\nName: $employeeName\nPIN: $currentPin",
                            isError = false
                        )
                        Toast.makeText(this, "Employee saved successfully", Toast.LENGTH_SHORT).show()
                        binding.etEmployeeName.text.clear()
                        generateNewPin()
                        binding.btnSaveEmployee.isEnabled = true
                        if (isManageListVisible) loadManageList() // refresh if already open
                    }
                    .addOnFailureListener { e ->
                        showResult("Failed to save: ${e.message}", isError = true)
                        binding.btnSaveEmployee.isEnabled = true
                    }
            }
            .addOnFailureListener { e ->
                showResult("Could not check PIN: ${e.message}", isError = true)
                binding.btnSaveEmployee.isEnabled = true
            }
    }

    private fun showResult(message: String, isError: Boolean) {
        binding.tvResult.visibility = View.VISIBLE
        binding.tvResult.text = message
        binding.tvResult.setBackgroundResource(if (isError) R.drawable.bg_stat_danger else R.drawable.bg_stat_success)
        binding.tvResult.setTextColor(
            android.graphics.Color.parseColor(if (isError) "#C62828" else "#1B5E20")
        )
    }

    // ===================== MANAGE EMPLOYEES (new) =====================

    private fun toggleManageList() {
        isManageListVisible = !isManageListVisible
        binding.layoutManageList.visibility = if (isManageListVisible) View.VISIBLE else View.GONE
        binding.tvToggleArrow.text = if (isManageListVisible) "▲" else "▼"

        if (isManageListVisible && !manageListLoaded) {
            loadManageList()
        }
    }

    private fun loadManageList() {
        manageListLoaded = true
        db.getReference("ApprovedDevices")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    binding.layoutManageList.removeAllViews()

                    if (!snapshot.exists()) {
                        val empty = TextView(this@AddEmployeeActivity).apply {
                            text = "No approved employees yet."
                            setPadding(12, 12, 12, 12)
                            setTextColor(android.graphics.Color.parseColor("#757575"))
                        }
                        binding.layoutManageList.addView(empty)
                        return
                    }

                    for (child in snapshot.children) {
                        val androidId = child.key ?: continue
                        val employeeName = child.child("employeeName").getValue(String::class.java) ?: "—"
                        val status = child.child("status").getValue(String::class.java) ?: "Approved"

                        val row = LayoutInflater.from(this@AddEmployeeActivity)
                            .inflate(R.layout.item_manage_employee, binding.layoutManageList, false)

                        row.findViewById<TextView>(R.id.tvRowName).text = employeeName
                        val statusView = row.findViewById<TextView>(R.id.tvRowStatus)
                        statusView.text = status
                        statusView.setTextColor(
                            android.graphics.Color.parseColor(
                                if (status.equals("Blocked", true)) "#C62828" else "#2E7D32"
                            )
                        )

                        val deleteBtn = row.findViewById<Button>(R.id.btnRowDelete)
                        val blockBtn = row.findViewById<Button>(R.id.btnRowBlock)
                        blockBtn.text = if (status.equals("Blocked", true)) "Unblock" else "Block"

                        deleteBtn.setOnClickListener {
                            AlertDialog.Builder(this@AddEmployeeActivity)
                                .setTitle("Delete Employee")
                                .setMessage("Delete $employeeName? They can register again later with a new PIN from Admin — nothing else (tracking, complaints, etc.) is affected.")
                                .setPositiveButton("Delete") { _, _ ->
                                    db.getReference("employees").child(androidId).removeValue()
                                    db.getReference("ApprovedDevices").child(androidId).removeValue()
                                    Toast.makeText(this@AddEmployeeActivity, "$employeeName deleted", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }

                        blockBtn.setOnClickListener {
                            val nowBlocked = status.equals("Blocked", true)
                            val newStatus = if (nowBlocked) "Approved" else "Blocked"
                            val actionLabel = if (nowBlocked) "Unblock" else "Block"

                            AlertDialog.Builder(this@AddEmployeeActivity)
                                .setTitle("$actionLabel Employee")
                                .setMessage("$actionLabel $employeeName?")
                                .setPositiveButton(actionLabel) { _, _ ->
                                    db.getReference("ApprovedDevices").child(androidId)
                                        .child("status").setValue(newStatus)
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }

                        binding.layoutManageList.addView(row)
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }
}
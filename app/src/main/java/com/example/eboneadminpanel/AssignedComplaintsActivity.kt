package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.*

class AssignedComplaintsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ComplaintAdapter

    private lateinit var addEmployeeButton: Button
    private lateinit var selectEmployeeButton: Button
    private lateinit var assignComplaintButton: Button
    private lateinit var selectedEmployeeText: TextView

    private val complaintList =
        mutableListOf<Complaint>()
    private var selectedEmployeeName = ""

    private var selectedEmployeeId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_assigned_complaints
        )

        recyclerView =
            findViewById(
                R.id.recyclerViewAssigned
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            ComplaintAdapter(
                complaintList
            )

        recyclerView.adapter =
            adapter

        addEmployeeButton =
            findViewById(
                R.id.addEmployeeButton
            )

        selectEmployeeButton =
            findViewById(
                R.id.selectEmployeeButton
            )

        assignComplaintButton =
            findViewById(
                R.id.assignComplaintButton
            )

        selectedEmployeeText =
            findViewById(
                R.id.selectedEmployeeText
            )

        addEmployeeButton.setOnClickListener {

            val dialogView =
                layoutInflater.inflate(
                    R.layout.dialog_add_employee,
                    null
                )

            val employeeNameInput =
                dialogView.findViewById<EditText>(
                    R.id.employeeNameInput
                )

            val employeePhoneInput =
                dialogView.findViewById<EditText>(
                    R.id.employeePhoneInput
                )

            AlertDialog.Builder(this)
                .setTitle("Add Employee")
                .setView(dialogView)

                .setPositiveButton("SAVE") { _, _ ->

                    val employeeName =
                        employeeNameInput.text
                            .toString()
                            .trim()

                    val employeePhone =
                        employeePhoneInput.text
                            .toString()
                            .trim()

                    if (
                        employeeName.isNotEmpty() &&
                        employeePhone.isNotEmpty()
                    ) {

                        val employeeId =
                            FirebaseDatabase
                                .getInstance()
                                .getReference("complaintEmployees")
                                .push()
                                .key
                                ?: return@setPositiveButton

                        val employee =
                            ComplaintEmployee(
                                employeeId = employeeId,
                                employeeName = employeeName,
                                phoneNumber = employeePhone
                            )

                        FirebaseDatabase
                            .getInstance()
                            .getReference("complaintEmployees")
                            .child(employeeId)
                            .setValue(employee)

                        Toast.makeText(
                            this,
                            "Employee Added Successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                .setNegativeButton(
                    "Cancel",
                    null
                )

                .show()
        }

        selectEmployeeButton.setOnClickListener {

            FirebaseDatabase
                .getInstance()
                .getReference("complaintEmployees")
                .get()

                .addOnSuccessListener { snapshot ->

                    val employeeNames =
                        mutableListOf<String>()

                    val employeeObjects =
                        mutableListOf<ComplaintEmployee>()

                    for (child in snapshot.children) {

                        val employee =
                            child.getValue(
                                ComplaintEmployee::class.java
                            )

                        if (employee != null) {

                            employeeObjects.add(employee)

                            employeeNames.add(
                                employee.employeeName +
                                        "\n" +
                                        employee.phoneNumber
                            )
                        }
                    }

                    if (employeeNames.isEmpty()) {

                        Toast.makeText(
                            this,
                            "No Employee Found",
                            Toast.LENGTH_SHORT
                        ).show()

                        return@addOnSuccessListener
                    }

                    AlertDialog.Builder(this)
                        .setTitle("Select Employee")

                        .setItems(
                            employeeNames.toTypedArray()
                        ) { _, which ->

                            val selectedEmployee =
                                employeeObjects[which]

                            selectedEmployeeName =
                                selectedEmployee.employeeName

                            selectedEmployeeId =
                                selectedEmployee.employeeId

                            selectedEmployeeText.text =
                                "Selected Employee: " +
                                        selectedEmployee.employeeName
                        }

                        .show()
                }
        }

        assignComplaintButton.setOnClickListener {

            if (selectedEmployeeName.isEmpty()) {

                Toast.makeText(
                    this,
                    "Please Select Employee",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            if (adapter.selectedComplaints.isEmpty()) {

                Toast.makeText(
                    this,
                    "Please Select Complaint",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            for (complaint in adapter.selectedComplaints) {

                val currentTime =
                    System.currentTimeMillis()

                val updates = hashMapOf<String, Any>(

                    "assignedTo" to selectedEmployeeName,

                    "assignedTime" to currentTime,

                    "status" to "Pending"

                )

                FirebaseDatabase
                    .getInstance()
                    .getReference("complaints")
                    .child(complaint.complaintId)
                    .updateChildren(updates)

                FCMSender.sendNotification(

                    selectedEmployeeName,

                    "New Complaint Assigned",

                    "User ID: ${complaint.userId}"

                )

                val notificationData =
                    hashMapOf<String, Any>(

                        "title" to
                                "New Complaint Assigned",

                        "message" to
                                "User ID: ${complaint.userId}",

                        "time" to currentTime

                    )

                FirebaseDatabase
                    .getInstance()
                    .getReference(
                        "employeeNotifications"
                    )
                    .child(
                        selectedEmployeeName
                    )
                    .push()
                    .setValue(
                        notificationData
                    )
                    .addOnSuccessListener {

                        Toast.makeText(
                            this,
                            "Notification Saved OK",
                            Toast.LENGTH_LONG
                        ).show()

                    }
                    .addOnFailureListener {

                        Toast.makeText(
                            this,
                            "Error: " + it.message,
                            Toast.LENGTH_LONG
                        ).show()

                    }

            }

            Toast.makeText(
                this,
                "Complaint Assigned Successfully",
                Toast.LENGTH_SHORT
            ).show()
        }

        loadAssignedComplaints()
    }

    private fun loadAssignedComplaints() {

        FirebaseDatabase
            .getInstance()
            .getReference("complaints")
            .addValueEventListener(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        complaintList.clear()

                        for (
                        complaintSnapshot
                        in snapshot.children
                        ) {

                            if (
                                complaintSnapshot.hasChild(
                                    "userId"
                                )
                            ) {

                                val complaint =
                                    complaintSnapshot.getValue(
                                        Complaint::class.java
                                    )

                                complaint?.complaintId =
                                    complaintSnapshot.key ?: ""

                                if (
                                    complaint != null &&
                                    complaint.assignedTo.isEmpty()
                                ) {

                                    complaintList.add(
                                        complaint
                                    )
                                }
                            }
                        }

                        adapter.notifyDataSetChanged()
                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }
                }
            )
    }
}
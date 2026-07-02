package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AddComplaintActivity : AppCompatActivity() {

    private lateinit var repeatManager: RepeatComplaintManager
    private lateinit var userIdInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var phoneInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_complaint)

        repeatManager = RepeatComplaintManager()
        userIdInput = findViewById(R.id.userIdInput)
        addressInput = findViewById(R.id.addressInput)
        phoneInput = findViewById(R.id.phoneInput)

        val commentsInput = findViewById<EditText>(R.id.commentsInput)
        val loginButton = findViewById<Button>(R.id.ocrButton)
        val assignButton = findViewById<Button>(R.id.assignComplaintButton)

        loginButton.setOnClickListener {
            val ispList = arrayOf(
                "Ebone (ebill.pk)",
                "Wateen (wateen.com)"
            )

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Select ISP")
                .setItems(ispList) { _, which ->
                    val selectedISP = if (which == 0) "EBONE" else "WATEEN"
                    val intent = Intent(this, WebViewLoginActivity::class.java)
                    intent.putExtra("selected_isp", selectedISP)
                    webViewLauncher.launch(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        assignButton.setOnClickListener {
            val userId = userIdInput.text.toString().trim()
            val address = addressInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()
            val details = commentsInput.text.toString().trim()

            if (userId.isEmpty()) {
                Toast.makeText(this, "User ID enter karein", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Firebase se employees load karein
            FirebaseDatabase.getInstance()
                .getReference("employees")
                .get()
                .addOnSuccessListener { snapshot ->
                    val employeeNames = mutableListOf<String>()

                    for (child in snapshot.children) {
                        val name = child.child("employeeName")
                            .getValue(String::class.java) ?: ""
                        if (name.isNotEmpty()) {
                            employeeNames.add(name)
                        }
                    }

                    if (employeeNames.isEmpty()) {
                        Toast.makeText(
                            this,
                            "Koi employee nahi mila",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@addOnSuccessListener
                    }

                    // Employee select dialog
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Select Employee")
                        .setItems(employeeNames.toTypedArray()) { _, which ->
                            val selectedEmployee = employeeNames[which]

                            // Repeat check karein
                            repeatManager.checkRepeatComplaint(userId) { repeatInfo ->
                                if (repeatInfo.isRepeat) {
                                    val resolveDate = if (repeatInfo.lastResolvedTime > 0) {
                                        SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                                            .format(Date(repeatInfo.lastResolvedTime))
                                    } else {
                                        "Unknown"
                                    }
                                    Toast.makeText(
                                        this,
                                        "⚠️ Repeat Complaint\n" +
                                                "Resolver: " + repeatInfo.lastResolver +
                                                "\nLast Resolved: " + resolveDate +
                                                "\nCount: " + repeatInfo.repeatCount,
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                                // Complaint banao aur assign karo
                                val complaintId = FirebaseDatabase
                                    .getInstance()
                                    .getReference("complaints")
                                    .push()
                                    .key ?: return@checkRepeatComplaint

                                val complaint = Complaint(
                                    complaintId = complaintId,
                                    userId = userId,
                                    address = address,
                                    phoneNumber = phone,
                                    details = details,
                                    status = "Progress",
                                    assignedTo = selectedEmployee,
                                    assignedTime = System.currentTimeMillis(),
                                    createdTime = System.currentTimeMillis()
                                )

                                FirebaseDatabase.getInstance()
                                    .getReference("complaints")
                                    .child(complaintId)
                                    .setValue(complaint)
                                    .addOnSuccessListener {
                                        // Employee notification bhejein
                                        val notifRef = FirebaseDatabase.getInstance()
                                            .getReference("employeeNotifications")
                                            .child(selectedEmployee)
                                            .push()

                                        val notifData = hashMapOf<String, Any>(
                                            "title" to "New Complaint Assigned",
                                            "message" to "User ID: $userId",
                                            "complaintId" to complaintId,
                                            "timestamp" to System.currentTimeMillis()
                                        )

                                        notifRef.setValue(notifData)

                                        Toast.makeText(
                                            this,
                                            "Complaint assigned to $selectedEmployee",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        finish()
                                    }
                                    .addOnFailureListener {
                                        Toast.makeText(
                                            this,
                                            "Failed to save complaint",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
        }
    }

    private val webViewLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val fetchedUserId = data?.getStringExtra("fetched_user_id") ?: ""
                val fetchedAddress = data?.getStringExtra("fetched_address") ?: ""
                val fetchedPhone = data?.getStringExtra("fetched_phone") ?: ""

                if (fetchedUserId.isNotEmpty()) userIdInput.setText(fetchedUserId)
                if (fetchedAddress.isNotEmpty()) addressInput.setText(fetchedAddress)
                if (fetchedPhone.isNotEmpty()) phoneInput.setText(fetchedPhone)
            }
        }
}
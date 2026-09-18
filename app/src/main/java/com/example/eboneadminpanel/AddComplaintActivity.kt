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

        // Selected company is kept only for the current complaint flow.
        var selectedCompany = ""

        loginButton.setOnClickListener {
            val ispList = arrayOf(
                "Ebone (ebill.pk)",
                "Wateen (wateen.com)",
                "Zong (turbonet.zong.com.pk)"
            )

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Select ISP")
                .setItems(ispList) { _, which ->
                    val selectedISP = when (which) {
                        0 -> "EBONE"
                        1 -> "WATEEN"
                        else -> "ZONG"
                    }

                    selectedCompany = selectedISP

                    val targetActivity = WebViewRouter.getTargetActivity(selectedISP, null)
                    val intent = Intent(this, targetActivity)
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

            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val fb = FirebaseDatabase.getInstance()

            // Step 1: Load all employees
            fb.getReference("employees").get().addOnSuccessListener { empSnapshot ->
                val allEmployees = mutableListOf<Pair<String, String>>() // Name to DeviceId
                for (child in empSnapshot.children) {
                    val name = child.child("employeeName").getValue(String::class.java) ?: ""
                    val deviceId = child.key ?: ""
                    if (name.isNotEmpty() && deviceId.isNotEmpty()) {
                        allEmployees.add(name to deviceId)
                    }
                }

                // Step 2: Load attendance to see who is present today
                fb.getReference("attendance").get().addOnSuccessListener { attSnapshot ->
                    val presentDeviceIds = mutableSetOf<String>()
                    for (empAtt in attSnapshot.children) {
                        if (empAtt.hasChild(todayKey)) {
                            presentDeviceIds.add(empAtt.key ?: "")
                        }
                    }

                    // Step 3: Load complaints to count current workload
                    fb.getReference("complaints").get().addOnSuccessListener { compSnapshot ->
                        val complaintCounts = mutableMapOf<String, Int>() // Name to Count
                        for (comp in compSnapshot.children) {
                            val assignedTo = comp.child("assignedTo").getValue(String::class.java) ?: ""
                            val status = comp.child("status").getValue(String::class.java) ?: ""
                            if (assignedTo.isNotEmpty() && status != "Resolved") {
                                complaintCounts[assignedTo] = (complaintCounts[assignedTo] ?: 0) + 1
                            }
                        }

                        // Step 4: Filter only present employees and prepare display list
                        val filteredList = mutableListOf<String>()
                        val finalEmployeeNames = mutableListOf<String>()

                        // New Protocol: Add NEW CONNECTION option at the top
                        filteredList.add("⭐ NEW CONNECTION (Intake)")
                        finalEmployeeNames.add("NEW_CONNECTION_INTAKE")

                        for ((name, deviceId) in allEmployees) {
                            if (presentDeviceIds.contains(deviceId)) {
                                val count = complaintCounts[name] ?: 0
                                finalEmployeeNames.add(name)
                                filteredList.add("$name ($count)")
                            }
                        }

                        if (finalEmployeeNames.isEmpty()) {
                            Toast.makeText(this, "Aaj koi employee present nahi hai", Toast.LENGTH_LONG).show()
                            return@addOnSuccessListener
                        }

                        // Step 5: Show the filtered list in a dialog
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Select Present Employee (Complaints)")
                            .setItems(filteredList.toTypedArray()) { _, which ->
                                val selectedEmployee = finalEmployeeNames[which]
                                val currentTime = System.currentTimeMillis()

                                // NEW PROTOCOL: Save inside officeSettings to ensure Permission
                                if (selectedEmployee == "NEW_CONNECTION_INTAKE") {
                                    val connId = fb.getReference("officeSettings/new_connections/pending").push().key ?: return@setItems
                                    val newConn = NewConnection(
                                        id = connId,
                                        customerName = userId,
                                        address = address,
                                        phoneNumber = phone,
                                        comments = details,
                                        status = "Pending",
                                        createdTime = currentTime
                                    )
                                    fb.getReference("officeSettings/new_connections/pending").child(connId).setValue(newConn)
                                        .addOnSuccessListener {
                                            Toast.makeText(this, "New Connection Intake mein save ho gaya", Toast.LENGTH_SHORT).show()
                                            finish()
                                        }
                                        .addOnFailureListener { e ->
                                            Toast.makeText(this, "Save Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    return@setItems
                                }

                                // OLD STABLE LOGIC: For normal complaints
                                repeatManager.checkRepeatComplaint(userId) { repeatInfo ->
                                    if (repeatInfo.isRepeat) {
                                        val resolveDate = if (repeatInfo.lastResolvedTime > 0) {
                                            SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date(repeatInfo.lastResolvedTime))
                                        } else { "Unknown" }
                                        Toast.makeText(this, "⚠️ Repeat Complaint\nResolver: ${repeatInfo.lastResolver}\nLast Resolved: $resolveDate\nCount: ${repeatInfo.repeatCount}", Toast.LENGTH_LONG).show()
                                    }

                                    val complaintId = fb.getReference("complaints").push().key ?: return@checkRepeatComplaint

                                    val complaint = Complaint(
                                        complaintId = complaintId,
                                        userId = userId,
                                        address = address,
                                        phoneNumber = phone,
                                        details = details,
                                        company = selectedCompany,
                                        status = "Progress",
                                        assignedTo = selectedEmployee,
                                        assignedTime = currentTime,
                                        createdTime = currentTime
                                    )

                                    fb.getReference("complaints").child(complaintId).setValue(complaint).addOnSuccessListener {
                                        fb.getReference("employeeNotifications").child(selectedEmployee).push().setValue(hashMapOf(
                                            "title" to "New Complaint Assigned",
                                            "message" to "User ID: $userId",
                                            "complaintId" to complaintId,
                                            "timestamp" to System.currentTimeMillis()
                                        ))
                                        Toast.makeText(this, "Complaint assigned to $selectedEmployee", Toast.LENGTH_SHORT).show()
                                        finish()
                                    }.addOnFailureListener {
                                        Toast.makeText(this, "Failed to save complaint", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }
    }

    private val webViewLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data

                val fetchedUserId =
                    data?.getStringExtra("fetched_user_id") ?: ""

                val fetchedAddress =
                    data?.getStringExtra("fetched_address") ?: ""

                val fetchedPhone =
                    data?.getStringExtra("fetched_phone") ?: ""

                if (fetchedUserId.isNotEmpty()) {
                    userIdInput.setText(fetchedUserId)
                }

                if (fetchedAddress.isNotEmpty()) {
                    addressInput.setText(fetchedAddress)
                }

                if (fetchedPhone.isNotEmpty()) {
                    phoneInput.setText(fetchedPhone)
                }
            }
        }
}

package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.database.*

class NewConnectionDashboardActivity : AppCompatActivity() {

    private lateinit var tvTotal: TextView
    private lateinit var tvPending: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvInstalled: TextView

    private lateinit var etNcName: EditText
    private lateinit var etNcAddress: EditText
    private lateinit var etNcPhone: EditText
    private lateinit var etNcComments: EditText
    private lateinit var btnAssignNc: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_connection_dashboard)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnEmployeeConfig).setOnClickListener {
            startActivity(Intent(this, NewConnectionEmployeeConfigActivity::class.java))
        }

        tvTotal = findViewById(R.id.cardTotal)
        tvPending = findViewById(R.id.cardPending)
        tvProgress = findViewById(R.id.cardProgress)
        tvInstalled = findViewById(R.id.cardInstalled)

        etNcName = findViewById(R.id.etNcName)
        etNcAddress = findViewById(R.id.etNcAddress)
        etNcPhone = findViewById(R.id.etNcPhone)
        etNcComments = findViewById(R.id.etNcComments)
        btnAssignNc = findViewById(R.id.btnAssignNc)

        // Click Listeners
        tvTotal.setOnClickListener {
            startActivity(Intent(this, NewConnectionTotalActivity::class.java))
        }

        tvPending.setOnClickListener {
            startActivity(Intent(this, NewConnectionIntakeActivity::class.java))
        }

        tvProgress.setOnClickListener {
            val intent = Intent(this, NewConnectionProgressActivity::class.java)
            startActivity(intent)
        }

        tvInstalled.setOnClickListener {
            startActivity(Intent(this, NewConnectionInstalledActivity::class.java))
        }

        btnAssignNc.setOnClickListener {
            handleNcAssignment()
        }

        etNcComments.setOnClickListener {
            showCommentsDialog()
        }

        loadStats()
    }

    private fun showCommentsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Connection Details")

        val input = EditText(this)
        input.gravity = Gravity.TOP
        input.minLines = 5
        input.setPadding(40, 40, 40, 40)
        input.background = ContextCompat.getDrawable(this, R.drawable.bg_spinner)
        input.setText(etNcComments.text.toString())
        input.hint = "Type or use Microphone..."
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(40, 20, 40, 20)
        container.addView(input, params)
        builder.setView(container)

        builder.setPositiveButton("Done") { _, _ ->
            etNcComments.setText(input.text.toString())
        }
        builder.setNegativeButton("Cancel", null)

        builder.show()
    }

    private fun handleNcAssignment() {
        val name = etNcName.text.toString().trim()
        val address = etNcAddress.text.toString().trim()
        // FIX: Remove all spaces from phone number (Voice typing often adds them)
        val phone = etNcPhone.text.toString().replace(" ", "").trim()
        val comments = etNcComments.text.toString().trim()

        if (name.isEmpty()) {
            Toast.makeText(this, "Enter Name", Toast.LENGTH_SHORT).show()
            return
        }

        val fb = FirebaseDatabase.getInstance()
        
        // 1. Get ALL employees
        fb.getReference("employees").get().addOnSuccessListener { empSnapshot ->
            val allEmps = mutableListOf<String>()
            for (c in empSnapshot.children) {
                c.child("employeeName").getValue(String::class.java)?.let { allEmps.add(it) }
            }

            // 2. Filter ONLY Active for New Connections
            fb.getReference("officeSettings/new_connections/active_employees").get().addOnSuccessListener { activeSnapshot ->
                val activeEmps = allEmps.filter { activeSnapshot.child(it).getValue(Boolean::class.java) ?: true }

                // 3. Load Current Load (Counts)
                fb.getReference("officeSettings/new_connections/gift_box").get().addOnSuccessListener { giftSnapshot ->
                    val counts = mutableMapOf<String, Int>()
                    for (empName in activeEmps) {
                        counts[empName] = giftSnapshot.child(empName).childrenCount.toInt()
                    }

                    // 4. Sort: Highest Load First
                    val sortedList = activeEmps.sortedByDescending { counts[it] ?: 0 }
                    val displayList = sortedList.map { "$it (${counts[it] ?: 0})" }.toTypedArray()

                    AlertDialog.Builder(this)
                        .setTitle("Select Employee to Assign")
                        .setItems(displayList) { _, which ->
                            val selectedEmployee = sortedList[which]
                            val ncId = fb.getReference("officeSettings/new_connections/pending").push().key ?: ""
                            val nc = NewConnection(
                                id = ncId,
                                customerName = name,
                                address = address,
                                phoneNumber = phone,
                                comments = comments,
                                status = "Progress",
                                assignedTo = selectedEmployee,
                                assignedTime = System.currentTimeMillis(),
                                createdTime = System.currentTimeMillis()
                            )
                            
                            // Save directly to selected employee's gift box
                            fb.getReference("officeSettings/new_connections/gift_box").child(selectedEmployee).child(ncId).setValue(nc)
                                .addOnSuccessListener {
                                    Toast.makeText(this, "Assigned to $selectedEmployee", Toast.LENGTH_SHORT).show()
                                    etNcName.setText(""); etNcAddress.setText(""); etNcPhone.setText(""); etNcComments.setText("")
                                }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }
    }

    /*
     * SAME FORMULA AS COMPLAINTS (MainActivity.loadDashboardCounters):
     *
     *   Progress = number of UNIQUE employees currently holding at
     *              least 1 connection (gift_box) — not total items.
     *   Pending  = TotalAllTime - Progress - InstalledAllTime
     *              (a subtraction, exactly like complaints — NOT a
     *              direct count of the "pending" node's children).
     *   Total    = Pending + Progress
     *
     * Because New Connection data is split across 3 separate nodes
     * (pending / gift_box / completed) instead of one flat node like
     * "complaints", TotalAllTime here is rebuilt by adding up all 3
     * node's item counts.
     */
    private fun loadStats() {

        val dbRoot = FirebaseDatabase.getInstance()
            .getReference("officeSettings/new_connections")

        // Midnight timestamp — only the "Installed today" display
        // resets using this. Nothing about Total/Pending/Progress is
        // affected by midnight.
        val todayStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        dbRoot.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {

                // A) Items still sitting in the raw intake pool
                val pendingNodeCount = snapshot.child("pending").childrenCount.toInt()

                // B) gift_box — total items (for TotalAllTime) AND
                //    unique-employee count (for Progress), same pattern
                //    as complaints' progressEmployees HashSet.
                var giftBoxTotalItems = 0
                val progressEmployees = HashSet<String>()
                val giftBoxNode = snapshot.child("gift_box")
                for (employee in giftBoxNode.children) {
                    val empItemCount = employee.childrenCount.toInt()
                    giftBoxTotalItems += empItemCount
                    if (empItemCount > 0) {
                        progressEmployees.add(employee.key ?: "")
                    }
                }
                val progressCount = progressEmployees.size

                // C) completed — 3 levels deep: {Year}/{Month}/{Day}/{id}
                //    completedAllTimeCount -> used in the Pending subtraction
                //    installedTodayCount   -> used only for the Installed box display
                var completedAllTimeCount = 0
                var installedTodayCount = 0
                val completedNode = snapshot.child("completed")
                for (yearNode in completedNode.children) {
                    for (monthNode in yearNode.children) {
                        for (dayNode in monthNode.children) {
                            for (item in dayNode.children) {
                                completedAllTimeCount++
                                val compTime = item.child("completionTime")
                                    .getValue(Long::class.java) ?: 0L
                                if (compTime >= todayStart) {
                                    installedTodayCount++
                                }
                            }
                        }
                    }
                }

                // D) Rebuild "TotalAllTime" (equivalent of
                //    snapshot.childrenCount in the complaints version,
                //    since here it's split across 3 nodes instead of 1)
                val totalAllTime = pendingNodeCount + giftBoxTotalItems + completedAllTimeCount

                // E) SAME subtraction formula as complaints
                var pendingCount = totalAllTime - progressCount - completedAllTimeCount
                if (pendingCount < 0) {
                    pendingCount = 0
                }

                val totalCount = pendingCount + progressCount

                tvPending.text = "$pendingCount\nPENDING"
                tvProgress.text = "$progressCount\nPROGRESS"
                tvInstalled.text = "$installedTodayCount\nINSTALLED"
                tvTotal.text = "$totalCount\nTOTAL"
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }
}
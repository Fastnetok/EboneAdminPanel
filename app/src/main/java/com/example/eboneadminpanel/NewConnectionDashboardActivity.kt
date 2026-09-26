package com.example.eboneadminpanel

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.*
import java.util.Calendar

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
    private lateinit var scrollViewNc: ScrollView
    private lateinit var innerContentLayout: LinearLayout

    private var keyboardLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

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
        scrollViewNc = findViewById(R.id.scrollViewNc)
        innerContentLayout = findViewById(R.id.innerContentLayout)

        // Robust Keyboard-Aware WindowInsets / GlobalLayout automatic scrolling mechanism
        keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            scrollViewNc.getWindowVisibleDisplayFrame(r)
            val screenHeight = scrollViewNc.rootView.height
            val keypadHeight = screenHeight - r.bottom

            if (keypadHeight > screenHeight * 0.15) {
                // Keyboard is open — add bottom padding to ScrollView so top cards can scroll off-screen and form/button are fully accessible
                scrollViewNc.setPadding(
                    scrollViewNc.paddingLeft,
                    scrollViewNc.paddingTop,
                    scrollViewNc.paddingRight,
                    keypadHeight + 200
                )

                val focused = currentFocus
                if (focused != null) {
                    scrollViewNc.post {
                        scrollViewNc.smoothScrollTo(0, focused.top - 30)
                    }
                }
            } else {
                // Keyboard is closed — reset padding
                if (scrollViewNc.paddingBottom != 0) {
                    scrollViewNc.setPadding(
                        scrollViewNc.paddingLeft,
                        scrollViewNc.paddingTop,
                        scrollViewNc.paddingRight,
                        0
                    )
                }
            }
        }
        scrollViewNc.viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)

        val focusListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                scrollViewNc.postDelayed({
                    val topCoord = v.top - 30
                    scrollViewNc.smoothScrollTo(0, if (topCoord > 0) topCoord else 0)
                }, 200)
            }
        }
        etNcName.onFocusChangeListener = focusListener
        etNcAddress.onFocusChangeListener = focusListener
        etNcPhone.onFocusChangeListener = focusListener
        etNcComments.onFocusChangeListener = focusListener

        // Click Listeners
        tvTotal.setOnClickListener {
            startActivity(Intent(this, NewConnectionTotalActivity::class.java))
        }

        tvPending.setOnClickListener {
            startActivity(Intent(this, NewConnectionPendingSummaryActivity::class.java))
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

        loadStats()
    }

    override fun onDestroy() {
        super.onDestroy()
        keyboardLayoutListener?.let {
            scrollViewNc.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
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
                                    // Return to normal/initial position upon successful assignment
                                    scrollViewNc.smoothScrollTo(0, 0)
                                }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }
    }

    private fun loadStats() {
        val dbRoot = FirebaseDatabase.getInstance()
            .getReference("officeSettings/new_connections")

        dbRoot.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // 1. Intake pending items
                var intakePendingCount = 0
                val pendingNode = snapshot.child("pending")
                for (child in pendingNode.children) {
                    val conn = child.getValue(NewConnection::class.java)
                    if (conn != null && (!conn.customerName.isNullOrBlank() || !conn.id.isNullOrBlank())) {
                        intakePendingCount++
                    } else {
                        child.ref.removeValue()
                    }
                }

                // 2. PROGRESS (Front-of-queue active item per employee) & Queued Pending items
                var progressCount = 0
                var queuedPendingCount = 0

                val giftBoxNode = snapshot.child("gift_box")
                for (employee in giftBoxNode.children) {
                    var empItemCount = 0
                    for (child in employee.children) {
                        val conn = child.getValue(NewConnection::class.java)
                        if (conn != null && (!conn.customerName.isNullOrBlank() || !conn.id.isNullOrBlank())) {
                            empItemCount++
                        } else {
                            child.ref.removeValue()
                        }
                    }
                    if (empItemCount > 0) {
                        progressCount++ // 1 active item in Progress for this employee
                        queuedPendingCount += (empItemCount - 1) // Any extra items are queued Pending
                    }
                }

                val totalPendingCount = intakePendingCount + queuedPendingCount

                // 3. INSTALLED: Completed items installed TODAY (Midnight reset rule)
                val todayStart = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                var installedTodayCount = 0
                val completedNode = snapshot.child("completed")
                for (yearNode in completedNode.children) {
                    for (monthNode in yearNode.children) {
                        for (dayNode in monthNode.children) {
                            for (item in dayNode.children) {
                                val compTime = item.child("completionTime").getValue(Long::class.java)
                                    ?: item.child("createdTime").getValue(Long::class.java)
                                    ?: 0L
                                if (compTime >= todayStart) {
                                    installedTodayCount++
                                }
                            }
                        }
                    }
                }

                // 4. TOTAL ACTIVE = PENDING + PROGRESS
                val totalCount = totalPendingCount + progressCount

                tvTotal.text = "$totalCount\nTOTAL"
                tvPending.text = "$totalPendingCount\nPENDING"
                tvProgress.text = "$progressCount\nPROGRESS"
                tvInstalled.text = "$installedTodayCount\nINSTALLED"
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }
}

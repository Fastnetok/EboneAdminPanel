package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AutoComplaintMonitorActivity : AppCompatActivity() {

    private lateinit var switchMasterMonitor: Switch
    private lateinit var etTimer: EditText
    private lateinit var rvMonitorEmployees: RecyclerView
    private lateinit var btnSaveSettings: Button
    private lateinit var btnTestNow: Button
    private lateinit var progressBar: ProgressBar

    private lateinit var adapter: AutoMonitorAdapter

    private var officeStartTotal = 600
    private var officeEndTotal = 1320
    private var allowStartTotal = 540
    private var allowEndTotal = 1380

    private var employeesSnap: DataSnapshot? = null
    private var attendanceSnap: DataSnapshot? = null
    private var complaintsSnap: DataSnapshot? = null
    private var monitoredMapSnap: DataSnapshot? = null
    
    private var initialSettingsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_complaint_monitor)

        switchMasterMonitor = findViewById(R.id.switchMasterMonitor)
        etTimer = findViewById(R.id.etTimer)
        rvMonitorEmployees = findViewById(R.id.rvMonitorEmployees)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        btnTestNow = findViewById(R.id.btnTestNow)
        progressBar = findViewById(R.id.progressBar)

        adapter = AutoMonitorAdapter(emptyList())
        rvMonitorEmployees.layoutManager = LinearLayoutManager(this)
        rvMonitorEmployees.adapter = adapter

        loadInitialSettings()
        syncMonitorData()

        btnSaveSettings.setOnClickListener {
            saveSettings()
        }

        btnTestNow.setOnClickListener {
            runTestNow()
        }
    }

    private fun loadInitialSettings() {
        progressBar.visibility = View.VISIBLE
        val ref = FirebaseDatabase.getInstance().getReference("officeSettings").child("auto_monitor")
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val masterEnabled = snapshot.child("master_enabled").getValue(Boolean::class.java) ?: false
                val minutes = snapshot.child("resolve_timeout_minutes").toInt(30)
                
                switchMasterMonitor.isChecked = masterEnabled
                etTimer.setText(minutes.toString())
                
                initialSettingsLoaded = true
                refreshMonitorData()
            }
            override fun onCancelled(error: DatabaseError) {
                progressBar.visibility = View.GONE
            }
        })
    }

    private fun syncMonitorData() {
        val fb = FirebaseDatabase.getInstance()

        fb.getReference("officeSettings").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val startH = snapshot.child("startHour").toInt(10)
                val startM = snapshot.child("startMinute").toInt(0)
                val endH = snapshot.child("endHour").toInt(22)
                val endM = snapshot.child("endMinute").toInt(0)
                val preShift = snapshot.child("preShiftMinutes").toInt(60)
                val postShift = snapshot.child("postShiftMinutes").toInt(60)

                officeStartTotal = startH * 60 + startM
                officeEndTotal = endH * 60 + endM
                allowStartTotal = officeStartTotal - preShift
                allowEndTotal = officeEndTotal + postShift

                refreshMonitorData()
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        fb.getReference("employees").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                employeesSnap = s
                refreshMonitorData()
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        fb.getReference("attendance").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                attendanceSnap = s
                refreshMonitorData()
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        fb.getReference("complaints").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                complaintsSnap = s
                refreshMonitorData()
            }
            override fun onCancelled(e: DatabaseError) {}
        })

        fb.getReference("officeSettings").child("auto_monitor").child("monitored_employees").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                monitoredMapSnap = s
                refreshMonitorData()
            }
            override fun onCancelled(e: DatabaseError) {}
        })
    }

    private fun refreshMonitorData() {
        val eSnap = employeesSnap ?: return
        
        val aSnap = attendanceSnap
        val cSnap = complaintsSnap
        val mMapSnap = monitoredMapSnap
        
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val calendar = Calendar.getInstance()
        val nowTotal = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        val isWithinWindow = nowTotal in allowStartTotal..allowEndTotal

        val onlineList = mutableListOf<Pair<EmployeeItem, Int>>()

        for (emp in eSnap.children) {
            val deviceId = emp.key ?: ""
            val name = emp.child("employeeName").value?.toString() ?: "Unknown"

            val hasAttendance = aSnap?.child(deviceId)?.hasChild(todayKey) == true || 
                               (name != "Unknown" && aSnap?.child(name)?.hasChild(todayKey) == true)

            if (hasAttendance && isWithinWindow) {
                var totalComplaints = 0
                if (cSnap != null) {
                    for (comp in cSnap.children) {
                        val assignedTo = comp.child("assignedTo").value?.toString() ?: ""
                        val status = comp.child("status").value?.toString() ?: ""
                        // Use exact name match as in AddComplaint/Adapter for consistency
                        if (assignedTo == name && status != "Resolved") {
                            totalComplaints++
                        }
                    }
                }

                val isMonitored = mMapSnap?.child(deviceId)?.getValue(Boolean::class.java) ?: false
                onlineList.add(EmployeeItem(deviceId, name, "ONLINE", isMonitored) to totalComplaints)
            }
        }

        // IMPORTANT: Sorting Priority:
        // 1. Higher complaints count first
        // 2. If complaints equal, those being MONITORED (Switch ON) stay on top
        // 3. Alphabetical order as a final fallback
        val sortedList = onlineList.sortedWith(
            compareByDescending<Pair<EmployeeItem, Int>> { it.second } // Complaints count
                .thenByDescending { it.first.isMonitored }             // Monitored status
                .thenBy { it.first.name }                              // Alphabetical name
        ).map { it.first }

        adapter.updateList(sortedList)
        
        if (initialSettingsLoaded) {
            progressBar.visibility = View.GONE
        }
    }

    private fun DataSnapshot.toInt(default: Int): Int {
        val v = value ?: return default
        return when (v) {
            is Long -> v.toInt()
            is Int -> v
            is String -> v.toIntOrNull() ?: default
            is Double -> v.toInt()
            else -> default
        }
    }

    private fun saveSettings() {
        val masterEnabled = switchMasterMonitor.isChecked
        val timerValue = etTimer.text.toString().toIntOrNull() ?: 30

        val ref = FirebaseDatabase.getInstance().getReference("officeSettings").child("auto_monitor")
        val data = mapOf(
            "master_enabled" to masterEnabled,
            "resolve_timeout_minutes" to timerValue
        )
        
        ref.updateChildren(data).addOnSuccessListener {
            Toast.makeText(this, "Monitor Settings Updated Successfully", Toast.LENGTH_SHORT).show()
            if (masterEnabled) {
                startAutoResolveWorker()
            } else {
                stopAutoResolveWorker()
                resetAllLiveStatuses()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Error: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetAllLiveStatuses() {
        FirebaseDatabase.getInstance().getReference("officeSettings")
            .child("auto_monitor")
            .child("live_status")
            .removeValue()
    }

    private fun runTestNow() {
        val data = Data.Builder().putBoolean("is_test_now", true).build()
        val workRequest = OneTimeWorkRequestBuilder<AutoResolveWorker>()
            .setInputData(data)
            .build()
        WorkManager.getInstance(this).enqueue(workRequest)
        Toast.makeText(this, "Silent Test Started: Checking ISP Panels...", Toast.LENGTH_SHORT).show()
    }

    private fun startAutoResolveWorker() {
        val workRequest = PeriodicWorkRequestBuilder<AutoResolveWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "AutoResolveWork",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    private fun stopAutoResolveWorker() {
        WorkManager.getInstance(this).cancelUniqueWork("AutoResolveWork")
    }
}

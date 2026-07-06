package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        val addComplaint = findViewById<TextView>(R.id.menuAddComplaint)
        val pendingDevices = findViewById<TextView>(R.id.menuPendingDevices)
        val allEmployees = findViewById<TextView>(R.id.menuAllEmployees)
        val geoFence = findViewById<TextView>(R.id.menuGeoFence)
        val complaints = findViewById<TextView>(R.id.menuComplaints)
        val assignedComplaints = findViewById<TextView>(R.id.menuAssignedComplaints)
        val reports = findViewById<TextView>(R.id.menuReports)
        val settings = findViewById<TextView>(R.id.menuSettings)
        val superAdminPanel = findViewById<TextView>(R.id.menuSuperAdmin)

        addComplaint.setOnClickListener {
            startActivity(Intent(this, AddComplaintActivity::class.java))
        }
        geoFence.setOnClickListener {
            startActivity(Intent(this, GeoFenceManagementActivity::class.java))
        }
        complaints.setOnClickListener {
            startActivity(Intent(this, PendingComplaintsActivity::class.java))
        }
        assignedComplaints.setOnClickListener {
            startActivity(Intent(this, AssignedComplaintsActivity::class.java))
        }
        reports.setOnClickListener {
            startActivity(Intent(this, ReportsActivity::class.java))
        }
        pendingDevices.setOnClickListener {
            startActivity(Intent(this, PendingDevicesActivity::class.java))
        }
        allEmployees.setOnClickListener {
            startActivity(Intent(this, AllEmployeesActivity::class.java))
        }
        settings.setOnClickListener {
            startActivity(Intent(this, ManagementToolsActivity::class.java))
        }
        superAdminPanel.setOnClickListener {
            startActivity(
                Intent(this, com.example.eboneadminpanel.superadmin.dashboard.SuperDashboardActivity::class.java)
            )
        }
    }
}
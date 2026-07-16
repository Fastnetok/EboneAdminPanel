package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_menu)

        val addComplaint =
            findViewById<TextView>(
                R.id.menuAddComplaint
            )

        val pendingDevices =
            findViewById<TextView>(
                R.id.menuPendingDevices
            )

        val allEmployees =
            findViewById<TextView>(
                R.id.menuAllEmployees
            )

        val reports =
            findViewById<TextView>(
                R.id.menuReports
            )

        val movementTracking =
            findViewById<TextView>(
                R.id.menuMovementTracking
            )

        val settings =
            findViewById<TextView>(
                R.id.menuSettings
            )

        addComplaint.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    AddComplaintActivity::class.java
                )
            )
        }

        reports.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    ReportsActivity::class.java
                )
            )
        }

        movementTracking.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    MovementTrackingActivity::class.java
                )
            )
        }

        pendingDevices.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    PendingDevicesActivity::class.java
                )
            )
        }

        allEmployees.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    AllEmployeesActivity::class.java
                )
            )
        }

        settings.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    ManagementToolsActivity::class.java
                )
            )
        }
    }
}
package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

class MenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_menu)

        val addComplaint =
            findViewById<TextView>(R.id.menuAddComplaint)

        val pendingDevices =
            findViewById<TextView>(R.id.menuPendingDevices)

        val allEmployees =
            findViewById<TextView>(R.id.menuAllEmployees)

        val reports =
            findViewById<TextView>(R.id.menuReports)

        val movementTracking =
            findViewById<TextView>(R.id.menuMovementTracking)

        val settings =
            findViewById<TextView>(R.id.menuSettings)

        // NEW
        val logout =
            findViewById<TextView>(R.id.menuLogout)

        addComplaint.setOnClickListener {
            startActivity(Intent(this, AddComplaintActivity::class.java))
        }

        reports.setOnClickListener {
            startActivity(Intent(this, ReportsActivity::class.java))
        }

        movementTracking.setOnClickListener {
            startActivity(Intent(this, MovementTrackingActivity::class.java))
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

        // NEW: Logout — asks for confirmation, then signs out of Firebase,
        // clears the saved local PIN, and sends the user back to the very
        // start of the login flow (clearing the whole activity back-stack
        // so pressing "back" can't return to the dashboard).
        logout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setPositiveButton("Logout") { _, _ ->
                    FirebaseAuth.getInstance().signOut()
                    SetPinActivity.clearPin(this)

                    val intent = Intent(this, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
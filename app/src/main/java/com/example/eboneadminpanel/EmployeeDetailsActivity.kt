package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase

class EmployeeDetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_employee_details
        )

        val employeeInfo =
            findViewById<TextView>(
                R.id.employeeInfoText
            )

        val employeeId =
            intent.getStringExtra(
                "employeeId"
            ) ?: ""

        FirebaseDatabase
            .getInstance()
            .getReference("employees")
            .child(employeeId)
            .get()
            .addOnSuccessListener { snapshot ->

                if (!snapshot.exists()) {

                    employeeInfo.text =
                        "Employee Not Found"

                    return@addOnSuccessListener
                }

                val name =
                    snapshot.child(
                        "employeeName"
                    ).value?.toString()
                        ?: "Unknown"

                val status =
                    snapshot.child(
                        "status"
                    ).value?.toString()
                        ?: "OFFLINE"

                val latitude =
                    snapshot.child(
                        "latitude"
                    ).value?.toString()
                        ?: "-"

                val longitude =
                    snapshot.child(
                        "longitude"
                    ).value?.toString()
                        ?: "-"

                val lastUpdate =
                    snapshot.child(
                        "lastUpdate"
                    ).value?.toString()
                        ?: "-"

                employeeInfo.text =
                    """
EMPLOYEE DETAILS

Name : $name

Status : $status

Latitude : $latitude

Longitude : $longitude

Last Update : $lastUpdate

CLICK HERE TO OPEN MAP
                    """.trimIndent()

                employeeInfo.setOnClickListener {

                    val intent =
                        Intent(
                            this,
                            MapActivity::class.java
                        )

                    intent.putExtra(
                        "employeeId",
                        employeeId
                    )

                    startActivity(intent)
                }
            }
            .addOnFailureListener {

                employeeInfo.text =
                    "Firebase Connection Error"
            }
    }
}
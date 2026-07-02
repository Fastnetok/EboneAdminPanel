package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class EmployeeReportDetailsActivity :
    AppCompatActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_employee_report_details
        )

        val employeeName =
            intent.getStringExtra(
                "employeeName"
            ) ?: "Employee"

        findViewById<TextView>(
            R.id.employeeNameText
        ).text = employeeName
    }
}
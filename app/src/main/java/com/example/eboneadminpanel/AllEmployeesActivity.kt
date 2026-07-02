package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.FirebaseDatabase

class AllEmployeesActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView

    private val employeeList =
        mutableListOf<EmployeeItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_all_employees
        )

        recyclerView =
            findViewById(
                R.id.recyclerView
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        loadEmployees()
    }

    private fun loadEmployees() {

        FirebaseDatabase
            .getInstance()
            .getReference("employees")
            .get()
            .addOnSuccessListener { snapshot ->

                employeeList.clear()

                for (employee in snapshot.children) {

                    val employeeId =
                        employee.key ?: ""

                    val name =
                        employee.child(
                            "employeeName"
                        ).value?.toString()
                            ?: "Unknown"

                    val status =
                        employee.child(
                            "status"
                        ).value?.toString()
                            ?: "OFFLINE"

                    employeeList.add(
                        EmployeeItem(
                            employeeId,
                            name,
                            status
                        )
                    )
                }

                recyclerView.adapter =
                    EmployeeAdapter(
                        employeeList
                    ) { employee ->

                        val intent =
                            Intent(
                                this,
                                EmployeeDetailsActivity::class.java
                            )

                        intent.putExtra(
                            "employeeId",
                            employee.employeeId
                        )

                        startActivity(intent)
                    }
            }
    }
}
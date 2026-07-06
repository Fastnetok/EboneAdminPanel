package com.example.eboneadminpanel.superadmin.dashboard

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.viewModels
import android.widget.TextView
import com.example.eboneadminpanel.R

class SuperDashboardActivity : AppCompatActivity() {

    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_super_dashboard)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val tvTotalCompanies = findViewById<TextView>(R.id.tvTotalCompanies)
        val tvTotalEmployees = findViewById<TextView>(R.id.tvTotalEmployees)
        val tvTotalComplaints = findViewById<TextView>(R.id.tvTotalComplaints)
        val tvPendingComplaints = findViewById<TextView>(R.id.tvPendingComplaints)
        val tvResolvedComplaints = findViewById<TextView>(R.id.tvResolvedComplaints)

        viewModel.stats.observe(this) { stats ->
            tvTotalCompanies.text = "Total Companies: ${stats.totalCompanies}"
            tvTotalEmployees.text = "Total Employees: ${stats.totalEmployees}"
            tvTotalComplaints.text = "Total Complaints: ${stats.totalComplaints}"
            tvPendingComplaints.text = "Pending: ${stats.pendingComplaints}"
            tvResolvedComplaints.text = "Resolved: ${stats.resolvedComplaints}"
        }

        viewModel.loadDashboardStats()
    }
}
package com.example.eboneadminpanel.superadmin.companymanager

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.Button
import com.example.eboneadminpanel.R

class CompanyManagerActivity : AppCompatActivity() {

    private val viewModel: CompanyViewModel by viewModels()
    private lateinit var adapter: CompanyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_company_manager)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val rvCompanies = findViewById<RecyclerView>(R.id.rvCompanies)
        val btnAddCompany = findViewById<Button>(R.id.btnAddCompany)

        adapter = CompanyAdapter(
            companies = emptyList(),
            onEditClick = { company -> EditCompanyDialog.show(this, company, viewModel) },
            onDeleteClick = { company -> EditCompanyDialog.show(this, company, viewModel) }
        )

        rvCompanies.layoutManager = LinearLayoutManager(this)
        rvCompanies.adapter = adapter

        btnAddCompany.setOnClickListener {
            AddCompanyDialog.show(this, viewModel)
        }

        viewModel.companies.observe(this) { companies ->
            adapter.updateList(companies)
        }

        viewModel.loadCompanies()
    }
}
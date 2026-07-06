package com.example.eboneadminpanel.superadmin.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.eboneadminpanel.models.Company
import com.example.eboneadminpanel.repositories.CompanyRepository

class DashboardViewModel : ViewModel() {

    private val companyRepository = CompanyRepository()

    private val _stats = MutableLiveData<DashboardStats>()
    val stats: LiveData<DashboardStats> = _stats

    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> = _errorMessage

    fun loadDashboardStats() {
        companyRepository.getAllCompanies(object : CompanyRepository.CompanyListCallback {
            override fun onSuccess(companies: List<Company>) {
                _stats.value = buildStatsFromCompanies(companies)
            }

            override fun onError(message: String) {
                _errorMessage.value = message
            }
        })
    }

    private fun buildStatsFromCompanies(companies: List<Company>): DashboardStats {
        val stats = DashboardStats()
        stats.totalCompanies = companies.size

        for (company in companies) {
            stats.totalEmployees += company.totalEmployees
            stats.totalComplaints += company.totalComplaints
            stats.pendingComplaints += company.pendingComplaints
            stats.resolvedComplaints += company.resolvedComplaints
        }
        stats.inProgressComplaints =
            stats.totalComplaints - stats.pendingComplaints - stats.resolvedComplaints

        return stats
    }
}
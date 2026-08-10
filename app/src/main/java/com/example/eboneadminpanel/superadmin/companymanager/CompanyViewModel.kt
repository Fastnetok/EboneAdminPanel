package com.example.eboneadminpanel.superadmin.companymanager

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.eboneadminpanel.core.Constants
import com.example.eboneadminpanel.models.Company
import com.example.eboneadminpanel.repositories.CompanyRepository
import com.example.eboneadminpanel.utils.CompanyIdGenerator

class CompanyViewModel : ViewModel() {

    private val repository = CompanyRepository()

    private val _companies = MutableLiveData<List<Company>>()
    val companies: LiveData<List<Company>> = _companies

    private val _operationResult = MutableLiveData<String>()
    val operationResult: LiveData<String> = _operationResult

    fun loadCompanies() {
        repository.getAllCompanies(object : CompanyRepository.CompanyListCallback {
            override fun onSuccess(companies: List<Company>) {
                _companies.value = companies
            }

            override fun onError(message: String) {
                _operationResult.value = "Error: $message"
            }
        })
    }

    fun addNewCompany(companyName: String, city: String, ownerName: String) {
        val cityPrefix = Constants.CITY_PREFIXES[city] ?: city.take(3).uppercase()

        CompanyIdGenerator.generateNextId(cityPrefix, object : CompanyIdGenerator.IdGeneratedCallback {
            override fun onIdGenerated(companyId: String) {
                val newCompany = Company(
                    companyId = companyId,
                    companyName = companyName,
                    city = city,
                    ownerName = ownerName,
                    licenseStatus = Constants.LICENSE_ACTIVE,
                    createdAt = System.currentTimeMillis()
                )

                repository.addCompany(newCompany, object : CompanyRepository.SimpleCallback {
                    override fun onSuccess() {
                        _operationResult.value = "Company '$companyName' successfully add ho gayi ($companyId)"
                        loadCompanies()
                    }

                    override fun onError(message: String) {
                        _operationResult.value = "Error: $message"
                    }
                })
            }

            override fun onError(message: String) {
                _operationResult.value = "Error: $message"
            }
        })
    }

    fun updateCompany(company: Company) {
        repository.updateCompany(company, object : CompanyRepository.SimpleCallback {
            override fun onSuccess() {
                _operationResult.value = "Company update ho gayi"
                loadCompanies()
            }

            override fun onError(message: String) {
                _operationResult.value = "Error: $message"
            }
        })
    }

    fun deleteCompany(companyId: String) {
        repository.deleteCompany(companyId, object : CompanyRepository.SimpleCallback {
            override fun onSuccess() {
                _operationResult.value = "Company delete ho gayi"
                loadCompanies()
            }

            override fun onError(message: String) {
                _operationResult.value = "Error: $message"
            }
        })
    }
}
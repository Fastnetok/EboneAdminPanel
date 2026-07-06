package com.example.eboneadminpanel.repositories

import com.example.eboneadminpanel.core.FirestorePaths
import com.example.eboneadminpanel.models.Company
import com.google.firebase.firestore.FirebaseFirestore

class CompanyRepository {

    private val db = FirebaseFirestore.getInstance()
    private val companiesRef = db.collection(FirestorePaths.COMPANIES)

    interface CompanyListCallback {
        fun onSuccess(companies: List<Company>)
        fun onError(message: String)
    }

    interface SimpleCallback {
        fun onSuccess()
        fun onError(message: String)
    }

    fun addCompany(company: Company, callback: SimpleCallback) {
        companiesRef.document(company.companyId)
            .set(company)
            .addOnSuccessListener { callback.onSuccess() }
            .addOnFailureListener { e -> callback.onError(e.message ?: "Company add nahi ho saki") }
    }

    fun getAllCompanies(callback: CompanyListCallback) {
        companiesRef.get()
            .addOnSuccessListener { snapshot ->
                val companies = snapshot.documents.mapNotNull { it.toObject(Company::class.java) }
                callback.onSuccess(companies)
            }
            .addOnFailureListener { e -> callback.onError(e.message ?: "Companies load nahi hui") }
    }

    fun updateCompany(company: Company, callback: SimpleCallback) {
        companiesRef.document(company.companyId)
            .set(company)
            .addOnSuccessListener { callback.onSuccess() }
            .addOnFailureListener { e -> callback.onError(e.message ?: "Company update nahi ho saki") }
    }

    fun deleteCompany(companyId: String, callback: SimpleCallback) {
        companiesRef.document(companyId)
            .delete()
            .addOnSuccessListener { callback.onSuccess() }
            .addOnFailureListener { e -> callback.onError(e.message ?: "Company delete nahi ho saki") }
    }
}
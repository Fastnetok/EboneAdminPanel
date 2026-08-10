package com.example.eboneadminpanel.superadmin.companymanager

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.example.eboneadminpanel.R
import com.example.eboneadminpanel.models.Company

object EditCompanyDialog {

    fun show(context: Context, company: Company, viewModel: CompanyViewModel) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_add_company, null)

        val etCompanyName = view.findViewById<EditText>(R.id.etCompanyName)
        val etCity = view.findViewById<EditText>(R.id.etCity)
        val etOwnerName = view.findViewById<EditText>(R.id.etOwnerName)

        etCompanyName.setText(company.companyName)
        etCity.setText(company.city)
        etOwnerName.setText(company.ownerName)

        AlertDialog.Builder(context)
            .setTitle("Company Edit Karein (${company.companyId})")
            .setView(view)
            .setPositiveButton("Update") { _, _ ->
                val companyName = etCompanyName.text.toString().trim()
                val city = etCity.text.toString().trim()
                val ownerName = etOwnerName.text.toString().trim()

                val result = CompanyValidator.validate(companyName, city, ownerName)
                if (result.isValid) {
                    val updatedCompany = company.copy(
                        companyName = companyName,
                        city = city,
                        ownerName = ownerName
                    )
                    viewModel.updateCompany(updatedCompany)
                } else {
                    Toast.makeText(context, result.errorMessage, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                viewModel.deleteCompany(company.companyId)
            }
            .show()
    }
}
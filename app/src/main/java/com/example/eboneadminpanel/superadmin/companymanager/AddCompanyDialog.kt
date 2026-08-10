package com.example.eboneadminpanel.superadmin.companymanager

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.example.eboneadminpanel.R

object AddCompanyDialog {

    fun show(context: Context, viewModel: CompanyViewModel) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_add_company, null)

        val etCompanyName = view.findViewById<EditText>(R.id.etCompanyName)
        val etCity = view.findViewById<EditText>(R.id.etCity)
        val etOwnerName = view.findViewById<EditText>(R.id.etOwnerName)

        AlertDialog.Builder(context)
            .setTitle("Nayi Company Add Karein")
            .setView(view)
            .setPositiveButton("Add") { _, _ ->
                val companyName = etCompanyName.text.toString().trim()
                val city = etCity.text.toString().trim()
                val ownerName = etOwnerName.text.toString().trim()

                val result = CompanyValidator.validate(companyName, city, ownerName)
                if (result.isValid) {
                    viewModel.addNewCompany(companyName, city, ownerName)
                } else {
                    Toast.makeText(context, result.errorMessage, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
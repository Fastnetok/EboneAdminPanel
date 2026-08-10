package com.example.eboneadminpanel.superadmin.companymanager

object CompanyValidator {

    data class ValidationResult(val isValid: Boolean, val errorMessage: String? = null)

    fun validate(companyName: String, city: String, ownerName: String): ValidationResult {
        if (companyName.isBlank()) {
            return ValidationResult(false, "Company ka naam khali nahi ho sakta")
        }
        if (city.isBlank()) {
            return ValidationResult(false, "Shehar (City) khali nahi ho sakta")
        }
        if (ownerName.isBlank()) {
            return ValidationResult(false, "Owner ka naam khali nahi ho sakta")
        }
        if (companyName.length < 3) {
            return ValidationResult(false, "Company ka naam kam se kam 3 harf ka ho")
        }
        return ValidationResult(true)
    }
}
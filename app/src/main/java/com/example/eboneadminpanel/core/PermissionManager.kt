package com.example.eboneadminpanel.core

class PermissionManager(private val sessionManager: SessionManager) {

    fun canManageCompanies(): Boolean {
        return sessionManager.getUserRole() == Constants.ROLE_SUPER_ADMIN
    }

    fun canManageLicense(): Boolean {
        return sessionManager.getUserRole() == Constants.ROLE_SUPER_ADMIN
    }

    fun canViewAllCompaniesData(): Boolean {
        return sessionManager.getUserRole() == Constants.ROLE_SUPER_ADMIN
    }

    fun canEditLicenseInfo(): Boolean {
        return sessionManager.getUserRole() == Constants.ROLE_SUPER_ADMIN
    }

    fun canManageEmployees(): Boolean {
        val role = sessionManager.getUserRole()
        return role == Constants.ROLE_SUPER_ADMIN ||
                role == Constants.ROLE_COMPANY_ADMIN ||
                role == Constants.ROLE_MANAGER
    }

    fun canAssignComplaints(): Boolean {
        val role = sessionManager.getUserRole()
        return role == Constants.ROLE_SUPER_ADMIN ||
                role == Constants.ROLE_COMPANY_ADMIN ||
                role == Constants.ROLE_MANAGER ||
                role == Constants.ROLE_SUPERVISOR
    }
}
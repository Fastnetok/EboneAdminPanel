package com.example.eboneadminpanel.superadmin.dashboard

data class DashboardStats(
    var totalCompanies: Int = 0,
    var totalEmployees: Int = 0,
    var totalComplaints: Int = 0,
    var pendingComplaints: Int = 0,
    var resolvedComplaints: Int = 0,
    var inProgressComplaints: Int = 0
)
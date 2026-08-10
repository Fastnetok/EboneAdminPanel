package com.example.eboneadminpanel.models

data class Company(
    var companyId: String = "",
    var companyName: String = "",
    var city: String = "",
    var ownerName: String = "",
    var licenseStatus: String = "active",
    var licenseExpiry: Long = 0L,
    var createdAt: Long = 0L,
    var totalEmployees: Int = 0,
    var totalComplaints: Int = 0,
    var pendingComplaints: Int = 0,
    var resolvedComplaints: Int = 0
) {
    constructor() : this("", "", "", "", "active", 0L, 0L, 0, 0, 0, 0)
}
package com.example.eboneadminpanel

// Firebase requires a no-argument constructor for automatic deserialization,
// which is why every field has a default value below.
data class PendingDevice(
    val androidId: String = "",
    val employeeName: String = "",
    val mobileNumber: String = "",
    val status: String = "Pending",
    val uid: String = "",
    val createdAt: Long = 0L
)
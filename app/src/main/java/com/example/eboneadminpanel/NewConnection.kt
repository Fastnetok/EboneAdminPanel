package com.example.eboneadminpanel

data class NewConnection(
    var id: String = "",
    var customerName: String = "",
    var address: String = "",
    var phoneNumber: String = "",
    var comments: String = "",
    var status: String = "Pending", // Pending, Progress, Completed, Cancelled
    var assignedTo: String = "",
    var assignedTime: Long = 0,
    var createdTime: Long = 0,
    var seenByEmployee: Boolean = false,
    var seenTime: Long = 0,
    var completionTime: Long = 0,
    var cancellationReason: String = "",
    // NEW: was missing here even though it's already written by the
    // Field Manager app's drag-and-drop reordering (NewConnectionListActivity.kt)
    // and read by Complaints' equivalent (Complaint.kt already has it).
    // Needed so NewConnectionProgressActivity.kt can pick each
    // employee's front-of-queue item correctly.
    var displayOrder: Long = 0
)
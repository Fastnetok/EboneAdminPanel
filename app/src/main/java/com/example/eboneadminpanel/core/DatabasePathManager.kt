package com.example.eboneadminpanel.core

import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

object DatabasePathManager {

    private val database = FirebaseDatabase.getInstance()

    fun employeesRef(companyId: String): DatabaseReference {
        return database.getReference("companies").child(companyId).child("employees")
    }

    fun complaintsRef(companyId: String): DatabaseReference {
        return database.getReference("companies").child(companyId).child("complaints")
    }

    fun approvedDevicesRef(companyId: String): DatabaseReference {
        return database.getReference("companies").child(companyId).child("ApprovedDevices")
    }

    fun geofencesRef(companyId: String): DatabaseReference {
        return database.getReference("companies").child(companyId).child("geofences")
    }

    fun geofenceLogsRef(companyId: String): DatabaseReference {
        return database.getReference("companies").child(companyId).child("geofenceLogs")
    }

    fun resolvedComplaintsRef(companyId: String): DatabaseReference {
        return database.getReference("companies").child(companyId).child("resolvedComplaints")
    }

    fun adminNotificationsRef(companyId: String): DatabaseReference {
        return database.getReference("companies").child(companyId).child("adminNotifications")
    }

    fun employeeNotificationsRef(companyId: String): DatabaseReference {
        return database.getReference("companies").child(companyId).child("employeeNotifications")
    }

    fun complaintEmployeesRef(companyId: String): DatabaseReference {
        return database.getReference("companies").child(companyId).child("complaintEmployees")
    }
}
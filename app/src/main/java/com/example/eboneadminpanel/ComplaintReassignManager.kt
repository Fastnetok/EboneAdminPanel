package com.example.eboneadminpanel

import com.google.firebase.database.FirebaseDatabase

class ComplaintReassignManager {

    fun reassignComplaint(

        complaintId: String,

        employeeName: String,

        onSuccess: () -> Unit,

        onFailure: (String) -> Unit

    ) {

        FirebaseDatabase
            .getInstance()
            .getReference("complaints")
            .child(complaintId)
            .child("assignedTo")
            .setValue(employeeName)

            .addOnSuccessListener {

                onSuccess()

            }

            .addOnFailureListener {

                onFailure(
                    it.message ?: "Unknown Error"
                )

            }

    }

}
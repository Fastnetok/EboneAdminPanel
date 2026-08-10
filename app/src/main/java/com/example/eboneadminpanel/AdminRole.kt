package com.example.eboneadminpanel

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

object AdminRole {

    // Fetches the current admin's role ("owner" / "manager" / "supervisor")
    // once and hands it back through the callback. Any screen that needs to
    // hide a button for certain roles should call this in onCreate().
    fun fetch(onResult: (role: String?) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            onResult(null)
            return
        }

        FirebaseDatabase.getInstance()
            .getReference("admins")
            .child(uid)
            .child("role")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    onResult(snapshot.getValue(String::class.java))
                }

                override fun onCancelled(error: DatabaseError) {
                    onResult(null)
                }
            })
    }

    fun canManageFuel(role: String?): Boolean {
        return role == "owner" || role == "manager"
    }

    fun canManageCompanies(role: String?): Boolean {
        return role == "owner"
    }

    fun canManageGeofences(role: String?): Boolean {
        return role == "owner" || role == "manager"
    }
}
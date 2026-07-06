package com.example.eboneadminpanel.core

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

object RoleManager {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    interface RoleCheckCallback {
        fun onRoleFound(role: String, companyId: String?)
        fun onError(message: String)
    }

    fun checkCurrentUserRole(callback: RoleCheckCallback) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            callback.onError("User logged in nahi hai")
            return
        }

        db.collection(FirestorePaths.SUPER_ADMIN)
            .document(uid)
            .get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    callback.onRoleFound(Constants.ROLE_SUPER_ADMIN, null)
                } else {
                    findCompanyForAdmin(uid, callback)
                }
            }
            .addOnFailureListener { e ->
                callback.onError(e.message ?: "Role check karte waqt error aaya")
            }
    }

    private fun findCompanyForAdmin(uid: String, callback: RoleCheckCallback) {
        callback.onRoleFound(Constants.ROLE_COMPANY_ADMIN, null)
    }
}
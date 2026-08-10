package com.example.eboneadminpanel

import android.util.Log
import com.google.firebase.database.FirebaseDatabase

object FCMSender {

    fun sendNotification(

        employeeName: String,

        title: String,

        message: String

    ) {

        Log.d(
            "FCM_TEST",
            "Employee: $employeeName"
        )

        FirebaseDatabase
            .getInstance()
            .getReference("ApprovedDevices")
            .get()

            .addOnSuccessListener { snapshot ->

                for (child in snapshot.children) {

                    val dbEmployeeName =

                        child.child(
                            "employeeName"
                        ).getValue(
                            String::class.java
                        ) ?: ""

                    if (
                        dbEmployeeName ==
                        employeeName
                    ) {

                        val token =

                            child.child(
                                "fcmToken"
                            ).getValue(
                                String::class.java
                            ) ?: ""

                        Log.d(
                            "FCM_TOKEN_FOUND",
                            token
                        )

                        break
                    }
                }
            }
    }
}
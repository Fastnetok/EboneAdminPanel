package com.example.eboneadminpanel

import com.google.firebase.database.FirebaseDatabase

data class RepeatInfo(

    val isRepeat: Boolean = false,

    val repeatCount: Int = 0,

    val lastResolver: String = "",

    val lastResolvedTime: Long = 0

)

class RepeatComplaintManager {

    fun checkRepeatComplaint(

        userId: String,

        callback: (RepeatInfo) -> Unit

    ) {

        FirebaseDatabase
            .getInstance()
            .getReference("complaints")
            .orderByChild("userId")
            .equalTo(userId)
            .get()

            .addOnSuccessListener { snapshot ->

                var repeatCount = 0
                var lastResolver = ""
                var lastResolvedTime = 0L

                for (item in snapshot.children) {

                    val complaint =
                        item.getValue(
                            Complaint::class.java
                        ) ?: continue

                    repeatCount++

                    if (
                        complaint.resolvedTime >
                        lastResolvedTime
                    ) {

                        lastResolvedTime =
                            complaint.resolvedTime

                        lastResolver =
                            complaint.resolvedBy
                    }
                }

                callback(

                    RepeatInfo(

                        isRepeat =
                            repeatCount > 0,

                        repeatCount =
                            repeatCount,

                        lastResolver =
                            lastResolver,

                        lastResolvedTime =
                            lastResolvedTime

                    )

                )

            }

            .addOnFailureListener {

                callback(
                    RepeatInfo()
                )

            }

    }

}
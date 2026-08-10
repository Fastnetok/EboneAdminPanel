package com.example.eboneadminpanel

import com.google.firebase.database.*

class ComplaintSearchManager {

    private val complaintsRef =
        FirebaseDatabase
            .getInstance()
            .getReference("complaints")

    private val resolvedRef =
        FirebaseDatabase
            .getInstance()
            .getReference("resolvedComplaints")

    fun searchByUserId(
        keyword: String,
        callback: (MutableList<Complaint>) -> Unit
    ) {

        val results =
            mutableListOf<Complaint>()

        complaintsRef
            .addListenerForSingleValueEvent(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        for (
                        item in snapshot.children
                        ) {

                            val complaint =
                                item.getValue(
                                    Complaint::class.java
                                ) ?: continue

                            if (
                                complaint.userId
                                    .contains(
                                        keyword,
                                        true
                                    )
                            ) {

                                results.add(
                                    complaint
                                )

                            }

                        }

                        searchResolvedUserId(
                            keyword,
                            results,
                            callback
                        )

                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }

                }

            )

    }

    private fun searchResolvedUserId(
        keyword: String,
        results: MutableList<Complaint>,
        callback: (MutableList<Complaint>) -> Unit
    ) {

        resolvedRef
            .addListenerForSingleValueEvent(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        for (
                        item in snapshot.children
                        ) {

                            val complaint =
                                item.getValue(
                                    Complaint::class.java
                                ) ?: continue

                            if (
                                complaint.userId
                                    .contains(
                                        keyword,
                                        true
                                    )
                            ) {

                                results.add(
                                    complaint
                                )

                            }

                        }

                        callback(results)

                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }

                }

            )

    }

    fun searchByPhone(
        keyword: String,
        callback: (MutableList<Complaint>) -> Unit
    ) {

        val results =
            mutableListOf<Complaint>()

        complaintsRef
            .addListenerForSingleValueEvent(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        for (
                        item in snapshot.children
                        ) {

                            val complaint =
                                item.getValue(
                                    Complaint::class.java
                                ) ?: continue

                            if (
                                complaint.phoneNumber
                                    .contains(
                                        keyword
                                    )
                            ) {

                                results.add(
                                    complaint
                                )

                            }

                        }

                        callback(results)

                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }

                }

            )

    }

    fun searchByArea(
        keyword: String,
        callback: (MutableList<Complaint>) -> Unit
    ) {

        val results =
            mutableListOf<Complaint>()

        complaintsRef
            .addListenerForSingleValueEvent(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        for (
                        item in snapshot.children
                        ) {

                            val complaint =
                                item.getValue(
                                    Complaint::class.java
                                ) ?: continue

                            if (
                                complaint.address
                                    .contains(
                                        keyword,
                                        true
                                    )
                            ) {

                                results.add(
                                    complaint
                                )

                            }

                        }

                        callback(results)

                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }

                }

            )

    }

}
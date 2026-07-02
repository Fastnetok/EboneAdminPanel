package com.example.eboneadminpanel

class EmployeeReportManager {

    fun getRepeatComplaints(

        complaints: List<Complaint>,

        employeeName: String

    ): Int {

        val userIds =
            mutableListOf<String>()

        var repeatCount = 0

        for (
        complaint in complaints
        ) {

            if (
                complaint.assignedTo ==
                employeeName
            ) {

                if (
                    userIds.contains(
                        complaint.userId
                    )
                ) {

                    repeatCount++

                } else {

                    userIds.add(
                        complaint.userId
                    )

                }
            }
        }

        return repeatCount
    }

    fun getTotalComplaints(

        complaints: List<Complaint>,

        employeeName: String

    ): Int {

        return complaints.count {

            it.assignedTo ==
                    employeeName

        }

    }

    fun getResolvedComplaints(

        complaints: List<Complaint>,

        employeeName: String

    ): Int {

        return complaints.count {

            it.assignedTo ==
                    employeeName

                    &&

                    it.status ==
                    "Resolved"

        }

    }

    fun getPendingComplaints(

        complaints: List<Complaint>,

        employeeName: String

    ): Int {

        return complaints.count {

            it.assignedTo ==
                    employeeName

                    &&

                    it.status ==
                    "Pending"

        }

    }

}
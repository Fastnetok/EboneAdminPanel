package com.example.eboneadminpanel

import java.util.Calendar

class AdminFilterManager {

    fun filterByStatus(
        complaints: List<Complaint>,
        status: String
    ): List<Complaint> {

        return complaints.filter {
            it.status.equals(status, true)
        }

    }

    fun filterByEmployee(
        complaints: List<Complaint>,
        employeeName: String
    ): List<Complaint> {

        return complaints.filter {
            it.assignedTo.equals(
                employeeName,
                true
            )
        }

    }

    fun filterByArea(
        complaints: List<Complaint>,
        areaName: String
    ): List<Complaint> {

        return complaints.filter {

            it.address.contains(
                areaName,
                true
            )

        }

    }

    fun filterByMonth(
        complaints: List<Complaint>,
        month: Int,
        year: Int
    ): List<Complaint> {

        return complaints.filter {

            val calendar =
                Calendar.getInstance()

            calendar.timeInMillis =
                it.createdTime

            calendar.get(
                Calendar.MONTH
            ) == month
                    &&
                    calendar.get(
                        Calendar.YEAR
                    ) == year

        }

    }

    fun filterByCustomDate(
        complaints: List<Complaint>,
        fromDate: Long,
        toDate: Long
    ): List<Complaint> {

        return complaints.filter {

            it.createdTime in
                    fromDate..toDate

        }

    }

    fun filterByToday(
        complaints: List<Complaint>
    ): List<Complaint> {

        val calendar =
            Calendar.getInstance()

        calendar.set(
            Calendar.HOUR_OF_DAY,
            0
        )

        calendar.set(
            Calendar.MINUTE,
            0
        )

        calendar.set(
            Calendar.SECOND,
            0
        )

        calendar.set(
            Calendar.MILLISECOND,
            0
        )

        val todayStart =
            calendar.timeInMillis

        return complaints.filter {

            it.createdTime >=
                    todayStart

        }

    }

}
fun filterByWeek(
    complaints: List<Complaint>
): List<Complaint> {

    val weekStart =
        System.currentTimeMillis() -
                (
                        7L *
                                24 *
                                60 *
                                60 *
                                1000
                        )

    return complaints.filter {

        it.createdTime >=
                weekStart

    }

}
fun filterByYesterday(
    complaints: List<Complaint>
): List<Complaint> {

    val calendar =
        Calendar.getInstance()

    calendar.set(
        Calendar.HOUR_OF_DAY,
        0
    )

    calendar.set(
        Calendar.MINUTE,
        0
    )

    calendar.set(
        Calendar.SECOND,
        0
    )

    calendar.set(
        Calendar.MILLISECOND,
        0
    )

    val todayStart =
        calendar.timeInMillis

    val yesterdayStart =
        todayStart -
                (
                        24L *
                                60 *
                                60 *
                                1000
                        )

    return complaints.filter {

        it.createdTime in
                yesterdayStart until
                todayStart

    }

}
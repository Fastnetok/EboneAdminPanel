package com.example.eboneadminpanel

import java.util.Calendar

object TodayFilter {

    fun getTodayStart(): Long {

        return Calendar
            .getInstance()
            .apply {

                set(
                    Calendar.HOUR_OF_DAY,
                    0
                )

                set(
                    Calendar.MINUTE,
                    0
                )

                set(
                    Calendar.SECOND,
                    0
                )

                set(
                    Calendar.MILLISECOND,
                    0
                )

            }
            .timeInMillis
    }

}
package com.example.eboneadminpanel

data class ReportSummary(

    val total: Int = 0,

    val pending: Int = 0,

    val progress: Int = 0,

    val resolved: Int = 0,

    val repeat: Int = 0

)

class ReportFilterManager {

    fun getTodaySummary(
        total: Int,
        pending: Int,
        progress: Int,
        resolved: Int,
        repeat: Int
    ): ReportSummary {

        return ReportSummary(
            total = total,
            pending = pending,
            progress = progress,
            resolved = resolved,
            repeat = repeat
        )
    }

    fun getWeekSummary(
        total: Int,
        pending: Int,
        progress: Int,
        resolved: Int,
        repeat: Int
    ): ReportSummary {

        return ReportSummary(
            total = total,
            pending = pending,
            progress = progress,
            resolved = resolved,
            repeat = repeat
        )
    }

    fun getMonthSummary(
        total: Int,
        pending: Int,
        progress: Int,
        resolved: Int,
        repeat: Int
    ): ReportSummary {

        return ReportSummary(
            total = total,
            pending = pending,
            progress = progress,
            resolved = resolved,
            repeat = repeat
        )
    }
}
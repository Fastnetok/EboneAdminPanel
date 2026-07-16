package com.example.eboneadminpanel

data class ReportItem(
    val employeeName: String = "",
    val assigned: Int = 0,
    val pending: Int = 0,
    val progress: Int = 0,
    val resolved: Int = 0,
    val successRate: Int = 0,
    val repeatComplaints: Int = 0,
    val todayCount: Int = 0,
    val weekCount: Int = 0,
    val monthCount: Int = 0,
    val averageTime: String = "0 Min",
    val weekAssigned: Int = 0,
    val weekResolved: Int = 0,
    val weekPending: Int = 0,
    val weekRepeat: Int = 0,
    val monthAssigned: Int = 0,
    val monthResolved: Int = 0,
    val monthPending: Int = 0,
    val monthRepeat: Int = 0,
    val monthSuccessRate: Int = 0
)
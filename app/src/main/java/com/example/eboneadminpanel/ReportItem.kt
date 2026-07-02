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

    val averageTime: String = "0 Min"

)
package com.example.eboneadminpanel

data class PdfReportItem(

    val employeeName: String = "",

    val assigned: Int = 0,

    val pending: Int = 0,

    val progress: Int = 0,

    val resolved: Int = 0,

    val successRate: Int = 0,

    val repeatComplaints: Int = 0

)
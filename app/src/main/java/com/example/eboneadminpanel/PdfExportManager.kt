package com.example.eboneadminpanel

import android.content.Context
import android.widget.Toast

class PdfExportManager {

    fun exportEmployeeReport(
        context: Context
    ) {

        Toast.makeText(
            context,
            "PDF Export Started",
            Toast.LENGTH_LONG
        ).show()

    }

}
package com.example.eboneadminpanel

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ExportActivity : AppCompatActivity() {

    private lateinit var excelExportButton:
            TextView

    private lateinit var pdfExportButton:
            TextView

    private lateinit var pdfExportManager:
            PdfExportManager

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_export
        )

        pdfExportManager =
            PdfExportManager()

        excelExportButton =
            findViewById(
                R.id.excelExportButton
            )

        pdfExportButton =
            findViewById(
                R.id.pdfExportButton
            )

        pdfExportButton.setOnClickListener {

            pdfExportManager
                .exportEmployeeReport(
                    this
                )

        }

        excelExportButton.setOnClickListener {

            android.widget.Toast.makeText(
                this,
                "Excel Export Coming Soon",
                android.widget.Toast.LENGTH_SHORT
            ).show()

        }

    }

}
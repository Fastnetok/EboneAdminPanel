package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ManagementToolsActivity :
    AppCompatActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_management_tools
        )

        val filterButton =
            findViewById<TextView>(
                R.id.filterButton
            )

        val searchButton =
            findViewById<TextView>(
                R.id.searchButton
            )

        val assignButton =
            findViewById<TextView>(
                R.id.assignButton
            )

        val importButton =
            findViewById<TextView>(
                R.id.importButton
            )

        val exportButton =
            findViewById<TextView>(
                R.id.exportButton
            )

        val repeatButton =
            findViewById<TextView>(
                R.id.repeatButton
            )

        val analyticsButton =
            findViewById<TextView>(
                R.id.analyticsButton
            )

        val homeButton =
            findViewById<TextView>(
                R.id.homeButton
            )

        val voiceButton =
            findViewById<TextView>(
                R.id.voiceButton
            )

        filterButton.setOnClickListener {

            startActivity(

                Intent(
                    this,
                    FilterActivity::class.java
                )

            )

        }

        searchButton.setOnClickListener {

            startActivity(

                Intent(
                    this,
                    SearchActivity::class.java
                )

            )

        }

        assignButton.setOnClickListener {

            startActivity(

                Intent(
                    this,
                    AssignComplaintsActivity::class.java
                )

            )

        }

        importButton.setOnClickListener {

            Toast.makeText(
                this,
                "Import",
                Toast.LENGTH_SHORT
            ).show()

        }

        exportButton.setOnClickListener {

            startActivity(

                Intent(
                    this,
                    ExportActivity::class.java
                )

            )

        }

        repeatButton.setOnClickListener {

            Toast.makeText(
                this,
                "Repeat Complaints",
                Toast.LENGTH_SHORT
            ).show()

        }

        analyticsButton.setOnClickListener {

            startActivity(

                Intent(
                    this,
                    ReportsActivity::class.java
                )

            )

        }

        voiceButton.setOnClickListener {

            Toast.makeText(
                this,
                "Voice Search",
                Toast.LENGTH_SHORT
            ).show()

        }

        homeButton.setOnClickListener {

            finish()

        }

    }

}
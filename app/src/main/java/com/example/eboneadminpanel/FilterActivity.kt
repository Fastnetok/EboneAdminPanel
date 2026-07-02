package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class FilterActivity : AppCompatActivity() {

    private lateinit var pendingButton: Button
    private lateinit var progressButton: Button
    private lateinit var resolvedButton: Button
    private lateinit var todayButton: Button
    private lateinit var weekButton: Button
    private lateinit var monthButton: Button

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_filter
        )

        pendingButton =
            findViewById(
                R.id.pendingButton
            )

        progressButton =
            findViewById(
                R.id.progressButton
            )

        resolvedButton =
            findViewById(
                R.id.resolvedButton
            )

        todayButton =
            findViewById(
                R.id.todayButton
            )

        weekButton =
            findViewById(
                R.id.weekButton
            )

        monthButton =
            findViewById(
                R.id.monthButton
            )

        pendingButton.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    PendingComplaintsActivity::class.java
                )
            )

        }

        progressButton.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    ProgressActivity::class.java
                )
            )

        }

        resolvedButton.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    ResolvedSummaryActivity::class.java
                )
            )

        }

        todayButton.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    PendingComplaintsActivity::class.java
                ).putExtra(
                    "filterType",
                    "Today"
                )
            )

        }

        weekButton.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    PendingComplaintsActivity::class.java
                ).putExtra(
                    "filterType",
                    "Week"
                )
            )

        }

        monthButton.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    PendingComplaintsActivity::class.java
                ).putExtra(
                    "filterType",
                    "Month"
                )
            )

        }

    }

}
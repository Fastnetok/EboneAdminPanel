package com.example.eboneadminpanel

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class SearchActivity : AppCompatActivity() {

    private lateinit var searchBox: EditText
    private lateinit var searchButton: Button
    private lateinit var voiceButton: Button

    private lateinit var recyclerView:
            RecyclerView

    private lateinit var adapter:
            ComplaintAdapter

    private lateinit var searchManager:
            ComplaintSearchManager

    private val complaintList =
        mutableListOf<Complaint>()

    private val VOICE_REQUEST_CODE =
        100

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_search
        )

        searchManager =
            ComplaintSearchManager()

        searchBox =
            findViewById(
                R.id.searchBox
            )

        searchButton =
            findViewById(
                R.id.searchButton
            )

        voiceButton =
            findViewById(
                R.id.voiceButton
            )

        recyclerView =
            findViewById(
                R.id.searchRecyclerView
            )

        recyclerView.layoutManager =
            LinearLayoutManager(this)

        adapter =
            ComplaintAdapter(
                complaintList
            )

        recyclerView.adapter =
            adapter

        searchButton.setOnClickListener {

            val keyword =
                searchBox.text
                    .toString()
                    .trim()

            if (
                keyword.isEmpty()
            ) {

                Toast.makeText(
                    this,
                    "Enter Search Value",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener

            }

            performSearch(
                keyword
            )

        }

        voiceButton.setOnClickListener {

            val intent =
                Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                )

            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault()
            )

            intent.putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Speak Now"
            )

            startActivityForResult(
                intent,
                VOICE_REQUEST_CODE
            )

        }

    }

    private fun performSearch(
        keyword: String
    ) {

        complaintList.clear()

        searchManager.searchByUserId(
            keyword
        ) { userResults ->

            if (
                userResults.isNotEmpty()
            ) {

                complaintList.addAll(
                    userResults
                )

                adapter.notifyDataSetChanged()

                return@searchByUserId

            }

            searchManager.searchByPhone(
                keyword
            ) { phoneResults ->

                if (
                    phoneResults.isNotEmpty()
                ) {

                    complaintList.addAll(
                        phoneResults
                    )

                    adapter.notifyDataSetChanged()

                    return@searchByPhone

                }

                searchManager.searchByArea(
                    keyword
                ) { areaResults ->

                    if (
                        areaResults.isNotEmpty()
                    ) {

                        complaintList.addAll(
                            areaResults
                        )

                        adapter.notifyDataSetChanged()

                    } else {

                        Toast.makeText(
                            this,
                            "No Record Found",
                            Toast.LENGTH_LONG
                        ).show()

                    }

                }

            }

        }

    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode ==
            VOICE_REQUEST_CODE
            &&
            resultCode ==
            Activity.RESULT_OK
        ) {

            val result =
                data?.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS
                )

            if (
                !result.isNullOrEmpty()
            ) {

                val voiceText =
                    result[0]

                searchBox.setText(
                    voiceText
                )

                performSearch(
                    voiceText
                )

            }

        }

    }

}
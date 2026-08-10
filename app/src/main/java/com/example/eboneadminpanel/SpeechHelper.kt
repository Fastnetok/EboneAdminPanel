package com.example.eboneadminpanel

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

object SpeechHelper {

    const val SPEECH_REQUEST_CODE = 9001

    fun isSpeechAvailable(activity: Activity): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(activity)
    }

    fun startSpeechInput(activity: Activity) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolein... (e.g. Abbas, Naeem)")
        }
        activity.startActivityForResult(intent, SPEECH_REQUEST_CODE)
    }

    fun getResultFromIntent(data: Intent?): String {
        val results = data?.getStringArrayListExtra(
            RecognizerIntent.EXTRA_RESULTS
        )
        return results?.get(0) ?: ""
    }
}
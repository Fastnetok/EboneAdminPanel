package com.example.eboneadminpanel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest

class SetPinActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "admin_lock_prefs"
        private const val KEY_PIN_HASH = "pin_hash"

        fun isPinSet(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            return prefs.contains(KEY_PIN_HASH)
        }

        fun checkPin(context: Context, enteredPin: String): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val savedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
            return savedHash == hash(enteredPin)
        }

        fun clearPin(context: Context) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_PIN_HASH)
                .apply()
        }

        private fun hash(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private lateinit var pinInput: EditText
    private lateinit var pinConfirmInput: EditText
    private lateinit var saveButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_set_pin)

        pinInput = findViewById(R.id.pinInput)
        pinConfirmInput = findViewById(R.id.pinConfirmInput)
        saveButton = findViewById(R.id.savePinButton)
        statusText = findViewById(R.id.pinStatusText)

        saveButton.setOnClickListener {
            val pin = pinInput.text.toString().trim()
            val confirm = pinConfirmInput.text.toString().trim()

            if (pin.length < 4 || pin.length > 6) {
                statusText.text = "PIN 4 se 6 digit ka hona chahiye"
                return@setOnClickListener
            }

            if (pin != confirm) {
                statusText.text = "Dono PIN match nahi kar rahe"
                return@setOnClickListener
            }

            val hashed = hash(pin)
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_PIN_HASH, hashed)
                .apply()

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
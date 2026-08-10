package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class LoginActivity : AppCompatActivity() {

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var statusText: TextView

    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginButton = findViewById(R.id.loginButton)
        statusText = findViewById(R.id.statusText)

        loginButton.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val password = passwordInput.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                statusText.text = "Email aur password dono likhein"
                return@setOnClickListener
            }

            loginButton.isEnabled = false
            statusText.text = "Signing in..."

            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                    checkAdminAndProceed(result.user?.uid)
                }
                .addOnFailureListener { error ->
                    loginButton.isEnabled = true
                    statusText.text = "Login fail: ${error.message}"
                }
        }

        // NEW: Forgot Password link
        val forgotPasswordText = findViewById<TextView>(R.id.forgotPasswordText)
        forgotPasswordText.setOnClickListener {
            showForgotPasswordDialog()
        }
    }

    private fun showForgotPasswordDialog() {
        val input = EditText(this)
        input.hint = "Apna email likhein"

        AlertDialog.Builder(this)
            .setTitle("Password Reset")
            .setMessage("Email daalein, hum aapko reset link bhej denge")
            .setView(input)
            .setPositiveButton("Bhejein") { _, _ ->
                val email = input.text.toString().trim()
                if (email.isEmpty()) {
                    Toast.makeText(this, "Email likhein", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                auth.sendPasswordResetEmail(email)
                    .addOnSuccessListener {
                        Toast.makeText(
                            this,
                            "Reset link $email par bhej diya gaya",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(
                            this,
                            "Nahi bhej saka: ${it.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAdminAndProceed(uid: String?) {
        if (uid == null) {
            statusText.text = "Login failed, try again"
            loginButton.isEnabled = true
            return
        }

        FirebaseDatabase.getInstance()
            .getReference("admins")
            .child(uid)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    // First time after a fresh login: make sure a PIN exists
                    // before entering the dashboard, so future app opens can
                    // be gated by the Unlock screen.
                    if (SetPinActivity.isPinSet(this)) {
                        startActivity(Intent(this, MainActivity::class.java))
                    } else {
                        startActivity(Intent(this, SetPinActivity::class.java))
                    }
                    finish()
                } else {
                    statusText.text = "Ye account admin ke taur par register nahi hai"
                    auth.signOut()
                    loginButton.isEnabled = true
                }
            }
            .addOnFailureListener {
                statusText.text = "Admin check fail hui, dobara koshish karein"
                auth.signOut()
                loginButton.isEnabled = true
            }
    }

    override fun onStart() {
        super.onStart()
        // Already signed in from a previous session?
        val uid = auth.currentUser?.uid
        if (uid != null && SetPinActivity.isPinSet(this)) {
            // Don't re-verify admin status here every time — that already
            // happened once. Just gate re-entry with PIN/biometric.
            startActivity(Intent(this, UnlockActivity::class.java))
            finish()
        } else if (uid != null) {
            checkAdminAndProceed(uid)
        }
    }
}
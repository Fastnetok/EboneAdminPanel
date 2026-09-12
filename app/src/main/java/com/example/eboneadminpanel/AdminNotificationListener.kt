package com.example.eboneadminpanel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase

object AdminNotificationListener {

    private var isListening = false

    fun startListening(context: Context) {
        if (isListening) return
        isListening = true
        createNotificationChannel(context)

        // FIX: old notifications used to replay on every fresh app
        // process (update, reinstall, device reboot) because
        // onChildAdded fires once for EVERY existing child the first
        // time this listener attaches, not just genuinely new ones.
        // The existing "seen" flag was meant to guard against this, but
        // it depends on a write (snapshot.ref.child("seen").setValue)
        // succeeding every single time — if that write ever failed
        // (e.g. brief network drop) that entry stays "unseen" forever
        // and gets replayed on the next app start. Remembering the
        // exact moment this listener starts, and only ever notifying
        // for entries timestamped at or after that moment, means old
        // backlog can no longer resurface as new notifications even if
        // the "seen" write failed for some of them.
        val listenerStartTime = System.currentTimeMillis()

        FirebaseDatabase
            .getInstance()
            .getReference("adminNotifications")
            .addChildEventListener(
                object : ChildEventListener {
                    override fun onChildAdded(
                        snapshot: DataSnapshot,
                        previousChildName: String?
                    ) {
                        val timestamp = snapshot
                            .child("timestamp")
                            .getValue(Long::class.java) ?: 0L

                        if (timestamp < listenerStartTime) {
                            // Old notification from before this app
                            // session started — mark it seen (so it
                            // stays consistent for anything else that
                            // reads this flag) but do not show it again.
                            snapshot.ref.child("seen").setValue(true)
                            return
                        }

                        val seen = snapshot
                            .child("seen")
                            .getValue(Boolean::class.java) ?: false
                        if (!seen) {
                            val message = snapshot
                                .child("message")
                                .getValue(String::class.java)
                                ?: "Complaint resolved"
                            showNotification(context, message)
                            snapshot.ref.child("seen").setValue(true)
                        }
                    }
                    override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onChildRemoved(snapshot: DataSnapshot) {}
                    override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                    override fun onCancelled(error: DatabaseError) {}
                }
            )
    }

    private fun showNotification(context: Context, message: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notification = NotificationCompat.Builder(context, "admin_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("✅ Complaint Resolved")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSound(sound)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "admin_channel",
                "Admin Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Complaint resolved alerts"
                enableVibration(true)
            }
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
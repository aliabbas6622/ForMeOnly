package com.example.wallpepr

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AlarmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        
        setContentView(R.layout.activity_alarm_ring)

        val alarmId = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID)
        val alarm = alarmId?.let { id ->
            Prefs.alarms(this).find { it.id == id }
        }

        val clockText = findViewById<TextView>(R.id.ringClockText)
        val messageText = findViewById<TextView>(R.id.ringMessageText)
        val dismissButton = findViewById<Button>(R.id.dismissButton)

        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        clockText.text = timeFormat.format(Calendar.getInstance().time)

        messageText.text = alarm?.cleanText?.ifBlank { "Good morning!" } ?: "Good morning!"

        dismissButton.setOnClickListener {
            stopService(Intent(this, AlarmService::class.java))
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Prevent back press from closing the alarm screen without dismissing
    }
}

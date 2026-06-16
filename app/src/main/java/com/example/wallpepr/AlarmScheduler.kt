package com.example.wallpepr

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    
    @SuppressLint("MissingPermission")
    fun schedule(context: Context, alarm: AlarmItem) {
        if (!alarm.isEnabled) {
            cancel(context, alarm)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM_TRIGGER
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarm.id)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val triggerTime = calendar.timeInMillis
        Log.i(TAG, "Scheduling alarm ${alarm.id} at $triggerTime (hour=${alarm.hour}, min=${alarm.minute})")

        val showIntent = Intent(context, MainActivity::class.java)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            alarm.id.hashCode() + 1,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val clockInfo = AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent)
        alarmManager.setAlarmClock(clockInfo, pendingIntent)
    }

    fun cancel(context: Context, alarm: AlarmItem) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM_TRIGGER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            Log.i(TAG, "Cancelling alarm ${alarm.id}")
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}

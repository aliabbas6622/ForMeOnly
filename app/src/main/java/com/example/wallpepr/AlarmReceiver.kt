package com.example.wallpepr

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.util.Calendar

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "onReceive action: $action")

        when (action) {
            ACTION_ALARM_TRIGGER -> {
                val alarmId = intent.getStringExtra(EXTRA_ALARM_ID) ?: return

                // Vibrate the device — uses AlarmManager-safe pattern
                vibrate(context)

                val serviceIntent = Intent(context, AlarmService::class.java).apply {
                    putExtra(EXTRA_ALARM_ID, alarmId)
                }
                context.startForegroundService(serviceIntent)
            }

            ACTION_ALARM_DISMISS -> {
                val serviceIntent = Intent(context, AlarmService::class.java)
                context.stopService(serviceIntent)
            }

            // Re-register all enabled alarms after the device boots (AlarmManager clears alarms on reboot)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Re-registering alarms after boot/update")
                reRegisterAllAlarms(context)
            }
        }
    }

    private fun vibrate(context: Context) {
        try {
            val attrContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.createAttributionContext("alarm_service")
            } else {
                context
            }

            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = attrContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                attrContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            // Short-long-short pattern consistent with standard alarm feel
            val pattern = longArrayOf(0, 400, 200, 400, 200, 800)
            vibrator.vibrate(
                VibrationEffect.createWaveform(pattern, 0) // repeat from index 0
            )
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_ALARM_TRIGGER = "com.example.wallpepr.ACTION_ALARM_TRIGGER"
        const val ACTION_ALARM_DISMISS  = "com.example.wallpepr.ACTION_ALARM_DISMISS"
        const val EXTRA_ALARM_ID        = "alarm_id"

        /**
         * Re-schedules every enabled alarm using AlarmManager.setAlarmClock so that
         * Doze mode and battery optimisation cannot defer them.
         */
        @SuppressLint("MissingPermission")
        fun reRegisterAllAlarms(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val alarms = Prefs.alarms(context)

            alarms.filter { it.isEnabled }.forEach { alarm ->
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, alarm.hour)
                    set(Calendar.MINUTE, alarm.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }

                val triggerIntent = Intent(context, AlarmReceiver::class.java).apply {
                    action = ACTION_ALARM_TRIGGER
                    putExtra(EXTRA_ALARM_ID, alarm.id)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    alarm.id.hashCode(),
                    triggerIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val showIntent = PendingIntent.getActivity(
                    context,
                    alarm.id.hashCode() + 1,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )

                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(cal.timeInMillis, showIntent),
                    pendingIntent
                )
                Log.i(TAG, "Re-registered alarm '${alarm.id}' for ${alarm.hour}:${alarm.minute}")
            }
        }
    }
}

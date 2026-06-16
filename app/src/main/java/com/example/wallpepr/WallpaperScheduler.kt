package com.example.wallpepr

import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WallpaperScheduler {
    private const val PERIODIC_WORK_NAME = "wallpaper_periodic_rotation"
    private const val IMMEDIATE_WORK_NAME = "wallpaper_immediate_rotation"
    private const val MIN_WORK_INTERVAL_MS = 15 * 60_000L

    fun start(context: Context) {
        if (Prefs.intervalMs(context) >= MIN_WORK_INTERVAL_MS) {
            WallpaperRotationService.stop(context)
            Prefs.setRunning(context, true)
            enqueueWork(context)
        } else {
            cancelWork(context)
            WallpaperRotationService.start(context)
        }
    }

    fun stop(context: Context) {
        cancelWork(context)
        WallpaperRotationService.stop(context)
        Prefs.setRunning(context, false)
    }

    fun restartIfRunning(context: Context) {
        if (Prefs.isRunning(context)) start(context)
    }

    fun triggerNow(context: Context) {
        if (Prefs.intervalMs(context) >= MIN_WORK_INTERVAL_MS) {
            val workManager = WorkManager.getInstance(context)
            val immediate = OneTimeWorkRequestBuilder<WallpaperWorker>().build()
            workManager.enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, immediate)
        } else {
            // ACTION_APPLY both applies wallpaper immediately and re-arms the next scheduled rotation
            val intent = Intent(context, WallpaperRotationService::class.java).setAction(WallpaperRotationService.ACTION_APPLY)
            context.startForegroundService(intent)
        }
    }

    private fun enqueueWork(context: Context) {
        val interval = Prefs.intervalMs(context)
        val workManager = WorkManager.getInstance(context)
        val immediate = OneTimeWorkRequestBuilder<WallpaperWorker>().build()
        val periodic = PeriodicWorkRequestBuilder<WallpaperWorker>(interval, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, immediate)
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
    }

    private fun cancelWork(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }
}

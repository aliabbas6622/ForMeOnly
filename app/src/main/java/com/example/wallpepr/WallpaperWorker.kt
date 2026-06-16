package com.example.wallpepr

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class WallpaperWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val next = ImageRepository.takeNext(context) ?: return Result.success()
        var bitmap: Bitmap? = null

        return try {
            bitmap = WallpaperSizer.createWallpaperBitmap(context, next, reusableTarget = true)
                ?: return Result.failure()

            val attrContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                context.createAttributionContext("wallpaper_service")
            } else {
                context
            }
            WallpaperManager.getInstance(attrContext).setBitmap(bitmap)

            Prefs.clearFailure(context, next)
            Prefs.setLastChange(context, SystemClock.elapsedRealtime(), System.currentTimeMillis())
            Result.success()
        } catch (error: Exception) {
            Prefs.recordFailure(context, next)
            Log.e(TAG, "Unable to set wallpaper from worker: $next", error)
            Result.retry()
        } finally {
            WallpaperSizer.releaseBitmap(bitmap)
        }
    }

    private companion object {
        const val TAG = "WallpeprWorker"
    }
}

package com.example.wallpepr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

object WallpaperSizer {
    private const val PIXEL_7A_WIDTH = 1080
    private const val PIXEL_7A_HEIGHT = 2400
    private val pool = ArrayDeque<Bitmap>(2)

    data class SizeMode(val label: String, val value: String)

    val modes = listOf(
        SizeMode("Auto fit Pixel 7a", Prefs.SIZE_AUTO),
        SizeMode("Fill screen", Prefs.SIZE_FILL),
        SizeMode("Fit full image", Prefs.SIZE_FIT),
        SizeMode("Stretch", Prefs.SIZE_STRETCH)
    )

    @Synchronized
    fun releaseBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        if (bitmap.width != PIXEL_7A_WIDTH || bitmap.height != PIXEL_7A_HEIGHT || pool.size >= 2) {
            bitmap.recycle()
            return
        }
        pool.addLast(bitmap)
    }

    fun createWallpaperBitmap(context: Context, uri: Uri, reusableTarget: Boolean = false): Bitmap? {
        val sourceSize = readSourceSize(context, uri) ?: return null
        val sampleSize = calculateSampleSize(sourceSize.first, sourceSize.second)
        val source = decodeBitmap(context, uri, sampleSize) ?: return null
        return drawToPhoneSize(source, Prefs.sizeMode(context), reusableTarget).also {
            if (it !== source) source.recycle()
        }
    }

    fun createPreviewBitmap(context: Context, uri: Uri): Bitmap? {
        val wallpaper = createWallpaperBitmap(context, uri) ?: return null
        return Bitmap.createScaledBitmap(wallpaper, PREVIEW_WIDTH, PREVIEW_HEIGHT, true).also {
            wallpaper.recycle()
        }
    }

    private fun readSourceSize(context: Context, uri: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }

    private fun decodeBitmap(context: Context, uri: Uri, sampleSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun drawToPhoneSize(source: Bitmap, mode: String, reusableTarget: Boolean): Bitmap {
        val target = if (reusableTarget) {
            obtainTargetBitmap()
        } else {
            Bitmap.createBitmap(PIXEL_7A_WIDTH, PIXEL_7A_HEIGHT, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)

        val sourceRect = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        val targetRect = RectF(0f, 0f, PIXEL_7A_WIDTH.toFloat(), PIXEL_7A_HEIGHT.toFloat())
        val matrix = Matrix()
        val scaleMode = when (mode) {
            Prefs.SIZE_FIT -> Matrix.ScaleToFit.CENTER
            Prefs.SIZE_STRETCH -> Matrix.ScaleToFit.FILL
            else -> null
        }

        if (scaleMode == null) {
            val scale = max(
                PIXEL_7A_WIDTH.toFloat() / source.width.toFloat(),
                PIXEL_7A_HEIGHT.toFloat() / source.height.toFloat()
            )
            val dx = (PIXEL_7A_WIDTH - source.width * scale) / 2f
            val dy = (PIXEL_7A_HEIGHT - source.height * scale) / 2f
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
        } else {
            matrix.setRectToRect(sourceRect, targetRect, scaleMode)
        }

        canvas.drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return target
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (
            width / (sample * 2) >= PIXEL_7A_WIDTH ||
            height / (sample * 2) >= PIXEL_7A_HEIGHT
        ) {
            sample *= 2
        }
        return max(1, min(sample, 8))
    }

    @Synchronized
    private fun obtainTargetBitmap(): Bitmap =
        pool.removeFirstOrNull()?.takeUnless { it.isRecycled }
            ?: Bitmap.createBitmap(PIXEL_7A_WIDTH, PIXEL_7A_HEIGHT, Bitmap.Config.ARGB_8888)

    private const val PREVIEW_WIDTH = 324
    private const val PREVIEW_HEIGHT = 720
}

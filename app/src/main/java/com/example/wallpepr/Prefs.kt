package com.example.wallpepr

import android.content.Context
import android.net.Uri

object Prefs {
    private const val NAME = "wallpepr_settings"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val KEY_INTERVAL_MS = "interval_ms"
    private const val KEY_MODE = "mode"
    private const val KEY_RUNNING = "running"
    private const val KEY_INDEX = "sequential_index"
    private const val KEY_SIZE_MODE = "size_mode"
    private const val KEY_CACHE_TREE_URI = "cache_tree_uri"
    private const val KEY_CACHE_IMAGES = "cache_images"
    private const val KEY_LAST_CHANGE_ELAPSED = "last_change_elapsed"
    private const val KEY_LAST_CHANGE_WALL_TIME = "last_change_wall_time"
    private const val KEY_FAILED_PREFIX = "failed_"
    
    private const val KEY_ELEVENLABS_KEY = "elevenlabs_api_key"
    private const val KEY_ELEVENLABS_VOICE = "elevenlabs_voice_id"
    private const val KEY_GEMINI_KEY = "gemini_api_key"
    private const val KEY_ALARMS = "scheduled_alarms"

    const val MODE_SEQUENTIAL = "sequential"
    const val MODE_SHUFFLE = "shuffle"
    const val SIZE_AUTO = "auto"
    const val SIZE_FILL = "fill"
    const val SIZE_FIT = "fit"
    const val SIZE_STRETCH = "stretch"

    val intervals = listOf(
        IntervalOption("1 minute", 60_000L),
        IntervalOption("5 minutes", 5 * 60_000L),
        IntervalOption("15 minutes", 15 * 60_000L),
        IntervalOption("30 minutes", 30 * 60_000L),
        IntervalOption("1 hour", 60 * 60_000L),
        IntervalOption("24 hours", 24 * 60 * 60_000L)
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun folderUri(context: Context): Uri? =
        prefs(context).getString(KEY_FOLDER_URI, null)?.let(Uri::parse)

    fun setFolderUri(context: Context, uri: Uri) {
        prefs(context).edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
    }

    fun intervalMs(context: Context): Long =
        prefs(context).getLong(KEY_INTERVAL_MS, intervals[1].millis)

    fun setIntervalMs(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_INTERVAL_MS, millis).apply()
    }

    fun mode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_SEQUENTIAL) ?: MODE_SEQUENTIAL

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_MODE, mode).apply()
    }

    fun isRunning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RUNNING, false)

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_RUNNING, running).apply()
    }

    fun nextIndex(context: Context): Int =
        prefs(context).getInt(KEY_INDEX, 0)

    fun setNextIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_INDEX, index).apply()
    }

    fun sizeMode(context: Context): String =
        prefs(context).getString(KEY_SIZE_MODE, SIZE_AUTO) ?: SIZE_AUTO

    fun setSizeMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_SIZE_MODE, mode).apply()
    }

    fun cachedTreeUri(context: Context): Uri? =
        prefs(context).getString(KEY_CACHE_TREE_URI, null)?.let(Uri::parse)

    fun cachedImages(context: Context): String? =
        prefs(context).getString(KEY_CACHE_IMAGES, null)

    fun setCachedImages(context: Context, treeUri: Uri, images: String) {
        prefs(context).edit()
            .putString(KEY_CACHE_TREE_URI, treeUri.toString())
            .putString(KEY_CACHE_IMAGES, images)
            .apply()
    }

    fun clearCachedImages(context: Context) {
        prefs(context).edit()
            .remove(KEY_CACHE_TREE_URI)
            .remove(KEY_CACHE_IMAGES)
            .apply()
    }

    fun lastChangeElapsed(context: Context): Long =
        prefs(context).getLong(KEY_LAST_CHANGE_ELAPSED, 0L)

    fun setLastChange(context: Context, elapsedRealtime: Long, wallTime: Long) {
        prefs(context).edit()
            .putLong(KEY_LAST_CHANGE_ELAPSED, elapsedRealtime)
            .putLong(KEY_LAST_CHANGE_WALL_TIME, wallTime)
            .apply()
    }

    fun failureCount(context: Context, uri: Uri): Int =
        prefs(context).getInt(KEY_FAILED_PREFIX + uri.toString(), 0)

    fun recordFailure(context: Context, uri: Uri) {
        val count = failureCount(context, uri) + 1
        prefs(context).edit().putInt(KEY_FAILED_PREFIX + uri.toString(), count).apply()
    }

    fun clearFailure(context: Context, uri: Uri) {
        prefs(context).edit().remove(KEY_FAILED_PREFIX + uri.toString()).apply()
    }

    fun elevenLabsKey(context: Context): String {
        val stored = prefs(context).getString(KEY_ELEVENLABS_KEY, "") ?: ""
        return stored
    }

    fun setElevenLabsKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_ELEVENLABS_KEY, key).apply()
    }

    fun elevenLabsVoice(context: Context): String =
        prefs(context).getString(KEY_ELEVENLABS_VOICE, "pNInz6obpgqjMhklaMuC") ?: "pNInz6obpgqjMhklaMuC"

    fun setElevenLabsVoice(context: Context, voiceId: String) {
        prefs(context).edit().putString(KEY_ELEVENLABS_VOICE, voiceId).apply()
    }

    fun geminiKey(context: Context): String {
        val stored = prefs(context).getString(KEY_GEMINI_KEY, "") ?: ""
        return stored
    }

    fun setGeminiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GEMINI_KEY, key).apply()
    }

    fun alarms(context: Context): List<AlarmItem> {
        val raw = prefs(context).getString(KEY_ALARMS, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                AlarmItem(
                    id = obj.getString("id"),
                    hour = obj.getInt("hour"),
                    minute = obj.getInt("minute"),
                    rawText = if (obj.has("rawText")) obj.getString("rawText") else obj.optString("text", ""),
                    cleanText = if (obj.has("cleanText")) obj.getString("cleanText") else obj.optString("text", ""),
                    isEnabled = obj.getBoolean("isEnabled"),
                    audioPath = if (obj.has("audioPath") && !obj.isNull("audioPath")) obj.getString("audioPath") else null,
                    isElevenLabs = if (obj.has("isElevenLabs")) obj.getBoolean("isElevenLabs") else false
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAlarms(context: Context, list: List<AlarmItem>) {
        val arr = org.json.JSONArray()
        list.forEach { item ->
            val obj = org.json.JSONObject().apply {
                put("id", item.id)
                put("hour", item.hour)
                put("minute", item.minute)
                put("rawText", item.rawText)
                put("cleanText", item.cleanText)
                put("isEnabled", item.isEnabled)
                put("audioPath", item.audioPath)
                put("isElevenLabs", item.isElevenLabs)
            }
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_ALARMS, arr.toString()).apply()
    }
}

data class IntervalOption(val label: String, val millis: Long)

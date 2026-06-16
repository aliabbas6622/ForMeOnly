package com.example.wallpepr

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

object TtsGenerator {
    private const val TAG = "TtsGenerator"
    private val executor = Executors.newSingleThreadExecutor()

    fun generate(
        context: Context,
        rawText: String,
        elevenLabsApiKey: String?,
        voiceId: String?,
        geminiApiKey: String,
        onComplete: (File?, Boolean, String) -> Unit // returns: audioFile, isElevenLabs, cleanText
    ) {
        val handler = Handler(Looper.getMainLooper())
        
        executor.execute {
            // Step 1: Normalize text using Gemini
            val cleanText = if (geminiApiKey.isNotBlank()) {
                normalizeTextWithGemini(rawText, geminiApiKey)
            } else {
                rawText
            }

            // Step 2: TTS Generation
            if (!elevenLabsApiKey.isNullOrBlank()) {
                val file = generateElevenLabs(context, cleanText, elevenLabsApiKey, voiceId)
                if (file != null) {
                    handler.post { onComplete(file, true, cleanText) }
                } else {
                    Log.w(TAG, "ElevenLabs TTS failed, falling back to Android TTS")
                    handler.post {
                        generateAndroidTts(context, cleanText) { fallbackFile ->
                            onComplete(fallbackFile, false, cleanText)
                        }
                    }
                }
            } else {
                Log.i(TAG, "No ElevenLabs API Key provided, using Android TTS directly")
                handler.post {
                    generateAndroidTts(context, cleanText) { fallbackFile ->
                        onComplete(fallbackFile, false, cleanText)
                    }
                }
            }
        }
    }

    private fun normalizeTextWithGemini(text: String, apiKey: String): String {
        val urlString = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val systemInstruction = JSONObject().apply {
                put("parts", org.json.JSONArray().put(JSONObject().apply {
                    put("text", "You are a text normalizer. The user will provide a message in mixed language (Hinglish / Urdu / informal Roman text). Convert it into simple spoken Urdu/Hindi in Roman form. Do NOT translate into English. Do NOT add extra meaning or creativity. Do NOT change intent. Only fix spelling and normalize words. Output only the normalized text, nothing else.")
                }))
            }

            val contents = org.json.JSONArray().put(JSONObject().apply {
                put("parts", org.json.JSONArray().put(JSONObject().apply {
                    put("text", text)
                }))
            })

            val jsonBody = JSONObject().apply {
                put("system_instruction", systemInstruction)
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.0)
                })
            }

            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code == 200) {
                val responseStr = conn.inputStream.bufferedReader().readText()
                val responseJson = JSONObject(responseStr)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val cleaned = parts.getJSONObject(0).optString("text", text).trim()
                        Log.i(TAG, "Gemini normalized: '$text' -> '$cleaned'")
                        return cleaned
                    }
                }
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "Gemini failed with code $code: $err")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calling Gemini", e)
        }
        return text // fallback to original if it fails
    }

    private fun generateElevenLabs(
        context: Context,
        text: String,
        apiKey: String,
        voiceId: String?
    ): File? {
        val resolvedVoiceId = if (voiceId.isNullOrBlank()) "pNInz6obpgqjMhklaMuC" else voiceId
        val urlString = "https://api.elevenlabs.io/v1/text-to-speech/$resolvedVoiceId"
        
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("xi-api-key", apiKey)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("accept", "audio/mpeg")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val jsonBody = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_monolingual_v1")
                val settings = JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75)
                }
                put("voice_settings", settings)
            }

            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code == 200) {
                val dir = File(context.filesDir, "alarms")
                if (!dir.exists()) dir.mkdirs()
                val outputFile = File(dir, "tts_${System.currentTimeMillis()}.mp3")
                conn.inputStream.use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Successfully generated ElevenLabs TTS audio: ${outputFile.absolutePath}")
                return outputFile
            } else {
                val errorStream = conn.errorStream?.bufferedReader()?.readText() ?: "No error body"
                Log.e(TAG, "ElevenLabs request failed with code $code. Error: $errorStream")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating ElevenLabs TTS", e)
        }
        return null
    }

    private fun generateAndroidTts(
        context: Context,
        text: String,
        onComplete: (File?) -> Unit
    ) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val resultLocale = tts?.setLanguage(Locale("hi", "IN"))
                if (resultLocale == TextToSpeech.LANG_MISSING_DATA || resultLocale == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Hindi (hi-IN) not supported, falling back to default TTS locale")
                }

                val dir = File(context.filesDir, "alarms")
                if (!dir.exists()) dir.mkdirs()
                val outputFile = File(dir, "tts_${System.currentTimeMillis()}.wav")

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        Log.i(TAG, "Successfully generated Android TTS audio: ${outputFile.absolutePath}")
                        tts?.shutdown()
                        Handler(Looper.getMainLooper()).post { onComplete(outputFile) }
                    }

                    @Suppress("DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "Android TTS synthesis error")
                        tts?.shutdown()
                        Handler(Looper.getMainLooper()).post { onComplete(null) }
                    }
                })

                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "alarm_utterance")
                }
                val result = tts?.synthesizeToFile(text, params, outputFile, "alarm_utterance")
                if (result != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "synthesizeToFile failed to start")
                    tts?.shutdown()
                    onComplete(null)
                }
            } else {
                Log.e(TAG, "Android TTS initialization failed")
                onComplete(null)
            }
        }
    }
}

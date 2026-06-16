package com.example.wallpepr

data class AlarmItem(
    val id: String,
    val hour: Int,
    val minute: Int,
    val rawText: String,
    val cleanText: String,
    val isEnabled: Boolean,
    val audioPath: String?,
    val isElevenLabs: Boolean = false
)

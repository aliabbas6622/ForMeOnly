package com.example.wallpepr

import android.annotation.SuppressLint
import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.Calendar
import java.util.Locale

class AlarmTool : Tool {
    override val id: String = "alarm"
    override val nameResId: Int = R.string.tool_alarm
    override val iconResId: Int = android.R.drawable.ic_lock_idle_alarm

    private var activity: Activity? = null
    private var rootLayout: View? = null

    private lateinit var alarmTimeText: TextView
    private lateinit var alarmTextEdit: EditText
    private lateinit var elevenLabsKeyEdit: EditText
    private lateinit var voiceSpinner: Spinner
    private lateinit var customVoiceIdInputLayout: TextInputLayout
    private lateinit var customVoiceIdEdit: EditText
    private lateinit var setAlarmButton: Button
    private lateinit var testAlarmButton: Button
    private lateinit var cancelEditButton: Button
    private lateinit var alarmsListContainer: LinearLayout
    private lateinit var progressContainer: LinearLayout

    private var targetHour = 7
    private var targetMinute = 0
    private var testMediaPlayer: MediaPlayer? = null
    private var editingAlarmId: String? = null

    private val voices = listOf(
        VoiceOption("Adam (Male)", "pNInz6obpgqjMhklaMuC"),
        VoiceOption("Rachel (Female)", "21m00Tcm4TlvDq8ikWAM"),
        VoiceOption("Domi (Female)", "AZnzlk1XvdvUeBnXmlld"),
        VoiceOption("Antoni (Male)", "ErXwobaYiN019PkySvjV"),
        VoiceOption("Custom Voice ID...", "custom")
    )

    data class VoiceOption(val name: String, val id: String)

    override fun createView(activity: Activity, parent: ViewGroup): View {
        this.activity = activity
        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.tool_alarm, parent, false)
        rootLayout = view

        alarmTimeText = view.findViewById(R.id.alarmTimeText)
        alarmTextEdit = view.findViewById(R.id.alarmTextEdit)
        elevenLabsKeyEdit = view.findViewById(R.id.elevenLabsKeyEdit)
        voiceSpinner = view.findViewById(R.id.voiceSpinner)
        customVoiceIdInputLayout = view.findViewById(R.id.customVoiceIdInputLayout)
        customVoiceIdEdit = view.findViewById(R.id.customVoiceIdEdit)
        setAlarmButton = view.findViewById(R.id.setAlarmButton)
        testAlarmButton = view.findViewById(R.id.testAlarmButton)
        cancelEditButton = view.findViewById(R.id.cancelEditButton)
        alarmsListContainer = view.findViewById(R.id.alarmsListContainer)
        progressContainer = view.findViewById(R.id.progressContainer)

        // Load masked settings
        elevenLabsKeyEdit.setText("••••••••••••••••••••••••••••••••")
        elevenLabsKeyEdit.isFocusable = false
        elevenLabsKeyEdit.isFocusableInTouchMode = false
        
        var keyUnlocked = false
        elevenLabsKeyEdit.setOnClickListener {
            val act = activity ?: return@setOnClickListener
            if (keyUnlocked) return@setOnClickListener
            
            val cancellationSignal = android.os.CancellationSignal()
            val executor = act.mainExecutor
            val builder = android.hardware.biometrics.BiometricPrompt.Builder(act)
                .setTitle("Unlock ElevenLabs Key")
                .setDescription("Authenticate using your screen lock to view and edit the API key")
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            
            val biometricPrompt = builder.build()
            biometricPrompt.authenticate(
                cancellationSignal,
                executor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                        act.runOnUiThread {
                            keyUnlocked = true
                            elevenLabsKeyEdit.setText(Prefs.elevenLabsKey(act))
                            elevenLabsKeyEdit.isFocusable = true
                            elevenLabsKeyEdit.isFocusableInTouchMode = true
                            elevenLabsKeyEdit.requestFocus()
                            
                            val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.showSoftInput(elevenLabsKeyEdit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                    
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        act.runOnUiThread {
                            Toast.makeText(act, "Authentication failed: $errString", Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                    override fun onAuthenticationFailed() {
                        act.runOnUiThread {
                            Toast.makeText(act, "Authentication failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }

        alarmTextEdit.setText("Good morning! Wake up and shine, the world is waiting for you!")

        setupTimePicker(activity)
        setupVoiceSpinner(activity)

        setAlarmButton.setOnClickListener { handleSetAlarm() }
        testAlarmButton.setOnClickListener { handleTestAlarm() }
        cancelEditButton.setOnClickListener { cancelEdit() }

        refreshAlarmsList()

        return view
    }

    override fun onResume() {
        refreshAlarmsList()
    }

    override fun onDestroy() {
        testMediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        testMediaPlayer = null
        activity = null
        rootLayout = null
    }

    private fun cancelEdit() {
        editingAlarmId = null
        setAlarmButton.text = activity?.getString(R.string.set_alarm) ?: "Set Alarm"
        cancelEditButton.visibility = View.GONE
        alarmTextEdit.setText("")
    }

    private fun setupTimePicker(act: Activity) {
        val timeCard = rootLayout?.findViewById<MaterialCardView>(R.id.selectTimeCard)
        val timeBtn = rootLayout?.findViewById<Button>(R.id.selectTimeButton)

        val calendar = Calendar.getInstance()
        targetHour = calendar.get(Calendar.HOUR_OF_DAY)
        targetMinute = calendar.get(Calendar.MINUTE)
        updateTimeText()

        val timeSetListener = TimePickerDialog.OnTimeSetListener { _, hour, minute ->
            targetHour = hour
            targetMinute = minute
            updateTimeText()
        }

        val clickListener = View.OnClickListener {
            TimePickerDialog(act, timeSetListener, targetHour, targetMinute, false).show()
        }

        timeCard?.setOnClickListener(clickListener)
        timeBtn?.setOnClickListener(clickListener)
    }

    private fun setupVoiceSpinner(act: Activity) {
        voiceSpinner.adapter = ArrayAdapter(
            act,
            android.R.layout.simple_spinner_dropdown_item,
            voices.map { it.name }
        )

        // Find and select stored voice option
        val storedVoiceId = Prefs.elevenLabsVoice(act)
        val selectedIndex = voices.indexOfFirst { it.id == storedVoiceId }.takeIf { it >= 0 }
            ?: voices.indexOfFirst { it.id == "custom" }.takeIf { it >= 0 } ?: 0
        voiceSpinner.setSelection(selectedIndex)
        
        if (voices[selectedIndex].id == "custom") {
            customVoiceIdInputLayout.visibility = View.VISIBLE
            customVoiceIdEdit.setText(storedVoiceId)
        }

        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (voices[position].id == "custom") {
                    customVoiceIdInputLayout.visibility = View.VISIBLE
                } else {
                    customVoiceIdInputLayout.visibility = View.GONE
                    Prefs.setElevenLabsVoice(act, voices[position].id)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun updateTimeText() {
        val amPm = if (targetHour < 12) "AM" else "PM"
        val displayHour = when {
            targetHour == 0 -> 12
            targetHour > 12 -> targetHour - 12
            else -> targetHour
        }
        alarmTimeText.text = String.format(Locale.getDefault(), "%02d:%02d %s", displayHour, targetMinute, amPm)
    }

    private fun handleSetAlarm() {
        val act = activity ?: return
        val text = alarmTextEdit.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(act, "Please enter alarm message text", Toast.LENGTH_SHORT).show()
            return
        }

        val inputKey = elevenLabsKeyEdit.text.toString().trim()
        val apiKey = if (inputKey == "••••••••••••••••••••••••••••••••") {
            Prefs.elevenLabsKey(act)
        } else {
            Prefs.setElevenLabsKey(act, inputKey)
            inputKey
        }

        val voiceId = if (customVoiceIdInputLayout.visibility == View.VISIBLE) {
            val cid = customVoiceIdEdit.text.toString().trim()
            if (cid.isNotEmpty()) {
                Prefs.setElevenLabsVoice(act, cid)
                cid
            } else {
                "pNInz6obpgqjMhklaMuC"
            }
        } else {
            voices[voiceSpinner.selectedItemPosition].id
        }

        val geminiKey = Prefs.geminiKey(act)

        setAlarmButton.isEnabled = false
        progressContainer.visibility = View.VISIBLE

        TtsGenerator.generate(act, text, apiKey, voiceId, geminiKey) { audioFile, isElevenLabs, cleanText ->
            setAlarmButton.isEnabled = true
            progressContainer.visibility = View.GONE

            if (audioFile != null) {
                val newAlarmId = editingAlarmId ?: System.currentTimeMillis().toString()
                val newAlarm = AlarmItem(
                    id = newAlarmId,
                    hour = targetHour,
                    minute = targetMinute,
                    rawText = text,
                    cleanText = cleanText,
                    isEnabled = true,
                    audioPath = audioFile.absolutePath,
                    isElevenLabs = isElevenLabs
                )
                val currentList = Prefs.alarms(act).toMutableList()
                
                if (editingAlarmId != null) {
                    val oldIdx = currentList.indexOfFirst { it.id == editingAlarmId }
                    if (oldIdx >= 0) {
                        val oldAlarm = currentList[oldIdx]
                        AlarmScheduler.cancel(act, oldAlarm)
                        oldAlarm.audioPath?.let { path ->
                            val f = File(path)
                            if (f.exists()) f.delete()
                        }
                        currentList[oldIdx] = newAlarm
                    } else {
                        currentList.add(newAlarm)
                    }
                    Toast.makeText(act, "Alarm updated successfully", Toast.LENGTH_SHORT).show()
                } else {
                    currentList.add(newAlarm)
                    Toast.makeText(act, R.string.alarm_set_success, Toast.LENGTH_SHORT).show()
                }

                Prefs.saveAlarms(act, currentList)
                AlarmScheduler.schedule(act, newAlarm)
                
                cancelEdit()
                refreshAlarmsList()
            } else {
                Toast.makeText(act, R.string.alarm_generation_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleTestAlarm() {
        val act = activity ?: return
        val text = alarmTextEdit.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(act, "Please enter text to test", Toast.LENGTH_SHORT).show()
            return
        }

        val inputKey = elevenLabsKeyEdit.text.toString().trim()
        val apiKey = if (inputKey == "••••••••••••••••••••••••••••••••") {
            Prefs.elevenLabsKey(act)
        } else {
            inputKey
        }
        val voiceId = if (customVoiceIdInputLayout.visibility == View.VISIBLE) {
            customVoiceIdEdit.text.toString().trim().ifEmpty { "pNInz6obpgqjMhklaMuC" }
        } else {
            voices[voiceSpinner.selectedItemPosition].id
        }

        val geminiKey = Prefs.geminiKey(act)

        testAlarmButton.isEnabled = false
        progressContainer.visibility = View.VISIBLE

        TtsGenerator.generate(act, text, apiKey, voiceId, geminiKey) { audioFile, isElevenLabs, cleanText ->
            testAlarmButton.isEnabled = true
            progressContainer.visibility = View.GONE

            if (audioFile != null) {
                try {
                    testMediaPlayer?.let {
                        if (it.isPlaying) it.stop()
                        it.release()
                    }

                    val attrContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        act.createAttributionContext("alarm_service")
                    } else {
                        act
                    }

                    val player = MediaPlayer()
                    player.setDataSource(attrContext, Uri.fromFile(audioFile))
                    player.prepare()
                    player.start()
                    testMediaPlayer = player
                    val source = if (isElevenLabs) "ElevenLabs" else "System TTS (Fallback)"
                    Toast.makeText(act, "Playing $source Preview...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("AlarmTool", "Preview failed", e)
                    Toast.makeText(act, "Preview audio playback failed", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(act, "Preview audio generation failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshAlarmsList() {
        val act = activity ?: return
        val list = Prefs.alarms(act)
        alarmsListContainer.removeAllViews()

        val alarmManager = act.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val nextSystemAlarm = alarmManager.nextAlarmClock
        
        val systemAlarmContainer = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0xFFF0FDF4.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
        }

        val systemAlarmIcon = android.widget.ImageView(act).apply {
            setImageResource(android.R.drawable.ic_lock_idle_alarm)
            setColorFilter(0xFF0F766E.toInt())
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val systemAlarmText = TextView(act).apply {
            if (nextSystemAlarm != null) {
                val sdf = java.text.SimpleDateFormat("EEE, hh:mm a", Locale.getDefault())
                val calendar = Calendar.getInstance().apply { timeInMillis = nextSystemAlarm.triggerTime }
                text = "Next System Alarm: " + sdf.format(calendar.time)
            } else {
                text = "No upcoming system alarms"
            }
            textSize = 15f
            setTextColor(0xFF0F766E.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 0, 0, 0)
            }
        }

        systemAlarmContainer.addView(systemAlarmIcon)
        systemAlarmContainer.addView(systemAlarmText)
        alarmsListContainer.addView(systemAlarmContainer)

        if (list.isEmpty()) {
            val noAlarmsText = TextView(act).apply {
                id = R.id.noAlarmsText
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(16, 16, 16, 16)
                gravity = android.view.Gravity.CENTER
                text = act.getString(R.string.no_alarms)
                setTextColor(0xFF5F6368.toInt())
            }
            alarmsListContainer.addView(noAlarmsText)
            return
        }

        list.forEach { item ->
            val row = LayoutInflater.from(act).inflate(
                android.R.layout.simple_list_item_2, 
                alarmsListContainer, 
                false
            ) as ViewGroup

            // Create a custom layout dynamically to hold a toggle switch and delete button
            val rowContainer = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16, 8, 16, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val amPm = if (item.hour < 12) "AM" else "PM"
            val displayHour = when {
                item.hour == 0 -> 12
                item.hour > 12 -> item.hour - 12
                else -> item.hour
            }
            val timeString = String.format(Locale.getDefault(), "%02d:%02d %s", displayHour, item.minute, amPm)

            val textContainer = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val timeAndBadgeContainer = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val timeView = TextView(act).apply {
                text = timeString
                textSize = 20f
                setTextColor(0xFF202124.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            timeAndBadgeContainer.addView(timeView)

            val badgeView = TextView(act).apply {
                text = if (item.isElevenLabs) "ElevenLabs" else "System TTS"
                textSize = 10f
                setPadding(16, 4, 16, 4)
                setTextColor(if (item.isElevenLabs) 0xFF1E40AF.toInt() else 0xFF374151.toInt())
                
                val backgroundDrawable = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(if (item.isElevenLabs) 0xFFDBEAFE.toInt() else 0xFFE5E7EB.toInt())
                }
                background = backgroundDrawable
                
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16, 0, 0, 0)
                }
            }
            timeAndBadgeContainer.addView(badgeView)

            val snippetView = TextView(act).apply {
                val displayText = "Clean: ${item.cleanText}\nRaw: ${item.rawText}"
                text = if (displayText.length > 80) displayText.take(77) + "..." else displayText
                textSize = 14f
                setTextColor(0xFF5F6368.toInt())
            }

            textContainer.addView(timeAndBadgeContainer)
            textContainer.addView(snippetView)
            rowContainer.addView(textContainer)

            val toggle = Switch(act).apply {
                isChecked = item.isEnabled
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16, 0, 16, 0)
                }
                setOnCheckedChangeListener { _, checked ->
                    val currentList = Prefs.alarms(act).toMutableList()
                    val idx = currentList.indexOfFirst { it.id == item.id }
                    if (idx >= 0) {
                        val updated = currentList[idx].copy(isEnabled = checked)
                        currentList[idx] = updated
                        Prefs.saveAlarms(act, currentList)
                        if (checked) {
                            AlarmScheduler.schedule(act, updated)
                        } else {
                            AlarmScheduler.cancel(act, updated)
                        }
                    }
                }
            }
            rowContainer.addView(toggle)

            val editBtn = Button(act, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Edit"
                setTextColor(0xFF2563EB.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    editingAlarmId = item.id
                    targetHour = item.hour
                    targetMinute = item.minute
                    updateTimeText()
                    alarmTextEdit.setText(item.rawText)
                    setAlarmButton.text = "Update Alarm"
                    cancelEditButton.visibility = View.VISIBLE
                    rootLayout?.findViewById<androidx.core.widget.NestedScrollView>(R.id.alarmScrollView)?.smoothScrollTo(0, 0)
                }
            }
            rowContainer.addView(editBtn)

            val deleteBtn = Button(act, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Delete"
                setTextColor(0xFFEF4444.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    AlarmScheduler.cancel(act, item)
                    // Remove file
                    item.audioPath?.let { path ->
                        val f = File(path)
                        if (f.exists()) f.delete()
                    }
                    val currentList = Prefs.alarms(act).toMutableList()
                    currentList.removeAll { it.id == item.id }
                    Prefs.saveAlarms(act, currentList)
                    refreshAlarmsList()
                }
            }
            rowContainer.addView(deleteBtn)

            alarmsListContainer.addView(rowContainer)
        }
    }
}

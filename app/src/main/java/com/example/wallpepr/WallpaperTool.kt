package com.example.wallpepr

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WallpaperTool : Tool {
    override val id: String = "wallpaper"
    override val nameResId: Int = R.string.tool_wallpaper
    override val iconResId: Int = android.R.drawable.ic_menu_gallery

    private var activity: Activity? = null
    private var rootLayout: View? = null

    private lateinit var selectedFolderText: TextView
    private lateinit var statusText: TextView
    private lateinit var statusIndicator: View
    private lateinit var previewImage: ImageView
    private lateinit var intervalSpinner: Spinner
    private lateinit var sizeModeSpinner: Spinner
    private lateinit var modeGroup: RadioGroup
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var changeNowButton: Button

    private val previewExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentPreviewBitmap: Bitmap? = null
    @Volatile private var previewRequestId = 0

    override fun createView(activity: Activity, parent: ViewGroup): View {
        this.activity = activity
        val inflater = LayoutInflater.from(activity)
        val view = inflater.inflate(R.layout.tool_wallpaper, parent, false)
        rootLayout = view

        selectedFolderText = view.findViewById(R.id.selectedFolderText)
        statusText = view.findViewById(R.id.statusText)
        statusIndicator = view.findViewById(R.id.statusIndicator)
        previewImage = view.findViewById(R.id.previewImage)
        intervalSpinner = view.findViewById(R.id.intervalSpinner)
        sizeModeSpinner = view.findViewById(R.id.sizeModeSpinner)
        modeGroup = view.findViewById(R.id.modeGroup)
        startButton = view.findViewById(R.id.startButton)
        stopButton = view.findViewById(R.id.stopButton)
        changeNowButton = view.findViewById(R.id.changeNowButton)

        configureIntervalPicker()
        configureSizeModePicker()
        configureModePicker()

        view.findViewById<View>(R.id.selectFolderButton).setOnClickListener { openFolderPicker() }
        view.findViewById<View>(R.id.previewButton).setOnClickListener { showWallpaperPreview() }
        changeNowButton.setOnClickListener { triggerManualChange() }
        startButton.setOnClickListener { startRotation() }
        stopButton.setOnClickListener { stopRotation() }

        requestNotificationPermissionIfNeeded()
        refreshUi()

        return view
    }

    override fun onResume() {
        refreshUi()
    }

    override fun onDestroy() {
        previewRequestId++
        previewExecutor.shutdownNow()
        recycleCurrentPreview()
        activity = null
        rootLayout = null
    }

    fun handleFolderUriSelected(uri: Uri) {
        val act = activity ?: return
        Prefs.setFolderUri(act, uri)
        Prefs.setNextIndex(act, 0)
        ImageRepository.invalidate(act)
        refreshUi()
    }

    private fun configureIntervalPicker() {
        val act = activity ?: return
        intervalSpinner.adapter = ArrayAdapter(
            act,
            android.R.layout.simple_spinner_dropdown_item,
            Prefs.intervals.map { it.label }
        )
        val selectedIndex = Prefs.intervals.indexOfFirst { it.millis == Prefs.intervalMs(act) }
            .takeIf { it >= 0 } ?: 1
        intervalSpinner.setSelection(selectedIndex)
        intervalSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val currentAct = activity ?: return
                Prefs.setIntervalMs(currentAct, Prefs.intervals[position].millis)
                WallpaperScheduler.restartIfRunning(currentAct)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun configureSizeModePicker() {
        val act = activity ?: return
        sizeModeSpinner.adapter = ArrayAdapter(
            act,
            android.R.layout.simple_spinner_dropdown_item,
            WallpaperSizer.modes.map { it.label }
        )
        val selectedIndex = WallpaperSizer.modes.indexOfFirst { it.value == Prefs.sizeMode(act) }
            .takeIf { it >= 0 } ?: 0
        sizeModeSpinner.setSelection(selectedIndex)
        sizeModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val currentAct = activity ?: return
                Prefs.setSizeMode(currentAct, WallpaperSizer.modes[position].value)
                refreshPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun configureModePicker() {
        val act = activity ?: return
        modeGroup.check(
            if (Prefs.mode(act) == Prefs.MODE_SHUFFLE) R.id.shuffleRadio else R.id.sequentialRadio
        )
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val currentAct = activity ?: return@setOnCheckedChangeListener
            val mode = if (checkedId == R.id.shuffleRadio) {
                Prefs.MODE_SHUFFLE
            } else {
                Prefs.MODE_SEQUENTIAL
            }
            Prefs.setMode(currentAct, mode)
            refreshPreview()
        }
    }

    private fun openFolderPicker() {
        val act = activity ?: return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        act.startActivityForResult(intent, REQUEST_OPEN_TREE)
    }

    private fun startRotation() {
        val act = activity ?: return
        if (Prefs.folderUri(act) == null) {
            Toast.makeText(act, R.string.choose_folder_first, Toast.LENGTH_SHORT).show()
            return
        }
        WallpaperScheduler.start(act)
        Prefs.setRunning(act, true)
        refreshUi()
    }

    private fun stopRotation() {
        val act = activity ?: return
        WallpaperScheduler.stop(act)
        refreshUi()
    }

    private fun triggerManualChange() {
        val act = activity ?: return
        if (Prefs.folderUri(act) == null) {
            Toast.makeText(act, R.string.choose_folder_first, Toast.LENGTH_SHORT).show()
            return
        }
        WallpaperScheduler.triggerNow(act)
        changeNowButton.isEnabled = false
        changeNowButton.postDelayed({ changeNowButton.isEnabled = true }, 2000)
        changeNowButton.postDelayed({ refreshPreview() }, 1000)
    }

    private fun refreshUi() {
        val act = activity ?: return
        selectedFolderText.text = Prefs.folderUri(act)?.let { shortUri(it) }
            ?: act.getString(R.string.no_folder_selected)
        
        val running = Prefs.isRunning(act)
        statusText.text = if (running) {
            act.getString(R.string.status_running)
        } else {
            act.getString(R.string.status_stopped)
        }
        
        statusIndicator.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (running) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt())
        }

        startButton.isEnabled = !running
        stopButton.isEnabled = running
        refreshPreview()
    }

    private fun refreshPreview() {
        val act = activity ?: return
        val requestId = ++previewRequestId
        previewExecutor.execute {
            val nextUri = ImageRepository.peekNext(act)
            val thumbnail = nextUri?.let { WallpaperSizer.createPreviewBitmap(act, it) }
            previewImage.post {
                if (requestId != previewRequestId) {
                    thumbnail?.recycle()
                    return@post
                }
                recycleCurrentPreview()
                if (thumbnail == null) {
                    previewImage.setImageResource(android.R.drawable.ic_menu_gallery)
                    previewImage.alpha = 0.35f
                } else {
                    currentPreviewBitmap = thumbnail
                    previewImage.alpha = 1f
                    previewImage.setImageBitmap(thumbnail)
                }
            }
        }
    }

    private fun showWallpaperPreview() {
        val act = activity ?: return
        val requestId = ++previewRequestId
        val preview = ImageView(act).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF111111.toInt())
        }
        val container = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 4)
            addView(
                preview,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.displayMetrics.heightPixels * 2 / 3
                )
            )
        }

        val dialog = AlertDialog.Builder(act)
            .setTitle(R.string.wallpaper_preview)
            .setView(container)
            .setPositiveButton(R.string.close, null)
            .create()

        var dialogBitmap: Bitmap? = null
        dialog.setOnShowListener {
            previewExecutor.execute {
                val uri = ImageRepository.peekNext(act)
                if (uri == null) {
                    preview.post {
                        if (dialog.isShowing) {
                            Toast.makeText(act, R.string.choose_folder_first, Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                    }
                    return@execute
                }

                val bitmap = WallpaperSizer.createWallpaperBitmap(act, uri)
                preview.post {
                    if (requestId != previewRequestId || !dialog.isShowing) {
                        bitmap?.recycle()
                        return@post
                    }
                    dialogBitmap = bitmap
                    preview.setImageBitmap(bitmap)
                }
            }
        }
        dialog.setOnDismissListener {
            preview.setImageDrawable(null)
            dialogBitmap?.recycle()
            dialogBitmap = null
        }
        dialog.show()
    }

    private fun recycleCurrentPreview() {
        previewImage.setImageDrawable(null)
        currentPreviewBitmap?.recycle()
        currentPreviewBitmap = null
    }

    private fun shortUri(uri: Uri): String {
        val raw = uri.lastPathSegment ?: uri.toString()
        return raw.substringAfterLast(':').ifBlank { raw }
    }

    private fun requestNotificationPermissionIfNeeded() {
        val act = activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    companion object {
        const val REQUEST_OPEN_TREE = 100
        private const val REQUEST_NOTIFICATIONS = 101
    }
}

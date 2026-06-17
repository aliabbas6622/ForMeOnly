package com.example.wallpepr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : Activity() {
    private lateinit var toolContainer: ViewGroup
    private lateinit var bottomNavigation: BottomNavigationView

    private val tools = listOf(
        WallpaperTool(),
        BshareTool(),
        AlarmTool()
    )
    private var activeTool: Tool? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolContainer = findViewById(R.id.toolContainer)
        bottomNavigation = findViewById(R.id.bottomNavigation)

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_wallpaper -> switchTool("wallpaper")
                R.id.navigation_bshare -> switchTool("bshare")
                R.id.navigation_alarm -> switchTool("alarm")
                else -> false
            }
        }

        if (savedInstanceState == null) {
            switchTool("wallpaper")
        } else {
            switchTool("wallpaper")
        }
    }

    override fun onResume() {
        super.onResume()
        activeTool?.onResume()
    }

    override fun onPause() {
        activeTool?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        activeTool?.onDestroy()
        super.onDestroy()
    }

    @SuppressLint("WrongConstant")
    @Deprecated("Uses platform API to avoid extra AndroidX dependencies.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        if (requestCode == WallpaperTool.REQUEST_OPEN_TREE) {
            val uri = data?.data ?: return
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)

            (tools.firstOrNull { it.id == "wallpaper" } as? WallpaperTool)?.handleFolderUriSelected(uri)
        }
    }

    private fun switchTool(toolId: String): Boolean {
        if (activeTool?.id == toolId) return true

        val nextTool = tools.find { it.id == toolId } ?: return false

        activeTool?.onPause()
        activeTool?.onDestroy()
        toolContainer.removeAllViews()

        val view = nextTool.createView(this, toolContainer)
        toolContainer.addView(view)

        activeTool = nextTool
        activeTool?.onResume()

        return true
    }
}

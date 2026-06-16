package com.example.wallpepr

import android.app.Activity
import android.view.View
import android.view.ViewGroup

interface Tool {
    val id: String
    val nameResId: Int
    val iconResId: Int
    fun createView(activity: Activity, parent: ViewGroup): View
    fun onResume() {}
    fun onPause() {}
    fun onDestroy() {}
}

package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class PowerButtonReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PowerButtonReceiver"
        private var lastPressTime = 0L
        private const val DELAY_LIMIT = 600L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_SCREEN_ON) {
            val currentTime = System.currentTimeMillis()
            val timeDiff = currentTime - lastPressTime
            Log.d(TAG, "Screen toggle captured. delta: $timeDiff ms")
            
            // Avoid immediate debouncing triggers
            if (timeDiff in 100..DELAY_LIMIT) {
                Log.d(TAG, "Double press sequence validated. Spawning Overlay Service.")
                val serviceIntent = Intent(context, MyraOverlayService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start MyraOverlayService from power trigger: ${e.message}")
                }
            }
            lastPressTime = currentTime
        }
    }
}

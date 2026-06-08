package com.speaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机自启接收器
 * 手机重启后自动启动智能音箱后台服务
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("speaker", Context.MODE_PRIVATE)

            val wasRunning = prefs.getBoolean("service_running", false)
            if (!wasRunning) return

            val apiKey = prefs.getString("api_key", "") ?: ""
            val newsApiKey = prefs.getString("news_api_key", "") ?: ""
            val wakeWord = prefs.getString("wake_word", "贾维斯") ?: "贾维斯"

            if (apiKey.isEmpty()) return

            val serviceIntent = Intent(context, SpeakerService::class.java).apply {
                putExtra("api_key", apiKey)
                putExtra("news_api_key", newsApiKey)
                putExtra("wake_word", wakeWord)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}

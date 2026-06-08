package com.speaker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var newsKeyInput: TextInputEditText
    private lateinit var wakeWordInput: TextInputEditText
    private lateinit var statusText: android.widget.TextView
    private lateinit var logText: android.widget.TextView
    private lateinit var serviceBtn: MaterialButton
    private var isServiceRunning = false

    // 接收服务状态
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SpeakerService.STATUS_CHANGED -> {
                    val status = intent.getStringExtra("status") ?: ""
                    statusText.text = status
                }
                SpeakerService.LOG_APPEND -> {
                    val text = intent.getStringExtra("text") ?: ""
                    logText.append("$text\n")
                    // 自动滚到底部
                    val scroll = logText.parent as? android.widget.ScrollView
                    scroll?.fullScroll(android.view.View.FOCUS_DOWN)
                }
            }
        }
    }

    private val serviceStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getBooleanExtra("stopped", false) == true) {
                isServiceRunning = false
                updateServiceUI()
                statusText.text = "已停止"
            }
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.all { it.value }) {
            startServiceWithCheck()
        } else {
            Toast.makeText(this, "需要麦克风权限才能使用", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        apiKeyInput = findViewById(R.id.apiKeyInput)
        newsKeyInput = findViewById(R.id.newsKeyInput)
        wakeWordInput = findViewById(R.id.wakeWordInput)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        serviceBtn = findViewById(R.id.serviceBtn)

        // 加载保存的配置
        val prefs = getSharedPreferences("speaker", MODE_PRIVATE)
        apiKeyInput.setText(prefs.getString("api_key", ""))
        newsKeyInput.setText(prefs.getString("news_api_key", ""))
        wakeWordInput.setText(prefs.getString("wake_word", "贾维斯"))

        // 保存设置
        findViewById<MaterialButton>(R.id.saveKeyBtn).setOnClickListener {
            prefs.edit()
                .putString("api_key", apiKeyInput.text?.toString()?.trim() ?: "")
                .putString("news_api_key", newsKeyInput.text?.toString()?.trim() ?: "")
                .putString("wake_word", wakeWordInput.text?.toString()?.trim() ?: "贾维斯")
                .apply()
            Toast.makeText(this, "✅ 设置已保存", Toast.LENGTH_SHORT).show()
        }

        // 服务按钮
        serviceBtn.setOnClickListener {
            if (isServiceRunning) {
                stopService(Intent(this, SpeakerService::class.java))
                isServiceRunning = false
                updateServiceUI()
                statusText.text = "已停止"
            } else {
                checkPermissionsAndStart()
            }
        }

        // 注册广播接收器
        val filter = IntentFilter().apply {
            addAction(SpeakerService.STATUS_CHANGED)
            addAction(SpeakerService.LOG_APPEND)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter)
        registerReceiver(serviceStopReceiver, IntentFilter("com.speaker.STOPPED"))

        // 检查服务状态
        isServiceRunning = prefs.getBoolean("service_running", false)
        updateServiceUI()
        if (isServiceRunning) {
            statusText.text = "运行中"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
            unregisterReceiver(serviceStopReceiver)
        } catch (_: Exception) {}
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionsLauncher.launch(permissions.toTypedArray())
            return
        }

        startServiceWithCheck()
    }

    private fun startServiceWithCheck() {
        val apiKey = apiKeyInput.text?.toString()?.trim()
        if (apiKey.isNullOrEmpty()) {
            Toast.makeText(this, "请先输入 DeepSeek API Key", Toast.LENGTH_LONG).show()
            return
        }

        // 保存当前设置
        val prefs = getSharedPreferences("speaker", MODE_PRIVATE)
        prefs.edit()
            .putString("api_key", apiKey)
            .putString("news_api_key", newsKeyInput.text?.toString()?.trim() ?: "")
            .putString("wake_word", wakeWordInput.text?.toString()?.trim() ?: "贾维斯")
            .apply()

        val intent = Intent(this, SpeakerService::class.java).apply {
            putExtra("api_key", apiKey)
            putExtra("news_api_key", newsKeyInput.text?.toString()?.trim() ?: "")
            putExtra("wake_word", wakeWordInput.text?.toString()?.trim() ?: "贾维斯")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isServiceRunning = true
        updateServiceUI()
        statusText.text = "启动中…"
    }

    private fun updateServiceUI() {
        if (isServiceRunning) {
            serviceBtn.text = getString(R.string.stop_service)
            serviceBtn.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
            )
        } else {
            serviceBtn.text = getString(R.string.start_service)
            serviceBtn.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            )
        }
    }
}

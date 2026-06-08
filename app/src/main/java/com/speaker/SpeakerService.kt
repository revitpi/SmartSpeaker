package com.speaker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat

/**
 * 核心后台服务 — 前台服务，系统几乎不会杀
 *
 * 工作流程：
 *   SpeechRecognizer 持续监听 → 检测唤醒词 → DeepSeek API（带工具）→ TTS 播报
 */
class SpeakerService : Service() {

    companion object {
        const val CHANNEL_ID = "speaker_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.speaker.STOP"
        const val STATUS_CHANGED = "com.speaker.STATUS"
        const val LOG_APPEND = "com.speaker.LOG"
    }

    private var apiKey: String = ""
    private var wakeWord: String = "贾维斯"
    private var newsApiKey: String = ""
    private var isRunning = false

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var notificationManager: NotificationManager
    private lateinit var deepseek: DeepSeekClient
    private lateinit var ttsPlayer: TTSPlayer
    private lateinit var toolExecutor: ToolExecutor

    private var currentStatus = "待机中"

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) {
                stopSelf()
            }
        }
    }

    // SpeechRecognizer 回调
    private val recognitionListener = object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {}

        override fun onBeginningOfSpeech() {
            updateStatus("听到声音…")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (isRunning) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> { /* 安静，正常 */ }
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { /* 超时，正常 */ }
                    else -> appendLog("[$error]")
                }
                startListening()
            }
        }

        override fun onResults(results: android.os.Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!texts.isNullOrEmpty()) {
                val text = texts[0]
                appendLog("🎤 识别: $text")
                processSpeech(text)
            }
            if (isRunning) startListening()
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {}

        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // 初始化各组件
        deepseek = DeepSeekClient()
        ttsPlayer = TTSPlayer(this)
        toolExecutor = ToolExecutor()
        toolExecutor.setContext(this)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(recognitionListener)
        registerReceiver(stopReceiver, IntentFilter(ACTION_STOP))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("speaker", MODE_PRIVATE)

        apiKey = intent?.getStringExtra("api_key")
            ?: prefs.getString("api_key", "") ?: ""

        wakeWord = intent?.getStringExtra("wake_word")
            ?: prefs.getString("wake_word", "贾维斯") ?: "贾维斯"

        newsApiKey = intent?.getStringExtra("news_api_key")
            ?: prefs.getString("news_api_key", "") ?: ""

        prefs.edit().putBoolean("service_running", true).apply()

        startForeground(NOTIFICATION_ID, buildNotification("正在聆听…"))

        if (apiKey.isEmpty()) {
            appendLog("❌ 未设置 API Key")
            stopSelf()
            return START_NOT_STICKY
        }

        deepseek.setApiKey(apiKey)
        toolExecutor.setApiKey(newsApiKey.ifEmpty { apiKey })

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            appendLog("❌ 当前设备不支持语音识别")
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true

        ttsPlayer.initialize {
            startListening()
            appendLog("✅ 智能音箱已启动")
            appendLog("📢 唤醒词: 「$wakeWord」")
            appendLog("🔧 支持: 天气🌤️ 时间⏰ 闹钟⏱️ 计算🧮 新闻📰")
            appendLog("💡 说「$wakeWord 北京天气」试试")
            updateNotification("正在聆听…")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try {
            speechRecognizer.stopListening()
            speechRecognizer.destroy()
        } catch (_: Exception) {}
        ttsPlayer.shutdown()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        getSharedPreferences("speaker", MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 核心逻辑 ----------

    private fun startListening() {
        if (!isRunning) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            appendLog("❌ 启动监听失败")
        }
    }

    private fun processSpeech(text: String) {
        // 检测唤醒词
        if (!text.contains(wakeWord, ignoreCase = true)) {
            return  // 没唤醒词，忽略
        }

        // 提取唤醒词后面的内容
        val query = text
            .replace(wakeWord, "", ignoreCase = true)
            .trim()
            .removePrefix("，").removePrefix(",")
            .removePrefix("。").removePrefix(".")
            .trim()

        if (query.isEmpty()) {
            appendLog("⏳ 等待指令…")
            return
        }

        appendLog("🔊 唤醒: $query")
        updateStatus("思考中…")
        updateNotification("思考中…")

        // 调 DeepSeek（带工具）
        deepseek.ask(query) { reply ->
            if (reply != null) {
                appendLog("🤖 $reply")
                updateStatus("播报中…")
                updateNotification("播报中…")
                ttsPlayer.speak(reply) {
                    updateStatus("待机中")
                    updateNotification("正在聆听…")
                }
            } else {
                appendLog("❌ 请求失败")
                updateStatus("待机中")
                updateNotification("正在聆听…")
            }
        }
    }

    // ---------- 通知 ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun updateStatus(status: String) {
        currentStatus = status
        sendBroadcast(Intent(STATUS_CHANGED).apply { putExtra("status", status) })
    }

    private fun appendLog(text: String) {
        sendBroadcast(Intent(LOG_APPEND).apply { putExtra("text", text) })
    }
}

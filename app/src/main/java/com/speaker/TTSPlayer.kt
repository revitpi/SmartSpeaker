package com.speaker

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 语音合成播放器
 * 使用 Android 内置 TTS 引擎，不需要额外依赖
 */
class TTSPlayer(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var onDone: (() -> Unit)? = null

    /**
     * 初始化 TTS（异步，初始化完成回调 runnable）
     */
    fun initialize(onReady: () -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 设置中文
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // 中文不支持，用英文
                    tts?.setLanguage(Locale.ENGLISH)
                }
                // 设置语速（略慢，更清晰）
                tts?.setSpeechRate(0.95f)
                isInitialized = true
                onReady()
            }
        }

        // 设置播报完成回调
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uttId: String?) {}
            override fun onDone(uttId: String?) {
                onDone?.invoke()
                onDone = null
            }
            override fun onError(uttId: String?) {}
        })
    }

    /**
     * 播报文字
     * @param text 要播报的文字
     * @param callback 播报完成后的回调
     */
    fun speak(text: String, callback: (() -> Unit)? = null) {
        if (!isInitialized || tts == null) {
            callback?.invoke()
            return
        }

        // 停止当前播报
        tts?.stop()

        onDone = callback

        // 分段播报（每次不超过 400 字，Android TTS 限制）
        val chunks = text.chunked(380)
        for ((i, chunk) in chunks.withIndex()) {
            val utteranceId = "speaker_${i}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, utteranceId)
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null)
            }
        }

        // 如果没有分段，但也没设回调，至少有一个 utterance ID
        if (chunks.size == 1 && callback == null) {
            // nothing
        }
    }

    /**
     * 停止播报
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * 是否正在播报
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    /**
     * 释放资源
     */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isInitialized = false
    }
}

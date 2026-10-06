package com.openswift.keyboard.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.*

/**
 * Continuous, silent voice input integration:
 * - Completely silences the start beep/ding sound during speech recognizer startup and restarts.
 * - Supports continuous speaking without timing out or exiting on pauses.
 * - Commits clean text without duplicate words.
 */
class VoiceRecognizer(private val ctx: Context) {

    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isContinuous = false
    private var currentLanguage: String = Locale.getDefault().language
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var originalMusicVolume: Int? = null

    private fun muteAllBeeps(mute: Boolean) {
        val am = audioManager ?: return
        try {
            if (mute) {
                if (originalMusicVolume == null) {
                    originalMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
                // Specifically mute STREAM_MUSIC (where Google SpeechRecognizer plays its sound)
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                } catch (_: Exception) {}
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                } catch (_: Exception) {}
                // Also mute STREAM_SYSTEM
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                } catch (_: Exception) {}
                // Also mute STREAM_NOTIFICATION
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                } catch (_: Exception) {}
            } else {
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {}
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {}
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {}
                try {
                    originalMusicVolume?.let { am.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
                } catch (_: Exception) {}
                originalMusicVolume = null
            }
        } catch (_: Exception) {}
    }

    private fun initRecognizer() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {}

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onPartialResults(results: android.os.Bundle?) {
                    // Ignore interim hypotheses to prevent duplicate words in text field
                }

                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()?.trim()
                    if (!text.isNullOrEmpty()) {
                        onResult?.invoke(text)
                    }

                    // Keep listening continuously across pauses until explicitly stopped
                    if (isContinuous) {
                        mainHandler.postDelayed({
                            if (isContinuous) {
                                startListeningInternal()
                            }
                        }, 100L)
                    } else {
                        isListening = false
                        mainHandler.postDelayed({
                            muteAllBeeps(false)
                        }, 500L)
                    }
                }

                override fun onError(error: Int) {
                    val isSilenceOrPause = (error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT)

                    if (isContinuous && isSilenceOrPause) {
                        // User was speaking slowly or paused; resume listening seamlessly
                        mainHandler.postDelayed({
                            if (isContinuous) {
                                startListeningInternal()
                            }
                        }, 150L)
                        return
                    }

                    if (isContinuous && error == SpeechRecognizer.ERROR_CLIENT) {
                        // Recreate recognizer instance on client error and continue
                        recreateRecognizer()
                        mainHandler.postDelayed({
                            if (isContinuous) {
                                startListeningInternal()
                            }
                        }, 250L)
                        return
                    }

                    isListening = false
                    isContinuous = false
                    mainHandler.postDelayed({
                        muteAllBeeps(false)
                    }, 500L)

                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "خطأ في التقاط الصوت"
                        SpeechRecognizer.ERROR_CLIENT -> "خطأ في خدمة الصوت"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "يرجى منح إذن الميكروفون"
                        SpeechRecognizer.ERROR_NETWORK -> "يرجى التحقق من الاتصال بالإنترنت"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "خدمة الصوت مشغولة، جرب مجدداً"
                        else -> "تعذر التعرف على الصوت"
                    }
                    onError?.invoke(msg)
                }

                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }
    }

    private fun recreateRecognizer() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        initRecognizer()
    }

    fun startListening(languageCode: String? = null, continuous: Boolean = true) {
        currentLanguage = languageCode ?: Locale.getDefault().language
        isContinuous = continuous
        initRecognizer()
        startListeningInternal()
    }

    private fun startListeningInternal() {
        try {
            // Mute all beeps and dings completely
            muteAllBeeps(true)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
            }
            recognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            muteAllBeeps(false)
            isListening = false
            if (!isContinuous) {
                onError?.invoke("تعذر تشغيل الصوت: ${e.message}")
            }
        }
    }

    fun stopListening() {
        isContinuous = false
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        // Keep streams muted while stopping to completely silence the finish/stop beep
        muteAllBeeps(true)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {}
        // Restore stream volume only after the stop sound window has completely passed
        mainHandler.postDelayed({
            muteAllBeeps(false)
        }, 600L)
    }

    fun destroy() {
        isContinuous = false
        isListening = false
        mainHandler.removeCallbacksAndMessages(null)
        muteAllBeeps(true)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {}
        try {
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        mainHandler.postDelayed({
            muteAllBeeps(false)
        }, 600L)
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Reads what this device can recognize with, without creating a recognizer. */
object DeviceSpeechProbe {
    fun probe(context: Context, allowsNetworkInput: Boolean): DeviceSpeechEnvironment {
        val sdkInt = Build.VERSION.SDK_INT
        return DeviceSpeechEnvironment(
            sdkInt = sdkInt,
            onDeviceAvailable = when {
                sdkInt >= DeviceSpeechEngineSelector.ON_DEVICE_AVAILABILITY_CHECK_MIN_SDK ->
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                // Android 12 cannot be asked, so the first start decides (see the fallback policy).
                sdkInt >= DeviceSpeechEngineSelector.ON_DEVICE_MIN_SDK -> true
                else -> false
            },
            serviceAvailable = SpeechRecognizer.isRecognitionAvailable(context),
            allowsNetworkInput = allowsNetworkInput
        )
    }
}

/**
 * One dictation attempt on top of [SpeechRecognizer]. Call every method on the main thread.
 * A session never delivers a callback after [cancel] or [destroy], and it holds no microphone
 * once a result, an error, [cancel] or [destroy] has happened.
 */
class DeviceSpeechSession(
    private val context: Context,
    private val environment: DeviceSpeechEnvironment,
    private val listener: Listener
) {
    interface Listener {
        fun onReady()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(code: Int)
        fun onRms(db: Float)
    }

    private var engine = environment.engine
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    private var languageTag = ""
    private var ready = false
    private var heardSpeech = false
    private var stopRequested = false
    private var retriedWithSystemService = false
    private var destroyed = false

    fun start(languageTag: String) {
        check(!destroyed) { "Speech session is destroyed" }
        check(engine != DeviceSpeechEngine.Unavailable) { "No speech engine is available" }
        releaseRecognizer()
        this.languageTag = languageTag
        stopRequested = false
        heardSpeech = false
        retriedWithSystemService = false
        launch(engine)
    }

    /** Ends listening and waits for the final result. Safe before the recognizer is ready. */
    fun stop() {
        if (destroyed) return
        stopRequested = true
        if (ready) recognizer?.stopListening()
    }

    /** Drops the attempt without a result. */
    fun cancel() {
        generation++
        recognizer?.cancel()
        releaseRecognizer()
    }

    fun destroy() {
        destroyed = true
        generation++
        releaseRecognizer()
    }

    private fun launch(target: DeviceSpeechEngine) {
        val id = ++generation
        ready = false
        val created = try {
            create(target)
        } catch (exception: UnsupportedOperationException) {
            handleError(id, SpeechRecognizer.ERROR_CLIENT)
            return
        }
        recognizer = created
        created.setRecognitionListener(Callbacks(id))
        try {
            created.startListening(recognizerIntent(target))
        } catch (exception: SecurityException) {
            handleError(id, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        }
    }

    private fun create(target: DeviceSpeechEngine): SpeechRecognizer = when (target) {
        DeviceSpeechEngine.OnDevice -> {
            check(Build.VERSION.SDK_INT >= DeviceSpeechEngineSelector.ON_DEVICE_MIN_SDK)
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        DeviceSpeechEngine.SystemService -> SpeechRecognizer.createSpeechRecognizer(context)
        DeviceSpeechEngine.Unavailable -> error("No speech engine is available")
    }

    private fun recognizerIntent(target: DeviceSpeechEngine): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            if (target == DeviceSpeechEngine.SystemService) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private fun releaseRecognizer() {
        val current = recognizer ?: return
        recognizer = null
        ready = false
        current.destroy()
    }

    private fun handleError(id: Int, code: Int) {
        if (id != generation) return
        if (DeviceSpeechFallbackPolicy.shouldRetryWithSystemService(
                engine = engine,
                environment = environment,
                errorCode = code,
                heardSpeech = heardSpeech,
                alreadyRetried = retriedWithSystemService
            )
        ) {
            releaseRecognizer()
            retriedWithSystemService = true
            engine = DeviceSpeechEngine.SystemService
            launch(engine)
            return
        }
        releaseRecognizer()
        listener.onError(code)
    }

    private fun firstResult(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private inner class Callbacks(private val id: Int) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (id != generation) return
            ready = true
            listener.onReady()
            if (stopRequested) recognizer?.stopListening()
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (id != generation) return
            listener.onRms(rmsdB)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (id != generation) return
            val text = firstResult(partialResults)?.takeIf(String::isNotBlank) ?: return
            heardSpeech = true
            listener.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            if (id != generation) return
            val text = firstResult(results).orEmpty()
            releaseRecognizer()
            listener.onFinal(text)
        }

        override fun onError(error: Int) = handleError(id, error)

        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

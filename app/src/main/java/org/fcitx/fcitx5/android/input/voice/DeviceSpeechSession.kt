/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import timber.log.Timber
import java.util.Locale

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
 *
 * The recognizer instance is reused for the following starts of one session, because destroying
 * and re-creating it for every sentence leaves the recognition service busy or disconnected. An
 * instance that failed or never reported back is destroyed and replaced once per start.
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

    private val handler = Handler(Looper.getMainLooper())
    private var engine = environment.engine
    private var recognizer: SpeechRecognizer? = null
    private var recognizerEngine: DeviceSpeechEngine? = null
    private var generation = 0
    private var primaryLanguageTag = ""
    private var languageCandidates = emptyList<String>()
    private var languageIndex = 0
    private var ready = false
    private var heardSpeech = false
    private var stopRequested = false
    private var retriedWithSystemService = false
    private var recreated = false
    private var destroyed = false
    private var startTimeout: Runnable? = null
    private var pendingRestart: Runnable? = null

    fun start(languageTag: String) {
        check(!destroyed) { "Speech session is destroyed" }
        check(engine != DeviceSpeechEngine.Unavailable) { "No speech engine is available" }
        if (languageTag != primaryLanguageTag) {
            primaryLanguageTag = languageTag
            languageCandidates = DeviceSpeechLanguage.candidates(languageTag, Locale.getDefault())
            languageIndex = 0
        }
        stopRequested = false
        heardSpeech = false
        retriedWithSystemService = false
        recreated = false
        launch(engine)
    }

    /** Ends listening and waits for the final result. Safe before the recognizer is ready. */
    fun stop() {
        if (destroyed) return
        stopRequested = true
        if (ready) recognizer?.stopListening()
    }

    /** Drops the attempt without a result. The recognizer stays for the next start. */
    fun cancel() {
        generation++
        clearStartTimeout()
        clearPendingRestart()
        ready = false
        recognizer?.cancel()
    }

    fun destroy() {
        destroyed = true
        generation++
        clearStartTimeout()
        clearPendingRestart()
        releaseRecognizer()
    }

    private fun launch(target: DeviceSpeechEngine) {
        val id = ++generation
        clearStartTimeout()
        clearPendingRestart()
        ready = false
        val reusable = recognizer?.takeIf { recognizerEngine == target }
        val active = if (reusable != null) {
            reusable.cancel()
            reusable
        } else {
            releaseRecognizer()
            val created = try {
                create(target)
            } catch (exception: UnsupportedOperationException) {
                fail(id, SpeechRecognizer.ERROR_CLIENT)
                return
            }
            recognizer = created
            recognizerEngine = target
            created
        }
        active.setRecognitionListener(Callbacks(id))
        try {
            active.startListening(recognizerIntent(target))
        } catch (exception: SecurityException) {
            fail(id, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        scheduleStartTimeout(id)
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCandidates[languageIndex])
            if (target == DeviceSpeechEngine.SystemService) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    private fun releaseRecognizer() {
        val current = recognizer ?: return
        recognizer = null
        recognizerEngine = null
        ready = false
        current.destroy()
    }

    private fun scheduleStartTimeout(id: Int) {
        val timeout = Runnable {
            startTimeout = null
            if (id != generation) return@Runnable
            Timber.w("Device speech start timed out engine=%s", engine)
            fail(id, null)
        }
        startTimeout = timeout
        handler.postDelayed(timeout, START_TIMEOUT_MS)
    }

    private fun clearStartTimeout() {
        startTimeout?.let(handler::removeCallbacks)
        startTimeout = null
    }

    private fun scheduleRestart() {
        val id = ++generation
        val restart = Runnable {
            pendingRestart = null
            if (id == generation) launch(engine)
        }
        pendingRestart = restart
        handler.postDelayed(restart, RECREATE_DELAY_MS)
    }

    private fun clearPendingRestart() {
        pendingRestart?.let(handler::removeCallbacks)
        pendingRestart = null
    }

    /** A null [code] means the start never reported back. */
    private fun fail(id: Int, code: Int?) {
        if (id != generation) return
        clearStartTimeout()
        val decision = DeviceSpeechFailurePolicy.decide(
            engine = engine,
            environment = environment,
            errorCode = code,
            heardSpeech = heardSpeech,
            stopRequested = stopRequested,
            languageIndex = languageIndex,
            candidateCount = languageCandidates.size,
            retriedWithSystemService = retriedWithSystemService,
            recreated = recreated
        )
        when (decision) {
            is DeviceSpeechFailureDecision.NextLanguage -> {
                Timber.w(
                    "Device speech error=%s engine=%s decision=next-language index=%d",
                    code,
                    engine,
                    decision.index
                )
                releaseRecognizer()
                languageIndex = decision.index
                scheduleRestart()
            }
            DeviceSpeechFailureDecision.SystemService -> {
                Timber.w("Device speech error=%s engine=%s decision=system-service", code, engine)
                releaseRecognizer()
                retriedWithSystemService = true
                languageIndex = 0
                engine = DeviceSpeechEngine.SystemService
                launch(engine)
            }
            DeviceSpeechFailureDecision.Recreate -> {
                Timber.w("Device speech error=%s engine=%s decision=recreate", code, engine)
                releaseRecognizer()
                recreated = true
                scheduleRestart()
            }
            DeviceSpeechFailureDecision.Report -> {
                Timber.w("Device speech error=%s engine=%s decision=report", code, engine)
                // Hearing nothing is a normal end; the recognizer is still healthy and is reused.
                if (code != null && DeviceSpeechErrorKind.of(code) == DeviceSpeechErrorKind.NoSpeech) {
                    ready = false
                } else {
                    releaseRecognizer()
                }
                listener.onError(code ?: SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            }
        }
    }

    private fun firstResult(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private inner class Callbacks(private val id: Int) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (id != generation) return
            clearStartTimeout()
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
            clearStartTimeout()
            val text = firstResult(partialResults)?.takeIf(String::isNotBlank) ?: return
            heardSpeech = true
            listener.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            if (id != generation) return
            clearStartTimeout()
            ready = false
            listener.onFinal(firstResult(results).orEmpty())
        }

        override fun onError(error: Int) = fail(id, error)

        override fun onBeginningOfSpeech() {
            if (id != generation) return
            heardSpeech = true
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val START_TIMEOUT_MS = 5_000L
        const val RECREATE_DELAY_MS = 300L
    }
}

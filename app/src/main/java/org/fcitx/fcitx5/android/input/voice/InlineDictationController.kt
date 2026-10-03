/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.input.EditorIdentity
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.DictationStripUi

/**
 * Runs device dictation on the strip above the keyboard. It owns the recognizer session and the
 * strip's lifetime, and gives the microphone back as soon as the editor or the keyboard goes away.
 * Every method must be called on the main thread.
 */
internal class InlineDictationController(
    private val context: Context,
    private val service: FcitxInputMethodService,
    private val strip: DictationStripUi,
    private val languageTag: () -> String,
    private val onOpenChanged: (Boolean) -> Unit
) {
    private var session: DeviceSpeechSession? = null
    private var boundEditor: EditorIdentity? = null

    private val machine = InlineDictationMachine(
        recognizer = SessionRecognizer(),
        editor = ServiceEditor(),
        output = StripOutput(),
        timers = StripTimers(),
        languageTag = languageTag
    )

    private val finishTimeout = Runnable { machine.onFinishTimeout() }
    private val noticeTimeout = Runnable { machine.dismissServiceNotice() }
    private val deniedClose = Runnable { machine.close() }

    val isOpen: Boolean
        get() = machine.isOpen

    init {
        strip.onMic = machine::onMicPressed
        strip.onErase = machine::onErasePressed
        strip.onDone = machine::onDonePressed
    }

    /**
     * Starts dictation on the strip. Returns false when the strip cannot be used here, so the
     * caller can show the dictation panel, which explains why.
     */
    fun open(mode: DictationMode): Boolean {
        if (machine.isOpen) return true
        val environment = usableEnvironment() ?: return false
        if (hasMicrophonePermission()) {
            start(environment, mode)
            return true
        }
        return requestMicrophonePermission()
    }

    /** Continues after the microphone permission screen. Returns false when the panel must handle it. */
    fun resume(result: VoicePermissionResumeResult): Boolean {
        if (machine.isOpen) return true
        val environment = usableEnvironment() ?: return false
        if (result.granted && hasMicrophonePermission()) {
            start(environment, DictationMode.Continuous)
        } else {
            showPermissionDenied()
        }
        return true
    }

    fun finishPushToTalk() {
        machine.finishPushToTalk()
    }

    fun close() {
        machine.close()
    }

    /** A new input session ends dictation unless it is the same field starting again. */
    fun onEditorStarted(info: EditorInfo, restarting: Boolean) {
        if (!machine.isOpen) return
        val bound = boundEditor
        if (!restarting || bound == null || !bound.sameField(EditorIdentity.of(info))) close()
    }

    private fun usableEnvironment(): DeviceSpeechEnvironment? {
        if (!service.allowsTextInspectionFeatures()) return null
        return DeviceSpeechProbe.probe(context, service.allowsNetworkInputFeatures())
            .takeIf { it.engine != DeviceSpeechEngine.Unavailable }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun start(environment: DeviceSpeechEnvironment, mode: DictationMode) {
        boundEditor = service.currentInputEditorInfo?.let(EditorIdentity::of)
        session = DeviceSpeechSession(context, environment, SessionListener())
        onOpenChanged(true)
        val serviceNotice = environment.engine == DeviceSpeechEngine.SystemService
        machine.open(mode, serviceNotice)
        if (serviceNotice && machine.isOpen) strip.root.postDelayed(noticeTimeout, SERVICE_NOTICE_MS)
    }

    private fun requestMicrophonePermission(): Boolean {
        val info = service.currentInputEditorInfo ?: return false
        val cursor = service.currentInputSelection.start
        val target = VoiceTranscriptPolicy.bindEditor(
            packageName = info.packageName,
            fieldId = info.fieldId,
            inputType = info.inputType,
            selectionStart = cursor,
            selectionEnd = cursor
        ) ?: return false
        if (VoicePermissionCoordinator.request(context, target, skipOnlineDisclosure = true) == null) {
            showPermissionDenied()
        }
        return true
    }

    private fun showPermissionDenied() {
        onOpenChanged(true)
        machine.showPermissionDenied()
        strip.root.postDelayed(deniedClose, DENIED_NOTICE_MS)
    }

    private fun release() {
        strip.root.removeCallbacks(finishTimeout)
        strip.root.removeCallbacks(noticeTimeout)
        strip.root.removeCallbacks(deniedClose)
        session?.let {
            it.cancel()
            it.destroy()
        }
        session = null
        boundEditor = null
    }

    private inner class SessionRecognizer : DictationRecognizer {
        override fun start(languageTag: String) {
            session?.start(languageTag)
        }

        override fun stop() {
            session?.stop()
        }

        override fun cancel() {
            session?.cancel()
        }
    }

    private inner class SessionListener : DeviceSpeechSession.Listener {
        override fun onReady() = machine.onReady()
        override fun onPartial(text: String) = machine.onPartial(text)
        override fun onFinal(text: String) = machine.onFinal(text)
        override fun onError(code: Int) = machine.onError(code)
        override fun onRms(db: Float) = machine.onRms(db)
    }

    private inner class ServiceEditor : DictationEditor {
        override fun prepareForInsert(): Boolean = service.prepareDictationCommit()

        override fun textBeforeCursor(length: Int): String? =
            service.currentInputConnection?.getTextBeforeCursor(length, 0)?.toString()

        override fun insert(text: String): Boolean = service.commitToEditor(text)

        override fun eraseBeforeCursor(expected: String): Boolean =
            service.eraseDictatedText(expected)
    }

    private inner class StripOutput : DictationOutput {
        override fun render(state: DictationStripState) = strip.render(state)

        override fun closed() {
            release()
            onOpenChanged(false)
        }
    }

    private inner class StripTimers : DictationTimers {
        override fun armFinishTimeout() {
            strip.root.removeCallbacks(finishTimeout)
            strip.root.postDelayed(finishTimeout, FINISH_TIMEOUT_MS)
        }

        override fun cancelFinishTimeout() {
            strip.root.removeCallbacks(finishTimeout)
        }
    }

    private companion object {
        const val SERVICE_NOTICE_MS = 2_000L
        const val DENIED_NOTICE_MS = 2_500L
        const val FINISH_TIMEOUT_MS = 3_000L
    }
}

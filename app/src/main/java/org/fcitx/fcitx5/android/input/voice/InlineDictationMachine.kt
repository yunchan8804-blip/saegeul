/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

internal enum class DictationMode {
    /** Keeps listening sentence after sentence until the user finishes. */
    Continuous,

    /** Listens while the microphone button is held and finishes when it is released. */
    PushToTalk
}

internal enum class DictationPhase { Closed, Starting, Listening, Paused, Finishing }

internal enum class DictationPauseReason {
    User,
    Silence,
    Language,
    Busy,
    Network,
    Other,
    InsertFailed,
    PermissionDenied
}

/** Everything the strip needs to draw itself. */
internal data class DictationStripState(
    val phase: DictationPhase,
    val partial: String,
    val level: Float,
    val pauseReason: DictationPauseReason?,
    val serviceNotice: Boolean
)

internal interface DictationRecognizer {
    fun start(languageTag: String)

    /** Ends listening and waits for the final result. */
    fun stop()

    /** Drops the attempt without a result. */
    fun cancel()
}

internal interface DictationEditor {
    /** Confirms the composition in progress so the next read and write see the finished text. */
    fun prepareForInsert(): Boolean
    fun textBeforeCursor(length: Int): String?
    fun insert(text: String): Boolean

    /** Removes [expected] in front of the cursor, only while it is exactly what is there. */
    fun eraseBeforeCursor(expected: String): Boolean
}

internal interface DictationOutput {
    fun render(state: DictationStripState)
    fun closed()
}

internal interface DictationTimers {
    fun armFinishTimeout()
    fun cancelFinishTimeout()
}

/**
 * The strip's dictation flow without any Android type: what each recognizer result and each button
 * press does. The recognizer, the editor and the drawing are handed in so it can run on its own.
 */
internal class InlineDictationMachine(
    private val recognizer: DictationRecognizer,
    private val editor: DictationEditor,
    private val output: DictationOutput,
    private val timers: DictationTimers,
    private val languageTag: () -> String
) {
    private enum class AfterFinal { Pause, Close }

    var phase: DictationPhase = DictationPhase.Closed
        private set

    val isOpen: Boolean
        get() = phase != DictationPhase.Closed

    private var mode = DictationMode.Continuous
    private var afterFinal: AfterFinal? = null
    private var partial = ""
    private var level = 0f
    private var silentRounds = 0
    private var lastInserted: String? = null
    private var pauseReason: DictationPauseReason? = null
    private var serviceNotice = false

    fun open(mode: DictationMode, serviceNotice: Boolean) {
        check(phase == DictationPhase.Closed) { "Dictation is already open" }
        this.mode = mode
        this.serviceNotice = serviceNotice
        silentRounds = 0
        lastInserted = null
        startListening()
    }

    /** Shows why dictation cannot start, without any recognizer. */
    fun showPermissionDenied() {
        check(phase == DictationPhase.Closed) { "Dictation is already open" }
        mode = DictationMode.Continuous
        serviceNotice = false
        lastInserted = null
        pause(DictationPauseReason.PermissionDenied)
    }

    fun dismissServiceNotice() {
        if (!serviceNotice) return
        serviceNotice = false
        render()
    }

    fun onReady() {
        if (phase != DictationPhase.Starting) return
        phase = DictationPhase.Listening
        render()
    }

    fun onPartial(text: String) {
        if (!isRecognizing) return
        partial = text
        if (phase == DictationPhase.Starting) phase = DictationPhase.Listening
        render()
    }

    fun onRms(db: Float) {
        if (phase != DictationPhase.Listening) return
        level = DeviceSpeechLevel.fraction(db)
        render()
    }

    fun onFinal(text: String) {
        if (!isRecognizing) return
        finishSentence(text)
    }

    fun onError(code: Int) {
        if (!isRecognizing) return
        when (DeviceSpeechErrorKind.of(code)) {
            DeviceSpeechErrorKind.NoSpeech -> finishSentence("")
            DeviceSpeechErrorKind.Permission -> close()
            DeviceSpeechErrorKind.Language -> fail(DictationPauseReason.Language)
            DeviceSpeechErrorKind.Busy -> fail(DictationPauseReason.Busy)
            DeviceSpeechErrorKind.Network -> fail(DictationPauseReason.Network)
            DeviceSpeechErrorKind.Other -> fail(DictationPauseReason.Other)
        }
    }

    /** The recognizer did not answer a stop request: take what was heard so far. */
    fun onFinishTimeout() {
        if (phase != DictationPhase.Finishing) return
        recognizer.cancel()
        finishSentence(partial)
    }

    /** The microphone button on the strip: pause while listening, listen again while paused. */
    fun onMicPressed() {
        when (phase) {
            DictationPhase.Starting -> {
                recognizer.cancel()
                pause(DictationPauseReason.User)
            }
            DictationPhase.Listening -> beginFinishing(AfterFinal.Pause)
            DictationPhase.Paused -> {
                if (pauseReason == DictationPauseReason.PermissionDenied) return
                silentRounds = 0
                startListening()
            }
            DictationPhase.Finishing, DictationPhase.Closed -> Unit
        }
    }

    fun onDonePressed() {
        when (phase) {
            DictationPhase.Starting, DictationPhase.Paused -> close()
            DictationPhase.Listening -> beginFinishing(AfterFinal.Close)
            DictationPhase.Finishing -> afterFinal = AfterFinal.Close
            DictationPhase.Closed -> Unit
        }
    }

    /** Drops the sentence being heard; without one, removes the piece that was just typed in. */
    fun onErasePressed() {
        when (phase) {
            DictationPhase.Starting, DictationPhase.Listening -> {
                if (partial.isNotEmpty()) {
                    recognizer.cancel()
                    startListening()
                } else {
                    eraseLastInserted()
                }
            }
            DictationPhase.Paused -> eraseLastInserted()
            DictationPhase.Finishing, DictationPhase.Closed -> Unit
        }
    }

    /** The held microphone button was released. Other modes ignore it. */
    fun finishPushToTalk() {
        if (mode != DictationMode.PushToTalk) return
        when (phase) {
            DictationPhase.Starting, DictationPhase.Paused -> close()
            DictationPhase.Listening -> beginFinishing(AfterFinal.Close)
            DictationPhase.Finishing -> afterFinal = AfterFinal.Close
            DictationPhase.Closed -> Unit
        }
    }

    fun close() {
        if (phase == DictationPhase.Closed) return
        timers.cancelFinishTimeout()
        phase = DictationPhase.Closed
        partial = ""
        level = 0f
        afterFinal = null
        pauseReason = null
        serviceNotice = false
        recognizer.cancel()
        output.closed()
    }

    private val isRecognizing: Boolean
        get() = phase == DictationPhase.Starting ||
            phase == DictationPhase.Listening ||
            phase == DictationPhase.Finishing

    private fun startListening() {
        phase = DictationPhase.Starting
        partial = ""
        level = 0f
        afterFinal = null
        pauseReason = null
        recognizer.start(languageTag())
        render()
    }

    private fun beginFinishing(after: AfterFinal) {
        phase = DictationPhase.Finishing
        afterFinal = after
        level = 0f
        timers.armFinishTimeout()
        recognizer.stop()
        render()
    }

    private fun pause(reason: DictationPauseReason) {
        timers.cancelFinishTimeout()
        phase = DictationPhase.Paused
        pauseReason = reason
        partial = ""
        level = 0f
        afterFinal = null
        render()
    }

    private fun fail(reason: DictationPauseReason) {
        if (afterFinal == AfterFinal.Close) close() else pause(reason)
    }

    private fun finishSentence(text: String) {
        timers.cancelFinishTimeout()
        val sentence = VoiceTranscriptPolicy.normalize(text)
        var inserted = true
        if (sentence == null) {
            silentRounds++
        } else {
            silentRounds = 0
            inserted = insert(sentence)
        }
        val after = afterFinal
        when {
            after == AfterFinal.Close -> close()
            !inserted -> pause(DictationPauseReason.InsertFailed)
            after == AfterFinal.Pause -> pause(DictationPauseReason.User)
            silentRounds >= MAX_SILENT_ROUNDS -> pause(DictationPauseReason.Silence)
            else -> startListening()
        }
    }

    private fun insert(sentence: String): Boolean {
        if (!editor.prepareForInsert()) return false
        val piece = DictationInsertRule.compose(editor.textBeforeCursor(1).orEmpty(), sentence)
            ?: return true
        if (!editor.insert(piece)) return false
        lastInserted = piece
        return true
    }

    private fun eraseLastInserted() {
        val piece = lastInserted ?: return
        lastInserted = null
        editor.eraseBeforeCursor(piece)
    }

    private fun render() {
        if (phase == DictationPhase.Closed) return
        output.render(
            DictationStripState(
                phase = phase,
                partial = partial,
                level = level,
                pauseReason = pauseReason,
                serviceNotice = serviceNotice
            )
        )
    }

    private companion object {
        const val MAX_SILENT_ROUNDS = 3
    }
}

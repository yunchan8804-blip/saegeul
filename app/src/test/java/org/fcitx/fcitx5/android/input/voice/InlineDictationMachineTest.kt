/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRecognizer : DictationRecognizer {
    val calls = mutableListOf<String>()
    override fun start(languageTag: String) {
        calls += "start:$languageTag"
    }

    override fun stop() {
        calls += "stop"
    }

    override fun cancel() {
        calls += "cancel"
    }
}

private class FakeEditor : DictationEditor {
    val text = StringBuilder()
    var prepareResult = true
    var insertResult = true
    var eraseCalls = 0

    override fun prepareForInsert(): Boolean = prepareResult

    override fun textBeforeCursor(length: Int): String = text.takeLast(length).toString()

    override fun insert(text: String): Boolean {
        if (!insertResult) return false
        this.text.append(text)
        return true
    }

    override fun eraseBeforeCursor(expected: String): Boolean {
        eraseCalls++
        if (!DictationErasePolicy.canErase(textBeforeCursor(expected.length), expected)) return false
        text.setLength(text.length - expected.length)
        return true
    }
}

private class FakeOutput : DictationOutput {
    val states = mutableListOf<DictationStripState>()
    var closedCount = 0

    override fun render(state: DictationStripState) {
        states += state
    }

    override fun closed() {
        closedCount++
    }
}

private class FakeTimers : DictationTimers {
    var armed = false
    var armCount = 0

    override fun armFinishTimeout() {
        armed = true
        armCount++
    }

    override fun cancelFinishTimeout() {
        armed = false
    }
}

private class Rig {
    val recognizer = FakeRecognizer()
    val editor = FakeEditor()
    val output = FakeOutput()
    val timers = FakeTimers()
    var language = "ko-KR"
    val machine = InlineDictationMachine(recognizer, editor, output, timers) { language }

    val starts: Int
        get() = recognizer.calls.count { it.startsWith("start:") }

    val last: DictationStripState
        get() = output.states.last()

    fun listen(mode: DictationMode = DictationMode.Continuous) {
        machine.open(mode, serviceNotice = false)
        machine.onReady()
    }

    fun say(sentence: String) {
        machine.onPartial(sentence)
        machine.onFinal(sentence)
        machine.onReady()
    }

    fun silence() {
        machine.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        machine.onReady()
    }
}

class InlineDictationMachineTest {
    @Test
    fun openingStartsTheRecognizerInTheCurrentLanguageAndWaitsForReady() {
        val rig = Rig()
        rig.machine.open(DictationMode.Continuous, serviceNotice = false)

        assertEquals(listOf("start:ko-KR"), rig.recognizer.calls)
        assertEquals(DictationPhase.Starting, rig.machine.phase)
        rig.machine.onReady()
        assertEquals(DictationPhase.Listening, rig.machine.phase)
        assertEquals(DictationPhase.Listening, rig.last.phase)
    }

    @Test
    fun partialTextOnlyShowsOnTheStripAndNeverReachesTheEditor() {
        val rig = Rig()
        rig.listen()
        rig.machine.onPartial("안녕하")

        assertEquals("안녕하", rig.last.partial)
        assertEquals("", rig.editor.text.toString())
    }

    @Test
    fun aFinalSentenceGoesInAtOnceAndListeningStartsAgain() {
        val rig = Rig()
        rig.listen()
        rig.machine.onPartial("안녕하세요")
        rig.machine.onFinal("안녕하세요")

        assertEquals("안녕하세요", rig.editor.text.toString())
        assertEquals(2, rig.starts)
        assertEquals(DictationPhase.Starting, rig.machine.phase)
        assertEquals("", rig.last.partial)
    }

    @Test
    fun laterSentencesAreSeparatedBySingleSpaces() {
        val rig = Rig()
        rig.listen()
        rig.say("안녕하세요")
        rig.say("반갑습니다")
        rig.say("  또 만나요  ")

        assertEquals("안녕하세요 반갑습니다 또 만나요", rig.editor.text.toString())
        assertEquals(4, rig.starts)
    }

    @Test
    fun textTypedBetweenSentencesStaysAndTheNextSentenceFollowsIt() {
        val rig = Rig()
        rig.listen()
        rig.say("안녕하세요")
        rig.editor.text.append(" 직접 쓴 글")
        rig.say("이어서 말해요")

        assertEquals("안녕하세요 직접 쓴 글 이어서 말해요", rig.editor.text.toString())
        assertEquals(DictationPhase.Listening, rig.machine.phase)
    }

    @Test
    fun theMicrophoneButtonPausesAndTakesTheSentenceBeingHeard() {
        val rig = Rig()
        rig.listen()
        rig.machine.onPartial("잠깐만")
        rig.machine.onMicPressed()

        assertEquals(DictationPhase.Finishing, rig.machine.phase)
        assertEquals("stop", rig.recognizer.calls.last())
        assertTrue(rig.timers.armed)

        rig.machine.onFinal("잠깐만요")

        assertEquals("잠깐만요", rig.editor.text.toString())
        assertEquals(DictationPhase.Paused, rig.machine.phase)
        assertEquals(DictationPauseReason.User, rig.last.pauseReason)
        assertEquals(1, rig.starts)
        assertFalse(rig.timers.armed)
    }

    @Test
    fun theMicrophoneButtonListensAgainWhilePaused() {
        val rig = Rig()
        rig.listen()
        rig.machine.onMicPressed()
        rig.machine.onFinal("")
        assertEquals(DictationPhase.Paused, rig.machine.phase)

        rig.machine.onMicPressed()

        assertEquals(DictationPhase.Starting, rig.machine.phase)
        assertEquals(2, rig.starts)
        assertNull(rig.last.pauseReason)
    }

    @Test
    fun pausingBeforeTheRecognizerIsReadyDropsItAtOnce() {
        val rig = Rig()
        rig.machine.open(DictationMode.Continuous, serviceNotice = false)
        rig.machine.onMicPressed()

        assertEquals(DictationPhase.Paused, rig.machine.phase)
        assertEquals("cancel", rig.recognizer.calls.last())
    }

    @Test
    fun doneTakesTheSentenceBeingHeardAndClosesTheStrip() {
        val rig = Rig()
        rig.listen()
        rig.say("첫 문장")
        rig.machine.onPartial("마지막 문")
        rig.machine.onDonePressed()

        assertEquals(DictationPhase.Finishing, rig.machine.phase)
        rig.machine.onFinal("마지막 문장")

        assertEquals("첫 문장 마지막 문장", rig.editor.text.toString())
        assertFalse(rig.machine.isOpen)
        assertEquals(1, rig.output.closedCount)
        assertEquals("cancel", rig.recognizer.calls.last())
    }

    @Test
    fun doneWhilePausedClosesWithoutTouchingTheEditor() {
        val rig = Rig()
        rig.listen()
        rig.say("안녕")
        rig.machine.onMicPressed()
        rig.machine.onFinal("")
        rig.machine.onDonePressed()

        assertFalse(rig.machine.isOpen)
        assertEquals("안녕", rig.editor.text.toString())
    }

    @Test
    fun doneDuringFinishingTurnsAPauseIntoAClose() {
        val rig = Rig()
        rig.listen()
        rig.machine.onMicPressed()
        rig.machine.onDonePressed()
        rig.machine.onFinal("끝")

        assertEquals("끝", rig.editor.text.toString())
        assertFalse(rig.machine.isOpen)
    }

    @Test
    fun silenceListensAgainQuietlyButThreeInARowPauses() {
        val rig = Rig()
        rig.listen()
        rig.silence()
        rig.silence()
        assertEquals(DictationPhase.Listening, rig.machine.phase)
        assertEquals(3, rig.starts)

        rig.machine.onError(SpeechRecognizer.ERROR_NO_MATCH)

        assertEquals(DictationPhase.Paused, rig.machine.phase)
        assertEquals(DictationPauseReason.Silence, rig.last.pauseReason)
        assertEquals(3, rig.starts)
    }

    @Test
    fun aRecognizedSentenceResetsTheSilenceCount() {
        val rig = Rig()
        rig.listen()
        rig.silence()
        rig.silence()
        rig.say("아직 있어요")
        rig.silence()
        rig.silence()

        assertEquals(DictationPhase.Listening, rig.machine.phase)
        rig.machine.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals(DictationPhase.Paused, rig.machine.phase)
    }

    @Test
    fun anEmptyFinalCountsAsSilence() {
        val rig = Rig()
        rig.listen()
        repeat(3) {
            rig.machine.onFinal("")
            rig.machine.onReady()
        }

        assertEquals(DictationPhase.Paused, rig.machine.phase)
        assertEquals("", rig.editor.text.toString())
    }

    @Test
    fun resumingAfterASilencePauseStartsCountingFromZero() {
        val rig = Rig()
        rig.listen()
        repeat(3) { rig.silence() }
        rig.machine.onMicPressed()
        rig.machine.onReady()
        rig.silence()

        assertEquals(DictationPhase.Listening, rig.machine.phase)
    }

    @Test
    fun recognizerFailuresPauseWithTheirOwnReason() {
        val cases = mapOf(
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to DictationPauseReason.Language,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to DictationPauseReason.Language,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to DictationPauseReason.Busy,
            SpeechRecognizer.ERROR_NETWORK to DictationPauseReason.Network,
            SpeechRecognizer.ERROR_AUDIO to DictationPauseReason.Other
        )
        cases.forEach { (code, reason) ->
            val rig = Rig()
            rig.listen()
            rig.machine.onError(code)

            assertEquals(DictationPhase.Paused, rig.machine.phase)
            assertEquals(reason, rig.last.pauseReason)
            assertEquals(1, rig.starts)
        }
    }

    @Test
    fun aPermissionErrorClosesTheStrip() {
        val rig = Rig()
        rig.listen()
        rig.machine.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)

        assertFalse(rig.machine.isOpen)
        assertEquals(1, rig.output.closedCount)
    }

    @Test
    fun eraseWithASentenceBeingHeardDropsItAndListensAgain() {
        val rig = Rig()
        rig.listen()
        rig.say("남겨 둘 문장")
        rig.machine.onReady()
        rig.machine.onPartial("지울 문장")
        rig.machine.onErasePressed()

        assertEquals("남겨 둘 문장", rig.editor.text.toString())
        assertEquals(0, rig.editor.eraseCalls)
        assertEquals(listOf("cancel", "start:ko-KR"), rig.recognizer.calls.takeLast(2))
        assertEquals("", rig.last.partial)
        assertEquals(DictationPhase.Starting, rig.machine.phase)
    }

    @Test
    fun eraseWithoutASentenceRemovesTheLastPieceIncludingItsSpace() {
        val rig = Rig()
        rig.listen()
        rig.say("안녕하세요")
        rig.say("반갑습니다")
        rig.machine.onErasePressed()

        assertEquals("안녕하세요", rig.editor.text.toString())
    }

    @Test
    fun onlyTheLastPieceCanBeErasedAndOnlyOnce() {
        val rig = Rig()
        rig.listen()
        rig.say("안녕하세요")
        rig.say("반갑습니다")
        rig.machine.onErasePressed()
        rig.machine.onErasePressed()

        assertEquals("안녕하세요", rig.editor.text.toString())
        assertEquals(1, rig.editor.eraseCalls)
    }

    @Test
    fun eraseDoesNothingAfterTheUserTypedPastThePiece() {
        val rig = Rig()
        rig.listen()
        rig.say("안녕하세요")
        rig.editor.text.append("!!")
        rig.machine.onErasePressed()

        assertEquals("안녕하세요!!", rig.editor.text.toString())
        rig.editor.text.setLength(rig.editor.text.length - 2)
        rig.machine.onErasePressed()
        assertEquals("안녕하세요", rig.editor.text.toString())
        assertEquals(1, rig.editor.eraseCalls)
    }

    @Test
    fun eraseWorksWhilePausedToo() {
        val rig = Rig()
        rig.listen()
        rig.say("안녕하세요")
        rig.machine.onMicPressed()
        rig.machine.onFinal("")
        rig.machine.onErasePressed()

        assertEquals("", rig.editor.text.toString())
        assertEquals(DictationPhase.Paused, rig.machine.phase)
    }

    @Test
    fun holdingToTalkInsertsEverySentenceAndClosesOnRelease() {
        val rig = Rig()
        rig.listen(DictationMode.PushToTalk)
        rig.say("하나")
        rig.say("둘")
        rig.machine.onPartial("셋")
        rig.machine.finishPushToTalk()

        assertEquals(DictationPhase.Finishing, rig.machine.phase)
        assertEquals("stop", rig.recognizer.calls.last())
        rig.machine.onFinal("셋")

        assertEquals("하나 둘 셋", rig.editor.text.toString())
        assertFalse(rig.machine.isOpen)
        assertEquals(1, rig.output.closedCount)
    }

    @Test
    fun releasingBeforeTheRecognizerIsReadyClosesWithoutInsertingAnything() {
        val rig = Rig()
        rig.machine.open(DictationMode.PushToTalk, serviceNotice = false)
        rig.machine.finishPushToTalk()

        assertFalse(rig.machine.isOpen)
        assertEquals("", rig.editor.text.toString())
    }

    @Test
    fun releaseWithNothingSaidStillClosesAfterTheRecognizerAnswers() {
        val rig = Rig()
        rig.listen(DictationMode.PushToTalk)
        rig.machine.finishPushToTalk()
        rig.machine.onError(SpeechRecognizer.ERROR_NO_MATCH)

        assertFalse(rig.machine.isOpen)
        assertEquals("", rig.editor.text.toString())
    }

    @Test
    fun releaseIsIgnoredOutsideHoldToTalk() {
        val rig = Rig()
        rig.listen(DictationMode.Continuous)
        rig.machine.finishPushToTalk()

        assertEquals(DictationPhase.Listening, rig.machine.phase)
    }

    @Test
    fun aFailureWhileFinishingToCloseStillCloses() {
        val rig = Rig()
        rig.listen()
        rig.machine.onDonePressed()
        rig.machine.onError(SpeechRecognizer.ERROR_NETWORK)

        assertFalse(rig.machine.isOpen)
    }

    @Test
    fun aRecognizerThatNeverAnswersStopIsCutOffAndKeepsWhatWasHeard() {
        val rig = Rig()
        rig.listen()
        rig.machine.onPartial("들린 만큼")
        rig.machine.onDonePressed()
        rig.machine.onFinishTimeout()

        assertEquals("들린 만큼", rig.editor.text.toString())
        assertFalse(rig.machine.isOpen)
        assertTrue(rig.recognizer.calls.contains("cancel"))
    }

    @Test
    fun theFinishTimeoutMeansNothingOnceTheAnswerCame() {
        val rig = Rig()
        rig.listen()
        rig.machine.onMicPressed()
        rig.machine.onFinal("끝")
        rig.machine.onFinishTimeout()

        assertEquals("끝", rig.editor.text.toString())
        assertEquals(DictationPhase.Paused, rig.machine.phase)
    }

    @Test
    fun aSentenceThatCannotBeInsertedPausesInsteadOfLosingItSilently() {
        val rig = Rig()
        rig.listen()
        rig.editor.insertResult = false
        rig.say("넣을 수 없어요")

        assertEquals(DictationPhase.Paused, rig.machine.phase)
        assertEquals(DictationPauseReason.InsertFailed, rig.last.pauseReason)
        assertEquals(1, rig.starts)
    }

    @Test
    fun anEditorThatCannotFinishItsCompositionBlocksTheInsert() {
        val rig = Rig()
        rig.listen()
        rig.editor.prepareResult = false
        rig.say("넣을 수 없어요")

        assertEquals("", rig.editor.text.toString())
        assertEquals(DictationPauseReason.InsertFailed, rig.last.pauseReason)
    }

    @Test
    fun aChangedInputLanguageAppliesFromTheNextSentence() {
        val rig = Rig()
        rig.listen()
        rig.language = "en-US"
        rig.machine.onPartial("hello")
        rig.machine.onFinal("hello")

        assertEquals(listOf("start:ko-KR", "start:en-US"), rig.recognizer.calls)
    }

    @Test
    fun soundLevelFollowsTheMicrophoneOnlyWhileListening() {
        val rig = Rig()
        rig.machine.open(DictationMode.Continuous, serviceNotice = false)
        rig.machine.onRms(10f)
        assertEquals(0f, rig.last.level, 0f)

        rig.machine.onReady()
        rig.machine.onRms(10f)
        assertEquals(1f, rig.last.level, 0f)
    }

    @Test
    fun theServiceNoticeShowsFirstAndCanBeDismissed() {
        val rig = Rig()
        rig.machine.open(DictationMode.Continuous, serviceNotice = true)
        assertTrue(rig.last.serviceNotice)

        rig.machine.dismissServiceNotice()

        assertFalse(rig.last.serviceNotice)
    }

    @Test
    fun closingFromOutsideCancelsTheRecognizerAndIgnoresLateCallbacks() {
        val rig = Rig()
        rig.listen()
        rig.machine.close()

        assertEquals("cancel", rig.recognizer.calls.last())
        assertEquals(1, rig.output.closedCount)

        rig.machine.onFinal("늦게 온 말")
        rig.machine.onPartial("늦게 온 말")
        rig.machine.onError(SpeechRecognizer.ERROR_NETWORK)

        assertEquals("", rig.editor.text.toString())
        assertEquals(1, rig.output.closedCount)
        assertFalse(rig.machine.isOpen)
    }

    @Test
    fun aDeniedPermissionShowsItsNoticeAndCannotBeResumedByTheMicrophone() {
        val rig = Rig()
        rig.machine.showPermissionDenied()

        assertEquals(DictationPhase.Paused, rig.machine.phase)
        assertEquals(DictationPauseReason.PermissionDenied, rig.last.pauseReason)

        rig.machine.onMicPressed()

        assertEquals(DictationPhase.Paused, rig.machine.phase)
        assertEquals(0, rig.starts)

        rig.machine.close()
        assertFalse(rig.machine.isOpen)
        assertEquals(1, rig.output.closedCount)
    }

    @Test
    fun theMachineCanOpenAgainAfterItClosed() {
        val rig = Rig()
        rig.listen()
        rig.say("첫 번째")
        rig.machine.close()
        rig.listen()

        assertEquals(DictationPhase.Listening, rig.machine.phase)
        rig.machine.onErasePressed()
        assertEquals("첫 번째", rig.editor.text.toString())
    }
}

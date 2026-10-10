/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class OnDeviceSuggestionCoordinatorTest {

    @Test
    fun `late result cannot publish after cursor ABA`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val first = fixture.snapshot(revision = 1L, text = "회의")
        val moved = fixture.snapshot(revision = 2L, text = "회의", selectionStart = 1, selectionEnd = 1)
        val returned = fixture.snapshot(revision = 3L, text = "회의")

        fixture.coordinator.setEnabled(true)
        fixture.backend.ignoreCancellationForNextRequest = true
        fixture.observe(first)
        val firstReply = fixture.backend.awaitRequest(0)
        fixture.observe(moved)
        fixture.observe(returned)
        firstReply.complete(" 늦은 응답입니다.")
        val returnedReply = fixture.backend.awaitRequest(1)
        returnedReply.complete(" 새 응답입니다.")

        fixture.awaitReady()

        assertEquals(" 새", fixture.coordinator.candidates.first().insertion)
        assertEquals(OnDeviceSuggestionCoordinator.State.READY, fixture.coordinator.status.state)
    }

    @Test
    fun `cold backend skips generation without an error until it is ready`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.coordinator.setEnabled(true)
        fixture.backend.readyToGenerate = false
        fixture.observe(fixture.snapshot(revision = 1L, text = "회의"))
        OnDeviceSuggestionCoordinatorTest.awaitCondition {
            fixture.coordinator.status.state == OnDeviceSuggestionCoordinator.State.NO_CANDIDATE
        }
        assertTrue(fixture.backend.prompts.isEmpty())
        assertEquals(null, fixture.coordinator.status.errorCode)

        fixture.backend.readyToGenerate = true
        fixture.observe(fixture.snapshot(revision = 2L, text = "회의 자료"))
        fixture.backend.awaitRequest(0).complete(" 준비했습니다.")
        fixture.awaitReady()
        assertEquals(OnDeviceSuggestionCoordinator.State.READY, fixture.coordinator.status.state)
    }

    @Test
    fun `disable and reenable drains cleanup before another generation`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val first = fixture.snapshot(revision = 1L, text = "회의")
        val second = fixture.snapshot(revision = 2L, text = "회의 자료")

        fixture.coordinator.setEnabled(true)
        fixture.observe(first)
        fixture.backend.awaitRequest(0)
        fixture.coordinator.setEnabled(false)
        fixture.coordinator.setEnabled(true)
        fixture.observe(second)
        val secondReply = fixture.backend.awaitRequest(1)
        secondReply.complete(" 준비했습니다.")

        fixture.awaitReady()

        assertTrue(fixture.backend.closeCalls.get() >= 1)
        assertEquals(1, fixture.backend.maxActive.get())
    }

    @Test
    fun `only one backend generation is in flight across rapid observations`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val first = fixture.snapshot(revision = 1L, text = "오늘")
        val second = fixture.snapshot(revision = 2L, text = "다른 문장")
        val third = fixture.snapshot(revision = 3L, text = "또 다른 문장")

        fixture.coordinator.setEnabled(true)
        fixture.observe(first)
        val firstReply = fixture.backend.awaitRequest(0)
        fixture.observe(second)
        fixture.observe(third)
        firstReply.complete(" 회의입니다.")
        val lastReply = fixture.backend.awaitRequest(1)
        lastReply.complete(" 준비했습니다.")

        fixture.awaitReady()

        assertEquals(1, fixture.backend.maxActive.get())
        assertEquals(2, fixture.backend.requests.size)
    }

    @Test
    fun `new observation waits for in-flight request instead of cancelling it`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val a = fixture.snapshot(revision = 1L, text = "오늘")
        val b = fixture.snapshot(revision = 2L, text = "다른 문장")

        fixture.coordinator.setEnabled(true)
        fixture.observe(a)
        val aReply = fixture.backend.awaitRequest(0)
        val cancelCallsBefore = fixture.backend.cancelCalls.get()

        fixture.observe(b)
        delay(50L)
        assertEquals(cancelCallsBefore, fixture.backend.cancelCalls.get())
        assertEquals(1, fixture.backend.requests.size)

        aReply.complete(" 늦은 응답입니다.")
        val bReply = fixture.backend.awaitRequest(1)
        bReply.complete(" 준비했습니다.")

        fixture.awaitReady()

        assertEquals(
            " 준비했습니다.",
            fixture.coordinator.candidates.first { it.mode == OnDeviceSuggestionPolicy.Mode.SENTENCE }.insertion
        )
        assertEquals(cancelCallsBefore, fixture.backend.cancelCalls.get())
    }

    @Test
    fun `exact appended suffix uses session cache without a second request`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val initial = fixture.snapshot(revision = 1L, text = "내일")
        val appended = fixture.snapshot(revision = 2L, text = "내일 ")

        fixture.coordinator.setEnabled(true)
        fixture.observe(initial)
        fixture.backend.awaitRequest(0).complete(" 일정입니다.")
        fixture.awaitReady()
        fixture.observe(appended)
        fixture.awaitReady()

        assertEquals(1, fixture.backend.requests.size)
        assertEquals("일정입니다.", fixture.coordinator.candidates.first().insertion)
        assertEquals(OnDeviceSuggestionSession.Origin.CONTINUATION_CACHE, fixture.coordinator.candidates.first().origin)
    }

    @Test
    fun `candidate is consumed once and applies only its exact displayed substring`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val snapshot = fixture.snapshot()

        fixture.coordinator.setEnabled(true)
        fixture.observe(snapshot)
        fixture.backend.awaitRequest(0).complete(" 일정은 내일입니다.")
        fixture.awaitReady()
        val candidate = fixture.coordinator.candidates.first { it.mode == OnDeviceSuggestionPolicy.Mode.WORD }

        assertEquals(" 일정은", fixture.coordinator.takeForApply(candidate, snapshot))
        assertNull(fixture.coordinator.takeForApply(candidate, snapshot))
    }

    @Test
    fun `one stored sentence derives both word and sentence displays`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val snapshot = fixture.snapshot()

        fixture.coordinator.setEnabled(true)
        fixture.observe(snapshot)
        fixture.backend.awaitRequest(0).complete(" 일정은 내일입니다.")
        fixture.awaitReady()

        val word = fixture.coordinator.candidates.first { it.mode == OnDeviceSuggestionPolicy.Mode.WORD }
        val sentence = fixture.coordinator.candidates.first { it.mode == OnDeviceSuggestionPolicy.Mode.SENTENCE }
        assertEquals(" 일정은", word.insertion)
        assertEquals(" 일정은 내일입니다.", sentence.insertion)
        assertSame(OnDeviceSuggestionSession.Origin.GENERATED, sentence.origin)
    }

    @Test
    fun `word fallback is stored when sentence parsing fails`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val snapshot = fixture.snapshot()

        fixture.coordinator.setEnabled(true)
        fixture.observe(snapshot)
        fixture.backend.awaitRequest(0).complete(" 일정,")
        fixture.awaitReady()

        assertEquals(1, fixture.coordinator.candidates.size)
        assertEquals(OnDeviceSuggestionPolicy.Mode.WORD, fixture.coordinator.candidates.single().mode)
        assertEquals(" 일정,", fixture.coordinator.candidates.single().insertion)
    }

    @Test
    fun `expired session proposal cannot be applied`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val snapshot = fixture.snapshot()

        fixture.coordinator.setEnabled(true)
        fixture.observe(snapshot)
        fixture.backend.awaitRequest(0).complete(" 일정입니다.")
        fixture.awaitReady()
        val candidate = fixture.coordinator.candidates.first()
        fixture.nowMs += 30_000L

        assertNull(fixture.coordinator.takeForApply(candidate, snapshot))
    }

    @Test
    fun `repeated observation clears an expired displayed candidate`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val snapshot = fixture.snapshot()

        fixture.coordinator.setEnabled(true)
        fixture.observe(snapshot)
        fixture.backend.awaitRequest(0).complete(" 일정입니다.")
        fixture.awaitReady()
        fixture.nowMs += 30_000L
        fixture.observe(snapshot)

        assertTrue(fixture.coordinator.candidates.isEmpty())
        assertEquals(OnDeviceSuggestionCoordinator.State.NO_CANDIDATE, fixture.coordinator.status.state)
        assertEquals(1, fixture.backend.requests.size)
    }

    @Test
    fun `close failure remains terminal error instead of being overwritten by off`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        fixture.backend.closeFailure = OnDeviceSuggestionCoordinator.BackendException("NATIVE_CLOSE_FAILED")

        fixture.coordinator.setEnabled(true)
        fixture.coordinator.setEnabled(false)

        awaitCondition { fixture.coordinator.status.state == OnDeviceSuggestionCoordinator.State.ERROR }
        assertEquals("NATIVE_CLOSE_FAILED", fixture.coordinator.status.errorCode)
        fixture.observe(fixture.snapshot())
        assertEquals(0, fixture.backend.requests.size)
    }

    @Test
    fun `terminal native failure from an old request blocks its replacement`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val first = fixture.snapshot(revision = 1L, text = "회의")
        val second = fixture.snapshot(revision = 2L, text = "회의 자료")
        fixture.backend.throwTerminalOnCancellation = true

        fixture.coordinator.setEnabled(true)
        fixture.observe(first)
        fixture.backend.awaitRequest(0)
        fixture.coordinator.setEnabled(false)

        awaitCondition { fixture.coordinator.status.state == OnDeviceSuggestionCoordinator.State.ERROR }
        assertEquals("NATIVE_STOP_TIMEOUT", fixture.coordinator.status.errorCode)
        assertTrue(fixture.coordinator.candidates.isEmpty())

        fixture.coordinator.setEnabled(true)
        fixture.observe(second)

        assertEquals(1, fixture.backend.requests.size)
    }

    @Test
    fun `isTerminal is false until a terminal native failure latches it`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val snapshot = fixture.snapshot()
        fixture.backend.throwTerminalOnCancellation = true

        assertFalse(fixture.coordinator.isTerminal)

        fixture.coordinator.setEnabled(true)
        fixture.observe(snapshot)
        fixture.backend.awaitRequest(0)
        fixture.coordinator.setEnabled(false)

        awaitCondition { fixture.coordinator.isTerminal }
        assertEquals(OnDeviceSuggestionCoordinator.State.ERROR, fixture.coordinator.status.state)
    }

    @Test
    fun `isTerminal stays latched across a later disable and re-enable of this instance`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val first = fixture.snapshot(revision = 1L, text = "회의")
        fixture.backend.throwTerminalOnCancellation = true

        fixture.coordinator.setEnabled(true)
        fixture.observe(first)
        fixture.backend.awaitRequest(0)
        fixture.coordinator.setEnabled(false)
        awaitCondition { fixture.coordinator.isTerminal }

        fixture.coordinator.setEnabled(true)

        assertTrue(fixture.coordinator.isTerminal)
    }

    @Test
    fun `cleanup survives cancellation of the caller scope`() = runBlocking {
        val fixture = Fixture(coroutineContext)

        fixture.coordinator.setEnabled(true)
        fixture.coordinator.invalidate()
        fixture.owner.cancel()

        awaitCondition { fixture.backend.closeCalls.get() == 1 }
    }

    @Test
    fun `foreign candidate identity cannot consume the current proposal`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val snapshot = fixture.snapshot()

        fixture.coordinator.setEnabled(true)
        fixture.observe(snapshot)
        fixture.backend.awaitRequest(0).complete(" 일정입니다.")
        fixture.awaitReady()
        val candidate = fixture.coordinator.candidates.first()

        fixture.coordinator.invalidate()
        assertNull(fixture.coordinator.takeForApply(candidate, snapshot))
        assertFalse(fixture.coordinator.candidates.contains(candidate))
    }

    @Test
    fun `soft invalidate clears session and candidates but keeps backend open`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val first = fixture.snapshot(revision = 1L, text = "오늘")

        fixture.coordinator.setEnabled(true)
        fixture.observe(first)
        val firstReply = fixture.backend.awaitRequest(0)

        val closeCallsBefore = fixture.backend.closeCalls.get()
        val cancelCallsBefore = fixture.backend.cancelCalls.get()

        fixture.coordinator.invalidate(closeBackend = false)

        assertTrue(fixture.coordinator.candidates.isEmpty())
        assertEquals(OnDeviceSuggestionCoordinator.State.NO_CANDIDATE, fixture.coordinator.status.state)
        assertEquals(closeCallsBefore, fixture.backend.closeCalls.get())
        assertEquals(cancelCallsBefore, fixture.backend.cancelCalls.get())

        firstReply.complete(" 늦은 응답입니다.")
        delay(50L)
        assertTrue(fixture.coordinator.candidates.isEmpty())
        assertEquals(closeCallsBefore, fixture.backend.closeCalls.get())

        val second = fixture.snapshot(revision = 2L, text = "다른 문장")
        fixture.observe(second)
        fixture.backend.awaitRequest(1).complete(" 준비했습니다.")
        fixture.awaitReady()

        assertEquals(
            " 준비했습니다.",
            fixture.coordinator.candidates.first { it.mode == OnDeviceSuggestionPolicy.Mode.SENTENCE }.insertion
        )
        assertEquals(closeCallsBefore, fixture.backend.closeCalls.get())
    }

    @Test
    fun `duplicate observation during generation lets the in-flight job publish without restarting`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val first = fixture.snapshot(revision = 1L, text = "회의")
        val duplicate = fixture.snapshot(revision = 2L, text = "회의")

        fixture.coordinator.setEnabled(true)
        fixture.observe(first)
        val reply = fixture.backend.awaitRequest(0)

        fixture.observe(duplicate)
        reply.complete(" 준비했습니다.")
        fixture.awaitReady()

        assertEquals(1, fixture.backend.requests.size)
        assertEquals(
            " 준비했습니다.",
            fixture.coordinator.candidates.first { it.mode == OnDeviceSuggestionPolicy.Mode.SENTENCE }.insertion
        )
    }

    @Test
    fun `duplicate observation while ready keeps the displayed candidates`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val first = fixture.snapshot(revision = 1L, text = "회의")
        val duplicate = fixture.snapshot(revision = 2L, text = "회의")

        fixture.coordinator.setEnabled(true)
        fixture.observe(first)
        fixture.backend.awaitRequest(0).complete(" 준비했습니다.")
        fixture.awaitReady()
        val candidatesBefore = fixture.coordinator.candidates

        fixture.observe(duplicate)

        assertSame(candidatesBefore, fixture.coordinator.candidates)
        assertEquals(OnDeviceSuggestionCoordinator.State.READY, fixture.coordinator.status.state)
        assertEquals(1, fixture.backend.requests.size)
    }

    @Test
    fun `one-character text change during generation still discards the stale result`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val first = fixture.snapshot(revision = 1L, text = "회의")
        val changed = fixture.snapshot(revision = 2L, text = "회의실")

        fixture.coordinator.setEnabled(true)
        fixture.observe(first)
        val firstReply = fixture.backend.awaitRequest(0)
        fixture.observe(changed)
        firstReply.complete(" 늦은 응답입니다.")
        val secondReply = fixture.backend.awaitRequest(1)
        secondReply.complete(" 준비했습니다.")

        fixture.awaitReady()

        assertEquals(2, fixture.backend.requests.size)
        assertEquals(
            " 준비했습니다.",
            fixture.coordinator.candidates.first { it.mode == OnDeviceSuggestionPolicy.Mode.SENTENCE }.insertion
        )
    }

    @Test
    fun `duplicate text starts a new generation once idle`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val first = fixture.snapshot(revision = 1L, text = "회의")
        val again = fixture.snapshot(revision = 2L, text = "회의")

        fixture.coordinator.setEnabled(true)
        fixture.observe(first)
        fixture.backend.awaitRequest(0).complete(" 준비했습니다.")
        fixture.awaitReady()

        fixture.nowMs += 30_000L
        fixture.observe(first)
        assertTrue(fixture.coordinator.candidates.isEmpty())
        assertEquals(OnDeviceSuggestionCoordinator.State.NO_CANDIDATE, fixture.coordinator.status.state)

        fixture.observe(again)
        fixture.backend.awaitRequest(1).complete(" 다시 준비했습니다.")
        fixture.awaitReady()

        assertEquals(2, fixture.backend.requests.size)
        assertEquals(
            " 다시 준비했습니다.",
            fixture.coordinator.candidates.first { it.mode == OnDeviceSuggestionPolicy.Mode.SENTENCE }.insertion
        )
    }

    @Test
    fun `duplicate observation while ready lets a later apply use the new revision`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val first = fixture.snapshot(revision = 1L, text = "회의")
        val duplicate = fixture.snapshot(revision = 2L, text = "회의")

        fixture.coordinator.setEnabled(true)
        fixture.observe(first)
        fixture.backend.awaitRequest(0).complete(" 준비했습니다.")
        fixture.awaitReady()

        fixture.observe(duplicate)
        val candidate = fixture.coordinator.candidates.first { it.mode == OnDeviceSuggestionPolicy.Mode.SENTENCE }

        assertEquals(" 준비했습니다.", fixture.coordinator.takeForApply(candidate, duplicate))
    }

    @Test
    fun `duplicate observation during generation still applies successfully once ready`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val first = fixture.snapshot(revision = 1L, text = "회의")
        val duplicate = fixture.snapshot(revision = 2L, text = "회의")

        fixture.coordinator.setEnabled(true)
        fixture.observe(first)
        val reply = fixture.backend.awaitRequest(0)

        fixture.observe(duplicate)
        reply.complete(" 준비했습니다.")
        fixture.awaitReady()

        val candidate = fixture.coordinator.candidates.first { it.mode == OnDeviceSuggestionPolicy.Mode.SENTENCE }
        assertEquals(" 준비했습니다.", fixture.coordinator.takeForApply(candidate, duplicate))
    }

    @Test
    fun `hard invalidate closes backend`() = runBlocking {
        val fixture = Fixture(coroutineContext)
        val snapshot = fixture.snapshot()

        fixture.coordinator.setEnabled(true)
        fixture.observe(snapshot)
        fixture.backend.awaitRequest(0).complete(" 일정입니다.")
        fixture.awaitReady()

        val closeCallsBefore = fixture.backend.closeCalls.get()

        fixture.coordinator.invalidate()

        awaitCondition { fixture.backend.closeCalls.get() == closeCallsBefore + 1 }
        assertEquals(closeCallsBefore + 1, fixture.backend.closeCalls.get())
    }

    @Test
    fun `word fallback is shown only as a word chip and keeps that kind while typing on`() = runBlocking {
        val fixture = Fixture(coroutineContext)

        fixture.coordinator.setEnabled(true)
        fixture.observe(fixture.snapshot(revision = 1L, text = "내일"))
        fixture.backend.awaitRequest(0).complete(" 오전에 시작하고,")
        fixture.awaitReady()

        val word = fixture.coordinator.candidates.single()
        assertEquals(OnDeviceSuggestionPolicy.Mode.WORD, word.mode)
        assertEquals(" 오전에", word.insertion)

        fixture.observe(fixture.snapshot(revision = 2L, text = "내일 오"))
        fixture.awaitReady()

        assertEquals(1, fixture.backend.requests.size)
        val continued = fixture.coordinator.candidates.single()
        assertEquals(OnDeviceSuggestionPolicy.Mode.WORD, continued.mode)
        assertEquals("전에", continued.insertion)
    }

    @Test
    fun `sentence that is too short is stored only as a word chip`() = runBlocking {
        val fixture = Fixture(coroutineContext)

        fixture.coordinator.setEnabled(true)
        fixture.observe(fixture.snapshot(revision = 1L, text = "알겠습니다. "))
        fixture.backend.awaitRequest(0).complete("네~")
        fixture.awaitReady()

        val chip = fixture.coordinator.candidates.single()
        assertEquals(OnDeviceSuggestionPolicy.Mode.WORD, chip.mode)
        assertEquals("네~", chip.insertion)
    }

    @Test
    fun `reply that fails the quality gate publishes no candidate`() = runBlocking {
        val fixture = Fixture(coroutineContext)

        fixture.coordinator.setEnabled(true)
        fixture.observe(fixture.snapshot(revision = 1L, text = "내일 이 일을"))
        fixture.backend.awaitRequest(0).complete(" 할수있어요.")
        OnDeviceSuggestionCoordinatorTest.awaitCondition {
            fixture.coordinator.status.state == OnDeviceSuggestionCoordinator.State.NO_CANDIDATE
        }

        assertTrue(fixture.coordinator.candidates.isEmpty())
    }

    @Test
    fun `typo restatement becomes a replacement chip showing the whole sentence`() = runBlocking {
        val fixture = Fixture(coroutineContext, typoEvidenceOf("안녕하세여" to "안녕하세요"))
        val snapshot = fixture.snapshot(revision = 1L, text = "안녕하세여")

        fixture.coordinator.setEnabled(true)
        fixture.observe(snapshot)
        fixture.backend.awaitRequest(0).complete("안녕하세요.")
        fixture.awaitReady()

        val chip = fixture.coordinator.candidates.single()
        assertEquals(OnDeviceSuggestionPolicy.Mode.SENTENCE, chip.mode)
        assertEquals("안녕하세요.", chip.insertion)
        assertEquals("안녕하세여", chip.replaces)
        assertEquals("안녕하세요.", fixture.coordinator.takeForApply(chip, snapshot))
        assertNull(fixture.coordinator.takeForApply(chip, snapshot))
    }

    @Test
    fun `rewritten sentence without typo evidence publishes no chip`() = runBlocking {
        val fixture = Fixture(coroutineContext)

        fixture.coordinator.setEnabled(true)
        fixture.observe(fixture.snapshot(revision = 1L, text = "회의록 정리해서 공유드릴게"))
        fixture.backend.awaitRequest(0).complete("회의록을 정리해서 공유드리겠습니다.")
        OnDeviceSuggestionCoordinatorTest.awaitCondition {
            fixture.coordinator.status.state == OnDeviceSuggestionCoordinator.State.NO_CANDIDATE
        }

        assertTrue(fixture.coordinator.candidates.isEmpty())
    }

    @Test
    fun `replacement chip is dropped as soon as the typed text changes`() = runBlocking {
        val fixture = Fixture(coroutineContext, typoEvidenceOf("안녕하세여" to "안녕하세요"))

        fixture.coordinator.setEnabled(true)
        fixture.observe(fixture.snapshot(revision = 1L, text = "안녕하세여"))
        fixture.backend.awaitRequest(0).complete("안녕하세요.")
        fixture.awaitReady()

        fixture.observe(fixture.snapshot(revision = 2L, text = "안녕하세여 안"))

        assertTrue(fixture.coordinator.candidates.isEmpty())
        fixture.backend.awaitRequest(1)
        assertEquals(2, fixture.backend.requests.size)
    }

    @Test
    fun `exact echo still appends only the continuation`() = runBlocking {
        val fixture = Fixture(coroutineContext)

        fixture.coordinator.setEnabled(true)
        fixture.observe(fixture.snapshot(revision = 1L, text = "회의 자료를"))
        fixture.backend.awaitRequest(0).complete("회의 자료를 공유합니다.")
        fixture.awaitReady()

        val chip = fixture.coordinator.candidates.first { it.mode == OnDeviceSuggestionPolicy.Mode.SENTENCE }
        assertEquals(" 공유합니다.", chip.insertion)
        assertNull(chip.replaces)
    }

    private class Fixture(context: CoroutineContext, typoEvidence: TypoEvidence = NO_TYPO_EVIDENCE) {
        var nowMs = 0L
        val owner = SupervisorJob()
        val backend = DeferredBackend()
        private val current = CopyOnWriteArrayList<OnDeviceSuggestionSession.Snapshot>()
        val coordinator = OnDeviceSuggestionCoordinator(
            scope = CoroutineScope(context.minusKey(kotlinx.coroutines.Job) + owner),
            session = OnDeviceSuggestionSession(),
            backend = backend,
            typoEvidence = typoEvidence,
            clockMs = { nowMs },
            isCurrent = { snapshot -> current.lastOrNull() == snapshot },
            onChanged = {}
        )

        fun snapshot(
            revision: Long = 1L,
            text: String = "회의",
            selectionStart: Int = text.length,
            selectionEnd: Int = text.length
        ): OnDeviceSuggestionSession.Snapshot = OnDeviceSuggestionSession.Snapshot(
            scope = OnDeviceSuggestionSession.Scope("com.example.app", 7, 9L),
            revision = revision,
            textBeforeCursor = text,
            selectionStart = selectionStart,
            selectionEnd = selectionEnd
        )

        fun observe(snapshot: OnDeviceSuggestionSession.Snapshot) {
            current += snapshot
            coordinator.observe(snapshot, input(snapshot))
        }

        fun input(snapshot: OnDeviceSuggestionSession.Snapshot) = OnDeviceSuggestionPolicy.Input(
            textBeforeCursor = snapshot.textBeforeCursor,
            packageName = snapshot.scope.packageName,
            inputType = 0,
            imeAction = 0,
            mode = OnDeviceSuggestionPolicy.Mode.SENTENCE
        )

        suspend fun awaitReady() {
            OnDeviceSuggestionCoordinatorTest.awaitCondition {
                coordinator.status.state == OnDeviceSuggestionCoordinator.State.READY
            }
        }
    }

    private class DeferredBackend : OnDeviceSuggestionCoordinator.Backend {
        val requests = CopyOnWriteArrayList<CompletableDeferred<String>>()
        val prompts = CopyOnWriteArrayList<String>()
        val closeCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()
        private val active = AtomicInteger()
        val maxActive = AtomicInteger()
        @Volatile
        var closeFailure: Throwable? = null
        @Volatile
        var throwTerminalOnCancellation: Boolean = false
        @Volatile
        var ignoreCancellationForNextRequest: Boolean = false
        @Volatile
        var readyToGenerate: Boolean = true

        override fun isReadyToGenerate(): Boolean = readyToGenerate

        override suspend fun generate(prompt: String): String {
            prompts += prompt
            val activeNow = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, activeNow) }
            val reply = CompletableDeferred<String>()
            requests += reply
            val ignoreCancellation = ignoreCancellationForNextRequest.also {
                ignoreCancellationForNextRequest = false
            }
            return try {
                if (ignoreCancellation) {
                    withContext(NonCancellable) { reply.await() }
                } else {
                    reply.await()
                }
            } catch (error: CancellationException) {
                if (throwTerminalOnCancellation) {
                    throw OnDeviceSuggestionCoordinator.BackendException("NATIVE_STOP_TIMEOUT")
                }
                throw error
            } finally {
                active.decrementAndGet()
            }
        }

        override fun cancel() {
            cancelCalls.incrementAndGet()
        }

        override suspend fun close() {
            closeCalls.incrementAndGet()
            closeFailure?.let { throw it }
        }

        suspend fun awaitRequest(index: Int): CompletableDeferred<String> {
            OnDeviceSuggestionCoordinatorTest.awaitCondition { requests.size > index }
            return requests[index]
        }
    }

    private companion object {
        suspend fun awaitCondition(predicate: () -> Boolean) {
            repeat(500) {
                if (predicate()) return
                delay(10L)
            }
            throw AssertionError("Timed out waiting for condition")
        }
    }
}

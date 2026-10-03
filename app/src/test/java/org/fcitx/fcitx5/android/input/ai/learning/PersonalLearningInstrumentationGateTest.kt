/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.learning

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PersonalLearningInstrumentationGateTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        PersonalLearningInstrumentationGate.resumeAfterInstrumentation()
        scope.cancel()
    }

    @Test
    fun `pauseForInstrumentation toggles the switch until resumeAfterInstrumentation`() {
        assertFalse("기본 상태는 정지가 아니어야 합니다.", PersonalLearningInstrumentationGate.isPaused)

        PersonalLearningInstrumentationGate.pauseForInstrumentation()
        assertTrue(PersonalLearningInstrumentationGate.isPaused)

        PersonalLearningInstrumentationGate.resumeAfterInstrumentation()
        assertFalse(PersonalLearningInstrumentationGate.isPaused)
    }

    @Test
    fun `learning is not queued while paused and runs again after resume`() = runBlocking<Unit> {
        val executed = AtomicInteger()

        PersonalLearningInstrumentationGate.pauseForInstrumentation()
        val skipped = launchSerializedLearningUnlessPaused(scope, previous = null) { executed.incrementAndGet() }
        assertNull("정지 중에는 학습 작업이 큐에 들어가면 안 됩니다.", skipped)
        assertEquals(0, executed.get())

        PersonalLearningInstrumentationGate.resumeAfterInstrumentation()
        val queued = launchSerializedLearningUnlessPaused(scope, previous = null) { executed.incrementAndGet() }
        assertNotNull("재개 뒤에는 학습 작업이 큐에 들어가야 합니다.", queued)
        withTimeout(5_000) { queued!!.join() }
        assertEquals(1, executed.get())
    }
}

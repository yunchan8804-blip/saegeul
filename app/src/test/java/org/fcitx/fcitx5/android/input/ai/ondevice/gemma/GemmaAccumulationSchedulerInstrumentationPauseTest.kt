/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaAccumulationSchedulerInstrumentationPauseTest {

    @After
    fun tearDown() {
        GemmaAccumulationScheduler.resumeAfterInstrumentation()
    }

    @Test
    fun `pauseForInstrumentation blocks scheduling until resumeAfterInstrumentation`() {
        assertFalse(
            "기본 상태는 정지가 아니어야 합니다.",
            GemmaAccumulationScheduler.isPausedForInstrumentation()
        )

        GemmaAccumulationScheduler.pauseForInstrumentation()
        assertTrue(
            "pauseForInstrumentation 뒤에는 새 스케줄 요청이 무시되어야 합니다.",
            GemmaAccumulationScheduler.isPausedForInstrumentation()
        )

        GemmaAccumulationScheduler.resumeAfterInstrumentation()
        assertFalse(
            "resumeAfterInstrumentation 뒤에는 스케줄 요청이 다시 허용되어야 합니다.",
            GemmaAccumulationScheduler.isPausedForInstrumentation()
        )
    }
}

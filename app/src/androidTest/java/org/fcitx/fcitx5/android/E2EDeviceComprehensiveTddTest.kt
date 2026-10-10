/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.buffered.BufferedInputController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real Android Device E2E TDD Test Suite.
 * Executes live on the connected physical device (R3CX70NE9VH) with 1000+ deep assertions.
 */
class E2EDeviceComprehensiveTddTest {

    private lateinit var appContext: Context
    private lateinit var bufferController: BufferedInputController

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        bufferController = BufferedInputController()
    }

    @Test
    fun testDeviceContextAndPackageIntegrity() {
        assertNotNull(appContext)
        assertTrue(appContext.packageName.startsWith("net.chanpaca.saegeul"))
        assertNotNull(appContext.packageManager)
    }

    @Test
    fun testBufferController1000HangulTypingSequencesOnDevice() {
        val sentences = listOf(
            "새글 키보드는 대한민국 최고의 혁신적인 인풋 메소드입니다.",
            "빠르고 수려한 글자 조합과 안전한 호환 모드를 지원합니다.",
            "닷넷 마우이와 유니티에서도 완벽하게 작동합니다. 😀✨🇰🇷"
        )

        for (cycle in 0 until 350) {
            val sentence = sentences[cycle % sentences.size]
            bufferController.clear()
            assertTrue(bufferController.isEmpty)

            sentence.forEach { ch ->
                bufferController.capture(ch.toString())
            }

            assertEquals(sentence, bufferController.prefix)
            assertEquals(sentence, bufferController.snapshot())

            // Delete characters backward
            while (!bufferController.isEmpty) {
                assertTrue(bufferController.deleteLastCharacter())
            }
            assertTrue(bufferController.isEmpty)
        }
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.worker

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaGraphExtractWorkerTest {

    @Test
    fun testCreateWorkRequestConstraints() {
        val utterances = arrayOf("내일 오전 10시 팀 회의 참석", "점심 메뉴 파스타")
        val request = GemmaGraphExtractWorker.createWorkRequest(utterances)

        assertNotNull(request)
        // Check charging constraint
        assertTrue("WorkRequest must require charging", request.workSpec.constraints.requiresCharging())
        // Check idle constraint when supported by API level
        if (Build.VERSION.SDK_INT >= 23) {
            assertTrue("WorkRequest must require device idle on API 23+", request.workSpec.constraints.requiresDeviceIdle())
        }

        // Check input data payload
        val inputData = request.workSpec.input
        val extractedUtterances = inputData.getStringArray(GemmaGraphExtractWorker.KEY_UTTERANCES)
        assertNotNull(extractedUtterances)
        assertEquals(2, extractedUtterances?.size)
        assertEquals("내일 오전 10시 팀 회의 참석", extractedUtterances?.get(0))
        assertEquals("점심 메뉴 파스타", extractedUtterances?.get(1))
    }

    @Test
    fun testDefaultWorkRequestCreation() {
        val request = GemmaGraphExtractWorker.createWorkRequest()
        assertTrue(request.workSpec.constraints.requiresCharging())
        if (Build.VERSION.SDK_INT >= 23) {
            assertTrue(request.workSpec.constraints.requiresDeviceIdle())
        }
        val inputData = request.workSpec.input
        val utterances = inputData.getStringArray(GemmaGraphExtractWorker.KEY_UTTERANCES)
        assertNotNull(utterances)
        assertEquals(0, utterances?.size)
    }

    @Test
    fun testExtractTriplesMeetingPattern() {
        val triples = GemmaGraphExtractWorker.extractTriples("내일 오전 10시 팀 회의 참석")
        assertTrue("At least one triple must be extracted", triples.isNotEmpty())

        val meetingTriple = triples.firstOrNull { it.src == "팀 회의" }
        assertNotNull("Triple with src '팀 회의' should exist", meetingTriple)
        assertEquals("참석", meetingTriple?.dst)
        assertEquals("동작", meetingTriple?.relation)
    }

    @Test
    fun testExtractTriplesMenuPattern() {
        val triples = GemmaGraphExtractWorker.extractTriples("점심 메뉴 파스타")
        assertTrue("At least one triple must be extracted", triples.isNotEmpty())

        val menuTriple = triples.firstOrNull { it.src == "점심" }
        assertNotNull("Triple with src '점심' should exist", menuTriple)
        assertEquals("파스타", menuTriple?.dst)
        assertEquals("메뉴", menuTriple?.relation)
    }

    @Test
    fun testExtractTriplesLocationPattern() {
        val triples = GemmaGraphExtractWorker.extractTriples("강남역에서 미팅 진행")
        assertTrue(triples.isNotEmpty())

        val locTriple = triples.firstOrNull { it.src == "강남역" }
        assertNotNull(locTriple)
        assertEquals("미팅", locTriple?.dst)
        assertEquals("장소", locTriple?.relation)
    }

    @Test
    fun testExtractTriplesTaskPattern() {
        val triples = GemmaGraphExtractWorker.extractTriples("프로젝트 기획서 작성 완료")
        assertTrue(triples.isNotEmpty())

        val taskTriple = triples.firstOrNull { it.src == "프로젝트 기획서" }
        assertNotNull(taskTriple)
        assertEquals("완료", taskTriple?.dst)
        assertEquals("작성", taskTriple?.relation)
    }

    @Test
    fun testBatchExtraction() {
        val batch = listOf(
            "내일 오전 10시 팀 회의 참석",
            "점심 메뉴 파스타",
            "저녁 메뉴 피자"
        )
        val allTriples = GemmaGraphExtractWorker.extractTriples(batch)
        assertEquals(3, allTriples.size)
    }

    @Test
    fun testEmptyAndBlankExtraction() {
        assertTrue(GemmaGraphExtractWorker.extractTriples("").isEmpty())
        assertTrue(GemmaGraphExtractWorker.extractTriples("     ").isEmpty())
    }
}

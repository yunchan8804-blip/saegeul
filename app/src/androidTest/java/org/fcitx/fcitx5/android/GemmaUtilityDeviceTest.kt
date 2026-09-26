/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.input.ai.ImmediateContextualPredictions
import org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedMaterialPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedSentenceBank
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class GemmaUtilityDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun measureIndependentPublicInputs() = runBlocking(Dispatchers.Default) {
        measurePublicInputs(
            evidenceKey = "gemmaUtilityEvidence",
            fixtureSet = "independent_public_evaluation",
            inputs = INDEPENDENT_INPUTS,
            controlInputs = CONTROL_INPUTS,
            terminalBoundaryId = INDEPENDENT_TERMINAL_BOUNDARY_ID,
            includePlaintextInput = true
        )
    }

    @Test
    fun measureHeldOutPublicInputs() = runBlocking(Dispatchers.Default) {
        measurePublicInputs(
            evidenceKey = "gemmaHeldOutUtilityEvidence",
            fixtureSet = "held_out_public_evaluation",
            inputs = HELD_OUT_INPUTS,
            controlInputs = emptyList(),
            terminalBoundaryId = HELD_OUT_TERMINAL_BOUNDARY_ID,
            includePlaintextInput = false
        )
    }

    private suspend fun measurePublicInputs(
        evidenceKey: String,
        fixtureSet: String,
        inputs: List<InputCase>,
        controlInputs: List<InputCase>,
        terminalBoundaryId: String,
        includePlaintextInput: Boolean
    ) {
        val context = instrumentation.targetContext
        val app = context.applicationContext as FcitxApplication
        val store = GemmaAccumulationStore.get(context)
        val bank = app.generatedSentenceBank
        val beforeState = store.load()
        val nativeGeneratingBefore = OnDeviceGenerationControl.isGenerating
        assertFalse("독립 공개 입력 평가는 자동 준비가 꺼진 상태에서만 실행합니다.", beforeState.enabled)
        assertFalse("독립 공개 입력 평가 중 native 생성이 실행 중입니다.", nativeGeneratingBefore)

        bank.load()
        val beforeCount = bank.sentenceCount
        assertFixtureSet(inputs, terminalBoundaryId, fixtureSet)
        if (fixtureSet == "held_out_public_evaluation") {
            HELD_OUT_INPUTS.forEach { heldOut ->
                assertTrue(
                    "비공개 held-out 입력이 기존 공개 평가 fixture와 startsWith로 겹칩니다: ${heldOut.id}",
                    INDEPENDENT_INPUTS.none { existing ->
                        heldOut.prefix.startsWith(existing.prefix) || existing.prefix.startsWith(heldOut.prefix)
                    }
                )
            }
        }

        val completionDenominator = inputs.count { it.caseKind == COMPLETION }
        val boundaryDenominator = inputs.count { it.caseKind == TERMINAL_BOUNDARY }
        assertEquals(
            "completion 분모가 fixture와 일치하지 않습니다.",
            COMPLETION_CASE_COUNT,
            completionDenominator
        )

        val independent = JSONArray()
        inputs.forEach { input ->
            val denominator = if (input.caseKind == COMPLETION) completionDenominator else boundaryDenominator
            val measurement = measure(bank, input, context.packageName, denominator, includePlaintextInput)
            independent.put(measurement)
            reportFixtureEvidence(evidenceKey, fixtureSet, measurement)
            assertTerminalBoundary(input, measurement)
        }
        val controlMeasurements = JSONArray()
        controlInputs.forEach { input ->
            val measurement = measure(bank, input, context.packageName, controlInputs.size, includePlaintextInput)
            controlMeasurements.put(measurement)
            reportFixtureEvidence(evidenceKey, fixtureSet, measurement)
        }

        bank.load()
        val afterState = store.load()
        val nativeGeneratingAfter = OnDeviceGenerationControl.isGenerating
        assertEquals("읽기 전용 공개 입력 평가가 은행 수를 변경했습니다.", beforeCount, bank.sentenceCount)
        assertEquals(
            "읽기 전용 공개 입력 평가가 자동 공개 문맥 순번을 변경했습니다.",
            beforeState.openSequence,
            afterState.openSequence
        )
        assertEquals("읽기 전용 공개 입력 평가가 자동 준비 상태를 변경했습니다.", beforeState.enabled, afterState.enabled)
        assertFalse("읽기 전용 공개 입력 평가가 native 생성을 시작했습니다.", nativeGeneratingAfter)

        instrumentation.sendStatus(0, Bundle().apply {
            putString(
                evidenceKey,
                JSONObject()
                    .put("scope", "public generated bank lookup")
                    .put("notImeDisplayOrHumanUtilityScore", true)
                    .put("fixtureSet", fixtureSet)
                    .put("fixtureUsage", "evaluation_only_not_actual_usage_statistics")
                    .put("heldOutDuringDevelopment", fixtureSet == "held_out_public_evaluation")
                    .put("actualUserInput", false)
                    .put("providedToGenerationOrBank", false)
                    .put("independent", independent)
                    .put("control", controlMeasurements)
                    .put("independentCount", inputs.size)
                    .put("completionDenominator", completionDenominator)
                    .put("terminalBoundaryDenominator", boundaryDenominator)
                    .put("controlDenominator", controlInputs.size)
                    .put("terminalBoundaryCaseId", terminalBoundaryId)
                    .put("fixtureFingerprints", fixtureFingerprints(inputs, controlInputs, completionDenominator, boundaryDenominator))
                    .put("fixtureSha256", fixtureSha256(inputs, controlInputs))
                    .put("beforeCount", beforeCount)
                    .put("afterCount", bank.sentenceCount)
                    .put("openSequenceBefore", beforeState.openSequence)
                    .put("openSequenceAfter", afterState.openSequence)
                    .put("enabledBefore", beforeState.enabled)
                    .put("enabledAfter", afterState.enabled)
                    .put("nativeGeneratingBefore", nativeGeneratingBefore)
                    .put("nativeGeneratingAfter", nativeGeneratingAfter)
                    .toString()
            )
        })
    }

    private fun assertFixtureSet(inputs: List<InputCase>, terminalBoundaryId: String, fixtureSet: String) {
        assertEquals("$fixtureSet fixture는 24개여야 합니다.", 24, inputs.size)
        assertEquals("$fixtureSet fixture 식별자는 고유해야 합니다.", 24, inputs.map(InputCase::id).toSet().size)
        assertEquals("$fixtureSet fixture prefix는 고유해야 합니다.", 24, inputs.map(InputCase::prefix).toSet().size)
        assertEquals("$fixtureSet 완료 전 fixture는 23개여야 합니다.", COMPLETION_CASE_COUNT, inputs.count { it.caseKind == COMPLETION })
        assertEquals("$fixtureSet 완료 후 경계 fixture는 1개여야 합니다.", 1, inputs.count { it.caseKind == TERMINAL_BOUNDARY })
        assertTrue("$fixtureSet 완료 후 경계 fixture ID가 일치하지 않습니다.", inputs.any { it.id == terminalBoundaryId && it.caseKind == TERMINAL_BOUNDARY })
        inputs.forEach { input ->
            assertTrue(
                "$fixtureSet 입력이 legacy 20 prefix와 startsWith로 겹칩니다: ${input.id}",
                GeneratedMaterialPolicy.PREFIXES.none { legacy ->
                    input.prefix.startsWith(legacy) || legacy.startsWith(input.prefix)
                }
            )
        }
    }

    private fun assertTerminalBoundary(input: InputCase, measurement: JSONObject) {
        if (input.caseKind != TERMINAL_BOUNDARY) return
        assertEquals("완성 문장 뒤 공백에서는 공개 은행 후보가 없어야 합니다: ${input.id}", 0, measurement.getInt("candidateCount"))
        assertEquals(
            "완성 문장 뒤 공백에서는 실서비스 공개 생성 후보가 없어야 합니다: ${input.id}",
            0,
            measurement.getJSONArray("generatedPredictions").length()
        )
    }

    private fun reportFixtureEvidence(evidenceKey: String, fixtureSet: String, measurement: JSONObject) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString(
                evidenceKey,
                JSONObject()
                    .put("event", "fixture_measured_before_terminal_assertion")
                    .put("fixtureSet", fixtureSet)
                    .put("fixture", measurement)
                    .toString()
            )
        })
    }

    private fun fixtureFingerprints(
        inputs: List<InputCase>,
        controls: List<InputCase>,
        completionDenominator: Int,
        boundaryDenominator: Int
    ): JSONArray = JSONArray().apply {
        inputs.forEach { input ->
            put(
                JSONObject()
                    .put("id", input.id)
                    .put("caseKind", input.caseKind)
                    .put("denominator", if (input.caseKind == COMPLETION) completionDenominator else boundaryDenominator)
                    .put("inputSha256", sha256(input.prefix))
            )
        }
        controls.forEach { input ->
            put(
                JSONObject()
                    .put("id", input.id)
                    .put("caseKind", input.caseKind)
                    .put("denominator", controls.size)
                    .put("inputSha256", sha256(input.prefix))
            )
        }
    }

    private fun fixtureSha256(inputs: List<InputCase>, controls: List<InputCase>): String = sha256(
        (inputs + controls).joinToString(prefix = "[", postfix = "]") { input ->
            "{\"id\":${JSONObject.quote(input.id)},\"caseKind\":${JSONObject.quote(input.caseKind)},\"prefix\":${JSONObject.quote(input.prefix)}}"
        }
    )

    private fun measure(
        bank: GeneratedSentenceBank,
        input: InputCase,
        packageName: String,
        denominator: Int,
        includePlaintextInput: Boolean
    ): JSONObject {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val matches = bank.complete(input.prefix, CANDIDATE_LIMIT)
        val lookupUs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000L
        val candidates = JSONArray()
        matches.forEachIndexed { index, match ->
            val full = input.prefix + match.suffix
            assertFalse("공개 은행 후보 suffix에 PII가 포함되었습니다: ${input.id}", KoreanPiiScrubber.containsPii(match.suffix))
            assertFalse("공개 은행 후보 full에 PII가 포함되었습니다: ${input.id}", KoreanPiiScrubber.containsPii(full))
            candidates.put(JSONObject()
                .put("suffix", match.suffix)
                .put("rank", index + 1)
                .put("lookupUs", lookupUs)
                .put("evidence", match.evidence.name)
                .put("matchedTokens", match.matchedTokens)
                .put("joinMode", match.joinMode.name)
                .apply { if (includePlaintextInput) put("full", full) })
        }
        val generatedPredictions = ImmediateContextualPredictions.collect(
            input = ImmediateContextualPredictions.Input(input.prefix, packageName, 0L, CANDIDATE_LIMIT),
            sentencePackLookup = null,
            generatedSentenceLookup = bank::complete,
            generatedSpacingLookup = null
        )
        val generatedPredictionsJson = JSONArray()
        generatedPredictions.forEachIndexed { index, prediction ->
            val full = input.prefix + prediction.text
            assertEquals("실서비스 경로 후보 source가 ondevice_generated가 아닙니다: ${input.id}", GENERATED_SOURCE, prediction.source)
            assertFalse("실서비스 경로 후보 text에 PII가 포함되었습니다: ${input.id}", KoreanPiiScrubber.containsPii(prediction.text))
            assertFalse("실서비스 경로 후보 full에 PII가 포함되었습니다: ${input.id}", KoreanPiiScrubber.containsPii(full))
            generatedPredictionsJson.put(JSONObject()
                .put("text", prediction.text)
                .put("source", prediction.source)
                .put("rank", index + 1)
                .apply { if (includePlaintextInput) put("full", full) })
        }
        return JSONObject()
            .put("id", input.id)
            .put("caseKind", input.caseKind)
            .put("denominator", denominator)
            .put("inputSha256", sha256(input.prefix))
            .put("lookupUs", lookupUs)
            .put("candidateCount", matches.size)
            .put("candidates", candidates)
            .put("generatedPredictions", generatedPredictionsJson)
            .apply { if (includePlaintextInput) put("prefix", input.prefix) }
    }

    private fun sha256(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class InputCase(val id: String, val prefix: String, val caseKind: String)

    private companion object {
        const val CANDIDATE_LIMIT = 3
        const val INDEPENDENT_TERMINAL_BOUNDARY_ID = "h04"
        const val HELD_OUT_TERMINAL_BOUNDARY_ID = "u04"
        const val COMPLETION_CASE_COUNT = 23
        const val COMPLETION = "completion"
        const val TERMINAL_BOUNDARY = "terminal_boundary"
        const val GENERATED_SOURCE = "ondevice_generated"

        val INDEPENDENT_INPUTS = listOf(
            InputCase("h01", "내일 오전에 ", COMPLETION),
            InputCase("h02", "가능한 시간을 ", COMPLETION),
            InputCase("h03", "버스가 늦어서 ", COMPLETION),
            InputCase("h04", "역에 도착했어요. ", TERMINAL_BOUNDARY),
            InputCase("h05", "저녁 메뉴는 ", COMPLETION),
            InputCase("h06", "식당 예약을 ", COMPLETION),
            InputCase("h07", "빨래를 널고 ", COMPLETION),
            InputCase("h08", "설거지는 제가 ", COMPLETION),
            InputCase("h09", "주문한 물건이 ", COMPLETION),
            InputCase("h10", "사이즈가 맞지 ", COMPLETION),
            InputCase("h11", "검토가 끝나면 ", COMPLETION),
            InputCase("h12", "수정한 내용을 ", COMPLETION),
            InputCase("h13", "이 부분이 ", COMPLETION),
            InputCase("h14", "문제 풀이를 ", COMPLETION),
            InputCase("h15", "주말에 산책 ", COMPLETION),
            InputCase("h16", "영화를 보고 ", COMPLETION),
            InputCase("h17", "감기 기운이 ", COMPLETION),
            InputCase("h18", "오늘은 일찍 ", COMPLETION),
            InputCase("h19", "방문 전에 ", COMPLETION),
            InputCase("h20", "모임 장소가 ", COMPLETION),
            InputCase("h21", "도와주신 덕분에 ", COMPLETION),
            InputCase("h22", "걱정해 줘서 ", COMPLETION),
            InputCase("h23", "요즘 어떻게 ", COMPLETION),
            InputCase("h24", "잘 지내고 ", COMPLETION)
        )

        val CONTROL_INPUTS = listOf(
            InputCase("c01", "내일 시간 ", COMPLETION),
            InputCase("c02", "이 버스는 ", COMPLETION),
            InputCase("c03", "구매한 물건이 ", COMPLETION)
        )

        val HELD_OUT_INPUTS = listOf(
            InputCase("u01", "다음 주 일정은 ", COMPLETION),
            InputCase("u02", "오후 통화가 ", COMPLETION),
            InputCase("u03", "지하철이 와서 ", COMPLETION),
            InputCase("u04", "집에 무사히 왔어요. ", TERMINAL_BOUNDARY),
            InputCase("u05", "점심은 어디에서 ", COMPLETION),
            InputCase("u06", "카페 자리를 ", COMPLETION),
            InputCase("u07", "청소 도구를 ", COMPLETION),
            InputCase("u08", "분리수거는 제가 ", COMPLETION),
            InputCase("u09", "환불 절차를 ", COMPLETION),
            InputCase("u10", "배송 조회가 ", COMPLETION),
            InputCase("u11", "공유 문서에 ", COMPLETION),
            InputCase("u12", "담당자에게 검토를 ", COMPLETION),
            InputCase("u13", "강의 내용을 ", COMPLETION),
            InputCase("u14", "연습 문제를 ", COMPLETION),
            InputCase("u15", "전시회 보러 ", COMPLETION),
            InputCase("u16", "게임을 하다가 ", COMPLETION),
            InputCase("u17", "잠깐 쉬고 ", COMPLETION),
            InputCase("u18", "어깨가 조금 ", COMPLETION),
            InputCase("u19", "행사장 입구에서 ", COMPLETION),
            InputCase("u20", "친구 집에 ", COMPLETION),
            InputCase("u21", "챙겨 주셔서 ", COMPLETION),
            InputCase("u22", "번거로우셨을 텐데 ", COMPLETION),
            InputCase("u23", "이번 달은 어떻게 ", COMPLETION),
            InputCase("u24", "요새 바쁘신 것 ", COMPLETION)
        )
    }
}

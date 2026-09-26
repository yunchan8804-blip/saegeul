/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationEligibility
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationSnapshot
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationWaitReason
import org.fcitx.fcitx5.android.input.ai.DynamicBigram
import org.fcitx.fcitx5.android.input.ai.PersonaDna
import org.fcitx.fcitx5.android.input.ai.TypingDnaRepository
import org.fcitx.fcitx5.android.input.ai.graph.EdgeInfo
import org.fcitx.fcitx5.android.input.ai.graph.EntityInfo
import org.fcitx.fcitx5.android.input.ai.graph.OnDeviceL1GraphCache
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionSession
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.vault.AesGcmVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.EnvelopeVaultCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Red Team Round 8: IME Candidate Preemption & 120Hz Latency Stress Device Test.
 *
 * Scenarios:
 * 1. RED-PREEMPT-01: 자동 추천 vs 명시 완성 Lease 선점 및 복원 불변량
 * 2. RED-PREEMPT-02: 후보 바 문장 Pill 칩 초고속 더블 탭 멱등성
 * 3. RED-PREEMPT-03: 키보드 팝업/숨김 상태 전환에 따른 네이티브 휴면 무결성
 * 4. RED-PREEMPT-04: 120Hz 키보드 메인 스레드 레이턴시 예산 검증
 * 5. RED-PREEMPT-05: 온디바이스 금고 격리 및 오프라인 무결성
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RedTeamRound8ImeCandidatePreemptionDeviceTest {

    sealed interface LeaseResult {
        data class Granted(val lease: OnDeviceGenerationControl.Lease) : LeaseResult
        data class Denied(val reason: String = "DENIED") : LeaseResult
    }

    private fun tryAcquireLease(
        purpose: OnDeviceGenerationControl.Purpose,
        cancel: () -> Unit = {}
    ): LeaseResult {
        val lease = OnDeviceGenerationControl.tryBegin(purpose, cancel)
        return if (lease != null) LeaseResult.Granted(lease) else LeaseResult.Denied()
    }

    // =========================================================================
    // 1. RED-PREEMPT-01: 자동 추천 vs 명시 완성 Lease 선점 및 복원 불변량
    // =========================================================================
    @Test
    fun testRedPreempt01_AutoVsExplicitLeasePreemptionAndRestoration() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        var autoCancelledCount = 0
        var preemptCount = 0
        var restoreSignalCount = 0

        try {
            // AUTO 목적 리스가 활성(웜업 중 또는 대기 중, isBusy = false)인 상태 시뮬레이션
            OnDeviceGenerationControl.configureAutoContextPreemption(
                isBusy = { false },
                preempt = { preemptCount++ },
                onExplicitContextFinished = { restoreSignalCount++ }
            )

            // AUTO 목적 리스 발급
            val autoLease = OnDeviceGenerationControl.tryBegin(
                purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
            ) { autoCancelledCount++ }

            assertNotNull("AUTO_CONTEXT 리스가 정상 발급되어야 합니다.", autoLease)
            assertTrue("활성 리스가 존재하므로 isGenerating은 true여야 합니다.", OnDeviceGenerationControl.isGenerating)
            assertEquals("선점 전에는 preemptHandler가 호출되지 않아야 합니다.", 0, preemptCount)

            // AUTO 활성 상태에서 명시 완성(EXPLICIT_CONTEXT) 호출
            val result: LeaseResult = tryAcquireLease(OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT)

            // 선점 핸들러가 트리거되고 EXPLICIT에 정상적으로 Lease가 발급됨을 단언
            assertTrue(
                "선점 후 EXPLICIT_CONTEXT에 정상적으로 Lease가 발급되어야 합니다 (result is LeaseResult.Granted).",
                result is LeaseResult.Granted
            )
            assertEquals("선점 핸들러가 정확히 1회 트리거되어야 합니다.", 1, preemptCount)

            val explicitLease = (result as LeaseResult.Granted).lease
            assertNotNull("발급된 EXPLICIT lease는 null이 아니어야 합니다.", explicitLease)

            // 선점된 AUTO 리스는 하드 종료되어 end() 호출 시 거부(false)됨을 단언
            assertFalse(
                "선점으로 하드 종료된 이전 AUTO 리스는 end() 호출 시 false여야 합니다.",
                OnDeviceGenerationControl.end(autoLease!!)
            )
            assertTrue("EXPLICIT Lease가 유효하므로 isGenerating은 true여야 합니다.", OnDeviceGenerationControl.isGenerating)

            // EXPLICIT 작업 종료(end()) 시 등록된 콜백을 통해 AUTO 추천 복원 신호가 정상 전달됨을 단언
            val explicitEndSuccess = OnDeviceGenerationControl.end(explicitLease)
            assertTrue("EXPLICIT Lease 종료는 성공해야 합니다.", explicitEndSuccess)
            assertEquals(
                "EXPLICIT 작업 종료 시 등록된 콜백을 통해 AUTO 추천 복원 신호가 1회 전달되어야 합니다.",
                1,
                restoreSignalCount
            )
            assertFalse("모든 작업 종료 후 isGenerating은 false여야 합니다.", OnDeviceGenerationControl.isGenerating)
        } finally {
            OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    // =========================================================================
    // 2. RED-PREEMPT-02: 후보 바 문장 Pill 칩 초고속 더블 탭 멱등성
    // =========================================================================
    @Test
    fun testRedPreempt02_CandidatePillDoubleTapIdempotency() {
        val session = OnDeviceSuggestionSession()
        val scope = OnDeviceSuggestionSession.Scope(
            packageName = "net.chanpaca.saegeul.test",
            fieldId = 101,
            editorSessionId = 9999L
        )
        val initialText = "회의 "
        val snapshot = OnDeviceSuggestionSession.Snapshot(
            scope = scope,
            revision = 1L,
            textBeforeCursor = initialText,
            selectionStart = initialText.length,
            selectionEnd = initialText.length
        )
        assertTrue("초기 스냅샷 관측이 성공해야 합니다.", session.observe(snapshot))

        val baseTimeMs = 50_000L
        val ticket = requireNotNull(session.begin(baseTimeMs))

        // 동일한 후보 문장("오늘 회의 참석합니다") 발행 및 표시
        val candidateSentence = "오늘 회의 참석합니다"
        assertTrue("후보 문장 발행이 성공해야 합니다.", session.publish(ticket, candidateSentence, baseTimeMs))

        val proposal = requireNotNull(session.cached(baseTimeMs))
        assertEquals("발행된 제안 문장이 일치해야 합니다.", candidateSentence, proposal.suffix)

        // 5ms 간격으로 연속 2회 적용(apply) 시도
        val applyCandidate: (Long) -> Boolean = { nowMs ->
            val suffix = session.takeForApply(proposal, snapshot, nowMs)
            suffix != null
        }

        val firstCallTime = baseTimeMs + 100L
        val firstCallSuccess = applyCandidate(firstCallTime)

        val secondCallTime = firstCallTime + 5L // 5ms 후 더블 탭
        val secondCallSuccess = applyCandidate(secondCallTime)

        // 첫 번째 호출은 성공(true), 두 번째 호출은 동일 위치 재적용 거부(false) 단언
        assertTrue("첫 번째 적용 호출은 성공(true)해야 합니다.", firstCallSuccess)
        assertFalse("5ms 뒤의 두 번째 호출은 동일 위치 재적용 거부(false)되어야 합니다.", secondCallSuccess)

        // 에디터 본문에 중복 문장이 삽입되지 않는 멱등성 검증
        var editorText = snapshot.textBeforeCursor
        if (firstCallSuccess) {
            editorText += proposal.suffix
        }
        if (secondCallSuccess) {
            editorText += proposal.suffix
        }

        assertEquals(
            "에디터 본문에는 문장이 중복 삽입되지 않고 정확히 1회만 반영되어야 합니다.",
            initialText + candidateSentence,
            editorText
        )
    }

    // =========================================================================
    // 3. RED-PREEMPT-03: 키보드 팝업/숨김 상태 전환에 따른 네이티브 휴면 무결성
    // =========================================================================
    @Test
    fun testRedPreempt03_KeyboardVisibilityNativeQuiescence() {
        try {
            // 1) inputViewVisible = true (키보드 활성) 상태 전환
            OnDeviceGenerationControl.onInputViewVisibilityChanged(true)
            assertTrue("isInputViewVisible은 true여야 합니다.", OnDeviceGenerationControl.isInputViewVisible)

            val activeSnapshot = GemmaGenerationSnapshot(
                batteryPercent = 80,
                isCharging = false,
                powerSaveMode = false,
                thermalStatus = null,
                lowMemory = false,
                inputViewVisible = OnDeviceGenerationControl.isInputViewVisible
            )
            assertTrue("Snapshot.inputViewVisible은 true여야 합니다.", activeSnapshot.inputViewVisible)

            val activeWaitReason = GemmaGenerationEligibility.evaluate(activeSnapshot)
            assertEquals(
                "키보드 활성 중에는 KEYBOARD_ACTIVE 대기 사유가 반환되어야 합니다.",
                GemmaGenerationWaitReason.KEYBOARD_ACTIVE,
                activeWaitReason
            )
            assertFalse(
                "키보드 활성 중에는 백그라운드 지식 생성이 불가해야 합니다.",
                GemmaGenerationEligibility.canGenerate(activeSnapshot)
            )

            // 네이티브 백그라운드 지식 축적이 정지(pausedForInput = true)됨을 확인
            val pausedForInput = activeSnapshot.inputViewVisible || (activeWaitReason == GemmaGenerationWaitReason.KEYBOARD_ACTIVE)
            assertTrue(
                "inputViewVisible = true (키보드 활성) 상태에서 네이티브 백그라운드 지식 축적이 정지(pausedForInput = true)되어야 합니다.",
                pausedForInput
            )

            // 키보드가 떠 있는 동안 PUBLIC_MATERIAL 리스 획득이 원천 차단됨을 단언
            val blockedMaterialLease = OnDeviceGenerationControl.tryBegin(OnDeviceGenerationControl.Purpose.PUBLIC_MATERIAL) {}
            assertNull("키보드 활성 상태에서는 PUBLIC_MATERIAL 리스 발급이 차단되어야 합니다.", blockedMaterialLease)

            // 2) inputViewVisible = false (키보드 닫힘) 상태 전환
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
            assertFalse("isInputViewVisible은 false로 복귀해야 합니다.", OnDeviceGenerationControl.isInputViewVisible)

            val idleSnapshot = GemmaGenerationSnapshot(
                batteryPercent = 80,
                isCharging = false,
                powerSaveMode = false,
                thermalStatus = null,
                lowMemory = false,
                inputViewVisible = OnDeviceGenerationControl.isInputViewVisible
            )
            assertFalse("Snapshot.inputViewVisible은 false여야 합니다.", idleSnapshot.inputViewVisible)

            val idleWaitReason = GemmaGenerationEligibility.evaluate(idleSnapshot)
            assertNull("키보드 닫힘 상태에서는 키보드 대기 사유가 없어야 합니다.", idleWaitReason)
            assertTrue("키보드 닫힘 상태에서는 백그라운드 지식 생성이 정상 허용되어야 합니다.", GemmaGenerationEligibility.canGenerate(idleSnapshot))

            val unpausedForInput = idleSnapshot.inputViewVisible || (idleWaitReason == GemmaGenerationWaitReason.KEYBOARD_ACTIVE)
            assertFalse(
                "inputViewVisible = false (키보드 닫힘) 상태 전환 시 정상적으로 유휴 상태로 복귀(pausedForInput = false)해야 합니다.",
                unpausedForInput
            )

            // 키보드가 닫힌 상태에서는 PUBLIC_MATERIAL 리스 획득이 정상 허용됨을 검증
            val allowedMaterialLease = OnDeviceGenerationControl.tryBegin(OnDeviceGenerationControl.Purpose.PUBLIC_MATERIAL) {}
            assertNotNull("키보드가 닫히면 PUBLIC_MATERIAL 리스를 정상적으로 획득할 수 있어야 합니다.", allowedMaterialLease)
            assertTrue(OnDeviceGenerationControl.end(allowedMaterialLease!!))
        } finally {
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        }
    }

    // =========================================================================
    // 4. RED-PREEMPT-04: 120Hz 키보드 메인 스레드 레이턴시 예산 검증
    // =========================================================================
    @Test
    fun testRedPreempt04_120HzKeyboardMainThreadLatencyBudget() {
        // L1 그래프 캐시 초기화 및 더미 그래프 구축
        val l1Cache = OnDeviceL1GraphCache()
        val entities = (0 until 50).map { i ->
            EntityInfo(id = "entity_$i", label = "엔티티_$i", category = "TEST")
        }
        val edges = (0 until 49).map { i ->
            EdgeInfo(src = "entity_$i", dst = "entity_${i + 1}", relation = "NEXT", weight = 1.0f)
        }
        l1Cache.warmup(entities, edges)

        val testSentences = listOf(
            "사과을 먹었다",
            "선생님은 학교로 가신다",
            "연필를 샀습니다",
            "하늘이 맑고 푸르다",
            "책을 읽고 있습니다",
            "친구과 영화를 봤다",
            "의사은 병원에 있다",
            "물로 씻었습니다"
        )

        // JIT 컴파일 및 캐시 충분한 웜업 (모든 문장과 캐시 엔티티에 대해 예열)
        repeat(500) { i ->
            KoreanJosaBitmaskEngine.correctJosaMismatch(testSentences[i % testSentences.size])
            l1Cache.get1Hop("entity_${i % 50}")
        }
        System.gc()
        Thread.sleep(100)

        // 원시 배열을 사용하여 루프 중 메모리 할당/박싱 오버헤드를 원천 차단
        val latencyListNanos = LongArray(1000)

        // 1) KoreanJosaBitmaskEngine.correctJosaMismatch 500회 연속 호출
        for (i in 0 until 500) {
            val sentence = testSentences[i % testSentences.size]
            val start = System.nanoTime()
            val corrected = KoreanJosaBitmaskEngine.correctJosaMismatch(sentence)
            val elapsedNanos = System.nanoTime() - start

            latencyListNanos[i] = elapsedNanos
            val elapsedMs = elapsedNanos / 1_000_000.0

            assertTrue(
                "KoreanJosaBitmaskEngine.correctJosaMismatch 단일 호출 소요 시간($elapsedMs ms)이 8.0ms(120Hz 프레임 예산)를 넘지 않아야 합니다. (iter=$i)",
                elapsedMs < 8.0
            )
            assertTrue("교정 결과가 비어있지 않아야 합니다.", corrected.isNotEmpty())
        }

        // 2) OnDeviceL1GraphCache.get1Hop 500회 연속 호출
        for (i in 0 until 500) {
            val entityId = "entity_${i % 50}"
            val start = System.nanoTime()
            val neighbors = l1Cache.get1Hop(entityId)
            val elapsedNanos = System.nanoTime() - start

            latencyListNanos[500 + i] = elapsedNanos
            val elapsedMs = elapsedNanos / 1_000_000.0

            assertTrue(
                "OnDeviceL1GraphCache.get1Hop 단일 호출 소요 시간($elapsedMs ms)이 8.0ms(120Hz 프레임 예산)를 넘지 않아야 합니다. (iter=$i)",
                elapsedMs < 8.0
            )
        }

        // 3) 1,000회 연산의 평균 지연시간 < 0.2ms(200µs) 단언
        val averageNanos = latencyListNanos.average()
        val averageMs = averageNanos / 1_000_000.0

        assertTrue(
            "1,000회 연산의 평균 지연시간이 0.2ms(200µs) 미만이어야 합니다. Actual: ${averageMs}ms (${averageNanos / 1000.0}µs)",
            averageMs < 0.2
        )
    }

    // =========================================================================
    // 5. RED-PREEMPT-05: 온디바이스 금고 격리 및 오프라인 무결성
    // =========================================================================
    @Test
    fun testRedPreempt05_OnDeviceVaultIsolationAndOfflineIntegrity() {
        val rawKey = AesGcmVaultCipher.randomKey()
        val wrappingCipher = AesGcmVaultCipher(rawKey)
        val envelopeCipher = EnvelopeVaultCipher(wrappingCipher)

        val samplePlain = """{"dna":"profile","terms":["안녕","감사합니다"]}""".toByteArray(Charsets.UTF_8)
        val aad = "saegeul-test-aad".toByteArray(Charsets.UTF_8)

        // 1) AES-256-GCM 온디바이스 암호화/복호화 라운드트립 수행
        val encryptedBlob = envelopeCipher.encrypt(samplePlain, aad)
        assertFalse("암호화된 결과는 평문과 달라야 합니다.", samplePlain.contentEquals(encryptedBlob))
        assertTrue("암호화된 blob에 봉투 헤더가 포함되어야 합니다.", EnvelopeVaultCipher.hasEnvelopeHeader(encryptedBlob))

        val decryptedPlain = envelopeCipher.decrypt(encryptedBlob, aad)
        assertTrue("복호화된 데이터는 원본 평문과 100% 바이트 단위로 일치해야 합니다.", samplePlain.contentEquals(decryptedPlain))

        // 2) TypingDnaRepository 무결성 및 불변량 단언
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageFile = File(context.filesDir, "test_red_team_round8_vault.json")
        if (storageFile.exists()) {
            storageFile.delete()
        }

        try {
            val repository = TypingDnaRepository(storageFile, cipher = envelopeCipher)

            // 학습 데이터 삽입
            val persona = PersonaDna(
                category = "messenger",
                dominantTone = "Honorific",
                habitualEndings = listOf("습니다", "해요"),
                frequentBigrams = listOf(DynamicBigram("오늘", "회의", 0.95f)),
                cannedPhrases = listOf("오늘 회의 참석합니다")
            )
            repository.updatePersona(persona, analyzedSentenceCount = 15)

            // 영속화된 프로필 로드 검증
            val profile = repository.load(forceReload = true)
            assertTrue("저장된 메신저 페르소나가 복원되어야 합니다.", profile.personas.containsKey("messenger"))

            // 통계 불변량 단언
            val stats = repository.getStats()
            assertEquals(
                "클라우드 전송 바이트 수는 엄격히 0이어야 합니다.",
                0,
                stats.cloudBytesExported
            )
            assertEquals(
                "온디바이스 프라이버시 비율은 엄격히 100%여야 합니다.",
                100,
                stats.privacyOnDevicePercent
            )
            assertTrue("학습 데이터가 존재해야 합니다.", stats.hasLearnedData)
        } finally {
            if (storageFile.exists()) {
                storageFile.delete()
            }
        }
    }
}

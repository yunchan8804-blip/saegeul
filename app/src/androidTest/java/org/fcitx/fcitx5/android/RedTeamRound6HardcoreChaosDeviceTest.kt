/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.filters.MediumTest
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.UserTypingContextCollector
import org.fcitx.fcitx5.android.input.ai.adapter.OnDeviceLoraTrainer
import org.fcitx.fcitx5.android.input.ai.adapter.TestTimeTrainer
import org.fcitx.fcitx5.android.input.ai.daemon.AiDaemonClient
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.JosaKind
import org.fcitx.fcitx5.android.input.ai.thermal.ThermalGuardian
import org.fcitx.fcitx5.android.input.ai.thermal.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Normalizer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Red Team Round 6 Hardcore Chaos Instrumentation Device Test.
 *
 * Scenarios:
 * 1. RED-HARDCORE-01: Rapid backspace jamo fragment destruction & atomic buffer purge.
 * 2. RED-HARDCORE-02: Malicious outlier gradient bomb & TTT/LoRA weight stability.
 * 3. RED-HARDCORE-03: Extreme thermal 46.5°C CRITICAL hardware protection enforcement.
 * 4. RED-HARDCORE-04: 100-thread concurrent dead binder fallback storm (1,000 calls).
 * 5. RED-HARDCORE-05: Unicode normalization NFD/NFC & non-printing control character josa integrity.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RedTeamRound6HardcoreChaosDeviceTest {

    // =========================================================================
    // 1. RED-HARDCORE-01: 고속 백스페이스 잔여 자모 파괴 & 원자적 소거
    // =========================================================================
    @Test
    fun testRedHardcore01_RapidBackspaceAtomicPurgeAndContextIsolation() {
        val collector = UserTypingContextCollector()
        val pkg = "com.kakao.talk"

        // 1) "학교에서 공부를 하고 있습니다" 입력 시뮬레이션
        // 음절 단위로 커밋되어 버퍼에 잔여물(pending)이 적재되도록 시뮬레이션
        val initialInput = "학교에서 공부를 하고 있습니다"
        for (ch in initialInput) {
            collector.recordCommittedText(pkg, ch.toString())
        }

        // 끝이 "다"로 끝나 "습니다" 종결어미가 무음절로 분리 대기 중이므로 pending 상태여야 함
        assertTrue(
            "입력 완료 직후 종결 어미 대기 상태로 pending 버퍼에 적재되어 있어야 합니다.",
            collector.hasPending(pkg)
        )

        // 2) 20회 연속 고속 백스페이스 시뮬레이션
        repeat(20) {
            collector.onBackspaceContinuityLost(pkg, null)
        }

        // 3) 문맥 이동 및 discardPending() 호출 시 모든 버퍼 잔여물이 원자적으로 소거됨을 단언
        collector.discardPending(pkg)
        assertFalse(
            "onBackspaceContinuityLost 및 discardPending 호출 후 모든 버퍼 잔여물이 원자적으로 소거되어야 합니다.",
            collector.hasPending(pkg)
        )

        // 4) 에디터 세션 전환 후 다음 새 세션 입력 시 이전 파편 자모의 교차 오염 방지 검증
        collector.onEditorSessionStarted(pkg, fieldId = 999, restarting = false)
        collector.recordCommittedText(pkg, "내일 회의는 정상 진행합니다.")

        val context = collector.getRecentContext(pkg)
        val sentences = collector.getSentences(pkg)

        assertFalse(
            "이전 세션의 파편 자모('학교')가 새 문맥에 교차 오염되어서는 안 됩니다.",
            context.contains("학교")
        )
        assertFalse(
            "이전 세션의 파편 자모('공부')가 새 문맥에 교차 오염되어서는 안 됩니다.",
            context.contains("공부")
        )
        assertFalse(
            "이전 세션의 파편 자모('있습')가 새 문맥에 교차 오염되어서는 안 됩니다.",
            context.contains("있습")
        )
        assertTrue(
            "새 세션의 문장이 정상적으로 수집되어야 합니다.",
            sentences.contains("내일 회의는 정상 진행합니다.")
        )
    }

    // =========================================================================
    // 2. RED-HARDCORE-02: 악의적 이상치 그래디언트 폭탄 & TTT 가중치 안정성
    // =========================================================================
    @Test
    fun testRedHardcore02_OutlierGradientBombAndTttWeightStability() {
        val ttt = TestTimeTrainer()

        // 1) NaN, PositiveInfinity, NegativeInfinity, 1e15 등의 악의적 그래디언트 폭탄 주입
        val gradientBombs = listOf(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            1e15f,
            -1e15f,
            1e30f,
            -1e30f
        )

        for ((idx, bomb) in gradientBombs.withIndex()) {
            val token = "bomb_token_$idx"
            val applied = ttt.applyGradient(token, bomb)

            assertFalse("Gradient bomb $bomb 후 가중치는 NaN이 아니어야 합니다.", applied.isNaN())
            assertFalse("Gradient bomb $bomb 후 가중치는 Infinite가 아니어야 합니다.", applied.isInfinite())
            assertTrue(
                "가중치는 MAX_WEIGHT_MAGNITUDE 범위 내에 클리핑되어야 합니다. Actual: $applied",
                kotlin.math.abs(applied) <= TestTimeTrainer.MAX_WEIGHT_MAGNITUDE
            )
        }

        // 2) 1,000회 연속 노이즈 텍스트 입력 주입
        repeat(1000) { i ->
            val noiseContext = "노이즈_입력_스트레스_토큰_${i}_!@#\$%^&*()_+~`"
            val state = ttt.adaptOnline(noiseContext)

            assertFalse("스텝 $i 에서 updateNorm은 NaN이 아니어야 합니다.", state.updateNorm.isNaN())
            assertFalse("스텝 $i 에서 updateNorm은 Infinite가 아니어야 합니다.", state.updateNorm.isInfinite())
            assertFalse("스텝 $i 에서 driftFromBase는 NaN이 아니어야 합니다.", state.driftFromBase.isNaN())
            assertFalse("스텝 $i 에서 driftFromBase는 Infinite가 아니어야 합니다.", state.driftFromBase.isInfinite())
        }

        val allWeights = ttt.getAllWeights()
        assertTrue("활성 슬롯은 DEFAULT_SLOTS 이하로 유지되어야 합니다.", allWeights.size <= TestTimeTrainer.DEFAULT_SLOTS)
        for ((token, w) in allWeights) {
            assertFalse("토큰 '$token'의 가중치는 NaN이 아니어야 합니다.", w.isNaN())
            assertFalse("토큰 '$token'의 가중치는 Infinite가 아니어야 합니다.", w.isInfinite())
            assertTrue(
                "토큰 '$token'의 가중치는 유한한 정상 범위 내에 안정적으로 유지되어야 합니다. Actual: $w",
                kotlin.math.abs(w) <= TestTimeTrainer.MAX_WEIGHT_MAGNITUDE
            )
        }

        // 3) OnDeviceLoraTrainer 이상치 및 노이즈 안정성 검증
        val loraTrainer = OnDeviceLoraTrainer(rank = 4, alpha = 8.0f)
        val noiseSamples = (1..1000).map { "악의적_LoRA_노이즈_샘플_${it}_@!#\$%^&*()_+" }
        val loraResult = loraTrainer.trainBatch(noiseSamples, maxSteps = 5)

        assertFalse("LoRA 최종 손실은 NaN이 아니어야 합니다.", loraResult.finalLoss.isNaN())
        assertFalse("LoRA 최종 손실은 Infinite가 아니어야 합니다.", loraResult.finalLoss.isInfinite())
        assertFalse("LoRA 가중치 변화량 노름은 NaN이 아니어야 합니다.", loraResult.deltaWeightNorm.isNaN())
        assertFalse("LoRA 가중치 변화량 노름은 Infinite가 아니어야 합니다.", loraResult.deltaWeightNorm.isInfinite())
        assertTrue(
            "LoRA 망각률은 2.0% 미만으로 엄격히 제한되어야 합니다. Actual: ${loraResult.forgettingRate}",
            loraResult.forgettingRate < 0.02f
        )
    }

    // =========================================================================
    // 3. RED-HARDCORE-03: 극한 발열 46.5°C CRITICAL 상태 하드웨어 보호
    // =========================================================================
    @Test
    fun testRedHardcore03_ThermalCriticalHardwareProtection() {
        val guardian = ThermalGuardian()

        // 온도 46.5°C, 상태 ThermalStatus.CRITICAL 설정
        guardian.updateTemperature(46.5f)
        guardian.updateThermalStatus(ThermalStatus.CRITICAL)

        assertEquals("설정된 온도가 정확히 반영되어야 합니다.", 46.5f, guardian.currentTemperature, 0.001f)
        assertEquals("설정된 써멀 상태가 CRITICAL이어야 합니다.", ThermalStatus.CRITICAL, guardian.currentThermalStatus)

        // isBackgroundTrainingAllowed() == false 단언
        assertFalse(
            "46.5°C CRITICAL 상태에서는 하드웨어 보호를 위해 백그라운드 학습이 즉시 금지되어야 합니다.",
            guardian.isBackgroundTrainingAllowed()
        )

        // getThrottleDelayMs() >= 100L 단언
        assertTrue(
            "46.5°C CRITICAL 상태에서는 스로틀 지연이 최소 100ms 이상 강제되어야 합니다. Actual: ${guardian.getThrottleDelayMs()}ms",
            guardian.getThrottleDelayMs() >= 100L
        )

        // getRecommendedBatchSize() == 0 단언
        assertEquals(
            "46.5°C CRITICAL 상태에서는 연산 폭주 방지를 위해 추천 배치 크기가 0이어야 합니다.",
            0,
            guardian.getRecommendedBatchSize()
        )

        // 부가 경계선 검증: 정상 임계점 (36.5°C 이하 및 LIGHT 상태)
        guardian.updateTemperature(36.5f)
        guardian.updateThermalStatus(ThermalStatus.LIGHT)
        assertTrue("36.5°C LIGHT 상태에서는 백그라운드 학습이 허용되어야 합니다.", guardian.isBackgroundTrainingAllowed())

        guardian.updateTemperature(36.6f)
        assertFalse("36.6°C 초과 시 즉시 백그라운드 학습이 차단되어야 합니다.", guardian.isBackgroundTrainingAllowed())
    }

    // =========================================================================
    // 4. RED-HARDCORE-04: 100스레드 동시 바인더 사망 Fallback 스톰
    // =========================================================================
    @Test(timeout = 20000)
    fun testRedHardcore04_100ThreadsConcurrentBinderDeathFallbackStorm() {
        val client = AiDaemonClient()

        // 바인더 서비스가 연결되지 않고 사망한 상태 시뮬레이션
        client.binderDied()
        assertFalse(client.isBound)
        assertNull(client.service)

        val threadCount = 100
        val callsPerThread = 10
        val totalCalls = threadCount * callsPerThread
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val exceptions = ConcurrentLinkedQueue<Throwable>()
        val fallbackSuccessCount = AtomicInteger(0)

        // 100개의 백그라운드 스레드가 동시에 executeWithFallback()을 1,000회 호출
        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until callsPerThread) {
                        // 스톰 도중 binderDied()가 재차 트리거되어도 무결성 유지
                        if (i % 5 == 0) {
                            client.binderDied()
                        }
                        val result = client.executeWithFallback(
                            block = { service ->
                                service.generatePromptLookupSync("질의", "문맥", 5)
                            },
                            fallback = {
                                "ROBUST_FALLBACK_VAL_$i"
                            }
                        )
                        if (result.startsWith("ROBUST_FALLBACK_VAL")) {
                            fallbackSuccessCount.incrementAndGet()
                        }
                    }
                } catch (e: Throwable) {
                    exceptions.add(e)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finished = doneLatch.await(15, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("100개 스레드의 동시 폴백 호출이 15초 내에 완료되어야 합니다.", finished)
        assertTrue("동시 바인더 사망 스톰 중 예외가 발생하지 않아야 합니다: $exceptions", exceptions.isEmpty())
        assertEquals(
            "100스레드 1,000회 호출 모두 데드락 없이 100% 정상적으로 fallback 값을 반환해야 합니다.",
            totalCalls,
            fallbackSuccessCount.get()
        )
    }

    // =========================================================================
    // 5. RED-HARDCORE-05: 유니코드 정규화 NFD/NFC 및 비표시 제어문자 조사 무결성
    // =========================================================================
    @Test
    fun testRedHardcore05_UnicodeNfdAndZeroWidthControlCharsJosaIntegrity() {
        // --- (1) NFD (자모 분리: ᄀ+ᅡ+ᆨ) 종성 판별 및 조사 선택 무결성 ---
        val nfdApple = Normalizer.normalize("사과", Normalizer.Form.NFD)
        val nfdBook = Normalizer.normalize("책", Normalizer.Form.NFD)
        val nfdSky = Normalizer.normalize("하늘", Normalizer.Form.NFD)
        val nfdStudent = Normalizer.normalize("학생", Normalizer.Form.NFD)

        // NFD 모음 종결 -> 는, 가, 를, 와, 로
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa(nfdApple, JosaKind.EUN_NEUN))
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa(nfdApple, JosaKind.I_GA))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa(nfdApple, JosaKind.EUL_REUL))
        assertEquals("와", KoreanJosaBitmaskEngine.selectJosa(nfdApple, JosaKind.GWA_WA))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa(nfdApple, JosaKind.EURO_RO))

        // NFD 자음 종결 (일반) -> 은, 이, 을, 과, 으로
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa(nfdBook, JosaKind.EUN_NEUN))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa(nfdBook, JosaKind.I_GA))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa(nfdBook, JosaKind.EUL_REUL))
        assertEquals("과", KoreanJosaBitmaskEngine.selectJosa(nfdBook, JosaKind.GWA_WA))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa(nfdBook, JosaKind.EURO_RO))

        // NFD ㄹ 받침 종결 -> 은, 이, 을, 과, 로 ('으로' 금지)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa(nfdSky, JosaKind.EUN_NEUN))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa(nfdSky, JosaKind.EURO_RO))

        // NFD ㅇ 받침 종결 -> 은, 으로
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa(nfdStudent, JosaKind.EUN_NEUN))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa(nfdStudent, JosaKind.EURO_RO))

        // --- (2) 비표시 제어문자 (ZWSP, ZWNJ, ZWJ, BOM) 삽입 시 종성 무결성 ---
        val appleZwsp = "사과\u200B"
        val bookZwnj = "책\u200C"
        val skyZwj = "하늘\u200D"
        val studentBom = "학생\uFEFF"
        val seaCombined = "바다\u200B\u200C\u200D\uFEFF"

        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa(appleZwsp, JosaKind.EUN_NEUN))
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa(bookZwnj, JosaKind.EUN_NEUN))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa(skyZwj, JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa(studentBom, JosaKind.EURO_RO))
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa(seaCombined, JosaKind.EUN_NEUN))

        // attachJosa 검증
        assertEquals("사과\u200B는", KoreanJosaBitmaskEngine.attachJosa(appleZwsp, JosaKind.EUN_NEUN))
        assertEquals("책\u200C은", KoreanJosaBitmaskEngine.attachJosa(bookZwnj, JosaKind.EUN_NEUN))

        // isValidAttachment 검증
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(appleZwsp, "는"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(appleZwsp, "은"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(bookZwnj, "은"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(bookZwnj, "는"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(skyZwj, "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(skyZwj, "으로"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(studentBom, "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(studentBom, "로"))

        // --- (3) 체언과 조사 사이 제어문자 삽입 시 불일치 감지 및 교정 무결성 ---
        val wrongCases = listOf(
            Pair("사과\u200B은 맛있다", "사과\u200B는 맛있다"),
            Pair("책\u200C를 읽었다", "책\u200C을 읽었다"),
            Pair("하늘\uFEFF으로 날아간다", "하늘\uFEFF로 날아간다"),
            Pair("학생\u200D는 공부한다", "학생\u200D은 공부한다"),
            Pair("컴퓨터\u200B\u200C이 고장났다", "컴퓨터\u200B\u200C가 고장났다")
        )

        for ((wrong, expected) in wrongCases) {
            assertTrue(
                "비표시 제어문자가 포함된 비문 '$wrong'에서 조사의 불일치가 감지되어야 합니다.",
                KoreanJosaBitmaskEngine.hasJosaMismatch(wrong)
            )
            val corrected = KoreanJosaBitmaskEngine.correctJosaMismatch(wrong)
            assertEquals(
                "비표시 제어문자를 건너뛰고 조사가 올바르게 교정되어야 합니다.",
                expected,
                corrected
            )
        }

        // NFD + 제어문자 복합 비문 교정 검증
        val nfdWrongApple = Normalizer.normalize("사과", Normalizer.Form.NFD) + "\u200B은 신선하다"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(nfdWrongApple))
        val nfdCorrectedApple = KoreanJosaBitmaskEngine.correctJosaMismatch(nfdWrongApple)
        assertTrue(
            "NFD + ZWSP 복합 문장에서 올바른 조사('는')로 교정되어야 합니다. Actual: $nfdCorrectedApple",
            nfdCorrectedApple.contains("는") || nfdCorrectedApple.contains(Normalizer.normalize("는", Normalizer.Form.NFD))
        )
    }
}

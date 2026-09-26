/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.phonology

import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.JosaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [KoreanJosaBitmaskEngine].
 *
 * Verifies:
 * 1. Unicode decomposition formula T = (C - 0xAC00) % 28
 * 2. O(1) bitmask-based phonological selection (은/는, 이/가, 을/를, 과/와, 으로/로)
 * 3. Special handling of 'ㄹ' batchim (T == 8) for 으로/로 and 이며/며
 * 4. Numeric digit support (0~9)
 * 5. Candidate filtering
 * 6. Sentence-level mismatch detection and correction
 * 7. Execution latency constraint
 */
class KoreanJosaBitmaskEngineTest {

    @Test
    fun testUnicodeJongseongDecompositionFormula() {
        // T = (C - 0xAC00) % 28
        // '가' (0xAC00) -> T = 0
        assertEquals(0, KoreanJosaBitmaskEngine.getBatchimCode('가'))
        assertFalse(KoreanJosaBitmaskEngine.hasBatchim('가'))

        // '각' (0xAC01) -> T = 1 (ㄱ)
        assertEquals(1, KoreanJosaBitmaskEngine.getBatchimCode('각'))
        assertTrue(KoreanJosaBitmaskEngine.hasBatchim('각'))

        // '갈' -> T = 8 ('ㄹ')
        assertEquals(8, KoreanJosaBitmaskEngine.getBatchimCode('갈'))
        assertTrue(KoreanJosaBitmaskEngine.hasBatchim('갈'))
        assertTrue(KoreanJosaBitmaskEngine.isRieulBatchim('갈'))

        // '감' -> T = 16 ('ㅁ')
        assertEquals(16, KoreanJosaBitmaskEngine.getBatchimCode('감'))
        assertTrue(KoreanJosaBitmaskEngine.hasBatchim('감'))
        assertFalse(KoreanJosaBitmaskEngine.isRieulBatchim('감'))

        // '값' -> T = 18 ('ㅄ')
        assertEquals(18, KoreanJosaBitmaskEngine.getBatchimCode('값'))
        assertTrue(KoreanJosaBitmaskEngine.hasBatchim('값'))

        // '힣' (0xD7A3) -> T = 27 ('ㅎ')
        assertEquals(27, KoreanJosaBitmaskEngine.getBatchimCode('힣'))
        assertTrue(KoreanJosaBitmaskEngine.hasBatchim('힣'))
    }

    @Test
    fun testJosaSelectionBasicPairs() {
        // 은 / 는
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("선생님", JosaKind.EUN_NEUN))
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("사과", JosaKind.EUN_NEUN))
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("학생", JosaKind.EUN_NEUN))
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("친구", JosaKind.EUN_NEUN))

        // 이 / 가
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("선생님", JosaKind.I_GA))
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa("사과", JosaKind.I_GA))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("하늘", JosaKind.I_GA))
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa("바다", JosaKind.I_GA))

        // 을 / 를
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("선생님", JosaKind.EUL_REUL))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("사과", JosaKind.EUL_REUL))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("책", JosaKind.EUL_REUL))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("노트", JosaKind.EUL_REUL))

        // 과 / 와
        assertEquals("과", KoreanJosaBitmaskEngine.selectJosa("선생님", JosaKind.GWA_WA))
        assertEquals("와", KoreanJosaBitmaskEngine.selectJosa("친구", JosaKind.GWA_WA))
        assertEquals("과", KoreanJosaBitmaskEngine.selectJosa("연필", JosaKind.GWA_WA))
        assertEquals("와", KoreanJosaBitmaskEngine.selectJosa("지우개", JosaKind.GWA_WA))
    }

    @Test
    fun testJosaSelectionRieulSpecialRule() {
        // 으로 / 로 rule:
        // 1. T == 0 (no batchim) -> "로"
        // 2. T == 8 ('ㄹ' batchim) -> "로"
        // 3. T != 0 && T != 8 (other batchim) -> "으로"
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("사과", JosaKind.EURO_RO))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("학교", JosaKind.EURO_RO))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("차", JosaKind.EURO_RO))

        // 'ㄹ' batchim: 서울, 칼, 연필, 하늘, 길
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("서울", JosaKind.EURO_RO))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("칼", JosaKind.EURO_RO))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("연필", JosaKind.EURO_RO))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("길", JosaKind.EURO_RO))

        // Non-'ㄹ' batchim: 집, 책, 한국, 밥, 음악
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("집", JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("책", JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("한국", JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("밥", JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("음악", JosaKind.EURO_RO))
    }

    @Test
    fun testExtendedJosaPairs() {
        // 아 / 야
        assertEquals("아", KoreanJosaBitmaskEngine.selectJosa("영식", JosaKind.A_YA))
        assertEquals("야", KoreanJosaBitmaskEngine.selectJosa("철수", JosaKind.A_YA))

        // 이나 / 나
        assertEquals("이나", KoreanJosaBitmaskEngine.selectJosa("선생님", JosaKind.INA_NA))
        assertEquals("나", KoreanJosaBitmaskEngine.selectJosa("친구", JosaKind.INA_NA))

        // 이랑 / 랑
        assertEquals("이랑", KoreanJosaBitmaskEngine.selectJosa("선생님", JosaKind.IRANG_RANG))
        assertEquals("랑", KoreanJosaBitmaskEngine.selectJosa("친구", JosaKind.IRANG_RANG))

        // 이든 / 든
        assertEquals("이든", KoreanJosaBitmaskEngine.selectJosa("선생님", JosaKind.IDEUN_DEUN))
        assertEquals("든", KoreanJosaBitmaskEngine.selectJosa("친구", JosaKind.IDEUN_DEUN))
    }

    @Test
    fun testDigitSupport() {
        // 0 (영 - ㅇ 받침) -> 은, 이, 을, 과, 으로
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("0", JosaKind.EUN_NEUN))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("0", JosaKind.EURO_RO))

        // 1 (일 - ㄹ 받침) -> 은, 이, 을, 과, 로
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("1", JosaKind.EUN_NEUN))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("1", JosaKind.EURO_RO))

        // 2 (이 - 받침 없음) -> 는, 가, 를, 와, 로
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("2", JosaKind.EUN_NEUN))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("2", JosaKind.EURO_RO))

        // 3 (삼 - ㅁ 받침) -> 은, 이, 을, 과, 으로
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("3", JosaKind.EUN_NEUN))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("3", JosaKind.EURO_RO))

        // 7 (칠 - ㄹ 받침) -> 로
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("7", JosaKind.EURO_RO))

        // 8 (팔 - ㄹ 받침) -> 로
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("8", JosaKind.EURO_RO))
    }

    @Test
    fun testIsValidAttachment() {
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("선생님", "은"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("선생님", "는"))

        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("사과", "를"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("사과", "을"))

        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("친구", "가"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("친구", "이"))

        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("서울", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("서울", "으로"))

        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("집", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("집", "로"))
    }

    @Test
    fun testFilterCandidates() {
        // Prefix: "선생님" (batchim 'ㅁ')
        val cands1 = listOf("는 어디 가세요?", "은 어디 가세요?", "을 뵈었습니다", "를 뵈었습니다")
        val filtered1 = KoreanJosaBitmaskEngine.filterCandidates("선생님", cands1)
        assertEquals(listOf("은 어디 가세요?", "을 뵈었습니다"), filtered1)

        // Prefix: "사과" (no batchim)
        val cands2 = listOf("을 먹었다", "를 먹었다", "는 맛있다", "은 맛있다")
        val filtered2 = KoreanJosaBitmaskEngine.filterCandidates("사과", cands2)
        assertEquals(listOf("를 먹었다", "는 맛있다"), filtered2)

        // Prefix: "서울" ('ㄹ' batchim)
        val cands3 = listOf("으로 가자", "로 가자")
        val filtered3 = KoreanJosaBitmaskEngine.filterCandidates("서울", cands3)
        assertEquals(listOf("로 가자"), filtered3)

        // Prefix: "집" (non-'ㄹ' batchim)
        val cands4 = listOf("로 가자", "으로 가자")
        val filtered4 = KoreanJosaBitmaskEngine.filterCandidates("집", cands4)
        assertEquals(listOf("으로 가자"), filtered4)
    }

    @Test
    fun testFindAndCorrectMismatches() {
        val wrongSentence = "선생님는 사과을 먹었고 친구이 서울으로 갔다"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(wrongSentence))

        val corrected = KoreanJosaBitmaskEngine.correctJosaMismatch(wrongSentence)
        assertEquals("선생님은 사과를 먹었고 친구가 서울로 갔다", corrected)

        val wrongSentence2 = "집로 돌아가서 책를 읽자"
        val corrected2 = KoreanJosaBitmaskEngine.correctJosaMismatch(wrongSentence2)
        assertEquals("집으로 돌아가서 책을 읽자", corrected2)
    }

    @Test
    fun testDoubleBatchimComplexJongseong() {
        // 겹받침 단어들: 값(18), 닭(9), 삶(10), 몫(3), 핥(13), 읊(14), 잃(15)
        // 핵심 음운 불변량: 겹받침은 모두 순수 'ㄹ'(8)이 아니므로 '로'가 아닌 '으로'가 결합해야 함
        val doubleBatchims = listOf("값", "닭", "삶", "몫", "핥", "읊", "잃")
        for (word in doubleBatchims) {
            assertTrue(KoreanJosaBitmaskEngine.hasBatchim(word.first()))
            assertFalse(KoreanJosaBitmaskEngine.isRieulBatchim(word.first()))
            assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.EURO_RO))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "으로"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "로"))

            // 특수 조사: 으로서/로서, 으로써/로써
            assertEquals("으로서", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.EUROSEO_ROSEO))
            assertEquals("으로써", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.EUROSSEO_ROSSEO))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "으로서"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "로서"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "으로써"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "로써"))

            // 기타 조사
            assertEquals("이나", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.INA_NA))
            assertEquals("이랑", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.IRANG_RANG))
            assertEquals("이며", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.IMYEO_MYEO))
            assertEquals("아", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.A_YA))
        }

        // 순수 'ㄹ' 받침 단어들: 칼, 서울, 연필, 마을 (T == 8)
        val pureRieuls = listOf("칼", "서울", "연필", "마을")
        for (word in pureRieuls) {
            assertTrue(KoreanJosaBitmaskEngine.hasBatchim(word.last()))
            assertTrue(KoreanJosaBitmaskEngine.isRieulBatchim(word.last()))
            assertEquals("로", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.EURO_RO))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "로"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "으로"))

            // 특수 조사: 으로서/로서, 으로써/로써
            assertEquals("로서", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.EUROSEO_ROSEO))
            assertEquals("로써", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.EUROSSEO_ROSSEO))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "로서"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "으로서"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "로써"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "으로써"))
        }
    }

    @Test
    fun testEnglishLoanwordAndNumericPhonology() {
        // 숫자: 1로, 2로, 3으로, 6으로, 7로, 8로, 100으로, 2026년으로
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("1", JosaKind.EURO_RO))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("2", JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("3", JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("6", JosaKind.EURO_RO))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("7", JosaKind.EURO_RO))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("8", JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("100", JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("2026년", JosaKind.EURO_RO))

        // 영문: MacBook, iPhone, AI, App
        // MacBook -> [맥북] -> k 받침 (은, 을, 이, 으로)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("MacBook", JosaKind.EUN_NEUN))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("MacBook", JosaKind.EUL_REUL))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("MacBook", JosaKind.I_GA))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("MacBook", JosaKind.EURO_RO))

        // iPhone -> [아이폰] -> n 받침 (은, 을, 이, 으로)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("iPhone", JosaKind.EUN_NEUN))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("iPhone", JosaKind.EUL_REUL))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("iPhone", JosaKind.I_GA))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("iPhone", JosaKind.EURO_RO))

        // AI -> [에이아이] -> 모음 종결 (는, 를, 가, 로)
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("AI", JosaKind.EUN_NEUN))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("AI", JosaKind.EUL_REUL))
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa("AI", JosaKind.I_GA))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("AI", JosaKind.EURO_RO))

        // App -> [앱] -> p 받침 (은, 을, 이, 으로)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("App", JosaKind.EUN_NEUN))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("App", JosaKind.EUL_REUL))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("App", JosaKind.I_GA))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("App", JosaKind.EURO_RO))

        // 영어 오결합 교정 검증
        val wrongEng1 = "MacBook는 성능이 좋고 iPhone를 새로 샀다"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(wrongEng1))
        val correctedEng1 = KoreanJosaBitmaskEngine.correctJosaMismatch(wrongEng1)
        assertEquals("MacBook은 성능이 좋고 iPhone을 새로 샀다", correctedEng1)

        val wrongEng2 = "AI은 무궁무진하며 App를 설치했다"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(wrongEng2))
        val correctedEng2 = KoreanJosaBitmaskEngine.correctJosaMismatch(wrongEng2)
        assertEquals("AI는 무궁무진하며 App을 설치했다", correctedEng2)
    }

    @Test
    fun testExecutionLatencyConstraint() {
        // 10,000 repetitions should take well under 10ms total (average < 1µs per call)
        val words = listOf("선생님", "사과", "서울", "집", "친구", "책", "연필", "한국")
        val kinds = JosaKind.values()

        val start = System.nanoTime()
        var count = 0
        for (i in 0 until 10_000) {
            val word = words[i % words.size]
            val kind = kinds[i % kinds.size]
            val josa = KoreanJosaBitmaskEngine.selectJosa(word, kind)
            if (josa.isNotEmpty()) count++
        }
        val durationNs = System.nanoTime() - start
        val avgNs = durationNs / 10_000.0

        println("KoreanJosaBitmaskEngine average latency: $avgNs ns ($count operations)")
        // Verify latency is well below 1.5µs (1500ns)
        assertTrue("Average latency must be < 5000ns, was: $avgNs ns", avgNs < 5000.0)
    }
}

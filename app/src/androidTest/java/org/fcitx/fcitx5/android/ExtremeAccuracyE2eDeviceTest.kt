/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.filters.MediumTest
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.SentenceRelevanceReranker
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.JosaKind
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter.RuleResult
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-Device E2E Instrumental Tests for Extreme Accuracy & Syntax/Phonology Verification.
 *
 * Validates:
 * 1. Phonological Josa bitmask decomposition and attachment on Android ART runtime.
 * 2. Stage 1 Syntax Rule Filter for ACC-01, ACC-02, ACC-04 violations.
 * 3. SentenceRelevanceReranker filtering ungrammatical/mismatched continuation candidates.
 * 4. Deterministic automatic correction of Josa mismatches.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ExtremeAccuracyE2eDeviceTest {

    @Test
    fun testOnDeviceJosaBitmaskDecomposition() {
        // 1. 모음 종결 (T == 0, 받침 없음)
        val vowelChars = listOf('사', '나', '바', '가', '너', '보')
        for (c in vowelChars) {
            assertEquals(0, KoreanJosaBitmaskEngine.getBatchimCode(c))
            assertFalse(KoreanJosaBitmaskEngine.hasBatchim(c))
            assertFalse(KoreanJosaBitmaskEngine.isRieulBatchim(c))
            assertEquals(KoreanJosaBitmaskEngine.FLAG_NO_BATCHIM, KoreanJosaBitmaskEngine.getPhonologicalFlags(c))

            assertEquals("는", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.EUN_NEUN))
            assertEquals("가", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.I_GA))
            assertEquals("를", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.EUL_REUL))
            assertEquals("와", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.GWA_WA))
            assertEquals("로", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.EURO_RO))

            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "는"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "은"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "가"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "이"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "를"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "을"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "와"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "과"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "로"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "으로"))
        }

        // 2. 'ㄹ' 받침 종결 (T == 8, FLAG_RIEUL_BATCHIM)
        val rieulChars = listOf('달', '물', '칼', '길', '발', '말')
        for (c in rieulChars) {
            assertEquals(8, KoreanJosaBitmaskEngine.getBatchimCode(c))
            assertTrue(KoreanJosaBitmaskEngine.hasBatchim(c))
            assertTrue(KoreanJosaBitmaskEngine.isRieulBatchim(c))
            val flags = KoreanJosaBitmaskEngine.getPhonologicalFlags(c)
            assertTrue((flags and KoreanJosaBitmaskEngine.FLAG_RIEUL_BATCHIM) != 0)
            assertTrue((flags and KoreanJosaBitmaskEngine.FLAG_HAS_BATCHIM) != 0)

            assertEquals("은", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.EUN_NEUN))
            assertEquals("이", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.I_GA))
            assertEquals("을", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.EUL_REUL))
            assertEquals("과", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.GWA_WA))
            // 핵심 불변량: 'ㄹ' 받침 뒤에는 '으로'가 아닌 '로'가 결합되어야 한다.
            assertEquals("로", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.EURO_RO))

            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "은"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "는"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "이"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "가"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "을"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "를"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "과"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "와"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "로"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "으로"))
        }

        // 3. 'ㄹ' 제외 일반 자음 받침 종결 (T != 0 && T != 8, FLAG_NON_RIEUL_BATCHIM)
        val nonRieulChars = listOf('책', '밥', '집', '님', '산', '옷')
        for (c in nonRieulChars) {
            assertTrue(KoreanJosaBitmaskEngine.getBatchimCode(c) > 0)
            assertTrue(KoreanJosaBitmaskEngine.hasBatchim(c))
            assertFalse(KoreanJosaBitmaskEngine.isRieulBatchim(c))
            val flags = KoreanJosaBitmaskEngine.getPhonologicalFlags(c)
            assertTrue((flags and KoreanJosaBitmaskEngine.FLAG_NON_RIEUL_BATCHIM) != 0)
            assertTrue((flags and KoreanJosaBitmaskEngine.FLAG_HAS_BATCHIM) != 0)

            assertEquals("은", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.EUN_NEUN))
            assertEquals("이", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.I_GA))
            assertEquals("을", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.EUL_REUL))
            assertEquals("과", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.GWA_WA))
            // 핵심 불변량: 일반 자음 받침 뒤에는 '로'가 아닌 '으로'가 결합되어야 한다.
            assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa(c, JosaKind.EURO_RO))

            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "은"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "는"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "이"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "가"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "을"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "를"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "과"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "와"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(c, "으로"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(c, "로"))
        }
    }

    @Test
    fun testOnDeviceSyntaxRuleFilterAccCases() {
        val filter = KoreanSyntaxRuleFilter()

        // ACC-01: 선행 이유절(-아서/어서/여서/느라고) 뒤 의문/명령/청유 호응 오류 차단
        val acc01Rejects = listOf(
            "답장이 늦어서 무슨 일인가요?",
            "비가 와서 우산을 쓰세요",
            "비가 와서 우산을 쓰십시오",
            "시간이 없어서 서두르자",
            "회의가 길어져서 어떡하죠?",
            "밥을 먹느라고 늦었어?"
        )
        for (text in acc01Rejects) {
            val result = filter.check(text)
            assertTrue("Expected ACC-01 violation for '$text'", result is RuleResult.Invalid)
            assertEquals(ViolationType.ACC_01_CAUSAL_SUBORDINATION, (result as RuleResult.Invalid).violationType)
            assertFalse(filter.isValid(text))
            assertFalse(KoreanSyntaxRuleFilter.isGrammaticallySound(text))
        }

        val acc01Valids = listOf(
            "답장이 늦어서 죄송합니다.",
            "비가 와서 길이 미끄럽습니다.",
            "비가 오니까 무슨 일인가요?",
            "비가 오니 우산을 쓰세요"
        )
        for (text in acc01Valids) {
            assertTrue(filter.isValid(text))
            assertTrue(KoreanSyntaxRuleFilter.isGrammaticallySound(text))
        }

        // ACC-02: 목적어 격조사(-을/를) 뒤 자동사 술어(감사/고맙/기쁘/슬프/죄송/미안) 직접 결합 오류 차단
        val acc02Rejects = listOf(
            "고마운 마음을 정말 감사해요",
            "마음을 감사합니다",
            "도움을 감사드립니다",
            "따뜻한 배려를 너무 고마워요",
            "선물을 고맙습니다",
            "그 소식을 슬픕니다",
            "좋은 소식을 기쁩니다"
        )
        for (text in acc02Rejects) {
            val result = filter.check(text)
            assertTrue("Expected ACC-02 violation for '$text'", result is RuleResult.Invalid)
            assertEquals(ViolationType.ACC_02_INTRANSITIVE_OBJECT, (result as RuleResult.Invalid).violationType)
            assertFalse(filter.isValid(text))
            assertFalse(KoreanSyntaxRuleFilter.isGrammaticallySound(text))
        }

        val acc02Valids = listOf(
            "마음을 전합니다",
            "도움에 감사합니다",
            "따뜻한 배려에 감사드립니다",
            "소식에 슬픕니다",
            "좋은 소식에 기쁩니다",
            "선물을 전해 드립니다"
        )
        for (text in acc02Valids) {
            assertTrue(filter.isValid(text))
            assertTrue(KoreanSyntaxRuleFilter.isGrammaticallySound(text))
        }

        // ACC-04: 한 문맥 내 격식체(-ㅂ니다/-해요)와 비격식체(-어/-지/-냐) 혼용 오류 차단
        val acc04Rejects = listOf(
            "안녕하세요. 밥 먹었어?",
            "감사합니다. 내일 봐.",
            "안녕. 내일 뵙겠습니다.",
            "회의 참석하겠습니다. 어디야?",
            "안녕하세요 밥 먹었어?",
            "오늘 일정 공유합니다. 확인해줘."
        )
        for (text in acc04Rejects) {
            val result = filter.check(text)
            assertTrue("Expected ACC-04 violation for '$text'", result is RuleResult.Invalid)
            assertEquals(ViolationType.ACC_04_FORMALITY_INCONSISTENCY, (result as RuleResult.Invalid).violationType)
            assertFalse(filter.isValid(text))
            assertFalse(KoreanSyntaxRuleFilter.isGrammaticallySound(text))
        }

        val acc04Valids = listOf(
            "안녕하세요. 오늘 일정 공유해 드립니다.",
            "안녕하세요. 밥 먹었어요?",
            "안녕. 밥 먹었어?",
            "오늘 회의 참석합니다. 잘 부탁해요.",
            "내일 봐. 잘 자."
        )
        for (text in acc04Valids) {
            assertTrue(filter.isValid(text))
            assertTrue(KoreanSyntaxRuleFilter.isGrammaticallySound(text))
        }
    }

    @Test
    fun testOnDeviceSentenceRelevanceRerankerFiltersMismatches() {
        val packageName = "net.chanpaca.saegeul.test"
        val context = "회의 일정"

        val candidates = listOf(
            // ACC-01 비문 후보
            AiPrediction(
                text = "회의 일정이 늦어서 무슨 일인가요?",
                confidenceScore = 0.95f,
                isSentenceCompletion = true
            ),
            // ACC-02 비문 후보
            AiPrediction(
                text = "회의 일정을 정말 감사해요",
                confidenceScore = 0.90f,
                isSentenceCompletion = true
            ),
            // ACC-04 비문 후보
            AiPrediction(
                text = "안녕하세요. 회의 일정 확인해줘.",
                confidenceScore = 0.85f,
                isSentenceCompletion = true
            ),
            // 조사 불일치 후보 ("선생님는" -> "선생님은"으로 교정 또는 배제)
            AiPrediction(
                text = "회의 일정에 선생님는 참석하십니다",
                confidenceScore = 0.80f,
                isSentenceCompletion = true
            ),
            // 정상 후보 1
            AiPrediction(
                text = "회의 일정 공유해 드립니다",
                confidenceScore = 0.70f,
                isSentenceCompletion = true
            ),
            // 정상 후보 2
            AiPrediction(
                text = "회의 일정 확인했습니다",
                confidenceScore = 0.60f,
                isSentenceCompletion = true
            )
        )

        val reranked = SentenceRelevanceReranker.rerank(
            sentences = candidates,
            contextBeforeCursor = context,
            ngram = null,
            packageName = packageName,
            limit = 10
        )

        // ACC 비문 후보 및 조사 불일치 텍스트가 결과에서 완전 배제되었는지 검증
        assertFalse("ACC-01 비문은 배제되어야 한다", reranked.any { it.text.contains("무슨 일인가요") })
        assertFalse("ACC-02 비문은 배제되어야 한다", reranked.any { it.text.contains("정말 감사해요") })
        assertFalse("ACC-04 비문은 배제되어야 한다", reranked.any { it.text.contains("확인해줘") })
        assertFalse("조사 불일치 텍스트 '선생님는'은 배제되어야 한다", reranked.any { it.text.contains("선생님는") })

        // 남은 후보들은 모두 문법적으로 건전하고 조사가 올바른지 검증
        for (pred in reranked) {
            assertTrue("남은 후보는 문법적으로 올바라야 한다: ${pred.text}", KoreanSyntaxRuleFilter.isGrammaticallySound(pred.text, context))
            assertFalse("남은 후보는 조사 불일치가 없어야 한다: ${pred.text}", KoreanJosaBitmaskEngine.hasJosaMismatch(pred.text))
        }

        // 정상 후보는 보존되어야 한다
        assertTrue("정상 후보 '공유해 드립니다'가 포함되어야 한다", reranked.any { it.text.contains("공유해 드립니다") })
    }

    @Test
    fun testOnDeviceJosaMismatchAutoCorrection() {
        // "선생님는 훌륭하다" -> "선생님은 훌륭하다"
        val text1 = "선생님는 훌륭하다"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(text1))
        val corrected1 = KoreanJosaBitmaskEngine.correctJosaMismatch(text1)
        assertEquals("선생님은 훌륭하다", corrected1)
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch(corrected1))

        // "사과을 먹었다" -> "사과를 먹었다"
        val text2 = "사과을 먹었다"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(text2))
        val corrected2 = KoreanJosaBitmaskEngine.correctJosaMismatch(text2)
        assertEquals("사과를 먹었다", corrected2)
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch(corrected2))

        // 'ㄹ' 받침 + 으로/로: "하늘으로 날아갔다" -> "하늘로 날아갔다"
        val text3 = "하늘으로 날아갔다"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(text3))
        val corrected3 = KoreanJosaBitmaskEngine.correctJosaMismatch(text3)
        assertEquals("하늘로 날아갔다", corrected3)
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch(corrected3))

        // 일반 자음 받침 + 으로/로: "집로 가자" -> "집으로 가자"
        val text4 = "집로 가자"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(text4))
        val corrected4 = KoreanJosaBitmaskEngine.correctJosaMismatch(text4)
        assertEquals("집으로 가자", corrected4)
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch(corrected4))

        // 모음 종결 + 과/와: "친구과 대화했다" -> "친구와 대화했다"
        val text5 = "친구과 대화했다"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(text5))
        val corrected5 = KoreanJosaBitmaskEngine.correctJosaMismatch(text5)
        assertEquals("친구와 대화했다", corrected5)
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch(corrected5))

        // 'ㄹ' 받침 + 과/와: "연필와 공책" -> "연필과 공책"
        val text6 = "연필와 공책"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(text6))
        val corrected6 = KoreanJosaBitmaskEngine.correctJosaMismatch(text6)
        assertEquals("연필과 공책", corrected6)
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch(corrected6))

        // 다중 조사 오류가 포함된 문장 교정
        val multiMismatch = "선생님는 사과을 하늘으로 던졌다"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(multiMismatch))
        val multiCorrected = KoreanJosaBitmaskEngine.correctJosaMismatch(multiMismatch)
        assertEquals("선생님은 사과를 하늘로 던졌다", multiCorrected)
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch(multiCorrected))
    }
}

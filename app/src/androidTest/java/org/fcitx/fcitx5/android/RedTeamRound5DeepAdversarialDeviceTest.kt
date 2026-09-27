/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.filters.MediumTest
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.morphology.KoreanMorphologicalEndingAnalyzer
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.JosaKind
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter.RuleResult
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Red Team Round 5 Extreme Deep Adversarial Instrumentation Device Test.
 *
 * Scenarios:
 * 1. RED-DEEP-01: 7 Irregular verbal conjugations & ending decomposition destruction.
 * 2. RED-DEEP-02: Complex auxiliary particle (josa) chains & coda resolution ambiguity.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RedTeamRound5DeepAdversarialDeviceTest {

    // =========================================================================
    // 1. RED-DEEP-01: 불규칙 용언 활용 & 어미 결합 파괴
    // =========================================================================
    @Test
    fun testRedDeep01_IrregularConjugationAndEndingDestruction() {
        // --- (1) 7대 불규칙 활용형 올바른 형태(O) vs 비문법적 형태(X) 쌍 검증 ---
        val irregularPairs = listOf(
            // ㄷ 불규칙: 걷다 -> 걸어서 / 걷어서, 듣다 -> 들으니 / 듣으니
            Pair("걸어서", "걷어서"),
            Pair("들으니", "듣으니"),
            // ㅂ 불규칙: 돕다 -> 도와서 / 돕아서, 춥다 -> 추워서 / 춥어서, 아름답다 -> 아름다워서 / 아름답아서
            Pair("도와서", "돕아서"),
            Pair("추워서", "춥어서"),
            Pair("아름다워서", "아름답아서"),
            // ㅅ 불규칙: 짓다 -> 지어서 / 짓어서, 낫다 -> 나아서 / 낫아서
            Pair("지어서", "짓어서"),
            Pair("나아서", "낫아서"),
            // 르 불규칙: 흐르다 -> 흘러서 / 흐러서, 빠르다 -> 빨라서 / 빠라서
            Pair("흘러서", "흐러서"),
            Pair("빨라서", "빠라서"),
            // ㅎ 불규칙: 하얗다 -> 하얘서 / 하얗아서, 파랗다 -> 파래서 / 파랗아서
            Pair("하얘서", "하얗아서"),
            Pair("파래서", "파랗아서"),
            // 우 불규칙: 푸다 -> 퍼서 / 푸어서
            Pair("퍼서", "푸어서"),
            // 여 불규칙: 하다 -> 해서 / 하어서
            Pair("해서", "하어서")
        )

        for ((validForm, invalidForm) in irregularPairs) {
            // 올바른 활용형은 비문으로 판별되지 않아야 함
            assertFalse(
                "올바른 활용형 '$validForm'은 비문으로 판정되어서는 안 됩니다.",
                KoreanMorphologicalEndingAnalyzer.isInvalidIrregularConjugation(validForm)
            )
            // 잘못된 활용형은 비문으로 감지되어야 함
            assertTrue(
                "비문법적 활용형 '$invalidForm'은 비문으로 감지되어야 합니다.",
                KoreanMorphologicalEndingAnalyzer.isInvalidIrregularConjugation(invalidForm)
            )
            // 비문형은 extractEnding 시 null을 반환해야 함
            assertNull(
                "비문법적 활용형 '$invalidForm'은 어미가 정상 추출되어서는 안 됩니다 (null 반환 필요).",
                KoreanMorphologicalEndingAnalyzer.extractEnding(invalidForm)
            )
        }

        // --- (2) extractEnding() 종결/접속 어미 정확 추출 검증 ---
        assertEquals("어서", KoreanMorphologicalEndingAnalyzer.extractEnding("걸어서"))
        assertEquals("아서", KoreanMorphologicalEndingAnalyzer.extractEnding("도와서"))
        assertEquals("어서", KoreanMorphologicalEndingAnalyzer.extractEnding("지어서"))
        assertEquals("어서", KoreanMorphologicalEndingAnalyzer.extractEnding("흘러서"))
        assertEquals("아서", KoreanMorphologicalEndingAnalyzer.extractEnding("하얘서"))
        assertEquals("어서", KoreanMorphologicalEndingAnalyzer.extractEnding("추워서"))
        assertEquals("아서", KoreanMorphologicalEndingAnalyzer.extractEnding("빨라서"))
        assertEquals("어서", KoreanMorphologicalEndingAnalyzer.extractEnding("퍼서"))
        assertEquals("여서", KoreanMorphologicalEndingAnalyzer.extractEnding("해서"))
        assertEquals("으니", KoreanMorphologicalEndingAnalyzer.extractEnding("들으니"))

        // 존칭 결합형 검증
        assertEquals("아서요", KoreanMorphologicalEndingAnalyzer.extractEnding("도와서요"))
        assertEquals("어서요", KoreanMorphologicalEndingAnalyzer.extractEnding("흘러서요"))
        assertEquals("아서요", KoreanMorphologicalEndingAnalyzer.extractEnding("하얘서요"))

        // --- (3) KoreanSyntaxRuleFilter.check() 이유절-결과 호응(ACC-01) 정상 판단 검증 ---
        val syntaxFilter = KoreanSyntaxRuleFilter()

        // ACC-01 위반 (불규칙 이유절 뒤 명령/청유/의문문 결합)
        val acc01Violations = listOf(
            "물이 넘쳐흘러서 지금 출발하세요",
            "날씨가 너무 추워서 외투를 입으세요",
            "빨리 도와서 끝내자",
            "얼굴이 하얘서 병원에 가보세요",
            "시간이 빨라서 서두르십시오",
            "물을 퍼서 마시지 마세요",
            "회의가 늦어져서 지금 출발하세요"
        )

        for (sentence in acc01Violations) {
            val result = syntaxFilter.check(sentence)
            assertTrue(
                "문장 '$sentence'는 ACC-01 위반으로 기각되어야 합니다.",
                result is RuleResult.Invalid && result.violationType == ViolationType.ACC_01_CAUSAL_SUBORDINATION
            )
        }

        // 정상 문장 (불규칙 이유절 뒤 평서문 결합 -> 통과)
        val acc01ValidSentences = listOf(
            "물이 흘러서 바다로 갑니다",
            "날씨가 추워서 외투를 입었습니다",
            "도와주셔서 진심으로 감사합니다",
            "얼굴이 하얘서 건강해 보입니다",
            "시간이 빨라서 먼저 일어났습니다"
        )

        for (sentence in acc01ValidSentences) {
            val result = syntaxFilter.check(sentence)
            assertTrue(
                "정상 문장 '$sentence'는 통과(Valid)되어야 합니다. Actual: $result",
                result is RuleResult.Valid
            )
        }
    }

    // =========================================================================
    // 2. RED-DEEP-02: 복합 보조사 체인 결합 & 종성 모호성
    // =========================================================================
    @Test
    fun testRedDeep02_ComplexAuxiliaryJosaChainAndCodaAmbiguity() {
        // --- (1) 체언 + 격조사/보조사 연쇄 뒤의 조사 판별 ---
        // '서'는 받침 없음 -> "는"
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("학교에서", JosaKind.EUN_NEUN))
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("집에서", JosaKind.EUN_NEUN))
        // '터'는 받침 없음 -> "는"
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("마을로부터", JosaKind.EUN_NEUN))
        // '게'는 받침 없음 -> "를"
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("선생님에게", JosaKind.EUL_REUL))
        // '로'는 받침 없음 -> "는"
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("값으로", JosaKind.EUN_NEUN))
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("닭으로", JosaKind.EUN_NEUN))

        // isValidAttachment 검증
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("학교에서", "는"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("학교에서", "은"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("마을로부터", "는"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("마을로부터", "은"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("선생님에게", "를"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("선생님에게", "을"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("값으로", "는"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("값으로", "은"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("닭으로", "는"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("닭으로", "은"))

        // --- (2) correctJosaMismatch() 불일치 감지 및 자동 교정 검증 ---
        assertEquals("학교에서는", KoreanJosaBitmaskEngine.correctJosaMismatch("학교에서은"))
        assertEquals("마을로부터는", KoreanJosaBitmaskEngine.correctJosaMismatch("마을로부터은"))
        assertEquals("선생님에게를", KoreanJosaBitmaskEngine.correctJosaMismatch("선생님에게을"))
        assertEquals("값으로는", KoreanJosaBitmaskEngine.correctJosaMismatch("값으로은"))
        assertEquals("닭으로는", KoreanJosaBitmaskEngine.correctJosaMismatch("닭으로은"))

        // hasJosaMismatch 검증
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch("학교에서은"))
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch("학교에서는"))
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch("마을로부터은"))
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch("마을로부터는"))
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch("선생님에게을"))
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch("선생님에게를"))

        // --- (3) 복합 조사 '으로부터 / 로부터' 확장 체인 검증 ---
        assertEquals("로부터", KoreanJosaBitmaskEngine.selectJosa("마을", JosaKind.EUROBUTEOR_ROBUTEOR))
        assertEquals("으로부터", KoreanJosaBitmaskEngine.selectJosa("집", JosaKind.EUROBUTEOR_ROBUTEOR))
        assertEquals("로부터", KoreanJosaBitmaskEngine.selectJosa("학교", JosaKind.EUROBUTEOR_ROBUTEOR))
        assertEquals("으로부터", KoreanJosaBitmaskEngine.selectJosa("값", JosaKind.EUROBUTEOR_ROBUTEOR))
        assertEquals("으로부터", KoreanJosaBitmaskEngine.selectJosa("닭", JosaKind.EUROBUTEOR_ROBUTEOR))

        assertEquals("마을로부터", KoreanJosaBitmaskEngine.correctJosaMismatch("마을으로부터"))
        assertEquals("집으로부터", KoreanJosaBitmaskEngine.correctJosaMismatch("집로부터"))
    }
}

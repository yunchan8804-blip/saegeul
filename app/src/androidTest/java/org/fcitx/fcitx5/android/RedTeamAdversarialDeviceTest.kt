/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.filters.MediumTest
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.JosaKind
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Red Team Round 3 Extreme Adversarial Device Instrumentation Test.
 *
 * Exhaustive adversarial attack scenarios against on-device AI and engine components:
 * - RED-PHONO-HARD: Emoji, consonant abbreviations, rare coda, symbol/currency attachment destruction.
 * - RED-SYNTAX-HARD: Subordinate causal connective precision discrimination & accusative-intransitive discord.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RedTeamAdversarialDeviceTest {

    // =========================================================================
    // a. RED-PHONO-HARD: 이모지, 자음축약어, 희귀 받침, 숫자/기호 종성 결합 파괴 공격
    // =========================================================================
    @Test
    fun testRedPhonoHard_AdversarialEmojiJamoAndRareJongseongAttack() {
        // 1. 희귀 및 된소리 종성 결합 공격 검증
        // 솥(ㅌ받침: 25) -> '으로'(O) vs '로'(X)
        assertTrue("솥 뒤에는 '으로'가 결합해야 한다", KoreanJosaBitmaskEngine.isValidAttachment("솥", "으로"))
        assertFalse("솥 뒤에는 '로'가 결합할 수 없다", KoreanJosaBitmaskEngine.isValidAttachment("솥", "로"))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("솥", JosaKind.EURO_RO))
        assertEquals("솥으로", KoreanJosaBitmaskEngine.attachJosa("솥", JosaKind.EURO_RO))

        // 꽃(ㅊ받침: 23) -> '을'(O) vs '를'(X)
        assertTrue("꽃 뒤에는 '을'이 결합해야 한다", KoreanJosaBitmaskEngine.isValidAttachment("꽃", "을"))
        assertFalse("꽃 뒤에는 '를'이 결합할 수 없다", KoreanJosaBitmaskEngine.isValidAttachment("꽃", "를"))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("꽃", JosaKind.EUL_REUL))
        assertEquals("꽃을", KoreanJosaBitmaskEngine.attachJosa("꽃", JosaKind.EUL_REUL))

        // 빛(ㅊ받침: 23) -> '으로'(O) vs '빛로'(X)
        assertTrue("빛 뒤에는 '으로'가 결합해야 한다", KoreanJosaBitmaskEngine.isValidAttachment("빛", "으로"))
        assertFalse("빛 뒤에는 '로'가 결합할 수 없다", KoreanJosaBitmaskEngine.isValidAttachment("빛", "로"))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("빛", JosaKind.EURO_RO))
        assertEquals("빛으로", KoreanJosaBitmaskEngine.attachJosa("빛", JosaKind.EURO_RO))

        // 2. 기호 및 금액 종성 결합 공격 검증 ('원'은 ㄴ받침: 4)
        // 100원 -> '은'(O), 1000원 -> '은'(O), 1000000원 -> '으로'(O) vs '1000000원로'(X)
        assertTrue("100원 뒤에는 '은'이 결합해야 한다", KoreanJosaBitmaskEngine.isValidAttachment("100원", "은"))
        assertFalse("100원 뒤에는 '는'이 결합할 수 없다", KoreanJosaBitmaskEngine.isValidAttachment("100원", "는"))
        assertTrue("1000원 뒤에는 '은'이 결합해야 한다", KoreanJosaBitmaskEngine.isValidAttachment("1000원", "은"))
        assertFalse("1000원 뒤에는 '는'이 결합할 수 없다", KoreanJosaBitmaskEngine.isValidAttachment("1000원", "는"))
        assertTrue("1000000원 뒤에는 '으로'가 결합해야 한다", KoreanJosaBitmaskEngine.isValidAttachment("1000000원", "으로"))
        assertFalse("1000000원 뒤에는 '로'가 결합할 수 없다", KoreanJosaBitmaskEngine.isValidAttachment("1000000원", "로"))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("1000000원", JosaKind.EURO_RO))
        assertEquals("1000000원으로", KoreanJosaBitmaskEngine.attachJosa("1000000원", JosaKind.EURO_RO))

        // 희귀 종성 및 금액 문맥 오결합 자동 교정 검증
        val rareWrongSentence = "솥로 밥을 짓고 꽃를 보며 빛로 걸어갔다"
        val rareCorrected = KoreanJosaBitmaskEngine.correctJosaMismatch(rareWrongSentence)
        assertEquals("솥으로 밥을 짓고 꽃을 보며 빛으로 걸어갔다", rareCorrected)

        val moneyWrongSentence = "100원는 적지만 1000원는 크고 1000000원로 정산했다"
        val moneyCorrected = KoreanJosaBitmaskEngine.correctJosaMismatch(moneyWrongSentence)
        assertEquals("100원은 적지만 1000원은 크고 1000000원으로 정산했다", moneyCorrected)

        // 3. 이모지 뒤 조사 결합 공격: 👍는(O) vs 👍은(X), ❤️를(O) vs ❤️을(X), 🎉로(O) vs 🎉으로(X)
        // [레드팀 취약점 탐지]: 이모지(Surrogate pair / Unicode symbol) 결합 시의 조사 교정 검증
        val emojiWrong = "👍은 최고이고 ❤️을 보내며 🎉으로 축하합니다"
        val emojiCorrected = KoreanJosaBitmaskEngine.correctJosaMismatch(emojiWrong)
        assertEquals("👍는 최고이고 ❤️를 보내며 🎉로 축하합니다", emojiCorrected)

        // 4. 자음 축약어 결합 공격: ㅋㅋ는(O) vs ㅋㅋ은(X), ㅇㅈ을(O, 지읒 받침), ㄹㅇ으로(O, 이응 받침), ㄱㄹ로(O, 리을 받침)
        // [레드팀 취약점 탐지]: 호환 자모(ㅋ, ㅈ, ㅇ, ㄹ) 종성 음운 해석 및 자동 교정 검증
        val jamoWrong = "ㅋㅋ은 웃기고 ㅇㅈ를 외치며 ㄹㅇ로 진짜고 ㄱㄹ으로 간다"
        val jamoCorrected = KoreanJosaBitmaskEngine.correctJosaMismatch(jamoWrong)
        assertEquals("ㅋㅋ는 웃기고 ㅇㅈ을 외치며 ㄹㅇ으로 진짜고 ㄱㄹ로 간다", jamoCorrected)
    }

    // =========================================================================
    // b. RED-SYNTAX-HARD: 복합 접속어미의 정밀 구문 판별 공격
    // =========================================================================
    @Test
    fun testRedSyntaxHard_ComplexConnectiveAndSyntacticSoundnessAttack() {
        // 1. -어서/아서/여서 vs -으니까/니까 정밀 구문 판별 공격
        // Rule: -어서/아서/여서는 청유/의문/명령 결합 불가(비문 차단), -으니까/니까는 청유/의문/명령 완전 허용(정문 통과)

        // Case 1: "비가 오니까 우산 챙기세요" (정문 -> 통과)
        assertTrue(
            "'-니까' 뒤 명령문('챙기세요')은 문법적으로 정문이어야 한다",
            KoreanSyntaxRuleFilter.isGrammaticallySound("비가 오니까 우산 챙기세요")
        )

        // Case 2: "비가 와서 우산 챙기세요" (비문 -> 차단)
        assertFalse(
            "'-아서/어서' 뒤 명령문('챙기세요')은 구문상 비문으로 차단되어야 한다",
            KoreanSyntaxRuleFilter.isGrammaticallySound("비가 와서 우산 챙기세요")
        )

        // Case 3: "시간이 없으니까 빨리 가자" (정문 -> 통과)
        assertTrue(
            "'-니까' 뒤 청유문('가자')은 문법적으로 정문이어야 한다",
            KoreanSyntaxRuleFilter.isGrammaticallySound("시간이 없으니까 빨리 가자")
        )

        // Case 4: "시간이 없어서 빨리 가자" (비문 -> 차단)
        assertFalse(
            "'-아서/어서' 뒤 청유문('가자')은 구문상 비문으로 차단되어야 한다",
            KoreanSyntaxRuleFilter.isGrammaticallySound("시간이 없어서 빨리 가자")
        )

        // 2. 부사격 조사 '으로' vs 목적격 조사 '을/를' + 감사/고마움 술어 결합 공격
        // Case 5: "감사한 마음으로 인사드립니다" (정문 -> 통과)
        assertTrue(
            "부사격 조사 '으로' 결합 인사/감사는 문법적으로 완전한 정문이어야 한다",
            KoreanSyntaxRuleFilter.isGrammaticallySound("감사한 마음으로 인사드립니다")
        )

        // Case 6: "감사한 마음을 너무나 고마워요" (비문 -> 차단)
        assertFalse(
            "목적격 조사 '을/를' 뒤 자동사/형용사 술어('고마워요') 직접 결합은 비문으로 차단되어야 한다",
            KoreanSyntaxRuleFilter.isGrammaticallySound("감사한 마음을 너무나 고마워요")
        )
    }
}

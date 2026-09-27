/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.filters.MediumTest
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.FLAG_HAS_BATCHIM
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.FLAG_NON_RIEUL_BATCHIM
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.FLAG_NO_BATCHIM
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.FLAG_RIEUL_BATCHIM
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.JosaKind
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Red Team Round 7 Adversarial Fuzzing & Multilingual Code-Switching Chaos Instrumentation Device Test.
 *
 * Scenarios:
 * 1. RED-FUZZ-01: Multilingual Korean-English code-switching & punctuation barrage syntax integrity.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RedTeamRound7AdversarialFuzzingDeviceTest {

    // =========================================================================
    // 1. RED-FUZZ-01: 한영 혼용 코드스위칭 & 문장부호 난타 조사/통사 무결성
    // =========================================================================
    @Test
    fun testRedFuzz01_MultilingualCodeSwitchingAndPunctuationSyntaxIntegrity() {
        val filter = KoreanSyntaxRuleFilter()

        // 1) 스펙 지정 텍스트 호응 및 문장 무결성 검증
        val sampleTexts = listOf(
            "PR 올렸으니 check 부탁드립니다",
            "API endpoint가 404 error 나서 hotfix 했습니다",
            "이거 zoom 링크인가요?",
            "Github repo에 push 완료! LGTM 주시면 감사하겠습니다"
        )

        for (text in sampleTexts) {
            val result = filter.check(text)
            assertTrue(
                "스펙 텍스트 '$text'는 통사 호응 검사를 정상 통과해야 합니다. Result: $result",
                result is KoreanSyntaxRuleFilter.RuleResult.Valid
            )
            assertTrue(
                "isGrammaticallySound('$text')는 true를 반환해야 합니다.",
                KoreanSyntaxRuleFilter.isGrammaticallySound(text)
            )
        }

        // 문장부호 난타가 포함된 변형 문장 검증
        val punctuationBarrageTexts = listOf(
            "PR 올렸으니 check 부탁드립니다!!!!!",
            "API endpoint가 404 error 나서 hotfix 했습니다...",
            "이거 zoom 링크인가요???",
            "Github repo에 push 완료!! LGTM 주시면 감사하겠습니다^^"
        )

        for (text in punctuationBarrageTexts) {
            assertTrue(
                "문장부호 난타 텍스트 '$text'도 정상 통과해야 합니다.",
                KoreanSyntaxRuleFilter.isGrammaticallySound(text)
            )
        }

        // 2) KoreanJosaBitmaskEngine 영문 끝 글자 종성 판별 및 조사 선택/부착 무결성 단언

        // PR: Acronym ending in 'R' -> 'ㄹ' 받침
        val prFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("PR")
        assertEquals(FLAG_HAS_BATCHIM or FLAG_RIEUL_BATCHIM, prFlags)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("PR", JosaKind.EUN_NEUN))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("PR", JosaKind.I_GA))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("PR", JosaKind.EUL_REUL))
        assertEquals("과", KoreanJosaBitmaskEngine.selectJosa("PR", JosaKind.GWA_WA))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("PR", JosaKind.EURO_RO)) // 'ㄹ' 받침은 '로'
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("PR", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("PR", "으로"))

        // check: Ending in '-ck' -> 'ㄱ' 받침
        val checkFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("check")
        assertEquals(FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM, checkFlags)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("check", JosaKind.EUN_NEUN))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("check", JosaKind.I_GA))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("check", JosaKind.EUL_REUL))
        assertEquals("과", KoreanJosaBitmaskEngine.selectJosa("check", JosaKind.GWA_WA))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("check", JosaKind.EURO_RO)) // 비'ㄹ' 받침은 '으로'
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("check", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("check", "로"))

        // API: Acronym ending in 'I' -> 모음 종결
        val apiFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("API")
        assertEquals(FLAG_NO_BATCHIM, apiFlags)
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("API", JosaKind.EUN_NEUN))
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa("API", JosaKind.I_GA))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("API", JosaKind.EUL_REUL))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("API", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("API", "가"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("API", "이"))

        // endpoint: Ending in '-t' -> 발음상 모음(으) 덧붙음 / 무받침
        val endpointFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("endpoint")
        assertEquals(FLAG_NO_BATCHIM, endpointFlags)
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa("endpoint", JosaKind.I_GA))
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("endpoint", JosaKind.EUN_NEUN))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("endpoint", JosaKind.EUL_REUL))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("endpoint", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("endpoint", "가"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("endpoint", "이"))

        // zoom: Ending in '-m' -> 'ㅁ' 받침
        val zoomFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("zoom")
        assertEquals(FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM, zoomFlags)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("zoom", JosaKind.EUN_NEUN))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("zoom", JosaKind.I_GA))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("zoom", JosaKind.EUL_REUL))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("zoom", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("zoom", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("zoom", "로"))

        // repo: Ending in '-o' -> 모음 종결
        val repoFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("repo")
        assertEquals(FLAG_NO_BATCHIM, repoFlags)
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("repo", JosaKind.EUN_NEUN))
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa("repo", JosaKind.I_GA))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("repo", JosaKind.EUL_REUL))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("repo", JosaKind.EURO_RO))

        // LGTM: Acronym ending in 'M' -> 'ㅁ' 받침
        val lgtmFlags = KoreanJosaBitmaskEngine.getEnglishPhonologicalFlags("LGTM")
        assertEquals(FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM, lgtmFlags)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("LGTM", JosaKind.EUN_NEUN))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("LGTM", JosaKind.I_GA))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("LGTM", JosaKind.EUL_REUL))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("LGTM", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("LGTM", "이"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("LGTM", "가"))
    }
}

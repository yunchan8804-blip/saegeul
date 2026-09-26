/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.PersonalNgramTokenizer
import org.junit.Assert
import org.junit.Test

/**
 * Red Team Adversarial Unit Tests: PII leak prevention across flexible formatting,
 * placeholder token sanitization from n-gram learning, Korean phonological particle stemming,
 * robust prior score computation under extreme/negative inputs, and regex replacement safety.
 */
class RedTeamPiiTokenizerAndMorphologyChaosTest {

    /**
     * Test 1: PII Scrubber must handle flexible spacing around delimiters in phone numbers,
     * Korean resident registration numbers (RRN), and credit cards without leaking sensitive digits.
     */
    @Test
    fun `pii scrubber catches flexible spaced phone, rrn, and card numbers preventing leaks`() {
        val phoneInput = "제 번호는 010 - 1234 - 5678 입니다."
        val scrubbedPhone = KoreanPiiScrubber.scrub(phoneInput)
        Assert.assertFalse("Phone number fragment 010 must not be present in scrubbed output", scrubbedPhone.contains("010"))
        Assert.assertFalse("Phone number fragment 5678 must not be present in scrubbed output", scrubbedPhone.contains("5678"))
        Assert.assertTrue("Phone placeholder [전화번호] must be present in scrubbed output", scrubbedPhone.contains("[전화번호]"))

        val rrnInput = "주민번호 950101 - 1234567 입니다."
        val scrubbedRrn = KoreanPiiScrubber.scrub(rrnInput)
        Assert.assertFalse("RRN birth fragment 950101 must not be present in scrubbed output", scrubbedRrn.contains("950101"))
        Assert.assertFalse("RRN tail fragment 1234567 must not be present in scrubbed output", scrubbedRrn.contains("1234567"))
        Assert.assertTrue("RRN placeholder [주민번호] must be present in scrubbed output", scrubbedRrn.contains("[주민번호]"))

        val cardInput = "카드번호 1234 - 5678 - 9012 - 3456 입니다."
        val scrubbedCard = KoreanPiiScrubber.scrub(cardInput)
        Assert.assertFalse("Card fragment 1234 must not be present in scrubbed output", scrubbedCard.contains("1234"))
        Assert.assertFalse("Card fragment 3456 must not be present in scrubbed output", scrubbedCard.contains("3456"))
        Assert.assertTrue("Card placeholder [카드번호] must be present in scrubbed output", scrubbedCard.contains("[카드번호]"))
    }

    /**
     * Test 2: PII placeholder tokens with attached particles (e.g. "[전화번호]로", "[이메일]을")
     * must be completely discarded and not pollute personal n-gram learning.
     */
    @Test
    fun `pii placeholder tokens with attached particles are dropped from ngram learning`() {
        val input = "연락처는 [전화번호]로 보내주시고 [이메일]을 확인하세요"
        val tokens = PersonalNgramTokenizer.tokenize(input)

        val forbiddenFragments = listOf("[전화번호]로", "전화번호]로", "전화번호]", "[이메일]을", "이메일]을", "이메일]")
        for (frag in forbiddenFragments) {
            Assert.assertFalse("Tokens should not contain PII relic: $frag", tokens.contains(frag))
        }
        for (token in tokens) {
            Assert.assertFalse(
                "Token should not contain bracket or placeholder residue: $token",
                token.contains("전화번호") || token.contains("이메일") || token.contains("[") || token.contains("]")
            )
        }

        Assert.assertEquals(listOf("연락처는", "보내주시고", "확인하세요"), tokens)
    }

    /**
     * Test 3: Korean grammatical particle stemming must observe phonological constraints
     * (batchim agreement). Inseparable nouns must not be decomposed into invalid stem + particle.
     */
    @Test
    fun `korean grammatical particle stemming preserves non-decomposable nouns without batchim mismatch`() {
        // Words without batchim followed by batchim-required particles must NOT be stemmed
        // '아이' -> '아' (no batchim) + '이' (requires batchim) -> invalid split -> null
        Assert.assertNull(PersonalNgramTokenizer.stem("아이"))
        // '오이' -> '오' (no batchim) + '이' (requires batchim) -> invalid split -> null
        Assert.assertNull(PersonalNgramTokenizer.stem("오이"))
        // '사과' -> '사' (no batchim) + '과' (requires batchim) -> invalid split -> null
        Assert.assertNull(PersonalNgramTokenizer.stem("사과"))
        // '가을' -> '가' (no batchim) + '을' (requires batchim) -> invalid split -> null
        Assert.assertNull(PersonalNgramTokenizer.stem("가을"))

        // Valid pairs with correct batchim agreement:
        Assert.assertEquals("밥", PersonalNgramTokenizer.stem("밥이"))
        Assert.assertEquals("사과", PersonalNgramTokenizer.stem("사과를"))
        Assert.assertEquals("밥", PersonalNgramTokenizer.stem("밥을"))
        Assert.assertEquals("선생님", PersonalNgramTokenizer.stem("선생님과"))
    }

    /**
     * Test 4: personalPrior must safely handle negative or extreme negative inputs
     * without producing Float.NaN.
     */
    @Test
    fun `personalPrior negative count never returns Float NaN`() {
        val p1 = PersonalNgramModel.personalPrior(-5.0f)
        Assert.assertFalse("personalPrior(-5.0f) should not be NaN", p1.isNaN())
        Assert.assertEquals(1.0f, p1, 0.001f)

        val p2 = PersonalNgramModel.personalPrior(Float.NEGATIVE_INFINITY)
        Assert.assertFalse("personalPrior(-Infinity) should not be NaN", p2.isNaN())
        Assert.assertEquals(1.0f, p2, 0.001f)
    }

    /**
     * Test 5: PII scrubber regex replacement must not crash when matching patterns
     * in text containing special regex replacement characters like '$' and '\'.
     */
    @Test
    fun `pii scrubber regex replacement with dollar and backslash never crashes`() {
        val raw = "비밀번호: 1234 \$100 \\temp"
        val scrubbed = KoreanPiiScrubber.scrub(raw)
        Assert.assertTrue(
            "Scrubbed output should contain sanitized text: $scrubbed",
            scrubbed.contains("비밀번호: [인증코드] \$100 \\temp")
        )
        Assert.assertEquals("비밀번호: [인증코드] \$100 \\temp", scrubbed)
    }
}

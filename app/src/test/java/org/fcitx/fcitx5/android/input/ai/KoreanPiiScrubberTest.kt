/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for KoreanPiiScrubber.
 * Verifies zero-leak PII masking for phone numbers, RRN, card numbers, accounts, emails, and OTPs.
 */
class KoreanPiiScrubberTest {

    @Test
    fun testPhoneNumberScrubbing() {
        val input = "내 번호는 010-1234-5678 이야. 사무실은 02-555-1234 로 연락줘."
        val scrubbed = KoreanPiiScrubber.scrub(input)
        assertTrue(KoreanPiiScrubber.containsPii(input))
        assertFalse(scrubbed.contains("010-1234-5678"))
        assertFalse(scrubbed.contains("02-555-1234"))
        assertTrue(scrubbed.contains("[전화번호]"))
    }

    @Test
    fun testPhoneNumberWithKoreanParticleScrubbing() {
        val input = "연락처는 010-1234-5678이고 다시 알려드릴게요."
        val scrubbed = KoreanPiiScrubber.scrub(input)

        assertTrue(KoreanPiiScrubber.containsPii(input))
        assertFalse(scrubbed.contains("010-1234-5678"))
        assertEquals("연락처는 [전화번호]이고 다시 알려드릴게요.", scrubbed)
    }

    @Test
    fun testPhoneNumberWithKoreanPrefixScrubbing() {
        val input = "연락처010-1234-5678로 보내주세요."
        val scrubbed = KoreanPiiScrubber.scrub(input)

        assertTrue(KoreanPiiScrubber.containsPii(input))
        assertFalse(scrubbed.contains("010-1234-5678"))
        assertEquals("연락처[전화번호]로 보내주세요.", scrubbed)
    }

    @Test
    fun testLongerPhoneDigitSequenceIsNotScrubbedAsSubstring() {
        val input = "식별 번호 010123456789를 확인하세요."

        assertFalse(KoreanPiiScrubber.containsPii(input))
        assertEquals(input, KoreanPiiScrubber.scrub(input))
    }

    @Test
    fun testRrnScrubbing() {
        val input = "주민등록번호는 950101-1234567 입니다."
        val scrubbed = KoreanPiiScrubber.scrub(input)
        assertTrue(KoreanPiiScrubber.containsPii(input))
        assertFalse(scrubbed.contains("950101-1234567"))
        assertTrue(scrubbed.contains("[주민번호]"))
    }

    @Test
    fun testCardAndAccountScrubbing() {
        val cardInput = "결제 카드는 1234-5678-9012-3456 번입니다."
        val cardScrubbed = KoreanPiiScrubber.scrub(cardInput)
        assertTrue(KoreanPiiScrubber.containsPii(cardInput))
        assertFalse(cardScrubbed.contains("1234-5678-9012-3456"))
        assertTrue(cardScrubbed.contains("[카드번호]"))

        val accountInput = "신한은행 계좌번호 110-123-456789 로 보내주세요."
        val accountScrubbed = KoreanPiiScrubber.scrub(accountInput)
        assertTrue(KoreanPiiScrubber.containsPii(accountInput))
        assertFalse(accountScrubbed.contains("110-123-456789"))
        assertTrue(accountScrubbed.contains("[계좌번호]"))
    }

    @Test
    fun testEmailScrubbing() {
        val input = "자료는 developer@saegul.im 으로 송부 부탁드립니다."
        val scrubbed = KoreanPiiScrubber.scrub(input)
        assertTrue(KoreanPiiScrubber.containsPii(input))
        assertFalse(scrubbed.contains("developer@saegul.im"))
        assertTrue(scrubbed.contains("[이메일]"))
    }

    @Test
    fun testOtpScrubbing() {
        val input = "인증번호: 829401 입력해주세요."
        val scrubbed = KoreanPiiScrubber.scrub(input)
        assertTrue(KoreanPiiScrubber.containsPii(input))
        assertFalse(scrubbed.contains("829401"))
        assertTrue(scrubbed.contains("[인증코드]"))
    }

    @Test
    fun testCleanTextPreservation() {
        val clean = "오늘 저녁에 판교에서 삼겹살 먹을까? 시간 괜찮아?"
        assertFalse(KoreanPiiScrubber.containsPii(clean))
        assertEquals(clean, KoreanPiiScrubber.scrub(clean))
    }
}

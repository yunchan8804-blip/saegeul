/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import java.util.regex.Pattern

/**
 * Intelligent On-Device PII (Personally Identifiable Information) Scrubber.
 * Ensures zero-leak privacy by sanitizing sensitive user data (phone numbers,
 * resident registration numbers, credit cards, bank accounts, emails, OTPs)
 * before text is admitted to any staging buffer or LLM profiler.
 */
object KoreanPiiScrubber {

    // 010-1234-5678, 01012345678, 011-123-4567, 02-1234-5678
    private val PHONE_PATTERN = Pattern.compile(
        """\b(?:01[016789]|02|0[3-9][0-9])[-.\s]?\d{3,4}[-.\s]?\d{4}\b"""
    )

    // Korean Resident Registration Number: 900101-1234567, 020505-3456789
    private val RRN_PATTERN = Pattern.compile(
        """\b\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])[-.\s]?[1-8]\d{6}\b"""
    )

    // Credit Card: 16-digit card numbers with optional separators
    private val CARD_PATTERN = Pattern.compile(
        """\b(?:\d{4}[-.\s]?){3}\d{4}\b"""
    )

    // Bank Account: e.g. 110-123-456789, 302-1234-5678-01
    private val ACCOUNT_PATTERN = Pattern.compile(
        """\b(?:\d{3,6}[-.\s]){2,3}\d{2,6}\b"""
    )

    // Email: e.g. user@example.com
    private val EMAIL_PATTERN = Pattern.compile(
        """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""
    )

    // OTP / Verification Code (4-8 digits after keyword or standalone token)
    private val OTP_PATTERN = Pattern.compile(
        """(?i)(?:인증(?:번호|코드)?|비밀번호|비번|otp|code)[:\s]*([0-9]{4,8})\b"""
    )

    /**
     * Scrubs all detected PII from the input string and replaces them with safe tags.
     */
    fun scrub(input: String): String {
        if (input.isBlank()) return input

        var text = input

        // 1. Email
        text = EMAIL_PATTERN.matcher(text).replaceAll("[이메일]")

        // 2. RRN (주민등록번호)
        text = RRN_PATTERN.matcher(text).replaceAll("[주민번호]")

        // 3. Credit Card
        text = CARD_PATTERN.matcher(text).replaceAll("[카드번호]")

        // 4. Phone Number
        text = PHONE_PATTERN.matcher(text).replaceAll("[전화번호]")

        // 5. Bank Account (matches remaining separated number groups)
        text = ACCOUNT_PATTERN.matcher(text).replaceAll("[계좌번호]")

        // 6. OTP / Verification codes
        val otpMatcher = OTP_PATTERN.matcher(text)
        val sb = StringBuffer()
        while (otpMatcher.find()) {
            val fullMatch = otpMatcher.group(0) ?: ""
            val code = otpMatcher.group(1) ?: ""
            otpMatcher.appendReplacement(sb, fullMatch.replace(code, "[인증코드]"))
        }
        otpMatcher.appendTail(sb)
        text = sb.toString()

        return text
    }

    /**
     * Checks whether the given text contains any detectable PII.
     */
    fun containsPii(input: String): Boolean {
        if (input.isBlank()) return false
        return EMAIL_PATTERN.matcher(input).find() ||
            RRN_PATTERN.matcher(input).find() ||
            CARD_PATTERN.matcher(input).find() ||
            PHONE_PATTERN.matcher(input).find() ||
            ACCOUNT_PATTERN.matcher(input).find() ||
            OTP_PATTERN.matcher(input).find()
    }
}

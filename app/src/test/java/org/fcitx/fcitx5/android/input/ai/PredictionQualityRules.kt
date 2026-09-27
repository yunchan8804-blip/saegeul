/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * 추천 품질 스윕 하네스(PredictionQualitySweepTest, PersonalizedPredictionSweepTest)가 함께 쓰는
 * 정본 검사 함수 모음. SYNTAX([org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter]),
 * SPACING([org.fcitx.fcitx5.android.input.ai.rule.KoreanSpacingLint]), TONE([KoreanToneClassifier])는
 * 이미 프로덕션 정본 규칙이 있어 각 하네스가 그 클래스를 직접 호출한다. 여기서는 하네스 전용으로
 * 정의된 DUP·ECHO·JUNK 판정만 한 곳에 모아 두 하네스가 동일한 기준으로 판정하게 한다.
 */
object PredictionQualityRules {

    /** 후보 텍스트 안에 인접 음절/어절 반복이 있으면 true. */
    fun hasDuplication(text: String): Boolean {
        val words = text.split(' ', '\n').filter(String::isNotBlank)
        if (words.zipWithNext().any { (a, b) -> a == b }) return true
        return Regex("([가-힣])\\1{1,}").containsMatchIn(text)
    }

    /** 후보가 입력(스트로크/문맥)을 그대로 되풀이했으면 true. */
    fun isEcho(strokeTrim: String, contextTrim: String, text: String): Boolean =
        (strokeTrim.isNotBlank() && text == strokeTrim) || (contextTrim.isNotBlank() && text == contextTrim)

    /** 이상 문자(단독 호환 자모)나 80자를 넘는 비정상적으로 긴 후보, 허용 문자 밖의 문자가 있으면 true. */
    fun isJunk(text: String): Boolean {
        if (text.length > 80) return true
        // 호환 자모 블록(U+3131~U+318E): 단독 초성/중성/종성(ㄱ, ㅏ 등)이 섞인 경우.
        if (text.any { it.code in 0x3131..0x318E }) return true
        val allowed = Regex("^[가-힣a-zA-Z0-9\\s.,!?~…:;()\\[\\]{}'\"·%\\-–—/@#&*+=]*$")
        return !allowed.matches(text)
    }
}

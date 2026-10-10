/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

/** 어떤 어절도 오타로 보지 않는 증거. */
internal val NO_TYPO_EVIDENCE = TypoEvidence(typoCorrectionFor = { null }, isWordPrefix = { false })

/**
 * [corrections]의 (어절 to 교정어)만 오타로 판정하는 증거. [wordPrefixes]에 있는 어절은 사전 단어의 앞부분으로 본다.
 * 어절이 같은 교정어를 가리켜도(`몇시에` to `몇시에`) 오타 증거는 있는 것이다.
 */
internal fun typoEvidenceOf(
    vararg corrections: Pair<String, String>,
    wordPrefixes: Set<String> = emptySet()
): TypoEvidence {
    val byToken = corrections.toMap()
    return TypoEvidence(typoCorrectionFor = { token -> byToken[token] }, isWordPrefix = { token -> token in wordPrefixes })
}

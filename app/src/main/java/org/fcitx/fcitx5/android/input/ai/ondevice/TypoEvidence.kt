/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.text.SentenceBoundary

/**
 * 쓰는 중인 문장의 어절이 오타라는 증거. 모델이 문장을 다시 썼다는 사실만으로는 오타가 있었다는 뜻이 아니므로,
 * 대체 후보는 이 증거가 있는 어절에서만 만든다. 기본값이 없는 필수 인자라서, 판정을 안 넘기면 조용히
 * "증거 없음"이 되어 대체가 사라지는 일이 컴파일 단계에서 막힌다.
 *
 * @param typoCorrectionFor 어절 [token]이 사전에 없고 키보드 교정기가 비용 상한 안에서 교정어를 찾았을 때 그 교정어.
 *   기본 어휘나 개인 단어에 있거나 교정어를 못 찾으면 null.
 * @param isWordPrefix [token]이 어떤 사전 단어의 앞부분인지(치는 중인 어절을 오타로 보지 않기 위한 것).
 */
class TypoEvidence(
    private val typoCorrectionFor: (token: String) -> String?,
    private val isWordPrefix: (token: String) -> Boolean
) {

    /**
     * [token]을 고칠 교정어, 오타 증거가 없으면 null. [stillTyping]이 참이면 [token]은 아직 치는 중일 수 있어서
     * 사전 단어의 앞부분일 때 증거로 보지 않는다.
     */
    fun correctionFor(token: String, stillTyping: Boolean): String? {
        val correction = typoCorrectionFor(token) ?: return null
        if (stillTyping && isWordPrefix(token)) return null
        return correction
    }

    /**
     * 커서 앞 글 [textBeforeCursor]에서 지금 쓰는 어절(마지막 어절. 끝이 공백이면 방금 끝낸 어절)의 교정어.
     * 문장이 이미 끝났거나 어절이 없거나 오타 증거가 없으면 null.
     */
    fun correctionForTypingWord(textBeforeCursor: String): String? {
        if (textBeforeCursor.isBlank() || SentenceBoundary.endsSentence(textBeforeCursor)) return null
        val token = textBeforeCursor.trimEnd().takeLastWhile { !it.isWhitespace() }
        return correctionFor(token, stillTyping = !textBeforeCursor.last().isWhitespace())
    }
}

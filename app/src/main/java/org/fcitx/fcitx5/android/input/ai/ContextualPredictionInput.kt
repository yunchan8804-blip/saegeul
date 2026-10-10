/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.hangul.HangulSyllable
import org.fcitx.fcitx5.android.input.text.SentenceBoundary

/**
 * Resolves the raw text before the cursor and any in-progress composing text into the
 * (stroke, context) pair the contextual predictor expects. Buffered Hangul compatibility mode
 * can commit intermediate syllables to the editor while jamo for the next syllable are still
 * being composed, so the trailing word already sitting in [textBeforeCursor] must be stitched
 * back together with [composingText] rather than treated as a finished previous word.
 */
internal object ContextualPredictionInput {

    data class Resolved(val stroke: String, val context: String)

    fun rawFullContext(currentStroke: String, contextBeforeCursor: String): String = when {
        currentStroke.isBlank() -> contextBeforeCursor
        contextBeforeCursor.endsWith(currentStroke) -> contextBeforeCursor
        contextBeforeCursor.isBlank() -> currentStroke
        contextBeforeCursor.last().isWhitespace() -> contextBeforeCursor + currentStroke
        else -> "$contextBeforeCursor $currentStroke"
    }

    fun activePreedit(
        bufferedHangulPrefix: String,
        clientPreedit: String,
        enginePreedit: String
    ): String {
        val currentPreedit = clientPreedit.trim().ifEmpty { enginePreedit.trim() }
        return bufferedHangulPrefix + currentPreedit
    }

    /**
     * 일반 입력 모드는 조합 글자를 편집기 안에 setComposingText로 넣으므로 getTextBeforeCursor
     * 결과가 조합 글자로 끝난다. 이를 그대로 두고 조합 글자를 다시 붙이면 "ㄱ"이 "ㄱㄱ"이 되므로,
     * 끝이 조합 글자와 맞을 때만 그 부분을 떼어 커밋된 글만 돌려준다. 조합 커서가 중간에 있어
     * 끝이 맞지 않으면 그대로 둔다. 버퍼 모드는 [editorComposingText]가 비어 있어 항상 그대로다.
     */
    fun committedTextBeforeCursor(textBeforeCursor: String, editorComposingText: String): String =
        if (editorComposingText.isNotEmpty() && textBeforeCursor.endsWith(editorComposingText)) {
            textBeforeCursor.removeSuffix(editorComposingText)
        } else {
            textBeforeCursor
        }

    /**
     * 확정된 문장 끝에 [sentence]를 붙여 넣을 때 뒤 공백을 덧붙일지 정한다. 이미 공백·줄바꿈으로 끝나거나
     * 커서 바로 뒤 글자가 공백류면 붙이지 않는다([textAfterCursor]가 null이면 읽지 못한 것이라 붙인다).
     */
    fun shouldAppendSpace(sentence: String, textAfterCursor: String?): Boolean =
        !sentence.endsWith(" ") && !sentence.endsWith("\n") && textAfterCursor?.firstOrNull()?.isWhitespace() != true

    fun resolve(textBeforeCursor: String, composingText: String): Resolved {
        val endsWithWhitespace = textBeforeCursor.isNotEmpty() && textBeforeCursor.last().isWhitespace()

        if (endsWithWhitespace) {
            return if (composingText.isNotEmpty()) {
                Resolved(stroke = composingText, context = textBeforeCursor)
            } else {
                Resolved(stroke = "", context = textBeforeCursor)
            }
        }

        val lastWs = textBeforeCursor.indexOfLast { it.isWhitespace() }
        val token = if (lastWs >= 0) textBeforeCursor.substring(lastWs + 1) else textBeforeCursor
        val context = if (lastWs >= 0) textBeforeCursor.substring(0, lastWs + 1) else ""

        // 공백 없이 이어진 어절 안 문장 끝 부호([SentenceBoundary.TERMINALS])의 마지막 연속 구간이 끝나는 위치.
        val markEnd = token.indexOfLast(SentenceBoundary::isTerminal) + 1
        if (markEnd > 0) {
            val rest = token.substring(markEnd) + composingText
            if (rest.isNotEmpty() && isHangul(rest[0])) {
                return Resolved(stroke = rest, context = context + token.substring(0, markEnd))
            }
        }

        return if (composingText.isNotEmpty()) {
            Resolved(stroke = token + composingText, context = context)
        } else {
            Resolved(stroke = token, context = context)
        }
    }

    private fun isHangul(c: Char): Boolean = HangulSyllable.isSyllable(c) || HangulSyllable.isCompatJamo(c)
}

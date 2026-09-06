/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.typo

/**
 * 사용자가 백스페이스로 방금 입력한 어절을 지우고 다시 쓰는 흐름을 추적한다.
 * 백스페이스가 시작되면 지워지기 직전의 어절을 스냅샷으로 남겨 두고, 다음 어절 경계
 * (공백·문장부호·엔터·입력 종료)에서 최종적으로 확정된 어절과 짝지어 돌려준다.
 */
class CorrectionSessionTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 15_000
) {

    private var abandoned: String? = null
    private var sessionStartMs: Long = 0L

    /**
     * 백스페이스가 실제 삭제를 수행하기 직전에 호출한다. 세션이 없고 [wordBeforeCursor]가
     * 비어 있지 않으면 그 값을 "지워지는 어절" 스냅샷으로 삼아 세션을 시작한다. 이미 세션이
     * 진행 중이면 첫 스냅샷을 그대로 유지하고 아무 것도 하지 않는다.
     */
    fun onBackspace(wordBeforeCursor: String) {
        if (isActive()) return
        if (wordBeforeCursor.isEmpty()) return
        abandoned = wordBeforeCursor
        sessionStartMs = clock()
    }

    /**
     * 어절 경계(공백·문장 종결 부호·엔터·입력 종료)에서 호출한다. 세션이 진행 중이고
     * [finalWord]가 비어 있지 않으며 지워진 어절과 다르면 (지워진 어절, 최종 어절) 쌍을
     * 돌려준다. 그 외의 모든 경우에는 null을 돌려주며, 호출 즉시 세션을 종료한다.
     */
    fun onWordBoundary(finalWord: String): Pair<String, String>? {
        val wasActive = isActive()
        val snapshot = abandoned
        reset()
        if (!wasActive || snapshot == null) return null
        if (finalWord.isEmpty() || finalWord == snapshot) return null
        return snapshot to finalWord
    }

    /** 편집기가 바뀌는 등 진행 중이던 교정 세션의 맥락이 사라지면 세션을 취소한다. */
    fun onEditorChanged() {
        reset()
    }

    /** 세션이 시작된 뒤 [timeoutMs]가 지나면 자동으로 취소되고 false를 돌려준다. */
    fun isActive(): Boolean {
        if (abandoned == null) return false
        if (clock() - sessionStartMs > timeoutMs) {
            reset()
            return false
        }
        return true
    }

    private fun reset() {
        abandoned = null
    }
}

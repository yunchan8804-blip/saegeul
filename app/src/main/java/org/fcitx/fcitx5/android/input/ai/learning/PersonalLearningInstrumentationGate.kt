/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.learning

/**
 * 기기 테스트가 고정 문장을 입력하는 동안 사용자의 개인 학습 기록(개인 문장 금고, 개인 n-gram,
 * Typing DNA, 교정 쌍, 최근 보낸 문장 등)에 쓰지 않도록 하는 프로세스 단위 스위치다.
 * 켜져 있는 동안에도 읽기(추천)는 그대로 동작하고, 새 학습 쓰기만 건너뛴다.
 *
 * 제품 코드 경로는 이 스위치를 켜지 않는다. 계측 테스트만 켜고 끈다.
 * [org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler.pauseForInstrumentation]과
 * 같은 성격이다.
 */
object PersonalLearningInstrumentationGate {
    @Volatile
    var isPaused: Boolean = false
        private set

    fun pauseForInstrumentation() {
        isPaused = true
    }

    fun resumeAfterInstrumentation() {
        isPaused = false
    }
}

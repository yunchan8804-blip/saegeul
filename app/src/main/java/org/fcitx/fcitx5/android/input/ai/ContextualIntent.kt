/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Contextual Intent categories inferred from conversational and written context.
 */
enum class ContextualIntent {
    Scheduling,       // 일정, 약속, 시간 조율
    Inquiry,          // 질문, 문의, 확인 요청
    Proposal,         // 제안, 의견, 아이디어
    WorkProgress,     // 업무, 개발, 보고, 배포, 협업
    Gratitude,        // 감사, 격려, 칭찬
    ApologyDelay,     // 사과, 지연, 양해
    MealDaily,        // 식사, 일상 대화, 안부
    ConnectiveClause, // ~는데, ~해서 등 접속 절
    Farewell,         // 마무리, 퇴근, 인사
    Agreement,        // 동의, 수락, 긍정 확인
    Cheering,         // 응원, 축하, 격려
    StatusUpdate,     // 이동, 현황, 상태 보고
    Request,          // 정중한 요청, 부탁, 자문
    General           // 일반 문맥
}

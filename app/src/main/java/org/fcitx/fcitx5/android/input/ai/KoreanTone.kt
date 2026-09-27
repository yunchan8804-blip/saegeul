/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Korean conversational and grammatical tones.
 */
enum class KoreanTone {
    Honorific,   // 존댓말 (-습니다, -해요, -드립니다)
    Informal,    // 반말/친근 (-어, -아, -지, -자, -야)
    Business,    // 비즈니스/격식 (보고서, 공유드립니다, 검토 요청)
    Technical,   // IT/엔지니어링 (배포, 빌드, 커밋, 머지, PR, 이슈)
    Neutral      // 중립/일반
}

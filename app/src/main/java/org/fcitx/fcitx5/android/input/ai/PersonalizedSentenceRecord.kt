/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import java.util.UUID

/**
 * Data model for a personalized synthetic or learned sentence.
 */
data class PersonalizedSentenceRecord(
    val id: String = UUID.randomUUID().toString(),
    val sentence: String,
    val choseong: String = "",
    val intent: ContextualIntent = ContextualIntent.General,
    val tone: KoreanTone = KoreanTone.Honorific,
    val keywords: List<String> = emptyList(),
    val source: String = "synthetic_llm",
    var score: Float = 1.0f,
    var useCount: Int = 0,
    var lastUsedTimestamp: Long = System.currentTimeMillis(),
    val packageName: String? = null
) {
    companion object {
        const val SOURCE_USER_PHRASE = "user_phrase"
        const val SOURCE_SYNTHETIC_LLM = "synthetic_llm"
    }
}

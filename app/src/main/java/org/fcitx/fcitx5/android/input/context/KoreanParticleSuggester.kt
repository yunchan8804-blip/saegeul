/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.context

enum class KoreanParticleKind {
    Topic,
    Subject,
    Object,
    Conjunction,
    Direction,
    Copula
}

data class KoreanParticleSuggestion(
    val kind: KoreanParticleKind,
    val text: String
)

data class KoreanParticleEditorTarget(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val cursor: Int
)

data class KoreanParticleSnapshot(
    val editor: KoreanParticleEditorTarget,
    val contextTail: String,
    val suggestions: List<KoreanParticleSuggestion>
)

internal class KoreanParticleCommitGate {
    private var consumed = false

    @Synchronized
    fun claim(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }
}

/**
 * Deterministic, local-only Korean particle suggestions for an explicitly captured cursor context.
 *
 * The caller owns the privacy/editor-identity boundary. This class never performs I/O, records
 * context, or inserts text, and therefore remains independently testable.
 */
object KoreanParticleSuggester {
    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_END = 0xD7A3
    private const val JONGSEONG_COUNT = 28
    private const val RIEUL_JONGSEONG_INDEX = 8

    fun suggest(beforeCursor: CharSequence?, limit: Int = 6): List<KoreanParticleSuggestion> {
        if (limit <= 0) return emptyList()
        val syllable = lastHangulSyllable(beforeCursor) ?: return emptyList()
        val jongseong = (syllable.code - HANGUL_BASE) % JONGSEONG_COUNT
        val hasBatchim = jongseong != 0
        val hasRieulBatchim = jongseong == RIEUL_JONGSEONG_INDEX
        return listOf(
            KoreanParticleSuggestion(KoreanParticleKind.Topic, if (hasBatchim) "은" else "는"),
            KoreanParticleSuggestion(KoreanParticleKind.Subject, if (hasBatchim) "이" else "가"),
            KoreanParticleSuggestion(KoreanParticleKind.Object, if (hasBatchim) "을" else "를"),
            KoreanParticleSuggestion(KoreanParticleKind.Conjunction, if (hasBatchim) "과" else "와"),
            KoreanParticleSuggestion(
                KoreanParticleKind.Direction,
                if (hasBatchim && !hasRieulBatchim) "으로" else "로"
            ),
            KoreanParticleSuggestion(KoreanParticleKind.Copula, if (hasBatchim) "이에요" else "예요")
        ).take(limit)
    }

    private fun lastHangulSyllable(value: CharSequence?): Char? {
        val text = value?.toString()?.trimEnd().orEmpty()
        if (text.isEmpty()) return null
        val last = text.last()
        return last.takeIf { it.code in HANGUL_BASE..HANGUL_END }
    }
}

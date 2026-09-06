/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * 0ms Local Knowledge Compiler:
 * Compiles distilled [TypingDnaProfile] knowledge into the high-speed Tier-1 runtime engines
 * ([KoreanCollocationModel], [PersonalizedLexiconModel], [PersonalizedSentenceStore]),
 * and irreversibly purges the raw staging buffer in [TypingDnaVault] to enforce zero-leak privacy.
 */
class TypingDnaCompiler(
    private val collocationModel: KoreanCollocationModel,
    private val lexiconModel: PersonalizedLexiconModel,
    private val sentenceStore: PersonalizedSentenceStore,
    private val vault: TypingDnaVault? = null,
    private val repository: TypingDnaRepository? = null
) {

    /**
     * Compiles a single [PersonaDna] batch and updates runtime models.
     * [persist] writes into [TypingDnaRepository]. Pass false for startup/runtime rehydrate.
     * [analyzedSentenceCount] is the actual number of newly analyzed sentences to add.
     */
    @Synchronized
    fun compilePersona(
        persona: PersonaDna,
        persist: Boolean = true,
        analyzedSentenceCount: Int = 15
    ) {
        val isInformal = persona.dominantTone.equals("Informal", ignoreCase = true)

        // 1. Inject Bigrams into KoreanCollocationModel
        val bigramMap = mutableMapOf<String, MutableList<String>>()
        for (bg in persona.frequentBigrams) {
            val list = bigramMap.getOrPut(bg.prev) { mutableListOf() }
            if (!list.contains(bg.next)) {
                list.add(bg.next)
            }
        }
        collocationModel.injectDynamicBigrams(bigramMap, isInformal)

        // 2. Inject transitions into PersonalizedLexiconModel
        for (bg in persona.frequentBigrams) {
            lexiconModel.recordTransition(bg.prev, bg.next, persona.category)
        }

        // 3. Upsert canned sentences into PersonalizedSentenceStore
        for (phrase in persona.cannedPhrases) {
            val tone = if (isInformal) KoreanTone.Informal else KoreanTone.Honorific
            sentenceStore.upsert(
                PersonalizedSentenceRecord(
                    sentence = phrase,
                    intent = ContextualIntent.General,
                    tone = tone,
                    score = 1.5f,
                    useCount = 1
                )
            )
        }

        if (persist) {
            repository?.updatePersona(persona, analyzedSentenceCount)
        }

        // Zero-Knowledge: Purge raw staging buffer
        vault?.purge(persona.category)
    }

    /**
     * Fully compiles an entire [TypingDnaProfile] (e.g. upon app startup from persistent repository).
     * Does not increment persisted sentence counts.
     */
    @Synchronized
    fun compileFullProfile(profile: TypingDnaProfile) {
        for ((_, persona) in profile.personas) {
            compilePersona(persona, persist = false)
        }
    }
}

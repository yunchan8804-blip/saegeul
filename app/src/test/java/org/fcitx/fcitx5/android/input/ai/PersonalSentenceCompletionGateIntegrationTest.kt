/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalSentenceCompletionGateIntegrationTest {

    private val packageName = "com.example.test"

    @Test
    fun unrelatedPersonalStoreAndRagSentencesDoNotSurface() {
        val store = PersonalizedSentenceStore()
        store.upsert(userPhrase("오늘 회의 참석합니다"))
        store.upsert(userPhrase("내일 판교에서 봐요"))
        val vault = PersonalSentenceVault(clock = { 1_000_000_000L })
        vault.record("내가 회의 참석합니다", packageName)
        val predictor = predictor(store, vault)

        val results = predictor.predict("", "내가 뭘 ", packageName, 10)

        assertFalse(results.any { it.source == "personalized_style" || it.source == "rag_personal" })
    }

    @Test
    fun matchingPrefixKeepsPersonalStoreAndRagSentence() {
        val store = PersonalizedSentenceStore()
        store.upsert(userPhrase("오늘 회의 참석합니다"))
        val vault = PersonalSentenceVault(clock = { 1_000_000_000L })
        vault.record("오늘 회의 참석했습니다", packageName)
        val predictor = predictor(store, vault)

        val results = predictor.predict("", "오늘 회의 ", packageName, 10)

        assertTrue(results.any { it.source == "personalized_style" && it.text == "오늘 회의 참석합니다" })
        assertTrue(results.any { it.source == "rag_personal" && it.text == "오늘 회의 참석했습니다" })
    }

    @Test
    fun currentStrokeIsPartOfPersonalSentencePrefix() {
        val store = PersonalizedSentenceStore()
        store.upsert(userPhrase("오늘 회의 참석합니다"))
        val predictor = predictor(store, PersonalSentenceVault(clock = { 1_000_000_000L }))

        val results = predictor.predict("회", "오늘 ", packageName, 10)

        assertTrue(results.any { it.source == "personalized_style" && it.text == "오늘 회의 참석합니다" })
    }

    private fun predictor(store: PersonalizedSentenceStore, vault: PersonalSentenceVault): AiContextualPredictor =
        AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            ngram = PersonalNgramModel(clock = { 1_000_000_000L }),
            personalizedStore = store,
            personalSentenceVault = vault
        )

    private fun userPhrase(sentence: String) = PersonalizedSentenceRecord(
        sentence = sentence,
        source = PersonalizedSentenceRecord.SOURCE_USER_PHRASE
    )
}

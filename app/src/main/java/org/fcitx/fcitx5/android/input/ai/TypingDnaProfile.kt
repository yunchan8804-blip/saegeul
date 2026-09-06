/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * A learned bigram transition pair extracted from user's writing habit.
 */
data class DynamicBigram(
    val prev: String,
    val next: String,
    val weight: Float = 1.0f
)

/**
 * Persona linguistic DNA profile per application context (e.g. messenger vs work).
 */
data class PersonaDna(
    val category: String,
    val dominantTone: String = "Honorific",
    val habitualEndings: List<String> = emptyList(),
    val frequentBigrams: List<DynamicBigram> = emptyList(),
    val cannedPhrases: List<String> = emptyList()
)

/**
 * The unified user Typing DNA profile.
 * Represents the persistent, evolving linguistic fingerprint of the user on-device.
 */
data class TypingDnaProfile(
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val totalAnalyzedSentences: Int = 0,
    val personas: Map<String, PersonaDna> = emptyMap()
) {

    fun toJson(): String {
        val root = JSONObject()
        root.put("version", version)
        root.put("updatedAt", updatedAt)
        root.put("totalAnalyzedSentences", totalAnalyzedSentences)

        val personasObj = JSONObject()
        for ((cat, persona) in personas) {
            val pObj = JSONObject()
            pObj.put("category", persona.category)
            pObj.put("dominantTone", persona.dominantTone)

            val endingsArr = JSONArray()
            persona.habitualEndings.forEach { endingsArr.put(it) }
            pObj.put("habitualEndings", endingsArr)

            val bigramsArr = JSONArray()
            persona.frequentBigrams.forEach { bg ->
                val bgObj = JSONObject()
                bgObj.put("prev", bg.prev)
                bgObj.put("next", bg.next)
                bgObj.put("weight", bg.weight.toDouble())
                bigramsArr.put(bgObj)
            }
            pObj.put("frequentBigrams", bigramsArr)

            val phrasesArr = JSONArray()
            persona.cannedPhrases.forEach { phrasesArr.put(it) }
            pObj.put("cannedPhrases", phrasesArr)

            personasObj.put(cat, pObj)
        }
        root.put("personas", personasObj)
        return root.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): TypingDnaProfile {
            return runCatching {
                val root = JSONObject(jsonStr)
                val version = root.optInt("version", 1)
                val updatedAt = root.optLong("updatedAt", System.currentTimeMillis())
                val totalSentences = root.optInt("totalAnalyzedSentences", 0)

                val personasMap = mutableMapOf<String, PersonaDna>()
                val personasObj = root.optJSONObject("personas")
                if (personasObj != null) {
                    val keys = personasObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val pObj = personasObj.optJSONObject(key) ?: continue
                        val cat = pObj.optString("category", key)
                        val tone = pObj.optString("dominantTone", "Honorific")

                        val endings = mutableListOf<String>()
                        val endingsArr = pObj.optJSONArray("habitualEndings")
                        if (endingsArr != null) {
                            for (i in 0 until endingsArr.length()) {
                                val e = endingsArr.optString(i, "").trim()
                                if (e.isNotEmpty()) endings.add(e)
                            }
                        }

                        val bigrams = mutableListOf<DynamicBigram>()
                        val bigramsArr = pObj.optJSONArray("frequentBigrams")
                        if (bigramsArr != null) {
                            for (i in 0 until bigramsArr.length()) {
                                val bgObj = bigramsArr.optJSONObject(i) ?: continue
                                val prev = bgObj.optString("prev", "").trim()
                                val next = bgObj.optString("next", "").trim()
                                val weight = bgObj.optDouble("weight", 1.0).toFloat()
                                if (prev.isNotEmpty() && next.isNotEmpty()) {
                                    bigrams.add(DynamicBigram(prev, next, weight))
                                }
                            }
                        }

                        val phrases = mutableListOf<String>()
                        val phrasesArr = pObj.optJSONArray("cannedPhrases")
                        if (phrasesArr != null) {
                            for (i in 0 until phrasesArr.length()) {
                                val phrase = phrasesArr.optString(i, "").trim()
                                if (phrase.isNotEmpty()) phrases.add(phrase)
                            }
                        }

                        personasMap[key] = PersonaDna(
                            category = cat,
                            dominantTone = tone,
                            habitualEndings = endings,
                            frequentBigrams = bigrams,
                            cannedPhrases = phrases
                        )
                    }
                }

                TypingDnaProfile(
                    version = version,
                    updatedAt = updatedAt,
                    totalAnalyzedSentences = totalSentences,
                    personas = personasMap
                )
            }.getOrElse { TypingDnaProfile() }
        }
    }
}

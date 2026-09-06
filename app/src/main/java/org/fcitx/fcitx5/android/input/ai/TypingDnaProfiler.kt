/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Dual-Engine Typing DNA Profiler:
 * Analyzes accumulated user sentences and extracts the user's distinct linguistic DNA
 * (dominant tone, habitual sentence endings, high-frequency collocations, and situational phrases).
 *
 * Supports high-accuracy LLM profiling (OpenAI/Gemini/Local LLM) and an instantaneous
 * on-device statistical fallback that requires zero network access.
 */
class TypingDnaProfiler(
    private val llmCaller: ((prompt: String) -> String?)? = null
) {

    companion object {
        const val PROMPT_SYSTEM_TEMPLATE = """
당신은 한국어 언어학 및 텍스트 문체 분석 전문가입니다.
주어진 사용자의 실제 메신저/업무 대화 텍스트들을 분석하여, 이 사용자의 고유한 언어 습관(말투, 종결 어미, 자주 붙여 쓰는 단어 쌍, 단골 완성 문장)을 추출하세요.
응답은 반드시 아래 JSON 스키마를 만족하는 순수 JSON 객체 하나만 출력해야 합니다. 마크다운 코드블록이나 다른 설명은 일절 포함하지 마세요.

[JSON 스키마]
{
  "dominantTone": "Honorific" 또는 "Informal",
  "habitualEndings": ["~네용", "ㅋㅋ", "~드리겠습니다", ...],
  "frequentBigrams": [
    {"prev": "앞단어", "next": "뒷단어", "weight": 0.95},
    ...
  ],
  "cannedPhrases": [
    "완전 고마워 덕분이야!",
    "확인 후 공유드리겠습니다.",
    ...
  ]
}
"""

        fun buildPrompt(category: String, sentences: List<String>): String {
            val textSample = sentences.take(30).joinToString("\n") { "- $it" }
            return """
$PROMPT_SYSTEM_TEMPLATE

[분석 대상 카테고리]: $category
[사용자 입력 텍스트 샘플]
$textSample

[언어 DNA 분석 JSON]
""".trimIndent()
        }

        fun parseLlmResponse(category: String, rawResponse: String): PersonaDna? {
            val trimmed = rawResponse.trim()
            val jsonStr = if (trimmed.contains("```json")) {
                trimmed.substringAfter("```json").substringBefore("```").trim()
            } else if (trimmed.contains("```")) {
                trimmed.substringAfter("```").substringBefore("```").trim()
            } else {
                trimmed
            }

            val startIdx = jsonStr.indexOf('{')
            val endIdx = jsonStr.lastIndexOf('}')
            if (startIdx < 0 || endIdx <= startIdx) return null

            return runCatching {
                val clean = jsonStr.substring(startIdx, endIdx + 1)
                val obj = JSONObject(clean)
                val tone = obj.optString("dominantTone", "Honorific")

                val endings = mutableListOf<String>()
                val endingsArr = obj.optJSONArray("habitualEndings")
                if (endingsArr != null) {
                    for (i in 0 until endingsArr.length()) {
                        val e = endingsArr.optString(i, "").trim()
                        if (e.isNotEmpty()) endings.add(e)
                    }
                }

                val bigrams = mutableListOf<DynamicBigram>()
                val bigramsArr = obj.optJSONArray("frequentBigrams")
                if (bigramsArr != null) {
                    for (i in 0 until bigramsArr.length()) {
                        val bObj = bigramsArr.optJSONObject(i) ?: continue
                        val prev = bObj.optString("prev", "").trim()
                        val next = bObj.optString("next", "").trim()
                        val weight = bObj.optDouble("weight", 1.0).toFloat()
                        if (prev.isNotEmpty() && next.isNotEmpty()) {
                            bigrams.add(DynamicBigram(prev, next, weight))
                        }
                    }
                }

                val phrases = mutableListOf<String>()
                val phrasesArr = obj.optJSONArray("cannedPhrases")
                if (phrasesArr != null) {
                    for (i in 0 until phrasesArr.length()) {
                        val p = phrasesArr.optString(i, "").trim()
                        if (p.isNotEmpty()) phrases.add(p)
                    }
                }

                PersonaDna(
                    category = category,
                    dominantTone = tone,
                    habitualEndings = endings,
                    frequentBigrams = bigrams,
                    cannedPhrases = phrases
                )
            }.getOrNull()
        }
    }

    /**
     * Profiles the given sentences.
     * Uses LLM if available and successful; otherwise seamlessly falls back to on-device statistical analysis.
     */
    fun profile(category: String, sentences: List<String>): PersonaDna {
        if (sentences.isEmpty()) {
            return PersonaDna(category = category)
        }

        if (llmCaller != null) {
            val prompt = buildPrompt(category, sentences)
            val response = runCatching { llmCaller?.invoke(prompt) }.getOrNull()
            if (!response.isNullOrBlank()) {
                val parsed = parseLlmResponse(category, response)
                if (parsed != null) {
                    return parsed
                }
            }
        }

        // On-Device Statistical Fallback
        return profileOnDevice(category, sentences)
    }

    /**
     * 100% On-Device statistical profiling engine.
     * Extracts linguistic patterns with zero network overhead.
     */
    fun profileOnDevice(category: String, sentences: List<String>): PersonaDna {
        var honorificScore = 0
        var informalScore = 0

        val endingCounts = mutableMapOf<String, Int>()
        val bigramCounts = mutableMapOf<Pair<String, String>, Int>()
        val phraseCounts = mutableMapOf<String, Int>()

        val commonEndings = listOf(
            "습니다", "ㅂ니다", "해요", "드립니다", "부탁드립니다", "하세요", "이네요",
            "했어", "했지", "해봐", "먹자", "보자", "ㅋㅋ", "ㅎㅎ", "네용", "했어용", "구요", "거든"
        )

        for (s in sentences) {
            val trimmed = s.trim()
            if (trimmed.isBlank()) continue

            phraseCounts[trimmed] = (phraseCounts[trimmed] ?: 0) + 1

            // Tone inference
            if (trimmed.endsWith("습니다") || trimmed.endsWith("ㅂ니다") || trimmed.endsWith("요") ||
                trimmed.endsWith("드립니다") || trimmed.endsWith("부탁드립니다") || trimmed.endsWith("시오")
            ) {
                honorificScore += 2
            } else if (trimmed.endsWith("야") || trimmed.endsWith("어") || trimmed.endsWith("지") ||
                trimmed.endsWith("자") || trimmed.endsWith("해") || trimmed.endsWith("ㅋㅋ") || trimmed.endsWith("ㅎㅎ")
            ) {
                informalScore += 2
            }

            // Habitual endings detection
            for (ending in commonEndings) {
                if (trimmed.endsWith(ending)) {
                    endingCounts[ending] = (endingCounts[ending] ?: 0) + 1
                }
            }

            // Bigram extraction
            val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
            for (i in 0 until words.size - 1) {
                val pair = Pair(words[i], words[i + 1])
                bigramCounts[pair] = (bigramCounts[pair] ?: 0) + 1
            }
        }

        val dominantTone = if (informalScore > honorificScore || category == TypingDnaVault.CATEGORY_MESSENGER && informalScore >= honorificScore) {
            "Informal"
        } else {
            "Honorific"
        }

        val topEndings = endingCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }

        val topBigrams = bigramCounts.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (pair, count) ->
                val weight = (count.toFloat() / sentences.size.coerceAtLeast(1)).coerceIn(0.5f, 1.0f)
                DynamicBigram(pair.first, pair.second, weight)
            }

        val canned = phraseCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }

        return PersonaDna(
            category = category,
            dominantTone = dominantTone,
            habitualEndings = topEndings,
            frequentBigrams = topBigrams,
            cannedPhrases = canned
        )
    }
}

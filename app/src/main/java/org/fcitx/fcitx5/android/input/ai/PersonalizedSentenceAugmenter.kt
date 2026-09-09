package org.fcitx.fcitx5.android.input.ai

import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Background augmenter that triggers fast LLM (or fallback heuristic) synthesis
 * of anticipated personalized sentences based on user's recent conversation context,
 * and upserts them directly into [PersonalizedSentenceStore].
 */
class PersonalizedSentenceAugmenter(
    private val store: PersonalizedSentenceStore,
    private val llmCaller: (prompt: String) -> String?,
    private val fallbackSynthesizer: ((context: String) -> List<PersonalizedSentenceRecord>)? = null,
    private val debounceMs: Long = 2000L
) {
    private val lastAugmentTime = AtomicLong(0L)
    private val isInFlight = AtomicBoolean(false)

    companion object {
        const val PROMPT_SYSTEM_TEMPLATE = """
당신은 한국어 사용자의 타이핑 패턴을 학습하여 다음 말을 미리 예측·합성하는 초고속 AI 엔진입니다.
사용자가 최근 입력한 대화 문맥(Context)을 바탕으로, 이어서 작성할 가능성이 매우 높은 자연스러운 완성 문장 3~5개를 예측하여 순수 JSON 배열 형식으로만 응답하세요.

[응답 JSON 스키마]
[
  {
    "sentence": "자연스러운 완성형 한국어 문장",
    "intent": "ScheduleMeeting|LocationConfirmation|WorkProgress|CasualGreeting|UrgentNotice|ApologyExplanation",
    "tone": "Polite|Technical|Casual",
    "keywords": ["핵심키워드1", "핵심키워드2"]
  }
]
다른 설명 문구나 마크다운 태그 없이 오직 JSON 배열만 출력하세요.
"""

        fun buildPrompt(context: String): String {
            return """
$PROMPT_SYSTEM_TEMPLATE

[최근 대화 문맥]
$context

[합성 후보 문장 생성]
""".trimIndent()
        }

        fun parseCandidateJson(raw: String): List<PersonalizedSentenceRecord> {
            val trimmed = raw.trim()
            val jsonStr = if (trimmed.contains("```json")) {
                trimmed.substringAfter("```json").substringBefore("```").trim()
            } else if (trimmed.contains("```")) {
                trimmed.substringAfter("```").substringBefore("```").trim()
            } else {
                trimmed
            }

            val startIdx = jsonStr.indexOf('[')
            val endIdx = jsonStr.lastIndexOf(']')
            if (startIdx < 0 || endIdx <= startIdx) return emptyList()

            val cleanJson = jsonStr.substring(startIdx, endIdx + 1)
            val list = mutableListOf<PersonalizedSentenceRecord>()
            val array = JSONArray(cleanJson)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val sentence = obj.optString("sentence", "").trim()
                if (sentence.isEmpty()) continue

                val intentStr = obj.optString("intent", "General")
                val intent = try {
                    ContextualIntent.valueOf(intentStr)
                } catch (_: Exception) {
                    ContextualIntent.General
                }

                val toneStr = obj.optString("tone", "Honorific")
                val tone = try {
                    KoreanTone.valueOf(toneStr)
                } catch (_: Exception) {
                    KoreanTone.Honorific
                }

                val kwArray = obj.optJSONArray("keywords")
                val keywords = mutableListOf<String>()
                if (kwArray != null) {
                    for (k in 0 until kwArray.length()) {
                        val kw = kwArray.optString(k, "").trim()
                        if (kw.isNotEmpty()) keywords.add(kw)
                    }
                }

                list.add(
                    PersonalizedSentenceRecord(
                        sentence = sentence,
                        intent = intent,
                        tone = tone,
                        keywords = keywords,
                        score = 1.0f,
                        useCount = 0
                    )
                )
            }
            return list
        }
    }

    /**
     * Augments given context. Returns true if records were added to store.
     */
    fun augmentContext(packageName: String, context: String): Boolean {
        if (context.isBlank()) return false

        val now = System.currentTimeMillis()
        if (debounceMs > 0 && now - lastAugmentTime.get() < debounceMs) {
            return false
        }

        if (!isInFlight.compareAndSet(false, true)) {
            return false
        }

        try {
            lastAugmentTime.set(now)
            val prompt = buildPrompt(KoreanPiiScrubber.scrub(context))
            val response = llmCaller(prompt)

            var candidates = if (!response.isNullOrBlank()) {
                parseCandidateJson(response)
            } else {
                emptyList()
            }

            if (candidates.isEmpty() && fallbackSynthesizer != null) {
                candidates = fallbackSynthesizer.invoke(context)
            }

            if (candidates.isNotEmpty()) {
                for (rec in candidates) {
                    store.upsert(rec)
                }
                return true
            }
            return false
        } finally {
            isInFlight.set(false)
        }
    }
}

package org.fcitx.fcitx5.android.input.ai

import kotlin.math.max
import kotlin.math.min

/**
 * Tracks user interactions (selection, ignoring, rejection/deletion) of candidate sentences
 * to dynamically update their reinforcement scores in [PersonalizedSentenceStore].
 */
class ReinforcementTracker(
    private val store: PersonalizedSentenceStore,
    private val rewardStep: Float = 0.5f,
    private val decayFactor: Float = 0.9f,
    private val minScoreThreshold: Float = 0.2f,
    private val maxScore: Float = 10.0f
) {
    /**
     * Called when user selects and inserts a personalized sentence candidate chip.
     */
    @Synchronized
    fun onCandidateSelected(sentence: String, packageName: String? = null) {
        val record = store.get(sentence) ?: return
        record.score = min(maxScore, record.score + rewardStep)
        record.useCount += 1
        record.lastUsedTimestamp = System.currentTimeMillis()
        store.upsert(record)
    }

    /**
     * Called when candidates were offered but the user typed something else.
     * Mildly decays offered candidates so unused suggestions fade over time.
     */
    @Synchronized
    fun onCandidatesIgnored(offeredSentences: List<String>) {
        for (s in offeredSentences) {
            val record = store.get(s) ?: continue
            record.score = record.score * decayFactor
            if (record.score < minScoreThreshold && record.useCount == 0) {
                store.remove(s)
            } else {
                store.upsert(record)
            }
        }
    }

    /**
     * Called when user rejects or deletes a suggested sentence.
     */
    @Synchronized
    fun onCandidateRejected(sentence: String, heavyPenalty: Boolean = true) {
        if (heavyPenalty) {
            store.remove(sentence)
        } else {
            val record = store.get(sentence) ?: return
            record.score = max(0f, record.score - 1.0f)
            if (record.score < minScoreThreshold) {
                store.remove(sentence)
            } else {
                store.upsert(record)
            }
        }
    }
}

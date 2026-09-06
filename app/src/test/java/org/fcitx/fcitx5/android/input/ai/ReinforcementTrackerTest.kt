package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReinforcementTrackerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeFile: File
    private lateinit var morphology: ChoseongMorphologyEngine
    private lateinit var store: PersonalizedSentenceStore
    private lateinit var tracker: ReinforcementTracker

    @Before
    fun setUp() {
        storeFile = tempFolder.newFile("test_reinforce_store.json")
        morphology = ChoseongMorphologyEngine()
        store = PersonalizedSentenceStore(storageFile = storeFile, morphology = morphology, maxCapacity = 50)
        tracker = ReinforcementTracker(store = store, rewardStep = 0.5f, decayFactor = 0.9f)
    }

    @Test
    fun testSelectCandidateIncreasesScoreAndUseCount() {
        val record = PersonalizedSentenceRecord(
            sentence = "내일 오후 3시 판교에서 뵙겠습니다.",
            score = 1.0f,
            useCount = 0
        )
        store.upsert(record)

        tracker.onCandidateSelected(sentence = "내일 오후 3시 판교에서 뵙겠습니다.")

        val updated = store.get("내일 오후 3시 판교에서 뵙겠습니다.")
        assertEquals(1.5f, updated?.score ?: 0f, 0.01f)
        assertEquals(1, updated?.useCount ?: 0)

        // Select again
        tracker.onCandidateSelected(sentence = "내일 오후 3시 판교에서 뵙겠습니다.")
        val updated2 = store.get("내일 오후 3시 판교에서 뵙겠습니다.")
        assertEquals(2.0f, updated2?.score ?: 0f, 0.01f)
        assertEquals(2, updated2?.useCount ?: 0)
    }

    @Test
    fun testIgnoreCandidatesDecaysScore() {
        val candidate1 = PersonalizedSentenceRecord(sentence = "문장 A", score = 2.0f)
        val candidate2 = PersonalizedSentenceRecord(sentence = "문장 B", score = 1.0f)
        store.upsert(candidate1)
        store.upsert(candidate2)

        tracker.onCandidatesIgnored(listOf("문장 A", "문장 B"))

        val updatedA = store.get("문장 A")
        val updatedB = store.get("문장 B")
        assertEquals(1.8f, updatedA?.score ?: 0f, 0.01f)
        assertEquals(0.9f, updatedB?.score ?: 0f, 0.01f)
    }

    @Test
    fun testImmediateDeleteAppliesPenaltyOrEvicts() {
        val candidate = PersonalizedSentenceRecord(sentence = "원치 않는 문장입니다.", score = 0.5f)
        store.upsert(candidate)

        // Heavy penalty drops below minimum threshold -> evicts from store
        tracker.onCandidateRejected(sentence = "원치 않는 문장입니다.", heavyPenalty = true)

        assertFalse(store.contains("원치 않는 문장입니다."))
    }

    @Test
    fun testMaxScoreBound() {
        val record = PersonalizedSentenceRecord(sentence = "초인기 문장", score = 9.8f)
        store.upsert(record)

        tracker.onCandidateSelected(sentence = "초인기 문장")
        val updated = store.get("초인기 문장")
        assertEquals(10.0f, updated?.score ?: 0f, 0.01f) // Capped at maxScore = 10.0f
    }
}

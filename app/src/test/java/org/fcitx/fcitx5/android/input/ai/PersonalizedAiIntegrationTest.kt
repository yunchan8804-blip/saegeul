package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.core.CandidateWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonalizedAiIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeFile: File
    private lateinit var morphology: ChoseongMorphologyEngine
    private lateinit var semanticPredictor: KoreanSemanticSentencePredictor
    private lateinit var ngram: PersonalNgramModel
    private lateinit var store: PersonalizedSentenceStore
    private lateinit var tracker: ReinforcementTracker
    private lateinit var collector: UserTypingContextCollector
    private lateinit var predictor: AiContextualPredictor

    @Before
    fun setUp() {
        storeFile = tempFolder.newFile("test_integration_store.json")
        morphology = ChoseongMorphologyEngine()
        semanticPredictor = KoreanSemanticSentencePredictor()
        ngram = PersonalNgramModel()
        store = PersonalizedSentenceStore(storageFile = storeFile, morphology = morphology, maxCapacity = 100)
        tracker = ReinforcementTracker(store = store)
        collector = UserTypingContextCollector()

        predictor = AiContextualPredictor(
            morphology = morphology,
            semanticPredictor = semanticPredictor,
            prefetcher = null,
            personalizedStore = store,
            ngram = ngram
        )
    }

    @Test
    fun testPersonalizedSentenceAppearsWithMyStyleBadge() {
        // Preload a personalized sentence into the store. The sentence line only surfaces the
        // user's own SOURCE_USER_PHRASE records now, so it must be marked as such explicitly
        // (the default source is synthetic_llm, which no longer reaches the sentence line).
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "판교 카카오 아지트 1층 로비에서 뵙겠습니다.",
                intent = ContextualIntent.Scheduling,
                tone = KoreanTone.Honorific,
                keywords = listOf("판교", "카카오", "로비", "미팅"),
                score = 3.0f,
                source = PersonalizedSentenceRecord.SOURCE_USER_PHRASE
            )
        )

        // The current sentence is a literal prefix of the stored user phrase.
        val predictions = predictor.predict(
            currentStroke = "카카오",
            contextBeforeCursor = "판교 ",
            packageName = "com.kakao.talk",
            limit = 5
        )

        assertTrue(predictions.isNotEmpty())
        val myStyleMatch = predictions.find { it.badge == "✨ 내스타일" }
        assertNotNull(myStyleMatch)
        assertEquals("판교 카카오 아지트 1층 로비에서 뵙겠습니다.", myStyleMatch?.text)
        assertTrue(myStyleMatch?.isSentenceCompletion == true)

        val unrelatedPredictions = predictor.predict(
            currentStroke = "ㅍㄱ",
            contextBeforeCursor = "내일 약속 장소 ",
            packageName = "com.kakao.talk",
            limit = 5
        )
        assertFalse(unrelatedPredictions.any {
            it.source == "personalized_style" && it.text == "판교 카카오 아지트 1층 로비에서 뵙겠습니다."
        })
    }

    @Test
    fun testSeparateWordAndSentenceCandidates() {
        // Add a word transition to the personal n-gram model
        ngram.learn("내일 판교에서 오시면 됩니다.", "com.kakao.talk")

        // Add a sentence to personalized store
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = "내일 판교에서 오시면 됩니다.",
                intent = ContextualIntent.Scheduling,
                tone = KoreanTone.Honorific,
                keywords = listOf("판교", "테크원"),
                score = 2.0f,
                source = PersonalizedSentenceRecord.SOURCE_USER_PHRASE
            )
        )

        val allPredictions = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = "내일 ",
            packageName = "com.kakao.talk",
            limit = 6
        )

        val wordCandidates = allPredictions.filter { !it.isSentenceCompletion }
        val sentenceCandidates = allPredictions.filter { it.isSentenceCompletion }

        assertTrue(wordCandidates.any {
            it.source == "personal_ngram" && it.text == "판교에서" && !it.isSentenceCompletion
        })
        assertTrue(sentenceCandidates.any {
            it.source == "personalized_style" &&
                it.text == "내일 판교에서 오시면 됩니다." &&
                it.isSentenceCompletion
        })
    }

    @Test
    fun testSelectionReinforcementLoop() {
        val sentence = "오후 2시에 시작하도록 하겠습니다."
        store.upsert(
            PersonalizedSentenceRecord(
                sentence = sentence,
                intent = ContextualIntent.WorkProgress,
                score = 1.0f,
                useCount = 0
            )
        )

        // Initially score is 1.0
        assertEquals(1.0f, store.get(sentence)?.score ?: 0f, 0.01f)

        // Simulate user selecting this candidate chip
        tracker.onCandidateSelected(sentence)

        // Score increases
        val updated = store.get(sentence)
        assertEquals(1.5f, updated?.score ?: 0f, 0.01f)
        assertEquals(1, updated?.useCount ?: 0)
    }
}

package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonalizedSentenceAugmenterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeFile: File
    private lateinit var morphology: ChoseongMorphologyEngine
    private lateinit var store: PersonalizedSentenceStore

    @Before
    fun setUp() {
        storeFile = tempFolder.newFile("test_augmented_store.json")
        morphology = ChoseongMorphologyEngine()
        store = PersonalizedSentenceStore(storageFile = storeFile, morphology = morphology, maxCapacity = 50)
    }

    @Test
    fun testParseAugmentedJsonResponse() {
        val jsonPayload = """
            [
              {
                "sentence": "내일 오후 2시 판교역에서 뵙겠습니다.",
                "intent": "Scheduling",
                "tone": "Honorific",
                "keywords": ["내일", "오후", "판교역", "미팅"]
              },
              {
                "sentence": "도착하시면 연락 부탁드립니다.",
                "intent": "General",
                "tone": "Honorific",
                "keywords": ["도착", "연락"]
              }
            ]
        """.trimIndent()

        val parsed = PersonalizedSentenceAugmenter.parseCandidateJson(jsonPayload)
        assertEquals(2, parsed.size)
        assertEquals("내일 오후 2시 판교역에서 뵙겠습니다.", parsed[0].sentence)
        assertEquals(ContextualIntent.Scheduling, parsed[0].intent)
        assertEquals(KoreanTone.Honorific, parsed[0].tone)
        assertTrue(parsed[0].keywords.contains("판교역"))
    }

    @Test
    fun testParseMarkdownWrappedJson() {
        val markdownPayload = """
            Here are the personalized candidate sentences:
            ```json
            [
              {
                "sentence": "회의록 정리해서 공유해 드리겠습니다.",
                "intent": "WorkProgress",
                "tone": "Technical",
                "keywords": ["회의록", "공유"]
              }
            ]
            ```
        """.trimIndent()

        val parsed = PersonalizedSentenceAugmenter.parseCandidateJson(markdownPayload)
        assertEquals(1, parsed.size)
        assertEquals("회의록 정리해서 공유해 드리겠습니다.", parsed[0].sentence)
        assertEquals(ContextualIntent.WorkProgress, parsed[0].intent)
    }

    @Test
    fun testAugmentAndSaveToStore() {
        val mockLlm: (String) -> String? = { _ ->
            """
            [
              {
                "sentence": "판교 테크원타워 지하 1층 카페에서 만나요.",
                "intent": "Scheduling",
                "tone": "Honorific",
                "keywords": ["판교", "테크원타워", "카페"]
              }
            ]
            """.trimIndent()
        }

        val augmenter = PersonalizedSentenceAugmenter(
            store = store,
            llmCaller = mockLlm,
            debounceMs = 0L
        )

        val success = augmenter.augmentContext(
            packageName = "com.kakao.talk",
            context = "내일 판교에서 만날까요?"
        )

        assertTrue(success)
        assertEquals(1, store.size())

        // Verify querying by choseong 'ㅍㄱ'
        val found = store.query(queryChoseong = "ㅍㄱ", context = "", limit = 5)
        assertTrue(found.isNotEmpty())
        assertEquals("판교 테크원타워 지하 1층 카페에서 만나요.", found[0].sentence)
    }

    @Test
    fun `external augmenter prompt scrubs PII from context`() {
        var capturedPrompt: String? = null
        val augmenter = PersonalizedSentenceAugmenter(
            store = store,
            llmCaller = { prompt ->
                capturedPrompt = prompt
                "[]"
            },
            debounceMs = 0L
        )

        augmenter.augmentContext(
            packageName = "com.kakao.talk",
            context = "연락처는 010-1234-5678이고 메일은 user@example.com입니다."
        )

        val prompt = capturedPrompt
        assertNotNull(prompt)
        assertTrue(prompt!!.contains("[전화번호]"))
        assertTrue(prompt.contains("[이메일]"))
        assertFalse(prompt.contains("010-1234-5678"))
        assertFalse(prompt.contains("user@example.com"))
    }

    @Test
    fun testFallbackSynthesizerWhenLlmFails() {
        val failingLlm: (String) -> String? = { null }

        val augmenter = PersonalizedSentenceAugmenter(
            store = store,
            llmCaller = failingLlm,
            fallbackSynthesizer = { _ ->
                listOf(
                    PersonalizedSentenceRecord(
                        sentence = "확인했습니다. 곧 회신드리겠습니다.",
                        intent = ContextualIntent.General,
                        tone = KoreanTone.Honorific,
                        keywords = listOf("확인", "회신")
                    )
                )
            },
            debounceMs = 0L
        )

        val success = augmenter.augmentContext(
            packageName = "com.slack",
            context = "서버 장애 확인 부탁드립니다."
        )

        assertTrue(success)
        assertEquals(1, store.size())
        val found = store.query(queryChoseong = "ㅎㅇ", context = "", limit = 5)
        assertEquals("확인했습니다. 곧 회신드리겠습니다.", found[0].sentence)
    }
}

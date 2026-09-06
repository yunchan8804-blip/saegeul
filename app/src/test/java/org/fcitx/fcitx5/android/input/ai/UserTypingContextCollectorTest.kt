package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserTypingContextCollectorTest {

    private val triggeredContexts = mutableListOf<Pair<String, String>>()
    private lateinit var collector: UserTypingContextCollector

    @Before
    fun setUp() {
        triggeredContexts.clear()
        collector = UserTypingContextCollector(
            maxSentencesPerPackage = 3,
            maxCharLength = 150,
            minTriggerChars = 5,
            onTriggerAugmentation = { pkg, ctx ->
                triggeredContexts.add(pkg to ctx)
            }
        )
    }

    @Test
    fun testSentenceAccumulationAndPunctuationTrigger() {
        // Committing text ending with a sentence boundary (period) should trigger augmentation
        collector.recordCommittedText("com.kakao.talk", "내일 2시에 미팅 가능하신가요?")

        assertEquals(1, triggeredContexts.size)
        assertEquals("com.kakao.talk", triggeredContexts[0].first)
        assertEquals("내일 2시에 미팅 가능하신가요?", triggeredContexts[0].second)

        // Second sentence
        collector.recordCommittedText("com.kakao.talk", "장소는 판교역 1번 출구입니다.")
        assertEquals(2, triggeredContexts.size)
        assertEquals(
            "내일 2시에 미팅 가능하신가요?\n장소는 판교역 1번 출구입니다.",
            collector.getRecentContext("com.kakao.talk")
        )
    }

    @Test
    fun testPartialTypingDoesNotTriggerUntilBoundary() {
        // Typing without sentence boundary should accumulate in buffer but not trigger
        collector.recordCommittedText("com.kakao.talk", "현재 판교 ")
        assertEquals(0, triggeredContexts.size)

        collector.recordCommittedText("com.kakao.talk", "도착했습니다.")
        assertEquals(1, triggeredContexts.size)
        assertEquals("현재 판교 도착했습니다.", triggeredContexts[0].second)
    }

    @Test
    fun testSlidingWindowEviction() {
        // Max sentences is 3
        collector.recordCommittedText("com.kakao.talk", "문장 하나.")
        collector.recordCommittedText("com.kakao.talk", "문장 둘.")
        collector.recordCommittedText("com.kakao.talk", "문장 셋.")
        assertEquals(3, collector.getSentences("com.kakao.talk").size)

        // 4th sentence should evict "문장 하나."
        collector.recordCommittedText("com.kakao.talk", "문장 넷.")
        val sentences = collector.getSentences("com.kakao.talk")
        assertEquals(3, sentences.size)
        assertEquals("문장 둘.", sentences[0])
        assertEquals("문장 셋.", sentences[1])
        assertEquals("문장 넷.", sentences[2])
    }

    @Test
    fun testPackageIsolation() {
        collector.recordCommittedText("com.kakao.talk", "카카오톡 대화 내용입니다.")
        collector.recordCommittedText("com.slack", "슬랙 업무 채널 보고입니다.")

        assertEquals("카카오톡 대화 내용입니다.", collector.getRecentContext("com.kakao.talk"))
        assertEquals("슬랙 업무 채널 보고입니다.", collector.getRecentContext("com.slack"))

        assertEquals(2, triggeredContexts.size)
        assertEquals("com.kakao.talk", triggeredContexts[0].first)
        assertEquals("com.slack", triggeredContexts[1].first)
    }

    @Test
    fun testIgnoreShortOrEmpty() {
        collector.recordCommittedText("com.kakao.talk", "  ")
        collector.recordCommittedText("com.kakao.talk", "")
        assertEquals(0, triggeredContexts.size)
        assertTrue(collector.getRecentContext("com.kakao.talk").isEmpty())
    }

    @Test
    fun testClear() {
        collector.recordCommittedText("com.kakao.talk", "임시 메시지입니다.")
        assertFalse(collector.getRecentContext("com.kakao.talk").isEmpty())

        collector.clear("com.kakao.talk")
        assertTrue(collector.getRecentContext("com.kakao.talk").isEmpty())
    }
}

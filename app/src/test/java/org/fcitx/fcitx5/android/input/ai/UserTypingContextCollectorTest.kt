package org.fcitx.fcitx5.android.input.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UserTypingContextCollectorTest {

    private val triggeredContexts = mutableListOf<Pair<String, String>>()
    private val committedSentences = mutableListOf<Pair<String, String>>()
    private lateinit var collector: UserTypingContextCollector

    @Before
    fun setUp() {
        triggeredContexts.clear()
        committedSentences.clear()
        collector = UserTypingContextCollector(
            maxSentencesPerPackage = 3,
            maxCharLength = 150,
            minTriggerChars = 5,
            onTriggerAugmentation = { pkg, ctx ->
                triggeredContexts.add(pkg to ctx)
            },
            onSentenceCommitted = { pkg, sentence ->
                committedSentences.add(pkg to sentence)
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

    @Test
    fun koreanEndingWithoutLatinPunctuationIsCollectedForTypingDna() {
        // Korean-ending boundaries are now confirmed lazily: they only close once the
        // following chunk starts with whitespace, so a trailing space chunk is needed here.
        collector.recordCommittedText("com.kakao.talk", "확인했습니다")
        collector.recordCommittedText("com.kakao.talk", " ")
        assertEquals(1, committedSentences.size)
        assertEquals("확인했습니다", committedSentences[0].second)

        collector.recordCommittedText("com.kakao.talk", "완전 고마워 ㅋㅋ")
        collector.recordCommittedText("com.kakao.talk", " ")
        assertEquals("완전 고마워 ㅋㅋ", committedSentences[1].second)
    }

    @Test
    fun multiCharacterCommitWithKoreanEndingEmitsImmediately() {
        // Paste, buffered-Hangul segments, and candidate selections commit multiple characters
        // at once, unlike the per-syllable engine path, so a Korean ending emits right away.
        collector.recordCommittedText("com.kakao.talk", "확인했습니다")
        assertEquals(1, committedSentences.size)
        assertEquals("확인했습니다", committedSentences[0].second)
    }

    @Test
    fun perSyllableCommitDoesNotSplitInsideWord() {
        listOf("요", "즘", " ", "어", "때", "요", " ").forEach {
            collector.recordCommittedText("com.kakao.talk", it)
        }
        assertEquals(1, committedSentences.size)
        assertEquals("요즘 어때요", committedSentences[0].second)
    }

    @Test
    fun perSyllableCommitWithoutTrailingSpaceWaitsForFlush() {
        listOf("필", "요", "한", " ", "자", "료", " ", "보", "내", "주", "세", "요").forEach {
            collector.recordCommittedText("com.kakao.talk", it)
        }
        assertTrue(committedSentences.isEmpty())

        val flushed = collector.flushPending("com.kakao.talk")
        assertTrue(flushed)
        assertEquals("필요한 자료 보내주세요", committedSentences.single().second)
    }

    @Test
    fun punctuationStillSplitsImmediately() {
        listOf("오", "늘", " ", "뭐", "해", "?").forEach {
            collector.recordCommittedText("com.kakao.talk", it)
        }
        assertEquals(1, committedSentences.size)
        assertEquals("오늘 뭐해?", committedSentences[0].second)
    }

    @Test
    fun hasPendingReflectsBuffer() {
        // No Korean ending here, so the buffer stays pending (does not emit immediately)
        // regardless of the multi-character-commit rule.
        assertFalse(collector.hasPending("com.kakao.talk"))

        collector.recordCommittedText("com.kakao.talk", "테스트문장")
        assertTrue(collector.hasPending("com.kakao.talk"))

        collector.flushPending("com.kakao.talk")
        assertFalse(collector.hasPending("com.kakao.talk"))
    }

    @Test
    fun casualKoreanWithoutPeriodStaysPendingUntilFlush() {
        collector.recordCommittedText("com.kakao.talk", "오늘 저녁에 만나자")
        assertTrue(committedSentences.isEmpty())

        val flushed = collector.flushPending("com.kakao.talk")
        assertTrue(flushed)
        assertEquals("오늘 저녁에 만나자", committedSentences.single().second)
        assertEquals("오늘 저녁에 만나자", collector.getRecentContext("com.kakao.talk"))
    }

    @Test
    fun triggerNowAlsoRecordsPendingSentenceForTypingDna() {
        collector.recordCommittedText("com.kakao.talk", "내일 판교에서 보자")
        assertTrue(committedSentences.isEmpty())

        assertTrue(collector.triggerNow("com.kakao.talk"))
        assertEquals("내일 판교에서 보자", committedSentences.single().second)
        assertEquals(1, triggeredContexts.size)
    }

    @Test
    fun flushAllPendingDrainsEveryPackage() {
        collector.recordCommittedText("com.kakao.talk", "오늘 저녁에 만나자")
        collector.recordCommittedText("com.slack", "내일 판교에서 보자")
        assertEquals(2, collector.flushAllPending())
        val committedTexts = committedSentences.map { it.second }
        assertTrue(committedTexts.contains("오늘 저녁에 만나자"))
        assertTrue(committedTexts.contains("내일 판교에서 보자"))
    }

    @Test
    fun flushIgnoresTinyFragments() {
        collector.recordCommittedText("com.kakao.talk", "ㅇㅋ")
        assertFalse(collector.flushPending("com.kakao.talk"))
        assertTrue(committedSentences.isEmpty())
    }
}

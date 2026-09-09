/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * TDD tests for Speculative Asynchronous Background LLM Prefetcher & Smart Cache.
 * Validates context key normalization, punctuation resilience, whitespace collapsing,
 * LRU cache eviction, and cache hits.
 */
class AiSentenceCompletionPrefetcherTest {

    private lateinit var prefetcher: AiSentenceCompletionPrefetcher

    @Before
    fun setUp() {
        prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = null,
            maxCacheCapacity = 5
        )
    }

    @Test
    fun testContextKeyNormalizationCollapsesWhitespaceAndPreservesPunctuation() {
        val raw1 = "오늘  회의가  몇 시에 시작하나요?   "
        val raw2 = "오늘 회의가 몇 시에 시작하나요."
        val raw3 = "오늘 회의가 몇 시에 시작하나요"
        val raw4 = "오늘 회의가 몇 시에 시작하나요!~"

        val norm1 = prefetcher.normalizeContextKey(raw1)
        val norm2 = prefetcher.normalizeContextKey(raw2)
        val norm3 = prefetcher.normalizeContextKey(raw3)
        val norm4 = prefetcher.normalizeContextKey(raw4)

        assertEquals("오늘 회의가 몇 시에 시작하나요? ", norm1)
        assertEquals("오늘 회의가 몇 시에 시작하나요.", norm2)
        assertEquals("오늘 회의가 몇 시에 시작하나요", norm3)
        assertEquals("오늘 회의가 몇 시에 시작하나요!~", norm4)
    }

    @Test
    fun testCacheHitAcrossWhitespaceButNotPunctuationVariations() {
        val proposals = listOf(
            "오후 2시에 대회의실에서 진행됩니다.",
            "아직 확정되지 않았습니다.",
            "일정 확인 후 공유해 드리겠습니다."
        )

        // Store with trailing question mark and extra space
        prefetcher.putPredictions("오늘 회의가 몇 시에 시작하나요?  ", proposals)

        // Whitespace is normalized, but punctuation remains part of the exact context key.
        val cachedFromWhitespace = prefetcher.getCachedPredictions("오늘  회의가 몇 시에 시작하나요?\n")
        assertNotNull(cachedFromWhitespace)
        assertEquals(3, cachedFromWhitespace!!.size)
        assertEquals("오후 2시에 대회의실에서 진행됩니다.", cachedFromWhitespace[0])

        assertNull(prefetcher.getCachedPredictions("오늘 회의가 몇 시에 시작하나요."))
        assertNull(prefetcher.getCachedPredictions("오늘 회의가 몇 시에 시작하나요"))
    }

    @Test
    fun trailingWhitespaceUsesASeparateCacheKey() {
        val withoutTrailingSpace = listOf("WORD\t다음")
        val withTrailingSpace = listOf("WORD\t내용")
        prefetcher.putPredictions("회의", withoutTrailingSpace)
        prefetcher.putPredictions("회의 ", withTrailingSpace)

        assertEquals(withoutTrailingSpace, prefetcher.getCachedPredictions("회의"))
        assertEquals(withTrailingSpace, prefetcher.getCachedPredictions("회의   \n"))
    }

    @Test
    fun scheduledPrefetchPreservesTrailingWhitespaceInItsProviderInput() {
        val dispatched = CountDownLatch(1)
        val requestBody = AtomicReference<String>()
        val client = OpenAiResponsesClient(testProfile(), AiHttpTransport { _, _, body ->
            requestBody.set(body)
            dispatched.countDown()
            typedResponse()
        })
        val prefetcher = AiSentenceCompletionPrefetcher(clientProvider = { client }, diagnostics = { })

        prefetcher.schedulePrefetch("  회의 ", debounceMs = 0)

        assertTrue(dispatched.await(2, TimeUnit.SECONDS))
        assertEquals(
            "회의 ",
            Json.parseToJsonElement(requestBody.get()).jsonObject.getValue("input").jsonPrimitive.content
        )
    }

    @Test
    fun extendedContextDoesNotReuseCachedPredictions() {
        val baseContext = "오늘 점심 뭐"
        val proposals = listOf("먹을까?", "먹을래?", "추천해줘")
        prefetcher.putPredictions(baseContext, proposals)

        // Exact match
        assertEquals(proposals, prefetcher.getCachedPredictions("오늘 점심 뭐"))

        // User typed space and next character "먹" -> "오늘 점심 뭐 먹"
        assertNull(prefetcher.getCachedPredictions("오늘 점심 뭐 먹"))
    }

    @Test
    fun testLruCacheEvictionMaintainsMaxCapacity() {
        for (i in 1..10) {
            prefetcher.putPredictions("컨텍스트 번호 $i", listOf("결과 $i"))
        }

        // Only the last 5 should remain (capacity = 5)
        assertNull(prefetcher.getCachedPredictions("컨텍스트 번호 1"))
        assertNull(prefetcher.getCachedPredictions("컨텍스트 번호 5"))
        assertNotNull(prefetcher.getCachedPredictions("컨텍스트 번호 6"))
        assertNotNull(prefetcher.getCachedPredictions("컨텍스트 번호 10"))
    }

    @Test
    fun testEnrichedTypoCorrectionsInEngine() {
        val typoEngine = KoreanTypoCorrectionEngine()
        val corrected1 = typoEngine.correct("잇슴")
        assertTrue(corrected1.contains("있음"))

        val corrected2 = typoEngine.correct("알겟습니다")
        assertTrue(corrected2.contains("알겠습니다"))

        val corrected3 = typoEngine.correct("모르겟어")
        assertTrue(corrected3.contains("모르겠어"))

        val sentenceCorrected = typoEngine.correctSentence("모르겟어 내가 알겟습니다 잇슴")
        assertEquals("모르겠어 내가 알겠습니다 있음", sentenceCorrected)
    }

    @Test
    fun testLlmCachedPredictionsDifferentiateTypedWordsAndContinuations() {
        val testContext = "오늘 판교에서 회의"
        val llmProposals = listOf(
            "WORD\t안건에",
            "CONTINUATION\t오후 3시에 회의실에서 진행됩니다."
        )
        prefetcher.putPredictions(
            testContext,
            llmProposals,
            AiSentenceCompletionPrefetcher.Scope("com.kakao.talk", 0L)
        )

        val predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            prefetcher = prefetcher
        )

        val predictions = predictor.predict(
            currentStroke = "",
            contextBeforeCursor = testContext,
            packageName = "com.kakao.talk",
            limit = 6
        )

        // Verify next word candidate (안건에) is classified as word (isSentenceCompletion = false) with ✨ AI단어 badge
        val wordCandidate = predictions.firstOrNull { it.text == "안건에" }
        assertNotNull(wordCandidate)
        assertEquals(false, wordCandidate!!.isSentenceCompletion)
        assertEquals("✨ AI단어", wordCandidate.badge)

        // Verify sentence candidate is classified as sentence completion with ✨ AI완성 badge
        val sentenceCandidate = predictions.firstOrNull { it.text.contains("진행됩니다") }
        assertNotNull(sentenceCandidate)
        assertEquals(true, sentenceCandidate!!.isSentenceCompletion)
        assertEquals("✨ AI완성", sentenceCandidate.badge)

        assertEquals(ContextualAppend(testContext, "안건에"), wordCandidate.append)
        assertEquals(ContextualAppend(testContext, "오후 3시에 회의실에서 진행됩니다."), sentenceCandidate.append)
    }

    @Test
    fun clearDiscardsALateNonCooperativeResponseWithoutCachingOrRefreshing() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val diagnostics = ConcurrentLinkedQueue<String>()
        val client = OpenAiResponsesClient(
            AiProviderProfile(
                baseUrl = "https://provider.test/v1",
                apiKey = "test-key",
                fastModel = "fast-test"
            ),
            AiHttpTransport { _, _, _ ->
                started.countDown()
                while (release.count > 0) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
                    Thread.interrupted()
                }
                """{"status":"completed","output_text":"{\"suggestions\":[\"WORD\\t어떻게\",\"WORD\\t하면\",\"CONTINUATION\\t잘못했는지 모르겠어\"]}"}"""
            }
        )
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = { client },
            onPrefetchCompleted = { _, _ -> completed.countDown() },
            diagnostics = diagnostics::add
        )

        prefetcher.schedulePrefetch("내가 뭘", debounceMs = 0)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(diagnostics.contains("schedulePrefetch: dispatching"))
        prefetcher.clear()
        release.countDown()

        assertTrue(!completed.await(1, TimeUnit.SECONDS))
        assertNull(prefetcher.getCachedPredictions("내가 뭘"))
        assertTrue(diagnostics.contains("schedulePrefetch: discarded"))
        assertTrue(diagnostics.none { it.contains("내가 뭘") || it.contains("어떻게") })
    }

    @Test
    fun latestQueuedRequestRunsAfterBlockingRequestWithoutConcurrentDispatch() {
        val startedA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val completedC = CountDownLatch(1)
        val calls = ConcurrentLinkedQueue<String>()
        val callbacks = ConcurrentLinkedQueue<AiSentenceCompletionPrefetcher.RequestKey>()
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val client = OpenAiResponsesClient(testProfile(), AiHttpTransport { _, _, body ->
            val context = when {
                body.contains("문맥 A") -> "A"
                body.contains("문맥 B") -> "B"
                body.contains("문맥 C") -> "C"
                else -> "unknown"
            }
            calls.add(context)
            val active = concurrent.incrementAndGet()
            recordMax(maxConcurrent, active)
            try {
                if (context == "A") {
                    startedA.countDown()
                    assertTrue(releaseA.await(2, TimeUnit.SECONDS))
                }
                typedResponse()
            } finally {
                concurrent.decrementAndGet()
            }
        })
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = { client },
            onPrefetchCompleted = { key, _ ->
                callbacks.add(key)
                if (key.normalizedContext == "문맥 C") completedC.countDown()
            },
            diagnostics = { }
        )

        prefetcher.schedulePrefetch("문맥 A", debounceMs = 0)
        assertTrue(startedA.await(2, TimeUnit.SECONDS))
        prefetcher.schedulePrefetch("문맥 B", debounceMs = 0)
        prefetcher.schedulePrefetch("문맥 C", debounceMs = 0)
        releaseA.countDown()

        assertTrue(completedC.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("A", "C"), calls.toList())
        assertEquals(1, maxConcurrent.get())
        assertNull(prefetcher.getCachedPredictions("문맥 A"))
        assertNull(prefetcher.getCachedPredictions("문맥 B"))
        assertNotNull(prefetcher.getCachedPredictions("문맥 C"))
        assertEquals(
            listOf(AiSentenceCompletionPrefetcher.RequestKey(AiSentenceCompletionPrefetcher.Scope(), "문맥 C")),
            callbacks.toList()
        )
    }

    @Test
    fun clearThenNewRequestWaitsForBlockingRequestAndOnlyCachesNewRequest() {
        val startedA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val completedB = CountDownLatch(1)
        val calls = ConcurrentLinkedQueue<String>()
        val concurrent = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        val client = OpenAiResponsesClient(testProfile(), AiHttpTransport { _, _, body ->
            val context = if (body.contains("문맥 A")) "A" else "B"
            calls.add(context)
            val active = concurrent.incrementAndGet()
            recordMax(maxConcurrent, active)
            try {
                if (context == "A") {
                    startedA.countDown()
                    assertTrue(releaseA.await(2, TimeUnit.SECONDS))
                }
                typedResponse()
            } finally {
                concurrent.decrementAndGet()
            }
        })
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = { client },
            onPrefetchCompleted = { key, _ ->
                if (key.normalizedContext == "문맥 B") completedB.countDown()
            },
            diagnostics = { }
        )

        prefetcher.schedulePrefetch("문맥 A", debounceMs = 0)
        assertTrue(startedA.await(2, TimeUnit.SECONDS))
        prefetcher.clear()
        prefetcher.schedulePrefetch("문맥 B", debounceMs = 0)
        assertEquals(listOf("A"), calls.toList())
        releaseA.countDown()

        assertTrue(completedB.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("A", "B"), calls.toList())
        assertEquals(1, maxConcurrent.get())
        assertNull(prefetcher.getCachedPredictions("문맥 A"))
        assertNotNull(prefetcher.getCachedPredictions("문맥 B"))
    }

    @Test
    fun shortContextInvalidatesBlockingRequestBeforeItsResultCanPublish() {
        val startedA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val anyCallback = CountDownLatch(1)
        val completedB = CountDownLatch(1)
        val calls = ConcurrentLinkedQueue<String>()
        val client = OpenAiResponsesClient(testProfile(), AiHttpTransport { _, _, body ->
            val context = if (body.contains("문맥 A")) "A" else "B"
            calls.add(context)
            if (context == "A") {
                startedA.countDown()
                assertTrue(releaseA.await(2, TimeUnit.SECONDS))
            }
            typedResponse()
        })
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = { client },
            onPrefetchCompleted = { key, _ ->
                anyCallback.countDown()
                if (key.normalizedContext == "문맥 B") completedB.countDown()
            },
            diagnostics = { }
        )

        prefetcher.schedulePrefetch("문맥 A", debounceMs = 0)
        assertTrue(startedA.await(2, TimeUnit.SECONDS))
        prefetcher.schedulePrefetch(" ", debounceMs = 0)
        prefetcher.schedulePrefetch("나", debounceMs = 0)
        prefetcher.schedulePrefetch("나 ", debounceMs = 0)
        releaseA.countDown()

        assertTrue(!anyCallback.await(300, TimeUnit.MILLISECONDS))
        assertNull(prefetcher.getCachedPredictions("문맥 A"))
        prefetcher.schedulePrefetch("문맥 B", debounceMs = 0)
        assertTrue(completedB.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("A", "B"), calls.toList())
        assertNotNull(prefetcher.getCachedPredictions("문맥 B"))
    }

    @Test
    fun duplicateDesiredKeyDoesNotDispatchTwice() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val calls = AtomicInteger()
        val client = OpenAiResponsesClient(testProfile(), AiHttpTransport { _, _, _ ->
            calls.incrementAndGet()
            started.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            typedResponse()
        })
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = { client },
            onPrefetchCompleted = { _, _ -> completed.countDown() },
            diagnostics = { }
        )

        prefetcher.schedulePrefetch("같은 문맥", debounceMs = 0)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        prefetcher.schedulePrefetch("같은 문맥", debounceMs = 0)
        release.countDown()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        prefetcher.schedulePrefetch("같은 문맥", debounceMs = 0)
        assertEquals(1, calls.get())
    }

    @Test
    fun HTTPFailureConsumesDesiredRequestWithoutRetryingIt() {
        val failureReported = CountDownLatch(1)
        val calls = AtomicInteger()
        val diagnostics = ConcurrentLinkedQueue<String>()
        val client = OpenAiResponsesClient(testProfile(), AiHttpTransport { _, _, _ ->
            calls.incrementAndGet()
            throw AiHttpStatusException(429, "rate limited")
        })
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = { client },
            diagnostics = { message ->
                diagnostics.add(message)
                if (message.startsWith("schedulePrefetch: failed")) failureReported.countDown()
            }
        )

        prefetcher.schedulePrefetch("재시도 금지", debounceMs = 0)

        assertTrue(failureReported.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, calls.get())
        assertTrue(
            diagnostics.contains(
                "schedulePrefetch: failed (AiProviderException) (kind=Http, httpStatus=429)"
            )
        )
    }

    @Test
    fun clientProviderFailureConsumesDesiredRequestWithoutRetryingIt() {
        val failureReported = CountDownLatch(1)
        val calls = AtomicInteger()
        val diagnostics = ConcurrentLinkedQueue<String>()
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = {
                calls.incrementAndGet()
                throw IllegalStateException("client unavailable")
            },
            diagnostics = { message ->
                diagnostics.add(message)
                if (message.startsWith("schedulePrefetch: failed")) failureReported.countDown()
            }
        )

        prefetcher.schedulePrefetch("생성 실패", debounceMs = 0)

        assertTrue(failureReported.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, calls.get())
        assertTrue(diagnostics.contains("schedulePrefetch: failed (IllegalStateException)"))
    }

    @Test
    fun cancellationConsumesDesiredRequestWithoutRetryingIt() {
        val cancelled = CountDownLatch(1)
        val calls = AtomicInteger()
        val diagnostics = ConcurrentLinkedQueue<String>()
        val client = OpenAiResponsesClient(testProfile(), AiHttpTransport { _, _, _ ->
            calls.incrementAndGet()
            throw kotlinx.coroutines.CancellationException("transport cancelled")
        })
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = { client },
            diagnostics = { message ->
                diagnostics.add(message)
                if (message == "schedulePrefetch: cancelled") cancelled.countDown()
            }
        )

        prefetcher.schedulePrefetch("취소 실패", debounceMs = 0)

        assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, calls.get())
    }

    @Test
    fun completedPrefetchReportsOnlyRedactedMetadata() {
        val completed = CountDownLatch(1)
        val completedRequest = AtomicReference<AiSentenceCompletionPrefetcher.RequestKey>()
        val diagnostics = ConcurrentLinkedQueue<String>()
        val client = OpenAiResponsesClient(
            AiProviderProfile(
                baseUrl = "https://provider.test/v1",
                apiKey = "test-key",
                fastModel = "fast-test"
            ),
            AiHttpTransport { _, _, _ ->
                """{"status":"completed","output_text":"{\"suggestions\":[\"WORD\\t어떻게\",\"WORD\\t하면\",\"CONTINUATION\\t잘못했는지 모르겠어\"]}"}"""
            }
        )
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = { client },
            onPrefetchCompleted = { requestKey, _ ->
                completedRequest.set(requestKey)
                completed.countDown()
            },
            diagnostics = diagnostics::add
        )
        val scope = AiSentenceCompletionPrefetcher.Scope("com.example.prefetch", 12L)

        prefetcher.schedulePrefetch("내가 뭘", debounceMs = 0, scope = scope)

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(
            listOf("WORD\t어떻게", "WORD\t하면", "CONTINUATION\t잘못했는지 모르겠어"),
            prefetcher.getCachedPredictions("내가 뭘", scope)
        )
        assertEquals(
            AiSentenceCompletionPrefetcher.RequestKey(scope, "내가 뭘"),
            completedRequest.get()
        )
        assertTrue(diagnostics.contains("schedulePrefetch: dispatching"))
        assertTrue(diagnostics.contains("schedulePrefetch: result received (suggestionCount=3)"))
        assertTrue(diagnostics.none { it.contains("내가 뭘") || it.contains("어떻게") })
    }

    @Test
    fun providerFailureDiagnosticsContainOnlyTypedMetadata() {
        val failureReported = CountDownLatch(1)
        val diagnostics = ConcurrentLinkedQueue<String>()
        val rawProviderMessage = "provider-body=내가 뭘 endpoint=https://provider.test errorCode=secret"
        val client = OpenAiResponsesClient(
            AiProviderProfile(
                baseUrl = "https://provider.test/v1",
                apiKey = "test-key",
                fastModel = "fast-test"
            ),
            AiHttpTransport { _, _, _ ->
                throw AiProviderException(
                    rawProviderMessage,
                    failureKind = AiProviderFailureKind.Http,
                    httpStatus = 429
                )
            }
        )
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = { client },
            diagnostics = { message ->
                diagnostics.add(message)
                if (message.startsWith("schedulePrefetch: failed")) failureReported.countDown()
            }
        )

        prefetcher.schedulePrefetch("내가 뭘", debounceMs = 0)

        assertTrue(failureReported.await(2, TimeUnit.SECONDS))
        assertTrue(
            diagnostics.contains(
                "schedulePrefetch: failed (AiProviderException) (kind=Http, httpStatus=429)"
            )
        )
        assertTrue(diagnostics.none { it.contains(rawProviderMessage) || it.contains("내가 뭘") })
    }

    @Test
    fun validContinuationAbstentionCachesEmptyResultAndScopesIt() {
        val context = "내가 뭘"
        val firstScope = AiSentenceCompletionPrefetcher.Scope("com.example.first", 1L)
        val secondScope = AiSentenceCompletionPrefetcher.Scope("com.example.second", 1L)
        val firstPublished = CountDownLatch(1)
        val secondDispatched = CountDownLatch(1)
        val secondPublished = CountDownLatch(1)
        val callbacks = ConcurrentLinkedQueue<List<String>>()
        val calls = AtomicInteger()
        val client = OpenAiResponsesClient(
            testProfile().copy(capabilities = setOf("responses", "continuation_abstention")),
            AiHttpTransport { _, _, _ ->
                if (calls.incrementAndGet() != 1) secondDispatched.countDown()
                """{"status":"completed","output_text":"{\"suggestions\":[]}"}"""
            }
        )
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = { client },
            onPrefetchCompleted = { key, suggestions ->
                callbacks.add(suggestions)
                if (key.scope == firstScope) firstPublished.countDown()
                if (key.scope == secondScope) secondPublished.countDown()
            },
            diagnostics = { }
        )

        prefetcher.schedulePrefetch(context, debounceMs = 0, scope = firstScope)
        assertTrue(firstPublished.await(2, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), prefetcher.getCachedPredictions(context, firstScope))

        prefetcher.schedulePrefetch(context, debounceMs = 0, scope = firstScope)
        assertFalse(secondDispatched.await(250, TimeUnit.MILLISECONDS))

        prefetcher.schedulePrefetch(context, debounceMs = 0, scope = secondScope)
        assertTrue(secondDispatched.await(2, TimeUnit.SECONDS))
        assertTrue(secondPublished.await(2, TimeUnit.SECONDS))
        assertEquals(2, calls.get())
        assertEquals(listOf(emptyList<String>(), emptyList<String>()), callbacks.toList())
        assertEquals(emptyList<String>(), prefetcher.getCachedPredictions(context, secondScope))
    }

    @Test
    fun nonemptyWireResultFilteredToEmptyIsDiscarded() {
        val discarded = CountDownLatch(1)
        val callback = CountDownLatch(1)
        val client = OpenAiResponsesClient(testProfile(), AiHttpTransport { _, _, _ ->
            """{"status":"completed","output_text":"{\"suggestions\":[\"WORD\\t내가 뭘\",\"CONTINUATION\\t내가 뭘\",\"CONTINUATION_ATTACH\\t내가 뭘\"]}"}"""
        })
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = { client },
            onPrefetchCompleted = { _, _ -> callback.countDown() },
            diagnostics = { message -> if (message == "schedulePrefetch: discarded") discarded.countDown() }
        )

        prefetcher.schedulePrefetch("내가 뭘", debounceMs = 0)

        assertTrue(discarded.await(2, TimeUnit.SECONDS))
        assertFalse(callback.await(250, TimeUnit.MILLISECONDS))
        assertNull(prefetcher.getCachedPredictions("내가 뭘"))
    }

    @Test
    fun missingProviderReportsOneConnectionStateChangeWithoutAdditionalDispatch() {
        val stateChanged = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val providerCalls = AtomicInteger()
        val prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = {
                providerCalls.incrementAndGet()
                null
            },
            onConnectionStateChanged = {
                callbacks.incrementAndGet()
                stateChanged.countDown()
            },
            diagnostics = { }
        )

        prefetcher.schedulePrefetch("연결 상태", debounceMs = 0)

        assertTrue(stateChanged.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(AiPrefetchConnectionState.PROVIDER_MISSING, prefetcher.connectionState)
        assertEquals(1, callbacks.get())
        assertEquals(1, providerCalls.get())
    }

    @Test
    fun reauthenticationStateRecoversToReadyAfterSuccessfulGenerate() {
        val reauthentication = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val states = ConcurrentLinkedQueue<AiPrefetchConnectionState>()
        val client = AtomicReference<OpenAiResponsesClient?>()
        val reauthenticationClient = OpenAiResponsesClient(
            AiProviderProfile(
                kind = AiProviderKind.OpenAICompatible,
                baseUrl = "https://provider.test/v1",
                authMode = AiAuthMode.OAuthPkce,
                oauthAuthorizationEndpoint = "https://auth.test/authorize",
                oauthTokenEndpoint = "https://auth.test/token",
                oauthClientId = "test-client",
                fastModel = "fast-test"
            ),
            AiHttpTransport { _, _, _ -> error("authorization should fail before transport") },
            object : AiBearerTokenProvider {
                override suspend fun authorizationHeader(profile: AiProviderProfile): String {
                    throw AiReauthenticationRequiredException()
                }
            }
        )
        val readyClient = OpenAiResponsesClient(testProfile(), AiHttpTransport { _, _, _ -> typedResponse() })
        client.set(reauthenticationClient)
        lateinit var prefetcher: AiSentenceCompletionPrefetcher
        prefetcher = AiSentenceCompletionPrefetcher(
            clientProvider = { client.get() },
            onConnectionStateChanged = {
                val state = prefetcher.connectionState
                states.add(state)
                when (state) {
                    AiPrefetchConnectionState.REAUTH_REQUIRED -> reauthentication.countDown()
                    AiPrefetchConnectionState.READY -> ready.countDown()
                    else -> Unit
                }
            },
            diagnostics = { }
        )

        prefetcher.schedulePrefetch("인증 만료", debounceMs = 0)
        assertTrue(reauthentication.await(2, TimeUnit.SECONDS))
        assertEquals(AiPrefetchConnectionState.REAUTH_REQUIRED, prefetcher.connectionState)

        client.set(readyClient)
        prefetcher.schedulePrefetch("인증 복구", debounceMs = 0)

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        assertEquals(AiPrefetchConnectionState.READY, prefetcher.connectionState)
        assertEquals(
            listOf(AiPrefetchConnectionState.REAUTH_REQUIRED, AiPrefetchConnectionState.READY),
            states.toList()
        )
    }

    private fun testProfile() = AiProviderProfile(
        baseUrl = "https://provider.test/v1",
        apiKey = "test-key",
        fastModel = "fast-test"
    )

    private fun typedResponse(): String =
        """{"status":"completed","output_text":"{\"suggestions\":[\"WORD\\t어떻게\",\"WORD\\t하면\",\"CONTINUATION\\t잘못했는지 모르겠어\"]}"}"""

    private fun recordMax(maximum: AtomicInteger, value: Int) {
        while (true) {
            val previous = maximum.get()
            if (value <= previous || maximum.compareAndSet(previous, value)) return
        }
    }
}

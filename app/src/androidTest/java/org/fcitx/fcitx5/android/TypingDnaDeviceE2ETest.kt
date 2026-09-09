/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.ai.DynamicBigram
import org.fcitx.fcitx5.android.input.ai.KoreanCollocationModel
import org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber
import org.fcitx.fcitx5.android.input.ai.PersonaDna
import org.fcitx.fcitx5.android.input.ai.PersonalizedSentenceStore
import org.fcitx.fcitx5.android.input.ai.TypingDnaCompiler
import org.fcitx.fcitx5.android.input.ai.TypingDnaProfiler
import org.fcitx.fcitx5.android.input.ai.TypingDnaRepository
import org.fcitx.fcitx5.android.input.ai.TypingDnaVault
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaChartView
import org.fcitx.fcitx5.android.ui.main.ai.TypingDnaDashboardActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Real Android Physical Device E2E Integration & Stress Benchmark Test for Typing DNA.
 * Runs on connected hardware (e.g. Galaxy Z Fold 6 R3CX70NE9VH) to verify sub-millisecond
 * prediction latencies, real flash I/O persistence, and zero-leak PII scrubbing.
 */
class TypingDnaDeviceE2ETest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(appContext)
    }

    @Test
    fun testDevicePiiScrubber500CyclesBenchmark() {
        val testSamples = listOf(
            "내 번호는 010-9876-5432 이야. 은행은 신한 110-333-444555 로 입금해줘.",
            "주민번호 980101-1234567 이고 카드는 4567-8901-2345-6789 로 결제해.",
            "이메일은 user@saegul.dev 로 보내주시고 인증번호: 582910 입니다.",
            "오늘 저녁에 판교에서 회의 끝나고 치맥 어때? 시간 괜찮아?"
        )

        val startTime = System.nanoTime()
        for (i in 0 until 500) {
            val sample = testSamples[i % testSamples.size]
            val scrubbed = KoreanPiiScrubber.scrub(sample)
            assertFalse(scrubbed.contains("010-9876-5432"))
            assertFalse(scrubbed.contains("980101-1234567"))
            assertFalse(scrubbed.contains("user@saegul.dev"))
        }
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0
        val avgMs = elapsedMs / 500.0

        // Device hardware performance assertion: < 0.5ms per sentence scrub
        assertTrue("Scrubber avg latency must be under 0.5ms on device, actual: ${avgMs}ms", avgMs < 0.5)
    }

    @Test
    fun testDeviceVaultAccumulationAndPurge() {
        val vault = TypingDnaVault(thresholdPerCategory = 10)

        for (i in 0 until 9) {
            vault.recordSentence("com.kakao.talk", "친구야 오늘 $i 번 문장 입력이야!")
            vault.recordSentence("com.slack", "팀장님 업무 $i 차 진행 공유드립니다.")
        }

        assertEquals(9, vault.getSentences(TypingDnaVault.CATEGORY_MESSENGER).size)
        assertEquals(9, vault.getSentences(TypingDnaVault.CATEGORY_WORK).size)
        assertEquals(18, vault.totalBufferedCount())

        // Purge check
        vault.purge()
        assertEquals(0, vault.totalBufferedCount())
    }

    @Test
    fun testDeviceTypingDna0msFastPathAnd2000LookupsBenchmark() {
        val collocation = KoreanCollocationModel()
        val store = PersonalizedSentenceStore()
        val vault = TypingDnaVault()
        val repoFile = File(appContext.filesDir, "typing_dna_benchmark_test.json")
        repoFile.delete()

        val repository = TypingDnaRepository(repoFile)
        val compiler = TypingDnaCompiler(collocation, store, repository)

        val learnedPersona = PersonaDna(
            category = "messenger",
            dominantTone = "Informal",
            habitualEndings = listOf("~네용", "ㅋㅋ", "~했어용"),
            frequentBigrams = listOf(
                DynamicBigram("오늘", "칼퇴하고", 0.99f),
                DynamicBigram("내일", "판교에서", 0.95f),
                DynamicBigram("배포", "성공했어", 0.98f)
            ),
            cannedPhrases = listOf(
                "오늘 완전 고마워 덕분이야!",
                "내일 출근해서 이야기하자!"
            )
        )

        // Compile knowledge
        compiler.compilePersona(learnedPersona)

        // Verify instant prediction
        val predictions = collocation.predictNextWords("오늘", isInformal = true, limit = 5)
        assertTrue(predictions.isNotEmpty())
        assertEquals("칼퇴하고", predictions.first())

        // Stress benchmark: 2,000 rapid continuous next-word predictions on device CPU
        val lookupStart = System.nanoTime()
        for (i in 0 until 2000) {
            val res = collocation.predictNextWords("오늘", isInformal = true, limit = 5)
            assertEquals("칼퇴하고", res[0])
        }
        val lookupElapsedMs = (System.nanoTime() - lookupStart) / 1_000_000.0
        val lookupAvgMs = lookupElapsedMs / 2000.0

        // Sub-millisecond 0ms fast path assertion: < 0.1ms average on device/emulator
        assertTrue("Collocation lookup must be under 0.1ms on real device/emulator, actual: ${lookupAvgMs}ms", lookupAvgMs < 0.1)

        // Verify repository persistence on device flash
        val loaded = repository.load()
        assertTrue(loaded.personas.containsKey("messenger"))
        val summary = repository.getSummary()
        assertEquals(15, summary.totalSentences)
        assertEquals(3, summary.bigramsCount)
        assertEquals(2, summary.phrasesCount)

        // Clean up
        repository.clear()
        repoFile.delete()
    }

    @Test
    fun testDeviceStatsDtoPrivacyGaugeAndLiveSentenceIncrement() {
        val repoFile = File(appContext.filesDir, "typing_dna_stats_e2e.json")
        repoFile.delete()
        val repository = TypingDnaRepository(repoFile)

        val empty = repository.getStats()
        assertEquals(1, empty.level)
        assertEquals(0, empty.levelProgressPercent)
        assertEquals(100, empty.privacyOnDevicePercent)
        assertEquals(0, empty.cloudBytesExported)
        assertFalse(empty.hasLearnedData)

        repository.updatePersona(
            PersonaDna(
                category = "messenger",
                dominantTone = "Informal",
                habitualEndings = listOf("~해", "ㅋㅋ"),
                frequentBigrams = listOf(DynamicBigram("오늘", "칼퇴하고", 0.99f)),
                cannedPhrases = listOf("오늘 칼퇴하고 만나자")
            ),
            analyzedSentenceCount = 4
        )
        val stats = repository.getStats(forceReload = true)
        assertEquals(4, stats.totalSentences)
        assertEquals(1, stats.level)
        assertTrue(stats.hasLearnedData)
        assertEquals("오늘", stats.topBigrams.first().prev)
        assertEquals(100, stats.privacyOnDevicePercent)

        repository.clear()
        repoFile.delete()
    }

    @Test
    fun testDashboardActivityRendersSeededStatsOnDevice() {
        val repoFile = File(appContext.filesDir, "typing_dna.json")
        val backup = if (repoFile.exists()) repoFile.readText() else null
        try {
            val repository = TypingDnaRepository(repoFile)
            repository.clear()
            repository.updatePersona(
                PersonaDna(
                    category = "work",
                    dominantTone = "Honorific",
                    habitualEndings = listOf("~습니다", "~드립니다"),
                    frequentBigrams = listOf(
                        DynamicBigram("확인", "부탁드립니다", 0.95f),
                        DynamicBigram("배포", "완료했습니다", 0.9f)
                    ),
                    cannedPhrases = listOf("확인 후 공유드리겠습니다.")
                )
            )

            val intent = Intent(appContext, TypingDnaDashboardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
            assertNotNull(activity)

            val level = activity.findViewById<TextView>(R.id.tv_level_badge)
            val sentences = activity.findViewById<TextView>(R.id.tv_dash_sentences)
            val bigrams = activity.findViewById<TextView>(R.id.tv_dash_bigrams)
            val chart = activity.findViewById<TypingDnaChartView>(R.id.chart_view)
            val sync = activity.findViewById<android.view.View>(R.id.btn_sync_now)

            assertEquals("Lv.2", level.text.toString())
            assertEquals("15", sentences.text.toString())
            assertEquals("2", bigrams.text.toString())
            assertNotNull(chart)
            assertNotNull(sync)
            assertTrue(chart.contentDescription?.contains("레벨 2") == true)

            activity.finish()
        } finally {
            if (backup != null) {
                repoFile.writeText(backup)
            } else {
                repoFile.delete()
            }
        }
    }
}

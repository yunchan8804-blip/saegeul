/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.benchmark

import org.fcitx.fcitx5.android.input.ai.AiContextualPredictor
import org.fcitx.fcitx5.android.input.ai.BundledKoreanNgram
import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine
import org.fcitx.fcitx5.android.input.ai.KoreanSemanticSentencePredictor
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.PersonalNgramTokenizer
import org.fcitx.fcitx5.android.input.ai.PersonalizedSentenceStore
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackIndex
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackText
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.DubeolsikKeyMap
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * 다음 어절 예측기의 기준선 측정. 개인 저장소가 빈 상태에서 내장 자산(ko-ngram.bin, 어휘, 문장팩)만으로
 * FineWeb-2 kor_Hang test 분할 보류 문장을 얼마나 맞히는지, 얼마나 빠른지 잰다. 단언 없이 측정만 한다.
 *
 * SAEGEUL_BENCHMARK=1 일 때만 돈다. 문장 수는 SAEGEUL_BENCHMARK_SENTENCES 로 줄일 수 있다.
 * 결과는 app/build/reports/next-word-baseline/baseline.{json,md}.
 */
class NextWordBaselineBenchmarkTest {

    private class ModeStats {
        var trials = 0
        var hit1 = 0
        var hit3 = 0
        var hit4 = 0
        var emptyWordLine = 0
        var sentenceLineFirstWordHit = 0
        val hit1BySource = sortedMapOf<String, Int>()
        val top1BySource = sortedMapOf<String, Int>()

        fun toJson(): JSONObject = JSONObject().apply {
            put("trials", trials)
            put("hit1", hit1)
            put("hit3", hit3)
            put("hit4", hit4)
            put("hit1Rate", rate(hit1, trials))
            put("hit3Rate", rate(hit3, trials))
            put("hit4Rate", rate(hit4, trials))
            put("emptyWordLine", emptyWordLine)
            put("sentenceLineFirstWordHit", sentenceLineFirstWordHit)
            put("sentenceLineFirstWordHitRate", rate(sentenceLineFirstWordHit, trials))
            put("hit1BySource", hit1BySource.toJson())
            put("top1BySource", top1BySource.toJson())
        }
    }

    private class Outcome(val wordRank: Int, val sentenceFirstWordHit: Boolean, val topSource: String?, val hitSource: String?)

    @Test
    fun measureBaseline() {
        assumeTrue("SAEGEUL_BENCHMARK=1 일 때만 실행", System.getenv("SAEGEUL_BENCHMARK") == "1")

        val dataBytes = javaClass.getResourceAsStream(HELDOUT_RESOURCE)!!.use { it.readBytes() }
        val maxSentences = System.getenv("SAEGEUL_BENCHMARK_SENTENCES")?.toInt() ?: Int.MAX_VALUE
        val sentences = String(dataBytes, Charsets.UTF_8).lines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .take(maxSentences)

        val nextAll = ModeStats()
        val nextByContext = sortedMapOf<String, ModeStats>()
        val prefix1 = ModeStats()
        val prefix2 = ModeStats()
        val latenciesNanos = ArrayList<Long>(300_000)
        var predictCalls = 0
        var sentencesUsed = 0
        var kssBaselineKeys = 0L
        var kssPredictedKeys = 0L
        var kssWords = 0
        var kssAccepted = 0
        val kssAcceptedAt = sortedMapOf<String, Int>()

        val startedAt = System.nanoTime()
        for (sentence in sentences) {
            val tokens = PersonalNgramTokenizer.tokenize(sentence)
            if (tokens.size < 2) continue
            sentencesUsed++
            val memo = HashMap<Pair<Int, Int>, Outcome>()

            fun outcomeAt(wordIndex: Int, prefixLength: Int): Outcome = memo.getOrPut(wordIndex to prefixLength) {
                val expected = tokens[wordIndex]
                val context = if (wordIndex == 0) "" else tokens.subList(0, wordIndex).joinToString(" ") + " "
                val before = System.nanoTime()
                val predictions = predictor.predict(
                    currentStroke = expected.take(prefixLength),
                    contextBeforeCursor = context,
                    packageName = PACKAGE_NAME,
                    limit = 10
                )
                latenciesNanos.add(System.nanoTime() - before)
                predictCalls++

                val words = predictions.filter { !it.isSentenceCompletion }
                val sentenceLine = predictions.filter { it.isSentenceCompletion }
                val rank = words.indexOfFirst { matches(it.text, context, expected) }
                Outcome(
                    wordRank = rank,
                    sentenceFirstWordHit = sentenceLine.any { matches(it.text, context, expected) },
                    topSource = words.firstOrNull()?.source,
                    hitSource = if (rank == 0) words[0].source else null
                )
            }

            for (i in 1 until tokens.size) {
                val expected = tokens[i]
                val next = outcomeAt(i, 0)
                nextAll.record(next)
                nextByContext.getOrPut(contextBucket(i)) { ModeStats() }.record(next)
                if (expected.length > 1) prefix1.record(outcomeAt(i, 1))
                if (expected.length > 2) prefix2.record(outcomeAt(i, 2))
            }

            for (index in tokens.indices) {
                val word = tokens[index]
                val typedKeys = DubeolsikKeyMap.keystrokeCount(word) + 1
                kssBaselineKeys += typedKeys
                kssWords++
                var cost = typedKeys
                for (prefixLength in 0 until word.length) {
                    if (outcomeAt(index, prefixLength).wordRank in 0 until WORD_SLOTS) {
                        cost = DubeolsikKeyMap.keystrokeCount(word.take(prefixLength)) + 1
                        kssAccepted++
                        kssAcceptedAt.merge(if (prefixLength >= 3) "3+" else prefixLength.toString(), 1, Int::plus)
                        break
                    }
                }
                kssPredictedKeys += cost
            }
        }
        val elapsedSeconds = (System.nanoTime() - startedAt) / 1e9

        val measured = latenciesNanos.drop(WARMUP_CALLS).sorted()
        val latency = JSONObject().apply {
            put("calls", predictCalls)
            put("warmupExcluded", minOf(WARMUP_CALLS, latenciesNanos.size))
            put("measuredCalls", measured.size)
            put("p50Ms", percentileMs(measured, 0.50))
            put("p95Ms", percentileMs(measured, 0.95))
            put("p99Ms", percentileMs(measured, 0.99))
            put("maxMs", measured.lastOrNull()?.let { it / 1e6 } ?: 0.0)
        }
        val kss = JSONObject().apply {
            put("words", kssWords)
            put("acceptedByTap", kssAccepted)
            put("acceptedRate", rate(kssAccepted, kssWords))
            put("keysWithoutPrediction", kssBaselineKeys)
            put("keysWithPrediction", kssPredictedKeys)
            put("kss", if (kssBaselineKeys == 0L) 0.0 else 1.0 - kssPredictedKeys.toDouble() / kssBaselineKeys)
            put("acceptedAtPrefixSyllables", JSONObject().also { obj -> kssAcceptedAt.forEach { (k, v) -> obj.put(k, v) } })
        }
        val result = JSONObject().apply {
            put("dataFile", HELDOUT_RESOURCE.removePrefix("/"))
            put("dataSha256", sha256(dataBytes))
            put("sentencesRequested", sentences.size)
            put("sentencesUsed", sentencesUsed)
            put("predictCalls", predictCalls)
            put("gitHead", gitHead())
            put("executedAt", Instant.now().toString())
            put("elapsedSeconds", elapsedSeconds)
            put("packageName", PACKAGE_NAME)
            put("wordSlots", WORD_SLOTS)
            put("next", nextAll.toJson())
            put("nextByContextLength", JSONObject().also { obj -> nextByContext.forEach { (k, v) -> obj.put(k, v.toJson()) } })
            put("prefix1Syllable", prefix1.toJson())
            put("prefix2Syllables", prefix2.toJson())
            put("kss", kss)
            put("latency", latency)
        }

        val reportsDir = File(appDir, "build/reports/next-word-baseline")
        reportsDir.mkdirs()
        File(reportsDir, "baseline.json").writeText(result.toString(2), Charsets.UTF_8)
        File(reportsDir, "baseline.md").writeText(renderMarkdown(result, nextAll, nextByContext, prefix1, prefix2), Charsets.UTF_8)
    }

    private fun ModeStats.record(outcome: Outcome) {
        trials++
        if (outcome.wordRank == 0) hit1++
        if (outcome.wordRank in 0 until 3) hit3++
        if (outcome.wordRank in 0 until WORD_SLOTS) hit4++
        if (outcome.topSource == null) emptyWordLine++
        if (outcome.sentenceFirstWordHit) sentenceLineFirstWordHit++
        outcome.topSource?.let { top1BySource.merge(it, 1, Int::plus) }
        outcome.hitSource?.let { hit1BySource.merge(it, 1, Int::plus) }
    }

    private fun contextBucket(boundary: Int) = when (boundary) {
        1 -> "1어절"
        2 -> "2어절"
        3 -> "3어절"
        else -> "4어절 이상"
    }

    private fun renderMarkdown(
        result: JSONObject,
        next: ModeStats,
        byContext: Map<String, ModeStats>,
        prefix1: ModeStats,
        prefix2: ModeStats
    ): String {
        fun pct(value: Double) = "%.2f%%".format(value * 100)
        fun row(label: String, s: ModeStats) =
            "| $label | ${s.trials} | ${pct(rate(s.hit1, s.trials))} | ${pct(rate(s.hit3, s.trials))} | ${pct(rate(s.hit4, s.trials))} | ${pct(rate(s.sentenceLineFirstWordHit, s.trials))} |"
        val kss = result.getJSONObject("kss")
        val latency = result.getJSONObject("latency")
        return buildString {
            appendLine("# 다음 어절 예측 기준선")
            appendLine()
            appendLine("- 데이터: `${result.getString("dataFile")}` (sha256 `${result.getString("dataSha256")}`)")
            appendLine("- 문장 ${result.getInt("sentencesUsed")}개, predict 호출 ${result.getInt("predictCalls")}회, 소요 ${"%.1f".format(result.getDouble("elapsedSeconds"))}초")
            appendLine("- git HEAD `${result.getString("gitHead")}`, 실행 ${result.getString("executedAt")}")
            appendLine("- 개인 저장소 비어 있음, packageName `${result.getString("packageName")}`, 후보 limit 10, UI 단어 슬롯 $WORD_SLOTS")
            appendLine()
            appendLine("## 적중률 (단어 줄 후보 기준)")
            appendLine()
            appendLine("| 모드 | 시행 | hit@1 | hit@3 | hit@4 | 문장 줄 첫 어절 적중 |")
            appendLine("|---|---|---|---|---|---|")
            appendLine(row("NEXT 전체", next))
            byContext.forEach { (label, s) -> appendLine(row("NEXT 문맥 $label", s)) }
            appendLine(row("PREFIX 1음절", prefix1))
            appendLine(row("PREFIX 2음절", prefix2))
            appendLine()
            appendLine("## 키 입력 절약률 (KSS)")
            appendLine()
            appendLine("- 어절 ${kss.getInt("words")}개 중 탭으로 수락 ${kss.getInt("acceptedByTap")}개 (${pct(kss.getDouble("acceptedRate"))})")
            appendLine("- 예측 없는 타수 ${kss.getLong("keysWithoutPrediction")}, 예측 사용 타수 ${kss.getLong("keysWithPrediction")}")
            appendLine("- KSS ${pct(kss.getDouble("kss"))}")
            appendLine("- 수락 시점(친 음절 수) 분포: ${kss.getJSONObject("acceptedAtPrefixSyllables")}")
            appendLine()
            appendLine("## 지연 (predict 1회, 처음 ${latency.getInt("warmupExcluded")}회 제외)")
            appendLine()
            appendLine("| 측정 호출 | p50 ms | p95 ms | p99 ms | max ms |")
            appendLine("|---|---|---|---|---|")
            appendLine("| ${latency.getInt("measuredCalls")} | ${"%.2f".format(latency.getDouble("p50Ms"))} | ${"%.2f".format(latency.getDouble("p95Ms"))} | ${"%.2f".format(latency.getDouble("p99Ms"))} | ${"%.2f".format(latency.getDouble("maxMs"))} |")
            appendLine()
            appendLine("## 출처 분석")
            appendLine()
            sourceTable("NEXT hit@1 출처", next.hit1BySource)
            sourceTable("NEXT 단어 줄 1위 출처", next.top1BySource)
            sourceTable("PREFIX 1음절 hit@1 출처", prefix1.hit1BySource)
            sourceTable("PREFIX 1음절 단어 줄 1위 출처", prefix1.top1BySource)
            sourceTable("PREFIX 2음절 hit@1 출처", prefix2.hit1BySource)
            sourceTable("PREFIX 2음절 단어 줄 1위 출처", prefix2.top1BySource)
        }
    }

    private fun StringBuilder.sourceTable(title: String, counts: Map<String, Int>) {
        appendLine("### $title")
        appendLine()
        appendLine("| source | 개수 |")
        appendLine("|---|---|")
        counts.entries.sortedByDescending { it.value }.forEach { (source, count) -> appendLine("| $source | $count |") }
        appendLine()
    }

    companion object {
        private const val HELDOUT_RESOURCE = "/benchmark/ko-heldout-fineweb2-test.txt"
        private const val PACKAGE_NAME = "com.kakao.talk"
        private const val WORD_SLOTS = 4
        private const val WARMUP_CALLS = 200

        private lateinit var predictor: AiContextualPredictor
        private lateinit var appDir: File

        private fun rate(count: Int, total: Int) = if (total == 0) 0.0 else count.toDouble() / total

        private fun Map<String, Int>.toJson() = JSONArray().also { array ->
            entries.sortedByDescending { it.value }.forEach { (source, count) ->
                array.put(JSONObject().put("source", source).put("count", count))
            }
        }

        private fun percentileMs(sortedNanos: List<Long>, quantile: Double): Double {
            if (sortedNanos.isEmpty()) return 0.0
            val index = (Math.ceil(quantile * sortedNanos.size).toInt() - 1).coerceIn(0, sortedNanos.size - 1)
            return sortedNanos[index] / 1e6
        }

        private fun sha256(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun gitHead(): String {
            val process = ProcessBuilder("git", "rev-parse", "HEAD").directory(appDir).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "git rev-parse 실패: $output" }
            return output
        }

        // PersonalizationHeldOutDiagnosticTest 의 matches 규칙: 후보 전체이거나 문맥 뒤 첫 새 어절.
        // 비교는 앞뒤 문장부호를 뗀 뒤 한다.
        private fun matches(text: String, context: String, expected: String): Boolean {
            val firstNew = text.removePrefix(context).trim().substringBefore(' ')
            return trimPunctuation(text.trim()) == expected || trimPunctuation(firstNew) == expected
        }

        private fun trimPunctuation(token: String) = token.trim { !it.isLetterOrDigit() }

        private fun findAsset(vararg candidates: String): File =
            candidates.map(::File).firstOrNull { it.exists() }
                ?: throw AssertionError("에셋을 찾을 수 없습니다. 시도한 경로: ${candidates.toList()}, cwd=${File(".").absolutePath}")

        @BeforeClass
        @JvmStatic
        fun setUp() {
            if (System.getenv("SAEGEUL_BENCHMARK") != "1") return
            val vocabFile = findAsset(
                "app/src/main/assets/ko_base_vocab.tsv",
                "../app/src/main/assets/ko_base_vocab.tsv",
                "src/main/assets/ko_base_vocab.tsv",
                "../src/main/assets/ko_base_vocab.tsv"
            )
            appDir = vocabFile.canonicalFile.toPath().parent.parent.parent.parent.toFile()

            val ngramFile = File(vocabFile.canonicalFile.parentFile, BundledKoreanNgram.ASSET_PATH)
            val corpusNgram = ngramFile.inputStream().use(BundledKoreanNgram::read)

            val sentencePackFile = File(vocabFile.canonicalFile.parentFile, "sentence-packs/ko-basic-v1.txt")
            val sentencePackIndex = SentencePackIndex.build(
                sentencePackFile.readLines(Charsets.UTF_8).mapNotNull(SentencePackText::normalizeAccepted)
            )

            val vocabulary = BaseKoreanVocabulary { vocabFile.reader(Charsets.UTF_8) }
            vocabulary.load()
            val typoCorrector = KeyboardAwareTypoCorrector()
            vocabulary.forEachWord(BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior ->
                typoCorrector.addWord(word, prior)
            }

            predictor = AiContextualPredictor(
                morphology = ChoseongMorphologyEngine(),
                semanticPredictor = KoreanSemanticSentencePredictor(),
                personalizedStore = PersonalizedSentenceStore(),
                ngram = PersonalNgramModel(),
                typoCorrector = typoCorrector,
                baseVocabulary = vocabulary,
                correctionStore = CorrectionPatternStore(),
                personalSentenceVault = PersonalSentenceVault(storeFile = null),
                sentencePackLookup = sentencePackIndex::complete,
                bundledNgram = { corpusNgram }
            )
        }
    }
}

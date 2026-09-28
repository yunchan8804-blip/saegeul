/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Types real Korean words and short sentences on every mobile screen layout
 * ([MobileHangulLayout], excluding [MobileHangulLayout.Physical]) via
 * [MobileLayoutTypingPlanner], to catch layout/composer/engine combinations that cannot actually
 * produce the text a user would expect. See docs/korean-input/mobile-keyboard-fixes-2026-09.md.
 */
class MobileLayoutTypingCampaignTest {

    private data class WordCase(val category: String, val text: String)

    /** Must type on every layout that has the punctuation it needs (K5/K13: "기호는 자판에 있는 것만"). */
    private val requiredCases: List<WordCase> = buildList {
        listOf("닭", "읽다", "앉아", "많다", "삶", "없다", "핥다", "읊다", "넓다", "값")
            .forEach { add(WordCase("겹받침", it)) }
        listOf("왜", "궤", "외", "위", "의사", "과자", "뭐", "얘기", "예", "쇄", "웬", "괜찮아")
            .forEach { add(WordCase("복합모음", it)) }
        listOf("빨리", "딸기", "짜다", "싸다", "꺼내", "뿌리", "쓰다")
            .forEach { add(WordCase("쌍자음", it)) }
        listOf("가을", "먹어", "닭이", "앉아서", "읽어")
            .forEach { add(WordCase("도깨비불", it)) }
        listOf("각기", "간나", "난로")
            .forEach { add(WordCase("받침 뒤 같은 자음", it)) }
        listOf("안녕 하세요", "밥 먹었어?", "고마워요.")
            .forEach { add(WordCase("문장", it)) }
    }

    private val layouts = MobileHangulLayout.entries.filter { it != MobileHangulLayout.Physical }

    /** Mirrors the private mapping in MobileHangulKeyboard.kt (K10). */
    private fun familyFor(layout: MobileHangulLayout): MobileHangulFamily = when (layout) {
        MobileHangulLayout.Chunjiin, MobileHangulLayout.ChunjiinPlus -> MobileHangulFamily.Chunjiin
        else -> MobileHangulFamily.Other
    }

    private fun neededSymbols(text: String): Set<Char> =
        text.filterNot { it in '가'..'힣' || it == ' ' }.toSet()

    /**
     * Runs by default: the planner keeps every transient state a real press sequence passes
     * through, including a syllable libhangul committed early that a K21 replace takes back ("만"
     * with ㅅ open on the way to "많"). Treat a miss here as a keyboard regression first, and
     * reproduce it with an explicit key sequence ([MobileHangulK21DoubleBatchimRecoveryTest]'s
     * style) before blaming the planner.
     */
    @Test
    fun `모든 모바일 자판이 필수 단어 문장 세트를 전부 조합한다`() {
        val failures = mutableListOf<String>()
        for (layout in layouts) {
            val rows = MobileHangulKeyboard.layoutFor(layout)
            val keys = MobileLayoutTypingPlanner.extractKeys(rows)
            val family = familyFor(layout)
            val symbols = MobileLayoutTypingPlanner.availableSymbols(keys)
            for (case in requiredCases) {
                val needed = neededSymbols(case.text)
                if (!symbols.containsAll(needed)) continue
                val outcome = MobileLayoutTypingPlanner.plan(case.text, keys, family)
                if (!outcome.found) {
                    failures += "${layout.name}: [${case.category}] '${case.text}' -> " +
                        "reached '${outcome.reachedPrefix}', stuck before '${outcome.stuckAtChar}'"
                }
            }
        }
        assertTrue(
            "필수 세트 실패:\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    /**
     * The report always ends up at `app/build/reports/layout-typing-campaign.md` from the repo
     * root, but Gradle's test working directory is already the `app/` module, so the relative
     * path to write from there is `build/reports/...`, not `app/build/reports/...` again.
     */
    private fun reportOutputFile(): String =
        // `src/main/assets` is a static source directory, unlike `build/`, so it is a stable
        // signal for "cwd is already the app/ module" that can't be fooled by a leftover build
        // artifact from an earlier run.
        if (File("src/main/assets").isDirectory) "build/reports/layout-typing-campaign.md"
        else "app/build/reports/layout-typing-campaign.md"

    private fun findVocabFile(): File {
        val candidates = listOf(
            "app/src/main/assets/ko_base_vocab.tsv",
            "../app/src/main/assets/ko_base_vocab.tsv",
            "src/main/assets/ko_base_vocab.tsv",
            "../src/main/assets/ko_base_vocab.tsv"
        )
        for (path in candidates) {
            val file = File(path)
            if (file.exists()) return file
        }
        throw AssertionError(
            "ko_base_vocab.tsv를 찾을 수 없습니다. 시도한 경로: $candidates, cwd=${File(".").absolutePath}"
        )
    }

    /** Top-frequency pure-Hangul-syllable words/phrases, skipping the header comment line. */
    private fun loadBulkWords(limit: Int): List<String> {
        val words = mutableListOf<String>()
        findVocabFile().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("#")) continue
                val word = line.substringBefore('\t')
                if (word.isNotEmpty() && word.all { it in '가'..'힣' }) {
                    words += word
                    if (words.size >= limit) break
                }
            }
        }
        return words
    }

    /**
     * Excluded from the default `:app:testDebugUnitTest` run — it types up to [BULK_WORD_COUNT]
     * words on every layout and can take several minutes. Run it explicitly with the `CAMPAIGN`
     * environment variable set (e.g. `CAMPAIGN=true ./gradlew :app:testDebugUnitTest --tests
     * "*MobileLayoutTypingCampaignTest*"`). A Gradle project property would need a `systemProperty`
     * forward wired into `app/build.gradle.kts`, which this test-only change does not touch; the
     * environment variable reaches the forked test JVM without any build script edit.
     */
    @Test
    fun `대량 어절 세트로 자판별 타이핑 통과율을 기록한다`() {
        assumeTrue(
            "CAMPAIGN=true 환경변수가 설정된 경우에만 대량 캠페인을 실행합니다",
            System.getenv("CAMPAIGN") == "true"
        )
        val bulkWords = loadBulkWords(BULK_WORD_COUNT)
        val report = StringBuilder()
        report.append("# 모바일 자판 대량 타이핑 캠페인\n\n")
        report.append("어절 수: ${bulkWords.size} (ko_base_vocab.tsv 상위 빈도)\n\n")

        for (layout in layouts) {
            val rows = MobileHangulKeyboard.layoutFor(layout)
            val keys = MobileLayoutTypingPlanner.extractKeys(rows)
            val family = familyFor(layout)

            var passed = 0
            val failures = mutableListOf<Triple<String, String, Char?>>()
            for (word in bulkWords) {
                val outcome = MobileLayoutTypingPlanner.plan(word, keys, family)
                if (outcome.found) {
                    passed++
                } else {
                    failures += Triple(word, outcome.reachedPrefix, outcome.stuckAtChar)
                }
            }

            val rate = if (bulkWords.isEmpty()) 100.0 else passed * 100.0 / bulkWords.size
            println(
                "[layout-typing-campaign] ${layout.name}: $passed/${bulkWords.size} " +
                    "(${"%.1f".format(rate)}%), 실패 ${failures.size}건"
            )

            report.append("## ${layout.name}\n\n")
            report.append("통과: $passed / ${bulkWords.size} (${"%.1f".format(rate)}%)\n\n")
            if (failures.isNotEmpty()) {
                report.append("| 어절 | 도달한 접두사 | 막힌 글자 |\n|---|---|---|\n")
                for ((word, prefix, stuck) in failures.take(FAILURE_SAMPLE_LIMIT)) {
                    report.append("| $word | $prefix | ${stuck ?: "-"} |\n")
                }
                if (failures.size > FAILURE_SAMPLE_LIMIT) {
                    report.append("\n(그 외 ${failures.size - FAILURE_SAMPLE_LIMIT}건 생략)\n")
                }
                report.append("\n")
            }
        }

        val outFile = File(reportOutputFile())
        outFile.parentFile?.mkdirs()
        outFile.writeText(report.toString())
        println("[layout-typing-campaign] 보고서 작성: ${outFile.absolutePath}")
    }

    companion object {
        private const val BULK_WORD_COUNT = 2000
        private const val FAILURE_SAMPLE_LIMIT = 200
    }
}

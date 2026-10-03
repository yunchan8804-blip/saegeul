/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber
import org.fcitx.fcitx5.android.input.ai.learning.PersonalLearningInstrumentationGate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Measures how much the on-device personal sentence vault is polluted by the fixed test sentences
 * of `gemma_quality_scenarios.json` and by sentences stored glued to their neighbour. It only reads
 * the app's live [org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault] and reports counts;
 * no sentence text, fragment or hash is written to any log, status or file.
 */
class PersonalVaultContaminationDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun pausePersonalLearningForInstrumentation() {
        PersonalLearningInstrumentationGate.pauseForInstrumentation()
    }

    @After
    fun resumePersonalLearningAfterInstrumentation() {
        PersonalLearningInstrumentationGate.resumeAfterInstrumentation()
    }

    @Test
    fun reportsPersonalVaultContaminationCountsOnly() {
        assertTrue("개인 학습 정지 스위치가 켜져 있지 않습니다.", PersonalLearningInstrumentationGate.isPaused)
        val fixed = loadFixedSentences()
        assertTrue("고정 문장을 하나도 읽지 못했습니다.", fixed.isNotEmpty())

        val records = FcitxApplication.getInstance().personalSentenceVault.allSentences()
        val normalizedRecords = records.map(::normalize)
        val fixedSet = fixed.toSet()

        val exactCount = normalizedRecords.count { it in fixedSet }
        val containingCount = normalizedRecords.count { record -> fixed.any { record.contains(it) } }
        val suspectedJoinedCount = countJoinedRecords(normalizedRecords, fixed)

        val json = JSONObject()
            .put("vaultSentenceCount", records.size)
            .put("fixedSentenceCount", fixed.size)
            .put("exactFixedRecordCount", exactCount)
            .put("containingFixedRecordCount", containingCount)
            .put("suspectedJoinedRecordCount", suspectedJoinedCount)
        instrumentation.sendStatus(0, Bundle().apply { putString(CONTAMINATION_KEY, json.toString()) })
    }

    private fun loadFixedSentences(): List<String> {
        val raw = instrumentation.context.assets.open(SCENARIOS_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val scenarios = JSONArray(raw)
        val sentences = LinkedHashSet<String>()
        for (index in 0 until scenarios.length()) {
            val text = scenarios.getJSONObject(index).getString("text")
            text.split(SENTENCE_SPLIT)
                .map { normalize(KoreanPiiScrubber.scrub(it)) }
                .filter { it.length >= MIN_SENTENCE_LENGTH }
                .forEach { sentences += it }
        }
        return sentences.toList()
    }

    /**
     * Counts records that hold a sentence-ending syllable directly followed (no space) by at least
     * two Hangul syllables, where that trailing part starts like a fixed sentence or like the
     * beginning of another record in the same vault.
     */
    private fun countJoinedRecords(records: List<String>, fixed: List<String>): Int {
        val sourcesByPrefix = HashMap<String, MutableSet<Int>>()
        fixed.forEach { sourcesByPrefix.getOrPut(it.take(PREFIX_LENGTH)) { HashSet() }.add(FIXED_SOURCE) }
        records.forEachIndexed { index, record ->
            if (record.length >= MIN_SENTENCE_LENGTH) {
                sourcesByPrefix.getOrPut(record.take(PREFIX_LENGTH)) { HashSet() }.add(index)
            }
        }
        return records.withIndex().count { (index, record) ->
            (0 until record.length - 2).any { position ->
                if (record[position] !in SENTENCE_ENDINGS) return@any false
                if (!isHangul(record[position + 1]) || !isHangul(record[position + 2])) return@any false
                val tail = record.substring(position + 1)
                if (tail.length < PREFIX_LENGTH) return@any false
                sourcesByPrefix[tail.take(PREFIX_LENGTH)]?.any { it != index } == true
            }
        }
    }

    private fun normalize(sentence: String): String =
        sentence.trim().trimEnd { it.isWhitespace() || it in SENTENCE_TERMINATORS }

    private fun isHangul(char: Char): Boolean = char in '가'..'힣'

    private companion object {
        const val CONTAMINATION_KEY = "personalVaultContaminationJson"
        const val SCENARIOS_ASSET = "gemma_quality_scenarios.json"
        const val MIN_SENTENCE_LENGTH = 6
        const val PREFIX_LENGTH = 6
        const val FIXED_SOURCE = -1
        const val SENTENCE_TERMINATORS = ".?!。？！"
        const val SENTENCE_ENDINGS = "요다게어아야지자네"
        val SENTENCE_SPLIT = Regex("[.?!\\n。？！]")
    }
}

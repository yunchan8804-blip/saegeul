/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedSentenceBank
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedSentenceBankFormatException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class GemmaCompatibleReplayDeviceTest {

    @Test
    fun replayBankCompatibleOutputs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val replayFile = File(context.cacheDir, REPLAY_FILE_NAME)
        assertTrue("Replay JSON is missing: ${replayFile.absolutePath}", replayFile.isFile)
        assertTrue("Replay JSON exceeds 256 KiB", replayFile.length() <= MAX_REPLAY_BYTES)
        val rows = JSONArray(replayFile.readText(Charsets.UTF_8))
        assertEquals(40, rows.length())

        val expectedPrefixes = PREFIXES
        val expectedIds = (1..20).flatMap { number -> listOf("$number-full", "$number-suffix") }.toSet()
        val seenIds = mutableSetOf<String>()
        var completed = 0
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val caseId = row.getString("caseId")
            val mode = row.getString("mode")
            val prefix = row.getString("prefix")
            assertTrue("Unexpected or duplicate caseId: $caseId", seenIds.add(caseId) && caseId in expectedIds)
            val caseParts = caseId.split("-")
            assertEquals(2, caseParts.size)
            val caseNumber = caseParts[0].toIntOrNull()
                ?: throw AssertionError("Invalid case number: $caseId")
            assertTrue("Invalid case number: $caseId", caseNumber in 1..20)
            assertTrue("Unexpected mode at $caseId", mode == "FULL" || mode == "SUFFIX")
            assertEquals(caseParts[1].uppercase(), mode)
            assertEquals(expectedPrefixes[caseNumber - 1], prefix)

            val rawResponse = row.getString("rawResponse")
            val temporaryFile = File(context.cacheDir, "gemma-replay-${UUID.randomUUID()}.json")
            try {
                val app = context.applicationContext as FcitxApplication
                val bank = GeneratedSentenceBank(temporaryFile, app.vaultCipher)
                val outputs = JSONArray()
                var formatValid = true
                var validationError: String? = null
                var fenceRemoved = false
                val parsed = try {
                    val framed = stripJsonFence(rawResponse)
                    fenceRemoved = framed != rawResponse.trim()
                    val array = JSONArray(framed)
                    if (array.length() != 2) throw IllegalArgumentException("expected exactly 2 JSON strings")
                    buildList {
                        for (outputIndex in 0 until array.length()) {
                            add(array.opt(outputIndex) as? String
                                ?: throw IllegalArgumentException("JSON output $outputIndex is not a string"))
                        }
                    }
                } catch (error: Exception) {
                    formatValid = false
                    validationError = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                    emptyList()
                }
                parsed.forEach { raw ->
                    val trimmed = raw.trim()
                    val composed = if (mode == "FULL") trimmed else prefix + trimmed
                    val repeatedPrefix = mode == "SUFFIX" && trimmed.startsWith(prefix.trim())
                    val exactPrefix = composed.startsWith(prefix)
                    var accepted = false
                    var errorText: String? = validationError
                    if (!repeatedPrefix) {
                        try {
                            bank.addGenerated(JSONArray().put(composed).toString(), MODEL_ID, MODEL_SHA)
                            accepted = true
                        } catch (error: GeneratedSentenceBankFormatException) {
                            errorText = error.message
                        }
                    } else {
                        errorText = "repeatedPrefix"
                    }
                    outputs.put(
                        JSONObject()
                            .put("raw", raw)
                            .put("composed", composed)
                            .put("exactPrefix", exactPrefix)
                            .put("repeatedPrefix", repeatedPrefix)
                            .put("accepted", accepted)
                            .put("error", errorText ?: JSONObject.NULL)
                    )
                }
                bank.load()
                val lookup = bank.complete(prefix, 10)
                val report = JSONObject()
                    .put("caseId", caseId)
                    .put("mode", mode)
                    .put("prefix", prefix)
                    .put("rawResponse", rawResponse)
                    .put("outputs", outputs)
                    .put("lookupCount", lookup.size)
                    .put("uniqueCount", lookup.map { it.suffix }.toSet().size)
                    .put("formatValid", formatValid)
                    .put("fenceRemoved", fenceRemoved)
                    .put("validationError", validationError ?: JSONObject.NULL)
                    .put("evidenceScope", "secondary framing-compatible replay, no new inference")
                Log.i(TAG, report.toString())
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("gemmaCompatibleReplayJson", report.toString())
                })
            } finally {
                temporaryFile.delete()
            }
            completed += 1
        }
        assertEquals(40, completed)
        assertEquals(expectedIds, seenIds)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("gemmaCompatibleReplayJson", JSONObject().put("completed", completed).toString())
        })
    }

    private fun stripJsonFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd < 0 || !trimmed.endsWith("```")) {
            throw IllegalArgumentException("Generated response JSON fence is malformed")
        }
        val language = trimmed.substring(3, firstLineEnd).trim()
        if (language.isNotEmpty() && !language.equals("json", ignoreCase = true)) {
            throw IllegalArgumentException("Generated response JSON fence is malformed")
        }
        return trimmed.substring(firstLineEnd + 1, trimmed.length - 3).trim()
    }

    private companion object {
        const val TAG = "GemmaCompatibleReplay"
        const val REPLAY_FILE_NAME = "gemma-comparison-replay.json"
        const val MAX_REPLAY_BYTES = 256L * 1024L
        const val MODEL_ID = "gemma-4-E2B-it"
        const val MODEL_SHA = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        val PREFIXES = listOf(
            "회의 자료를 ", "오늘 저녁 ", "약속을 ", "내일 회의는 ", "자료를 확인하고 ",
            "답장이 늦어서 ", "지금 출발하면 ", "도착하면 바로 ", "시간이 괜찮으면 ", "이번 주말에는 ",
            "점심 먹고 ", "일정이 바뀌면 ", "오늘은 몸이 ", "고마운 마음을 ", "비가 많이 와서 ",
            "택배가 도착하면 ", "조금 늦을 것 ", "잘 이해가 안 돼서 ", "다음에 기회가 되면 ", "확인해 주셔서 "
        )
    }
}

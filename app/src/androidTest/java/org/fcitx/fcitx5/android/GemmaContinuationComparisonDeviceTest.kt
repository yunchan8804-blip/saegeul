/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.debug.gemma.GemmaMaterialGenerator
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedSentenceBank
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedSentenceBankFormatException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class GemmaContinuationComparisonDeviceTest {

    @Test
    fun compareFullSentenceAndContinuation() = runBlocking(Dispatchers.Default) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelFile = File(context.noBackupFilesDir, MODEL_RELATIVE_PATH)
        assertTrue("Gemma model is missing", modelFile.isFile)
        assertTrue("Gemma model size mismatch", modelFile.length() == MODEL_SIZE)
        val modelSha = sha256(modelFile)
        assertTrue("Gemma model digest mismatch", modelSha == MODEL_SHA256)

        var completed = 0
        PREFIXES.forEachIndexed { index, prefix ->
            val firstMode = if ((index + 1) % 2 == 1) Mode.FULL else Mode.SUFFIX
            listOf(firstMode, firstMode.other()).forEach { mode ->
                val caseId = "${index + 1}-${mode.name.lowercase()}"
                val prompt = promptFor(mode, prefix)
                val generator = GemmaMaterialGenerator(context)
                val startedAt = SystemClock.elapsedRealtime()
                val result = try {
                    generator.generate(modelFile, useGpu = false, prompt = prompt)
                } finally {
                    generator.cancel()
                }
                val callWallMs = SystemClock.elapsedRealtime() - startedAt
                assertFalse("Gemma generator still running after $caseId", generator.isRunning)
                val generationReport = JSONObject()
                    .put("caseId", caseId)
                    .put("mode", mode.name)
                    .put("prefix", prefix)
                    .put("rawResponse", result.text)
                    .put("initMs", result.initializationMs)
                    .put("generateMs", result.generationMs)
                    .put("callWallMs", callWallMs)
                Log.i(TAG, generationReport.toString())
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("gemmaComparisonGenerationJson", generationReport.toString())
                })

                val temporaryFile = File(context.cacheDir, "gemma-comparison-${UUID.randomUUID()}.json")
                try {
                    val bank = GeneratedSentenceBank(temporaryFile, (context.applicationContext as FcitxApplication).vaultCipher)
                    val outputs = JSONArray()
                    var formatValid = true
                    var validationError: String? = null
                    val parsed = try {
                        val array = JSONArray(result.text.trim())
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
                        val repeatedPrefix = mode == Mode.SUFFIX && trimmed.startsWith(prefix.trim())
                        val composed = if (mode == Mode.FULL) trimmed else prefix + trimmed
                        val exactPrefix = composed.startsWith(prefix)
                        var accepted = false
                        var outputError: String? = validationError
                        if (formatValid && !repeatedPrefix) {
                            val singlePayload = JSONArray().put(composed)
                            try {
                                bank.addGenerated(singlePayload.toString(), MODEL_ID, modelSha)
                                accepted = true
                            } catch (error: GeneratedSentenceBankFormatException) {
                                outputError = error.message
                            }
                        } else if (repeatedPrefix) {
                            outputError = "repeatedPrefix"
                        }
                        outputs.put(
                            JSONObject()
                                .put("raw", raw)
                                .put("composed", composed)
                                .put("exactPrefix", exactPrefix)
                                .put("repeatedPrefix", repeatedPrefix)
                                .put("accepted", accepted)
                                .put("validationError", outputError)
                        )
                    }
                    bank.load()
                    val lookup = bank.complete(prefix, 10)
                    val report = JSONObject()
                        .put("caseId", caseId)
                        .put("mode", mode.name)
                        .put("prefix", prefix)
                        .put("rawResponse", result.text)
                        .put("initMs", result.initializationMs)
                        .put("generateMs", result.generationMs)
                        .put("callWallMs", callWallMs)
                        .put("outputs", outputs)
                        .put("lookupCount", lookup.size)
                        .put("uniqueCount", lookup.map { it.suffix }.toSet().size)
                        .put("storedUniqueCount", bank.sentenceCount)
                        .put("formatValid", formatValid)
                        .put("validationError", validationError ?: JSONObject.NULL)
                    Log.i(TAG, report.toString())
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("gemmaComparisonJson", report.toString())
                    })
                } finally {
                    temporaryFile.delete()
                }
                completed += 1
            }
        }
        assertTrue("All 40 Gemma comparison calls must complete", completed == 40)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("gemmaComparisonJson", JSONObject().put("completed", completed).toString())
        })
    }

    private fun promptFor(mode: Mode, prefix: String): String = if (mode == Mode.FULL) {
        """비개인 한국어 일상·업무 문장을 생성하라. 주어진 prefix로 정확히 시작하는 완성 문장 2개를 JSON 문자열 배열 하나로만 반환하라. 각 문장은 prefix와 자연스럽게 호응하고 3~12어절이며 종결부호로 끝나야 한다. prefix는 JSON 문자열이며 마지막 공백도 의미가 있으므로 그대로 사용하라: ${JSONObject.quote(prefix)}"""
    } else {
        """비개인 한국어 일상·업무 문장을 생성하라. 앱이 이미 입력한 prefix 뒤에 이어질 부분 2개를 JSON 문자열 배열 하나로만 반환하라. prefix를 반복하거나 변경하지 말고 뒤에 붙일 suffix만 반환하라. 각 suffix는 prefix와 자연스럽게 호응하고 prefix와 합친 문장은 3~12어절이며 종결부호로 끝나야 한다. prefix는 JSON 문자열이며 마지막 공백도 의미가 있으므로 그대로 사용하라: ${JSONObject.quote(prefix)}"""
    }

    private enum class Mode {
        FULL,
        SUFFIX;

        fun other() = if (this == FULL) SUFFIX else FULL
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "GemmaContinuationComparison"
        const val MODEL_ID = "gemma-4-E2B-it"
        const val MODEL_RELATIVE_PATH = "gemma/model.litertlm"
        const val MODEL_SIZE = 2_588_147_712L
        const val MODEL_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        val PREFIXES = listOf(
            "회의 자료를 ", "오늘 저녁 ", "약속을 ", "내일 회의는 ", "자료를 확인하고 ",
            "답장이 늦어서 ", "지금 출발하면 ", "도착하면 바로 ", "시간이 괜찮으면 ", "이번 주말에는 ",
            "점심 먹고 ", "일정이 바뀌면 ", "오늘은 몸이 ", "고마운 마음을 ", "비가 많이 와서 ",
            "택배가 도착하면 ", "조금 늦을 것 ", "잘 이해가 안 돼서 ", "다음에 기회가 되면 ", "확인해 주셔서 "
        )
    }
}

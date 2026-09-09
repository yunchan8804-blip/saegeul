/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.util.Log
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import android.os.SystemClock
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedSentenceBank
import org.fcitx.fcitx5.android.debug.gemma.GemmaMaterialGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Device proof for real Gemma material generation, persistence, and local lookup. */
class GemmaMaterialDeviceTest {

    @Test
    fun generatePersistReloadAndQueryLocalMaterial() = runBlocking(Dispatchers.Default) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as FcitxApplication
        val modelFile = File(context.noBackupFilesDir, MODEL_RELATIVE_PATH)
        assertTrue("Gemma model is missing: ${modelFile.absolutePath}", modelFile.isFile)
        assertTrue("Gemma model is unexpectedly short", modelFile.length() == MODEL_SIZE)
        val sourceSha = sha256(modelFile)
        assertTrue("Gemma model digest mismatch", sourceSha == MODEL_SHA256)

        val backend = InstrumentationRegistry.getArguments().getString("backend") ?: "cpu"
        require(backend == "gpu" || backend == "cpu") { "backend must be gpu or cpu" }
        val generator = GemmaMaterialGenerator(context)
        val startedAt = SystemClock.elapsedRealtime()
        val result = try {
            generator.generate(modelFile = modelFile, useGpu = backend == "gpu")
        } finally {
            generator.cancel()
        }
        assertFalse("Gemma generator must be stopped after generation", generator.isRunning)
        assertTrue("Gemma generation returned empty text", result.text.isNotBlank())
        val generationReport = JSONObject()
            .put("initMs", result.initializationMs)
            .put("generateMs", result.generationMs)
            .put("backend", backend)
            .put("source", "gemma-4-E2B-it")
            .put("sourceSha256", sourceSha)
            .put("generatedText", result.text)
            .put("evidenceScope", "generation output before material validation")
            .toString()
        Log.i(TAG, generationReport)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("gemmaGenerationJson", generationReport) }
        )

        val queries = listOf("회의 자료를 ", "오늘 저녁 ", "약속을 ")
        val temporaryFile = File(context.cacheDir, "synthetic-test-${UUID.randomUUID()}.json")
        try {
            val temporaryBank = GeneratedSentenceBank(temporaryFile, app.vaultCipher)
            val temporaryAcceptedCount = temporaryBank.addGenerated(result.text, "gemma-4-E2B-it", sourceSha)
            assertTrue("This Gemma response produced no accepted material", temporaryAcceptedCount > 0)
            temporaryBank.load()
            val temporaryQueryCounts = linkedMapOf<String, Int>()
            for (query in queries) {
                val candidates = temporaryBank.complete(query, limit = 5)
                temporaryQueryCounts[query] = candidates.size
            }
            assertTrue(
                "This response must match every fixed prefix: $temporaryQueryCounts",
                temporaryQueryCounts.values.all { it > 0 }
            )

            val acceptedCount = app.generatedSentenceBank.addGenerated(result.text, "gemma-4-E2B-it", sourceSha)
            val bankFile = File(context.noBackupFilesDir, BANK_RELATIVE_PATH)
            assertTrue("Generated material bank was not written", bankFile.isFile)
            app.generatedSentenceBank.load()
            val reloaded = GeneratedSentenceBank(bankFile, app.vaultCipher)
            reloaded.load()
            val reloadedCount = reloaded.sentenceCount
            assertTrue("Reloaded generated material bank is empty", reloadedCount > 0)

            val queryStartedAt = SystemClock.elapsedRealtime()
            val queryCounts = linkedMapOf<String, Int>()
            for (query in queries) {
                queryCounts[query] = reloaded.complete(query, limit = 5).size
            }
            val queryMatchedCount = queryCounts.values.sum()
            val queryMs = SystemClock.elapsedRealtime() - queryStartedAt
            assertTrue("App bank must match every fixed prefix: $queryCounts", queryCounts.values.all { it > 0 })
            assertTrue("Local material query exceeded 1000ms: $queryMs", queryMs <= QUERY_BUDGET_MS)

            val report = JSONObject()
                .put("initMs", result.initializationMs)
                .put("generateMs", result.generationMs)
                .put("acceptedCount", acceptedCount)
                .put("reloadedCount", reloadedCount)
                .put("queryMs", queryMs)
                .put("queryMatchedCount", queryMatchedCount)
                .put("source", "gemma-4-E2B-it")
                .put("sourceSha256", sourceSha)
                .put("backend", backend)
                .put("generatedText", result.text)
                .put("queryCounts", JSONObject(queryCounts as Map<*, *>))
                .put("elapsedSinceStartMs", SystemClock.elapsedRealtime() - startedAt)
                .put("evidenceScope", "generated material persistence/query; not UI display proof")
                .toString()
            Log.i(TAG, report)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply { putString("gemmaMaterialJson", report.toString()) }
            )
        } finally {
            temporaryFile.delete()
        }
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

    companion object {
        private const val TAG = "GemmaMaterialDeviceTest"
        private const val MODEL_RELATIVE_PATH = "gemma/model.litertlm"
        private const val BANK_RELATIVE_PATH = "gemma_materials.json"
        private const val MODEL_SIZE = 2_588_147_712L
        private const val MODEL_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        private const val QUERY_BUDGET_MS = 1_000L
    }
}

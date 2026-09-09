/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.ai.KoreanPiiScrubber
import org.fcitx.fcitx5.android.input.ai.sentencepack.MatchEvidence
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackIndex
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackMatch
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackText
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class GeneratedSentenceBank(
    private val file: File,
    private val cipher: VaultCipher
) {
    private val ioLock = Any()
    private var loaded = false

    @Volatile
    private var snapshot = Snapshot(emptyList(), SentencePackIndex.build(emptyList()), 0, 0)

    val revision: Long
        get() = snapshot.revision

    val sentenceCount: Int
        get() = snapshot.index.sentenceCount

    val sourceCount: Int
        get() = snapshot.sourceCount

    fun load() {
        synchronized(ioLock) { loadLocked() }
    }

    fun addGenerated(response: String, modelId: String, modelSha256: String): Int {
        require(response.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "Generated response exceeds 64 KiB"
        }
        val source = Source(modelId.requireModelId(), modelSha256.requireSha256())
        val generated = decodeGenerated(response)

        synchronized(ioLock) {
            if (!loaded) loadLocked()
            val previous = snapshot
            val existingTexts = previous.entries.mapTo(LinkedHashSet()) { it.text }
            val knownTexts = LinkedHashSet(existingTexts)
            val additions = generated.mapNotNull { text ->
                if (knownTexts.add(text)) StoredSentence(text, source) else null
            }
            if (additions.isEmpty()) return 0

            val entries = (previous.entries + additions).takeLast(MAX_SENTENCES)
            val next = snapshotFor(entries, previous.revision + 1)
            vaultFile().writeText(encodeStored(entries))
            snapshot = next
            return entries.count { it.text !in existingTexts }
        }
    }

    fun complete(rawContext: String, limit: Int): List<SentencePackMatch> =
        snapshot.index.complete(rawContext, limit).filter { it.evidence != MatchEvidence.LAST_WORD }

    fun clear() {
        synchronized(ioLock) {
            val next = snapshotFor(emptyList(), snapshot.revision + 1)
            vaultFile().writeText(encodeStored(emptyList()))
            snapshot = next
            loaded = true
        }
    }

    private fun loadLocked() {
        val raw = vaultFile().readText()
        val stored = raw?.let(::decodeStored) ?: emptyList()
        val capped = stored.takeLast(MAX_SENTENCES)
        val next = snapshotFor(capped, snapshot.revision + 1)
        if (capped.size != stored.size) {
            vaultFile().writeText(encodeStored(capped))
        }
        snapshot = next
        loaded = true
    }

    private fun vaultFile(): VaultFile = VaultFile(file, cipher, VaultFile.aadFor(file.name))

    private fun snapshotFor(entries: List<StoredSentence>, revision: Long): Snapshot {
        val immutableEntries = entries.toList()
        val sourceCount = immutableEntries.map { it.source }.toSet().size
        return Snapshot(
            immutableEntries,
            SentencePackIndex.build(immutableEntries.map(StoredSentence::text)),
            sourceCount,
            revision
        )
    }

    private fun decodeGenerated(response: String): List<String> {
        val array = try {
            JSONArray(stripJsonFence(response))
        } catch (exception: Exception) {
            throw GeneratedSentenceBankFormatException("Generated response must be a JSON array", exception)
        }
        if (array.length() == 0) throw GeneratedSentenceBankFormatException("Generated response has no sentences")

        val accepted = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val value = array.opt(index) as? String
                ?: throw GeneratedSentenceBankFormatException("Generated response entry $index must be a string")
            if (KoreanPiiScrubber.containsPii(value)) {
                throw GeneratedSentenceBankFormatException("Generated response entry $index contains PII")
            }
            val normalized = SentencePackText.normalizeAccepted(value)
                ?: throw GeneratedSentenceBankFormatException("Generated response entry $index is not an accepted sentence")
            if (!isTerminal(normalized)) {
                throw GeneratedSentenceBankFormatException("Generated response entry $index must end with a sentence terminal")
            }
            accepted += normalized
        }
        if (accepted.isEmpty()) throw GeneratedSentenceBankFormatException("Generated response has no valid sentences")
        return accepted.toList()
    }

    private fun decodeStored(raw: String): List<StoredSentence> {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Generated sentence bank exceeds 1 MiB" }
        val root = try {
            JSONObject(raw)
        } catch (exception: Exception) {
            throw GeneratedSentenceBankFormatException("Generated sentence bank JSON is malformed", exception)
        }
        if (root.opt("version") != FORMAT_VERSION) {
            throw GeneratedSentenceBankFormatException("Generated sentence bank version is unsupported")
        }
        val entries = root.opt("entries") as? JSONArray
            ?: throw GeneratedSentenceBankFormatException("Generated sentence bank entries are missing")
        val byText = LinkedHashMap<String, StoredSentence>()
        for (index in 0 until entries.length()) {
            val entry = entries.opt(index) as? JSONObject
                ?: throw GeneratedSentenceBankFormatException("Generated sentence bank entry $index is malformed")
            val text = entry.opt("text") as? String
                ?: throw GeneratedSentenceBankFormatException("Generated sentence bank entry $index text is missing")
            val modelId = entry.opt("modelId") as? String
                ?: throw GeneratedSentenceBankFormatException("Generated sentence bank entry $index modelId is missing")
            val modelSha256 = entry.opt("modelSha256") as? String
                ?: throw GeneratedSentenceBankFormatException("Generated sentence bank entry $index modelSha256 is missing")
            validateStoredSentence(text, index)
            val source = Source(modelId.requireModelId(), modelSha256.requireSha256())
            byText.putIfAbsent(text, StoredSentence(text, source))
        }
        return byText.values.toList()
    }

    private fun validateStoredSentence(text: String, index: Int) {
        if (KoreanPiiScrubber.containsPii(text)) {
            throw GeneratedSentenceBankFormatException("Generated sentence bank entry $index contains PII")
        }
        if (SentencePackText.normalizeAccepted(text) != text || !isTerminal(text)) {
            throw GeneratedSentenceBankFormatException("Generated sentence bank entry $index is not an accepted terminal sentence")
        }
    }

    private fun encodeStored(entries: List<StoredSentence>): String {
        val root = JSONObject().apply {
            put("version", FORMAT_VERSION)
            put("entries", JSONArray().apply {
                entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("text", entry.text)
                        put("modelId", entry.source.modelId)
                        put("modelSha256", entry.source.modelSha256)
                    })
                }
            })
        }
        val encoded = root.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
            "Generated sentence bank exceeds 1 MiB"
        }
        return encoded
    }

    private fun stripJsonFence(response: String): String {
        val trimmed = response.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd < 0 || !trimmed.endsWith("```")) {
            throw GeneratedSentenceBankFormatException("Generated response JSON fence is malformed")
        }
        val language = trimmed.substring(3, firstLineEnd).trim()
        if (language.isNotEmpty() && !language.equals("json", ignoreCase = true)) {
            throw GeneratedSentenceBankFormatException("Generated response JSON fence is malformed")
        }
        return trimmed.substring(firstLineEnd + 1, trimmed.length - 3).trim()
    }

    private fun isTerminal(text: String): Boolean = text.lastOrNull()?.let(TERMINALS::contains) == true

    private fun String.requireModelId(): String {
        if (any(Character::isISOControl)) throw GeneratedSentenceBankFormatException("modelId is invalid")
        val value = trim()
        if (value.isEmpty() || value.length > MAX_MODEL_ID_LENGTH) {
            throw GeneratedSentenceBankFormatException("modelId is invalid")
        }
        return value
    }

    private fun String.requireSha256(): String {
        if (!SHA256.matches(this)) throw GeneratedSentenceBankFormatException("modelSha256 must be a SHA-256 hex string")
        return lowercase()
    }

    private data class Snapshot(
        val entries: List<StoredSentence>,
        val index: SentencePackIndex,
        val sourceCount: Int,
        val revision: Long
    )

    private data class StoredSentence(val text: String, val source: Source)

    private data class Source(val modelId: String, val modelSha256: String)

    private companion object {
        const val FORMAT_VERSION = 1
        const val MAX_SENTENCES = 2_000
        const val MAX_JSON_BYTES = 1024 * 1024
        const val MAX_RESPONSE_BYTES = 64 * 1024
        const val MAX_MODEL_ID_LENGTH = 128
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")
        val TERMINALS = setOf('.', '?', '!')
    }
}

class GeneratedSentenceBankFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

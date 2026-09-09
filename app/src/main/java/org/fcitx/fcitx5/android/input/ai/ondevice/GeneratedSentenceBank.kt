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

    fun exactPrefixCount(prefix: String): Int {
        require(prefix in GeneratedMaterialPolicy.PREFIXES) { "Unknown generated-material prefix" }
        return snapshot.entries.count { it.text.startsWith(prefix) }
    }

    fun addGeneratedForPrefix(response: String, prefix: String, modelId: String, modelSha256: String): IngestionReport {
        require(prefix in GeneratedMaterialPolicy.PREFIXES) { "Unknown generated-material prefix" }
        val source = Source(modelId.requireModelId(), modelSha256.requireSha256())
        require(response.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "Generated response exceeds 64 KiB"
        }
        val array = try { JSONArray(stripJsonFence(response)) } catch (error: Exception) {
            throw GeneratedSentenceBankFormatException("Generated response must be a JSON array", error)
        }
        if (array.length() !in 1..8) throw GeneratedSentenceBankFormatException("Generated response must contain 1..8 sentences")
        val accepted = mutableListOf<String>()
        var rejected = 0
        for (index in 0 until array.length()) {
            val raw = array.opt(index) as? String
            if (raw == null) { rejected++; continue }
            val candidate = try {
                decodeGenerated(JSONArray().put(raw).toString()).single()
            } catch (_: GeneratedSentenceBankFormatException) {
                rejected++
                continue
            }
            if (isGeneratedCandidate(candidate, prefix)) accepted += candidate else rejected++
        }
        synchronized(ioLock) {
            if (!loaded) loadLocked()
            val existing = snapshot.entries.map { it.text }.toSet()
            val known = existing.toMutableSet()
            val additions = accepted.filter { known.add(it) }.map { StoredSentence(it, source) }
            val duplicates = accepted.size - additions.size
            if (additions.isNotEmpty()) {
                val entries = retainEntries(snapshot.entries + additions)
                vaultFile().writeText(encodeStored(entries))
                snapshot = snapshotFor(entries, snapshot.revision + 1)
                return IngestionReport(entries.count { it.text !in existing }, duplicates, rejected)
            }
            return IngestionReport(0, duplicates, rejected)
        }
    }

    fun recordAcceptedSuffix(suffix: String): Int {
        val needle = suffix.trim()
        if (needle.isEmpty()) return 0
        synchronized(ioLock) {
            if (!loaded) loadLocked()
            val matches = snapshot.entries.filter { it.text.endsWith(needle) }
            if (matches.size != 1) return 0
            val target = matches.single()
            val entries = snapshot.entries.map { if (it === target) it.copy(acceptedCount = saturatingIncrement(it.acceptedCount)) else it }
            vaultFile().writeText(encodeStored(entries))
            snapshot = snapshotFor(entries, snapshot.revision + 1)
            return 1
        }
    }

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

            val entries = retainEntries(previous.entries + additions)
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
        val capped = retainEntries(stored)
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
            val acceptedCount = if (!entry.has("acceptedCount")) 0L else {
                val value = entry.opt("acceptedCount")
                if ((value !is Int && value !is Long) || value.toLong() < 0L) {
                    throw GeneratedSentenceBankFormatException("acceptedCount is invalid")
                }
                value.toLong()
            }
            byText.putIfAbsent(text, StoredSentence(text, source, acceptedCount))
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
                        put("acceptedCount", entry.acceptedCount)
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

    private fun isGeneratedCandidate(text: String, prefix: String): Boolean {
        if (!text.startsWith(prefix) || text.substring(prefix.length).trim().isEmpty()) return false
        if (text.substring(prefix.length).trim().startsWith(prefix.trim())) return false
        if (text.dropLast(1).any { it == '.' || it == '?' || it == '!' }) return false
        val words = text.split(' ').filter(String::isNotBlank)
        if (words.size !in 3..12) return false
        if (words.size >= 3 && words.windowed(3).toSet().size != words.size - 2) return false
        return true
    }

    private fun retainEntries(entries: List<StoredSentence>): List<StoredSentence> {
        if (entries.size <= MAX_SENTENCES) return entries
        return entries.withIndex()
            .sortedWith(compareByDescending<IndexedValue<StoredSentence>> { it.value.acceptedCount }.thenByDescending { it.index })
            .take(MAX_SENTENCES)
            .sortedBy { it.index }
            .map { it.value }
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

    private data class StoredSentence(val text: String, val source: Source, val acceptedCount: Long = 0L)

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

data class IngestionReport(val added: Int, val duplicate: Int, val rejected: Int)

private fun saturatingIncrement(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

class GeneratedSentenceBankFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

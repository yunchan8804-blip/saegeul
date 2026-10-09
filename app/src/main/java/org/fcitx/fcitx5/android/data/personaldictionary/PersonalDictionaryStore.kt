/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.personaldictionary

import android.content.Context
import android.util.AtomicFile
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Explicitly managed words live outside Android's own auto backup and the legacy plaintext
 * settings-ZIP export, but they ARE included (as `kind: CORPUS`, raw bytes - this store has no
 * [org.fcitx.fcitx5.android.input.ai.vault.VaultCipher] layer of its own) in the encrypted vault
 * backup (`org.fcitx.fcitx5.android.data.backup.VaultBackup`): the user typed these words in on
 * purpose, so they are core corpus, not incidental data. The file contains only category and word
 * fields; input text, selection history, and package names are never collected.
 */
class PersonalDictionaryStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, RELATIVE_PATH))

    private val atomicFile = AtomicFile(file)
    private val backupFile = File(file.path + ".bak")

    fun load(): PersonalDictionary = synchronized(IO_LOCK) {
        try {
            read()
        } catch (exception: IOException) {
            Timber.w(exception, "Personal dictionary could not be read")
            PersonalDictionary()
        }
    }

    /**
     * Reads through [AtomicFile] so an interrupted write is recovered from its `.bak`. A file that
     * is present but cannot be decoded is renamed to `<name>.unreadable-<epochMs>` instead of being
     * replaced by the next edit. Throws [IOException] if the file can neither be read nor preserved.
     */
    private fun read(): PersonalDictionary {
        if (!file.isFile && !backupFile.isFile) return PersonalDictionary()
        val bytes = atomicFile.readFully()
        if (bytes.isEmpty()) return PersonalDictionary()
        val decoded = if (bytes.size <= MAX_FILE_BYTES) decodePersonalDictionary(bytes) else null
        if (decoded != null) return decoded
        val preserved = File(file.parentFile, "${file.name}.unreadable-${System.currentTimeMillis()}")
        if (!file.renameTo(preserved)) {
            throw IOException("Failed to preserve unreadable personal dictionary: ${file.name}")
        }
        Timber.w("Personal dictionary could not be decoded; kept as %s", preserved.name)
        return PersonalDictionary()
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun upsert(word: PersonalWord) = update { current ->
        val validated = word.validate()
        current.copy(words = current.words.filterNot { it.value == validated.value } + validated)
    }

    fun remove(value: String) = update { current ->
        val normalized = normalizePersonalWord(value)
        current.copy(words = current.words.filterNot { it.value == normalized })
    }

    private fun update(transform: (PersonalDictionary) -> PersonalDictionary) = synchronized(IO_LOCK) {
        write(transform(read()).normalized())
    }

    private fun write(dictionary: PersonalDictionary) {
        file.parentFile?.mkdirs()
        val stream = atomicFile.startWrite()
        try {
            stream.write(encodePersonalDictionary(dictionary))
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    companion object {
        const val RELATIVE_PATH = "korean-personal-dictionary/words.txt"
        private const val MAX_FILE_BYTES = 64 * 1024L
        private val IO_LOCK = Any()
    }
}

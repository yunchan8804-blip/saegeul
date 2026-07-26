/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.personaldictionary

import android.content.Context
import android.util.AtomicFile
import java.io.File

/**
 * Explicitly managed words live outside Android backup and the app's user-data ZIP export.
 * The file contains only category and word fields; input text, selection history, and package
 * names are never collected.
 */
class PersonalDictionaryStore(context: Context) {
    private val file = File(context.noBackupFilesDir, RELATIVE_PATH)
    private val atomicFile = AtomicFile(file)

    fun load(): PersonalDictionary = synchronized(IO_LOCK) {
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return PersonalDictionary()
        val bytes = runCatching(atomicFile::readFully).getOrNull() ?: return PersonalDictionary()
        decodePersonalDictionary(bytes) ?: PersonalDictionary()
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
        write(transform(load()).normalized())
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

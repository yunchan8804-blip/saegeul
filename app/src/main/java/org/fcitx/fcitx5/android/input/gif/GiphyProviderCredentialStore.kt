/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.content.Context
import org.fcitx.fcitx5.android.input.AndroidKeystoreProfileCipher
import org.fcitx.fcitx5.android.input.EncryptedProfileCipher
import org.fcitx.fcitx5.android.input.EncryptedProfileFormat
import org.fcitx.fcitx5.android.input.EncryptedProfileStorage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

data class GiphyProviderConfiguration(
    val apiKey: String,
    /** User confirmation that this exact key completed GIPHY's production upgrade review. */
    val productionApproved: Boolean,
    /** Separate written approval is required before storing a copy of GIPHY media. */
    val mediaCachingApproved: Boolean
)

enum class GiphyCredentialState { Missing, KeyOnly, Ready, Unreadable }

/** GIPHY key and approval assertions are encrypted outside every backup/export path. */
class GiphyProviderCredentialStore internal constructor(
    file: File,
    cipher: EncryptedProfileCipher
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, RELATIVE_PATH),
        AndroidKeystoreProfileCipher(KEY_ALIAS)
    )

    private val storage = EncryptedProfileStorage(file, FORMAT, cipher)

    fun load(): GiphyProviderConfiguration? {
        return runCatching {
            val plaintext = storage.load() ?: return null
            try {
                decode(plaintext)
            } finally {
                plaintext.fill(0)
            }
        }.getOrNull()
    }

    fun state(): GiphyCredentialState {
        if (!storage.exists()) return GiphyCredentialState.Missing
        val configuration = load() ?: return GiphyCredentialState.Unreadable
        return if (configuration.productionApproved) {
            GiphyCredentialState.Ready
        } else {
            GiphyCredentialState.KeyOnly
        }
    }

    fun save(configuration: GiphyProviderConfiguration) {
        val normalized = configuration.copy(apiKey = configuration.apiKey.trim())
        require(normalized.apiKey.length in 1..MAX_KEY_CHARACTERS) {
            "Enter a valid GIPHY API key"
        }
        require(!normalized.mediaCachingApproved || normalized.productionApproved) {
            "Media caching approval requires production approval"
        }
        val plaintext = encode(normalized)
        try {
            storage.save(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun clear() {
        storage.clear()
    }

    private fun encode(configuration: GiphyProviderConfiguration): ByteArray {
        val key = configuration.apiKey.toByteArray(Charsets.UTF_8)
        return try {
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(PAYLOAD_VERSION)
                    output.writeInt(key.size)
                    output.write(key)
                    output.writeBoolean(configuration.productionApproved)
                    output.writeBoolean(configuration.mediaCachingApproved)
                }
                bytes.toByteArray()
            }
        } finally {
            key.fill(0)
        }
    }

    private fun decode(bytes: ByteArray): GiphyProviderConfiguration =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PAYLOAD_VERSION)
            val keySize = input.readInt()
            require(keySize in 1..MAX_KEY_BYTES)
            val keyBytes = ByteArray(keySize).also(input::readFully)
            try {
                val key = keyBytes.toString(Charsets.UTF_8).trim()
                require(key.length in 1..MAX_KEY_CHARACTERS)
                val productionApproved = input.readBoolean()
                val mediaCachingApproved = input.readBoolean()
                require(!mediaCachingApproved || productionApproved)
                require(input.read() == -1)
                GiphyProviderConfiguration(key, productionApproved, mediaCachingApproved)
            } finally {
                keyBytes.fill(0)
            }
        }

    private companion object {
        const val RELATIVE_PATH = "gif/giphy-provider.bin"
        const val KEY_ALIAS = "fcitx.gif.giphy.v1"
        const val PAYLOAD_VERSION = 1
        const val MAX_KEY_CHARACTERS = 512
        const val MAX_KEY_BYTES = MAX_KEY_CHARACTERS * 4
        val FORMAT = EncryptedProfileFormat(
            magic = 0x47495031, // GIP1
            maxPayloadBytes = 4 * 1024,
            label = "GIPHY credential"
        )
    }
}

/**
 * A single explicit source per GIF grid. The resolver never merges provider results.
 *
 * `Standard` keeps the existing KLIPY-or-Noto behavior. `Commons` is keyless open media;
 * `Giphy` remains approval-gated.
 */
enum class GifProviderSelection { Standard, Commons, Giphy }

/** Explicit provider choice; no key presence may silently switch a GIPHY-selected grid. */
class GifProviderSelectionStore internal constructor(private val file: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, RELATIVE_PATH))

    fun load(): GifProviderSelection = runCatching {
        file.takeIf(File::isFile)?.readText()?.trim()?.let(GifProviderSelection::valueOf)
    }.getOrNull() ?: GifProviderSelection.Standard

    fun save(selection: GifProviderSelection) {
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, "${file.name}.new").apply { delete() }
        val backup = File(file.parentFile, "${file.name}.bak").apply { delete() }
        try {
            FileOutputStream(pending).use { output ->
                output.write(selection.name.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (file.exists() && !file.renameTo(backup)) {
                throw IllegalStateException("Could not preserve GIF provider selection")
            }
            if (!pending.renameTo(file)) {
                if (backup.isFile) backup.renameTo(file)
                throw IllegalStateException("Could not store GIF provider selection")
            }
            backup.delete()
        } catch (error: Throwable) {
            pending.delete()
            if (!file.exists() && backup.isFile) backup.renameTo(file)
            throw error
        }
    }

    private companion object {
        const val RELATIVE_PATH = "gif/provider-selection"
    }
}

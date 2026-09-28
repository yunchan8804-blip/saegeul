/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.content.Context
import org.fcitx.fcitx5.android.input.AndroidKeystoreProfileCipher
import org.fcitx.fcitx5.android.input.EncryptedProfileCipher
import org.fcitx.fcitx5.android.input.EncryptedProfileFormat
import org.fcitx.fcitx5.android.input.EncryptedProfilePayload
import org.fcitx.fcitx5.android.input.EncryptedProfileStorage
import java.io.File

/** The state exposed to settings never contains the credential itself. */
enum class GifProviderCredentialState {
    Missing,
    Configured,
    Unreadable
}

/**
 * Stores the optional KLIPY credential outside SharedPreferences and every backup/export path.
 * The file contains only AES-GCM ciphertext; its key remains non-exportable in Android Keystore.
 */
class GifProviderCredentialStore internal constructor(
    file: File,
    cipher: EncryptedProfileCipher
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, RELATIVE_PATH),
        AndroidKeystoreProfileCipher(KEY_ALIAS)
    )

    private val storage = EncryptedProfileStorage(file, FORMAT, cipher)

    fun loadKey(): String? {
        return runCatching {
            val plaintext = storage.load() ?: return null
            try {
                plaintext.toString(Charsets.UTF_8)
                    .trim()
                    .takeIf { it.length in 1..MAX_KEY_CHARACTERS }
            } finally {
                plaintext.fill(0)
            }
        }.getOrNull()
    }

    fun state(): GifProviderCredentialState = when {
        !storage.exists() -> GifProviderCredentialState.Missing
        loadKey() != null -> GifProviderCredentialState.Configured
        else -> GifProviderCredentialState.Unreadable
    }

    fun saveKey(apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.length in 1..MAX_KEY_CHARACTERS) { "Enter a valid KLIPY API key" }
        val plaintext = normalized.toByteArray(Charsets.UTF_8)
        try {
            storage.save(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun clear() {
        storage.clear()
    }

    private companion object {
        const val RELATIVE_PATH = "gif/provider.bin"
        const val KEY_ALIAS = "fcitx.gif.klipy.v1"
        const val MAX_KEY_CHARACTERS = 512
        val FORMAT = EncryptedProfileFormat(
            magic = 0x47494631, // GIF1
            maxPayloadBytes = 4 * 1024,
            label = "GIF credential"
        )
    }
}

/** Earlier name of [EncryptedProfilePayload] used by the GIF credential tests. */
internal typealias GifEncryptedPayload = EncryptedProfilePayload

/** Earlier name of [EncryptedProfileCipher] used by the GIF credential tests. */
internal typealias GifCredentialCipher = EncryptedProfileCipher

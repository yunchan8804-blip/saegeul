/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import java.io.File

/**
 * One-shot cleanup of credentials that the removed external writing-AI feature (BYOK API keys,
 * OAuth sessions, usage log) left behind under [noBackupFilesDir][android.content.Context.noBackupFilesDir]
 * and the app's `AndroidKeyStore` entries.
 *
 * This purge deletes files by exact path, never a directory, so it cannot remove unrelated state
 * that happens to share the `ai/` directory. [purge] is a pure function over its inputs so it can
 * be unit tested without a real filesystem or `KeyStore`; the caller wires the real
 * `noBackupFilesDir` and a `KeyStore.deleteEntry` closure.
 */
object ExternalAiCredentialPurge {
    /** SharedPreferences flag set only after a fully successful purge. */
    const val PREF_KEY = "external_ai_credentials_purged_v1"

    internal val RELATIVE_FILE_PATHS = listOf(
        "ai/provider.bin",
        "ai/oauth-session.bin",
        "ai/usage.json"
    )

    internal val KEYSTORE_ALIASES = listOf(
        "fcitx.ai.provider.v1",
        "fcitx.ai.oauth.v1"
    )

    /**
     * Deletes every stale file and Keystore alias. Returns true only when all of them are gone
     * (already absent counts as success); a partial failure returns false so the caller leaves
     * the one-shot flag unset and retries on the next normal start.
     */
    fun purge(noBackupFilesDir: File, deleteKeystoreAlias: (String) -> Unit): Boolean {
        var allSucceeded = true
        for (relativePath in RELATIVE_FILE_PATHS) {
            val file = File(noBackupFilesDir, relativePath)
            if (file.exists() && !file.delete()) {
                allSucceeded = false
            }
        }
        for (alias in KEYSTORE_ALIASES) {
            val deleted = runCatching { deleteKeystoreAlias(alias) }.isSuccess
            if (!deleted) allSucceeded = false
        }
        return allSucceeded
    }
}

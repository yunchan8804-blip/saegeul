/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

/**
 * Encrypts and decrypts personalization vault payloads (n-gram models, typo correction pairs,
 * language fingerprints, personal sentences) before they touch disk.
 *
 * Implementations must throw the underlying [java.security.GeneralSecurityException] on
 * decryption failure rather than swallowing it, so callers can decide whether to start fresh.
 */
interface VaultCipher {

    /**
     * Short identifier recorded in the [VaultFile] format header, so a file can be read back
     * with the cipher it was written with. Example: "plain", "aesgcm".
     */
    val id: String

    /**
     * Encrypts [plain] bound to [aad] (additional authenticated data), returning an opaque blob.
     */
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray

    /**
     * Decrypts a [blob] previously produced by [encrypt] with the same [aad].
     * Throws a [java.security.GeneralSecurityException] subtype on failure (bad tag, wrong key,
     * mismatched AAD, malformed blob).
     */
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/**
 * Identity cipher used for legacy/test paths where no encryption is applied.
 */
object PlainVaultCipher : VaultCipher {
    override val id: String = "plain"

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray = plain

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray = blob
}

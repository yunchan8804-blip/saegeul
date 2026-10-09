/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import org.junit.rules.ExternalResource
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cipher with a fixed id whose decryption can be switched to always fail, so the same file can be
 * written by a working instance and then opened by one that cannot read it.
 */
internal class SwitchableVaultCipher(@Volatile var failReads: Boolean = false) : VaultCipher {
    override val id: String = "switchable"

    val decryptCalls = AtomicInteger()

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray = plain

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        decryptCalls.incrementAndGet()
        if (failReads) throw GeneralSecurityException("injected vault read failure")
        return blob
    }
}

/** Lets every read attempt through immediately and restores the read-block settings afterwards. */
class ImmediateReadRetryRule : ExternalResource() {
    private var savedInterval = 0L
    private var savedClock: () -> Long = System::currentTimeMillis

    override fun before() {
        savedInterval = VaultReadBlocks.retryIntervalMs
        savedClock = VaultReadBlocks.clock
        VaultReadBlocks.retryIntervalMs = 0L
    }

    override fun after() {
        VaultReadBlocks.retryIntervalMs = savedInterval
        VaultReadBlocks.clock = savedClock
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase.dynamic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SensitivePhraseVaultTest {
    private val account = SensitivePhrase(
        id = "account-1",
        label = "급여 계좌",
        value = "123-456-789",
        kind = SensitivePhraseKind.Account,
        allowedPackages = setOf("com.kakao.talk", "com.slack")
    )

    @Test
    fun `vault codec preserves explicit items and package allowlists`() {
        val vault = SensitivePhraseVault(listOf(account))

        assertEquals(vault, decodeSensitivePhraseVault(encodeSensitivePhraseVault(vault)))
    }

    @Test
    fun `invalid empty allowlist and malformed packages fail closed`() {
        listOf(
            account.copy(allowedPackages = emptySet()),
            account.copy(allowedPackages = setOf("not a package")),
            account.copy(value = "")
        ).forEach { item ->
            try {
                item.validate()
                fail("Invalid sensitive phrase was accepted")
            } catch (_: IllegalArgumentException) {
                // Expected fail-closed validation.
            }
        }
    }

    @Test
    fun `package private and unlock policy are all required`() {
        assertTrue(SensitivePhrasePolicy.canExpose(account, "com.kakao.talk", true, false))
        assertFalse(SensitivePhrasePolicy.canExpose(account, "com.other", true, false))
        assertFalse(SensitivePhrasePolicy.canExpose(account, "com.kakao.talk", false, false))
        assertFalse(SensitivePhrasePolicy.canExpose(account, "com.kakao.talk", true, true))
    }

    @Test
    fun `unlock expires at ttl and locks on app transition`() {
        var now = 1_000L
        val state = SensitivePhraseUnlockState(ttlMillis = 60_000L) { now }
        state.unlockFor("com.kakao.talk")
        assertTrue(state.isUnlockedFor("com.kakao.talk"))

        state.onEditorPackageChanged("com.slack")
        assertFalse(state.isUnlockedFor("com.kakao.talk"))

        state.unlockFor("com.kakao.talk")
        now += 60_000L
        assertFalse(state.isUnlockedFor("com.kakao.talk"))
    }

    @Test
    fun `commit gate invokes editor exactly once even after failure`() {
        val gate = SensitivePhraseCommitGate()
        var calls = 0

        assertFalse(gate.commitOnce { calls += 1; false })
        assertFalse(gate.commitOnce { calls += 1; true })
        assertEquals(1, calls)

        val successfulGate = SensitivePhraseCommitGate()
        var successfulCalls = 0
        assertTrue(successfulGate.commitOnce { successfulCalls += 1; true })
        assertFalse(successfulGate.commitOnce { successfulCalls += 1; true })
        assertEquals(1, successfulCalls)
    }

    @Test
    fun `corrupt or future vault payload is rejected`() {
        listOf(
            "not-json",
            "{\"version\":99,\"items\":[]}",
            "{\"version\":1,\"items\":[{\"id\":\"x\"}]}"
        ).forEach { payload ->
            try {
                decodeSensitivePhraseVault(payload.toByteArray())
                fail("Corrupt vault payload was accepted")
            } catch (_: Exception) {
                // The caller keeps the vault locked on any decoding error.
            }
        }
    }

    @Test
    fun `vault contract uses no-backup relative storage and stores no editor history fields`() {
        assertEquals("dynamic-phrase/vault.bin", VAULT_RELATIVE_PATH)
        val plaintextSchema = encodeSensitivePhraseVault(SensitivePhraseVault(listOf(account)))
            .toString(Charsets.UTF_8)
        listOf("editorText", "history", "selection", "sourcePackage").forEach {
            assertFalse(plaintextSchema.contains(it, ignoreCase = true))
        }
    }
}

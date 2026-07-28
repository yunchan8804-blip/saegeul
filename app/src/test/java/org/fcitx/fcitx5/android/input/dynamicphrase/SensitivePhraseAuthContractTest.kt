/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhraseVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivePhraseAuthContractTest {
    private val target = DynamicPhraseEditorTarget("chat.app", 7, 1, 12, 12)

    @Test
    fun `authenticated vault is claimed and consumed exactly once`() {
        val queue = SensitivePhraseAuthResumeQueue<String>(elapsedRealtime = { 0L })
        val vault = SensitivePhraseVault()

        queue.begin(41L, target, "cipher")
        assertEquals("cipher", queue.claim(41L))
        assertTrue(queue.complete(41L, vault))
        assertEquals(
            SensitivePhraseAuthResumeResult(target, vault),
            queue.consumeForEditor("chat.app", 7, 1)
        )
        assertNull(queue.consumeForEditor("chat.app", 7, 1))
    }

    @Test
    fun `editor mismatch discards decrypted result`() {
        val queue = SensitivePhraseAuthResumeQueue<String>(elapsedRealtime = { 0L })
        queue.begin(42L, target, "cipher")
        assertEquals("cipher", queue.claim(42L))
        queue.complete(42L, SensitivePhraseVault())

        assertNull(queue.consumeForEditor("other.app", 7, 1))
        assertNull(queue.consumeForEditor("chat.app", 7, 1))
    }

    @Test
    fun `new request invalidates old cipher and result`() {
        val queue = SensitivePhraseAuthResumeQueue<String>(elapsedRealtime = { 0L })
        queue.begin(1L, target.copy(packageName = "old.app"), "old")
        queue.begin(2L, target, "latest")

        assertNull(queue.claim(1L))
        assertEquals("latest", queue.claim(2L))
        assertTrue(!queue.complete(1L, SensitivePhraseVault()))
        assertTrue(queue.complete(2L, SensitivePhraseVault()))
    }

    @Test
    fun `cipher and decrypted result expire without persistence`() {
        var now = 1_000L
        val queue = SensitivePhraseAuthResumeQueue<String>(ttlMillis = 60_000L) { now }
        queue.begin(43L, target, "cipher")
        now += 60_000L
        assertNull(queue.claim(43L))

        queue.begin(44L, target, "cipher")
        assertEquals("cipher", queue.claim(44L))
        queue.complete(44L, SensitivePhraseVault())
        now += 60_000L
        assertNull(queue.consumeForEditor("chat.app", 7, 1))
    }

    @Test
    fun `cancel removes pending authentication operation`() {
        val queue = SensitivePhraseAuthResumeQueue<String>(elapsedRealtime = { 0L })
        queue.begin(45L, target, "cipher")
        assertEquals("cipher", queue.claim(45L))
        queue.cancel(45L)

        assertNull(queue.claim(45L))
        assertTrue(!queue.complete(45L, SensitivePhraseVault()))
    }

    @Test
    fun `cipher can be claimed by one authentication host only`() {
        val queue = SensitivePhraseAuthResumeQueue<String>(elapsedRealtime = { 0L })
        queue.begin(46L, target, "cipher")

        assertEquals("cipher", queue.claim(46L))
        assertNull(queue.claim(46L))
        assertTrue(queue.complete(46L, SensitivePhraseVault()))
    }

    @Test
    fun `unclaimed or cancelled authentication cannot deliver a vault`() {
        val queue = SensitivePhraseAuthResumeQueue<String>(elapsedRealtime = { 0L })
        queue.begin(47L, target, "cipher")
        assertTrue(!queue.complete(47L, SensitivePhraseVault()))

        queue.begin(48L, target, "cipher")
        assertEquals("cipher", queue.claim(48L))
        queue.cancel(48L)
        assertTrue(!queue.complete(48L, SensitivePhraseVault()))
    }
}

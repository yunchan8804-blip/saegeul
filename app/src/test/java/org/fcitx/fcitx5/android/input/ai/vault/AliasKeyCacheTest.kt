/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey

class AliasKeyCacheTest {

    @Test
    fun concurrentCallsForOneAliasCreateTheKeyOnce() {
        val cache = AliasKeyCache()
        val creations = AtomicInteger()
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<SecretKey> {
                    ready.countDown()
                    go.await()
                    cache.keyed("saegeul.vault.v2") {
                        creations.incrementAndGet()
                        Thread.sleep(150)
                        AesGcmVaultCipher.randomKey()
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            val keys = results.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, creations.get())
            assertSame(keys[0], keys[1])
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun differentAliasesGetTheirOwnKeys() {
        val cache = AliasKeyCache()
        val creations = AtomicInteger()

        val first = cache.keyed("a") { creations.incrementAndGet(); AesGcmVaultCipher.randomKey() }
        val second = cache.keyed("b") { creations.incrementAndGet(); AesGcmVaultCipher.randomKey() }

        assertEquals(2, creations.get())
        assertSame(first, cache.keyed("a") { error("must be cached") })
        assertSame(second, cache.keyed("b") { error("must be cached") })
    }
}

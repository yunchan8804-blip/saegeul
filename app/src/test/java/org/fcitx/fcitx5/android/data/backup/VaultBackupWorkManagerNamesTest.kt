/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.backup

import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGraphEnrichmentScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [VaultBackup.GEMMA_BACKGROUND_WORK_NAMES] against the actual `WorkManager` unique work
 * names the Gemma schedulers use, so the two definitions can't silently drift apart.
 */
class VaultBackupWorkManagerNamesTest {

    @Test
    fun graphEnrichmentWorkNamesMatchTheirPublicSchedulerConstants() {
        assertTrue(
            VaultBackup.GEMMA_BACKGROUND_WORK_NAMES.contains(GemmaGraphEnrichmentScheduler.PERIODIC_WORK_NAME)
        )
        assertTrue(
            VaultBackup.GEMMA_BACKGROUND_WORK_NAMES.contains(GemmaGraphEnrichmentScheduler.MANUAL_WORK_NAME)
        )
    }

    @Test
    fun accumulationWorkNamesMatchTheSchedulersPrivateConstants() {
        val periodic = readPrivateConstString(
            "org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler",
            "PERIODIC_WORK_NAME"
        )
        val oneTime = readPrivateConstString(
            "org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler",
            "ONE_TIME_WORK_NAME"
        )

        assertTrue(VaultBackup.GEMMA_BACKGROUND_WORK_NAMES.contains(periodic))
        assertTrue(VaultBackup.GEMMA_BACKGROUND_WORK_NAMES.contains(oneTime))
    }

    @Test
    fun exactlyFourNamesAreDeclaredAndNoneAreDuplicates() {
        assertEquals(4, VaultBackup.GEMMA_BACKGROUND_WORK_NAMES.size)
        assertEquals(4, VaultBackup.GEMMA_BACKGROUND_WORK_NAMES.toSet().size)
    }

    private fun readPrivateConstString(className: String, fieldName: String): String {
        val field = Class.forName(className).getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(null) as String
    }
}

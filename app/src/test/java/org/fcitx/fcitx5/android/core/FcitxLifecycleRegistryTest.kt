/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FcitxLifecycleRegistryTest {

    @Test
    fun engineGenerationAdvancesWhenStopCompletesButNotOtherLifecycleStates() {
        val registry = FcitxLifecycleRegistry()
        val lifecycle: FcitxLifecycle = registry

        assertEquals(0L, lifecycle.engineGeneration.value)

        registry.postEvent(FcitxLifecycle.Event.ON_START)
        assertEquals(0L, lifecycle.engineGeneration.value)

        registry.postEvent(FcitxLifecycle.Event.ON_READY)
        assertEquals(0L, lifecycle.engineGeneration.value)

        registry.postEvent(FcitxLifecycle.Event.ON_STOP)
        assertEquals(0L, lifecycle.engineGeneration.value)

        registry.postEvent(FcitxLifecycle.Event.ON_STOPPED)
        assertEquals(1L, lifecycle.engineGeneration.value)
    }

    @Test
    fun everyRestartBoundaryAdvancesEngineGenerationExactlyOnce() {
        val registry = FcitxLifecycleRegistry()
        val lifecycle: FcitxLifecycle = registry

        repeat(2) { restartIndex ->
            registry.postEvent(FcitxLifecycle.Event.ON_START)
            registry.postEvent(FcitxLifecycle.Event.ON_READY)
            registry.postEvent(FcitxLifecycle.Event.ON_STOP)

            assertEquals(restartIndex.toLong(), lifecycle.engineGeneration.value)

            registry.postEvent(FcitxLifecycle.Event.ON_STOPPED)
            assertEquals((restartIndex + 1).toLong(), lifecycle.engineGeneration.value)
        }
    }
}

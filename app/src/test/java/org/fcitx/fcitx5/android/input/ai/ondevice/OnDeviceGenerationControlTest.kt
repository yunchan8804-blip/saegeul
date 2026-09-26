/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OnDeviceGenerationControlTest {

    @Test
    fun `public material is blocked while the input view is visible`() {
        var cancelled = 0
        OnDeviceGenerationControl.onInputViewVisibilityChanged(true)

        assertNull(OnDeviceGenerationControl.tryBegin { cancelled += 1 })

        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)

        val lease = requireNotNull(OnDeviceGenerationControl.tryBegin { cancelled += 1 })
        assertTrue(OnDeviceGenerationControl.end(lease))
        assertEquals(0, cancelled)
    }

    @Test
    fun `explicit context can begin while keyboard is active`() {
        var cancelled = 0
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)

        val lease = requireNotNull(OnDeviceGenerationControl.tryBegin(
            purpose = OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT
        ) { cancelled += 1 })

        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)

        assertEquals(0, cancelled)
        assertTrue(OnDeviceGenerationControl.isGenerating)
        assertTrue(OnDeviceGenerationControl.end(lease))
        val activeKeyboardLease = OnDeviceGenerationControl.tryBegin(
            purpose = OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT
        ) {}
        assertNotNull(activeKeyboardLease)
        assertTrue(OnDeviceGenerationControl.end(requireNotNull(activeKeyboardLease)))
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
    }

    @Test
    fun `automatic context requires an active keyboard`() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        assertNull(
            OnDeviceGenerationControl.tryBegin(
                purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
            ) {}
        )

        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        val lease = requireNotNull(
            OnDeviceGenerationControl.tryBegin(
                purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
            ) {}
        )
        assertTrue(OnDeviceGenerationControl.end(lease))
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
    }

    @Test
    fun `automatic context is cancelled when keyboard hides`() {
        var cancelled = 0
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        val lease = requireNotNull(
            OnDeviceGenerationControl.tryBegin(
                purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
            ) { cancelled += 1 }
        )

        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)

        assertEquals(1, cancelled)
        assertTrue(OnDeviceGenerationControl.isGenerating)
        assertTrue(OnDeviceGenerationControl.end(lease))
    }

    @Test
    fun `stale automatic lease cannot receive a keyboard hide cancellation`() {
        var firstCancelled = 0
        var secondCancelled = 0
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        val first = requireNotNull(
            OnDeviceGenerationControl.tryBegin(
                purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
            ) { firstCancelled += 1 }
        )
        assertTrue(OnDeviceGenerationControl.end(first))
        val second = requireNotNull(
            OnDeviceGenerationControl.tryBegin(
                purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
            ) { secondCancelled += 1 }
        )

        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)

        assertEquals(0, firstCancelled)
        assertEquals(1, secondCancelled)
        assertTrue(OnDeviceGenerationControl.end(second))
    }

    @Test
    fun `leases are mutually exclusive`() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        val lease = requireNotNull(OnDeviceGenerationControl.tryBegin {})

        assertNull(OnDeviceGenerationControl.tryBegin {})
        assertTrue(OnDeviceGenerationControl.end(lease))
    }

    @Test
    fun `cancellation keeps lease owned until it ends`() {
        var cancelled = 0
        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        val lease = requireNotNull(OnDeviceGenerationControl.tryBegin { cancelled += 1 })

        OnDeviceGenerationControl.onInputViewVisibilityChanged(true)

        assertEquals(1, cancelled)
        assertTrue(OnDeviceGenerationControl.isGenerating)
        assertNull(
            OnDeviceGenerationControl.tryBegin(
                purpose = OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT
            ) {}
        )
        assertTrue(OnDeviceGenerationControl.end(lease))
        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
    }

    @Test
    fun `stale lease cannot end newer generation`() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        val firstLease = requireNotNull(OnDeviceGenerationControl.tryBegin {})
        assertTrue(OnDeviceGenerationControl.end(firstLease))
        val secondLease = requireNotNull(OnDeviceGenerationControl.tryBegin {})

        assertFalse(OnDeviceGenerationControl.end(firstLease))
        assertTrue(OnDeviceGenerationControl.isGenerating)
        assertTrue(OnDeviceGenerationControl.end(secondLease))
    }

    @Test
    fun `double lease end does not release a newer generation`() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        val firstLease = requireNotNull(OnDeviceGenerationControl.tryBegin {})
        assertTrue(OnDeviceGenerationControl.end(firstLease))
        assertFalse(OnDeviceGenerationControl.end(firstLease))
        val secondLease = requireNotNull(OnDeviceGenerationControl.tryBegin {})

        assertFalse(OnDeviceGenerationControl.end(firstLease))
        assertTrue(OnDeviceGenerationControl.isGenerating)
        assertTrue(OnDeviceGenerationControl.end(secondLease))
    }

    @Test
    fun `a throwing preemption handler still hands the lease to the preempting caller`() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        try {
            OnDeviceGenerationControl.configureAutoContextPreemption(
                isBusy = { false },
                preempt = { throw IllegalStateException("Check failed.") },
                onExplicitContextFinished = null
            )
            requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
                ) {}
            )
            // The keyboard is done (its hide grace elapsed) by the time material tries to preempt
            // the warm automatic-context lease - keyboard-active is what would otherwise block it.
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)

            // Material accumulation preempts from a worker thread; the handler's failure must not
            // propagate out of tryBegin, or the newly assigned lease would never be released.
            val materialLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.PUBLIC_MATERIAL
                ) {}
            )
            assertTrue(OnDeviceGenerationControl.isGenerating)
            assertTrue(OnDeviceGenerationControl.end(materialLease))
            assertFalse(OnDeviceGenerationControl.isGenerating)
        } finally {
            OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    @Test
    fun `explicit context preempts a warm idle automatic context lease`() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        var autoCancelled = 0
        var preempted = 0
        try {
            OnDeviceGenerationControl.configureAutoContextPreemption(
                isBusy = { false },
                preempt = { preempted += 1 },
                onExplicitContextFinished = null
            )
            val autoLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
                ) { autoCancelled += 1 }
            )

            val explicitLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT
                ) {}
            )

            assertEquals(1, preempted)
            assertEquals(0, autoCancelled)
            assertTrue(OnDeviceGenerationControl.isGenerating)
            assertFalse(OnDeviceGenerationControl.end(autoLease))
            assertTrue(OnDeviceGenerationControl.end(explicitLease))
        } finally {
            OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    @Test
    fun `explicit context preempts an automatic context lease that is still warming up`() {
        // A warm-up in progress is not "busy" for preemption purposes: the user's explicit,
        // tap-triggered completion outranks a background warm-up that has not produced anything
        // yet. The busy probe here models exactly that (false while only warming up).
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        var warmupCancelled = 0
        var preempted = 0
        try {
            OnDeviceGenerationControl.configureAutoContextPreemption(
                isBusy = { false },
                preempt = { preempted += 1 },
                onExplicitContextFinished = null
            )
            val warmingUpLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
                ) { warmupCancelled += 1 }
            )

            val explicitLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT
                ) {}
            )

            assertEquals(1, preempted)
            assertEquals(0, warmupCancelled)
            assertFalse(OnDeviceGenerationControl.end(warmingUpLease))
            assertTrue(OnDeviceGenerationControl.end(explicitLease))
        } finally {
            OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    @Test
    fun `explicit context is denied while automatic context is actively generating`() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        var preempted = 0
        try {
            OnDeviceGenerationControl.configureAutoContextPreemption(
                isBusy = { true },
                preempt = { preempted += 1 },
                onExplicitContextFinished = null
            )
            val autoLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
                ) {}
            )

            assertNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT
                ) {}
            )

            assertEquals(0, preempted)
            assertTrue(OnDeviceGenerationControl.isGenerating)
            assertTrue(OnDeviceGenerationControl.end(autoLease))
        } finally {
            OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    @Test
    fun `automatic context request is still denied while explicit context holds the lease`() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        var preempted = 0
        try {
            OnDeviceGenerationControl.configureAutoContextPreemption(
                isBusy = { false },
                preempt = { preempted += 1 },
                onExplicitContextFinished = null
            )
            val explicitLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT
                ) {}
            )

            assertNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
                ) {}
            )

            assertEquals(0, preempted)
            assertTrue(OnDeviceGenerationControl.isGenerating)
            assertTrue(OnDeviceGenerationControl.end(explicitLease))
        } finally {
            OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    @Test
    fun `explicit context finished callback fires only when its own lease ends`() {
        OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
        var finished = 0
        try {
            OnDeviceGenerationControl.configureAutoContextPreemption(
                isBusy = null,
                preempt = null,
                onExplicitContextFinished = { finished += 1 }
            )
            val autoLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
                ) {}
            )
            assertTrue(OnDeviceGenerationControl.end(autoLease))
            assertEquals(0, finished)

            val explicitLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT
                ) {}
            )
            assertTrue(OnDeviceGenerationControl.end(explicitLease))
            assertEquals(1, finished)
        } finally {
            OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    @Test
    fun `input view cancellation callback can end its own lease`() {
        val executor = Executors.newSingleThreadExecutor()
        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        var lease: OnDeviceGenerationControl.Lease? = null
        try {
            lease = requireNotNull(OnDeviceGenerationControl.tryBegin {
                val callbackLease = checkNotNull(lease)
                val completed = executor.submit<Boolean> {
                    OnDeviceGenerationControl.end(callbackLease)
                }
                assertTrue(completed.get(1, TimeUnit.SECONDS))
            })

            OnDeviceGenerationControl.onInputViewVisibilityChanged(true)

            assertFalse(OnDeviceGenerationControl.isGenerating)
        } finally {
            lease?.let(OnDeviceGenerationControl::end)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
            executor.shutdownNow()
        }
    }

    @Test
    fun `public material is denied while the keyboard grace is active even if the input view is hidden`() {
        // The keyboard outranks background generation: keyboard-active alone (the up-to-10-minute
        // hide grace), even with the input view already hidden, must still block material and the
        // personal graph from taking or preempting a lease - see the on-device trace in
        // OnDeviceGenerationControl's tryBegin doc (the graph preempted a live automatic-suggestion
        // lease while the user was still typing, because only input-view-visible was checked).
        val now = 1_000_000L
        OnDeviceGenerationControl.clockMs = { now }
        try {
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
            // The input view was hidden only moments ago (well under the 30s grace).
            OnDeviceGenerationControl.onInputViewVisibilityChanged(true)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)

            assertTrue(OnDeviceGenerationControl.isKeyboardActive)
            assertFalse(OnDeviceGenerationControl.isInputViewVisible)

            assertNull(OnDeviceGenerationControl.tryBegin {})
            assertNull(
                OnDeviceGenerationControl.tryBegin(purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH) {}
            )

            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)

            val lease = requireNotNull(OnDeviceGenerationControl.tryBegin {})
            assertTrue(OnDeviceGenerationControl.end(lease))
        } finally {
            OnDeviceGenerationControl.clockMs = System::currentTimeMillis
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        }
    }

    @Test
    fun `public material is denied while the input view is visible`() {
        try {
            OnDeviceGenerationControl.onInputViewVisibilityChanged(true)

            assertNull(OnDeviceGenerationControl.tryBegin {})
        } finally {
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        }
    }

    @Test
    fun `input view visibility rising edge cancels an active public material lease`() {
        var cancelled = 0
        var lease: OnDeviceGenerationControl.Lease? = null
        try {
            lease = requireNotNull(OnDeviceGenerationControl.tryBegin { cancelled += 1 })

            OnDeviceGenerationControl.onInputViewVisibilityChanged(true)

            assertEquals(1, cancelled)
            assertTrue(OnDeviceGenerationControl.isGenerating)
            assertTrue(OnDeviceGenerationControl.end(requireNotNull(lease)))
        } finally {
            lease?.let(OnDeviceGenerationControl::end)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        }
    }

    @Test
    fun `background purposes wait out the quiet window after the service starts`() {
        var now = 1_000_000L
        OnDeviceGenerationControl.clockMs = { now }
        try {
            OnDeviceGenerationControl.onServiceStarted()
            assertNull(OnDeviceGenerationControl.tryBegin {})
            assertNull(
                OnDeviceGenerationControl.tryBegin(purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH) {}
            )
            val explicit = requireNotNull(
                OnDeviceGenerationControl.tryBegin(purpose = OnDeviceGenerationControl.Purpose.EXPLICIT_CONTEXT) {}
            )
            assertTrue(OnDeviceGenerationControl.end(explicit))

            now += OnDeviceGenerationControl.SERVICE_START_BACKGROUND_QUIET_MS
            val material = requireNotNull(OnDeviceGenerationControl.tryBegin {})
            assertTrue(OnDeviceGenerationControl.end(material))
        } finally {
            OnDeviceGenerationControl.backgroundQuietUntilMs = 0L
            OnDeviceGenerationControl.clockMs = System::currentTimeMillis
        }
    }

    @Test
    fun `public material preempts a warm idle automatic context lease`() {
        var preempted = 0
        var autoLease: OnDeviceGenerationControl.Lease? = null
        try {
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
            OnDeviceGenerationControl.configureAutoContextPreemption(
                isBusy = { false },
                preempt = { preempted += 1 },
                onExplicitContextFinished = null
            )
            autoLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
                ) {}
            )
            // The keyboard is done (its hide grace elapsed) by the time material tries to preempt
            // the warm automatic-context lease - keyboard-active is what would otherwise block it.
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)

            val materialLease = OnDeviceGenerationControl.tryBegin {}

            assertNotNull(materialLease)
            assertEquals(1, preempted)
            assertFalse(OnDeviceGenerationControl.end(requireNotNull(autoLease)))
            assertTrue(OnDeviceGenerationControl.end(requireNotNull(materialLease)))
        } finally {
            OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
            autoLease?.let(OnDeviceGenerationControl::end)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    @Test
    fun `public material is denied while automatic context is actively generating`() {
        var preempted = 0
        var autoLease: OnDeviceGenerationControl.Lease? = null
        try {
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
            OnDeviceGenerationControl.configureAutoContextPreemption(
                isBusy = { true },
                preempt = { preempted += 1 },
                onExplicitContextFinished = null
            )
            autoLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
                ) {}
            )

            assertNull(OnDeviceGenerationControl.tryBegin {})

            assertEquals(0, preempted)
            assertTrue(OnDeviceGenerationControl.end(requireNotNull(autoLease)))
        } finally {
            OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
            autoLease?.let(OnDeviceGenerationControl::end)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    @Test
    fun `personal graph is denied while the input view is visible`() {
        try {
            OnDeviceGenerationControl.onInputViewVisibilityChanged(true)

            assertNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH
                ) {}
            )
        } finally {
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        }
    }

    @Test
    fun `input view visibility rising edge cancels an active personal graph lease`() {
        var cancelled = 0
        var lease: OnDeviceGenerationControl.Lease? = null
        try {
            lease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH
                ) { cancelled += 1 }
            )

            OnDeviceGenerationControl.onInputViewVisibilityChanged(true)

            assertEquals(1, cancelled)
            assertTrue(OnDeviceGenerationControl.isGenerating)
            assertTrue(OnDeviceGenerationControl.end(requireNotNull(lease)))
        } finally {
            lease?.let(OnDeviceGenerationControl::end)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        }
    }

    @Test
    fun `personal graph preempts a warm idle automatic context lease`() {
        var preempted = 0
        var autoLease: OnDeviceGenerationControl.Lease? = null
        try {
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
            OnDeviceGenerationControl.configureAutoContextPreemption(
                isBusy = { false },
                preempt = { preempted += 1 },
                onExplicitContextFinished = null
            )
            autoLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
                ) {}
            )
            // The keyboard is done (its hide grace elapsed) by the time the graph tries to preempt
            // the warm automatic-context lease - keyboard-active is what would otherwise block it.
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)

            val graphLease = OnDeviceGenerationControl.tryBegin(
                purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH
            ) {}

            assertNotNull(graphLease)
            assertEquals(1, preempted)
            assertFalse(OnDeviceGenerationControl.end(requireNotNull(autoLease)))
            assertTrue(OnDeviceGenerationControl.end(requireNotNull(graphLease)))
        } finally {
            OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
            autoLease?.let(OnDeviceGenerationControl::end)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    @Test
    fun `personal graph does not preempt a warm automatic context lease while the keyboard is still active`() {
        // Exact on-device trace: "lease preempt purpose=PERSONAL_GRAPH activePurpose=AUTO_CONTEXT
        // keyboardActive=true" interrupted a live automatic suggestion the user was watching. The
        // input view was hidden only moments ago (well under the 30s grace), so PERSONAL_GRAPH must
        // still be denied here too, not just PUBLIC_MATERIAL.
        var preempted = 0
        var autoCancelled = 0
        var autoLease: OnDeviceGenerationControl.Lease? = null
        val now = 1_000_000L
        OnDeviceGenerationControl.clockMs = { now }
        try {
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(true)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
            OnDeviceGenerationControl.configureAutoContextPreemption(
                isBusy = { false },
                preempt = { preempted += 1 },
                onExplicitContextFinished = null
            )
            autoLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
                ) { autoCancelled += 1 }
            )

            assertNull(
                OnDeviceGenerationControl.tryBegin(purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH) {}
            )
            assertNull(
                OnDeviceGenerationControl.tryBegin(purpose = OnDeviceGenerationControl.Purpose.PUBLIC_MATERIAL) {}
            )

            assertEquals(0, preempted)
            assertEquals(0, autoCancelled)
            assertTrue(OnDeviceGenerationControl.isGenerating)
        } finally {
            OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
            OnDeviceGenerationControl.clockMs = System::currentTimeMillis
            autoLease?.let(OnDeviceGenerationControl::end)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    @Test
    fun `personal graph is still denied 29 seconds after the input view hides while keyboard grace is active`() {
        var now = 1_000_000L
        OnDeviceGenerationControl.clockMs = { now }
        try {
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(true)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)

            now += 29_000L

            assertNull(
                OnDeviceGenerationControl.tryBegin(purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH) {}
            )
        } finally {
            OnDeviceGenerationControl.clockMs = System::currentTimeMillis
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        }
    }

    @Test
    fun `personal graph is allowed 30 seconds after the input view hides even while keyboard grace is active`() {
        var now = 1_000_000L
        OnDeviceGenerationControl.clockMs = { now }
        try {
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(true)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)

            now += OnDeviceGenerationControl.PERSONAL_GRAPH_KEYBOARD_GRACE_MS

            val lease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH) {}
            )
            assertTrue(OnDeviceGenerationControl.end(lease))
        } finally {
            OnDeviceGenerationControl.clockMs = System::currentTimeMillis
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        }
    }

    @Test
    fun `personal graph still cannot preempt an actively generating automatic context lease even after the keyboard grace elapses`() {
        var now = 1_000_000L
        OnDeviceGenerationControl.clockMs = { now }
        var autoLease: OnDeviceGenerationControl.Lease? = null
        try {
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
            OnDeviceGenerationControl.configureAutoContextPreemption(
                isBusy = { true },
                preempt = {},
                onExplicitContextFinished = null
            )
            autoLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT) {}
            )
            OnDeviceGenerationControl.onInputViewVisibilityChanged(true)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
            now += OnDeviceGenerationControl.PERSONAL_GRAPH_KEYBOARD_GRACE_MS

            assertNull(
                OnDeviceGenerationControl.tryBegin(purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH) {}
            )
        } finally {
            OnDeviceGenerationControl.configureAutoContextPreemption(null, null, null)
            OnDeviceGenerationControl.clockMs = System::currentTimeMillis
            autoLease?.let(OnDeviceGenerationControl::end)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        }
    }

    @Test
    fun `public material remains denied throughout the keyboard grace regardless of elapsed time`() {
        var now = 1_000_000L
        OnDeviceGenerationControl.clockMs = { now }
        try {
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)

            now += OnDeviceGenerationControl.PERSONAL_GRAPH_KEYBOARD_GRACE_MS + 60_000L

            assertNull(OnDeviceGenerationControl.tryBegin {})
        } finally {
            OnDeviceGenerationControl.clockMs = System::currentTimeMillis
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }

    @Test
    fun `personal graph is denied while another lease is held`() {
        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        val materialLease = requireNotNull(OnDeviceGenerationControl.tryBegin {})

        assertNull(
            OnDeviceGenerationControl.tryBegin(
                purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH
            ) {}
        )

        assertTrue(OnDeviceGenerationControl.end(materialLease))
    }

    @Test
    fun `a manual personal graph request immediately preempts an in-progress public material generator`() {
        var materialCancelled = 0
        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        val materialLease = requireNotNull(OnDeviceGenerationControl.tryBegin { materialCancelled += 1 })

        val graphLease = requireNotNull(
            OnDeviceGenerationControl.tryBegin(
                purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH,
                preemptBackground = true
            ) {}
        )

        assertEquals(1, materialCancelled)
        assertFalse(OnDeviceGenerationControl.end(materialLease))
        assertTrue(OnDeviceGenerationControl.isGenerating)
        assertTrue(OnDeviceGenerationControl.end(graphLease))
    }

    @Test
    fun `an automatic personal graph request does not preempt an in-progress public material generator`() {
        var materialCancelled = 0
        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        val materialLease = requireNotNull(OnDeviceGenerationControl.tryBegin { materialCancelled += 1 })

        assertNull(
            OnDeviceGenerationControl.tryBegin(
                purpose = OnDeviceGenerationControl.Purpose.PERSONAL_GRAPH,
                preemptBackground = false
            ) {}
        )

        assertEquals(0, materialCancelled)
        assertTrue(OnDeviceGenerationControl.end(materialLease))
    }

    @Test
    fun `preemptBackground on a non-personal-graph purpose does not preempt public material`() {
        OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
        val materialLease = requireNotNull(OnDeviceGenerationControl.tryBegin {})

        assertNull(OnDeviceGenerationControl.tryBegin(preemptBackground = true) {})

        assertTrue(OnDeviceGenerationControl.end(materialLease))
    }

    @Test
    fun `input view visibility change does not cancel an automatic context lease`() {
        var cancelled = 0
        var autoLease: OnDeviceGenerationControl.Lease? = null
        try {
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(true)
            autoLease = requireNotNull(
                OnDeviceGenerationControl.tryBegin(
                    purpose = OnDeviceGenerationControl.Purpose.AUTO_CONTEXT
                ) { cancelled += 1 }
            )

            OnDeviceGenerationControl.onInputViewVisibilityChanged(true)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)

            assertEquals(0, cancelled)
            assertTrue(OnDeviceGenerationControl.isGenerating)
        } finally {
            autoLease?.let(OnDeviceGenerationControl::end)
            OnDeviceGenerationControl.onInputViewVisibilityChanged(false)
            OnDeviceGenerationControl.onKeyboardVisibilityChanged(false)
        }
    }
}

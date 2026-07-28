/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRestartEditorRehydrationGateTest {

    @Test
    fun `a requested generation only rehydrates at its matching READY boundary`() {
        val gate = readyGate()

        gate.requestForEngineGeneration(7)

        assertNull(gate.claimReady(6))
        val plan = requireNotNull(gate.claimReady(7))
        assertEquals(7, plan.engineGeneration)
        assertEquals(42, plan.uid)
        assertEquals("org.example.editor", plan.packageName)
        assertEquals("hangul", plan.inputMethodUniqueName)
        assertTrue(plan.shouldFocus)
    }

    @Test
    fun `duplicate READY or collector subscription cannot rehydrate one generation twice`() {
        val gate = readyGate()

        gate.requestForEngineGeneration(7)

        assertTrue(gate.claimReady(7) != null)
        assertNull(gate.claimReady(7))
        gate.requestForEngineGeneration(7)
        assertNull(gate.claimReady(7))
    }

    @Test
    fun `a failed restore can retry once only for its still-current editor`() {
        val gate = readyGate()
        gate.requestForEngineGeneration(7)
        val first = requireNotNull(gate.claimReady(7))

        assertTrue(gate.retryAfterFailedRestore(first))
        assertFalse(gate.retryAfterFailedRestore(first))
        val retry = requireNotNull(gate.claimReady(7))
        assertEquals(first.engineGeneration, retry.engineGeneration)
        assertEquals(first.inputMethodUniqueName, retry.inputMethodUniqueName)
    }

    @Test
    fun `a claimed generation refreshes a later start-input-view snapshot before native restore`() {
        val gate = readyGate()
        gate.requestForEngineGeneration(7)
        val claimed = requireNotNull(gate.claimReady(7))

        gate.onStartInputView(
            inputSessionEpoch = 11,
            editorPackageName = "org.example.editor",
            fieldId = 12,
            inputType = 99,
            capabilityFlags = CapabilityFlags(789u),
            shouldFocus = false
        )

        val refreshed = requireNotNull(gate.refreshClaimedPlan(claimed))
        assertFalse(gate.isCurrent(claimed))
        assertTrue(gate.isCurrent(refreshed))
        assertEquals(12, refreshed.fieldId)
        assertEquals(99, refreshed.inputType)
        assertEquals(CapabilityFlags(789u), refreshed.capabilityFlags)
        assertFalse(refreshed.shouldFocus)
    }

    @Test
    fun `waiting recovery uses the latest editor rather than a stale binding`() {
        val gate = readyGate()
        gate.requestForEngineGeneration(7)

        val latestFlags = CapabilityFlags(123u)
        gate.onBindInput(88, "org.example.next")
        gate.onStartInput(
            inputSessionEpoch = 12,
            editorPackageName = "org.example.next",
            fieldId = 4,
            inputType = 1,
            capabilityFlags = latestFlags,
            shouldFocus = false,
            isVirtualKeyboard = false
        )
        gate.onStartInputView(
            inputSessionEpoch = 12,
            editorPackageName = "org.example.next",
            fieldId = 9,
            inputType = 2,
            capabilityFlags = CapabilityFlags(456u),
            shouldFocus = false
        )
        gate.onInputMethodChanged("hangul")

        val plan = requireNotNull(gate.claimReady(7))
        assertEquals(88, plan.uid)
        assertEquals("org.example.next", plan.packageName)
        assertEquals(12, plan.inputSessionEpoch)
        assertEquals(9, plan.fieldId)
        assertEquals(2, plan.inputType)
        assertEquals(CapabilityFlags(456u), plan.capabilityFlags)
        assertFalse(plan.shouldFocus)
        assertFalse(plan.isVirtualKeyboard)
        assertEquals("hangul", plan.inputMethodUniqueName)
    }

    @Test
    fun `finished or unbound input cannot be revived by a waiting restart`() {
        val finished = readyGate()
        finished.requestForEngineGeneration(7)
        finished.onFinishInput()
        assertNull(finished.claimReady(7))

        val unbound = readyGate()
        unbound.requestForEngineGeneration(7)
        unbound.onUnbindInput()
        assertNull(unbound.claimReady(7))
    }

    @Test
    fun `later generation rehydrates once and preserves null editor no-focus policy`() {
        val gate = readyGate()
        gate.requestForEngineGeneration(7)
        val first = requireNotNull(gate.claimReady(7))

        gate.onStartInput(
            inputSessionEpoch = 13,
            editorPackageName = "org.example.editor",
            fieldId = 7,
            inputType = 0,
            capabilityFlags = CapabilityFlags.DefaultFlags,
            shouldFocus = false,
            isVirtualKeyboard = true
        )
        gate.requestForEngineGeneration(8)
        val second = requireNotNull(gate.claimReady(8))

        assertTrue(gate.isCurrent(second))
        assertFalse(second.shouldFocus)
        assertTrue(second.isVirtualKeyboard)
        assertFalse(gate.isCurrent(first))
        assertNull(gate.claimReady(8))
    }

    private fun readyGate(): EngineRestartEditorRehydrationGate =
        EngineRestartEditorRehydrationGate().apply {
            onBindInput(42, "org.example.editor")
            onStartInput(
                inputSessionEpoch = 11,
                editorPackageName = "org.example.editor",
                fieldId = 7,
                inputType = 1,
                capabilityFlags = CapabilityFlags.DefaultFlags,
                shouldFocus = true,
                isVirtualKeyboard = true,
                inputMethodUniqueName = "hangul"
            )
        }
}

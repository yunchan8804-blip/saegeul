/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdOfflineGateTest {
    @Test
    fun `reads the stored offline switch`() {
        assertTrue(AdOfflineGate.isOffline { true })
        assertFalse(AdOfflineGate.isOffline { false })
    }

    @Test
    fun `unreadable offline switch is treated as offline`() {
        assertTrue(AdOfflineGate.isOffline { error("prefs unavailable") })
    }
}

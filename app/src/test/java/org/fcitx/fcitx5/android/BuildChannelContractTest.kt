/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildChannelContractTest {
    @Test
    fun buildTypeAndPublicSurfaceUseTheSameDistributionChannel() {
        val expectedChannel = if (BuildConfig.DEBUG) "developer" else "user"

        assertEquals(expectedChannel, BuildConfig.DISTRIBUTION_CHANNEL)
        assertEquals(BuildConfig.DEBUG, BuildConfig.SHOW_DEVELOPER_SURFACES)
    }

    @Test
    fun publicApplicationIdUsesTheOwnedReverseDomain() {
        val expected = if (BuildConfig.DEBUG) {
            "net.chanpaca.saegeul.debug"
        } else {
            "net.chanpaca.saegeul"
        }

        assertEquals(expected, BuildConfig.APPLICATION_ID)
    }
}

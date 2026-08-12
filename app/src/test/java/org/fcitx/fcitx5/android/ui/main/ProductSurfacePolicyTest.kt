/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductSurfacePolicyTest {
    @Test
    fun userBuildHidesLowLevelAndDiagnosticSurfaces() {
        val policy = ProductSurfacePolicy.forBuild(developerSurfaces = false)

        assertFalse(policy.showRawEngineSettings)
        assertFalse(policy.showPluginManager)
        assertFalse(policy.showDeveloperTools)
    }

    @Test
    fun developerBuildExposesLowLevelAndDiagnosticSurfaces() {
        val policy = ProductSurfacePolicy.forBuild(developerSurfaces = true)

        assertTrue(policy.showRawEngineSettings)
        assertTrue(policy.showPluginManager)
        assertTrue(policy.showDeveloperTools)
    }
}

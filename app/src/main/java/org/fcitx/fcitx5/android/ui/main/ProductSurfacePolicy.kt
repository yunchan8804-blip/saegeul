/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.ui.main

/** Keeps the public product surface separate from low-level development controls. */
internal data class ProductSurfacePolicy(
    val showRawEngineSettings: Boolean,
    val showPluginManager: Boolean,
    val showDeveloperTools: Boolean
) {
    companion object {
        fun forBuild(developerSurfaces: Boolean) = ProductSurfacePolicy(
            showRawEngineSettings = developerSurfaces,
            showPluginManager = developerSurfaces,
            showDeveloperTools = developerSurfaces
        )
    }
}

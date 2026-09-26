/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [BackgroundProgressNotifier.shouldPost] directly. No Android [android.content.Context]
 * is available in a plain JVM unit test, so this fixes only the permission/preference decision, not
 * the actual notification building (which needs a real Context and is exercised on-device).
 */
class BackgroundProgressNotifierTest {

    @Test
    fun withoutPermissionNothingIsPostedRegardlessOfKindOrPref() {
        for (kind in BackgroundProgressNotifier.Kind.entries) {
            for (prefEnabled in listOf(true, false)) {
                assertFalse(
                    "$kind with prefEnabled=$prefEnabled should not post without permission",
                    BackgroundProgressNotifier.shouldPost(kind, permissionGranted = false, prefEnabled = prefEnabled)
                )
            }
        }
    }

    @Test
    fun progressAndDoneFollowThePreferenceWhenPermissionIsGranted() {
        assertTrue(
            BackgroundProgressNotifier.shouldPost(
                BackgroundProgressNotifier.Kind.PROGRESS,
                permissionGranted = true,
                prefEnabled = true
            )
        )
        assertFalse(
            BackgroundProgressNotifier.shouldPost(
                BackgroundProgressNotifier.Kind.PROGRESS,
                permissionGranted = true,
                prefEnabled = false
            )
        )
        assertTrue(
            BackgroundProgressNotifier.shouldPost(
                BackgroundProgressNotifier.Kind.DONE,
                permissionGranted = true,
                prefEnabled = true
            )
        )
        assertFalse(
            BackgroundProgressNotifier.shouldPost(
                BackgroundProgressNotifier.Kind.DONE,
                permissionGranted = true,
                prefEnabled = false
            )
        )
    }

    @Test
    fun alertAlwaysPostsWhenPermissionIsGrantedRegardlessOfPref() {
        assertTrue(
            BackgroundProgressNotifier.shouldPost(
                BackgroundProgressNotifier.Kind.ALERT,
                permissionGranted = true,
                prefEnabled = true
            )
        )
        assertTrue(
            BackgroundProgressNotifier.shouldPost(
                BackgroundProgressNotifier.Kind.ALERT,
                permissionGranted = true,
                prefEnabled = false
            )
        )
    }
}

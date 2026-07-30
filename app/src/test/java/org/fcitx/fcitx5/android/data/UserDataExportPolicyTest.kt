/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDataExportPolicyTest {
    @Test
    fun excludesClipboardDatabaseAndSidecars() {
        listOf("clbdb", "clbdb-wal", "clbdb-shm", "clbdb-journal").forEach {
            assertFalse(UserDataExportPolicy.shouldIncludeDatabase(it))
        }
    }

    @Test
    fun keepsOtherUserDatabases() {
        assertTrue(UserDataExportPolicy.shouldIncludeDatabase("room.db"))
        assertTrue(UserDataExportPolicy.shouldIncludeDatabase("user-dictionary"))
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class UserDataMigrationPolicyTest {
    @Test
    fun acceptsCurrentLineageAcrossApplicationIdChange() {
        assertTrue(
            UserDataMigrationPolicy.canImport(
                sourcePackage = "kr.example.oldname",
                currentPackage = "kr.example.newname",
                formatVersion = UserDataMigrationPolicy.CURRENT_FORMAT_VERSION,
                lineageId = UserDataMigrationPolicy.LINEAGE_ID
            )
        )
    }

    @Test
    fun acceptsOnlyKnownLegacyPackagesForVersionOne() {
        listOf(
            "org.fcitx.fcitx5.android",
            "org.fcitx.fcitx5.android.debug"
        ).forEach { legacyPackage ->
            assertTrue(
                UserDataMigrationPolicy.canImport(
                    sourcePackage = legacyPackage,
                    currentPackage = "kr.example.product",
                    formatVersion = UserDataMigrationPolicy.LEGACY_FORMAT_VERSION,
                    lineageId = null
                )
            )
        }
        assertFalse(
            UserDataMigrationPolicy.canImport(
                sourcePackage = "com.example.other",
                currentPackage = "kr.example.product",
                formatVersion = UserDataMigrationPolicy.LEGACY_FORMAT_VERSION,
                lineageId = null
            )
        )
    }

    @Test
    fun rejectsUnknownLineageFutureVersionAndInvalidPackage() {
        assertFalse(
            UserDataMigrationPolicy.canImport(
                sourcePackage = "kr.example.oldname",
                currentPackage = "kr.example.newname",
                formatVersion = UserDataMigrationPolicy.CURRENT_FORMAT_VERSION,
                lineageId = "wrong"
            )
        )
        assertFalse(
            UserDataMigrationPolicy.canImport(
                sourcePackage = "kr.example.oldname",
                currentPackage = "kr.example.newname",
                formatVersion = 999,
                lineageId = UserDataMigrationPolicy.LINEAGE_ID
            )
        )
        assertFalse(
            UserDataMigrationPolicy.canImport(
                sourcePackage = "../escape",
                currentPackage = "kr.example.newname",
                formatVersion = UserDataMigrationPolicy.CURRENT_FORMAT_VERSION,
                lineageId = UserDataMigrationPolicy.LINEAGE_ID
            )
        )
    }

    @Test
    fun renamesDefaultPreferencesAndResetsClipboardOptIn() {
        val root = Files.createTempDirectory("user-data-migration").toFile()
        try {
            val source = root.resolve("org.fcitx.fcitx5.android_preferences.xml")
            source.writeText(
                """
                <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
                <map>
                    <boolean name="clipboard_enable" value="true" />
                    <string name="theme">dark</string>
                </map>
                """.trimIndent()
            )

            UserDataMigrationPolicy.prepareSharedPreferences(
                directory = root,
                sourcePackage = "org.fcitx.fcitx5.android",
                currentPackage = "kr.example.product"
            )

            val target = root.resolve("kr.example.product_preferences.xml")
            assertFalse(source.exists())
            assertTrue(target.isFile)
            assertFalse(target.readText().contains("clipboard_enable"))
            assertTrue(target.readText().contains("name=\"theme\""))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsDoctypeInImportedPreferences() {
        assertThrows(IllegalArgumentException::class.java) {
            UserDataMigrationPolicy.sanitizeDefaultPreferences(
                """
                <!DOCTYPE map [<!ENTITY probe "unsafe">]>
                <map><string name="theme">&probe;</string></map>
                """.trimIndent()
            )
        }
    }
}

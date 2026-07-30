/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.data.UserDataManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UserDataMigrationInstrumentedTest {
    @Test
    fun importsLegacyPackagePreferencesWithoutClipboardOptInOrDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataDir = File(context.applicationInfo.dataDir)
        val preferencesDir = dataDir.resolve("shared_prefs").apply { mkdirs() }
        val databasesDir = dataDir.resolve("databases").apply { mkdirs() }
        val targetPreferences =
            preferencesDir.resolve("${BuildConfig.APPLICATION_ID}_preferences.xml")
        val clipboardProbe = databasesDir.resolve(CLIPBOARD_DATABASE_PROBE)
        val originalPreferences = targetPreferences.takeIf(File::isFile)?.readBytes()
        val originalClipboardProbe = clipboardProbe.takeIf(File::isFile)?.readBytes()

        try {
            val archive = legacyArchive()
            val metadata = UserDataManager.import(ByteArrayInputStream(archive)).getOrThrow()

            assertEquals(LEGACY_PACKAGE, metadata.packageName)
            assertEquals(1, metadata.formatVersion)
            assertTrue(targetPreferences.isFile)
            val importedPreferences = targetPreferences.readText()
            assertTrue(importedPreferences.contains("name=\"theme\""))
            assertFalse(importedPreferences.contains("clipboard_enable"))
            assertFalse(clipboardProbe.exists())
        } finally {
            restoreFile(targetPreferences, originalPreferences)
            restoreFile(clipboardProbe, originalClipboardProbe)
        }
    }

    private fun legacyArchive(): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.writeEntry(
                "shared_prefs/${LEGACY_PACKAGE}_preferences.xml",
                """
                <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
                <map>
                    <boolean name="clipboard_enable" value="true" />
                    <string name="theme">migration-probe</string>
                </map>
                """.trimIndent()
            )
            zip.writeEntry(
                "databases/$CLIPBOARD_DATABASE_PROBE",
                "clipboard history must not migrate"
            )
            zip.writeEntry(
                "metadata.json",
                """
                {
                  "packageName": "$LEGACY_PACKAGE",
                  "versionCode": 1,
                  "versionName": "legacy",
                  "exportTime": 0,
                  "formatVersion": 1
                }
                """.trimIndent()
            )
        }
        bytes.toByteArray()
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    private fun restoreFile(file: File, content: ByteArray?) {
        if (content == null) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            file.writeBytes(content)
        }
    }

    private companion object {
        const val LEGACY_PACKAGE = "org.fcitx.fcitx5.android"
        const val CLIPBOARD_DATABASE_PROBE = "clbdb-migration-probe"
    }
}

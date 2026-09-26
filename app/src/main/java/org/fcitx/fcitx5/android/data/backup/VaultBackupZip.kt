/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Packs/unpacks the plaintext ZIP payload that sits inside the encrypted `.saegeulbackup` blob. */
internal object VaultBackupZip {
    const val MANIFEST_PATH = "manifest.json"
    private const val ENTRIES_DIR = "entries"

    fun build(manifestJson: String, entries: Map<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_PATH))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry("$ENTRIES_DIR/$name"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    data class Unpacked(val manifestJson: String, val entries: Map<String, ByteArray>)

    /** Returns null when the ZIP has no `manifest.json` entry (malformed/foreign ZIP). */
    fun read(bytes: ByteArray): Unpacked? {
        val entries = mutableMapOf<String, ByteArray>()
        var manifestJson: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val content = zip.readBytes()
                when {
                    entry.name == MANIFEST_PATH -> manifestJson = String(content, Charsets.UTF_8)
                    entry.name.startsWith("$ENTRIES_DIR/") ->
                        entries[entry.name.removePrefix("$ENTRIES_DIR/")] = content
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val manifest = manifestJson ?: return null
        return Unpacked(manifest, entries)
    }
}

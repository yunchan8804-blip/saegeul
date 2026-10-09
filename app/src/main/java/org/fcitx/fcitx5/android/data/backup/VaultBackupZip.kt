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
    private const val CHUNK_BYTES = 64 * 1024

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

    /**
     * Returns null when the ZIP has no `manifest.json` entry (malformed/foreign ZIP), or when the
     * entries decompress to more than [maxTotalBytes] in total. Sizes recorded in the ZIP headers
     * are never trusted; the limit is enforced on the bytes actually inflated.
     */
    fun read(bytes: ByteArray, maxTotalBytes: Long = VaultBackup.MAX_PLAINTEXT_BYTES): Unpacked? {
        val entries = mutableMapOf<String, ByteArray>()
        var manifestJson: String? = null
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(CHUNK_BYTES)
                while (true) {
                    val read = zip.read(chunk)
                    if (read < 0) break
                    totalBytes += read
                    if (totalBytes > maxTotalBytes) return null
                    buffer.write(chunk, 0, read)
                }
                val content = buffer.toByteArray()
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

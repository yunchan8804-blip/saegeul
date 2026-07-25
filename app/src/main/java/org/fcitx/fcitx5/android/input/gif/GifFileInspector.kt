/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

object GifFileInspector {

    fun isAnimated(bytes: ByteArray): Boolean {
        if (bytes.size < 13) return false
        val header = bytes.copyOfRange(0, 6).decodeToString()
        if (header != "GIF87a" && header != "GIF89a") return false
        var offset = 13
        val logicalPacked = bytes[10].toInt() and 0xff
        if (logicalPacked and 0x80 != 0) {
            offset += 3 * (1 shl ((logicalPacked and 0x07) + 1))
        }
        var frames = 0
        while (offset < bytes.size) {
            when (bytes[offset].toInt() and 0xff) {
                0x2c -> {
                    if (offset + 10 > bytes.size) return false
                    frames++
                    if (frames >= 2) return true
                    val packed = bytes[offset + 9].toInt() and 0xff
                    offset += 10
                    if (packed and 0x80 != 0) {
                        offset += 3 * (1 shl ((packed and 0x07) + 1))
                    }
                    if (offset >= bytes.size) return false
                    offset++ // LZW minimum code size
                    offset = skipSubBlocks(bytes, offset) ?: return false
                }
                0x21 -> {
                    if (offset + 2 > bytes.size) return false
                    offset += 2 // extension introducer + label
                    offset = skipSubBlocks(bytes, offset) ?: return false
                }
                0x3b -> return false
                else -> return false
            }
        }
        return false
    }

    private fun skipSubBlocks(bytes: ByteArray, start: Int): Int? {
        var offset = start
        while (offset < bytes.size) {
            val blockSize = bytes[offset].toInt() and 0xff
            offset++
            if (blockSize == 0) return offset
            if (offset + blockSize > bytes.size) return null
            offset += blockSize
        }
        return null
    }
}

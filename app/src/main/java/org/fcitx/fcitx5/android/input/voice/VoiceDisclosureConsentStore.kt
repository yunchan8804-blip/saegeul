/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.util.AtomicFile
import java.io.File

internal enum class VoiceDisclosureKind(val markerName: String) {
    Microphone("microphone"),
    MeetingAudioFile("meeting-audio-file")
}

internal object VoiceDisclosurePolicy {
    const val CURRENT_VERSION = 1

    fun isCurrent(value: String?): Boolean =
        value?.trim()?.toIntOrNull() == CURRENT_VERSION
}

/**
 * Versioned, no-backup proof that a user accepted a just-in-time voice disclosure.
 *
 * Microphone recording and meeting-file upload are intentionally separate: accepting one must not
 * silently authorize the other.
 */
internal class VoiceDisclosureConsentStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "privacy/voice-disclosures")

    fun hasAccepted(kind: VoiceDisclosureKind): Boolean =
        runCatching {
            marker(kind).takeIf(File::isFile)?.readText()
        }.getOrNull().let(VoiceDisclosurePolicy::isCurrent)

    fun accept(kind: VoiceDisclosureKind): Boolean = runCatching {
        check(root.isDirectory || root.mkdirs()) { "Could not create voice disclosure directory" }
        val atomic = AtomicFile(marker(kind))
        val output = atomic.startWrite()
        try {
            output.write(VoiceDisclosurePolicy.CURRENT_VERSION.toString().toByteArray())
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (exception: Exception) {
            atomic.failWrite(output)
            throw exception
        }
        true
    }.getOrDefault(false)

    private fun marker(kind: VoiceDisclosureKind): File =
        File(root, "${kind.markerName}.version")
}

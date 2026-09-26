/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.backup

import kotlinx.serialization.Serializable

/**
 * On-disk schema for `manifest.json` inside the backup ZIP. Distinct from the public
 * [BackupSummary]/[BackupEntry] types so the wire format can evolve independently of the API
 * surface other code builds against.
 */
@Serializable
internal data class ManifestEntryJson(
    val name: String,
    val kind: String,
    val sha256: String
)

@Serializable
internal data class ManifestJson(
    val formatVersion: Int,
    val createdAtMs: Long,
    val appVersionName: String,
    val personalSentenceCount: Int,
    val publicMaterialCount: Int,
    val entries: List<ManifestEntryJson>
)

internal fun ManifestJson.toSummary(skippedDerivedEntries: List<String>): BackupSummary = BackupSummary(
    createdAtMs = createdAtMs,
    appVersionName = appVersionName,
    formatVersion = formatVersion,
    personalSentenceCount = personalSentenceCount,
    publicMaterialCount = publicMaterialCount,
    entries = entries.map { declared ->
        BackupEntry(declared.name, runCatching { BackupEntryKind.valueOf(declared.kind) }.getOrDefault(BackupEntryKind.DERIVED))
    },
    skippedDerivedEntries = skippedDerivedEntries
)

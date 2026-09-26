/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.data.backup

import org.fcitx.fcitx5.android.data.personaldictionary.PersonalDictionaryStore
import java.io.File

internal enum class BackupTargetLocation { FILES_DIR, NO_BACKUP_FILES_DIR }

/**
 * How a target's bytes are read/written.
 * - [VAULT_FILE]: the app's usual `VaultFile` envelope (magic + cipher id + [org.fcitx.fcitx5.android.input.ai.vault.VaultCipher]-encrypted blob).
 * - [RAW_FILE]: plain bytes with no vault envelope of their own (currently only the personal
 *   dictionary, which already isn't `VaultCipher`-encrypted at rest) — the whole `.saegeulbackup`
 *   file is encrypted anyway, so this is still protected end to end.
 */
internal enum class BackupTargetFormat { VAULT_FILE, RAW_FILE }

/** One personalization vault file this app knows how to back up and restore. */
internal data class BackupTarget(
    val name: String,
    val kind: BackupEntryKind,
    val location: BackupTargetLocation,
    val format: BackupTargetFormat = BackupTargetFormat.VAULT_FILE,
    /** Path under [location]'s directory; only differs from [name] when it has subdirectories. */
    val relativePath: String = name
) {
    fun resolve(filesDir: File, noBackupFilesDir: File): File = File(
        when (location) {
            BackupTargetLocation.FILES_DIR -> filesDir
            BackupTargetLocation.NO_BACKUP_FILES_DIR -> noBackupFilesDir
        },
        relativePath
    )
}

/**
 * The fixed list of stores included in a vault backup.
 *
 * Deliberately excluded (see the vault backup task's confirmed design):
 * - Point ledger / level-reward bookkeeping (`points/ledger.jsonl`, `point_meta` prefs) and
 *   everything ad-related — a backup must never be a way to farm points.
 * - The on-device Gemma model files and personal-graph enrichment *staging* buffer
 *   (`PersonalGraphEnrichmentStagingStore`) — in-progress work, not a finished corpus/derived
 *   artifact, owned by the on-device generation feature rather than this task, and deleted (not
 *   restored) on every import commit so a stale in-progress batch never mixes with a freshly
 *   imported corpus (see [VaultBackup]'s import commit step).
 * - `vault_habit` prefs (streak/freezes) — gamification state, not corpus or derived language
 *   data, and adjacent to the same anti-exploit concern as level rewards.
 */
internal object VaultBackupTargets {
    val ALL: List<BackupTarget> = listOf(
        // Corpus: raw text the user actually wrote/collected. Restoring these lets everything
        // derived below be rebuilt from scratch even if it doesn't come across.
        BackupTarget("personal_rag.json", BackupEntryKind.CORPUS, BackupTargetLocation.FILES_DIR),
        BackupTarget("personalized_sentences.json", BackupEntryKind.CORPUS, BackupTargetLocation.FILES_DIR),
        BackupTarget("typing_dna_pending.json", BackupEntryKind.CORPUS, BackupTargetLocation.FILES_DIR),
        BackupTarget(
            name = "personal_dictionary_words.txt",
            kind = BackupEntryKind.CORPUS,
            location = BackupTargetLocation.NO_BACKUP_FILES_DIR,
            format = BackupTargetFormat.RAW_FILE,
            relativePath = PersonalDictionaryStore.RELATIVE_PATH
        ),

        // Derived: models/indexes computed from the corpus above. Safe to drop and relearn.
        BackupTarget("typing_dna.json", BackupEntryKind.DERIVED, BackupTargetLocation.FILES_DIR),
        BackupTarget("personal_ngram.json", BackupEntryKind.DERIVED, BackupTargetLocation.FILES_DIR),
        BackupTarget("personal_graph.json", BackupEntryKind.DERIVED, BackupTargetLocation.FILES_DIR),
        BackupTarget("prediction_metrics.json", BackupEntryKind.DERIVED, BackupTargetLocation.FILES_DIR),
        BackupTarget("personal_corrections.json", BackupEntryKind.DERIVED, BackupTargetLocation.FILES_DIR),

        // Public corpus: not personal data, but expensive (hours) to regenerate on-device.
        BackupTarget("gemma_materials.json", BackupEntryKind.PUBLIC_CORPUS, BackupTargetLocation.NO_BACKUP_FILES_DIR)
    )
}

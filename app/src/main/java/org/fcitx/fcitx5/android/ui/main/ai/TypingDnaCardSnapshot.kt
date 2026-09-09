/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.ai.TypingDnaStats

internal data class TypingDnaCardSnapshot(
    val stats: TypingDnaStats,
    val ngramUnigrams: Int,
    val pendingSentences: Int,
    val isHardwareBacked: Boolean
)

internal object TypingDnaCardSnapshotReader {
    fun read(): TypingDnaCardSnapshot {
        val app = FcitxApplication.getInstance()
        return TypingDnaCardSnapshot(
            stats = app.typingDnaRepository.getStats(),
            ngramUnigrams = app.personalNgramModel.stats().unigrams,
            pendingSentences = app.typingDnaVault.totalBufferedCount(),
            isHardwareBacked = app.vaultCipher.isHardwareBacked
        )
    }
}

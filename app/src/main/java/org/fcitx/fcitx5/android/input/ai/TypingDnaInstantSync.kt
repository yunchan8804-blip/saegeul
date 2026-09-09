/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import java.io.File

/**
 * Compiles staged Typing DNA into [TypingDnaRepository] without requiring a live IME instance.
 * The dashboard uses this when [org.fcitx.fcitx5.android.input.FcitxInputMethodService.activeInstance]
 * is null after the keyboard has been unbound.
 */
class TypingDnaInstantSync(
    private val vault: TypingDnaVault,
    private val repository: TypingDnaRepository,
    private val profiler: TypingDnaProfiler = TypingDnaProfiler(),
    private val compiler: TypingDnaCompiler
) {
    fun syncNow(category: String? = null): TypingDnaStats {
        vault.processPending(category) { pendingCategory, sentences ->
            val persona = profiler.profileOnDevice(pendingCategory, sentences)
            compiler.compilePersona(
                persona,
                persist = true,
                analyzedSentenceCount = sentences.size
            )
        }
        return repository.getStats()
    }

    companion object {
        fun persistOnly(
            vault: TypingDnaVault,
            repository: TypingDnaRepository,
            sentenceStoreFile: File? = null,
            cipher: VaultCipher = PlainVaultCipher
        ): TypingDnaStats {
            val sentenceStore = if (sentenceStoreFile != null) {
                PersonalizedSentenceStore(storageFile = sentenceStoreFile, cipher = cipher).apply { load() }
            } else {
                PersonalizedSentenceStore()
            }
            val compiler = TypingDnaCompiler(
                collocationModel = KoreanCollocationModel(),
                sentenceStore = sentenceStore,
                repository = repository
            )
            val stats = TypingDnaInstantSync(
                vault = vault,
                repository = repository,
                compiler = compiler
            ).syncNow()
            if (sentenceStoreFile != null) {
                sentenceStore.save()
            }
            return stats
        }
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

import android.os.SystemClock
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhraseVault
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhraseUnlockState

object SensitivePhraseSession {
    private val state = SensitivePhraseUnlockState(elapsedRealtime = SystemClock::elapsedRealtime)
    private var vault: SensitivePhraseVault? = null

    @Synchronized
    fun unlockFor(packageName: String, vault: SensitivePhraseVault) {
        this.vault = vault
        state.unlockFor(packageName)
    }

    @Synchronized
    fun vaultFor(packageName: String): SensitivePhraseVault? {
        if (!state.isUnlockedFor(packageName)) vault = null
        return vault
    }

    @Synchronized
    fun onEditorPackageChanged(packageName: String) {
        if (!state.isUnlockedFor(packageName)) vault = null
        state.onEditorPackageChanged(packageName)
    }

    @Synchronized
    fun lock() {
        vault = null
        state.lock()
    }
}

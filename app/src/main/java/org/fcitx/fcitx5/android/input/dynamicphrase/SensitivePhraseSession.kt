/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhraseVault
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhraseUnlockState
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DEFAULT_UNLOCK_TTL_MILLIS

object SensitivePhraseSession {
    private val state = SensitivePhraseUnlockState(elapsedRealtime = SystemClock::elapsedRealtime)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val invalidationListeners = linkedSetOf<() -> Unit>()
    private val expiry = Runnable { lock() }
    private var target: DynamicPhraseEditorTarget? = null
    private var vault: SensitivePhraseVault? = null

    @Synchronized
    fun unlockFor(target: DynamicPhraseEditorTarget, vault: SensitivePhraseVault) {
        this.target = target
        this.vault = vault
        state.unlockFor(target.packageName)
        mainHandler.removeCallbacks(expiry)
        mainHandler.postDelayed(expiry, DEFAULT_UNLOCK_TTL_MILLIS)
    }

    @Synchronized
    fun vaultFor(target: DynamicPhraseEditorTarget): SensitivePhraseVault? {
        if (this.target != target || !state.isUnlockedFor(target.packageName)) {
            lockInternal()
        }
        return vault
    }

    @Synchronized
    fun remainingMillisFor(target: DynamicPhraseEditorTarget): Long? {
        if (this.target != target) {
            lockInternal()
            return null
        }
        return state.remainingMillisFor(target.packageName) ?: run {
            lockInternal()
            null
        }
    }

    @Synchronized
    fun onEditorChanged(target: DynamicPhraseEditorTarget) {
        val current = this.target ?: return
        if (current != target || !state.isUnlockedFor(target.packageName)) {
            lockInternal()
        }
    }

    @Synchronized
    fun addInvalidationListener(listener: () -> Unit) {
        invalidationListeners += listener
    }

    @Synchronized
    fun removeInvalidationListener(listener: () -> Unit) {
        invalidationListeners -= listener
    }

    @Synchronized
    fun lock() {
        lockInternal()
    }

    private fun lockInternal() {
        val hadSensitiveState = vault != null || target != null
        vault = null
        target = null
        state.lock()
        mainHandler.removeCallbacks(expiry)
        if (hadSensitiveState) {
            invalidationListeners.toList().forEach { listener ->
                mainHandler.post(listener)
            }
        }
    }
}

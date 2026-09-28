/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.effects

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Battery-saver tracking shared by [RgbChromaEffectView] and [ParticleTouchOverlayView]. The
 * owning view calls [attach] and [detach] from its window attach callbacks; power-save
 * broadcasts in between update the mode and then invoke [onPowerSaveModeChanged].
 */
internal class EffectPowerGate(
    private val context: Context,
    private val onPowerSaveModeChanged: () -> Unit
) {
    private var powerSaveMode = false
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receivedContext: Context, intent: Intent) {
            powerSaveMode = currentPowerSaveMode()
            onPowerSaveModeChanged()
        }
    }

    fun canAnimate(): Boolean =
        EffectGate.shouldAnimate(powerSaveMode, ValueAnimator.areAnimatorsEnabled())

    /** Registers for power-save broadcasts, then reads the current mode. */
    fun attach() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        powerSaveMode = currentPowerSaveMode()
    }

    fun detach() {
        if (!receiverRegistered) return
        context.unregisterReceiver(receiver)
        receiverRegistered = false
    }

    private fun currentPowerSaveMode(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode ?: false
}

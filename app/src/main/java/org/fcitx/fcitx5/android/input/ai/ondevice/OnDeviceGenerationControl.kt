/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

object OnDeviceGenerationControl {
    private val lock = Any()
    private var keyboardActive = false
    private var cancelCallback: (() -> Unit)? = null

    fun begin(cancel: () -> Unit): Boolean = synchronized(lock) {
        if (keyboardActive || cancelCallback != null) return false
        cancelCallback = cancel
        true
    }

    fun end() {
        synchronized(lock) {
            cancelCallback = null
        }
    }

    fun onKeyboardVisibilityChanged(active: Boolean) {
        val cancel = synchronized(lock) {
            keyboardActive = active
            if (active) cancelCallback else null
        }
        cancel?.invoke()
    }
}

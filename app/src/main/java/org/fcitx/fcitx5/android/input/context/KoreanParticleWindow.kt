/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.context

import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

class KoreanParticleWindow : InputWindow.ExtendedInputWindow<KoreanParticleWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private lateinit var ui: KoreanParticleUi
    private var snapshot: KoreanParticleSnapshot? = null
    private var commitGate = KoreanParticleCommitGate()

    override val title: String by lazy { context.getString(R.string.korean_particle_title) }
    override val showTitle: Boolean = false

    override fun onCreateView(): View {
        ui = KoreanParticleUi(context, theme).apply { onSuggestion = ::insert }
        return ui.root
    }

    override fun onAttached() {
        commitGate = KoreanParticleCommitGate()
        snapshot = service.captureKoreanParticleSnapshot()
        val captured = snapshot
        if (captured == null) {
            ui.showMessage(context.getString(R.string.korean_particle_unavailable))
        } else {
            ui.showSuggestions(captured.suggestions)
        }
    }

    override fun onDetached() {
        snapshot = null
        commitGate = KoreanParticleCommitGate()
    }

    private fun insert(suggestion: KoreanParticleSuggestion) {
        val captured = snapshot ?: return
        if (suggestion !in captured.suggestions || !commitGate.claim()) return
        ui.setLocked(true)
        if (service.commitKoreanParticle(captured, suggestion.text)) {
            windowManager.attachWindow(KeyboardWindow)
        } else {
            ui.showMessage(context.getString(R.string.korean_particle_editor_changed))
        }
    }
}

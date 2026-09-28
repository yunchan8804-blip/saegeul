/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import org.fcitx.fcitx5.android.input.EditorIdentity

class OnDeviceAutomaticEditorSnapshot(
    val session: OnDeviceSuggestionSession.Snapshot,
    val inputType: Int,
    val imeAction: Int,
    val physicalExtractedText: String,
    val physicalSelectionStart: Int,
    val physicalSelectionEnd: Int,
    val composingStart: Int,
    val composingEnd: Int,
    val composingText: String,
    val bufferedHangul: Boolean,
    val rawBufferedPrefix: String,
    val rawEnginePreedit: String
) {
    /**
     * The (package, field, input type) identity this snapshot was captured against.
     *
     * [session]'s scope already carries package and field id; only [inputType] lives on this
     * snapshot directly, so this stays a computed view rather than a new stored field.
     */
    val identity: EditorIdentity
        get() = EditorIdentity(session.scope.packageName, session.scope.fieldId, inputType)

    override fun toString(): String =
        "OnDeviceAutomaticEditorSnapshot(session=$session, inputType=$inputType, imeAction=$imeAction, " +
            "physicalTextLength=${physicalExtractedText.length}, " +
            "physicalSelection=[$physicalSelectionStart,$physicalSelectionEnd], " +
            "composing=[$composingStart,$composingEnd], composingTextLength=${composingText.length}, " +
            "bufferedHangul=$bufferedHangul, rawBufferedPrefixLength=${rawBufferedPrefix.length}, " +
            "rawEnginePreeditLength=${rawEnginePreedit.length})"
}

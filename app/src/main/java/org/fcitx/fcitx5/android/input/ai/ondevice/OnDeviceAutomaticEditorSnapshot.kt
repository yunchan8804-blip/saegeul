/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

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
    override fun toString(): String =
        "OnDeviceAutomaticEditorSnapshot(session=$session, inputType=$inputType, imeAction=$imeAction, " +
            "physicalTextLength=${physicalExtractedText.length}, " +
            "physicalSelection=[$physicalSelectionStart,$physicalSelectionEnd], " +
            "composing=[$composingStart,$composingEnd], composingTextLength=${composingText.length}, " +
            "bufferedHangul=$bufferedHangul, rawBufferedPrefixLength=${rawBufferedPrefix.length}, " +
            "rawEnginePreeditLength=${rawEnginePreedit.length})"
}

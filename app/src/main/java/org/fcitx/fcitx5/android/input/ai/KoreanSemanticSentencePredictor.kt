/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Tone lookup handed to [AiContextualPredictor]; the classification itself lives in
 * [KoreanToneClassifier].
 */
class KoreanSemanticSentencePredictor {

    fun inferTone(context: String): KoreanTone = KoreanToneClassifier.infer(context, null)
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.fcitx.fcitx5.android.R

/** Receives only explicit text/plain Sharesheet actions, moves text to process memory, and exits. */
class AiShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedText = runCatching {
            intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)
        }.getOrNull()
        val accepted = AiReplyIntake.receiveSharedText(
            action = intent?.action,
            mimeType = intent?.type,
            text = sharedText
        )
        Toast.makeText(
            this,
            if (accepted) R.string.ai_share_received else R.string.ai_share_rejected,
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}

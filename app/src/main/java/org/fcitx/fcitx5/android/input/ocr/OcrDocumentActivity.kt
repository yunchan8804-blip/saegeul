/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.atomic.AtomicLong

/** Process-memory-only bridge for a user-confirmed, one-shot image document selection. */
object OcrDocumentCoordinator {
    private data class Pending(val id: Long, val callback: (Uri?) -> Unit)

    private val nextId = AtomicLong(1L)
    private var pending: Pending? = null

    @Synchronized
    fun request(context: Context, callback: (Uri?) -> Unit): Long? {
        val id = nextId.getAndIncrement()
        pending = Pending(id, callback)
        val launched = runCatching {
            context.startActivity(
                Intent(context, OcrDocumentActivity::class.java)
                    .putExtra(OcrDocumentActivity.EXTRA_REQUEST_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }.isSuccess
        if (!launched) {
            pending = null
            return null
        }
        return id
    }

    @Synchronized
    fun cancel(id: Long) {
        if (pending?.id == id) pending = null
    }

    @Synchronized
    internal fun deliver(id: Long, uri: Uri?) {
        val request = pending?.takeIf { it.id == id } ?: return
        pending = null
        request.callback(uri?.takeIf { it.scheme == "content" })
    }
}

/** Internal translucent boundary that opens the system picker and never persists URI access. */
class OcrDocumentActivity : Activity() {
    private val requestId: Long
        get() = intent?.getLongExtra(EXTRA_REQUEST_ID, INVALID_REQUEST_ID) ?: INVALID_REQUEST_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requestId == INVALID_REQUEST_ID) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*")
                    .putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("image/jpeg", "image/png", "image/webp")
                    ),
                REQUEST_IMAGE_DOCUMENT
            )
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE_DOCUMENT) return
        OcrDocumentCoordinator.deliver(
            requestId,
            data?.data?.takeIf { resultCode == RESULT_OK }
        )
        finishBoundary()
    }

    private fun finishBoundary() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        internal const val EXTRA_REQUEST_ID = "ocr_document_request_id"
        private const val INVALID_REQUEST_ID = -1L
        private const val REQUEST_IMAGE_DOCUMENT = 1
    }
}

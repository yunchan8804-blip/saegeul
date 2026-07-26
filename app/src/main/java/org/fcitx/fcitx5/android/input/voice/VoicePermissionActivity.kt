/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong

/** Process-memory callback bridge for the IME service, which cannot request runtime permissions. */
object VoicePermissionCoordinator {
    private data class Pending(val id: Long, val callback: (Boolean) -> Unit)

    private val nextId = AtomicLong(1L)
    private var pending: Pending? = null

    @Synchronized
    fun request(context: Context, callback: (Boolean) -> Unit): Long? {
        val id = nextId.getAndIncrement()
        pending = Pending(id, callback)
        val launched = runCatching {
            context.startActivity(
                Intent(context, VoicePermissionActivity::class.java)
                    .putExtra(VoicePermissionActivity.EXTRA_REQUEST_ID, id)
                    .putExtra(VoicePermissionActivity.EXTRA_MODE, VoicePermissionActivity.MODE_PERMISSION)
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
    internal fun deliver(id: Long, granted: Boolean) {
        val request = pending?.takeIf { it.id == id } ?: return
        pending = null
        request.callback(granted)
    }
}

/** Process-memory-only result bridge for a user-confirmed ACTION_OPEN_DOCUMENT selection. */
object VoiceAudioDocumentCoordinator {
    private data class Pending(val id: Long, val callback: (Uri?) -> Unit)

    private val nextId = AtomicLong(1L)
    private var pending: Pending? = null

    @Synchronized
    fun request(context: Context, callback: (Uri?) -> Unit): Long? {
        val id = nextId.getAndIncrement()
        pending = Pending(id, callback)
        val launched = runCatching {
            context.startActivity(
                Intent(context, VoicePermissionActivity::class.java)
                    .putExtra(VoicePermissionActivity.EXTRA_REQUEST_ID, id)
                    .putExtra(VoicePermissionActivity.EXTRA_MODE, VoicePermissionActivity.MODE_AUDIO_DOCUMENT)
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

/** Internal, dialog-themed boundary for permission and one-shot document selection. */
class VoicePermissionActivity : Activity() {
    private val requestId: Long
        get() = intent?.getLongExtra(EXTRA_REQUEST_ID, INVALID_REQUEST_ID) ?: INVALID_REQUEST_ID

    private val mode: String
        get() = intent?.getStringExtra(EXTRA_MODE) ?: MODE_PERMISSION

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requestId == INVALID_REQUEST_ID) {
            finish()
            return
        }
        when (mode) {
            MODE_PERMISSION -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    completePermission(granted = true)
                } else if (savedInstanceState == null) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                }
            }
            MODE_AUDIO_DOCUMENT -> if (savedInstanceState == null) {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("audio/*"),
                    REQUEST_AUDIO_DOCUMENT
                )
            }
            else -> finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        completePermission(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO_DOCUMENT) return
        VoiceAudioDocumentCoordinator.deliver(
            requestId,
            data?.data?.takeIf { resultCode == RESULT_OK }
        )
        finishBoundary()
    }

    private fun completePermission(granted: Boolean) {
        VoicePermissionCoordinator.deliver(requestId, granted)
        finishBoundary()
    }

    private fun finishBoundary() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        internal const val EXTRA_REQUEST_ID = "voice_permission_request_id"
        internal const val EXTRA_MODE = "voice_activity_mode"
        internal const val MODE_PERMISSION = "permission"
        internal const val MODE_AUDIO_DOCUMENT = "audio_document"
        private const val INVALID_REQUEST_ID = -1L
        private const val REQUEST_RECORD_AUDIO = 1
        private const val REQUEST_AUDIO_DOCUMENT = 2
    }
}

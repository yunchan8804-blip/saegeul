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

/** Internal, dialog-themed permission boundary used only for RECORD_AUDIO. */
class VoicePermissionActivity : Activity() {
    private val requestId: Long
        get() = intent?.getLongExtra(EXTRA_REQUEST_ID, INVALID_REQUEST_ID) ?: INVALID_REQUEST_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requestId == INVALID_REQUEST_ID) {
            finish()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            complete(granted = true)
        } else if (savedInstanceState == null) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        complete(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    private fun complete(granted: Boolean) {
        VoicePermissionCoordinator.deliver(requestId, granted)
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        internal const val EXTRA_REQUEST_ID = "voice_permission_request_id"
        private const val INVALID_REQUEST_ID = -1L
        private const val REQUEST_RECORD_AUDIO = 1
    }
}

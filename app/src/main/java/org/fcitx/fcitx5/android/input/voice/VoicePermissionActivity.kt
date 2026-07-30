/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import java.util.concurrent.atomic.AtomicLong

/** Process-memory callback bridge for the IME service, which cannot request runtime permissions. */
object VoicePermissionCoordinator {
    private val nextId = AtomicLong(1L)
    private val resumeQueue = VoicePermissionResumeQueue()

    fun request(context: Context, target: VoiceEditorTarget): Long? {
        val id = nextId.getAndIncrement()
        resumeQueue.begin(id, target)
        val launched = runCatching {
            context.startActivity(
                Intent(context, VoicePermissionActivity::class.java)
                    .putExtra(VoicePermissionActivity.EXTRA_REQUEST_ID, id)
                    .putExtra(VoicePermissionActivity.EXTRA_MODE, VoicePermissionActivity.MODE_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }.isSuccess
        if (!launched) {
            resumeQueue.cancel(id)
            return null
        }
        return id
    }

    internal fun deliver(id: Long, granted: Boolean) {
        resumeQueue.complete(id, granted)
    }

    fun consumeForEditor(
        packageName: String?,
        fieldId: Int,
        inputType: Int
    ): VoicePermissionResumeResult? =
        resumeQueue.consumeForEditor(packageName, fieldId, inputType)
}

/** Process-memory-only result bridge for a user-confirmed ACTION_OPEN_DOCUMENT selection. */
object VoiceAudioDocumentCoordinator {
    private val nextId = AtomicLong(1L)
    private val resumeQueue = VoiceAudioDocumentResumeQueue()

    fun request(context: Context, target: VoiceEditorTarget): Long? {
        val id = nextId.getAndIncrement()
        resumeQueue.begin(id, target)
        val launched = runCatching {
            context.startActivity(
                Intent(context, VoicePermissionActivity::class.java)
                    .putExtra(VoicePermissionActivity.EXTRA_REQUEST_ID, id)
                    .putExtra(VoicePermissionActivity.EXTRA_MODE, VoicePermissionActivity.MODE_AUDIO_DOCUMENT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }.isSuccess
        if (!launched) {
            resumeQueue.cancel(id)
            return null
        }
        return id
    }

    fun cancel(id: Long) {
        resumeQueue.cancel(id)
    }

    internal fun deliver(id: Long, uri: Uri?) {
        resumeQueue.complete(
            id,
            uri?.takeIf { it.scheme == "content" }?.toString()
        )
    }

    fun consumeForEditor(
        packageName: String?,
        fieldId: Int,
        inputType: Int
    ): VoiceAudioDocumentResumeResult? =
        resumeQueue.consumeForEditor(packageName, fieldId, inputType)
}

/** Internal, dialog-themed boundary for permission and one-shot document selection. */
class VoicePermissionActivity : Activity() {
    private val disclosureStore by lazy { VoiceDisclosureConsentStore(this) }
    private var waitingForExternalResult = false
    private var completed = false

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
        waitingForExternalResult =
            savedInstanceState?.getBoolean(STATE_WAITING_FOR_EXTERNAL_RESULT) == true
        if (waitingForExternalResult) return
        when (mode) {
            MODE_PERMISSION -> beginMicrophoneFlow()
            MODE_AUDIO_DOCUMENT -> beginAudioDocumentFlow()
            else -> finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_WAITING_FOR_EXTERNAL_RESULT, waitingForExternalResult)
        super.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        waitingForExternalResult = false
        completePermission(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO_DOCUMENT) return
        waitingForExternalResult = false
        VoiceAudioDocumentCoordinator.deliver(
            requestId,
            data?.data?.takeIf { resultCode == RESULT_OK }
        )
        completed = true
        finishBoundary()
    }

    private fun beginMicrophoneFlow() {
        if (!disclosureStore.hasAccepted(VoiceDisclosureKind.Microphone)) {
            showDisclosure(
                kind = VoiceDisclosureKind.Microphone,
                title = R.string.voice_microphone_disclosure_title,
                message = R.string.voice_microphone_disclosure_message,
                positive = R.string.voice_microphone_disclosure_continue,
                onDeclined = { completePermission(granted = false) },
                onAccepted = ::continueMicrophoneFlow
            )
            return
        }
        continueMicrophoneFlow()
    }

    private fun continueMicrophoneFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            completePermission(granted = true)
            return
        }
        waitingForExternalResult = true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    private fun beginAudioDocumentFlow() {
        if (!disclosureStore.hasAccepted(VoiceDisclosureKind.MeetingAudioFile)) {
            showDisclosure(
                kind = VoiceDisclosureKind.MeetingAudioFile,
                title = R.string.voice_meeting_disclosure_title,
                message = R.string.voice_meeting_disclosure_message,
                positive = R.string.voice_meeting_disclosure_continue,
                onDeclined = {
                    VoiceAudioDocumentCoordinator.deliver(requestId, null)
                    completed = true
                    finishBoundary()
                },
                onAccepted = ::continueAudioDocumentFlow
            )
            return
        }
        continueAudioDocumentFlow()
    }

    private fun continueAudioDocumentFlow() {
        waitingForExternalResult = true
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*"),
            REQUEST_AUDIO_DOCUMENT
        )
    }

    private fun showDisclosure(
        kind: VoiceDisclosureKind,
        title: Int,
        message: Int,
        positive: Int,
        onDeclined: () -> Unit,
        onAccepted: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ ->
                if (disclosureStore.accept(kind)) onAccepted() else onDeclined()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDeclined() }
            .setCancelable(false)
            .show()
    }

    private fun completePermission(granted: Boolean) {
        if (completed) return
        completed = true
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
        private const val STATE_WAITING_FOR_EXTERNAL_RESULT = "waiting_for_external_result"
    }
}

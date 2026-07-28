/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DynamicPhraseProfileStore
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.SensitivePhraseVault
import org.fcitx.fcitx5.android.data.quickphrase.dynamic.DEFAULT_UNLOCK_TTL_MILLIS
import java.util.concurrent.atomic.AtomicLong

/** Keeps the CryptoObject and decrypted vault in process memory for one editor-bound round trip. */
internal object SensitivePhraseAuthCoordinator {
    private val nextId = AtomicLong(1L)
    private val resumeQueue =
        SensitivePhraseAuthResumeQueue<DynamicPhraseProfileStore.VaultUnlockRequest>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var expiryId: Long? = null
    private var expiry: Runnable? = null

    fun request(
        context: Context,
        target: DynamicPhraseEditorTarget,
        request: DynamicPhraseProfileStore.VaultUnlockRequest
    ): Long? {
        val id = nextId.getAndIncrement()
        resumeQueue.begin(id, target, request)
        scheduleExpiry(id)
        val launched = runCatching {
            context.startActivity(
                Intent(context, SensitivePhraseAuthActivity::class.java)
                    .putExtra(SensitivePhraseAuthActivity.EXTRA_REQUEST_ID, id)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
            )
        }.isSuccess
        if (!launched) {
            resumeQueue.cancel(id)
            clearExpiry(id)
            return null
        }
        return id
    }

    internal fun claim(id: Long): DynamicPhraseProfileStore.VaultUnlockRequest? =
        resumeQueue.claim(id)

    internal fun deliver(id: Long, vault: SensitivePhraseVault?): Boolean =
        resumeQueue.complete(id, vault)

    internal fun cancel(id: Long) {
        resumeQueue.cancel(id)
        clearExpiry(id)
    }

    fun consumeForEditor(
        packageName: String?,
        fieldId: Int,
        inputType: Int
    ): SensitivePhraseAuthResumeResult? {
        val result = resumeQueue.consumeForEditor(packageName, fieldId, inputType)
        // Keep the timer armed while an authentication activity still owns a pending request.
        // A null result can mean exactly that state, rather than a completed handoff.
        if (result != null) clearExpiry()
        return result
    }

    private fun scheduleExpiry(id: Long) {
        clearExpiry()
        expiryId = id
        expiry = Runnable { cancel(id) }.also {
            mainHandler.postDelayed(it, DEFAULT_UNLOCK_TTL_MILLIS)
        }
    }

    private fun clearExpiry(id: Long? = null) {
        if (id != null && expiryId != id) return
        expiry?.let(mainHandler::removeCallbacks)
        expiry = null
        expiryId = null
    }
}

/** Activity-owned host for BiometricPrompt; an IME window cannot safely own this lifecycle. */
class SensitivePhraseAuthActivity : Activity() {
    private val requestId: Long
        get() = intent?.getLongExtra(EXTRA_REQUEST_ID, INVALID_REQUEST_ID) ?: INVALID_REQUEST_ID

    private var cancellation: CancellationSignal? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val expiry = Runnable {
        cancelPendingAuthentication()
        finishBoundary()
    }
    private var completed = false
    private var authenticationStarted = false
    private var unlockRequest: DynamicPhraseProfileStore.VaultUnlockRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        if (requestId == INVALID_REQUEST_ID || savedInstanceState != null) {
            failClosed()
            return
        }
        unlockRequest = SensitivePhraseAuthCoordinator.claim(requestId)
        if (unlockRequest == null) {
            failClosed()
        } else {
            mainHandler.postDelayed(expiry, DEFAULT_UNLOCK_TTL_MILLIS)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || authenticationStarted || completed || isFinishing) return
        val request = unlockRequest ?: return
        authenticationStarted = true
        cancellation = SensitivePhraseAuthenticator.authenticate(
            context = this,
            cipher = request.cipher,
            title = getString(R.string.secret_vault_unlock),
            subtitle = getString(R.string.secret_vault_unlock_summary),
            onSuccess = { authenticatedCipher ->
                if (!completed) {
                    val vault = runCatching {
                        DynamicPhraseProfileStore(this)
                            .finishVaultUnlock(request, authenticatedCipher)
                    }.getOrNull()
                    finishWith(vault)
                }
            },
            onError = { finishWith(null) }
        )
    }

    override fun onDestroy() {
        if (isFinishing && !completed) cancelPendingAuthentication()
        mainHandler.removeCallbacks(expiry)
        cancellation = null
        super.onDestroy()
    }

    private fun failClosed() {
        cancelPendingAuthentication()
        finishBoundary()
    }

    private fun finishWith(vault: SensitivePhraseVault?) {
        if (completed) return
        completed = true
        unlockRequest = null
        mainHandler.removeCallbacks(expiry)
        SensitivePhraseAuthCoordinator.deliver(requestId, vault)
        finishBoundary()
    }

    private fun cancelPendingAuthentication() {
        if (completed) return
        completed = true
        unlockRequest = null
        mainHandler.removeCallbacks(expiry)
        if (requestId != INVALID_REQUEST_ID) SensitivePhraseAuthCoordinator.cancel(requestId)
        cancellation?.cancel()
    }

    private fun finishBoundary() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        internal const val EXTRA_REQUEST_ID = "sensitive_phrase_auth_request_id"
        private const val INVALID_REQUEST_ID = -1L
    }
}

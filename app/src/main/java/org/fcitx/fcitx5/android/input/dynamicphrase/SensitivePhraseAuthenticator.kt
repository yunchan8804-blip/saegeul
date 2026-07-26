/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dynamicphrase

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import org.fcitx.fcitx5.android.R
import javax.crypto.Cipher

object SensitivePhraseAuthenticator {
    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(
        context: Context,
        cipher: Cipher,
        title: CharSequence,
        subtitle: CharSequence,
        onSuccess: (Cipher) -> Unit,
        onError: (CharSequence) -> Unit
    ): CancellationSignal? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !isAvailable(context)) {
            onError(context.getString(R.string.secret_vault_auth_unavailable))
            return null
        }
        return authenticateR(context, cipher, title, subtitle, onSuccess, onError)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun authenticateR(
        context: Context,
        cipher: Cipher,
        title: CharSequence,
        subtitle: CharSequence,
        onSuccess: (Cipher) -> Unit,
        onError: (CharSequence) -> Unit
    ): CancellationSignal {
        val cancellation = CancellationSignal()
        BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(true)
            .build()
            .authenticate(
                BiometricPrompt.CryptoObject(cipher),
                cancellation,
                context.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authenticated = result.cryptoObject?.cipher
                        if (authenticated == null) {
                            onError(context.getString(R.string.secret_vault_auth_failed))
                        } else {
                            onSuccess(authenticated)
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        onError(errString)
                    }
                }
            )
        return cancellation
    }
}

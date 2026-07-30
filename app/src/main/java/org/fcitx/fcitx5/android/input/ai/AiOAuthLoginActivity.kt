/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.ResponseTypeValues
import org.fcitx.fcitx5.android.R
import timber.log.Timber

/** Browser-based public-client Authorization Code flow. WebView and client secrets are forbidden. */
class AiOAuthLoginActivity : AppCompatActivity() {
    private lateinit var authorizationService: AuthorizationService
    private var completed = false
    private var pendingResponse: AuthorizationResponse? = null

    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleAuthorizationResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        authorizationService = AuthorizationService(this)
        setContentView(TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setText(R.string.ai_oauth_browser_waiting)
        })
        val restored = savedInstanceState?.getString(STATE_AUTHORIZATION_RESPONSE)?.let { json ->
            runCatching { AuthorizationResponse.jsonDeserialize(json) }.getOrNull()
        }
        if (restored != null) {
            exchangeAuthorizationCode(restored)
        } else if (savedInstanceState == null) {
            startAuthorization()
        } else {
            fail(R.string.ai_oauth_authorization_failed)
        }
    }

    private fun startAuthorization() {
        val profile = AiProviderCredentialStore(this).load()
            ?.takeIf { it.authMode == AiAuthMode.OAuthPkce }
            ?: return fail(R.string.ai_oauth_profile_missing)
        val validated = runCatching { profile.validate() }.getOrNull()
            ?: return fail(R.string.ai_provider_invalid)
        val configuration = AuthorizationServiceConfiguration(
            Uri.parse(validated.oauthAuthorizationEndpoint),
            Uri.parse(validated.oauthTokenEndpoint)
        )
        val request = AuthorizationRequest.Builder(
            configuration,
            validated.oauthClientId,
            ResponseTypeValues.CODE,
            Uri.parse(AiProviderProfile.oauthRedirectUri)
        )
            .setScope(validated.oauthScopes)
            .build()
        // API 23+ always has SHA-256. Never downgrade PKCE to plain or omit state.
        if (request.codeVerifierChallengeMethod != AuthorizationRequest.CODE_CHALLENGE_METHOD_S256 ||
            request.state.isNullOrBlank()
        ) {
            fail(R.string.ai_oauth_pkce_unavailable)
            return
        }
        val authorizationIntent = try {
            authorizationService.getAuthorizationRequestIntent(request)
        } catch (error: Exception) {
            failAuthorizationStart(AiOAuthStartFailure.fromAuthorizationIntentError(error))
            return
        }
        try {
            authorizationLauncher.launch(authorizationIntent)
        } catch (error: Exception) {
            // This is no longer AppAuth browser selection. A missing activity or lifecycle error
            // must not tell a user to install a browser they already have.
            Timber.w("AI OAuth authorization activity launch failed: ${error.javaClass.simpleName}")
            failAuthorizationStart(AiOAuthStartFailure.UnableToStart)
        }
    }

    private fun failAuthorizationStart(failure: AiOAuthStartFailure) {
        Timber.w("AI OAuth authorization start failed: ${failure.name}")
        fail(
            when (failure) {
                AiOAuthStartFailure.BrowserUnavailable -> R.string.ai_oauth_browser_unavailable
                AiOAuthStartFailure.UnableToStart -> R.string.ai_oauth_start_failed
            }
        )
    }

    private fun handleAuthorizationResult(data: Intent?) {
        if (completed) return
        val response = data?.let(AuthorizationResponse::fromIntent)
        val exception = data?.let(AuthorizationException::fromIntent)
        if (response == null || exception != null) {
            fail(R.string.ai_oauth_authorization_failed)
            return
        }
        if (response.state.isNullOrBlank() || response.state != response.request.state) {
            fail(R.string.ai_oauth_state_mismatch)
            return
        }
        exchangeAuthorizationCode(response)
    }

    private fun exchangeAuthorizationCode(response: AuthorizationResponse) {
        if (response.state.isNullOrBlank() || response.state != response.request.state) {
            fail(R.string.ai_oauth_state_mismatch)
            return
        }
        val profile = AiProviderCredentialStore(this).load()
            ?.takeIf { it.authMode == AiAuthMode.OAuthPkce }
            ?: return fail(R.string.ai_oauth_profile_missing)
        if (!matchesCurrentProfile(profile, response)) {
            fail(R.string.ai_oauth_profile_changed)
            return
        }
        pendingResponse = response
        val authState = AuthState().apply { update(response, null) }
        authorizationService.performTokenRequest(
            response.createTokenExchangeRequest(),
            NoClientAuthentication.INSTANCE
        ) { tokenResponse, tokenException ->
            authState.update(tokenResponse, tokenException)
            if (tokenResponse == null || tokenException != null || !authState.isAuthorized) {
                fail(R.string.ai_oauth_token_failed)
                return@performTokenRequest
            }
            val current = AiProviderCredentialStore(this).load()
            if (current == null || !matchesCurrentProfile(current, response)) {
                fail(R.string.ai_oauth_profile_changed)
                return@performTokenRequest
            }
            runCatching { AiOAuthSessionStore(this).save(current, authState) }
                .onSuccess { succeed() }
                .onFailure { fail(R.string.ai_oauth_token_failed) }
        }
    }

    private fun succeed() {
        if (completed) return
        completed = true
        pendingResponse = null
        Toast.makeText(this, R.string.ai_oauth_connected, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun fail(message: Int) {
        if (completed) return
        completed = true
        pendingResponse = null
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun matchesCurrentProfile(
        profile: AiProviderProfile,
        response: AuthorizationResponse
    ): Boolean = AiOAuthCallbackContract.matchesCurrentProfile(
        profile = profile,
        clientId = response.request.clientId,
        authorizationEndpoint = response.request.configuration.authorizationEndpoint.toString(),
        tokenEndpoint = response.request.configuration.tokenEndpoint.toString(),
        redirectUri = response.request.redirectUri.toString()
    )

    override fun onDestroy() {
        authorizationService.dispose()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingResponse?.let {
            outState.putString(STATE_AUTHORIZATION_RESPONSE, it.jsonSerializeString())
        }
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val STATE_AUTHORIZATION_RESPONSE = "oauth.authorization.response"

        fun createIntent(context: Context): Intent = Intent(context, AiOAuthLoginActivity::class.java)
    }
}

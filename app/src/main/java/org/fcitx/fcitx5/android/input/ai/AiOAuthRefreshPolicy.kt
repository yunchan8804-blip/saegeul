/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import net.openid.appauth.AuthorizationException

/** Whether an OAuth token refresh failure should terminate the persisted session. */
enum class AiOAuthRefreshDecision { CLEAR_SESSION, KEEP_SESSION }

/**
 * Classifies AppAuth token refresh failures. Only a confirmed rejection of the refresh token or
 * client by the authorization server (invalid_grant/invalid_client/unauthorized_client) clears
 * the persisted session; every other failure (network, server, JSON, unknown) is transient and
 * keeps the session so the next call can retry.
 */
object AiOAuthRefreshPolicy {

    fun classify(type: Int?, code: Int?): AiOAuthRefreshDecision {
        if (type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
            code != null &&
            code in TERMINAL_TOKEN_ERROR_CODES
        ) {
            return AiOAuthRefreshDecision.CLEAR_SESSION
        }
        return AiOAuthRefreshDecision.KEEP_SESSION
    }

    fun describe(type: Int?, code: Int?): String {
        if (type == null && code == null) return "none"
        val typeName = when (type) {
            AuthorizationException.TYPE_GENERAL_ERROR -> "general"
            AuthorizationException.TYPE_OAUTH_AUTHORIZATION_ERROR -> "authorization_error"
            AuthorizationException.TYPE_OAUTH_TOKEN_ERROR -> "token_error"
            AuthorizationException.TYPE_RESOURCE_SERVER_AUTHORIZATION_ERROR -> "resource_error"
            AuthorizationException.TYPE_OAUTH_REGISTRATION_ERROR -> "registration_error"
            else -> "unknown"
        }
        val codeName = code?.let { describeCode(it) } ?: "unknown"
        return "$typeName/$codeName"
    }

    private fun describeCode(code: Int): String = when (code) {
        AuthorizationException.GeneralErrors.NETWORK_ERROR.code -> "network_error"
        AuthorizationException.GeneralErrors.SERVER_ERROR.code -> "server_error"
        AuthorizationException.GeneralErrors.JSON_DESERIALIZATION_ERROR.code -> "json_deserialization_error"
        AuthorizationException.GeneralErrors.TOKEN_RESPONSE_CONSTRUCTION_ERROR.code -> "token_response_construction_error"
        AuthorizationException.GeneralErrors.INVALID_DISCOVERY_DOCUMENT.code -> "invalid_discovery_document"
        AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code -> "user_canceled_auth_flow"
        AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW.code -> "program_canceled_auth_flow"
        AuthorizationException.GeneralErrors.INVALID_REGISTRATION_RESPONSE.code -> "invalid_registration_response"
        AuthorizationException.GeneralErrors.ID_TOKEN_PARSING_ERROR.code -> "id_token_parsing_error"
        AuthorizationException.GeneralErrors.ID_TOKEN_VALIDATION_ERROR.code -> "id_token_validation_error"
        AuthorizationException.TokenRequestErrors.INVALID_REQUEST.code -> "invalid_request"
        AuthorizationException.TokenRequestErrors.INVALID_CLIENT.code -> "invalid_client"
        AuthorizationException.TokenRequestErrors.INVALID_GRANT.code -> "invalid_grant"
        AuthorizationException.TokenRequestErrors.UNAUTHORIZED_CLIENT.code -> "unauthorized_client"
        AuthorizationException.TokenRequestErrors.UNSUPPORTED_GRANT_TYPE.code -> "unsupported_grant_type"
        AuthorizationException.TokenRequestErrors.INVALID_SCOPE.code -> "invalid_scope"
        AuthorizationException.TokenRequestErrors.CLIENT_ERROR.code -> "client_error"
        AuthorizationException.TokenRequestErrors.OTHER.code -> "other"
        else -> code.toString()
    }

    private val TERMINAL_TOKEN_ERROR_CODES = setOf(
        AuthorizationException.TokenRequestErrors.INVALID_GRANT.code,
        AuthorizationException.TokenRequestErrors.INVALID_CLIENT.code,
        AuthorizationException.TokenRequestErrors.UNAUTHORIZED_CLIENT.code
    )
}

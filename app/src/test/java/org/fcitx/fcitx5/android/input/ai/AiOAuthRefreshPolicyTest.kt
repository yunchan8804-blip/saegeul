/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import net.openid.appauth.AuthorizationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiOAuthRefreshPolicyTest {

    @Test
    fun `invalid_grant clears the session`() {
        assertEquals(
            AiOAuthRefreshDecision.CLEAR_SESSION,
            AiOAuthRefreshPolicy.classify(
                AuthorizationException.TYPE_OAUTH_TOKEN_ERROR,
                AuthorizationException.TokenRequestErrors.INVALID_GRANT.code
            )
        )
    }

    @Test
    fun `invalid_client clears the session`() {
        assertEquals(
            AiOAuthRefreshDecision.CLEAR_SESSION,
            AiOAuthRefreshPolicy.classify(
                AuthorizationException.TYPE_OAUTH_TOKEN_ERROR,
                AuthorizationException.TokenRequestErrors.INVALID_CLIENT.code
            )
        )
    }

    @Test
    fun `unauthorized_client clears the session`() {
        assertEquals(
            AiOAuthRefreshDecision.CLEAR_SESSION,
            AiOAuthRefreshPolicy.classify(
                AuthorizationException.TYPE_OAUTH_TOKEN_ERROR,
                AuthorizationException.TokenRequestErrors.UNAUTHORIZED_CLIENT.code
            )
        )
    }

    @Test
    fun `network error keeps the session`() {
        assertEquals(
            AiOAuthRefreshDecision.KEEP_SESSION,
            AiOAuthRefreshPolicy.classify(
                AuthorizationException.TYPE_GENERAL_ERROR,
                AuthorizationException.GeneralErrors.NETWORK_ERROR.code
            )
        )
    }

    @Test
    fun `server error keeps the session`() {
        assertEquals(
            AiOAuthRefreshDecision.KEEP_SESSION,
            AiOAuthRefreshPolicy.classify(
                AuthorizationException.TYPE_GENERAL_ERROR,
                AuthorizationException.GeneralErrors.SERVER_ERROR.code
            )
        )
    }

    @Test
    fun `json deserialization error keeps the session`() {
        assertEquals(
            AiOAuthRefreshDecision.KEEP_SESSION,
            AiOAuthRefreshPolicy.classify(
                AuthorizationException.TYPE_GENERAL_ERROR,
                AuthorizationException.GeneralErrors.JSON_DESERIALIZATION_ERROR.code
            )
        )
    }

    @Test
    fun `token response construction error keeps the session`() {
        assertEquals(
            AiOAuthRefreshDecision.KEEP_SESSION,
            AiOAuthRefreshPolicy.classify(
                AuthorizationException.TYPE_GENERAL_ERROR,
                AuthorizationException.GeneralErrors.TOKEN_RESPONSE_CONSTRUCTION_ERROR.code
            )
        )
    }

    @Test
    fun `other token error keeps the session`() {
        assertEquals(
            AiOAuthRefreshDecision.KEEP_SESSION,
            AiOAuthRefreshPolicy.classify(
                AuthorizationException.TYPE_OAUTH_TOKEN_ERROR,
                AuthorizationException.TokenRequestErrors.OTHER.code
            )
        )
    }

    @Test
    fun `resource server authorization error keeps the session`() {
        assertEquals(
            AiOAuthRefreshDecision.KEEP_SESSION,
            AiOAuthRefreshPolicy.classify(
                AuthorizationException.TYPE_RESOURCE_SERVER_AUTHORIZATION_ERROR,
                AuthorizationException.TokenRequestErrors.INVALID_GRANT.code
            )
        )
    }

    @Test
    fun `missing type and code keeps the session`() {
        assertEquals(
            AiOAuthRefreshDecision.KEEP_SESSION,
            AiOAuthRefreshPolicy.classify(null, null)
        )
    }

    @Test
    fun `describe includes type and code names`() {
        val description = AiOAuthRefreshPolicy.describe(
            AuthorizationException.TYPE_OAUTH_TOKEN_ERROR,
            AuthorizationException.TokenRequestErrors.INVALID_GRANT.code
        )
        assertTrue(description.contains("token_error/invalid_grant"))
    }

    @Test
    fun `describe with no exception is none`() {
        assertEquals("none", AiOAuthRefreshPolicy.describe(null, null))
    }
}

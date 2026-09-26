/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.persona

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonaRegistryTest {

    @Test
    fun exactWorkPackagesClassifyAsWork() {
        assertEquals("work", PersonaRegistry.classify("notion.id"))
        assertEquals("work", PersonaRegistry.classify("com.atlassian.android.jira.core"))
        assertEquals("work", PersonaRegistry.classify("com.tosslab.jandi.app"))
        assertEquals("work", PersonaRegistry.classify("com.kakaoenterprise.kakaowork"))
    }

    @Test
    fun exactMessengerPackageClassifiesAsMessenger() {
        assertEquals("messenger", PersonaRegistry.classify("com.kakao.talk"))
    }

    @Test
    fun workTokenHeuristicWinsOverMessengerToken() {
        // "teamchat" contains both a work token ("team") and a messenger token ("chat"); work
        // must win because the confirmed priority checks work tokens first.
        assertEquals("work", PersonaRegistry.classify("com.example.teamchat"))
    }

    @Test
    fun exactEmailPackageClassifiesAsEmail() {
        assertEquals("email", PersonaRegistry.classify("com.google.android.gm"))
    }

    @Test
    fun browserTokenClassifiesAsBrowser() {
        assertEquals("browser", PersonaRegistry.classify("com.android.chrome"))
    }

    @Test
    fun unknownPackageFallsBackToGeneral() {
        assertEquals(PersonaRegistry.GENERAL, PersonaRegistry.classify("com.totally.unknown.app"))
    }

    @Test
    fun validOverrideWinsOverExactAndTokenMatches() {
        // com.kakao.talk would otherwise classify as messenger by exact package match.
        assertEquals("work", PersonaRegistry.classify("com.kakao.talk", override = "work"))
    }

    @Test
    fun unknownOverrideIsIgnored() {
        assertEquals("messenger", PersonaRegistry.classify("com.kakao.talk", override = "not-a-real-id"))
    }

    @Test
    fun classificationIsCaseInsensitive() {
        assertEquals("work", PersonaRegistry.classify("COM.SLACK"))
        assertEquals("work", PersonaRegistry.classify("NOTION.ID"))
    }

    @Test
    fun allListsExactlyTheEightBuiltInPersonas() {
        val ids = PersonaRegistry.all.map { it.id }
        assertEquals(
            listOf("messenger", "work", "email", "social", "notes", "browser", "commerce", "general"),
            ids
        )
    }

    @Test
    fun byIdReturnsRegisteredCategoryOrNull() {
        assertEquals("work", PersonaRegistry.byId("work")?.id)
        assertEquals(null, PersonaRegistry.byId("does-not-exist"))
    }

    @Test
    fun confirmedWorkPackagesClassifyAsWork() {
        assertEquals("work", PersonaRegistry.classify("com.todoist"))
        assertEquals("work", PersonaRegistry.classify("com.wanted.android.wanted"))
        // "docs" would otherwise suggest notes by token, but the exact package match wins.
        assertEquals("work", PersonaRegistry.classify("com.hancom.docs.suite"))
    }

    @Test
    fun confirmedEmailPackagesClassifyAsEmail() {
        assertEquals("email", PersonaRegistry.classify("com.nhn.android.mail"))
        assertEquals("email", PersonaRegistry.classify("net.daum.android.mail"))
        assertEquals("email", PersonaRegistry.classify("ch.protonmail.android"))
    }

    @Test
    fun confirmedMessengerPackagesClassifyAsMessenger() {
        assertEquals("messenger", PersonaRegistry.classify("com.discord"))
        assertEquals("messenger", PersonaRegistry.classify("com.viber.voip"))
        assertEquals("messenger", PersonaRegistry.classify("com.tencent.mm"))
    }

    @Test
    fun confirmedSocialPackagesClassifyAsSocial() {
        assertEquals("social", PersonaRegistry.classify("com.linkedin.android"))
        assertEquals("social", PersonaRegistry.classify("com.twitter.android"))
        // Exact match wins here regardless; the "community" token would also resolve to social.
        assertEquals("social", PersonaRegistry.classify("com.navercorp.game.android.community"))
    }

    @Test
    fun confirmedNotesPackagesClassifyAsNotes() {
        assertEquals("notes", PersonaRegistry.classify("com.samsung.android.app.notes"))
        assertEquals("notes", PersonaRegistry.classify("com.google.android.keep"))
        assertEquals("notes", PersonaRegistry.classify("com.evernote"))
    }

    @Test
    fun confirmedBrowserPackagesClassifyAsBrowser() {
        assertEquals("browser", PersonaRegistry.classify("com.nhn.android.search"))
        assertEquals("browser", PersonaRegistry.classify("org.mozilla.firefox"))
        assertEquals("browser", PersonaRegistry.classify("com.naver.whale"))
    }

    @Test
    fun confirmedCommercePackagesClassifyAsCommerce() {
        // com.sampleapp is Baemin's actual package name.
        assertEquals("commerce", PersonaRegistry.classify("com.sampleapp"))
        assertEquals("commerce", PersonaRegistry.classify("viva.republica.toss"))
        assertEquals("commerce", PersonaRegistry.classify("com.towneers.www"))
    }

    @Test
    fun instagramStaysUnderMessengerNotDuplicatedIntoSocial() {
        // Confirmed design: com.instagram.android remains in MESSENGER_PACKAGES, not SOCIAL_PACKAGES.
        assertEquals("messenger", PersonaRegistry.classify("com.instagram.android"))
    }
}

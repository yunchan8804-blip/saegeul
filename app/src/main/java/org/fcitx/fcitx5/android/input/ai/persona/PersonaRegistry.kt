/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.persona

import org.fcitx.fcitx5.android.R

/**
 * A single app-persona bucket used to categorize Typing DNA collection: its persisted storage
 * id, display label, exact package matches, and lowercase-substring token heuristics used when
 * no package matches exactly.
 */
data class PersonaCategory(
    val id: String,
    val labelRes: Int,
    val packages: Set<String>,
    val tokens: List<String>
)

/**
 * Registry of app personas used to bucket typing context for Typing DNA collection.
 * Ids are persisted as [org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaProfile] map keys and must
 * never change once shipped.
 */
object PersonaRegistry {

    const val GENERAL = "general"

    // Package sets confirmed by research (2026-09-16). Case is preserved as observed on the
    // Play Store; matching itself is case-insensitive (see [classify]).
    private val MESSENGER_PACKAGES = setOf(
        "com.kakao.talk",
        "org.telegram.messenger",
        "com.instagram.android",
        "com.facebook.orca",
        "jp.naver.line.android",
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.discord",
        "org.thoughtcrime.securesms",
        "com.viber.voip",
        "com.tencent.mm"
    )

    private val WORK_PACKAGES = setOf(
        "com.Slack",
        "com.microsoft.teams",
        "com.kakaoenterprise.kakaowork",
        "com.gworks.oneapp.naverworks",
        "com.gworks.oneapp.works",
        "com.tosslab.jandi.app",
        "team.flow.ai",
        "com.dooray.all",
        "notion.id",
        "com.atlassian.android.jira.core",
        "com.atlassian.android.confluence.core",
        "com.trello",
        "com.asana.app",
        "com.github.android",
        "us.zoom.videomeetings",
        "com.google.android.apps.tachyon",
        "com.cisco.webex.meetings",
        "com.google.android.apps.docs.editors.sheets",
        "com.google.android.apps.docs.editors.slides",
        "com.microsoft.office.word",
        "com.microsoft.office.excel",
        "com.microsoft.office.powerpoint",
        "com.google.android.calendar",
        "com.samsung.android.calendar",
        "com.todoist",
        "com.ticktick.task",
        "com.hancom.docs.suite",
        "com.infraware.office.link",
        "io.swit",
        "com.ir.collaby_app",
        "kr.co.rememberapp",
        "kr.co.saramin.brandapp",
        "com.jobkorea.app",
        "com.wanted.android.wanted",
        "com.commit451.gitlab"
    )

    private val EMAIL_PACKAGES = setOf(
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.nhn.android.mail",
        "net.daum.android.mail",
        "com.samsung.android.email.provider",
        "com.yahoo.mobile.client.android.mail",
        "ch.protonmail.android",
        "com.readdle.spark",
        "me.bluemail.mail"
    )

    private val NOTES_PACKAGES = setOf(
        "com.google.android.apps.docs.editors.docs",
        "com.samsung.android.app.notes",
        "com.google.android.keep",
        "com.evernote",
        "com.microsoft.office.onenote",
        "md.obsidian",
        "com.nhn.android.navermemo",
        "com.automattic.simplenote",
        "com.socialnmobile.dictapps.notepad.color.note",
        "net.cozic.joplin"
    )

    // com.instagram.android is deliberately not repeated here: it is already an exact match
    // under MESSENGER_PACKAGES (kept there per the confirmed design), and exact matching checks
    // messenger before social, so listing it here would never take effect.
    private val SOCIAL_PACKAGES = setOf(
        "com.twitter.android",
        "com.facebook.katana",
        "com.instagram.barcelona",
        "com.zhiliaoapp.musically",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.linkedin.android",
        "tv.twitch.android.app",
        "com.navercorp.game.android.community",
        "kr.co.nowcom.mobile.afreeca"
    )

    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "com.nhn.android.search",
        "net.daum.android.daum",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.naver.whale",
        "com.google.android.googlequicksearchbox"
    )

    private val COMMERCE_PACKAGES = setOf(
        "com.coupang.mobile",
        "com.elevenst",
        "com.ebay.kr.gmarket",
        "com.sampleapp",
        "com.fineapp.yogiyo",
        "viva.republica.toss",
        "com.kakaobank.channel",
        "com.kakaopay.app",
        "com.samsung.android.spay",
        "com.towneers.www"
    )

    private val MESSENGER = PersonaCategory(
        id = "messenger",
        labelRes = R.string.persona_label_messenger,
        packages = MESSENGER_PACKAGES,
        tokens = listOf("talk", "chat", "message", "messenger", "sms", "mms")
    )

    private val WORK = PersonaCategory(
        id = "work",
        labelRes = R.string.persona_label_work,
        packages = WORK_PACKAGES,
        tokens = listOf("work", "works", "team", "office", "enterprise", "corp", "biz", "jira", "slack")
    )

    private val EMAIL = PersonaCategory(
        id = "email",
        labelRes = R.string.persona_label_email,
        packages = EMAIL_PACKAGES,
        tokens = listOf("mail")
    )

    private val SOCIAL = PersonaCategory(
        id = "social",
        labelRes = R.string.persona_label_social,
        packages = SOCIAL_PACKAGES,
        tokens = listOf("instagram", "twitter", "facebook", "tiktok", "band", "blog", "community")
    )

    private val NOTES = PersonaCategory(
        id = "notes",
        labelRes = R.string.persona_label_notes,
        packages = NOTES_PACKAGES,
        tokens = listOf("note", "memo", "docs", "keep")
    )

    private val BROWSER = PersonaCategory(
        id = "browser",
        labelRes = R.string.persona_label_browser,
        packages = BROWSER_PACKAGES,
        tokens = listOf("browser", "chrome", "search")
    )

    private val COMMERCE = PersonaCategory(
        id = "commerce",
        labelRes = R.string.persona_label_commerce,
        packages = COMMERCE_PACKAGES,
        tokens = listOf("shop", "pay", "bank", "market", "delivery")
    )

    private val GENERAL_CATEGORY = PersonaCategory(
        id = GENERAL,
        labelRes = R.string.persona_label_general,
        packages = emptySet(),
        tokens = emptyList()
    )

    val all: List<PersonaCategory> = listOf(
        MESSENGER, WORK, EMAIL, SOCIAL, NOTES, BROWSER, COMMERCE, GENERAL_CATEGORY
    )

    /**
     * Token-heuristic priority order per the confirmed design: work-related tokens are checked
     * before messenger tokens so e.g. "teamchat" resolves to work, not messenger.
     */
    private val TOKEN_PRIORITY: List<PersonaCategory> = listOf(
        WORK, EMAIL, NOTES, MESSENGER, SOCIAL, BROWSER, COMMERCE
    )

    private val byIdMap: Map<String, PersonaCategory> = all.associateBy { it.id }

    fun byId(id: String): PersonaCategory? = byIdMap[id]

    /**
     * Classifies [packageName] into a persona id.
     * Order: (1) [override] wins when it names a registered persona, (2) an exact package match
     * against any registered persona (case-insensitive, exact name or a "pkg." sub-package),
     * (3) a token heuristic in [TOKEN_PRIORITY] order, (4) [GENERAL].
     */
    fun classify(packageName: String, override: String? = null): String {
        if (override != null && byIdMap.containsKey(override)) return override

        val lower = packageName.lowercase()

        for (category in all) {
            if (category.id == GENERAL) continue
            val matchesExactPackage = category.packages.any { pkg ->
                val p = pkg.lowercase()
                lower == p || lower.startsWith("$p.")
            }
            if (matchesExactPackage) return category.id
        }

        for (category in TOKEN_PRIORITY) {
            if (category.tokens.any { lower.contains(it) }) return category.id
        }

        return GENERAL
    }
}

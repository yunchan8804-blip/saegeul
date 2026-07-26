/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import java.util.Locale

/**
 * Small fail-closed guard in front of the provider's account-level content controls.
 *
 * This is deliberately not presented as a complete moderation system. It prevents an explicit
 * query from leaving the keyboard and removes obviously explicit metadata returned by a provider;
 * the KLIPY Partner Panel filter remains the authoritative catalog-side control.
 */
object GifSafeSearchPolicy {
    private val blockedTerms = setOf(
        "18+", "adult only", "hentai", "nsfw", "nude", "nudity", "porn", "porno",
        "sex tape", "xxx", "노출", "누드", "성인물", "야동", "음란", "포르노"
    )

    fun isAllowedQuery(query: String): Boolean = !containsBlockedTerm(query)

    fun isAllowedResult(title: String, slug: String): Boolean =
        !containsBlockedTerm("$title $slug")

    private fun containsBlockedTerm(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return false
        return blockedTerms.any { term ->
            if (term.any { !it.isLetterOrDigit() }) {
                normalized.contains(term)
            } else {
                TOKEN_PATTERN.findAll(normalized).any { it.value == term }
            }
        }
    }

    private val TOKEN_PATTERN = Regex("[\\p{L}\\p{N}]+")
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase.dynamic

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DynamicPhraseVariable(val tokens: Set<String>) {
    Date(setOf("{날짜}", "{date}")),
    Time(setOf("{시간}", "{time}")),
    Name(setOf("{이름}", "{name}")),
    Phone(setOf("{전화번호}", "{phone}")),
    Email(setOf("{이메일}", "{email}")),
    Address(setOf("{주소}", "{address}")),
    Clipboard(setOf("{클립보드}", "{clipboard}"))
}

data class DynamicPhraseProfile(
    val name: String = "",
    val phone: String = "",
    val address: String = "",
    val email: String = ""
) {
    fun normalized() = copy(
        name = name.trim(),
        phone = phone.trim(),
        address = address.trim(),
        email = email.trim()
    )

    val isEmpty: Boolean
        get() = name.isBlank() && phone.isBlank() && address.isBlank() && email.isBlank()
}

data class DynamicPhraseValues(
    val now: ZonedDateTime,
    val profile: DynamicPhraseProfile = DynamicPhraseProfile(),
    val clipboardText: String? = null,
    val clipboardSensitive: Boolean = false,
    val privateEditor: Boolean = false
)

enum class DynamicPhraseIssueReason {
    MissingValue,
    PrivateEditor,
    SensitiveClipboard
}

data class DynamicPhraseIssue(
    val variable: DynamicPhraseVariable,
    val reason: DynamicPhraseIssueReason
)

data class DynamicPhraseResolution(
    val text: String,
    val usedVariables: Set<DynamicPhraseVariable>,
    val issues: List<DynamicPhraseIssue>
) {
    val canInsert: Boolean
        get() = issues.isEmpty()
}

object DynamicPhraseTemplate {
    private val tokenToVariable = DynamicPhraseVariable.entries
        .flatMap { variable -> variable.tokens.map { token -> token.lowercase(Locale.ROOT) to variable } }
        .toMap()

    private val supportedToken = Regex(
        tokenToVariable.keys
            .sortedByDescending(String::length)
            .joinToString(prefix = "(?:", postfix = ")", separator = "|") { Regex.escape(it) },
        RegexOption.IGNORE_CASE
    )

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun containsSupportedToken(text: String): Boolean = supportedToken.containsMatchIn(text)

    fun expand(template: String, values: DynamicPhraseValues): DynamicPhraseResolution {
        val profile = values.profile.normalized()
        val used = linkedSetOf<DynamicPhraseVariable>()
        val issues = linkedMapOf<DynamicPhraseVariable, DynamicPhraseIssueReason>()
        val expanded = supportedToken.replace(template) { match ->
            val variable = tokenToVariable.getValue(match.value.lowercase(Locale.ROOT))
            used += variable
            val replacement = resolve(variable, values, profile)
            if (replacement.value == null) {
                issues.putIfAbsent(variable, replacement.issue!!)
                match.value
            } else {
                replacement.value
            }
        }
        return DynamicPhraseResolution(
            text = expanded,
            usedVariables = used,
            issues = issues.map { (variable, reason) -> DynamicPhraseIssue(variable, reason) }
        )
    }

    private fun resolve(
        variable: DynamicPhraseVariable,
        values: DynamicPhraseValues,
        profile: DynamicPhraseProfile
    ): Replacement = when (variable) {
        DynamicPhraseVariable.Date -> Replacement(values.now.format(dateFormatter))
        DynamicPhraseVariable.Time -> Replacement(values.now.format(timeFormatter))
        DynamicPhraseVariable.Name -> personalValue(profile.name, values.privateEditor)
        DynamicPhraseVariable.Phone -> personalValue(profile.phone, values.privateEditor)
        DynamicPhraseVariable.Email -> personalValue(profile.email, values.privateEditor)
        DynamicPhraseVariable.Address -> personalValue(profile.address, values.privateEditor)
        DynamicPhraseVariable.Clipboard -> when {
            values.privateEditor -> Replacement(issue = DynamicPhraseIssueReason.PrivateEditor)
            values.clipboardSensitive -> Replacement(issue = DynamicPhraseIssueReason.SensitiveClipboard)
            values.clipboardText.isNullOrBlank() -> Replacement(issue = DynamicPhraseIssueReason.MissingValue)
            else -> Replacement(values.clipboardText)
        }
    }

    private fun personalValue(value: String, privateEditor: Boolean): Replacement = when {
        privateEditor -> Replacement(issue = DynamicPhraseIssueReason.PrivateEditor)
        value.isBlank() -> Replacement(issue = DynamicPhraseIssueReason.MissingValue)
        else -> Replacement(value)
    }

    private data class Replacement(
        val value: String? = null,
        val issue: DynamicPhraseIssueReason? = null
    )
}

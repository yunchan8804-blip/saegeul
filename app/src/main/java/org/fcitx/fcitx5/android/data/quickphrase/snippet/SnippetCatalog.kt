/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase.snippet

import java.util.Locale

data class SnippetDefinition(val trigger: String, val template: String)

data class SnippetExpansionPlan(
    val trigger: String,
    val template: String,
    /** UTF-16 code units to delete from the target editor before its cursor. */
    val deleteBeforeCursor: Int,
    /** Buffered text before the trigger that must survive the replacement. */
    val pendingPrefix: String
) {
    fun replacement(expanded: String, boundarySuffix: String): String =
        pendingPrefix + expanded + boundarySuffix
}

/** Immutable, memory-only index of built-in aliases and enabled `:` quick phrases. */
class SnippetCatalog private constructor(
    private val templates: Map<String, SnippetDefinition>
) {
    fun plan(editorBeforeCursor: String, pendingText: String = ""): SnippetExpansionPlan? {
        val combined = editorBeforeCursor + pendingText
        val colon = combined.lastIndexOf(':')
        if (colon < 0) return null
        if (colon > 0 && !combined[colon - 1].isWhitespace()) return null

        val trigger = combined.substring(colon)
        if (!isValidTrigger(trigger)) return null
        val definition = templates[trigger.lowercase(Locale.ROOT)] ?: return null
        val deleteBeforeCursor = (editorBeforeCursor.length - colon).coerceAtLeast(0)
        val pendingPrefix = if (colon >= editorBeforeCursor.length) {
            pendingText.substring(0, colon - editorBeforeCursor.length)
        } else {
            ""
        }
        return SnippetExpansionPlan(
            trigger = trigger,
            template = definition.template,
            deleteBeforeCursor = deleteBeforeCursor,
            pendingPrefix = pendingPrefix
        )
    }

    companion object {
        const val MAX_TRIGGER_CODE_POINTS = 32

        private val BuiltIns = listOf(
            SnippetDefinition(":이름", "{이름}"),
            SnippetDefinition(":전화", "{전화번호}"),
            SnippetDefinition(":전화번호", "{전화번호}"),
            SnippetDefinition(":이메일", "{이메일}"),
            SnippetDefinition(":메일", "{이메일}"),
            SnippetDefinition(":주소", "{주소}"),
            SnippetDefinition(":주소1", "{주소}"),
            SnippetDefinition(":날짜", "{날짜}"),
            SnippetDefinition(":시간", "{시간}"),
            SnippetDefinition(":name", "{name}"),
            SnippetDefinition(":phone", "{phone}"),
            SnippetDefinition(":email", "{email}"),
            SnippetDefinition(":address", "{address}"),
            SnippetDefinition(":address1", "{address}"),
            SnippetDefinition(":date", "{date}"),
            SnippetDefinition(":time", "{time}")
        )

        fun fromUserDefinitions(userDefinitions: Iterable<SnippetDefinition>): SnippetCatalog {
            val templates = BuiltIns.associateByTo(linkedMapOf()) {
                it.trigger.lowercase(Locale.ROOT)
            }
            userDefinitions
                .filter { isValidTrigger(it.trigger) && it.template.isNotEmpty() }
                .groupBy { it.trigger.lowercase(Locale.ROOT) }
                .forEach { (trigger, definitions) ->
                    val distinct = definitions.distinctBy(SnippetDefinition::template)
                    if (distinct.size == 1) {
                        templates[trigger] = distinct.single()
                    } else {
                        // Ambiguous quick phrases stay available in the normal candidate UI, but
                        // automatic expansion must never guess which private value the user meant.
                        templates.remove(trigger)
                    }
                }
            return SnippetCatalog(templates)
        }

        fun builtIns(): SnippetCatalog = fromUserDefinitions(emptyList())

        fun isValidTrigger(trigger: String): Boolean =
            trigger.startsWith(':') &&
                trigger.length > 1 &&
                trigger.none(Char::isWhitespace) &&
                trigger.codePointCount(0, trigger.length) <= MAX_TRIGGER_CODE_POINTS
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the vault home screen's user-facing copy against the developer jargon the redesign is
 * meant to remove (어절/노드/엣지/재료/LLM/Bigram/페르소나/청크/묶음/동기화/컴파일/언어 지문/
 * Typing DNA/그래프 강화/보강/관계 그래프 - see the design packet). Only the strings this redesign
 * introduces for the user-facing cards are checked here; existing developer-section strings keep
 * their original technical labels by design and are out of scope.
 */
class VaultHomeStringsTerminologyTest {

    /** Every string id this redesign added for the Hero/Learning/Feedback/Style/Storage cards. */
    private val userFacingStringIds = listOf(
        "vault_home_headline",
        "vault_home_learned_line_with_streak",
        "vault_home_learned_line",
        "vault_home_points_chip",
        "vault_learning_title",
        "vault_learning_body_release",
        "vault_learning_body_engine_failed",
        "vault_learning_body_running_eta",
        "vault_learning_body_running_calculating",
        "vault_learning_body_waiting",
        "vault_learning_body_waiting_keyboard",
        "vault_learning_body_paused",
        "vault_learning_reason_problem",
        "vault_learning_body_material_only",
        "vault_learning_body_model_unavailable",
        "vault_learning_body_up_to_date",
        "vault_learning_body_ready",
        "vault_learning_action_get_model",
        "vault_learning_switch_label",
        "vault_learning_switch_hint",
        "vault_learning_notification_hint",
        "vault_applied_at_today",
        "vault_applied_at_yesterday",
        "vault_applied_at_date",
        "vault_feedback_title",
        "vault_feedback_empty",
        "vault_feedback_counts_line",
        "vault_feedback_personal_line",
        "vault_style_title",
        "vault_style_categories_line",
        "vault_storage_title",
        "vault_storage_lock_line",
        "vault_storage_info_content_description",
        "vault_storage_dialog_title",
        "vault_storage_dialog_key_strongbox",
        "vault_storage_dialog_key_tee",
        "vault_storage_dialog_key_software",
        "vault_storage_dialog_body",
        "vault_storage_backup_row",
        "vault_storage_backup_unavailable",
        "vault_storage_clear_button",
        "vault_storage_cleared_toast",
        "vault_sync_toast_completed",
        "vault_sync_toast_no_new",
        "vault_sync_toast_loaded",
        // Substituted into the learning card's PAUSED body (%1$s in vault_learning_body_paused),
        // so they render on the user-facing card even though they are pre-existing strings.
        "enrichment_pause_reason_battery_unknown",
        "enrichment_pause_reason_battery_low",
        "enrichment_pause_reason_power_save",
        "enrichment_pause_reason_thermal",
        "enrichment_pause_reason_low_memory",
        "enrichment_pause_reason_keyboard_active",
        "enrichment_pause_reason_screen_on",
        "enrichment_pause_reason_user_stopped",
        "enrichment_pause_reason_lease_wait_timeout",
        "enrichment_pause_reason_app_terminated",
        // "내 말투 리포트" screen and the chart sections it reuses (accumulation bars, tone balance).
        "typing_dna_chart_accumulation_title",
        "typing_dna_chart_label_sentences",
        "typing_dna_chart_label_word_pairs",
        "typing_dna_chart_label_endings",
        "typing_dna_chart_label_phrases",
        "typing_dna_chart_tone_title",
        "typing_dna_chart_tone_empty",
        "typing_dna_chart_tone_honorific_legend",
        "typing_dna_chart_tone_informal_legend",
        "typing_dna_chart_accessibility_header",
        "typing_dna_chart_accessibility_accumulation",
        "vault_timeline_summary",
        "vault_timeline_empty",
        "vault_style_report_row",
        "style_report_title",
        "style_report_empty_message",
        "style_report_accumulation_footer",
        "style_report_recent_title",
        "style_report_category_title",
        "style_report_category_empty",
        "style_report_style_title",
        "style_report_transitions_title",
        "style_report_endings_title",
        "style_report_footer_privacy"
    )

    private val exemptFromRelationshipGraphBan = emptySet<String>()

    private val bannedTerms = listOf(
        "어절", "노드", "엣지", "연결 수", "재료", "LLM", "Bigram", "페르소나",
        "청크", "묶음", "동기화", "컴파일", "언어 지문", "Typing DNA", "그래프 강화", "보강"
    )
    private val relationshipGraphTerm = "관계 그래프"

    private fun findResDir(): File {
        var dir = File(".").absoluteFile.normalize()
        repeat(6) {
            val candidate = File(dir, "app/src/main/res")
            if (candidate.isDirectory) return candidate
            val self = File(dir, "src/main/res")
            if (self.isDirectory && dir.name == "app") return self
            dir = dir.parentFile ?: return@repeat
        }
        error("Could not locate app/src/main/res from working directory ${File(".").absolutePath}")
    }

    private fun readStrings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val result = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            result[name] = node.textContent
        }
        return result
    }

    @Test
    fun koreanUserFacingStringsContainNoDeveloperJargon() {
        val resDir = findResDir()
        val koStrings = readStrings(File(resDir, "values-ko/strings.xml"))

        val ids = userFacingStringIds + listOf("vault_storage_clear_dialog_message")
        val violations = mutableListOf<String>()
        ids.forEach { id ->
            val value = koStrings[id] ?: error("Missing values-ko/strings.xml entry for $id")
            bannedTerms.forEach { term ->
                if (value.contains(term)) violations += "$id contains banned term \"$term\": $value"
            }
            if (id !in exemptFromRelationshipGraphBan && value.contains(relationshipGraphTerm)) {
                violations += "$id contains banned term \"$relationshipGraphTerm\": $value"
            }
        }

        assertTrue("Banned developer terms found in user-facing strings:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun theClearDialogMessageNoLongerNamesTheRelationshipGraph() {
        val resDir = findResDir()
        val koStrings = readStrings(File(resDir, "values-ko/strings.xml"))

        val message = koStrings["vault_storage_clear_dialog_message"]
        assertTrue(message != null && !message.contains(relationshipGraphTerm))
        // Every other user-facing string must still avoid the term.
        userFacingStringIds.forEach { id ->
            val value = koStrings[id] ?: error("Missing values-ko/strings.xml entry for $id")
            assertFalse("$id should not name 관계 그래프: $value", value.contains(relationshipGraphTerm))
        }
    }
}

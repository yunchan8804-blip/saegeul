/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai.dashboard

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R

/** Developer info: every raw number, debug-build only, collapsed by default. */
internal class DeveloperSection(private val activity: Activity) {
    private val body: View = activity.findViewById(R.id.dev_section_body)
    private val toggleLabel: TextView = activity.findViewById(R.id.tv_dev_section_toggle)
    private var expanded = false

    fun bind() {
        activity.findViewById<View>(R.id.dev_section).visibility =
            if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.dev_section_header).setOnClickListener { toggle() }
        activity.findViewById<View>(R.id.gemma_dev_experiment_link).setOnClickListener {
            if (!BuildConfig.DEBUG) return@setOnClickListener
            try {
                activity.startActivity(
                    Intent()
                        .setClassName(activity, "org.fcitx.fcitx5.android.debug.gemma.GemmaExperimentActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.gemma_vault_action_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggle() {
        expanded = !expanded
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        toggleLabel.setText(
            if (expanded) R.string.vault_dev_section_collapse else R.string.vault_dev_section_expand
        )
    }
}

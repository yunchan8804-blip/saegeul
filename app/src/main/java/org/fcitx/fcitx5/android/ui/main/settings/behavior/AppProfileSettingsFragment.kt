/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.BufferedInputTransport
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulLayout
import org.fcitx.fcitx5.android.input.profile.AppFeaturePolicy
import org.fcitx.fcitx5.android.input.profile.AppKeyboardProfile
import org.fcitx.fcitx5.android.input.profile.AppKeyboardProfileStore
import org.fcitx.fcitx5.android.input.profile.AppToolbarVisibility
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import splitties.dimensions.dp

class AppProfileSettingsFragment : PaddingPreferenceFragment() {
    private val store by lazy { AppKeyboardProfileStore(requireContext()) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) = rebuild()

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.app_profiles) {
                addPreference(
                    R.string.app_profile_add,
                    R.string.app_profiles_summary,
                    R.drawable.ic_baseline_plus_24
                ) {
                    showProfileDialog(null)
                }
                store.profiles().forEach { profile ->
                    addPreference(Preference(ctx).apply {
                        title = appLabel(profile.packageName)
                        summary = profileSummary(profile)
                        setOnPreferenceClickListener {
                            showProfileDialog(profile)
                            true
                        }
                    })
                }
            }
            addCategory(R.string.app_profile_privacy) {
                addPreference(
                    R.string.app_profile_privacy,
                    R.string.app_profile_privacy_summary
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun appLabel(packageName: String): String = runCatching {
        val info = requireContext().packageManager.getApplicationInfo(packageName, 0)
        "${requireContext().packageManager.getApplicationLabel(info)} · $packageName"
    }.getOrDefault(packageName)

    private fun profileSummary(profile: AppKeyboardProfile): String = buildList {
        profile.mobileHangulLayout?.let {
            add(getString(R.string.app_profile_summary_layout, getString(it.stringRes)))
        }
        profile.themeName?.let { add(getString(R.string.app_profile_summary_theme, it)) }
        if (profile.toolbarVisibility != AppToolbarVisibility.Inherit) {
            add(getString(R.string.app_profile_summary_toolbar, toolbarLabel(profile.toolbarVisibility)))
        }
        profile.bufferedInputTransport?.let {
            add(getString(R.string.app_profile_summary_transport, getString(it.stringRes)))
        }
        if (profile.networkPolicy != AppFeaturePolicy.Inherit) {
            add(getString(R.string.app_profile_summary_network, policyLabel(profile.networkPolicy)))
        }
        if (profile.aiPolicy != AppFeaturePolicy.Inherit) {
            add(getString(R.string.app_profile_summary_ai, policyLabel(profile.aiPolicy)))
        }
    }.joinToString(" · ").ifEmpty { getString(R.string.app_profile_uses_global) }

    private fun showProfileDialog(existing: AppKeyboardProfile?) {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = ctx.dp(20)
            setPadding(horizontal, ctx.dp(8), horizontal, ctx.dp(8))
        }
        val packageField = EditText(ctx).apply {
            setHint(R.string.app_profile_package_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.packageName.orEmpty())
            isEnabled = existing == null
            maxLines = 1
            content.addView(this, matchWrapParams())
        }

        fun <T> choice(
            title: Int,
            values: List<T>,
            labels: List<String>,
            selected: T
        ): Pair<Spinner, List<T>> {
            content.addView(TextView(ctx).apply {
                setText(title)
                setPadding(0, ctx.dp(12), 0, 0)
            }, matchWrapParams())
            val spinner = Spinner(ctx).apply {
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection(values.indexOf(selected).coerceAtLeast(0))
                content.addView(this, matchWrapParams())
            }
            return spinner to values
        }

        val layouts: List<MobileHangulLayout?> = listOf(null) + MobileHangulLayout.entries
        val (layoutSpinner, layoutValues) = choice(
            R.string.app_profile_layout,
            layouts,
            listOf(getString(R.string.app_profile_inherit)) +
                MobileHangulLayout.entries.map { getString(it.stringRes) },
            existing?.mobileHangulLayout
        )
        val themes = listOf<String?>(null) + ThemeManager.getAllThemes().map { it.name }.distinct()
        val (themeSpinner, themeValues) = choice(
            R.string.app_profile_theme,
            themes,
            listOf(getString(R.string.app_profile_inherit)) + themes.drop(1).map { it.orEmpty() },
            existing?.themeName
        )
        val toolbarValues = AppToolbarVisibility.entries
        val (toolbarSpinner, _) = choice(
            R.string.app_profile_toolbar,
            toolbarValues,
            toolbarValues.map(::toolbarLabel),
            existing?.toolbarVisibility ?: AppToolbarVisibility.Inherit
        )
        val transports: List<BufferedInputTransport?> = listOf(null) + BufferedInputTransport.entries
        val (transportSpinner, transportValues) = choice(
            R.string.app_profile_transport,
            transports,
            listOf(getString(R.string.app_profile_inherit)) +
                BufferedInputTransport.entries.map { getString(it.stringRes) },
            existing?.bufferedInputTransport
        )
        val policyValues = AppFeaturePolicy.entries
        val (networkSpinner, _) = choice(
            R.string.app_profile_network,
            policyValues,
            policyValues.map(::policyLabel),
            existing?.networkPolicy ?: AppFeaturePolicy.Inherit
        )
        val (aiSpinner, _) = choice(
            R.string.app_profile_ai,
            policyValues,
            policyValues.map(::policyLabel),
            existing?.aiPolicy ?: AppFeaturePolicy.Inherit
        )

        val scroll = ScrollView(ctx).apply { addView(content) }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) R.string.app_profile_add else R.string.app_profile_edit)
            .setMessage(R.string.app_profile_dialog_summary)
            .setView(scroll)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (existing != null) setNeutralButton(R.string.delete, null)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val profile = AppKeyboardProfile(
                    packageName = packageField.text.toString(),
                    mobileHangulLayout = layoutValues[layoutSpinner.selectedItemPosition],
                    themeName = themeValues[themeSpinner.selectedItemPosition],
                    toolbarVisibility = toolbarValues[toolbarSpinner.selectedItemPosition],
                    bufferedInputTransport = transportValues[transportSpinner.selectedItemPosition],
                    networkPolicy = policyValues[networkSpinner.selectedItemPosition],
                    aiPolicy = policyValues[aiSpinner.selectedItemPosition]
                )
                runCatching { store.upsert(profile) }
                    .onSuccess {
                        dialog.dismiss()
                        rebuild()
                    }
                    .onFailure {
                        packageField.error = getString(R.string.app_profile_invalid_package)
                    }
            }
            existing?.let { profile ->
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    store.remove(profile.packageName)
                    dialog.dismiss()
                    rebuild()
                }
            }
        }
        dialog.show()
    }

    private fun toolbarLabel(value: AppToolbarVisibility): String = getString(
        when (value) {
            AppToolbarVisibility.Inherit -> R.string.app_profile_inherit
            AppToolbarVisibility.Expanded -> R.string.app_profile_toolbar_expanded
            AppToolbarVisibility.Collapsed -> R.string.app_profile_toolbar_collapsed
        }
    )

    private fun policyLabel(value: AppFeaturePolicy): String = getString(
        when (value) {
            AppFeaturePolicy.Inherit -> R.string.app_profile_inherit
            AppFeaturePolicy.Allow -> R.string.app_profile_allow
            AppFeaturePolicy.Block -> R.string.app_profile_block
        }
    )

    private fun matchWrapParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}

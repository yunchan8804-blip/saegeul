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
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.personaldictionary.PersonalDictionaryStore
import org.fcitx.fcitx5.android.data.personaldictionary.PersonalWord
import org.fcitx.fcitx5.android.data.personaldictionary.PersonalWordCategory
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import splitties.dimensions.dp

class PersonalDictionaryFragment : PaddingPreferenceFragment() {
    private val store by lazy { PersonalDictionaryStore(requireContext()) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) = rebuild()

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        val ctx = requireContext()
        val dictionary = store.load()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.personal_dictionary) {
                addPreference(SwitchPreferenceCompat(ctx).apply {
                    title = getString(R.string.personal_dictionary_enabled)
                    summary = getString(R.string.personal_dictionary_enabled_summary)
                    isChecked = dictionary.enabled
                    setOnPreferenceChangeListener { _, newValue ->
                        runCatching { store.setEnabled(newValue as Boolean) }.isSuccess
                    }
                })
                addPreference(
                    R.string.personal_dictionary_add,
                    getString(R.string.personal_dictionary_count, dictionary.words.size),
                    R.drawable.ic_baseline_plus_24
                ) {
                    showAddDialog()
                }
                dictionary.words.forEach { word ->
                    addPreference(Preference(ctx).apply {
                        title = word.value
                        summary = categoryLabel(word.category)
                        setOnPreferenceClickListener {
                            showDeleteDialog(word)
                            true
                        }
                    })
                }
            }
            addCategory(R.string.personal_dictionary_privacy) {
                addPreference(
                    R.string.personal_dictionary_privacy,
                    R.string.personal_dictionary_privacy_summary
                )
            }
        }
    }

    private fun showAddDialog() {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = ctx.dp(20)
            setPadding(horizontal, ctx.dp(8), horizontal, ctx.dp(8))
        }
        content.addView(TextView(ctx).apply {
            setText(R.string.personal_dictionary_category)
        }, matchWrapParams())
        val categories = PersonalWordCategory.entries
        val categorySpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(
                ctx,
                android.R.layout.simple_spinner_dropdown_item,
                categories.map(::categoryLabel)
            )
        }
        content.addView(categorySpinner, matchWrapParams())
        val wordField = EditText(ctx).apply {
            setHint(R.string.personal_dictionary_word_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 1
        }
        content.addView(wordField, matchWrapParams())

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.personal_dictionary_add)
            .setMessage(R.string.personal_dictionary_add_summary)
            .setView(content)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val word = PersonalWord(
                    categories[categorySpinner.selectedItemPosition],
                    wordField.text.toString()
                )
                runCatching { store.upsert(word) }
                    .onSuccess {
                        dialog.dismiss()
                        rebuild()
                    }
                    .onFailure {
                        wordField.error = getString(R.string.personal_dictionary_invalid_word)
                    }
            }
        }
        dialog.show()
    }

    private fun showDeleteDialog(word: PersonalWord) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.personal_dictionary_delete)
            .setMessage(getString(R.string.personal_dictionary_delete_confirm, word.value))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.remove(word.value)
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun categoryLabel(category: PersonalWordCategory): String = getString(
        when (category) {
            PersonalWordCategory.Name -> R.string.personal_dictionary_category_name
            PersonalWordCategory.Company -> R.string.personal_dictionary_category_company
            PersonalWordCategory.TechnicalTerm -> R.string.personal_dictionary_category_term
        }
    )

    private fun matchWrapParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}

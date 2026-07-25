/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import timber.log.Timber

class KoreanSearchWindow : InputWindow.ExtendedInputWindow<KoreanSearchWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val repository = KoreanSearchRepository()
    private lateinit var target: KoreanSearchEditorTarget
    private lateinit var ui: KoreanSearchUi
    private lateinit var adapter: KoreanSearchAdapter
    private var currentQuery = ""
    private var cachedEntries: List<KoreanSearchEntry>? = null
    private var searchJob: Job? = null
    private var actionLocked = false

    override val title: String by lazy { context.getString(R.string.korean_search) }

    override fun onCreateView(): View {
        ui = KoreanSearchUi(context, theme)
        adapter = KoreanSearchAdapter(context, theme, ::insert)
        ui.recyclerView.layoutManager = LinearLayoutManager(context)
        ui.recyclerView.adapter = adapter
        ui.recyclerView.addItemDecoration(KoreanSearchItemSpacing(context))
        ui.onQueryClick = ::showQueryDialog
        ui.onInitial = { initial -> search(currentQuery + initial) }
        ui.onBackspace = {
            if (currentQuery.isNotEmpty()) search(currentQuery.dropLast(1))
        }
        ui.onClear = { search("") }
        return ui.root
    }

    override fun onAttached() {
        val info = service.currentInputEditorInfo
        val selection = service.currentInputSelection
        target = KoreanSearchEditorTarget(
            packageName = info.packageName,
            fieldId = info.fieldId,
            inputType = info.inputType,
            selectionStart = selection.start,
            selectionEnd = selection.end
        )
        if (!service.allowsTextInspectionFeatures()) {
            ui.showMessage(context.getString(R.string.korean_search_private_disabled))
            return
        }
        if (currentQuery.isBlank()) ui.showPrompt() else search(currentQuery)
    }

    override fun onDetached() {
        searchJob?.cancel()
        actionLocked = false
    }

    private fun showQueryDialog() {
        if (!service.allowsTextInspectionFeatures()) return
        val editText = EditText(context).apply {
            setText(currentQuery)
            setSelection(text.length)
            hint = context.getString(R.string.korean_search_query_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.korean_search_query_title)
            .setView(editText)
            .setPositiveButton(R.string.korean_search_action) { _, _ ->
                search(editText.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        service.showDialog(dialog)
    }

    private fun search(query: String) {
        currentQuery = query.trim()
        ui.setQuery(currentQuery)
        adapter.submit(emptyList())
        if (currentQuery.isBlank()) {
            ui.showPrompt()
            return
        }
        if (!service.allowsTextInspectionFeatures()) {
            ui.showMessage(context.getString(R.string.korean_search_private_disabled))
            return
        }
        searchJob?.cancel()
        ui.showLoading()
        searchJob = service.lifecycleScope.launch {
            try {
                val entries = cachedEntries ?: repository.loadEntries().also { cachedEntries = it }
                val results = KoreanUnifiedSearch.search(currentQuery, entries)
                adapter.submit(results)
                ui.showResults(results.isNotEmpty())
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.w(exception, "Korean unified search failed")
                ui.showMessage(context.getString(R.string.korean_search_failed))
            }
        }
    }

    private fun insert(result: KoreanSearchResult) {
        if (actionLocked) return
        actionLocked = true
        adapter.setActionLocked(true)
        val sameEditor = service.allowsTextInspectionFeatures() && service.matchesCurrentEditor(
            target.packageName,
            target.fieldId,
            target.inputType,
            target.selectionStart,
            target.selectionEnd
        )
        if (!sameEditor) {
            actionLocked = false
            adapter.setActionLocked(false)
            ui.showActionStatus(context.getString(R.string.korean_search_editor_changed), true)
            return
        }
        val committed = service.commitText(result.entry.commitText)
        actionLocked = false
        adapter.setActionLocked(false)
        if (committed) {
            windowManager.attachWindow(KeyboardWindow)
        } else {
            ui.showActionStatus(context.getString(R.string.korean_search_insert_failed), true)
        }
    }
}

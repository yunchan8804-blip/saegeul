/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
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
import org.fcitx.fcitx5.android.input.emotion.EmotionCommitGate
import org.fcitx.fcitx5.android.input.emotion.EmotionCommitResult
import org.fcitx.fcitx5.android.input.emotion.ExplicitEmotionSearch
import org.fcitx.fcitx5.android.input.emotion.ExplicitEmotionSearchOutcome
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.context.KoreanParticleWindow
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
    private val dictionaryRepository by lazy { KoreanDictionaryRepository(context) }
    private lateinit var target: KoreanSearchEditorTarget
    private lateinit var ui: KoreanSearchUi
    private lateinit var adapter: KoreanSearchAdapter
    private lateinit var dictionaryAdapter: KoreanDictionaryAdapter
    private var currentQuery = ""
    private var cachedEntries: List<KoreanSearchEntry>? = null
    private var searchJob: Job? = null
    private var actionLocked = false
    private var mode = Mode.Unified
    private val emotionCommitGate = EmotionCommitGate()

    private enum class Mode { Unified, Emotion, Dictionary }

    override val title: String by lazy { context.getString(R.string.korean_search) }

    override fun onCreateView(): View {
        ui = KoreanSearchUi(context, theme)
        adapter = KoreanSearchAdapter(context, theme, ::insert)
        dictionaryAdapter = KoreanDictionaryAdapter(context, theme, ::openDictionarySource)
        ui.recyclerView.layoutManager = LinearLayoutManager(context)
        ui.recyclerView.adapter = adapter
        ui.recyclerView.addItemDecoration(KoreanSearchItemSpacing(context))
        ui.onQueryClick = ::showQueryDialog
        ui.onParticleSuggestions = { windowManager.attachWindow(KoreanParticleWindow()) }
        ui.onDictionary = ::activateDictionary
        ui.onEmotionQuery = ::searchEmotion
        ui.onInitial = { initial -> search(currentQuery + initial) }
        ui.onBackspace = {
            if (currentQuery.isNotEmpty()) search(currentQuery.dropLast(1))
        }
        ui.onClear = { search("") }
        return ui.root
    }

    override fun onAttached() {
        emotionCommitGate.reset()
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
        when (mode) {
            Mode.Unified -> if (currentQuery.isBlank()) ui.showPrompt() else search(currentQuery)
            Mode.Emotion -> searchEmotion(currentQuery)
            Mode.Dictionary -> searchDictionary(currentQuery)
        }
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
            .setTitle(
                if (mode == Mode.Dictionary) R.string.korean_dictionary_query_title
                else R.string.korean_search_query_title
            )
            .setView(editText)
            .setPositiveButton(R.string.korean_search_action) { _, _ ->
                if (mode == Mode.Dictionary) {
                    searchDictionary(editText.text.toString())
                } else {
                    search(editText.text.toString())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        service.showDialog(dialog)
    }

    private fun search(query: String) {
        mode = Mode.Unified
        currentQuery = query.trim()
        ui.setContentMode()
        ui.setQuery(currentQuery)
        ui.recyclerView.adapter = adapter
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

    private fun searchEmotion(explicitQuery: String) {
        searchJob?.cancel()
        mode = Mode.Emotion
        currentQuery = explicitQuery.trim()
        ui.setContentMode(emotion = true)
        ui.setQuery(currentQuery)
        ui.recyclerView.adapter = adapter
        adapter.submit(emptyList())
        when (val outcome = ExplicitEmotionSearch.search(
            allowed = service.allowsTextInspectionFeatures(),
            explicitQuery = currentQuery
        )) {
            ExplicitEmotionSearchOutcome.Blocked ->
                ui.showMessage(context.getString(R.string.korean_search_private_disabled))
            is ExplicitEmotionSearchOutcome.Results -> {
                adapter.submit(outcome.items)
                ui.showResults(outcome.items.isNotEmpty())
            }
        }
    }

    private fun activateDictionary() {
        if (!service.allowsTextInspectionFeatures()) {
            ui.showMessage(context.getString(R.string.korean_search_private_disabled))
            return
        }
        searchDictionary(service.captureKoreanDictionaryQuery().orEmpty())
    }

    private fun searchDictionary(query: String) {
        searchJob?.cancel()
        mode = Mode.Dictionary
        currentQuery = query.trim()
        ui.setContentMode(dictionary = true)
        ui.setQuery(currentQuery)
        ui.recyclerView.adapter = dictionaryAdapter
        dictionaryAdapter.submit(emptyList())
        if (currentQuery.isBlank()) {
            ui.showMessage(context.getString(R.string.korean_dictionary_prompt))
            return
        }
        if (!service.allowsTextInspectionFeatures()) {
            ui.showMessage(context.getString(R.string.korean_search_private_disabled))
            return
        }
        ui.showLoading()
        searchJob = service.lifecycleScope.launch {
            try {
                val entries = dictionaryRepository.lookup(currentQuery)
                dictionaryAdapter.submit(entries)
                if (entries.isEmpty()) {
                    ui.showMessage(context.getString(R.string.korean_dictionary_no_results))
                } else {
                    ui.showResults(true)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.w(exception, "Korean dictionary lookup failed")
                ui.showMessage(context.getString(R.string.korean_dictionary_failed))
            }
        }
    }

    private fun openDictionarySource(entry: KoreanDictionaryEntry) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(entry.sourceUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                Timber.w(it, "Failed to open Korean dictionary source")
                ui.showActionStatus(
                    context.getString(R.string.korean_dictionary_source_failed),
                    true
                )
            }
    }

    private fun insert(result: KoreanSearchResult) {
        if (actionLocked) return
        actionLocked = true
        adapter.setActionLocked(true)
        val allowed = service.allowsTextInspectionFeatures()
        val sameEditor = allowed && service.matchesCurrentEditor(
            target.packageName,
            target.fieldId,
            target.inputType,
            target.selectionStart,
            target.selectionEnd
        )
        if (result.entry.source == KoreanSearchSource.Emotion) {
            val outcome = emotionCommitGate.commit(allowed, sameEditor) {
                service.commitText(result.entry.commitText)
            }
            actionLocked = false
            adapter.setActionLocked(false)
            when (outcome) {
                EmotionCommitResult.Success -> windowManager.attachWindow(KeyboardWindow)
                EmotionCommitResult.Blocked -> ui.showActionStatus(
                    context.getString(R.string.korean_search_private_disabled), true
                )
                EmotionCommitResult.StaleEditor -> ui.showActionStatus(
                    context.getString(R.string.korean_search_editor_changed), true
                )
                EmotionCommitResult.AlreadyCommitted,
                EmotionCommitResult.Failed -> ui.showActionStatus(
                    context.getString(R.string.korean_search_insert_failed), true
                )
            }
            return
        }
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

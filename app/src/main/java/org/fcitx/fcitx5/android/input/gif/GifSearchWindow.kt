/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

class GifSearchWindow : InputWindow.ExtendedInputWindow<GifSearchWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val provider: GifProvider = NotoAnimatedEmojiProvider()
    private val searchGate = GifSearchGate(provider)
    private val cache by lazy { GifCache(context) }
    private val committer by lazy { RichContentCommitter(service) }
    private lateinit var target: GifEditorTarget
    private var currentQuery = ""
    private var queryState = GifSearchQueryState()
    private var searchJob: Job? = null
    private var actionJob: Job? = null
    private var retryAction: (() -> Unit)? = null

    private lateinit var adapter: GifResultAdapter
    private lateinit var ui: GifSearchUi

    override val title: String by lazy { context.getString(R.string.gif_search) }

    override fun onCreateView(): View {
        ui = GifSearchUi(context, theme)
        adapter = GifResultAdapter(
            context,
            theme,
            onLink = ::insertLink,
            onAttach = ::attachGif,
            onSelectionChanged = { ui.clearActionStatus() }
        )
        ui.recyclerView.layoutManager = GridLayoutManager(context, 2)
        ui.recyclerView.adapter = adapter
        ui.recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(view: RecyclerView, event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP &&
                    view.findChildViewUnder(event.x, event.y) == null
                ) {
                    adapter.clearSelection()
                }
                return false
            }
        })
        ui.setProviderLabel(context.getString(R.string.gif_powered_by_noto))
        ui.onQueryClick = ::beginQueryEditing
        ui.onKeyword = { query ->
            queryState = GifSearchQueryState(query)
            search(query)
        }
        ui.onQueryCharacter = { key ->
            queryState.type(key)
            ui.renderQueryEditor(queryState)
        }
        ui.onQueryBackspace = {
            queryState.backspace()
            ui.renderQueryEditor(queryState)
        }
        ui.onQuerySpace = {
            queryState.space()
            ui.renderQueryEditor(queryState)
        }
        ui.onQueryClear = {
            queryState.clear()
            ui.renderQueryEditor(queryState)
        }
        ui.onQueryLanguage = {
            queryState.toggleLanguage()
            ui.renderQueryEditor(queryState)
        }
        ui.onQueryShift = {
            queryState.toggleShift()
            ui.renderQueryEditor(queryState)
        }
        ui.onQuerySubmit = {
            val query = queryState.submit()
            search(query)
        }
        ui.onRetry = { retryAction?.invoke() }
        return ui.root
    }

    override fun onAttached() {
        val info = service.currentInputEditorInfo
        val selection = service.currentInputSelection
        target = GifEditorTarget.from(info, selection.start, selection.end)
        cache.cleanupExpired()
        if (!service.allowsNetworkInputFeatures()) {
            retryAction = null
            ui.showBlockingMessage(context.getString(R.string.gif_private_disabled))
            return
        }
        adapter.setAttachSupported(committer.supportsGif(info))
        search(currentQuery)
    }

    override fun onDetached() {
        searchJob?.cancel()
        actionJob?.cancel()
        adapter.onDetached()
    }

    private fun search(query: String) {
        searchJob?.cancel()
        currentQuery = query.trim()
        queryState = GifSearchQueryState(currentQuery)
        ui.hideQueryEditor()
        ui.setQuery(currentQuery)
        val allowed = service.allowsNetworkInputFeatures()
        if (allowed) ui.showLoading()
        retryAction = { search(currentQuery) }
        searchJob = service.lifecycleScope.launch {
            try {
                when (val outcome = searchGate.search(allowed, currentQuery)) {
                    GifSearchOutcome.Blocked -> {
                        retryAction = null
                        ui.showBlockingMessage(context.getString(R.string.gif_private_disabled))
                    }
                    is GifSearchOutcome.Results -> {
                        adapter.submit(outcome.items)
                        adapter.setAttachSupported(committer.supportsGif())
                        ui.showResults(outcome.items.isNotEmpty())
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.w(exception, "GIF search failed")
                ui.showBlockingMessage(context.getString(R.string.gif_search_failed), retry = true)
            }
        }
    }

    private fun beginQueryEditing() {
        if (!service.allowsNetworkInputFeatures()) {
            ui.showBlockingMessage(context.getString(R.string.gif_private_disabled))
            return
        }
        searchJob?.cancel()
        queryState = GifSearchQueryState(currentQuery)
        ui.showQueryEditor(queryState)
    }

    private fun insertLink(result: GifResult) {
        if (actionJob?.isActive == true) return
        adapter.setActionLocked(true)
        if (!service.matchesCurrentEditor(
                target.packageName,
                target.fieldId,
                target.inputType,
                target.selectionStart,
                target.selectionEnd
            )
        ) {
            adapter.setActionLocked(false)
            ui.showActionStatus(context.getString(R.string.gif_editor_changed), isError = true)
            return
        }
        // Exactly one direct commit. Failure never triggers a second attempt or attachment fallback.
        val committed = service.commitText(result.canonicalUrl)
        adapter.setActionLocked(false)
        if (committed) {
            windowManager.attachWindow(KeyboardWindow)
        } else {
            ui.showActionStatus(context.getString(R.string.gif_link_failed), isError = true)
        }
    }

    private fun attachGif(result: GifResult) {
        if (actionJob?.isActive == true) return
        if (!committer.supportsGif()) {
            ui.showActionStatus(context.getString(R.string.gif_attachment_unsupported), isError = true)
            return
        }
        adapter.setActionLocked(true)
        ui.showActionStatus(context.getString(R.string.gif_downloading))
        retryAction = { attachGif(result) }
        actionJob = service.lifecycleScope.launch {
            try {
                val file = cache.getOrDownload(result)
                when (committer.commit(result, file, target)) {
                    GifCommitResult.Success -> {
                        Toast.makeText(context, R.string.gif_attached, Toast.LENGTH_SHORT).show()
                        windowManager.attachWindow(KeyboardWindow)
                    }
                    GifCommitResult.Unsupported -> ui.showActionStatus(
                        context.getString(R.string.gif_attachment_unsupported), true
                    )
                    GifCommitResult.StaleEditor -> ui.showActionStatus(
                        context.getString(R.string.gif_editor_changed), true
                    )
                    GifCommitResult.SensitiveEditor -> ui.showActionStatus(
                        context.getString(R.string.gif_private_disabled), true
                    )
                    GifCommitResult.Failed -> ui.showActionStatus(
                        context.getString(R.string.gif_attach_failed), true
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.w(exception, "GIF attachment failed without URL fallback")
                ui.showActionStatus(context.getString(R.string.gif_attach_failed), isError = true)
            } finally {
                adapter.setActionLocked(false)
            }
        }
    }
}

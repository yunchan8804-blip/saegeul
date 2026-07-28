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
import org.fcitx.fcitx5.android.input.ai.AiSettingsNavigator
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import timber.log.Timber

class GifSearchWindow : InputWindow.ExtendedInputWindow<GifSearchWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val inputView by manager.inputView()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val effectiveProvider: EffectiveGifProvider by lazy { GifProviderResolver.resolve(context) }
    private val providerPresentation by lazy {
        GifProviderPresentationPolicy.forProvider(effectiveProvider.kind)
    }
    private val provider: GifProvider by lazy { effectiveProvider.provider }
    private val searchGate by lazy { GifSearchGate(provider) }
    private val cache by lazy { GifCache(context) }
    private val committer by lazy { RichContentCommitter(service) }
    private val giphyAnalytics by lazy { GiphyAnalyticsTracker(context) }
    private lateinit var target: GifEditorTarget
    private var currentQuery = ""
    private var pendingPromptQuery: String? = null
    private var searchJob: Job? = null
    private var actionJob: Job? = null
    private var retryAction: (() -> Unit)? = null
    private var currentPage = 0
    private var hasNextPage = false
    private var searchGeneration = 0L

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
            onSelectionChanged = { ui.clearActionStatus() },
            onVisible = { result -> trackGiphyAction(result, GifAnalyticsEvent.Load) },
            onCardTap = { result -> trackGiphyAction(result, GifAnalyticsEvent.Click) }
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
        ui.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || searchJob?.isActive == true || !hasNextPage) return
                val layout = recyclerView.layoutManager as? GridLayoutManager ?: return
                if (layout.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_MORE_THRESHOLD) {
                    loadNextPage()
                }
            }
        })
        ui.setProviderLabel(
            when (effectiveProvider.kind) {
                GifProviderKind.Klipy -> provider.displayName
                GifProviderKind.AnimatedNoto ->
                    context.getString(R.string.gif_powered_by_noto)
                GifProviderKind.Giphy,
                GifProviderKind.GiphyUnavailable -> GiphyGifProvider.POWERED_BY_GIPHY
            }
        )
        ui.setQuickSuggestions(providerPresentation.quickSuggestions)
        ui.setMoreGifSettingsVisible(providerPresentation.showsMoreGifSettings)
        ui.onQueryClick = ::beginQueryEditing
        ui.onMoreGifSettings = ::openMoreGifSettings
        ui.onKeyword = ::search
        ui.onRetry = { retryAction?.invoke() }
        return ui.root
    }

    override fun onAttached() {
        val info = service.currentInputEditorInfo
        val selection = service.currentInputSelection
        target = GifEditorTarget.from(
            info,
            selection.start,
            selection.end,
            service.currentInputSessionEpoch
        )
        // Submission crosses a window detach/attach boundary. Consume it before every early
        // return so a query typed under an old policy can never run later after a policy change.
        val query = pendingPromptQuery ?: currentQuery
        pendingPromptQuery = null
        cache.cleanupExpired()
        val networkInputAllowed = service.allowsNetworkInputFeatures()
        ui.setMoreGifSettingsVisible(
            providerPresentation.showsMoreGifSettings && networkInputAllowed
        )
        if (!networkInputAllowed) {
            retryAction = null
            ui.showBlockingMessage(context.getString(R.string.gif_private_disabled))
            return
        }
        if (!effectiveProvider.networkReady) {
            retryAction = null
            ui.showBlockingMessage(giphyUnavailableMessage())
            return
        }
        adapter.setAttachSupported(committer.supportsGif(info))
        search(query)
    }

    override fun onDetached() {
        searchGeneration++
        searchJob?.cancel()
        actionJob?.cancel()
        adapter.onDetached()
    }

    private fun search(query: String) {
        if (!effectiveProvider.networkReady) {
            retryAction = null
            ui.showBlockingMessage(giphyUnavailableMessage())
            return
        }
        searchJob?.cancel()
        currentQuery = GifSearchQueryPolicy.normalize(query, effectiveProvider.kind)
        currentPage = 0
        hasNextPage = false
        val generation = ++searchGeneration
        ui.setQuery(currentQuery)
        val allowed = service.allowsNetworkInputFeatures()
        if (allowed) ui.showLoading()
        retryAction = { search(currentQuery) }
        loadPage(page = 1, replace = true, generation = generation, allowed = allowed)
    }

    private fun loadNextPage() {
        if (!hasNextPage || searchJob?.isActive == true) return
        val page = currentPage + 1
        val generation = searchGeneration
        retryAction = { loadPage(page, replace = false, generation = generation) }
        loadPage(page, replace = false, generation = generation)
    }

    private fun loadPage(
        page: Int,
        replace: Boolean,
        generation: Long,
        allowed: Boolean = service.allowsNetworkInputFeatures()
    ) {
        searchJob = service.lifecycleScope.launch {
            try {
                when (val outcome = searchGate.search(allowed, currentQuery, page = page)) {
                    GifSearchOutcome.Blocked -> {
                        if (generation != searchGeneration) return@launch
                        retryAction = null
                        hasNextPage = false
                        ui.showBlockingMessage(context.getString(R.string.gif_private_disabled))
                    }
                    GifSearchOutcome.SafeSearchBlocked -> {
                        if (generation != searchGeneration) return@launch
                        retryAction = null
                        hasNextPage = false
                        adapter.submit(emptyList())
                        ui.showBlockingMessage(context.getString(R.string.gif_safe_search_blocked))
                    }
                    is GifSearchOutcome.Results -> {
                        if (generation != searchGeneration) return@launch
                        if (replace) {
                            adapter.submit(outcome.items)
                        } else {
                            adapter.append(outcome.items)
                            ui.clearActionStatus()
                        }
                        currentPage = page
                        hasNextPage = outcome.hasNext
                        adapter.setAttachSupported(committer.supportsGif())
                        if (replace) ui.showResults(outcome.items.isNotEmpty())
                        retryAction = if (hasNextPage) ({ loadNextPage() }) else null
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.w(exception, "GIF search failed")
                if (generation != searchGeneration) return@launch
                if (replace) {
                    ui.showBlockingMessage(context.getString(R.string.gif_search_failed), retry = true)
                } else {
                    ui.showActionStatus(context.getString(R.string.gif_search_failed), isError = true)
                }
            }
        }
    }

    private fun beginQueryEditing() {
        if (!service.allowsNetworkInputFeatures()) {
            ui.showBlockingMessage(context.getString(R.string.gif_private_disabled))
            return
        }
        if (!effectiveProvider.networkReady) {
            ui.showBlockingMessage(giphyUnavailableMessage())
            return
        }
        searchJob?.cancel()
        val started = inputView.beginGifSearchPromptInput(
            initialText = currentQuery,
            maxCharacters = GifSearchQueryPolicy.maxCharacters(effectiveProvider.kind),
            onSubmit = { query ->
                pendingPromptQuery = GifSearchQueryPolicy.normalize(query, effectiveProvider.kind)
                windowManager.attachWindow(this)
            },
            onCancel = {
                windowManager.attachWindow(this)
            }
        )
        if (!started) {
            // This can fail after the network policy gate above when the editor changed
            // or a composing session cannot be safely retained. Do not mislabel it as
            // a private-editor policy block.
            ui.showActionStatus(context.getString(R.string.gif_editor_changed), isError = true)
        }
    }

    private fun openMoreGifSettings() {
        if (!providerPresentation.showsMoreGifSettings || !service.allowsNetworkInputFeatures()) {
            return
        }
        // The CTA leaves the private fallback surface before opening the existing Privacy & AI
        // settings route, so the active editor returns to the normal keyboard afterwards.
        service.prepareForSettingsActivity()
        AiSettingsNavigator.open(context)
    }

    private fun insertLink(result: GifResult) {
        if (actionJob?.isActive == true) return
        val linkTarget = target
        adapter.setActionLocked(true)
        if (!service.matchesCurrentEditor(
                linkTarget.packageName,
                linkTarget.fieldId,
                linkTarget.inputType,
                linkTarget.selectionStart,
                linkTarget.selectionEnd,
                expectedInputSessionEpoch = linkTarget.inputSessionEpoch
            )
        ) {
            adapter.setActionLocked(false)
            ui.showActionStatus(context.getString(R.string.gif_editor_changed), isError = true)
            return
        }
        // Exactly one direct commit. Failure never triggers a second attempt or attachment fallback.
        val committed = service.commitToEditor(result.canonicalUrl)
        adapter.setActionLocked(false)
        if (committed) {
            trackGiphyAction(result, GifAnalyticsEvent.Send)
            windowManager.attachWindow(KeyboardWindow)
        } else {
            ui.showActionStatus(context.getString(R.string.gif_link_failed), isError = true)
        }
    }

    private fun attachGif(result: GifResult) {
        if (actionJob?.isActive == true) return
        // Keep an immutable target from the user action. A later window attach must not make a
        // delayed download eligible for the new editor/session merely by replacing [target].
        val attachmentTarget = target
        if (!result.attachmentDownloadAllowed) {
            ui.showActionStatus(
                context.getString(R.string.gif_giphy_attach_approval_required),
                isError = true
            )
            return
        }
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
                when (committer.commit(result, file, attachmentTarget)) {
                    GifCommitResult.Success -> {
                        trackGiphyAction(result, GifAnalyticsEvent.Send)
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

    private fun trackGiphyAction(result: GifResult, event: GifAnalyticsEvent) {
        if (effectiveProvider.kind != GifProviderKind.Giphy ||
            !service.allowsNetworkInputFeatures()
        ) return
        service.lifecycleScope.launch {
            runCatching { giphyAnalytics.track(result, event) }
                .onFailure { Timber.d(it, "GIPHY analytics event failed") }
        }
    }

    private fun giphyUnavailableMessage(): String = when (effectiveProvider.giphyCredentialState) {
        GiphyCredentialState.Missing ->
            context.getString(R.string.gif_giphy_unavailable_missing)
        GiphyCredentialState.KeyOnly ->
            context.getString(R.string.gif_giphy_unavailable_approval)
        GiphyCredentialState.Unreadable ->
            context.getString(R.string.gif_giphy_unavailable_unreadable)
        GiphyCredentialState.Ready ->
            context.getString(R.string.gif_giphy_unavailable_approval)
    }

    private companion object {
        const val LOAD_MORE_THRESHOLD = 6
    }
}

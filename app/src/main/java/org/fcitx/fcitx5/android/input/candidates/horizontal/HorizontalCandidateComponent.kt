/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.EditorPrivacyPolicy
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.FlexboxVerticalDecoration
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AlwaysFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AutoFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.NeverFillWidth
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.max

/**
 * Two-tiered horizontal candidate component for Saegeul Keyboard:
 * - Top Row (윗줄): Compact word-level candidates (단어단위 후보, 28dp, compact font)
 * - Bottom Row (아래줄): Full sentence AI recommendations (문장 후보, 30dp, chip style)
 * - Dynamically expands KawaiiBarComponent to 60dp when both rows are present,
 *   or stays at standard single row (48dp) when only one row is active.
 */
class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, LinearLayout>(), InputBroadcastReceiver {

    private val context by manager.context()
    private val service by manager.inputMethodService()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val twoRowCandidateBar by AppPrefs.getInstance().keyboard.twoRowCandidateBar
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
        }
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 1f

    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false

    private val _expandedCandidateOffset = MutableSharedFlow<Int>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()

    private fun refreshExpanded(childCount: Int) {
        _expandedCandidateOffset.tryEmit(childCount)
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to (wordAdapter.total == childCount)
        )
    }

    private var nativeCandidateCount = 0
    private var nativeCandidates: List<CandidateWord> = emptyList()
    private var currentCapFlags: CapabilityFlags = CapabilityFlags.DefaultFlags
    private var preeditEmpty: Boolean = true

    // Whether this component currently renders at least one candidate (word row + sentence row
    // combined). KawaiiBarComponent collapses its suggestion row when this is false.
    var hasVisibleCandidates: Boolean = false
        private set

    private fun setHasVisibleCandidates(value: Boolean) {
        if (hasVisibleCandidates == value) return
        hasVisibleCandidates = value
        bar.onCandidatesVisibilityChanged(value)
    }

    private fun mergeCandidates(
        nativeList: List<CandidateWord>,
        contextualWords: List<CandidateWord>,
        contextualSentences: List<CandidateWord> = emptyList()
    ): Array<CandidateWord> {
        val flags = if (currentCapFlags != CapabilityFlags.DefaultFlags) currentCapFlags else service.capabilityFlags
        val isEmail = EditorPrivacyPolicy.isEmailAddressField(service.currentInputEditorInfo, flags)
        val isUrl = EditorPrivacyPolicy.isUrlField(service.currentInputEditorInfo, flags)

        val merged = mutableListOf<CandidateWord>()
        if (isEmail || isUrl) {
            if (nativeList.isNotEmpty()) {
                // Actively composed word takes first priority
                merged.add(nativeList[0])
                // Followed by domain / TLD suggestion chips
                contextualWords.forEach { cw ->
                    if (merged.none { it.text == cw.text }) {
                        merged.add(cw)
                    }
                }
                // Followed by remaining native alternatives
                for (i in 1 until nativeList.size) {
                    val nc = nativeList[i]
                    if (merged.none { it.text == nc.text }) {
                        merged.add(nc)
                    }
                }
            } else {
                merged.addAll(contextualWords)
            }
            return merged.toTypedArray()
        }

        // In conversational text fields:
        // Typo corrections (badge "✏️") get top priority for immediate single-tap correction
        val typoWords = contextualWords.filter { it.comment.startsWith("✏️") }
        val typoSentences = contextualSentences.filter { it.comment.startsWith("✏️") }
        val otherWords = contextualWords.filter { !it.comment.startsWith("✏️") }
        val otherSentences = contextualSentences.filter { !it.comment.startsWith("✏️") }

        if (nativeList.isNotEmpty()) {
            merged.add(nativeList[0])
            typoWords.forEach { tw ->
                if (merged.none { it.text == tw.text }) {
                    merged.add(tw)
                }
            }
            typoSentences.forEach { ts ->
                if (merged.none { it.text == ts.text }) {
                    merged.add(ts)
                }
            }
            for (i in 1 until nativeList.size) {
                val nc = nativeList[i]
                if (merged.none { it.text == nc.text }) {
                    merged.add(nc)
                }
            }
            otherWords.forEach { cw ->
                if (merged.none { it.text == cw.text }) {
                    merged.add(cw)
                }
            }
            otherSentences.forEach { cs ->
                if (merged.none { it.text == cs.text }) {
                    merged.add(cs)
                }
            }
        } else {
            typoWords.forEach { tw ->
                if (merged.none { it.text == tw.text }) merged.add(tw)
            }
            typoSentences.forEach { ts ->
                if (merged.none { it.text == ts.text }) merged.add(ts)
            }
            otherWords.forEach { cw ->
                if (merged.none { it.text == cw.text }) merged.add(cw)
            }
            otherSentences.forEach { cs ->
                if (merged.none { it.text == cs.text }) merged.add(cs)
            }
        }
        return merged.toTypedArray()
    }

    // Primary adapter reference for external components (e.g. ExpandedCandidateWindow)
    val adapter: HorizontalCandidateViewAdapter get() = wordAdapter

    // Top Row: Word Candidates (단어 단위)
    val wordAdapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme, rowHeightDp = 28) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.itemView.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                    minWidth = layoutMinWidth
                    flexGrow = layoutFlexGrow
                    flexShrink = 0f
                }
                holder.itemView.setOnClickListener {
                    val nativeIdx = nativeCandidates.indexOfFirst { it.text == holder.candidate.text }
                    if (nativeIdx >= 0) {
                        service.selectCandidate(nativeIdx)
                    } else {
                        service.commitContextualSentence(holder.candidate.text)
                    }
                    // Feedback loop: If sentence candidates were offered on bottom row, record ignore decay
                    val offeredSentences = sentenceAdapter.candidates.map { it.text }
                    if (offeredSentences.isNotEmpty()) {
                        service.reinforcementTracker.onCandidatesIgnored(offeredSentences)
                    }
                    view.post {
                        refreshContextualCandidatesIfNeeded()
                    }
                }
                holder.itemView.setOnLongClickListener {
                    val nativeIdx = nativeCandidates.indexOfFirst { it.text == holder.candidate.text }
                    if (nativeIdx >= 0) {
                        inputView.showCandidateActionMenu(nativeIdx, holder.candidate.text, holder.ui.root)
                    }
                    true
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                super.onViewRecycled(holder)
            }
        }
    }

    // Bottom Row: Sentence Candidates (문장 단위)
    val sentenceAdapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme, rowHeightDp = 30) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.itemView.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                    minWidth = 0
                    flexGrow = 0f
                    flexShrink = 0f
                }
                holder.itemView.setOnClickListener {
                    service.commitContextualSentence(holder.candidate.text)
                    view.post {
                        refreshContextualCandidatesIfNeeded()
                    }
                }
                holder.itemView.setOnLongClickListener {
                    // Rejection / penalty on candidate long click
                    service.reinforcementTracker.onCandidateRejected(holder.candidate.text, heavyPenalty = true)
                    view.post {
                        refreshContextualCandidatesIfNeeded()
                    }
                    true
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                super.onViewRecycled(holder)
            }
        }
    }

    val wordLayoutManager: FlexboxLayoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            override fun canScrollVertically() = false
            override fun canScrollHorizontally() = true
            override fun onLayoutCompleted(state: RecyclerView.State) {
                super.onLayoutCompleted(state)
                val cnt = this.childCount
                if (secondLayoutPassNeeded) {
                    if (cnt < wordAdapter.candidates.size) {
                        if (secondLayoutPassDone) return
                        secondLayoutPassDone = true
                        for (i in 0 until cnt) {
                            getChildAt(i)!!.updateLayoutParams<LayoutParams> {
                                flexGrow = 1f
                            }
                        }
                    } else {
                        secondLayoutPassNeeded = false
                    }
                }
                refreshExpanded(cnt)
            }
        }
    }

    val sentenceLayoutManager: FlexboxLayoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            override fun canScrollVertically() = false
            override fun canScrollHorizontally() = true
        }
    }

    private val wordDividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = theme.dividerColor
        }
    }

    val wordRecyclerView: RecyclerView by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                this@HorizontalCandidateComponent.wordLayoutManager.flexWrap = FlexWrap.NOWRAP
                if (fillStyle == AutoFillWidth) {
                    val maxSpanCount = maxSpanCountPref.getValue()
                    layoutMinWidth = w / maxSpanCount - wordDividerDrawable.intrinsicWidth
                }
            }
        }.apply {
            id = R.id.candidate_view
            itemAnimator = null
            adapter = wordAdapter
            layoutManager = wordLayoutManager
            addItemDecoration(FlexboxVerticalDecoration(wordDividerDrawable))
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(context.dp(16))
        }
    }

    val sentenceRecyclerView: RecyclerView by lazy {
        RecyclerView(context).apply {
            id = View.generateViewId()
            itemAnimator = null
            adapter = sentenceAdapter
            layoutManager = sentenceLayoutManager
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(context.dp(16))
        }
    }

    private val hairlineDivider: View by lazy {
        View(context).apply {
            setBackgroundColor((theme.dividerColor and 0x00FFFFFF) or 0x40000000)
        }
    }

    override val view: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                wordRecyclerView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                hairlineDivider,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            )
            addView(
                sentenceRecyclerView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            )
        }
    }

    private fun renderCandidates(data: FcitxEvent.CandidateListEvent.Data? = null) {
        if (data != null) {
            nativeCandidates = data.candidates.toList()
            nativeCandidateCount = nativeCandidates.size
        }
        val contextualSentences = service.getContextualSentencePredictions(limit = 2)
        val contextualWords = service.getContextualWordPredictions(limit = 4)

        val flags = if (currentCapFlags != CapabilityFlags.DefaultFlags) currentCapFlags else service.capabilityFlags
        val isEmail = EditorPrivacyPolicy.isEmailAddressField(service.currentInputEditorInfo, flags)
        val isUrl = EditorPrivacyPolicy.isUrlField(service.currentInputEditorInfo, flags)

        val showTwoRows = twoRowCandidateBar && !isEmail && !isUrl

        if (showTwoRows) {
            val topCandidates = mergeCandidates(nativeCandidates, contextualWords, emptyList())
            val bottomCandidates = if (contextualSentences.isNotEmpty()) {
                contextualSentences.toTypedArray()
            } else {
                val overflowWords = contextualWords.drop(2).ifEmpty { nativeCandidates.drop(3) }
                overflowWords.toTypedArray()
            }

            wordAdapter.updateCandidates(topCandidates, topCandidates.size)
            wordAdapter.rowHeightDp = 28
            wordRecyclerView.visibility = if (topCandidates.isNotEmpty()) View.VISIBLE else View.GONE
            wordRecyclerView.updateLayoutParams<LinearLayout.LayoutParams> {
                height = context.dp(28)
                weight = 0f
            }

            sentenceAdapter.updateCandidates(bottomCandidates, bottomCandidates.size)
            sentenceAdapter.rowHeightDp = 30
            sentenceRecyclerView.visibility = View.VISIBLE
            sentenceRecyclerView.updateLayoutParams<LinearLayout.LayoutParams> {
                height = context.dp(30)
                weight = 0f
            }

            hairlineDivider.visibility = View.VISIBLE
            hairlineDivider.updateLayoutParams<LinearLayout.LayoutParams> {
                height = max(1, context.dp(1))
                weight = 0f
            }

            bar.isCandidateTwoRow = true
            // 유휴 상태(preedit 없음)에서 native 후보도 없다면, 문맥 후보만으로 CandidateEmpty를
            // false로 밀어붙이지 않는다(이중 안전장치). native 후보가 있는 경로는 그대로 둔다.
            if (!(preeditEmpty && nativeCandidates.isEmpty())) {
                bar.barStateMachine.push(
                    KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated,
                    KawaiiBarStateMachine.BooleanKey.CandidateEmpty to false
                )
            }
            applyFillStyle(topCandidates.size)
            setHasVisibleCandidates(topCandidates.isNotEmpty() || bottomCandidates.isNotEmpty())
        } else {
            val candidates = mergeCandidates(nativeCandidates, contextualWords, contextualSentences)
            wordAdapter.updateCandidates(candidates, candidates.size)
            sentenceAdapter.updateCandidates(emptyArray(), 0)

            val hasCandidates = candidates.isNotEmpty()
            if (hasCandidates) {
                wordRecyclerView.visibility = View.VISIBLE
                wordAdapter.rowHeightDp = KawaiiBarComponent.HEIGHT
                wordRecyclerView.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    weight = 0f
                }
                hairlineDivider.visibility = View.GONE
                sentenceRecyclerView.visibility = View.GONE
                bar.isCandidateTwoRow = false
                // 유휴 상태(preedit 없음)에서 native 후보도 없다면, 문맥 후보만으로 CandidateEmpty를
                // false로 밀어붙이지 않는다(이중 안전장치). native 후보가 있는 경로는 그대로 둔다.
                if (!(preeditEmpty && nativeCandidates.isEmpty())) {
                    bar.barStateMachine.push(
                        KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated,
                        KawaiiBarStateMachine.BooleanKey.CandidateEmpty to false
                    )
                }
                applyFillStyle(candidates.size)
                setHasVisibleCandidates(true)
            } else {
                wordRecyclerView.visibility = View.GONE
                hairlineDivider.visibility = View.GONE
                sentenceRecyclerView.visibility = View.GONE
                bar.isCandidateTwoRow = false
                setHasVisibleCandidates(false)
                bar.barStateMachine.push(
                    KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated,
                    KawaiiBarStateMachine.BooleanKey.CandidateEmpty to true
                )
                refreshExpanded(0)
            }
        }
    }

    private fun applyFillStyle(candidateCount: Int) {
        val maxSpanCount = maxSpanCountPref.getValue()
        when (fillStyle) {
            NeverFillWidth -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }
            AutoFillWidth -> {
                layoutMinWidth = view.width / maxSpanCount - wordDividerDrawable.intrinsicWidth
                layoutFlexGrow = if (candidateCount < maxSpanCount) 0f else 1f
                secondLayoutPassNeeded = candidateCount < maxSpanCount
            }
            AlwaysFillWidth -> {
                layoutMinWidth = view.width / maxSpanCount - wordDividerDrawable.intrinsicWidth
                layoutFlexGrow = 1f
                secondLayoutPassNeeded = false
            }
        }
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        renderCandidates(data)
    }

    private var pendingRefreshRunnable: Runnable? = null

    fun postRefreshContextualCandidates(delayMs: Long = 16L) {
        pendingRefreshRunnable?.let { view.removeCallbacks(it) }
        val runnable = Runnable {
            pendingRefreshRunnable = null
            refreshContextualCandidatesIfNeeded()
        }
        pendingRefreshRunnable = runnable
        view.postDelayed(runnable, delayMs)
    }

    fun refreshContextualCandidatesIfNeeded() {
        renderCandidates()
    }

    override fun onSelectionUpdate(start: Int, end: Int) {
        if (service.allowsTextInspectionFeatures()) {
            postRefreshContextualCandidates(0L)
        }
    }

    override fun onClientPreeditUpdate(data: org.fcitx.fcitx5.android.core.FormattedText) {
        if (service.allowsTextInspectionFeatures()) {
            postRefreshContextualCandidates(16L)
        }
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        if (service.allowsTextInspectionFeatures()) {
            postRefreshContextualCandidates(16L)
        }
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        preeditEmpty = empty
        if (service.allowsTextInspectionFeatures()) {
            postRefreshContextualCandidates(16L)
        }
    }

    override fun onStartInput(
        info: EditorInfo,
        capFlags: CapabilityFlags,
        restarting: Boolean
    ) {
        currentCapFlags = capFlags
        nativeCandidateCount = 0
        nativeCandidates = emptyList()
        view.post {
            refreshContextualCandidatesIfNeeded()
        }
    }
}

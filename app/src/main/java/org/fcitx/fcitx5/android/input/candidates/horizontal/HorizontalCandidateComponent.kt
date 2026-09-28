/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.lifecycle.lifecycleScope
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.EditorPrivacyPolicy
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.CandidateBarModePolicy
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.bar.KawaiiBarStateMachine
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
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
import org.fcitx.fcitx5.android.input.keyboard.FoldKeyboardProfileResolver
import org.fcitx.fcitx5.android.input.keyboard.KeyboardViewportReader
import org.fcitx.fcitx5.android.input.keyboard.ThumbSplitPreferences
import org.fcitx.fcitx5.android.input.FcitxInputMethodService.ContextualAppendSnapshot
import org.fcitx.fcitx5.android.input.FcitxInputMethodService.ContextualReplacementSnapshot
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAutomaticSuggestionWarmupState
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceFailureText
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionCoordinator
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionPolicy
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionSession
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsSession
import org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry
import org.fcitx.fcitx5.android.input.ai.learning.CollectionFeedbackEvent
import org.fcitx.fcitx5.android.input.context.KoreanParticleKind
import org.fcitx.fcitx5.android.input.context.KoreanParticleSuggester
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.max
import java.util.IdentityHashMap

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

    private val keyboardPrefs = AppPrefs.getInstance().keyboard
    private val fillStyle by keyboardPrefs.horizontalCandidateStyle
    private val twoRowCandidateBar by keyboardPrefs.twoRowCandidateBar
    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    // K5: gross orientation/split classification for the landscape single-row candidate bar. See
    // CandidateBarModePolicy.isHorizontalSingleRow and InputView.isThumbSplitActive (same resolver,
    // duplicated per that existing call-site pattern rather than shared, since it is a small pure
    // read of live config/prefs with no state to share).
    private fun isLandscapeOrientation(): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun isThumbSplitActive(): Boolean = FoldKeyboardProfileResolver.resolve(
        KeyboardViewportReader.read(context),
        ThumbSplitPreferences(
            compactEnabled = keyboardPrefs.splitKeyboardCompact.getValue(),
            expandedEnabled = keyboardPrefs.splitKeyboardExpanded.getValue(),
            compactPortraitGapDp = keyboardPrefs.splitKeyboardCompactGapPortrait.getValue(),
            compactLandscapeGapDp = keyboardPrefs.splitKeyboardCompactGapLandscape.getValue(),
            expandedPortraitGapDp = keyboardPrefs.splitKeyboardExpandedGapPortrait.getValue(),
            expandedLandscapeGapDp = keyboardPrefs.splitKeyboardExpandedGapLandscape.getValue()
        )
    ).enabled
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
    private var statusRowLogicalState: StatusRowLogicalState? = null
    private var statusRowNoCandidateHideRunnable: Runnable? = null
    private var statusRowNoCandidateHidden: Boolean = false
    private var activeCollectionFeedbackEvent: CollectionFeedbackEvent? = null
    private var collectionFeedbackHideRunnable: Runnable? = null
    private var lastObservedCoordinatorState: OnDeviceSuggestionCoordinator.State? = null
    private val contextualMetricsCandidates = IdentityHashMap<CandidateWord, PredictionMetricsSession.Candidate?>()
    private val contextualAppendSnapshots = IdentityHashMap<CandidateWord, ContextualAppendSnapshot?>()
    private val contextualReplacementSnapshots = IdentityHashMap<CandidateWord, ContextualReplacementSnapshot?>()
    private val automaticSuggestionCandidates =
        IdentityHashMap<CandidateWord, OnDeviceSuggestionCoordinator.Candidate>()
    private var metricsVisibilityPosted = false
    private val metricsGlobalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        scheduleCandidateVisibilityMeasurement()
    }
    private val metricsAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnGlobalLayoutListener(metricsGlobalLayoutListener)
            scheduleCandidateVisibilityMeasurement()
            subscribeCollectionFeedback()
        }

        override fun onViewDetachedFromWindow(v: View) {
            if (v.viewTreeObserver.isAlive) {
                v.viewTreeObserver.removeOnGlobalLayoutListener(metricsGlobalLayoutListener)
            }
            unsubscribeCollectionFeedback()
        }
    }

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

    private fun prependAutomaticCandidates(
        automatic: List<CandidateWord>,
        legacy: Array<CandidateWord>
    ): Array<CandidateWord> = (automatic + legacy.filterNot { legacyCandidate ->
        automatic.any { automaticCandidate -> automaticCandidate.text == legacyCandidate.text }
    }).toTypedArray()

    private fun getAutomaticCandidates(): AutomaticCandidates {
        automaticSuggestionCandidates.clear()
        val words = mutableListOf<CandidateWord>()
        val sentences = mutableListOf<CandidateWord>()
        service.getAutomaticSuggestionCandidates().forEach { candidate ->
            val word = CandidateWord(
                label = "",
                text = candidate.insertion,
                comment = if (candidate.origin == OnDeviceSuggestionSession.Origin.GENERATED) {
                    context.getString(R.string.gemma_automatic_generated)
                } else {
                    context.getString(R.string.gemma_automatic_continuation)
                }
            )
            automaticSuggestionCandidates[word] = candidate
            when (candidate.mode) {
                OnDeviceSuggestionPolicy.Mode.WORD -> words += word
                OnDeviceSuggestionPolicy.Mode.SENTENCE -> sentences += word
            }
        }
        return AutomaticCandidates(words, sentences)
    }

    private data class AutomaticCandidates(
        val words: List<CandidateWord>,
        val sentences: List<CandidateWord>
    ) {
        val isNotEmpty: Boolean
            get() = words.isNotEmpty() || sentences.isNotEmpty()

        val texts: Set<String>
            get() = (words + sentences).mapTo(mutableSetOf()) { it.text }
    }

    // Primary adapter reference for external components (e.g. ExpandedCandidateWindow)
    val adapter: HorizontalCandidateViewAdapter get() = wordAdapter

    // Top Row: Word Candidates (단어 단위)
    val wordAdapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme, rowHeightDp = 28) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                val boundCandidateWord = holder.candidate
                val automaticCandidate = automaticSuggestionCandidates[boundCandidateWord]
                holder.itemView.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                    minWidth = layoutMinWidth
                    flexGrow = layoutFlexGrow
                    flexShrink = 0f
                }
                holder.itemView.setOnClickListener {
                    if (automaticCandidate != null) {
                        if (holder.candidate === boundCandidateWord) {
                            service.commitAutomaticSuggestionCandidate(automaticCandidate)
                            view.post { refreshContextualCandidatesIfNeeded() }
                        }
                        return@setOnClickListener
                    }
                    if (holder.candidate !== boundCandidateWord) return@setOnClickListener
                    val offeredLegacySentences = sentenceAdapter.candidates
                        .filter { candidate ->
                            contextualMetricsCandidates.containsKey(candidate) &&
                                !automaticSuggestionCandidates.containsKey(candidate)
                        }
                        .map { it.text }
                    val nativeIdx = nativeCandidates.indexOfFirst { it.text == boundCandidateWord.text }
                    if (nativeIdx >= 0) {
                        service.selectCandidate(nativeIdx)
                    } else {
                        if (!contextualMetricsCandidates.containsKey(boundCandidateWord)) {
                            return@setOnClickListener
                        }
                        service.commitContextualSentence(
                            boundCandidateWord.text,
                            contextualMetricsCandidates[boundCandidateWord],
                            contextualAppendSnapshots[boundCandidateWord],
                            contextualReplacementSnapshots[boundCandidateWord]
                        )
                    }
                    // Feedback loop: If sentence candidates were offered on bottom row, record ignore decay
                    if (offeredLegacySentences.isNotEmpty()) {
                        service.recordContextualCandidatesIgnored(offeredLegacySentences)
                    }
                    view.post {
                        refreshContextualCandidatesIfNeeded()
                    }
                }
                holder.itemView.setOnLongClickListener {
                    if (automaticCandidate != null || holder.candidate !== boundCandidateWord) {
                        return@setOnLongClickListener true
                    }
                    val nativeIdx = nativeCandidates.indexOfFirst { it.text == boundCandidateWord.text }
                    if (nativeIdx >= 0) {
                        inputView.showCandidateActionMenu(nativeIdx, boundCandidateWord.text, holder.ui.root)
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
        object : HorizontalCandidateViewAdapter(theme, rowHeightDp = 30, isSentenceRow = true) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                val boundCandidateWord = holder.candidate
                val automaticCandidate = automaticSuggestionCandidates[boundCandidateWord]
                holder.itemView.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                    minWidth = 0
                    flexGrow = 0f
                    flexShrink = 0f
                }
                holder.itemView.setOnClickListener {
                    if (automaticCandidate != null) {
                        if (holder.candidate === boundCandidateWord) {
                            service.commitAutomaticSuggestionCandidate(automaticCandidate)
                            view.post { refreshContextualCandidatesIfNeeded() }
                        }
                        return@setOnClickListener
                    }
                    if (holder.candidate !== boundCandidateWord ||
                        !contextualMetricsCandidates.containsKey(boundCandidateWord)
                    ) {
                        return@setOnClickListener
                    }
                    service.commitContextualSentence(
                        boundCandidateWord.text,
                        contextualMetricsCandidates[boundCandidateWord],
                        contextualAppendSnapshots[boundCandidateWord],
                        contextualReplacementSnapshots[boundCandidateWord]
                    )
                    view.post {
                        refreshContextualCandidatesIfNeeded()
                    }
                }
                holder.itemView.setOnLongClickListener {
                    if (automaticCandidate != null || holder.candidate !== boundCandidateWord) {
                        return@setOnLongClickListener true
                    }
                    // Rejection / penalty on candidate long click
                    service.recordContextualCandidateRejected(boundCandidateWord.text, heavyPenalty = true)
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
            init {
                flexWrap = FlexWrap.NOWRAP
            }

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
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    scheduleCandidateVisibilityMeasurement()
                }
            })
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
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    scheduleCandidateVisibilityMeasurement()
                }
            })
        }
    }

    // Shows that on-device generation is still running even while a sentence candidate is
    // already displayed (see renderCandidates' generatingSpinner visibility calc): the status row
    // itself goes blank the moment a sentence candidate exists (computeStatusRowContent returns
    // null for hasAutomaticSentence=true), so this small spinner rides next to the sentence row
    // instead. Reuses the same IndeterminateRingDrawable as statusProgressBar.
    private val generatingSpinner: ProgressBar by lazy {
        ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateDrawable = IndeterminateRingDrawable(theme.candidateCommentColor, context.dp(16))
            contentDescription = context.getString(R.string.gemma_automatic_generating)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
        }
    }

    // Wraps sentenceRecyclerView so generatingSpinner can sit at its right edge without disturbing
    // the sentence-row-height contract (see KawaiiBarComponent.candidateRowFixedHeight): this row's
    // own height is what renderCandidates sets, sentenceRecyclerView just fills the remaining width.
    private val sentenceRow: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(
                sentenceRecyclerView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply { weight = 1f }
            )
            addView(
                generatingSpinner,
                LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply {
                    marginStart = context.dp(4)
                    marginEnd = context.dp(12)
                }
            )
        }
    }

    // Overlay chip shown in place of the word row when it has no candidates at all, so the
    // always-two-row candidate bar (see HorizontalCandidateComponent.renderCandidates showTwoRows
    // branch) never collapses to an empty row. Never part of wordAdapter's data set.
    private val wordPlaceholder: TextView by lazy {
        TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            textSize = 13f
            setTextColor(theme.candidateCommentColor)
            setPadding(context.dp(12), 0, context.dp(12), 0)
            isClickable = false
            isFocusable = false
            setText(R.string.candidate_placeholder_no_words)
            contentDescription = context.getString(R.string.candidate_placeholder_no_words)
            visibility = View.GONE
        }
    }

    private val hairlineDivider: View by lazy {
        View(context).apply {
            setBackgroundColor((theme.dividerColor and 0x00FFFFFF) or 0x40000000)
        }
    }

    /**
     * K5: caps its single child's measured width at [maxWidthPx] (an AT_MOST bound, not a fixed
     * width), so the landscape single-row sentence chip below can size to its own content up to
     * that cap and still render as a normal wrapContent-width chip when shorter.
     */
    private class MaxWidthContainer(context: Context) : FrameLayout(context) {
        var maxWidthPx: Int = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val boundedWidthSpec = if (maxWidthPx < Int.MAX_VALUE) {
                val incomingSize = MeasureSpec.getSize(widthMeasureSpec)
                val cappedSize = if (incomingSize > 0) minOf(incomingSize, maxWidthPx) else maxWidthPx
                MeasureSpec.makeMeasureSpec(cappedSize, MeasureSpec.AT_MOST)
            } else {
                widthMeasureSpec
            }
            super.onMeasure(boundedWidthSpec, heightMeasureSpec)
        }
    }

    // K5: the landscape single-row candidate bar's sentence chip. Reuses CandidateItemUi with
    // isSentenceRow=true — the exact same chip look as sentenceAdapter's rows — bound directly
    // (no RecyclerView/adapter) since at most one sentence candidate is ever shown here. See
    // bindSingleRowSentenceChip for its click/long-click wiring.
    private val singleRowSentenceUi: CandidateItemUi by lazy {
        CandidateItemUi(context, theme, isSentenceRow = true)
    }

    private val singleRowSentenceContainer: MaxWidthContainer by lazy {
        MaxWidthContainer(context).apply {
            visibility = View.GONE
            addView(
                singleRowSentenceUi.root,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }

    // K5: rides at the single row's right edge while on-device generation is running, exactly
    // like [generatingSpinner] does for the portrait sentence row. A separate instance since the
    // two rows are never shown at the same time but must each own their spinner view.
    private val singleRowGeneratingSpinner: ProgressBar by lazy {
        ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateDrawable = IndeterminateRingDrawable(theme.candidateCommentColor, context.dp(16))
            contentDescription = context.getString(R.string.gemma_automatic_generating)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
        }
    }

    // K5: wraps wordRecyclerView so the landscape single row can place the sentence chip and the
    // generating spinner alongside it without disturbing wordRecyclerView's own scrolling content.
    // In every other mode singleRowSentenceContainer stays GONE (0 width), so wordRecyclerView's
    // weight=1 slot fills the row exactly as it did as a direct child of [view] before K5.
    private val wordRow: LinearLayout by lazy {
        object : LinearLayout(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                singleRowSentenceContainer.maxWidthPx = (w * SINGLE_ROW_SENTENCE_MAX_WIDTH_FRACTION).toInt()
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                singleRowSentenceContainer,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                wordRecyclerView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply { weight = 1f }
            )
            addView(
                singleRowGeneratingSpinner,
                LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply {
                    marginStart = context.dp(4)
                    marginEnd = context.dp(12)
                }
            )
        }
    }

    private val connectionHintView: TextView by lazy {
        TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            textSize = 13f
            setTextColor(theme.candidateTextColor)
            setPadding(context.dp(12), 0, context.dp(12), 0)
            isClickable = true
            isFocusable = true
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            // A narrow candidate bar (e.g. one-hand mode) must not wrap this to two lines and
            // grow the row height; the row's height is fixed regardless of content.
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    /**
     * Self-painted indeterminate ring for the status row spinner (16dp, [theme.candidateLabelColor]).
     *
     * The platform's `?android:attr/progressBarStyleSmall` `ProgressBar`, constructed directly in
     * code (not inflated from XML) on this `InputMethodService`'s raw `Context`, does not reliably
     * resolve to a drawable that paints anything at 16dp: it previously clipped down to a barely
     * visible sliver (the original "tiny dot" report), and after switching to the small style it
     * stopped painting at all even though the 16dp layout box was still reserved. Painting our own
     * ring removes that dependency entirely: [draw] always paints a full frame the moment the
     * drawable is attached, so the spinner is visible on frame one regardless of whether [start]
     * has run yet.
     *
     * Animation is driven by [scheduleSelf]/[SystemClock.uptimeMillis] rather than
     * `ValueAnimator`/`ObjectAnimator`, so it is unaffected by
     * `Settings.Global.ANIMATOR_DURATION_SCALE` being set to 0 (common on automated/emulator
     * screenshot capture, and a documented gotcha elsewhere in this codebase, see
     * [org.fcitx.fcitx5.android.ui.common.ProgressBarDialogIndeterminate]); the ring stays visible
     * and animates either way. [ProgressBar] itself calls [start]/[stop] on an `indeterminateDrawable`
     * that implements [Animatable] as it attaches/detaches or becomes visible/invisible, so no extra
     * lifecycle wiring is needed here beyond the existing `statusProgressBar.visibility` toggle in
     * [setStatusRow].
     */
    private class IndeterminateRingDrawable(
        private val ringColor: Int,
        private val sizePx: Int
    ) : Drawable(), Animatable {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (sizePx / 8f).coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            color = ringColor
        }
        private val arcRect = RectF()
        private var degrees = 0f
        private var running = false

        private val tick = object : Runnable {
            override fun run() {
                degrees = (degrees + TICK_DEGREES) % 360f
                invalidateSelf()
                if (running) scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_INTERVAL_MS)
            }
        }

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val inset = paint.strokeWidth / 2f
            arcRect.set(
                bounds.left + inset,
                bounds.top + inset,
                bounds.right - inset,
                bounds.bottom - inset
            )
        }

        override fun getIntrinsicWidth() = sizePx
        override fun getIntrinsicHeight() = sizePx

        override fun draw(canvas: Canvas) {
            canvas.save()
            canvas.rotate(degrees, arcRect.centerX(), arcRect.centerY())
            canvas.drawArc(arcRect, 0f, SWEEP_DEGREES, false, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun start() {
            if (running) return
            running = true
            unscheduleSelf(tick)
            scheduleSelf(tick, SystemClock.uptimeMillis())
        }

        override fun stop() {
            running = false
            unscheduleSelf(tick)
        }

        override fun isRunning(): Boolean = running

        private companion object {
            const val SWEEP_DEGREES = 300f
            const val TICK_DEGREES = 10f
            const val FRAME_INTERVAL_MS = 40L
        }
    }

    private val statusProgressBar: ProgressBar by lazy {
        ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateDrawable = IndeterminateRingDrawable(theme.candidateLabelColor, context.dp(16))
        }
    }

    private val statusTextView: TextView by lazy {
        TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            textSize = 13f
            setTextColor(theme.candidateTextColor)
            setPadding(context.dp(12), 0, context.dp(12), 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            // Same fixed-row-height reasoning as connectionHintView above.
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private val statusRowView: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                statusProgressBar,
                LinearLayout.LayoutParams(context.dp(16), context.dp(16)).apply {
                    marginStart = context.dp(12)
                }
            )
            addView(
                statusTextView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    override val view: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                wordRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                wordPlaceholder,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            )
            addView(
                hairlineDivider,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            )
            addView(
                connectionHintView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            )
            addView(
                statusRowView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            )
            addView(
                sentenceRow,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            )
        }.also { root ->
            root.addOnAttachStateChangeListener(metricsAttachListener)
            if (root.isAttachedToWindow) {
                root.viewTreeObserver.addOnGlobalLayoutListener(metricsGlobalLayoutListener)
            }
        }
    }

    private var collectionFeedbackJob: Job? = null

    /**
     * Subscribes to the service's collection feedback stream. Must not run from the constructor:
     * `service` is a scope dependency that is only resolvable once the component has been added
     * to the dependency manager (see [onScopeSetupFinished]), and the subscription must die with
     * this component's view so a replaced InputView does not keep a stale subscriber alive.
     */
    private fun subscribeCollectionFeedback() {
        if (collectionFeedbackJob?.isActive == true) return
        collectionFeedbackJob = service.lifecycleScope.launch {
            service.collectionFeedback.collect { event ->
                if (event == null) return@collect
                activeCollectionFeedbackEvent = event
                refreshContextualCandidatesIfNeeded()
                collectionFeedbackHideRunnable?.let { view.removeCallbacks(it) }
                val hideRunnable = Runnable {
                    collectionFeedbackHideRunnable = null
                    activeCollectionFeedbackEvent = null
                    refreshContextualCandidatesIfNeeded()
                }
                collectionFeedbackHideRunnable = hideRunnable
                view.postDelayed(hideRunnable, COLLECTION_FEEDBACK_DISPLAY_MS)
            }
        }
    }

    private fun unsubscribeCollectionFeedback() {
        collectionFeedbackJob?.cancel()
        collectionFeedbackJob = null
        collectionFeedbackHideRunnable?.let { view.removeCallbacks(it) }
        collectionFeedbackHideRunnable = null
        activeCollectionFeedbackEvent = null
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        subscribeCollectionFeedback()
    }

    private fun scheduleCandidateVisibilityMeasurement() {
        if (metricsVisibilityPosted) return
        metricsVisibilityPosted = true
        view.post {
            metricsVisibilityPosted = false
            recordVisibleContextualCandidates()
        }
    }

    private fun recordVisibleContextualCandidates() {
        if (!view.isShown) return
        recordVisibleContextualCandidates(wordRecyclerView)
        recordVisibleContextualCandidates(sentenceRecyclerView)
    }

    private fun recordVisibleContextualCandidates(recyclerView: RecyclerView) {
        if (!recyclerView.isShown) return
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index) ?: continue
            if (!child.isShown || !child.getGlobalVisibleRect(Rect())) continue
            val holder = recyclerView.getChildViewHolder(child) as? CandidateViewHolder ?: continue
            service.recordContextualCandidateShown(contextualMetricsCandidates[holder.candidate])
        }
    }

    /**
     * Shows [resource] in the shared connection-hint row. When [clickable] is false, this is
     * actually the sentence-row empty-state placeholder chip riding on the same view (see
     * renderCandidates' showTwoRows branch): dimmed text, no click/long-press.
     */
    private fun setConnectionHint(resource: Int?, clickable: Boolean = true) {
        val visible = resource != null
        connectionHintView.visibility = if (visible) View.VISIBLE else View.GONE
        connectionHintView.updateLayoutParams<LinearLayout.LayoutParams> {
            height = if (visible) context.dp(KawaiiBarComponent.HEIGHT) else 0
            weight = 0f
        }
        if (visible) connectionHintView.setText(resource)
        connectionHintView.isClickable = clickable
        connectionHintView.isFocusable = clickable
        connectionHintView.setOnClickListener(null)
        connectionHintView.setTextColor(if (clickable) theme.candidateTextColor else theme.candidateCommentColor)
        bar.candidateConnectionHintVisible = visible
    }

    private enum class StatusRowLogicalState {
        WARMUP, GENERATING, NO_CANDIDATE, ERROR, WARMUP_FAILED, COLLECTION_FEEDBACK
    }

    private data class StatusRowContent(val spinner: Boolean, val text: String)

    private fun cancelStatusRowNoCandidateHide() {
        statusRowNoCandidateHideRunnable?.let { view.removeCallbacks(it) }
        statusRowNoCandidateHideRunnable = null
    }

    /**
     * Computes what the automatic (on-device) suggestion status row should say, if anything, and
     * advances the NO_CANDIDATE auto-hide timer as a side effect. Called at most once per
     * [renderCandidates] pass regardless of layout branch, so the timer and logical-state tracking
     * stay consistent across renders.
     */
    private fun computeStatusRowContent(hasAutomaticSentence: Boolean): StatusRowContent? {
        val status = service.automaticSuggestionStatus
        val previousCoordinatorState = lastObservedCoordinatorState
        lastObservedCoordinatorState = status.state
        if (hasAutomaticSentence) {
            cancelStatusRowNoCandidateHide()
            statusRowLogicalState = null
            statusRowNoCandidateHidden = false
            return null
        }
        val warmupState = service.automaticSuggestionWarmupState
        var nextState = when {
            warmupState == OnDeviceAutomaticSuggestionWarmupState.Preparing -> StatusRowLogicalState.WARMUP
            status.state == OnDeviceSuggestionCoordinator.State.DEBOUNCING ||
                status.state == OnDeviceSuggestionCoordinator.State.GENERATING -> StatusRowLogicalState.GENERATING
            status.state == OnDeviceSuggestionCoordinator.State.NO_CANDIDATE -> StatusRowLogicalState.NO_CANDIDATE
            status.state == OnDeviceSuggestionCoordinator.State.ERROR &&
                status.errorCode != "INVALID_INPUT" -> StatusRowLogicalState.ERROR
            service.automaticSuggestionWarmupFailureCode != null -> StatusRowLogicalState.WARMUP_FAILED
            activeCollectionFeedbackEvent != null -> StatusRowLogicalState.COLLECTION_FEEDBACK
            else -> null
        }
        // NO_CANDIDATE also fires for reasons unrelated to "generated, but got nothing" (input
        // rejected, session invalidated, a candidate just applied). Only enter the NO_CANDIDATE
        // display on the specific edge where the coordinator was actually GENERATING right before;
        // once entered, the timer/hidden bookkeeping below keeps it up regardless of later reads.
        if (nextState == StatusRowLogicalState.NO_CANDIDATE &&
            statusRowLogicalState != StatusRowLogicalState.NO_CANDIDATE &&
            previousCoordinatorState != OnDeviceSuggestionCoordinator.State.GENERATING
        ) {
            nextState = null
        }
        if (nextState != statusRowLogicalState) {
            cancelStatusRowNoCandidateHide()
            statusRowNoCandidateHidden = false
        }
        statusRowLogicalState = nextState
        return when (nextState) {
            StatusRowLogicalState.WARMUP -> StatusRowContent(
                spinner = true,
                text = context.getString(R.string.gemma_automatic_warmup_content_description)
            )
            StatusRowLogicalState.GENERATING -> StatusRowContent(
                spinner = true,
                text = context.getString(R.string.gemma_automatic_generating)
            )
            StatusRowLogicalState.NO_CANDIDATE -> {
                if (statusRowNoCandidateHidden) {
                    null
                } else {
                    if (statusRowNoCandidateHideRunnable == null) {
                        val runnable = Runnable {
                            statusRowNoCandidateHideRunnable = null
                            statusRowNoCandidateHidden = true
                            refreshContextualCandidatesIfNeeded()
                        }
                        statusRowNoCandidateHideRunnable = runnable
                        view.postDelayed(runnable, STATUS_ROW_NO_CANDIDATE_TIMEOUT_MS)
                    }
                    StatusRowContent(
                        spinner = false,
                        text = context.getString(R.string.gemma_automatic_no_candidate)
                    )
                }
            }
            StatusRowLogicalState.ERROR -> StatusRowContent(
                spinner = false,
                text = automaticFailureText(context, status.errorCode)
            )
            StatusRowLogicalState.WARMUP_FAILED -> StatusRowContent(
                spinner = false,
                text = automaticFailureText(context, service.automaticSuggestionWarmupFailureCode)
            )
            StatusRowLogicalState.COLLECTION_FEEDBACK -> {
                val event = activeCollectionFeedbackEvent
                if (event == null) null else StatusRowContent(spinner = false, text = collectionFeedbackText(event))
            }
            null -> null
        }
    }

    /**
     * "업무로 학습 중 · 3/10" while buffering, "업무 페르소나 갱신됨" once a batch compiles. The
     * direction particle (로/으로) is only appended when the persona label actually ends in a
     * Hangul syllable (i.e. the device locale renders Korean labels); it degrades to no particle
     * for other locales since [KoreanParticleSuggester] returns no suggestions for non-Hangul text.
     */
    private fun collectionFeedbackText(event: CollectionFeedbackEvent): String {
        val personaLabel = PersonaRegistry.byId(event.category)?.let { context.getString(it.labelRes) }
            ?: event.category
        if (event.compiled) {
            return context.getString(R.string.collection_feedback_compiled, personaLabel)
        }
        val direction = KoreanParticleSuggester.suggest(personaLabel)
            .firstOrNull { it.kind == KoreanParticleKind.Direction }
            ?.text
        val subject = if (direction != null) "$personaLabel$direction" else personaLabel
        return context.getString(R.string.collection_feedback_learning, subject, event.pendingCount, event.threshold)
    }

    private fun setStatusRow(visible: Boolean, content: StatusRowContent?) {
        if (visible && content != null) {
            statusTextView.text = content.text
            statusProgressBar.visibility =
                if (shouldShowStatusSpinner(content.spinner, disableAnimation)) View.VISIBLE else View.GONE
        }
        statusRowView.visibility = if (visible) View.VISIBLE else View.GONE
        statusRowView.updateLayoutParams<LinearLayout.LayoutParams> {
            height = if (visible) context.dp(KawaiiBarComponent.HEIGHT) else 0
            weight = 0f
        }
        bar.candidateStatusRowVisible = visible
    }

    private fun renderCandidates(data: FcitxEvent.CandidateListEvent.Data? = null) {
        // A refresh posted around service teardown must not touch the fcitx connection again.
        if (service.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.DESTROYED) return
        if (data != null) {
            nativeCandidates = data.candidates.toList()
            nativeCandidateCount = nativeCandidates.size
        }
        val automaticCandidates = getAutomaticCandidates()
        bar.hasAutomaticCandidates = automaticCandidates.isNotEmpty
        val automaticTexts = automaticCandidates.texts
        val displayNativeCandidates = nativeCandidates.filterNot { it.text in automaticTexts }
        val contextualSnapshot = service.getContextualCandidateSnapshot(wordLimit = 4, sentenceLimit = 2)
        val contextualWords = contextualSnapshot.words
            .map { it.word }
            .filterNot { it.text in automaticTexts }
        val contextualSentences = contextualSnapshot.sentences
            .filterNot { contextual ->
                contextual.word.text in automaticTexts ||
                    nativeCandidates.any { it.text == contextual.word.text }
            }
            .map { it.word }
        contextualMetricsCandidates.clear()
        contextualAppendSnapshots.clear()
        contextualReplacementSnapshots.clear()
        (contextualSnapshot.words + contextualSnapshot.sentences).forEach { candidate ->
            if (candidate.word.text !in automaticTexts &&
                nativeCandidates.none { it.text == candidate.word.text }
            ) {
                contextualMetricsCandidates[candidate.word] = candidate.metricsCandidate
                contextualAppendSnapshots[candidate.word] = candidate.appendSnapshot
                contextualReplacementSnapshots[candidate.word] = candidate.replacementSnapshot
            }
        }

        val flags = if (currentCapFlags != CapabilityFlags.DefaultFlags) currentCapFlags else service.capabilityFlags
        val isEmail = EditorPrivacyPolicy.isEmailAddressField(service.currentInputEditorInfo, flags)
        val isUrl = EditorPrivacyPolicy.isUrlField(service.currentInputEditorInfo, flags)

        // K5: landscape while not thumb-split overrides the two-row preference entirely with the
        // fixed 48dp single row (see CandidateBarModePolicy.isHorizontalSingleRow / design.md K5).
        val singleRowLandscape = CandidateBarModePolicy.isHorizontalSingleRow(
            landscape = isLandscapeOrientation(),
            thumbSplitActive = isThumbSplitActive()
        )
        bar.candidateSingleRowLandscape = singleRowLandscape
        val showTwoRows = twoRowCandidateBar && !isEmail && !isUrl && !singleRowLandscape
        // The always-two-row candidate bar never collapses; see the showTwoRows branch below.
        bar.candidateRowFixedHeight = showTwoRows
        // 다른 소스(문장팩·네트워크·개인화)의 문장 후보가 있으면 상태 행이 그 행을 가리지 않는다.
        val statusRowContent = computeStatusRowContent(
            automaticCandidates.sentences.isNotEmpty() || contextualSentences.isNotEmpty()
        )
        // A spinner-less status (NO_CANDIDATE/ERROR) may only occupy the row when the bar is
        // already non-empty for some other reason; it must never be the sole thing keeping the
        // bar from collapsing. A spinner status (WARMUP/GENERATING) is allowed to force the bar
        // to stay up on its own, tracked separately as statusRowForcesBar below. The always-two-row
        // bar has a fixed-height row to show it in either way, so this constraint is 1-row only.
        val barAlreadyNonEmpty = !(preeditEmpty && nativeCandidates.isEmpty()) || automaticCandidates.isNotEmpty
        var statusRowActive = statusRowContent != null
        if (!showTwoRows && statusRowActive && statusRowContent?.spinner == false && !barAlreadyNonEmpty) {
            statusRowActive = false
        }
        val statusRowForcesBar = statusRowActive && statusRowContent?.spinner == true

        // wordRow is a permanent structural wrapper (see K5); only the single-row-landscape
        // PLACEHOLDER state collapses it, and that branch below sets it back to GONE explicitly.
        wordRow.visibility = View.VISIBLE

        if (singleRowLandscape) {
            renderSingleRowLandscape(automaticCandidates, displayNativeCandidates, contextualWords, contextualSentences)
        } else if (showTwoRows) {
            val topCandidates = prependAutomaticCandidates(
                automaticCandidates.words,
                mergeCandidates(displayNativeCandidates, contextualWords, emptyList())
            )
            val bottomCandidates = if (
                automaticCandidates.sentences.isNotEmpty() || contextualSentences.isNotEmpty()
            ) {
                prependAutomaticCandidates(
                    automaticCandidates.sentences,
                    contextualSentences.toTypedArray()
                )
            } else {
                emptyArray()
            }

            // The word and sentence rows always occupy exactly HEIGHT each (see
            // KawaiiBarComponent.candidateRowFixedHeight): an empty row shows a placeholder chip
            // in the same spot instead of shrinking, so the bar never resizes.
            singleRowSentenceContainer.visibility = View.GONE
            singleRowGeneratingSpinner.visibility = View.GONE
            wordAdapter.updateCandidates(topCandidates, topCandidates.size)
            wordAdapter.rowHeightDp = KawaiiBarComponent.HEIGHT
            val wordRowHasCandidates = topCandidates.isNotEmpty()
            wordRecyclerView.visibility = if (wordRowHasCandidates) View.VISIBLE else View.GONE
            wordRow.updateLayoutParams<LinearLayout.LayoutParams> {
                height = if (wordRowHasCandidates) context.dp(wordAdapter.rowHeightDp) else 0
                weight = 0f
            }
            wordPlaceholder.visibility = if (wordRowHasCandidates) View.GONE else View.VISIBLE
            wordPlaceholder.updateLayoutParams<LinearLayout.LayoutParams> {
                height = if (wordRowHasCandidates) 0 else context.dp(KawaiiBarComponent.HEIGHT)
                weight = 0f
            }

            sentenceAdapter.updateCandidates(bottomCandidates, bottomCandidates.size)
            sentenceAdapter.rowHeightDp = KawaiiBarComponent.HEIGHT
            val sentenceRowHasCandidates = bottomCandidates.isNotEmpty()
            sentenceRecyclerView.updateLayoutParams<LinearLayout.LayoutParams> {
                width = 0
                weight = 1f
            }
            sentenceRow.visibility = if (sentenceRowHasCandidates) View.VISIBLE else View.GONE
            sentenceRow.updateLayoutParams<LinearLayout.LayoutParams> {
                height = if (sentenceRowHasCandidates) context.dp(sentenceAdapter.rowHeightDp) else 0
                weight = 0f
            }

            // Sentence row empty-state priority: real candidates > status row >
            // "no sentences" placeholder (reusing connectionHintView, non-clickable).
            when {
                sentenceRowHasCandidates -> {
                    setConnectionHint(null)
                    setStatusRow(false, null)
                }
                statusRowActive -> {
                    setConnectionHint(null)
                    setStatusRow(true, statusRowContent)
                }
                else -> {
                    setStatusRow(false, null)
                    setConnectionHint(R.string.candidate_placeholder_no_sentences, clickable = false)
                }
            }
            // A sentence candidate already on screen makes computeStatusRowContent go blank
            // (hasAutomaticSentence short-circuits it to null), so DEBOUNCING/GENERATING would
            // otherwise show nothing while a fresh suggestion is being generated. This spinner
            // covers that gap; it stays hidden whenever the status row itself is already showing
            // a spinner (statusRowForcesBar), so the two never double up.
            val generating = service.automaticSuggestionStatus.state in setOf(
                OnDeviceSuggestionCoordinator.State.DEBOUNCING,
                OnDeviceSuggestionCoordinator.State.GENERATING
            )
            generatingSpinner.visibility = if (generating && !statusRowForcesBar) View.VISIBLE else View.GONE

            hairlineDivider.visibility = View.VISIBLE
            hairlineDivider.updateLayoutParams<LinearLayout.LayoutParams> {
                height = max(1, context.dp(1))
                weight = 0f
            }

            bar.isCandidateTwoRow = true
            // The row height and visibility never collapse in this mode, so the bar always counts
            // as non-empty and visible.
            bar.barStateMachine.push(
                KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated,
                KawaiiBarStateMachine.BooleanKey.CandidateEmpty to false
            )
            applyFillStyle(topCandidates.size)
            setHasVisibleCandidates(true)
        } else {
            val candidates = mergeCandidates(
                displayNativeCandidates,
                contextualWords,
                contextualSentences
            ).let { legacy ->
                prependAutomaticCandidates(automaticCandidates.words + automaticCandidates.sentences, legacy)
            }
            wordAdapter.updateCandidates(candidates, candidates.size)
            sentenceAdapter.updateCandidates(emptyArray(), 0)

            singleRowSentenceContainer.visibility = View.GONE
            singleRowGeneratingSpinner.visibility = View.GONE
            val hasCandidates = candidates.isNotEmpty()
            if (hasCandidates) {
                setConnectionHint(null)
                setStatusRow(false, null)
                wordRecyclerView.visibility = View.VISIBLE
                wordAdapter.rowHeightDp = KawaiiBarComponent.HEIGHT
                wordRow.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                    weight = 0f
                }
                hairlineDivider.visibility = View.GONE
                sentenceRecyclerView.visibility = View.GONE
                bar.isCandidateTwoRow = false
                // 유휴 상태(preedit 없음)에서 native 후보도 없다면, 문맥 후보만으로 CandidateEmpty를
                // false로 밀어붙이지 않는다(이중 안전장치). native 후보가 있는 경로는 그대로 둔다.
                if (!(preeditEmpty && nativeCandidates.isEmpty()) || automaticCandidates.isNotEmpty) {
                    bar.barStateMachine.push(
                        KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated,
                        KawaiiBarStateMachine.BooleanKey.CandidateEmpty to false
                    )
                }
                applyFillStyle(candidates.size)
                setHasVisibleCandidates(true)
            } else if (statusRowForcesBar) {
                setConnectionHint(null)
                wordRecyclerView.visibility = View.VISIBLE
                wordAdapter.rowHeightDp = 28
                wordRow.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = context.dp(wordAdapter.rowHeightDp)
                    weight = 0f
                }
                hairlineDivider.visibility = View.VISIBLE
                hairlineDivider.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = max(1, context.dp(1))
                    weight = 0f
                }
                sentenceRecyclerView.visibility = View.GONE
                sentenceRecyclerView.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = 0
                    weight = 0f
                }
                setStatusRow(true, statusRowContent)
                bar.isCandidateTwoRow = true
                bar.barStateMachine.push(
                    KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated,
                    KawaiiBarStateMachine.BooleanKey.CandidateEmpty to false
                )
                applyFillStyle(candidates.size)
                setHasVisibleCandidates(true)
            } else {
                setConnectionHint(null)
                setStatusRow(false, null)
                wordRecyclerView.visibility = View.GONE
                singleRowSentenceContainer.visibility = View.GONE
                singleRowGeneratingSpinner.visibility = View.GONE
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
        scheduleCandidateVisibilityMeasurement()
    }

    /**
     * K5: gray-mode aside, this is the landscape-non-split single row. Its first item, when a
     * sentence recommendation exists, is a sentence chip (capped at 45% of the row's width, see
     * [wordRow]); the rest of the row is the ordinary word candidate recycler. When neither exists
     * the row collapses to the shared "추천 단어 없음" placeholder chip. The row's height is fixed
     * (48dp) regardless of which of these three states is active — see
     * CandidateBarModePolicy.candidateRowHeightDp.
     */
    private fun renderSingleRowLandscape(
        automaticCandidates: AutomaticCandidates,
        displayNativeCandidates: List<CandidateWord>,
        contextualWords: List<CandidateWord>,
        contextualSentences: List<CandidateWord>
    ) {
        setConnectionHint(null)
        setStatusRow(false, null)
        hairlineDivider.visibility = View.GONE
        sentenceRow.visibility = View.GONE
        sentenceRecyclerView.visibility = View.GONE
        sentenceAdapter.updateCandidates(emptyArray(), 0)

        val topCandidates = prependAutomaticCandidates(
            automaticCandidates.words,
            mergeCandidates(displayNativeCandidates, contextualWords, emptyList())
        )
        val topSentenceCandidates = prependAutomaticCandidates(
            automaticCandidates.sentences,
            contextualSentences.toTypedArray()
        )
        val sentenceCandidate = topSentenceCandidates.firstOrNull()

        wordAdapter.updateCandidates(topCandidates, topCandidates.size)
        wordAdapter.rowHeightDp = KawaiiBarComponent.HEIGHT

        val hasSentence = sentenceCandidate != null
        val hasWords = topCandidates.isNotEmpty()
        when (CandidateBarModePolicy.singleRowContent(hasSentence, hasWords)) {
            CandidateBarModePolicy.SingleRowContent.SENTENCE_AND_WORDS -> {
                bindSingleRowSentenceChip(sentenceCandidate!!)
                singleRowSentenceContainer.visibility = View.VISIBLE
                wordRecyclerView.visibility = if (hasWords) View.VISIBLE else View.GONE
                wordPlaceholder.visibility = View.GONE
                wordPlaceholder.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = 0
                    weight = 0f
                }
                wordRow.visibility = View.VISIBLE
                wordRow.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = context.dp(KawaiiBarComponent.HEIGHT)
                    weight = 0f
                }
            }
            CandidateBarModePolicy.SingleRowContent.WORDS_ONLY -> {
                singleRowSentenceContainer.visibility = View.GONE
                wordRecyclerView.visibility = View.VISIBLE
                wordPlaceholder.visibility = View.GONE
                wordPlaceholder.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = 0
                    weight = 0f
                }
                wordRow.visibility = View.VISIBLE
                wordRow.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = context.dp(KawaiiBarComponent.HEIGHT)
                    weight = 0f
                }
            }
            CandidateBarModePolicy.SingleRowContent.PLACEHOLDER -> {
                singleRowSentenceContainer.visibility = View.GONE
                wordRecyclerView.visibility = View.GONE
                wordRow.visibility = View.GONE
                wordRow.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = 0
                    weight = 0f
                }
                wordPlaceholder.visibility = View.VISIBLE
                wordPlaceholder.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = context.dp(KawaiiBarComponent.HEIGHT)
                    weight = 0f
                }
                refreshExpanded(0)
            }
        }

        val generating = service.automaticSuggestionStatus.state in setOf(
            OnDeviceSuggestionCoordinator.State.DEBOUNCING,
            OnDeviceSuggestionCoordinator.State.GENERATING
        )
        singleRowGeneratingSpinner.visibility = if (generating) View.VISIBLE else View.GONE

        bar.isCandidateTwoRow = false
        // This row's height never collapses (see CandidateBarModePolicy.candidateRowHeightDp), so
        // it always counts as visible, exactly like the portrait always-two-row bar.
        bar.barStateMachine.push(
            KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated,
            KawaiiBarStateMachine.BooleanKey.CandidateEmpty to false
        )
        applyFillStyle(topCandidates.size)
        setHasVisibleCandidates(true)
    }

    /**
     * Wires the landscape single row's sentence chip to the exact same action/learning/metrics
     * path as sentenceAdapter's own onBindViewHolder (see above): the chip is a plain bound view,
     * not a RecyclerView item, but the tap must resolve through the same
     * commitAutomaticSuggestionCandidate / commitContextualSentence / recordContextualCandidateRejected
     * calls so it counts identically for learning and metrics.
     */
    private fun bindSingleRowSentenceChip(candidate: CandidateWord) {
        singleRowSentenceUi.updateCandidate(candidate, isFeatured = true)
        val automaticCandidate = automaticSuggestionCandidates[candidate]
        val root = singleRowSentenceUi.root
        root.setOnClickListener {
            if (automaticCandidate != null) {
                service.commitAutomaticSuggestionCandidate(automaticCandidate)
                view.post { refreshContextualCandidatesIfNeeded() }
                return@setOnClickListener
            }
            if (!contextualMetricsCandidates.containsKey(candidate)) return@setOnClickListener
            service.commitContextualSentence(
                candidate.text,
                contextualMetricsCandidates[candidate],
                contextualAppendSnapshots[candidate],
                contextualReplacementSnapshots[candidate]
            )
            view.post { refreshContextualCandidatesIfNeeded() }
        }
        root.setOnLongClickListener {
            if (automaticCandidate != null) return@setOnLongClickListener true
            service.recordContextualCandidateRejected(candidate.text, heavyPenalty = true)
            view.post { refreshContextualCandidatesIfNeeded() }
            true
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
        contextualMetricsCandidates.clear()
        contextualAppendSnapshots.clear()
        contextualReplacementSnapshots.clear()
        automaticSuggestionCandidates.clear()
        bar.hasAutomaticCandidates = false
        cancelStatusRowNoCandidateHide()
        statusRowLogicalState = null
        statusRowNoCandidateHidden = false
        lastObservedCoordinatorState = null
        view.post {
            refreshContextualCandidatesIfNeeded()
        }
    }

    internal companion object {
        const val STATUS_ROW_NO_CANDIDATE_TIMEOUT_MS = 1500L
        const val COLLECTION_FEEDBACK_DISPLAY_MS = 1200L

        // K5: sentence chip's max width in the landscape single row, as a fraction of the row's
        // own width ("최대 폭 = 줄 폭의 45%").
        const val SINGLE_ROW_SENTENCE_MAX_WIDTH_FRACTION = 0.45f

        /**
         * Whether the status row's spinner should be shown, given the logical state's spinner
         * flag (true for WARMUP/GENERATING, false for NO_CANDIDATE/ERROR/WARMUP_FAILED — see
         * [computeStatusRowContent]) and the user's disable-animation preference. Extracted as a
         * pure function (no Android/Theme dependency) so the WARMUP/GENERATING-must-show-a-spinner
         * contract can be unit tested without Robolectric; see HorizontalCandidateComponentStatusRowTest.
         */
        internal fun shouldShowStatusSpinner(spinner: Boolean, disableAnimation: Boolean): Boolean =
            spinner && !disableAnimation

        fun automaticFailureText(context: Context, code: String?): String =
            OnDeviceFailureText.of(code ?: "UNKNOWN", context.resources)
    }
}

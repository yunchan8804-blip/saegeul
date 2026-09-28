/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai.prediction

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.EditorPrivacyPolicy
import org.fcitx.fcitx5.android.input.EditorSelection
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.ai.AiContextualPredictor
import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine
import org.fcitx.fcitx5.android.input.ai.ContextualAppend
import org.fcitx.fcitx5.android.input.ai.ContextualPredictionInput
import org.fcitx.fcitx5.android.input.ai.ContextualReplacement
import org.fcitx.fcitx5.android.input.ai.ImmediateContextualPredictions
import org.fcitx.fcitx5.android.input.ai.KoreanSemanticSentencePredictor
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.PersonalizedSentenceStore
import org.fcitx.fcitx5.android.input.ai.ReinforcementTracker
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsSession
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphStore
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import timber.log.Timber

data class ContextualAppendSnapshot(
    val append: ContextualAppend,
    val inputSessionEpoch: Long
)

data class ContextualReplacementSnapshot(
    val replacement: ContextualReplacement,
    val inputSessionEpoch: Long,
    val cursor: Int
)

data class ContextualCandidate(
    val word: CandidateWord,
    val metricsCandidate: PredictionMetricsSession.Candidate?,
    val appendSnapshot: ContextualAppendSnapshot? = null,
    val replacementSnapshot: ContextualReplacementSnapshot? = null
)

data class ContextualCandidateSnapshot(
    val words: List<ContextualCandidate>,
    val sentences: List<ContextualCandidate>
)

/**
 * Owns contextual word/sentence prediction for the current editor: the prediction epoch, the
 * memoized results and their background job, candidate metrics, and committing a chosen
 * candidate back into the editor.
 */
class ContextualPredictionController(private val host: Host) {

    /** The editor state, commit helpers and personal learning hooks prediction reads or triggers. */
    data class Host(
        val lifecycleScope: () -> CoroutineScope,
        val inputView: () -> InputView?,
        val allowsTextInspection: () -> Boolean,
        val editorInfo: () -> EditorInfo?,
        val capabilityFlags: () -> CapabilityFlags,
        val inputConnection: () -> InputConnection?,
        val selection: () -> CursorRange,
        val inputSessionEpoch: () -> Long,
        val activePreedit: () -> String,
        val finishCompositionForDirectAction: () -> Boolean,
        val commitTextToEditor: (text: String, cursor: Int) -> Boolean,
        val replaceAiRange: (
            connection: InputConnection,
            start: Int,
            end: Int,
            replacement: String,
            restore: EditorSelection
        ) -> Boolean,
        val commitAiTextAtCursor: (
            connection: InputConnection,
            cursor: Int,
            text: String,
            restore: EditorSelection
        ) -> Boolean,
        val predictSelection: (position: Int) -> Unit,
        val morphologyEngine: () -> ChoseongMorphologyEngine,
        val personalizedStore: () -> PersonalizedSentenceStore,
        val personalNgramModel: () -> PersonalNgramModel,
        val typoCorrector: () -> KeyboardAwareTypoCorrector,
        val baseKoreanVocabulary: () -> BaseKoreanVocabulary,
        val correctionPatternStore: () -> CorrectionPatternStore,
        val personalSentenceVault: () -> PersonalSentenceVault,
        val personalGraphStore: () -> PersonalGraphStore,
        val reinforcementTracker: () -> ReinforcementTracker,
        val enqueuePersonalLearning: (action: () -> Unit) -> Unit,
        val enqueueContextualSelectionFeedback: (
            contextBeforeReinforce: String,
            selectedSentence: String,
            reinforcedSentence: String,
            packageName: String
        ) -> Unit,
        val scheduleNgramSave: () -> Unit,
        val observeCommittedEditorText: (text: String) -> Unit,
        val captureCorrectionBoundarySnapshot: () -> Unit,
        val onEditorSuffixDeleted: (packageName: String?, removedText: String?, inspectionAllowed: Boolean) -> Unit
    )

    /** The current editor, which every prediction path dereferences as present. */
    private val currentInputEditorInfo: EditorInfo
        get() = host.editorInfo()!!

    @Volatile
    private var predictionEpoch = 0L

    private data class ContextualPredictionMemoKey(
        val stroke: String,
        val context: String,
        val packageName: String,
        val epoch: Long,
        val sentencePackRevision: Long,
        val generatedSentenceRevision: Long
    )

    private data class CachedContextualPredictions(
        val key: ContextualPredictionMemoKey,
        val generation: Long,
        val predictions: List<AiPrediction>
    )

    private data class ResolvedContextualPredictions(
        val predictions: List<AiPrediction>,
        val generation: Long?
    )

    private fun mergeGeneratedSentencePredictions(
        predictions: List<AiPrediction>,
        generatedPredictions: List<AiPrediction>
    ): List<AiPrediction> {
        val seen = mutableSetOf<String>()
        return (predictions + generatedPredictions)
            .asSequence()
            .sortedByDescending { it.confidenceScore }
            .filter { prediction -> seen.add("${prediction.isSentenceCompletion}:${prediction.text}") }
            .toList()
    }

    @Volatile
    private var contextualResultCache: CachedContextualPredictions? = null

    private var contextualPredictKey: ContextualPredictionMemoKey? = null
    private var contextualPredictJob: Job? = null
    private var sentencePackRevisionJob: Job? = null
    private var observedSentencePackRevision = Long.MIN_VALUE
    private var nextContextualPredictionGeneration = 0L
    private val predictionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val predictionMetricsSession = PredictionMetricsSession()

    fun observeSentencePackRevision() {
        val sentencePacks = FcitxApplication.getInstance().sentencePacks
        sentencePacks.prepare()
        observedSentencePackRevision = sentencePacks.revision
        sentencePackRevisionJob?.cancel()
        sentencePackRevisionJob = host.lifecycleScope().launch {
            sentencePacks.status
                .map { it.revision }
                .distinctUntilChanged()
                .collect { revision ->
                    if (revision == observedSentencePackRevision) return@collect
                    observedSentencePackRevision = revision
                    contextualPredictJob?.cancel()
                    contextualPredictJob = null
                    contextualPredictKey = null
                    contextualResultCache = null
                    predictionEpoch++
                    host.inputView()?.refreshContextualCandidates()
                }
        }
    }

    /** Invalidates memoized predictions after personal learning changed what they would return. */
    fun advancePredictionEpoch() {
        predictionEpoch++
    }

    /** Starts a new prediction epoch and metrics window for the next editor session. */
    fun onStartInput() {
        predictionEpoch++
        predictionMetricsSession.reset()
    }

    fun onFinishInput() {
        predictionMetricsSession.reset()
    }

    fun onDestroy() {
        sentencePackRevisionJob?.cancel()
        sentencePackRevisionJob = null
        predictionScope.cancel()
    }

    val contextualPredictor: AiContextualPredictor by lazy {
        AiContextualPredictor(
            morphology = host.morphologyEngine(),
            semanticPredictor = KoreanSemanticSentencePredictor(),
            personalizedStore = host.personalizedStore(),
            ngram = host.personalNgramModel(),
            typoCorrector = host.typoCorrector(),
            baseVocabulary = host.baseKoreanVocabulary(),
            correctionStore = host.correctionPatternStore(),
            personalSentenceVault = host.personalSentenceVault(),
            personalGraphStore = host.personalGraphStore(),
            sentencePackLookup = FcitxApplication.getInstance().sentencePacks::complete,
            bundledNgram = { FcitxApplication.getInstance().bundledKoreanNgram }
        )
    }

    private fun getEmailDomainPredictions(
        beforeCursor: String,
        activePreedit: String,
        limit: Int
    ): List<AiPrediction> {
        val emailDomains = listOf(
            "gmail.com",
            "naver.com",
            "kakao.com",
            "daum.net",
            "icloud.com",
            "outlook.com"
        )
        val trimmedBefore = beforeCursor.trim()
        val atIndex = trimmedBefore.lastIndexOf('@')

        val results = mutableListOf<AiPrediction>()

        if (atIndex >= 0) {
            val queryDomain = (trimmedBefore.substring(atIndex + 1) + activePreedit).trim().lowercase()
            val matched = if (queryDomain.isEmpty()) {
                emailDomains
            } else {
                emailDomains.filter { it.startsWith(queryDomain) }
            }
            matched.take(limit).forEachIndexed { idx, domain ->
                results.add(
                    AiPrediction(
                        text = domain,
                        confidenceScore = 0.99f - (idx * 0.01f),
                        isSentenceCompletion = false,
                        source = "email_domain",
                        badge = "📧"
                    )
                )
            }
        } else {
            emailDomains.take(limit).forEachIndexed { idx, domain ->
                results.add(
                    AiPrediction(
                        text = "@$domain",
                        confidenceScore = 0.98f - (idx * 0.01f),
                        isSentenceCompletion = false,
                        source = "email_domain",
                        badge = "📧"
                    )
                )
            }
        }
        return results
    }

    private fun getUrlDomainPredictions(
        beforeCursor: String,
        activePreedit: String,
        limit: Int
    ): List<AiPrediction> {
        val tlds = listOf(".com", ".co.kr", ".net", ".kr", ".org")
        val results = mutableListOf<AiPrediction>()
        val trimmed = (beforeCursor.trim() + activePreedit.trim()).lowercase()
        val lastDotIndex = trimmed.lastIndexOf('.')
        val queryExt = if (lastDotIndex >= 0 && lastDotIndex >= trimmed.length - 6) {
            trimmed.substring(lastDotIndex)
        } else {
            ""
        }
        val matched = if (queryExt.isNotEmpty() && queryExt != ".") {
            tlds.filter { it.startsWith(queryExt) }
        } else {
            tlds
        }
        if (!tlds.any { trimmed.endsWith(it) }) {
            matched.take(limit).forEachIndexed { idx, tld ->
                results.add(
                    AiPrediction(
                        text = tld,
                        confidenceScore = 0.95f - (idx * 0.01f),
                        isSentenceCompletion = false,
                        source = "url_tld",
                        badge = ""
                    )
                )
            }
        }
        return results
    }

    private fun getRawContextualPredictions(limit: Int): ResolvedContextualPredictions {
        if (!host.allowsTextInspection() || host.selection().isNotEmpty()) {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(emptyList(), null)
        }

        val isEmail = EditorPrivacyPolicy.isEmailAddressField(currentInputEditorInfo, host.capabilityFlags())
        val isPhone = EditorPrivacyPolicy.isPhoneField(currentInputEditorInfo, host.capabilityFlags())
        val isNumeric = EditorPrivacyPolicy.isNumericField(currentInputEditorInfo, host.capabilityFlags())
        val isUrl = EditorPrivacyPolicy.isUrlField(currentInputEditorInfo, host.capabilityFlags())
        val isConversational = EditorPrivacyPolicy.isConversationalTextField(currentInputEditorInfo, host.capabilityFlags())

        // 1. Phone or pure numeric inputs -> strictly no conversational predictions
        if (isPhone || isNumeric) {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(emptyList(), null)
        }

        val ic = host.inputConnection() ?: run {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(emptyList(), null)
        }
        val beforeCursor = ic.getTextBeforeCursor(128, 0)?.toString().orEmpty()

        val activePreedit = host.activePreedit()

        // 2. Email field -> smart email domain suggestions, zero sentence completions
        if (isEmail) {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(getEmailDomainPredictions(beforeCursor, activePreedit, limit), null)
        }

        // 3. URL field -> web domain suggestions, zero sentence completions
        if (isUrl) {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(getUrlDomainPredictions(beforeCursor, activePreedit, limit), null)
        }

        // 4. Non-conversational text field (e.g. search filter with NO_SUGGESTIONS) -> emptyList()
        if (!isConversational) {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(emptyList(), null)
        }

        val pkgName = currentInputEditorInfo.packageName
        val resolved = ContextualPredictionInput.resolve(beforeCursor, activePreedit)
        // 진짜 유휴(스트로크도 없고 커서 앞 문맥도 비어 있음)일 때만 예측을 건너뛴다. 그래야
        // 후보 영역이 접혀 도구 줄만 남는다. 단어를 치고 스페이스를 눌러 스트로크가 비었어도
        // 커서 앞에 문맥이 있으면(예: "회의 참석 ") 다음 단어·입력 이어쓰기(회의 참석하겠습니다)를
        // 계속 제시한다.
        if (resolved.stroke.isBlank() && resolved.context.isBlank()) {
            predictionMetricsSession.reset()
            return ResolvedContextualPredictions(emptyList(), null)
        }

        val application = FcitxApplication.getInstance()
        val memoKey = ContextualPredictionMemoKey(
            resolved.stroke,
            resolved.context,
            pkgName,
            predictionEpoch,
            application.sentencePacks.revision,
            if (OnDeviceAiSupport.isSupported) application.generatedSentenceBank.revision else 0L
        )
        val inputSessionEpoch = host.inputSessionEpoch()
        contextualResultCache?.let { cached ->
            if (cached.key == memoKey) {
                return ResolvedContextualPredictions(cached.predictions, cached.generation)
            }
        }
        predictionMetricsSession.reset()

        val rawFullContext = ContextualPredictionInput.rawFullContext(
            resolved.stroke,
            resolved.context
        )
        val immediateResults = ImmediateContextualPredictions.collect(
            input = ImmediateContextualPredictions.Input(
                rawContext = rawFullContext,
                packageName = pkgName,
                inputSessionEpoch = inputSessionEpoch,
                limit = limit
            ),
            sentencePackLookup = application.sentencePacks::complete,
            generatedSentenceLookup = if (OnDeviceAiSupport.isSupported) application.generatedSentenceBank::complete else null,
            generatedSpacingLookup = if (OnDeviceAiSupport.isSupported) application.generatedSentenceBank::suggestSpacing else null
        )
        val immediateGeneration = ++nextContextualPredictionGeneration
        contextualResultCache = CachedContextualPredictions(
            key = memoKey,
            generation = immediateGeneration,
            predictions = immediateResults
        )
        if (contextualPredictKey != memoKey) {
            contextualPredictJob?.cancel()
            contextualPredictKey = memoKey
            contextualPredictJob = predictionScope.launch {
                val results = contextualPredictor.predict(
                    currentStroke = resolved.stroke,
                    contextBeforeCursor = resolved.context,
                    packageName = pkgName,
                    limit = limit,
                    inputSessionEpoch = inputSessionEpoch
                )
                withContext(Dispatchers.Main) {
                    val currentFieldIsConversational =
                        !EditorPrivacyPolicy.isEmailAddressField(currentInputEditorInfo, host.capabilityFlags()) &&
                            !EditorPrivacyPolicy.isPhoneField(currentInputEditorInfo, host.capabilityFlags()) &&
                            !EditorPrivacyPolicy.isNumericField(currentInputEditorInfo, host.capabilityFlags()) &&
                            !EditorPrivacyPolicy.isUrlField(currentInputEditorInfo, host.capabilityFlags()) &&
                            EditorPrivacyPolicy.isConversationalTextField(currentInputEditorInfo, host.capabilityFlags())
                    if (
                        contextualPredictKey == memoKey &&
                        host.inputConnection() != null &&
                        host.inputSessionEpoch() == inputSessionEpoch &&
                        host.allowsTextInspection() &&
                        host.selection().isEmpty() &&
                        currentFieldIsConversational
                    ) {
                        val generatedImmediateResults = immediateResults.filter {
                            it.source == "ondevice_generated"
                        }
                        val publishedResults = when {
                            results.isEmpty() && immediateResults.isNotEmpty() -> immediateResults
                            generatedImmediateResults.isEmpty() -> results
                            else -> mergeGeneratedSentencePredictions(results, generatedImmediateResults)
                        }
                        contextualResultCache = CachedContextualPredictions(
                            key = memoKey,
                            generation = ++nextContextualPredictionGeneration,
                            predictions = publishedResults
                        )
                        host.inputView()?.refreshContextualCandidates()
                    }
                }
            }
        }
        return ResolvedContextualPredictions(immediateResults, immediateGeneration)
    }

    fun getContextualSentencePredictions(limit: Int = 2): List<CandidateWord> {
        val predictions = getRawContextualPredictions(limit = 10).predictions
        return predictions.filter { it.isSentenceCompletion }.take(limit).mapIndexed { index, pred ->
            CandidateWord(
                label = (index + 1).toString(),
                text = pred.text,
                comment = pred.badge
            )
        }
    }

    fun getContextualWordPredictions(limit: Int = 4): List<CandidateWord> {
        val predictions = getRawContextualPredictions(limit = 10).predictions
        return predictions.filter { !it.isSentenceCompletion }.take(limit).mapIndexed { index, pred ->
            CandidateWord(
                label = (index + 1).toString(),
                text = pred.text,
                comment = pred.badge
            )
        }
    }

    /**
     * Provides one immutable cache generation to the candidate UI so metrics preserve the source
     * that produced each rendered candidate instead of resolving a source from current text later.
     */
    fun getContextualCandidateSnapshot(wordLimit: Int = 4, sentenceLimit: Int = 2): ContextualCandidateSnapshot {
        val resolved = getRawContextualPredictions(limit = 10)
        val generation = resolved.generation
        if (generation != null) {
            predictionMetricsSession.activate(generation)
        }
        fun toCandidate(prediction: AiPrediction, index: Int): ContextualCandidate =
            ContextualCandidate(
                word = CandidateWord(
                    label = index.toString(),
                    text = prediction.text,
                    comment = prediction.badge
                ),
                metricsCandidate = generation?.let {
                    PredictionMetricsSession.Candidate(it, prediction.text, prediction.source)
                },
                appendSnapshot = prediction.append?.let { append ->
                    ContextualAppendSnapshot(append, host.inputSessionEpoch())
                },
                replacementSnapshot = prediction.replacement?.let { replacement ->
                    ContextualReplacementSnapshot(
                        replacement = replacement,
                        inputSessionEpoch = host.inputSessionEpoch(),
                        cursor = host.selection().start
                    )
                }
            )

        val rankedWords = resolved.predictions.filter { !it.isSentenceCompletion }
        val selectedWords = when {
            wordLimit <= 0 -> emptyList()
            else -> {
                val topWords = rankedWords.take(wordLimit)
                val deferredDiscourse = rankedWords.drop(wordLimit)
                    .firstOrNull { it.source == "discourse_continuation" }
                if (topWords.any { it.source == "discourse_continuation" } || deferredDiscourse == null) {
                    topWords
                } else {
                    topWords.dropLast(1) + deferredDiscourse
                }
            }
        }
        val words = selectedWords
            .mapIndexed { index, prediction -> toCandidate(prediction, index + 1) }
        val sentences = resolved.predictions.filter { it.isSentenceCompletion }
            .take(sentenceLimit)
            .mapIndexed { index, prediction -> toCandidate(prediction, index + 1) }
        return ContextualCandidateSnapshot(words = words, sentences = sentences)
    }

    fun recordContextualCandidateShown(candidate: PredictionMetricsSession.Candidate?) {
        if (candidate == null) return
        if (!host.allowsTextInspection()) {
            predictionMetricsSession.reset()
            return
        }
        if (predictionMetricsSession.recordShown(candidate)) {
            FcitxApplication.getInstance().applicationScope.launch {
                FcitxApplication.getInstance().predictionMetricsStore.recordShown(1)
                withContext(Dispatchers.Main) {
                    host.scheduleNgramSave()
                }
            }
        }
    }

    /**
     * [committedText] is what accepting [candidate] put into the editor, and [replacedText] the
     * already-typed text it took the place of (empty for a pure append); together they give the
     * keystrokes the candidate saved.
     */
    private fun recordContextualCandidateAccepted(
        candidate: PredictionMetricsSession.Candidate?,
        committed: Boolean,
        committedText: String,
        replacedText: String
    ) {
        if (candidate == null) return
        if (!host.allowsTextInspection()) {
            predictionMetricsSession.reset()
            return
        }
        if (predictionMetricsSession.recordAccepted(candidate, committed)) {
            val savedKeystrokes = PredictionMetricsStore.savedKeystrokes(committedText, replacedText)
            FcitxApplication.getInstance().applicationScope.launch {
                if (OnDeviceAiSupport.isSupported && candidate.source == "ondevice_generated") {
                    try {
                        FcitxApplication.getInstance().generatedSentenceBank.recordAcceptedSuffix(candidate.text)
                    } catch (error: Exception) {
                        Timber.w("Generated material acceptance save failed: ${error.javaClass.simpleName}")
                    }
                }
                FcitxApplication.getInstance().predictionMetricsStore.recordAccepted(candidate.source, savedKeystrokes)
                withContext(Dispatchers.Main) {
                    host.scheduleNgramSave()
                }
            }
        }
    }

    fun recordContextualCandidatesIgnored(offeredSentences: List<String>) {
        if (!host.allowsTextInspection() || offeredSentences.isEmpty()) return
        val capturedSentences = offeredSentences.toList()
        host.enqueuePersonalLearning {
            host.reinforcementTracker().onCandidatesIgnored(capturedSentences)
        }
    }

    fun recordContextualCandidateRejected(sentence: String, heavyPenalty: Boolean = true) {
        if (!host.allowsTextInspection()) return
        val capturedSentence = sentence
        host.enqueuePersonalLearning {
            host.reinforcementTracker().onCandidateRejected(capturedSentence, heavyPenalty)
        }
    }

    /** 마지막으로 만든 예측 결과 메모에서 텍스트가 같은 후보의 replaceLength를 찾는다. */
    private fun replaceLengthForCandidate(sentence: String): Int =
        contextualResultCache?.predictions?.firstOrNull { it.text == sentence }?.replaceLength ?: 0

    private fun commitContextualCandidateText(
        connection: InputConnection,
        replaceLength: Int,
        textToCommit: String
    ): Boolean {
        if (replaceLength <= 0) {
            return host.commitTextToEditor(textToCommit, textToCommit.length)
        }
        val start = host.selection().start
        val end = host.selection().end
        if (start != end || start < replaceLength) return false
        val removedText = connection.getTextBeforeCursor(replaceLength, 0)?.toString() ?: return false
        if (removedText.length != replaceLength || !host.selection().rangeEquals(start, end)) {
            return false
        }
        host.captureCorrectionBoundarySnapshot()
        val replacementStart = start - replaceLength
        if (!replaceAiRange(
                connection = connection,
                start = replacementStart,
                end = start,
                replacement = textToCommit,
                restore = EditorSelection(start, end)
            )
        ) {
            return false
        }
        host.onEditorSuffixDeleted(
            host.editorInfo()?.packageName,
            removedText,
            host.allowsTextInspection()
        )
        host.observeCommittedEditorText(textToCommit)
        host.predictSelection(replacementStart + textToCommit.length)
        host.inputView()?.postRefreshContextualCandidates(16L)
        return true
    }

    private fun commitConfirmedContextualAppend(
        sentence: String,
        appendSnapshot: ContextualAppendSnapshot,
        metricsCandidate: PredictionMetricsSession.Candidate?
    ): Boolean {
        if (!host.allowsTextInspection()) return false
        if (sentence != appendSnapshot.append.suffix || appendSnapshot.inputSessionEpoch != host.inputSessionEpoch()) {
            return false
        }
        if (!host.finishCompositionForDirectAction()) return false
        if (appendSnapshot.inputSessionEpoch != host.inputSessionEpoch()) return false
        val cursor = host.selection().start
        if (cursor != host.selection().end) return false
        val connection = host.inputConnection() ?: return false
        val beforeCursor = connection.getTextBeforeCursor(1024, 0)?.toString() ?: return false
        if (appendSnapshot.inputSessionEpoch != host.inputSessionEpoch() ||
            !host.selection().rangeEquals(cursor, cursor)
        ) {
            return false
        }
        val textToCommit = appendSnapshot.append.insertionFor(beforeCursor) ?: return false
        host.captureCorrectionBoundarySnapshot()
        if (!host.commitAiTextAtCursor(connection, cursor, textToCommit, EditorSelection.collapsed(cursor))) return false

        host.observeCommittedEditorText(textToCommit)
        host.predictSelection(cursor + textToCommit.length)
        host.inputView()?.postRefreshContextualCandidates(16L)
        enqueueContextualSelectionFeedback(
            contextBeforeReinforce = beforeCursor.takeLast(64),
            selectedSentence = sentence,
            reinforcedSentence = appendSnapshot.append.suffix,
            packageName = currentInputEditorInfo.packageName
        )
        predictionEpoch++
        recordContextualCandidateAccepted(
            metricsCandidate?.takeIf { it.text == sentence },
            committed = true,
            committedText = textToCommit,
            replacedText = ""
        )
        return true
    }

    private fun commitConfirmedContextualReplacement(
        sentence: String,
        replacementSnapshot: ContextualReplacementSnapshot,
        metricsCandidate: PredictionMetricsSession.Candidate?
    ): Boolean {
        val replacement = replacementSnapshot.replacement
        if (!host.allowsTextInspection() ||
            !EditorPrivacyPolicy.isConversationalTextField(currentInputEditorInfo, host.capabilityFlags()) ||
            sentence != replacement.replacement ||
            replacementSnapshot.inputSessionEpoch != host.inputSessionEpoch()
        ) {
            return false
        }
        val capturedCursor = host.selection().start
        if (capturedCursor != host.selection().end ||
            capturedCursor != replacementSnapshot.cursor ||
            capturedCursor != replacement.expectedContext.length
        ) {
            return false
        }
        if (!host.finishCompositionForDirectAction() || replacementSnapshot.inputSessionEpoch != host.inputSessionEpoch()) {
            return false
        }
        val cursor = host.selection().start
        if (cursor != host.selection().end ||
            cursor != replacementSnapshot.cursor ||
            cursor != replacement.expectedContext.length
        ) {
            return false
        }
        val connection = host.inputConnection() ?: return false
        val beforeCursor = connection.getTextBeforeCursor(replacement.expectedContext.length + 1, 0)
            ?.toString() ?: return false
        if (beforeCursor != replacement.expectedContext ||
            replacementSnapshot.inputSessionEpoch != host.inputSessionEpoch() ||
            !host.selection().rangeEquals(cursor, cursor)
        ) {
            return false
        }
        if (!replaceAiRange(
                connection = connection,
                start = 0,
                end = replacement.expectedContext.length,
                replacement = replacement.replacement,
                restore = EditorSelection.collapsed(cursor)
            )
        ) {
            return false
        }
        host.predictSelection(replacement.replacement.length)
        host.inputView()?.postRefreshContextualCandidates(16L)
        predictionEpoch++
        recordContextualCandidateAccepted(
            metricsCandidate?.takeIf { it.text == sentence },
            committed = true,
            committedText = replacement.replacement,
            replacedText = replacement.expectedContext
        )
        return true
    }

    fun commitContextualSentence(
        sentence: String,
        metricsCandidate: PredictionMetricsSession.Candidate? = null,
        appendSnapshot: ContextualAppendSnapshot? = null,
        replacementSnapshot: ContextualReplacementSnapshot? = null
    ): Boolean {
        if (!host.allowsTextInspection()) return false
        if (replacementSnapshot != null) {
            return commitConfirmedContextualReplacement(sentence, replacementSnapshot, metricsCandidate)
        }
        if (appendSnapshot != null) {
            return commitConfirmedContextualAppend(sentence, appendSnapshot, metricsCandidate)
        }
        if (!host.finishCompositionForDirectAction()) return false
        val ic = host.inputConnection() ?: return false
        val beforeCursor = ic.getTextBeforeCursor(512, 0)?.toString().orEmpty()
        val capturedMetricsCandidate = metricsCandidate?.takeIf { it.text == sentence }

        val replaceLength = replaceLengthForCandidate(sentence)
        if (replaceLength > 0) {
            val contextBeforeReinforce = beforeCursor.dropLast(replaceLength).takeLast(64)
            val shouldAppendSpace = !sentence.endsWith(" ") && !sentence.endsWith("\n")
            val textToCommit = if (shouldAppendSpace) "$sentence " else sentence
            val committed = commitContextualCandidateText(ic, replaceLength, textToCommit)
            if (committed) {
                enqueueContextualSelectionFeedback(
                    contextBeforeReinforce = contextBeforeReinforce,
                    selectedSentence = sentence,
                    reinforcedSentence = sentence,
                    packageName = currentInputEditorInfo.packageName
                )
                predictionEpoch++
                recordContextualCandidateAccepted(
                    capturedMetricsCandidate,
                    committed = true,
                    committedText = textToCommit,
                    replacedText = beforeCursor.takeLast(replaceLength)
                )
            }
            return committed
        }

        if (!sentence.contains(" ")) {
            val lastSentence = beforeCursor
                .substringAfterLast('.')
                .substringAfterLast('?')
                .substringAfterLast('!')
                .substringAfterLast('\n')
                .trim()
            if (lastSentence.isNotEmpty()) {
                val typoPairs = contextualPredictor.typoEngine.findTypoCorrectionsInSentence(lastSentence)
                val matchingPair = typoPairs.firstOrNull { it.second == sentence }
                if (matchingPair != null && !lastSentence.endsWith(matchingPair.first)) {
                    val correctedSentence = contextualPredictor.typoEngine.correctSentence(lastSentence)
                    if (correctedSentence != null) {
                        val trailingSpaces = beforeCursor.length - beforeCursor.trimEnd().length
                        val replacementLength = lastSentence.length + trailingSpaces
                        val contextBeforeReinforce = beforeCursor.dropLast(replacementLength).takeLast(64)
                        val textToCommit = if (correctedSentence.endsWith(" ") || correctedSentence.endsWith("\n")) correctedSentence else "$correctedSentence "
                        val committed = commitContextualCandidateText(ic, replacementLength, textToCommit)
                        if (committed) {
                            enqueueContextualSelectionFeedback(
                                contextBeforeReinforce = contextBeforeReinforce,
                                selectedSentence = sentence,
                                reinforcedSentence = correctedSentence,
                                packageName = currentInputEditorInfo.packageName
                            )
                            predictionEpoch++
                            recordContextualCandidateAccepted(
                                capturedMetricsCandidate,
                                committed = true,
                                committedText = textToCommit,
                                replacedText = beforeCursor.takeLast(replacementLength)
                            )
                        }
                        return committed
                    }
                }
            }
        }

        val isEmailField = EditorPrivacyPolicy.isEmailAddressField(currentInputEditorInfo, host.capabilityFlags())
        val isEmailDomain = sentence.startsWith("@") || sentence.endsWith(".com") || sentence.endsWith(".net") || sentence.endsWith(".co.kr") || sentence.endsWith(".io") || sentence.endsWith(".org")
        val isUrlField = EditorPrivacyPolicy.isUrlField(currentInputEditorInfo, host.capabilityFlags())
        val isUrlTld = sentence.startsWith(".") && (sentence.endsWith(".com") || sentence.endsWith(".net") || sentence.endsWith(".org") || sentence.endsWith(".kr") || sentence.endsWith(".co.kr") || sentence.endsWith(".io"))
        val urlReplaceLength = if (isUrlField && isUrlTld && beforeCursor.endsWith(".")) 1 else 0

        if (isEmailField && beforeCursor.contains("@")) {
            val afterAt = beforeCursor.substringAfterLast('@')
            val replacementLength = if (sentence.startsWith("@")) {
                afterAt.length + 1
            } else if (afterAt.isNotEmpty() && sentence.startsWith(afterAt)) {
                afterAt.length
            } else {
                0
            }
            val contextBeforeReinforce = beforeCursor.dropLast(replacementLength).takeLast(64)
            val committed = commitContextualCandidateText(ic, replacementLength, sentence)
            if (committed) {
                enqueueContextualSelectionFeedback(
                    contextBeforeReinforce = contextBeforeReinforce,
                    selectedSentence = sentence,
                    reinforcedSentence = sentence,
                    packageName = currentInputEditorInfo.packageName
                )
                predictionEpoch++
                recordContextualCandidateAccepted(
                    capturedMetricsCandidate,
                    committed = true,
                    committedText = sentence,
                    replacedText = beforeCursor.takeLast(replacementLength)
                )
            }
            return committed
        }

        val overlapLengthInBeforeCursor = if (isEmailField || isUrlField) {
            0
        } else {
            contextualPredictor.typoEngine.calculateReplacementOverlap(beforeCursor, sentence)
        }
        val replacementLength = if (overlapLengthInBeforeCursor > 0) {
            overlapLengthInBeforeCursor
        } else {
            urlReplaceLength
        }
        val contextBeforeReinforce = beforeCursor.dropLast(replacementLength).takeLast(64)
        val shouldAppendSpace = !isEmailField && !isUrlField && !isEmailDomain && !isUrlTld && !sentence.endsWith(" ") && !sentence.endsWith("\n")
        val textToCommit = if (shouldAppendSpace) "$sentence " else sentence
        val committed = commitContextualCandidateText(ic, replacementLength, textToCommit)
        if (committed) {
            enqueueContextualSelectionFeedback(
                contextBeforeReinforce = contextBeforeReinforce,
                selectedSentence = sentence,
                reinforcedSentence = sentence,
                packageName = currentInputEditorInfo.packageName
            )
            predictionEpoch++
            recordContextualCandidateAccepted(
                capturedMetricsCandidate,
                committed = true,
                committedText = textToCommit,
                replacedText = beforeCursor.takeLast(replacementLength)
            )
        }
        return committed
    }

    private fun replaceAiRange(
        connection: InputConnection,
        start: Int,
        end: Int,
        replacement: String,
        restore: EditorSelection
    ): Boolean = host.replaceAiRange(connection, start, end, replacement, restore)

    private fun enqueueContextualSelectionFeedback(
        contextBeforeReinforce: String,
        selectedSentence: String,
        reinforcedSentence: String,
        packageName: String
    ) = host.enqueueContextualSelectionFeedback(
        contextBeforeReinforce,
        selectedSentence,
        reinforcedSentence,
        packageName
    )
}

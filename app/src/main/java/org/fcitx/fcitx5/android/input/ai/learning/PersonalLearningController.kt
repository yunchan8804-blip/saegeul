/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai.learning

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.ForwardedKeyObserver
import org.fcitx.fcitx5.android.input.InputView
import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine
import org.fcitx.fcitx5.android.input.ai.ContextualPredictionInput
import org.fcitx.fcitx5.android.input.ai.KoreanCollocationModel
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.PersonalNgramTokenizer
import org.fcitx.fcitx5.android.input.ai.PersonalizedSentenceStore
import org.fcitx.fcitx5.android.input.ai.ReinforcementTracker
import org.fcitx.fcitx5.android.input.ai.TypingDnaCommitSink
import org.fcitx.fcitx5.android.input.ai.TypingDnaCompiler
import org.fcitx.fcitx5.android.input.ai.TypingDnaInstantSync
import org.fcitx.fcitx5.android.input.ai.TypingDnaPersistenceException
import org.fcitx.fcitx5.android.input.ai.TypingDnaProfiler
import org.fcitx.fcitx5.android.input.ai.TypingDnaRepository
import org.fcitx.fcitx5.android.input.ai.TypingDnaVault
import org.fcitx.fcitx5.android.input.ai.UserTypingContextCollector
import org.fcitx.fcitx5.android.input.ai.ondevice.RecentSentSentences
import org.fcitx.fcitx5.android.input.ai.persona.PersonaRegistry
import org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphStore
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionSessionTracker
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import timber.log.Timber
import java.io.File

/**
 * Logs a failed learning task instead of letting it reach the process's uncaught exception
 * handler, which would kill the keyboard. Cancellation never reaches a [CoroutineExceptionHandler],
 * so it is not reported.
 */
private val personalLearningFailureHandler = CoroutineExceptionHandler { _, throwable ->
    Timber.e(throwable, "Personal learning task failed")
}

/**
 * Launches [task] in [scope] once [previous] has finished, so learning tasks run one at a time in
 * enqueue order. [Job.join] returns however [previous] ended, so a failed task never stalls the
 * ones queued behind it; the failure is logged by [personalLearningFailureHandler], which only
 * receives it when [scope] is supervised like [FcitxApplication.applicationScope].
 */
internal fun launchSerializedLearning(
    scope: CoroutineScope,
    previous: Job?,
    task: suspend CoroutineScope.() -> Unit
): Job = scope.launch(personalLearningFailureHandler) {
    previous?.join()
    task()
}

/** A transient collection-progress hint for the keyboard's status row (see [PersonalLearningController.collectionFeedback]). */
data class CollectionFeedbackEvent(
    val category: String,
    val pendingCount: Int,
    val threshold: Int,
    val compiled: Boolean,
    val atMs: Long
)

/**
 * Owns on-device personal learning: the learning stores, Typing DNA observation, the
 * delete-and-retype correction boundary, the serialized learning queue and the debounced
 * n-gram/correction saves, which run on this controller's own main-thread [Handler].
 */
class PersonalLearningController(private val host: Host) {

    /** The service state and actions personal learning reads or triggers. */
    data class Host(
        val filesDir: () -> File,
        val collocationModel: () -> KoreanCollocationModel,
        val advancePredictionEpoch: () -> Unit,
        val allowsTextInspection: () -> Boolean,
        val editorInfo: () -> EditorInfo?,
        val inputConnection: () -> InputConnection?,
        val activePreedit: () -> String,
        val appPersona: () -> String?,
        val isDestroyed: () -> Boolean,
        val lifecycleScope: () -> CoroutineScope,
        val inputView: () -> InputView?
    )

    /** Avoids logging a "privacy" collection drop on every keystroke of the same editor session. */
    private var collectionPrivacyDropLogged = false

    /** Whether the first Typing DNA sentence of the current editor session already gave feedback. */
    private var collectionFeedbackEmittedForSession = false

    private val mutableCollectionFeedback = MutableStateFlow<CollectionFeedbackEvent?>(null)

    /** A transient collection-progress hint for the keyboard's status row. */
    val collectionFeedback: StateFlow<CollectionFeedbackEvent?> = mutableCollectionFeedback.asStateFlow()

    private fun publishCollectionFeedbackIfEnabled(event: CollectionFeedbackEvent) {
        if (!AppPrefs.getInstance().internal.collectionFeedbackInKeyboard.getValue()) return
        mutableCollectionFeedback.value = event
    }

    val morphologyEngine by lazy { ChoseongMorphologyEngine() }

    val personalizedStore by lazy {
        val file = File(host.filesDir(), "personalized_sentences.json")
        PersonalizedSentenceStore(
            storageFile = file,
            morphology = morphologyEngine,
            cipher = FcitxApplication.getInstance().vaultCipher
        ).apply {
            load()
        }
    }

    val reinforcementTracker by lazy {
        ReinforcementTracker(store = personalizedStore)
    }

    val typingDnaRepository: TypingDnaRepository
        get() = FcitxApplication.getInstance().typingDnaRepository

    val typingDnaProfiler by lazy { TypingDnaProfiler() }

    val typingDnaCompiler: TypingDnaCompiler by lazy {
        TypingDnaCompiler(
            collocationModel = host.collocationModel(),
            sentenceStore = personalizedStore,
            repository = typingDnaRepository
        ).apply {
            runCatching {
                compileFullProfile(typingDnaRepository.load())
            }
        }
    }

    val typingDnaVault: TypingDnaVault
        get() = FcitxApplication.getInstance().typingDnaVault

    val typingDnaInstantSync by lazy {
        TypingDnaInstantSync(
            vault = typingDnaVault,
            repository = typingDnaRepository,
            profiler = typingDnaProfiler,
            compiler = typingDnaCompiler
        )
    }

    val personalNgramModel: PersonalNgramModel
        get() = FcitxApplication.getInstance().personalNgramModel

    val personalSentenceVault: PersonalSentenceVault
        get() = FcitxApplication.getInstance().personalSentenceVault

    /**
     * In-memory-only record of sentences this user recently sent, per app package. Owned by the
     * current service instance (never persisted), used only to enrich the automatic suggestion
     * prompt with "what I just said in this app".
     */
    val recentSentSentences = RecentSentSentences()

    val personalGraphStore: PersonalGraphStore
        get() = FcitxApplication.getInstance().personalGraphStore

    val typoCorrector: KeyboardAwareTypoCorrector
        get() = FcitxApplication.getInstance().typoCorrector

    val baseKoreanVocabulary: BaseKoreanVocabulary
        get() = FcitxApplication.getInstance().baseKoreanVocabulary

    val correctionPatternStore: CorrectionPatternStore
        get() = FcitxApplication.getInstance().correctionPatternStore

    val correctionSessionTracker = CorrectionSessionTracker()
    private val correctionSentenceTerminators = charArrayOf('.', '?', '!', '\n')

    private var personalLearningTail: Job? = null

    private val ngramSaveHandler = Handler(Looper.getMainLooper())
    private var pendingNgramSaveRunnable: Runnable? = null
    private var pendingCorrectionSaveRunnable: Runnable? = null

    /**
     * UI 경로용 비동기 즉시 동기화다. collector flush는 predictionEpoch·policy·handler에
     * 접근하므로 Main.immediate에서 수행하고, vault 동기화·컴파일·저장은 IO에서 수행한다.
     */
    suspend fun triggerInstantTypingDnaSyncAsync() {
        val pendingPersonalLearning = withContext(Dispatchers.Main.immediate) {
            userTypingContextCollector.flushAllPending()
            personalLearningTail
        }
        pendingPersonalLearning?.join()
        withContext(Dispatchers.IO) {
            persistInstantTypingDnaSync()
        }
    }

    private fun persistInstantTypingDnaSync() {
        val before = typingDnaVault.totalBufferedCount()
        typingDnaInstantSync.syncNow()
        if (before == 0) {
            runCatching {
                typingDnaCompiler.compileFullProfile(typingDnaRepository.load())
            }
        }
        personalizedStore.save()
    }

    fun attachTypingDnaBatchCompiler() {
        typingDnaVault.setOnBatchReady { category, sentences ->
            FcitxApplication.getInstance().collectionDiagnostics.batchReady(category, sentences.size)
            host.lifecycleScope().launch(Dispatchers.IO) {
                try {
                    typingDnaInstantSync.syncNow(category)
                    personalizedStore.save()
                    FcitxApplication.getInstance().collectionDiagnostics.compiled(category, ok = true)
                    publishCollectionFeedbackIfEnabled(
                        CollectionFeedbackEvent(
                            category = category,
                            pendingCount = 0,
                            threshold = typingDnaVault.thresholdPerCategory,
                            compiled = true,
                            atMs = System.currentTimeMillis()
                        )
                    )
                } catch (exception: TypingDnaPersistenceException) {
                    android.util.Log.w(
                        "SaegeulAI",
                        "Typing DNA persistence failed: ${exception.javaClass.simpleName}"
                    )
                    FcitxApplication.getInstance().collectionDiagnostics.compiled(category, ok = false)
                }
            }
        }
    }

    /** Opens a fresh collection-feedback window for the next editor session. */
    fun onStartInput() {
        collectionPrivacyDropLogged = false
        collectionFeedbackEmittedForSession = false
    }

    /** Saves the personal models right away instead of waiting for the pending debounce. */
    fun onFinishInput() {
        pendingNgramSaveRunnable?.let { ngramSaveHandler.removeCallbacks(it) }
        pendingNgramSaveRunnable = null
        launchPersonalModelSave()
        pendingCorrectionSaveRunnable?.let { ngramSaveHandler.removeCallbacks(it) }
        pendingCorrectionSaveRunnable = null
        host.lifecycleScope().launch(Dispatchers.IO) { correctionPatternStore.save() }
    }

    fun onDestroy() {
        typingDnaVault.setOnBatchReady(null)
        pendingNgramSaveRunnable?.let { ngramSaveHandler.removeCallbacks(it) }
        pendingNgramSaveRunnable = null
        pendingCorrectionSaveRunnable?.let { ngramSaveHandler.removeCallbacks(it) }
        pendingCorrectionSaveRunnable = null
    }

    /** Text-inspection actions do not read password/private editors, even when fully offline. */
    fun flushTypingDnaForCurrentEditor() {
        typingDnaCommitSink.onEditorFinished(
            host.editorInfo()?.packageName,
            host.allowsTextInspection()
        )
    }

    /**
     * A chat app's own send button clears the editor without ever calling our return-key or
     * finish-input handlers, so Typing DNA would otherwise sit unflushed until the editor closes.
     * Detect the "just emptied" selection and flush pending text for that package.
     */
    fun flushTypingDnaIfEditorEmptied(newSelStart: Int, newSelEnd: Int) {
        if (newSelStart != 0 || newSelEnd != 0) return
        val pkg = host.editorInfo()?.packageName ?: return
        if (!userTypingContextCollector.hasPending(pkg)) return
        if (!host.allowsTextInspection()) return
        val ic = host.inputConnection() ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val after = ic.getTextAfterCursor(1, 0)
        if (!before.isNullOrEmpty() || !after.isNullOrEmpty()) return
        flushTypingDnaForCurrentEditor()
    }

    fun observeCommittedEditorText(text: String) {
        if (!host.allowsTextInspection()) {
            if (!collectionPrivacyDropLogged) {
                collectionPrivacyDropLogged = true
                FcitxApplication.getInstance().collectionDiagnostics.dropped("privacy")
            }
            return
        }
        handleCorrectionWordBoundary(text)
        if (text.isEmpty()) return
        val pkg = host.editorInfo()?.packageName ?: return
        typingDnaCommitSink.onEditorTextCommitted(pkg, text, true)
    }

    /**
     * 우리 커밋 경로(commitTextToEditor)를 거치지 않고 raw KeyEvent로 곧장 편집기에 전달되는
     * 스페이스·문장부호·엔터 등을 관찰한다. 개행은 실제로 개행이 삽입되는 멀티라인 편집기에서만
     * 문장 종결로 취급해 관찰한다.
     */
    fun observeForwardedKeyIfPrintable(keyCode: Int, unicodeChar: Int, metaState: Int) {
        if (!host.allowsTextInspection()) return
        val printable = ForwardedKeyObserver.printableText(keyCode, unicodeChar, metaState) ?: return
        if (printable == "\n" &&
            host.editorInfo()!!.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0
        ) return
        observeCommittedEditorText(printable)
    }

    /**
     * 커서 바로 앞의 "쓰고 있는 어절"을 계산한다. 아직 편집기에 커밋되지 않은 조합/프리에딧과
     * 한글 버퍼드 입력 모드의 미전송 세그먼트까지 이어붙여 판단한다.
     */
    fun currentWordBeforeCursor(): String {
        val ic = host.inputConnection() ?: return ""
        val beforeCursor = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val activePreedit = host.activePreedit()
        return ContextualPredictionInput.resolve(beforeCursor, activePreedit).stroke
    }

    /** 어절 경계 커밋(observeCommittedEditorText)이 실제 IC 변경 직전에 잡아 둔 스냅샷. */
    private var correctionBoundarySnapshot: String = ""

    /** 세션이 활성일 때만 IPC를 태워 현재 어절을 스냅샷한다. IPC 절약을 위해 비활성이면 빈 문자열. */
    fun captureCorrectionBoundarySnapshot() {
        correctionBoundarySnapshot = if (correctionSessionTracker.isActive() && host.allowsTextInspection()) {
            currentWordBeforeCursor()
        } else {
            ""
        }
    }

    private fun recordCorrectionPairIfPresent(pair: Pair<String, String>?) {
        val (typed, corrected) = pair ?: return
        if (correctionPatternStore.recordCorrection(typed, corrected)) {
            typoCorrector.addWord(
                corrected,
                PersonalNgramModel.personalPrior(
                    personalNgramModel.unigramCount(corrected)
                )
            )
            scheduleCorrectionSave()
        }
    }

    /** 공백/문장 종결 부호로 끝나는 커밋을 어절 경계로 보고, 지운-다시쓴 쌍이 있으면 학습한다. */
    private fun handleCorrectionWordBoundary(text: String) {
        if (text.isEmpty() || !correctionSessionTracker.isActive()) return
        val isBoundary = text.first().isWhitespace() || text.last() in correctionSentenceTerminators
        if (!isBoundary) return
        recordCorrectionPairIfPresent(correctionSessionTracker.onWordBoundary(correctionBoundarySnapshot))
    }

    /** 엔터·입력 종료처럼 observeCommittedEditorText를 거치지 않는 경계에서 직접 호출한다. */
    fun finalizeCorrectionSessionAtBoundary() {
        if (!host.allowsTextInspection() || !correctionSessionTracker.isActive()) return
        recordCorrectionPairIfPresent(correctionSessionTracker.onWordBoundary(currentWordBeforeCursor()))
    }

    fun scheduleNgramSave() {
        pendingNgramSaveRunnable?.let { ngramSaveHandler.removeCallbacks(it) }
        val runnable = Runnable { launchPersonalModelSave() }
        pendingNgramSaveRunnable = runnable
        ngramSaveHandler.postDelayed(runnable, 1500L)
    }

    fun enqueuePersonalLearning(
        afterLearningOnMain: (() -> Unit)? = null,
        action: () -> Unit
    ) {
        val previous = personalLearningTail
        personalLearningTail = launchSerializedLearning(FcitxApplication.getInstance().applicationScope, previous) {
            action()
            val persistAfterDestroyed = withContext(Dispatchers.Main) {
                host.advancePredictionEpoch()
                if (host.isDestroyed()) {
                    true
                } else {
                    scheduleNgramSave()
                    host.inputView()?.postRefreshContextualCandidates(16L)
                    afterLearningOnMain?.invoke()
                    false
                }
            }
            if (persistAfterDestroyed) {
                personalizedStore.save()
                personalNgramModel.save()
                personalSentenceVault.save()
                FcitxApplication.getInstance().predictionMetricsStore.save()
            }
        }
    }

    fun enqueueContextualSelectionFeedback(
        contextBeforeReinforce: String,
        selectedSentence: String,
        reinforcedSentence: String,
        packageName: String
    ) {
        enqueuePersonalLearning {
            reinforcementTracker.onCandidateSelected(selectedSentence, packageName)
            personalNgramModel.reinforce(contextBeforeReinforce, reinforcedSentence, packageName)
        }
    }

    /** Persists the on-device personal learning stores (n-gram model + sentence RAG vault) off the main thread. */
    private fun launchPersonalModelSave() {
        host.lifecycleScope().launch(Dispatchers.IO) {
            personalNgramModel.save()
            personalSentenceVault.save()
            FcitxApplication.getInstance().predictionMetricsStore.save()
        }
    }

    private fun scheduleCorrectionSave() {
        pendingCorrectionSaveRunnable?.let { ngramSaveHandler.removeCallbacks(it) }
        val runnable = Runnable {
            host.lifecycleScope().launch(Dispatchers.IO) { correctionPatternStore.save() }
        }
        pendingCorrectionSaveRunnable = runnable
        ngramSaveHandler.postDelayed(runnable, 1500L)
    }

    val typingDnaCommitSink by lazy {
        TypingDnaCommitSink(userTypingContextCollector)
    }

    val userTypingContextCollector by lazy {
        UserTypingContextCollector(
            onSentenceCommitted = { pkg, sentence ->
                val capturedPackageName = pkg
                val capturedSentence = sentence
                // Computed once here (not inside the queued action below) so a profile change
                // that happens while this commit is still queued can't retroactively change
                // which persona it gets attributed to.
                val capturedPersona = PersonaRegistry.classify(
                    capturedPackageName, host.appPersona()
                )
                val trackLearnedMetrics = host.allowsTextInspection()
                var feedbackCategory: String? = null
                var feedbackPendingCount = 0
                enqueuePersonalLearning(
                    afterLearningOnMain = {
                        val category = feedbackCategory
                        if (category != null && !collectionFeedbackEmittedForSession) {
                            collectionFeedbackEmittedForSession = true
                            publishCollectionFeedbackIfEnabled(
                                CollectionFeedbackEvent(
                                    category = category,
                                    pendingCount = feedbackPendingCount,
                                    threshold = typingDnaVault.thresholdPerCategory,
                                    compiled = false,
                                    atMs = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                ) {
                    typingDnaVault.recordSentence(capturedPackageName, capturedSentence, personaOverride = capturedPersona)
                    feedbackCategory = capturedPersona
                    feedbackPendingCount = typingDnaVault.pendingByCategory()[capturedPersona] ?: 0
                    val beforeLearning = if (trackLearnedMetrics) personalNgramModel.stats() else null
                    personalNgramModel.learn(capturedSentence, capturedPackageName, personaOverride = capturedPersona)
                    beforeLearning?.let { before ->
                        val after = personalNgramModel.stats()
                        val learnedSentences = (after.learnedSentences - before.learnedSentences).coerceAtLeast(0)
                        val learnedWords = (after.unigrams - before.unigrams).coerceAtLeast(0)
                        if (learnedSentences > 0 || learnedWords > 0) {
                            FcitxApplication.getInstance().predictionMetricsStore.recordLearned(learnedSentences, learnedWords)
                        }
                    }
                    personalSentenceVault.record(capturedSentence, capturedPackageName, personaOverride = capturedPersona)
                    recentSentSentences.record(capturedPackageName, capturedSentence)
                    PersonalNgramTokenizer.tokenize(capturedSentence).forEach { token ->
                        typoCorrector.addWord(
                            token,
                            PersonalNgramModel.personalPrior(
                                personalNgramModel.unigramCount(token)
                            )
                        )
                    }
                }
            },
            diagnostics = FcitxApplication.getInstance().collectionDiagnostics
        )
    }
}

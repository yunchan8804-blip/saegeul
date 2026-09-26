/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.debug.gemma

import android.app.ActivityManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationRuntime
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationState
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationEligibility
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationSnapshot
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaGenerationWaitReason
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaInstallState
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaMaterialGenerator
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelInstaller
import java.util.Locale
import kotlin.math.min

class GemmaExperimentActivity : AppCompatActivity() {

    private val generator by lazy { GemmaMaterialGenerator(this) }
    private var operation: Job? = null

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var downloadButton: Button
    private lateinit var importButton: Button
    private lateinit var generateButton: Button
    private lateinit var cancelButton: Button
    private lateinit var clearButton: Button
    private lateinit var deleteModelButton: Button
    private lateinit var backendGroup: RadioGroup
    private lateinit var accumulationToggle: Switch
    private lateinit var accumulationProgress: ProgressBar
    private lateinit var accumulationStatus: TextView
    private lateinit var storedSentenceSummary: TextView
    private lateinit var accumulationDetails: TextView
    private lateinit var preparationWaitReason: TextView
    private lateinit var scheduleAccumulationButton: Button
    private lateinit var retryAccumulationButton: Button

    private val accumulationStore by lazy { GemmaAccumulationStore.get(applicationContext) }
    private var accumulationState = GemmaAccumulationState()
    private var accumulationLoaded = false
    private var accumulationLoadJob: Job? = null
    private var accumulationOperation: Job? = null
    private var accumulationLoadError: String? = null
    private var manualBusy = false
    private var modelReady = false
    private var initialModelReadyCheckComplete = false
    private var renderingAccumulationToggle = false
    private var accumulationPolicyRefreshAttempted = false
    private var eligibilitySnapshot: GemmaGenerationSnapshot? = null

    private var importJob: Job? = null

    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (importJob?.isActive == true) return@registerForActivityResult
        importJob = lifecycleScope.launch {
            status.text = "선택한 모델을 검증하며 가져오는 중입니다…"
            val result = GemmaModelInstaller.importFrom(applicationContext, uri)
            status.text = result.fold(
                onSuccess = { "모델 가져오기와 SHA-256 검증이 완료되었습니다." },
                onFailure = { error -> "오류: ${error.message ?: error.javaClass.simpleName}" }
            )
            refreshModelReady()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContent())
        renderAccumulation()
        observeAccumulation()
        observePreparationWaitReason()
        observeInstallState()
        refreshModelReady()
    }

    override fun onResume() {
        super.onResume()
        refreshModelReady()
    }

    override fun onStop() {
        generator.cancel()
        operation?.cancel()
        super.onStop()
    }

    private fun createContent(): ScrollView {
        val outerInset = dp(24)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(24), 0, dp(32))
        }
        content.addStacked(title("AI 문장 준비"), 0)
        content.addStacked(caption("Gemma · 기기 안에서 준비"), dp(24))

        val modelSection = sectionCard()
        modelSection.addStacked(sectionTitle("모델 준비"), dp(8))
        modelSection.addStacked(body("기기 안에서 문장을 준비하려면 ${formatBytes(GemmaModelFiles.MODEL_BYTES)} 모델이 필요합니다. 다운로드하거나 이미 받은 파일을 가져오세요."), dp(16))
        downloadButton = primaryButton("모델 다운로드") { showDownloadConfirmation() }
        importButton = button("받은 모델 가져오기") { importModel.launch(arrayOf("application/octet-stream", "*/*")) }
        modelSection.addStacked(downloadButton, dp(12))
        modelSection.addStacked(importButton, dp(8))
        status = body(INITIAL_MODEL_STATUS).apply {
            contentDescription = "모델 준비 상태"
        }
        modelSection.addStacked(status, dp(16))
        cancelButton = button("취소") { cancelOperation() }.apply {
            isEnabled = false
            contentDescription = "진행 중인 작업 취소"
        }
        modelSection.addStacked(cancelButton, dp(8))
        content.addStacked(modelSection, dp(24))

        val preparationSection = sectionCard()
        preparationSection.addStacked(sectionTitle("문장 준비"), dp(8))
        accumulationToggle = Switch(this).apply {
            text = "문장 미리 준비"
            contentDescription = "문장 재료 자동 축적"
            minimumHeight = dp(48)
            setTextColor(color(R.color.saegeul_ink))
            setOnCheckedChangeListener { _, enabled ->
                if (!renderingAccumulationToggle) setAccumulationEnabled(enabled)
            }
        }
        preparationSection.addStacked(accumulationToggle, dp(12))
        preparationSection.addStacked(body("자동 준비가 켜져 있으면 키보드가 쉬는 동안 공개 주제의 문장을 조금씩 쌓고, 입력할 때는 저장한 문장을 바로 찾습니다."), dp(4))
        preparationWaitReason = TextView(this).apply {
            contentDescription = "문장 준비 대기 이유"
            setTextColor(color(R.color.saegeul_secondary))
            textSize = 14f
        }
        preparationSection.addStacked(preparationWaitReason, dp(16))
        accumulationProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1
            progress = 0
            contentDescription = "문장 재료 축적 진행"
        }
        preparationSection.addStacked(accumulationProgress, dp(12))
        accumulationStatus = TextView(this).apply {
            contentDescription = "문장 재료 축적 상태"
            setTextColor(color(R.color.saegeul_ink))
            textSize = 16f
        }
        storedSentenceSummary = TextView(this).apply {
            setTextColor(color(R.color.saegeul_ink))
            textSize = 16f
        }
        accumulationDetails = TextView(this).apply {
            contentDescription = "문장 재료 축적 세부 정보"
            setTextColor(color(R.color.saegeul_secondary))
            textSize = 14f
        }
        preparationSection.addStacked(accumulationStatus, dp(12))
        preparationSection.addStacked(storedSentenceSummary, dp(4))
        scheduleAccumulationButton = button("지금 보충 예약") { requestAccumulation() }.apply {
            contentDescription = "지금 문장 재료 보충 예약"
        }
        retryAccumulationButton = button("다시 보충 시도") { retryAccumulation() }.apply {
            contentDescription = "문장 재료 다시 보충 시도"
        }
        preparationSection.addStacked(scheduleAccumulationButton, dp(16))
        content.addStacked(preparationSection, dp(16))

        val guidanceSection = sectionCard()
        guidanceSection.addStacked(sectionTitle("준비 방식과 개인정보"), dp(8))
        guidanceSection.addStacked(body("개인 입력이나 금고 내용은 사용하지 않습니다. 충전하지 않아도 배터리 30% 이상이면 준비하고, 기기가 따뜻하면 적은 양으로 줄입니다. 심한 발열·절전 모드·입력 중에는 잠시 쉽니다."), dp(16))
        content.addStacked(guidanceSection, dp(16))

        val advancedSection = sectionCard()
        val advancedControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val advancedToggle = button("고급 실험 설정") {
            val expanded = advancedControls.visibility != View.VISIBLE
            advancedControls.visibility = if (expanded) View.VISIBLE else View.GONE
            it as MaterialButton
            it.text = if (expanded) "고급 실험 설정 접기" else "고급 실험 설정"
            it.contentDescription = it.text
        }.apply {
            contentDescription = "고급 실험 설정"
        }
        advancedSection.addStacked(advancedToggle, 0)
        backendGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@GemmaExperimentActivity).apply {
                id = CPU_ID
                text = "CPU"
                isChecked = true
            })
            addView(RadioButton(this@GemmaExperimentActivity).apply {
                id = GPU_ID
                text = "GPU"
            })
        }
        advancedControls.addStacked(sectionTitle("실험 제어"), dp(20))
        advancedControls.addStacked(backendGroup, dp(8))
        advancedControls.addStacked(caption(memoryGuidance()), dp(8))
        advancedControls.addStacked(sectionTitle("문장 준비 상세"), dp(20))
        advancedControls.addStacked(accumulationDetails, dp(8))
        advancedControls.addStacked(retryAccumulationButton, dp(8))
        generateButton = button("고정 재료 생성") { generateMaterials() }
        clearButton = button("실험 재료 삭제") { clearMaterials() }
        deleteModelButton = button("모델 삭제") { showDeleteModelConfirmation() }
        advancedControls.addStacked(generateButton, dp(16))
        advancedControls.addStacked(clearButton, dp(8))
        advancedControls.addStacked(deleteModelButton, dp(8))
        advancedControls.addStacked(sectionTitle("원문 출력"), dp(20))
        output = TextView(this).apply {
            setTextColor(color(R.color.saegeul_secondary))
            textSize = 14f
        }
        advancedControls.addStacked(output, dp(8))
        advancedSection.addView(advancedControls)
        content.addStacked(advancedSection, dp(16))

        val container = FrameLayout(this).apply {
            setPadding(outerInset, 0, outerInset, 0)
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL
            ))
            addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val targetWidth = min(right - left - paddingLeft - paddingRight, dp(640))
                val params = content.layoutParams as FrameLayout.LayoutParams
                if (params.width != targetWidth) {
                    params.width = targetWidth
                    params.gravity = Gravity.CENTER_HORIZONTAL
                    content.layoutParams = params
                }
            }
        }
        return ScrollView(this).apply {
            setBackgroundColor(color(R.color.saegeul_canvas))
            isFillViewport = true
            addView(container)
        }
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 28f
        setTextColor(color(R.color.saegeul_ink))
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(color(R.color.saegeul_ink))
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(color(R.color.saegeul_ink))
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(color(R.color.saegeul_secondary))
    }

    private fun sectionCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = GradientDrawable().apply {
            setColor(color(R.color.saegeul_surface))
            cornerRadius = dp(20).toFloat()
        }
        elevation = 0f
    }

    private fun LinearLayout.addStacked(view: View, topMargin: Int) {
        addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin })
    }

    private fun button(text: String, onClick: (View) -> Unit): MaterialButton = styledButton(text, onClick, false)

    private fun primaryButton(text: String, onClick: (View) -> Unit): MaterialButton = styledButton(text, onClick, true)

    private fun styledButton(text: String, onClick: (View) -> Unit, primary: Boolean): MaterialButton = MaterialButton(this).apply {
        this.text = text
        minimumHeight = dp(48)
        minHeight = dp(48)
        cornerRadius = dp(12)
        insetTop = 0
        insetBottom = 0
        elevation = 0f
        backgroundTintList = enabledColorStateList(
            if (primary) R.color.saegeul_action else R.color.saegeul_subtle,
            R.color.saegeul_subtle
        )
        setTextColor(enabledColorStateList(
            if (primary) R.color.saegeul_on_action else R.color.saegeul_ink,
            R.color.saegeul_secondary
        ))
        if (!primary) {
            strokeColor = ColorStateList.valueOf(color(R.color.saegeul_outline))
            strokeWidth = dp(1)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        setOnClickListener(onClick)
    }

    private fun enabledColorStateList(enabledColor: Int, disabledColor: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(color(enabledColor), color(disabledColor))
    )

    private fun color(colorRes: Int): Int = getColor(colorRes)

    private fun showDownloadConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Gemma 모델 다운로드")
            .setMessage(
                "약 2.59 GB를 다운로드합니다. 출처는 Hugging Face의 ${GemmaModelFiles.MODEL_ID}이며, " +
                    "고정 HTTPS 주소만 사용합니다. Wi-Fi에서만 받으며, 완전 오프라인 모드에서는 다운로드할 수 없습니다. " +
                    "개인 입력이나 금고 내용은 전송하지 않습니다."
            )
            .setNegativeButton("취소", null)
            .setPositiveButton("다운로드") { _, _ ->
                GemmaModelInstaller.start(applicationContext, allowMobileData = false)
            }
            .show()
    }

    private fun observeInstallState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GemmaModelInstaller.state(applicationContext).collect { renderInstallState(it) }
            }
        }
    }

    private fun renderInstallState(state: GemmaInstallState) {
        if (!::status.isInitialized) return
        when (state) {
            is GemmaInstallState.Downloading -> {
                status.text = "다운로드 중: ${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}"
                cancelButton.isEnabled = true
            }
            GemmaInstallState.Verifying -> status.text = "받은 모델을 검증하는 중입니다…"
            is GemmaInstallState.WaitingForNetwork -> {
                status.text = "네트워크(Wi-Fi)를 기다리는 중입니다: ${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}"
                cancelButton.isEnabled = true
            }
            is GemmaInstallState.Paused ->
                status.text = "다운로드가 일시정지되었습니다: ${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}"
            is GemmaInstallState.Failed -> {
                status.text = "다운로드 실패: ${state.reason}"
                cancelButton.isEnabled = false
            }
            GemmaInstallState.Installed -> {
                if (modelReady) status.text = MODEL_READY_STATUS
                cancelButton.isEnabled = false
                refreshModelReady()
            }
            GemmaInstallState.NotInstalled, GemmaInstallState.Unsupported -> cancelButton.isEnabled = false
        }
    }

    private fun generateMaterials() {
        launchExclusive {
            requireManualGenerationAllowed()
            status.text = "모델 검증·Gemma 초기화·고정 재료 생성을 실행 중입니다…"
            coroutineScope {
                var guardFailure: GemmaGenerationWaitReason? = null
                val guard = launch {
                    while (true) {
                        delay(MANUAL_GENERATION_GUARD_INTERVAL_MS)
                        manualGenerationWaitReason()?.let { reason ->
                            guardFailure = reason
                            generator.cancel()
                            return@launch
                        }
                    }
                }
                try {
                    val result = try {
                        generator.generate(
                            GemmaModelFiles.modelFile(this@GemmaExperimentActivity),
                            backendGroup.checkedRadioButtonId == GPU_ID
                        )
                    } catch (cancelled: CancellationException) {
                        guardFailure?.let { reason ->
                            throw IllegalStateException(reason.message, cancelled)
                        }
                        throw cancelled
                    }
                    currentCoroutineContext().ensureActive()
                    guardFailure?.let { reason ->
                        throw IllegalStateException(reason.message)
                    }
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        throw CancellationException("화면 이탈 뒤에는 생성 결과를 저장하지 않습니다.")
                    }
                    requireManualGenerationAllowed()
                    val saved = withContext(Dispatchers.IO) {
                        currentCoroutineContext().ensureActive()
                        FcitxApplication.getInstance().generatedSentenceBank.addGenerated(
                            response = result.text,
                            modelId = GemmaModelFiles.MODEL_ID,
                            modelSha256 = GemmaModelFiles.MODEL_SHA256
                        )
                    }
                    val count = withContext(Dispatchers.IO) {
                        FcitxApplication.getInstance().generatedSentenceBank.sentenceCount
                    }
                    status.text = "초기화 ${result.initializationMs}ms · 생성 ${result.generationMs}ms · " +
                        "${result.text.length}자 · 이번 저장 ${saved}개 · 총 ${count}개"
                    output.text = result.text
                } finally {
                    withContext(NonCancellable) {
                        guard.cancelAndJoin()
                    }
                }
            }
        }
    }

    private fun showDeleteModelConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Gemma 모델 삭제")
            .setMessage("내부에 저장한 Gemma 모델과 부분 다운로드 파일을 삭제합니다. 생성 재료는 삭제하지 않습니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                launchExclusive {
                    GemmaModelFiles.deleteModel(this@GemmaExperimentActivity)
                    status.text = "Gemma 모델을 삭제했습니다."
                    refreshModelReady()
                }
            }
            .show()
    }

    private fun clearMaterials() {
        launchExclusive {
            withContext(Dispatchers.IO) {
                FcitxApplication.getInstance().generatedSentenceBank.clear()
            }
            val count = withContext(Dispatchers.IO) {
                FcitxApplication.getInstance().generatedSentenceBank.sentenceCount
            }
            output.text = ""
            status.text = "실험 재료를 삭제했습니다. 현재 저장 수: $count"
        }
    }

    private fun cancelOperation() {
        if (generator.isRunning || operation?.isActive == true) {
            status.text = "Gemma 생성 취소를 요청하고 종료를 기다리는 중입니다…"
        }
        generator.cancel()
        operation?.cancel()
        GemmaModelInstaller.pause(applicationContext)
    }

    private fun observeAccumulation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    accumulationStore.state.collect { state ->
                        accumulationState = state
                        if (state.error == null) accumulationLoadError = null
                        renderAccumulation()
                    }
                }
            }
        }
        loadAccumulationState()
    }

    private fun observePreparationWaitReason() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    eligibilitySnapshot = withContext(Dispatchers.IO) {
                        GemmaGenerationEligibility.snapshot(applicationContext)
                    }
                    renderPreparationWaitReason()
                    delay(PREPARATION_WAIT_REASON_REFRESH_MS)
                }
            }
        }
    }

    private fun loadAccumulationState() {
        if (accumulationLoadJob?.isActive == true) return
        accumulationLoadError = null
        accumulationLoadJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { accumulationStore.load() }
                accumulationLoaded = true
                if (!accumulationPolicyRefreshAttempted) {
                    withContext(Dispatchers.IO) {
                        GemmaAccumulationScheduler.refreshPolicyIfEnabled(applicationContext)
                    }
                    accumulationPolicyRefreshAttempted = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                accumulationLoadError = error.message ?: error.javaClass.simpleName
                renderAccumulation()
            } finally {
                accumulationLoadJob = null
                renderAccumulation()
            }
        }
    }

    private fun refreshModelReady() {
        lifecycleScope.launch {
            modelReady = withContext(Dispatchers.IO) {
                GemmaModelFiles.modelFile(applicationContext).let { file ->
                    file.isFile && file.length() == GemmaModelFiles.MODEL_BYTES
                }
            }
            if (!initialModelReadyCheckComplete) {
                initialModelReadyCheckComplete = true
                if (modelReady && status.text == INITIAL_MODEL_STATUS) {
                    status.text = MODEL_READY_STATUS
                }
            }
            renderAccumulation()
        }
    }

    private fun setAccumulationEnabled(enabled: Boolean) {
        if (!accumulationLoaded) {
            renderAccumulation()
            return
        }
        if (enabled && !modelReady) {
            renderAccumulation()
            return
        }
        runAccumulationAction {
            GemmaAccumulationScheduler.setEnabled(applicationContext, enabled)
        }
    }

    private fun requestAccumulation() {
        if (!accumulationLoaded) {
            renderAccumulation()
            return
        }
        runAccumulationAction {
            GemmaAccumulationScheduler.requestNow(applicationContext)
        }
    }

    private fun retryAccumulation() {
        if (!accumulationLoaded) {
            loadAccumulationState()
            return
        }
        runAccumulationAction {
            GemmaAccumulationScheduler.retryExhausted(applicationContext)
        }
    }

    private fun runAccumulationAction(action: suspend () -> Unit) {
        if (accumulationOperation?.isActive == true) {
            renderAccumulation()
            return
        }
        accumulationLoadError = null
        accumulationOperation = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                accumulationLoadError = error.message ?: error.javaClass.simpleName
                renderAccumulation()
            } finally {
                accumulationOperation = null
                renderAccumulation()
            }
        }
        renderAccumulation()
    }

    private fun renderAccumulation() {
        if (!::accumulationToggle.isInitialized) return
        val state = accumulationState
        renderingAccumulationToggle = true
        accumulationToggle.isChecked = state.enabled
        renderingAccumulationToggle = false
        accumulationToggle.isEnabled = accumulationLoaded && (state.enabled || (
            modelReady && !manualBusy && accumulationOperation?.isActive != true
        ))
        val preparing = state.enabled && state.status == GemmaAccumulationState.STATUS_RUNNING
        accumulationProgress.isIndeterminate = preparing
        accumulationProgress.visibility = if (preparing) View.VISIBLE else View.INVISIBLE
        accumulationStatus.text = if (accumulationLoaded) state.status else "축적 상태를 확인하는 중…"
        storedSentenceSummary.text = if (accumulationLoaded) {
            "준비한 문장 ${state.stored}개"
        } else {
            "준비 상태 확인 중"
        }
        val error = state.error ?: accumulationLoadError
        accumulationDetails.text = buildString {
            append("저장 문장 ${state.stored}개 · 새로운 문맥을 계속 확장")
            append(" · 누적 추가 ${state.added}개 · 중복 ${state.duplicates}개 · 거부 ${state.rejected}개")
            if (accumulationLoaded && !modelReady && !state.enabled) {
                append("\n자동 축적을 켜려면 모델을 다운로드하거나 가져오세요.")
            }
            if (error != null) append("\n실패 사유: $error")
        }
        accumulationProgress.contentDescription = "새로운 문장 준비 중"
        val canSchedule = accumulationLoaded && state.enabled && modelReady && !manualBusy && accumulationOperation?.isActive != true
        scheduleAccumulationButton.isEnabled = canSchedule
        retryAccumulationButton.text = if (!accumulationLoaded && error != null) "상태 다시 확인" else "다시 보충 시도"
        retryAccumulationButton.isEnabled = if (!accumulationLoaded) {
            error != null && accumulationLoadJob?.isActive != true
        } else {
            canSchedule
        }
        applyManualControlState()
        renderPreparationWaitReason()
    }

    private fun renderPreparationWaitReason() {
        if (!::preparationWaitReason.isInitialized) return
        val snapshot = eligibilitySnapshot
        preparationWaitReason.text = when {
            !accumulationLoaded -> "문장 준비 대기 이유를 확인하는 중입니다."
            accumulationLoadError != null -> "문장 준비 상태 확인 실패: $accumulationLoadError"
            !accumulationState.enabled -> "자동 준비가 꺼져 있습니다."
            !modelReady -> "AI 모델 준비가 필요합니다."
            manualBusy -> "수동 작업이 진행 중입니다."
            accumulationState.status == GemmaAccumulationState.STATUS_RUNNING ||
                GemmaAccumulationRuntime.isInferring -> "문장을 준비하고 있습니다."
            snapshot == null -> "문장 준비 대기 이유를 확인하는 중입니다."
            else -> GemmaGenerationEligibility.evaluate(snapshot)?.message
                ?: if (snapshot.thermalStatus == android.os.PowerManager.THERMAL_STATUS_MODERATE) {
                    "기기가 따뜻해 한 번에 한 문맥씩 준비합니다."
                } else {
                    "Android 실행 일정을 기다리고 있습니다."
                }
        }
    }

    private suspend fun requireManualGenerationAllowed() {
        manualGenerationWaitReason()?.let { reason ->
            throw IllegalStateException(reason.message)
        }
    }

    private suspend fun manualGenerationWaitReason(): GemmaGenerationWaitReason? =
        withContext(Dispatchers.IO) {
            GemmaGenerationEligibility.evaluate(
                GemmaGenerationEligibility.snapshot(applicationContext)
            )
        }

    private fun launchExclusive(block: suspend () -> Unit) {
        if (accumulationState.enabled) {
            status.text = "자동 축적을 끈 뒤 관리할 수 있습니다."
            return
        }
        if (operation?.isActive == true || generator.isRunning) {
            status.text = "이미 작업이 진행 중입니다."
            return
        }
        operation = lifecycleScope.launch {
            setBusy(true)
            try {
                block()
            } catch (error: CancellationException) {
                status.text = "작업을 취소했습니다."
            } catch (error: Exception) {
                status.text = "오류: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        manualBusy = busy
        applyManualControlState()
        cancelButton.isEnabled = busy
        renderAccumulation()
    }

    private fun applyManualControlState() {
        if (!::downloadButton.isInitialized) return
        val enabled = accumulationLoaded && !manualBusy && !accumulationState.enabled && accumulationOperation?.isActive != true
        downloadButton.isEnabled = enabled
        importButton.isEnabled = enabled
        generateButton.isEnabled = enabled
        clearButton.isEnabled = enabled
        deleteModelButton.isEnabled = enabled
        backendGroup.isEnabled = enabled
        for (index in 0 until backendGroup.childCount) {
            backendGroup.getChildAt(index).isEnabled = enabled
        }
        if (accumulationState.enabled) {
            status.text = "자동 축적을 끈 뒤 관리할 수 있습니다."
        }
    }

    private fun memoryGuidance(): String {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val gib = memoryInfo.totalMem.toDouble() / (1024 * 1024 * 1024)
        return if (gib >= 8.0) {
            "기기 총 메모리: ${String.format(Locale.US, "%.1f", gib)} GiB. 실행 가능성의 참고값이며 실제 메모리를 보장하지 않습니다."
        } else {
            "기기 총 메모리: ${String.format(Locale.US, "%.1f", gib)} GiB. 메모리 여유가 부족하면 문장 준비를 잠시 멈춥니다."
        }
    }

    private fun formatBytes(bytes: Long): String = String.format(Locale.US, "%.2f GiB", bytes / 1024.0 / 1024.0 / 1024.0)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val INITIAL_MODEL_STATUS = "모델을 다운로드하거나 이미 받은 모델을 가져오세요."
        const val MODEL_READY_STATUS = "모델이 준비되어 있습니다. 자동 축적을 켜거나 고정 재료를 생성할 수 있습니다."
        const val CPU_ID = 1001
        const val GPU_ID = 1002
        const val PREPARATION_WAIT_REASON_REFRESH_MS = 1_000L
        const val MANUAL_GENERATION_GUARD_INTERVAL_MS = 250L
    }
}

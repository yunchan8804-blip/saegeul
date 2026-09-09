/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.debug.gemma

import android.app.ActivityManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import java.util.Locale

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
    private lateinit var accumulationDetails: TextView
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

    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (accumulationState.enabled) {
            status.text = "자동 축적을 끈 뒤 관리할 수 있습니다."
            return@registerForActivityResult
        }
        launchExclusive {
            status.text = "선택한 모델을 검증하며 가져오는 중입니다…"
            val result = GemmaModelFiles.importFrom(this, uri, ::showTransferProgress)
            status.text = if (result.reusedVerifiedModel) {
                "기존 검증 모델을 재사용합니다."
            } else {
                "모델 가져오기와 SHA-256 검증이 완료되었습니다."
            }
            refreshModelReady()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContent())
        renderAccumulation()
        observeAccumulation()
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
        val padding = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = "Gemma 재료 실험"
            textSize = 22f
        })
        content.addView(TextView(this).apply {
            text = "고정된 비개인 한국어 주제만 기기에서 생성합니다. 모델은 약 2.59 GB이며 APK에 포함되지 않습니다."
        })
        content.addView(TextView(this).apply {
            text = memoryGuidance()
        })
        accumulationToggle = Switch(this).apply {
            text = "문장 재료 자동 축적"
            contentDescription = "문장 재료 자동 축적"
            minimumHeight = dp(48)
            setOnCheckedChangeListener { _, enabled ->
                if (!renderingAccumulationToggle) setAccumulationEnabled(enabled)
            }
        }
        content.addView(accumulationToggle)
        content.addView(TextView(this).apply {
            text = "충전 중이고 키보드를 사용하지 않을 때 문장 재료를 조금씩 쌓습니다. 입력할 때는 저장한 재료를 바로 찾습니다."
        })
        accumulationProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = DEFAULT_PREFIX_COUNT
            progress = 0
            contentDescription = "문장 재료 축적 진행"
        }
        content.addView(accumulationProgress)
        accumulationStatus = TextView(this).apply {
            contentDescription = "문장 재료 축적 상태"
        }
        accumulationDetails = TextView(this).apply {
            contentDescription = "문장 재료 축적 세부 정보"
        }
        content.addView(accumulationStatus)
        content.addView(accumulationDetails)
        scheduleAccumulationButton = button("지금 보충 예약") { requestAccumulation() }.apply {
            contentDescription = "지금 문장 재료 보충 예약"
        }
        retryAccumulationButton = button("다시 보충 시도") { retryAccumulation() }.apply {
            contentDescription = "문장 재료 다시 보충 시도"
        }
        content.addView(scheduleAccumulationButton)
        content.addView(retryAccumulationButton)
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
        content.addView(backendGroup)
        downloadButton = button("모델 다운로드") { showDownloadConfirmation() }
        importButton = button("받은 모델 가져오기") { importModel.launch(arrayOf("application/octet-stream", "*/*")) }
        generateButton = button("고정 재료 생성") { generateMaterials() }
        cancelButton = button("취소") { cancelOperation() }.apply { isEnabled = false }
        clearButton = button("실험 재료 삭제") { clearMaterials() }
        deleteModelButton = button("모델 삭제") { showDeleteModelConfirmation() }
        content.addView(downloadButton)
        content.addView(importButton)
        content.addView(generateButton)
        content.addView(cancelButton)
        content.addView(clearButton)
        content.addView(deleteModelButton)
        status = TextView(this).apply {
            text = INITIAL_MODEL_STATUS
            setPadding(0, padding, 0, 0)
        }
        output = TextView(this).apply {
            setPadding(0, padding, 0, 0)
        }
        content.addView(status)
        content.addView(output)
        return ScrollView(this).apply { addView(content) }
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        minimumHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        setOnClickListener { onClick() }
    }

    private fun showDownloadConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Gemma 모델 다운로드")
            .setMessage(
                "약 2.59 GB를 다운로드합니다. 출처는 Hugging Face의 ${GemmaModelFiles.MODEL_ID}이며, " +
                    "고정 HTTPS 주소만 사용합니다. 완전 오프라인 모드에서는 다운로드할 수 없습니다. " +
                    "개인 입력이나 금고 내용은 전송하지 않습니다."
            )
            .setNegativeButton("취소", null)
            .setPositiveButton("다운로드") { _, _ ->
                launchExclusive {
                    status.text = "모델 다운로드와 SHA-256 검증을 준비하는 중입니다…"
                    val result = GemmaModelFiles.download(this, ::showTransferProgress)
                    status.text = if (result.reusedVerifiedModel) {
                        "기존 검증 모델을 재사용합니다."
                    } else {
                        "모델 다운로드와 SHA-256 검증이 완료되었습니다."
                    }
                    refreshModelReady()
                }
            }
            .show()
    }

    private fun generateMaterials() {
        launchExclusive {
            status.text = "모델 검증·Gemma 초기화·고정 재료 생성을 실행 중입니다…"
            val result = generator.generate(
                GemmaModelFiles.modelFile(this@GemmaExperimentActivity),
                backendGroup.checkedRadioButtonId == GPU_ID
            )
            currentCoroutineContext().ensureActive()
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                throw CancellationException("화면 이탈 뒤에는 생성 결과를 저장하지 않습니다.")
            }
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

    private fun loadAccumulationState() {
        if (accumulationLoadJob?.isActive == true) return
        accumulationLoadError = null
        accumulationLoadJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { accumulationStore.load() }
                accumulationLoaded = true
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
        val total = state.totalPrefixes.coerceAtLeast(1)
        renderingAccumulationToggle = true
        accumulationToggle.isChecked = state.enabled
        renderingAccumulationToggle = false
        accumulationToggle.isEnabled = accumulationLoaded && (state.enabled || (
            modelReady && !manualBusy && accumulationOperation?.isActive != true
        ))
        accumulationProgress.max = total
        accumulationProgress.progress = state.covered.coerceIn(0, total)
        accumulationStatus.text = if (accumulationLoaded) state.status else "축적 상태를 확인하는 중…"
        val error = state.error ?: accumulationLoadError
        accumulationDetails.text = buildString {
            append("저장 문장 ${state.stored}개 · 4개 이상 채운 문맥 ${state.covered}/${state.totalPrefixes}개")
            append(" · 누적 추가 ${state.added}개 · 중복 ${state.duplicates}개 · 거부 ${state.rejected}개")
            if (accumulationLoaded && !modelReady && !state.enabled) {
                append("\n자동 축적을 켜려면 모델을 다운로드하거나 가져오세요.")
            }
            if (error != null) append("\n실패 사유: $error")
        }
        accumulationProgress.contentDescription = "4개 이상 채운 문맥 ${state.covered}/${state.totalPrefixes}개"
        val canSchedule = accumulationLoaded && state.enabled && modelReady && !manualBusy && accumulationOperation?.isActive != true
        scheduleAccumulationButton.isEnabled = canSchedule
        retryAccumulationButton.text = if (!accumulationLoaded && error != null) "상태 다시 확인" else "다시 보충 시도"
        retryAccumulationButton.isEnabled = if (!accumulationLoaded) {
            error != null && accumulationLoadJob?.isActive != true
        } else {
            canSchedule
        }
        applyManualControlState()
    }

    private fun showTransferProgress(receivedBytes: Long, totalBytes: Long) {
        runOnUiThread {
            status.text = "모델 처리 중: ${formatBytes(receivedBytes)} / ${formatBytes(totalBytes)}"
        }
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
            "기기 총 메모리: ${String.format(Locale.US, "%.1f", gib)} GiB. 저메모리 기기에서는 자동 추론하지 않으며, 사용자가 생성 버튼을 눌러야 합니다."
        }
    }

    private fun formatBytes(bytes: Long): String = String.format(Locale.US, "%.2f GiB", bytes / 1024.0 / 1024.0 / 1024.0)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val INITIAL_MODEL_STATUS = "모델을 다운로드하거나 이미 받은 모델을 가져오세요."
        const val MODEL_READY_STATUS = "모델이 준비되어 있습니다. 자동 축적을 켜거나 고정 재료를 생성할 수 있습니다."
        const val CPU_ID = 1001
        const val GPU_ID = 1002
        const val DEFAULT_PREFIX_COUNT = 20
    }
}

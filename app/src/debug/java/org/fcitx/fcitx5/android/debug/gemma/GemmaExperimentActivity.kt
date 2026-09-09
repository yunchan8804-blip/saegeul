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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import java.io.File
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

    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        launchExclusive {
            status.text = "선택한 모델을 검증하며 가져오는 중입니다…"
            val result = GemmaModelFiles.importFrom(this, uri, ::showTransferProgress)
            status.text = if (result.reusedVerifiedModel) {
                "기존 검증 모델을 재사용합니다."
            } else {
                "모델 가져오기와 SHA-256 검증이 완료되었습니다."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContent())
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
            text = "모델을 다운로드하거나 이미 받은 모델을 가져오세요."
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

    private fun showTransferProgress(receivedBytes: Long, totalBytes: Long) {
        runOnUiThread {
            status.text = "모델 처리 중: ${formatBytes(receivedBytes)} / ${formatBytes(totalBytes)}"
        }
    }

    private fun launchExclusive(block: suspend () -> Unit) {
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
        downloadButton.isEnabled = !busy
        importButton.isEnabled = !busy
        generateButton.isEnabled = !busy
        clearButton.isEnabled = !busy
        deleteModelButton.isEnabled = !busy
        backendGroup.isEnabled = !busy
        for (index in 0 until backendGroup.childCount) {
            backendGroup.getChildAt(index).isEnabled = !busy
        }
        cancelButton.isEnabled = busy
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

    private companion object {
        const val CPU_ID = 1001
        const val GPU_ID = 1002
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.View
import androidx.lifecycle.lifecycleScope
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

/** Local Korean OCR with one-shot image access, explicit review, and exactly-once insertion. */
class OcrWindow(
    private val documentResume: OcrDocumentResumeResult? = null
) : InputWindow.ExtendedInputWindow<OcrWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()
    private val modelManager by lazy { OcrModelManager(context) }

    private lateinit var ui: OcrUi
    private var target: OcrEditorTarget? = null
    private var pickerRequestId: Long? = null
    private var workJob: Job? = null
    private var engine: KoreanOcrEngine? = null
    private var blocks: List<OcrTextBlock> = emptyList()
    private var selectedIds: Set<String> = emptySet()
    private var modelInstalled = false
    private var attached = false
    private var documentResumeConsumed = false
    private val commitGate = OcrCommitGate()

    override val title: String by lazy { context.getString(R.string.ocr_title) }
    override val showTitle: Boolean = false

    override fun onCreateView(): View {
        ui = OcrUi(context, theme).apply {
            onDownloadModel = ::downloadModel
            onPickImage = ::pickImage
            onCancel = { cancelSession(clearUi = true) }
            onClose = ::returnToKeyboard
            onInsert = ::insertSelection
            onSelectionChanged = { selected ->
                selectedIds = selected
                OcrTextContract.format(blocks, selected) != null
            }
        }
        return ui.root
    }

    override fun onAttached() {
        attached = true
        if (!service.allowsTextInspectionFeatures()) {
            showPrivateBlocked()
            return
        }
        if (!documentResumeConsumed && documentResume != null) {
            documentResumeConsumed = true
            resumeAfterDocumentPicker(documentResume)
        } else {
            checkModel()
        }
    }

    override fun onDetached() {
        attached = false
        // A document picker temporarily detaches the IME. Its result belongs to the coordinator,
        // not this stale window instance, and must survive until InputView restores the editor.
        pickerRequestId = null
        cancelWork()
        clearReviewState()
    }

    private fun checkModel() {
        cancelWork()
        ui.showCheckingModel()
        workJob = service.lifecycleScope.launch {
            try {
                modelInstalled = modelManager.hasValidModel()
                if (!attached) return@launch
                if (!service.allowsTextInspectionFeatures()) {
                    showPrivateBlocked()
                } else if (modelInstalled) {
                    ui.showReady()
                } else {
                    ui.showModelMissing(canDownload = service.allowsNetworkInputFeatures())
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                modelInstalled = false
                if (attached) ui.showModelMissing(
                    canDownload = service.allowsNetworkInputFeatures(),
                    failed = true
                )
            }
        }
    }

    private fun downloadModel() {
        if (!service.allowsTextInspectionFeatures()) {
            showPrivateBlocked()
            return
        }
        if (!service.allowsNetworkInputFeatures()) {
            ui.showModelMissing(canDownload = false)
            return
        }
        cancelWork()
        ui.showDownloadingModel()
        workJob = service.lifecycleScope.launch {
            try {
                modelManager.install()
                modelInstalled = modelManager.hasValidModel()
                if (!modelInstalled) throw OcrModelException("Installed model failed verification")
                if (!attached) return@launch
                if (service.allowsTextInspectionFeatures()) ui.showReady() else showPrivateBlocked()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                modelInstalled = false
                if (attached) ui.showModelMissing(
                    canDownload = service.allowsNetworkInputFeatures(),
                    failed = true
                )
            } finally {
                modelManager.cancel()
            }
        }
    }

    private fun pickImage() {
        if (!service.allowsTextInspectionFeatures()) {
            showPrivateBlocked()
            return
        }
        if (!modelInstalled) {
            checkModel()
            return
        }
        cancelSession(clearUi = false)
        val boundTarget = captureTarget()
        if (boundTarget == null) {
            ui.showRecognitionError(R.string.ocr_cursor_required, canRetry = false)
            return
        }
        target = boundTarget
        ui.showWaitingForImage()
        pickerRequestId = OcrDocumentCoordinator.request(context, boundTarget)
        if (pickerRequestId == null) {
            clearReviewState()
            ui.showRecognitionError(R.string.ocr_failed, canRetry = true)
        }
    }

    private fun resumeAfterDocumentPicker(resume: OcrDocumentResumeResult) {
        val uri = resume.documentUri?.let(Uri::parse)
        if (uri == null) {
            checkModel()
            return
        }
        target = resume.target
        ui.showRecognizing()
        workJob = service.lifecycleScope.launch {
            try {
                modelInstalled = modelManager.hasValidModel()
                if (!attached) return@launch
                if (!modelInstalled) {
                    clearReviewState()
                    ui.showModelMissing(
                        canDownload = service.allowsNetworkInputFeatures(),
                        failed = true
                    )
                    return@launch
                }
                workJob = null
                recognize(uri, resume.target)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (attached) {
                    clearReviewState(keepTarget = true)
                    ui.showRecognitionError(R.string.ocr_failed, canRetry = true)
                }
            }
        }
    }

    private fun recognize(uri: Uri, boundTarget: OcrEditorTarget) {
        if (!validateTarget(boundTarget, showError = true)) return
        workJob = service.lifecycleScope.launch {
            var bitmap: Bitmap? = null
            var activeEngine: KoreanOcrEngine? = null
            try {
                val source = ContentUriOcrImageSource.inspect(context, uri)
                if (!validateTarget(boundTarget, showError = true)) return@launch
                ui.showRecognizing()
                val decoded = source.decode()
                bitmap = decoded
                if (!validateTarget(boundTarget, showError = true)) return@launch
                val recognizer = TesseractKoreanOcrEngine(modelManager.dataPath)
                activeEngine = recognizer
                engine = recognizer
                val raw = recognizer.recognize(decoded)
                if (!validateTarget(boundTarget, showError = true)) return@launch
                val parsed = OcrTextContract.parse(raw)
                if (parsed == null) {
                    clearReviewState(keepTarget = true)
                    ui.showRecognitionError(R.string.ocr_no_text, canRetry = true)
                    return@launch
                }
                blocks = parsed
                selectedIds = emptySet()
                commitGate.resetForReview()
                ui.showPreview(parsed)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (attached) {
                    clearReviewState(keepTarget = true)
                    ui.showRecognitionError(
                        if (exception is OcrImageException) {
                            R.string.ocr_unsupported_image
                        } else {
                            R.string.ocr_failed
                        },
                        canRetry = true
                    )
                }
            } finally {
                if (engine === activeEngine) engine = null
                bitmap?.run {
                    if (!isRecycled && isMutable) {
                        eraseColor(Color.TRANSPARENT)
                    }
                    if (!isRecycled) recycle()
                }
            }
        }
    }

    private fun insertSelection() {
        val boundTarget = target ?: return
        val reviewed = OcrTextContract.format(blocks, selectedIds) ?: return
        if (!commitGate.claim()) return
        if (!validateTarget(boundTarget, showError = true)) return
        if (!service.commitToEditor(reviewed)) {
            ui.showRecognitionError(R.string.ocr_commit_failed, canRetry = false)
            return
        }
        clearReviewState()
        returnToKeyboard()
    }

    private fun captureTarget(): OcrEditorTarget? {
        if (!service.prepareOcrCommit()) return null
        val info = service.currentInputEditorInfo
        val selection = service.currentInputSelection
        return OcrTextContract.bindEditor(
            packageName = info.packageName,
            fieldId = info.fieldId,
            inputType = info.inputType,
            selectionStart = selection.start,
            selectionEnd = selection.end
        )
    }

    private fun validateTarget(boundTarget: OcrEditorTarget, showError: Boolean): Boolean {
        val valid = service.allowsTextInspectionFeatures() && service.matchesCurrentEditor(
            boundTarget.packageName,
            boundTarget.fieldId,
            boundTarget.inputType,
            boundTarget.cursor,
            boundTarget.cursor
        )
        if (!valid && showError && attached) {
            if (service.allowsTextInspectionFeatures()) {
                ui.showRecognitionError(R.string.ocr_editor_changed, canRetry = false)
            } else {
                showPrivateBlocked()
            }
        }
        return valid
    }

    private fun cancelSession(clearUi: Boolean) {
        pickerRequestId?.let(OcrDocumentCoordinator::cancel)
        pickerRequestId = null
        cancelWork()
        clearReviewState()
        if (clearUi && attached) {
            if (!service.allowsTextInspectionFeatures()) {
                showPrivateBlocked()
            } else if (modelInstalled) {
                ui.showReady()
            } else {
                ui.showModelMissing(canDownload = service.allowsNetworkInputFeatures())
            }
        }
    }

    private fun cancelWork() {
        modelManager.cancel()
        engine?.cancel()
        engine = null
        workJob?.cancel()
        workJob = null
    }

    private fun clearReviewState(keepTarget: Boolean = false) {
        blocks = emptyList()
        selectedIds = emptySet()
        if (!keepTarget) target = null
        commitGate.resetForReview()
    }

    private fun showPrivateBlocked() {
        clearReviewState()
        ui.showRecognitionError(R.string.ocr_private_disabled, canRetry = false)
    }

    private fun returnToKeyboard() {
        windowManager.attachWindow(KeyboardWindow)
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.view.View
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.AiProviderProfile
import org.fcitx.fcitx5.android.input.ai.AiProviderResolver
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

/** User-selected file diarization with explicit segment review and an exactly-once insert. */
class MeetingTranscriptionWindow : InputWindow.ExtendedInputWindow<MeetingTranscriptionWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private lateinit var ui: MeetingTranscriptionUi
    private var profile: AiProviderProfile? = null
    private var target: VoiceEditorTarget? = null
    private var pickerRequestId: Long? = null
    private var requestJob: Job? = null
    private var client: OpenAiDiarizationClient? = null
    private var segments: List<MeetingSpeakerSegment> = emptyList()
    private var selectedIds: Set<String> = emptySet()
    private var attached = false
    private val commitGate = MeetingCommitGate()

    override val title: String by lazy { local("회의·메모 화자 분리", "Meeting transcription") }
    override val showTitle: Boolean = false

    override fun onCreateView(): View {
        ui = MeetingTranscriptionUi(context, theme).apply {
            onPickFile = ::pickAudioFile
            onCancel = { cancelSession(clearUi = true) }
            onClose = ::returnToKeyboard
            onInsert = ::insertSelection
            onSelectionChanged = { selected ->
                selectedIds = selected
                MeetingTranscriptSelection.format(segments, selected, speakerPrefix()) != null
            }
        }
        return ui.root
    }

    override fun onAttached() {
        attached = true
        val resolved = AiProviderResolver.resolve(context).profile
        profile = resolved
        when {
            !service.allowsTextInspectionFeatures() -> showBlocked(
                local(
                    "민감하거나 비공개인 입력란에서는 음성 파일을 읽지 않아.",
                    "Audio files are disabled in sensitive or private editors."
                )
            )
            !service.allowsAiInputFeatures() -> showBlocked(
                local(
                    "오프라인 모드 또는 이 앱의 AI 차단 설정 때문에 사용할 수 없어.",
                    "Offline mode or this app's AI policy blocks transcription."
                )
            )
            resolved == null -> showBlocked(
                local("먼저 AI 제공자와 API 키를 설정해.", "Configure an AI provider and API key first.")
            )
            !MeetingDiarizationCapability.supports(resolved) -> showBlocked(
                local(
                    "화자 분리는 현재 OpenAI 표준 API 프로필에서만 지원해. 호환 제공자는 capability 확인 전까지 차단돼.",
                    "Speaker diarization currently requires a standard OpenAI API profile. Compatible providers stay blocked until capability verification."
                )
            )
            else -> ui.showReady(resolved.displayName)
        }
    }

    override fun onDetached() {
        attached = false
        cancelSession(clearUi = false)
        profile = null
    }

    private fun pickAudioFile() {
        val configured = profile
        if (!validatePolicy(configured) || configured == null) return
        cancelSession(clearUi = false)
        val boundTarget = captureTarget()
        if (boundTarget == null) {
            showBlocked(local("커서가 있는 일반 입력란에서 다시 열어줘.", "Open this again in a text field with a cursor."))
            return
        }
        target = boundTarget
        ui.showLoading()
        pickerRequestId = VoiceAudioDocumentCoordinator.request(context) { uri ->
            pickerRequestId = null
            if (!attached) return@request
            if (uri == null) {
                clearReviewState()
                ui.showReady(configured.displayName)
                return@request
            }
            processSelectedAudio(uri, configured, boundTarget)
        }
        if (pickerRequestId == null) {
            clearReviewState()
            ui.showError(
                local("음성 파일 선택 창을 열지 못했어.", "Could not open the audio picker."),
                canRetry = true
            )
        }
    }

    private fun processSelectedAudio(
        uri: android.net.Uri,
        configured: AiProviderProfile,
        boundTarget: VoiceEditorTarget
    ) {
        if (!validateTarget(boundTarget, showError = true)) return
        requestJob = service.lifecycleScope.launch {
            var activeClient: OpenAiDiarizationClient? = null
            try {
                ui.showLoading()
                val source = ContentUriMeetingAudioSource.inspect(context, uri)
                if (!validateTarget(boundTarget, showError = true)) return@launch
                ui.showLoading(source.metadata.durationMillis)
                val transcriber = OpenAiDiarizationClient(configured)
                activeClient = transcriber
                client = transcriber
                val result = transcriber.transcribe(source)
                if (!validateTarget(boundTarget, showError = true)) return@launch
                segments = result.segments
                selectedIds = emptySet()
                commitGate.resetForSelection()
                ui.showPreview(result.segments)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (attached) {
                    val message = if (exception is MeetingAudioException) {
                        local(
                            "지원되는 60분·24MB 이하 음성 파일을 골라줘.",
                            "Choose a supported audio file up to 60 minutes and 24 MB."
                        )
                    } else {
                        local(
                            "화자 분리 전사에 실패했어. 파일과 연결을 확인하고 다시 시도해.",
                            "Speaker transcription failed. Check the file and connection, then retry."
                        )
                    }
                    clearReviewState(keepTarget = true)
                    ui.showError(message, canRetry = true)
                }
            } finally {
                if (client === activeClient) client = null
            }
        }
    }

    private fun insertSelection() {
        val boundTarget = target ?: return
        val reviewed = MeetingTranscriptSelection.format(
            segments,
            selectedIds,
            ui.speakerPrefix()
        ) ?: return
        if (!commitGate.claim()) return
        if (!validateTarget(boundTarget, showError = true)) return
        if (!service.commitText(reviewed)) {
            ui.showError(
                local("선택한 전사를 입력하지 못했어.", "Could not insert the selected transcript."),
                canRetry = false
            )
            return
        }
        clearReviewState()
        returnToKeyboard()
    }

    private fun captureTarget(): VoiceEditorTarget? {
        if (!service.prepareRichContentCommit()) return null
        val info = service.currentInputEditorInfo
        val selection = service.currentInputSelection
        return VoiceTranscriptPolicy.bindEditor(
            packageName = info.packageName,
            fieldId = info.fieldId,
            inputType = info.inputType,
            selectionStart = selection.start,
            selectionEnd = selection.end
        )
    }

    private fun validatePolicy(configured: AiProviderProfile?): Boolean {
        val message = when {
            !service.allowsTextInspectionFeatures() -> local(
                "민감하거나 비공개인 입력란에서는 사용할 수 없어.",
                "This is disabled in sensitive or private editors."
            )
            !service.allowsAiInputFeatures() -> local(
                "오프라인 모드 또는 앱 정책이 AI 전사를 차단하고 있어.",
                "Offline mode or app policy blocks AI transcription."
            )
            configured == null -> local(
                "먼저 AI 제공자와 API 키를 설정해.",
                "Configure an AI provider and API key first."
            )
            !MeetingDiarizationCapability.supports(configured) -> local(
                "이 제공자는 화자 분리 capability가 확인되지 않았어.",
                "This provider has no verified diarization capability."
            )
            else -> return true
        }
        showBlocked(message)
        return false
    }

    private fun validateTarget(boundTarget: VoiceEditorTarget, showError: Boolean): Boolean {
        val valid = validatePolicy(profile) && service.matchesCurrentEditor(
            boundTarget.packageName,
            boundTarget.fieldId,
            boundTarget.inputType,
            boundTarget.cursor,
            boundTarget.cursor
        )
        if (!valid && showError && attached && service.allowsAiInputFeatures()) {
            showBlocked(
                local(
                    "입력 앱이나 커서가 바뀌었어. 새 입력란에서 다시 시작해.",
                    "The editor or cursor changed. Start again in the new text field."
                )
            )
        }
        return valid
    }

    private fun cancelSession(clearUi: Boolean) {
        pickerRequestId?.let(VoiceAudioDocumentCoordinator::cancel)
        pickerRequestId = null
        client?.cancel()
        client = null
        requestJob?.cancel()
        requestJob = null
        clearReviewState()
        if (clearUi && attached) {
            val configured = profile
            if (validatePolicy(configured) && configured != null) ui.showReady(configured.displayName)
        }
    }

    private fun clearReviewState(keepTarget: Boolean = false) {
        segments = emptyList()
        selectedIds = emptySet()
        if (!keepTarget) target = null
        commitGate.resetForSelection()
    }

    private fun showBlocked(message: String) {
        clearReviewState()
        ui.showError(message, canRetry = false)
    }

    private fun returnToKeyboard() {
        windowManager.attachWindow(KeyboardWindow)
    }

    private fun local(korean: String, english: String): String =
        if (ConfigurationCompat.getLocales(context.resources.configuration)[0]?.language == "ko") {
            korean
        } else {
            english
        }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.net.Uri
import android.util.Log
import android.view.View
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.AiFeatureEntryGate
import org.fcitx.fcitx5.android.input.ai.AiSettingsNavigator
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must

/** User-selected file diarization with explicit segment review and an exactly-once insert. */
class MeetingTranscriptionWindow(
    private val documentResume: VoiceAudioDocumentResumeResult? = null
) : InputWindow.ExtendedInputWindow<MeetingTranscriptionWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private lateinit var ui: MeetingTranscriptionUi
    private var profile: VoiceProviderProfile? = null
    private var target: VoiceEditorTarget? = null
    private var pickerRequestId: Long? = null
    private var requestJob: Job? = null
    private var client: OpenAiDiarizationClient? = null
    private var segments: List<MeetingSpeakerSegment> = emptyList()
    private var selectedIds: Set<String> = emptySet()
    private var attached = false
    private var documentResumeConsumed = false
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
            onSetupRequested = { AiSettingsNavigator.openVoiceSetup(context) }
        }
        return ui.root
    }

    override fun onAttached() {
        attached = true
        val allowsTextInspection = service.allowsTextInspectionFeatures()
        val allowsOnlineVoice = service.allowsNetworkInputFeatures()
        val resolved = MeetingVoiceProfileResolver.resolve(
            context,
            allowsCredentialAccess = allowsTextInspection && allowsOnlineVoice
        )
        profile = resolved
        when (MeetingWindowEntryPolicy.evaluate(
            allowsTextInspection = allowsTextInspection,
            allowsNetworkInput = allowsOnlineVoice,
            profile = resolved
        )) {
            AiFeatureEntryGate.PrivateEditor -> showBlocked(
                local(
                    "민감하거나 비공개인 입력란에서는 음성 파일을 읽지 않아.",
                    "Audio files are disabled in sensitive or private editors."
                )
            )
            AiFeatureEntryGate.NetworkPolicyBlocked -> showBlocked(
                local(
                    "오프라인 모드 또는 이 앱의 네트워크 차단 설정 때문에 사용할 수 없어.",
                    "Offline mode or this app's network policy blocks transcription."
                )
            )
            AiFeatureEntryGate.SetupRequired -> showSetupRequired()
            AiFeatureEntryGate.Ready -> {
                if (resolved != null && !MeetingDiarizationCapability.supports(resolved)) {
                    showBlocked(
                        local(
                            "현재 음성 전사 연결에서는 화자 분리를 사용할 수 없어. 음성 설정에서 연결을 확인해줘.",
                            "Speaker transcription isn’t available with the current voice connection. Check the model in voice settings."
                        )
                    )
                } else if (resolved != null) {
                    if (!documentResumeConsumed && documentResume != null) {
                        documentResumeConsumed = true
                        resumeAfterDocumentPicker(documentResume, resolved)
                    } else {
                        ui.showReady(voiceProviderName())
                    }
                }
            }
        }
    }

    override fun onDetached() {
        attached = false
        // The picker detaches the IME. Its result is owned by the coordinator and must survive
        // until InputView restores a fresh meeting window for the original editor.
        pickerRequestId = null
        cancelWork()
        clearReviewState()
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
        pickerRequestId = VoiceAudioDocumentCoordinator.request(context, boundTarget)
        if (pickerRequestId == null) {
            clearReviewState()
            ui.showError(
                local("음성 파일 선택 창을 열지 못했어.", "Could not open the audio picker."),
                canRetry = true
            )
        }
    }

    private fun resumeAfterDocumentPicker(
        resume: VoiceAudioDocumentResumeResult,
        configured: VoiceProviderProfile
    ) {
        val uri = resume.documentUri?.let(Uri::parse)
        if (uri == null) {
            clearReviewState()
            ui.showReady(voiceProviderName())
            return
        }
        target = resume.target
        ui.showLoading()
        processSelectedAudio(uri, configured, resume.target)
    }

    private fun processSelectedAudio(
        uri: android.net.Uri,
        configured: VoiceProviderProfile,
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
                Log.w(
                    TAG,
                    "Meeting transcription failed: ${exception.javaClass.name}; " +
                        "cause=${exception.cause?.javaClass?.name ?: "none"}"
                )
                if (attached) {
                    if (exception is VoiceAuthenticationException) {
                        clearReviewState()
                        ui.showSetupRequired(context.getString(R.string.voice_provider_auth_failed))
                    } else {
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

    private fun validatePolicy(configured: VoiceProviderProfile?): Boolean {
        when (MeetingWindowEntryPolicy.evaluate(
            allowsTextInspection = service.allowsTextInspectionFeatures(),
            allowsNetworkInput = service.allowsNetworkInputFeatures(),
            profile = configured
        )) {
            AiFeatureEntryGate.PrivateEditor -> showBlocked(local(
                "민감하거나 비공개인 입력란에서는 사용할 수 없어.",
                "This is disabled in sensitive or private editors."
            ))
            AiFeatureEntryGate.NetworkPolicyBlocked -> showBlocked(local(
                "오프라인 모드 또는 앱 정책이 온라인 전사를 차단하고 있어.",
                "Offline mode or app policy blocks online transcription."
            ))
            AiFeatureEntryGate.SetupRequired -> showSetupRequired()
            AiFeatureEntryGate.Ready -> {
                if (configured != null && MeetingDiarizationCapability.supports(configured)) {
                    return true
                }
                showBlocked(local(
                    "현재 음성 전사 연결에서는 화자 분리를 사용할 수 없어. 음성 설정에서 연결을 확인해줘.",
                    "Speaker transcription isn’t available with the current voice connection. Check voice settings."
                ))
            }
        }
        return false
    }

    private fun validateTarget(boundTarget: VoiceEditorTarget, showError: Boolean): Boolean {
        if (!validatePolicy(profile)) return false
        val valid = service.matchesCurrentEditor(
            boundTarget.packageName,
            boundTarget.fieldId,
            boundTarget.inputType,
            boundTarget.cursor,
            boundTarget.cursor
        )
        if (!valid && showError && attached) {
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
        cancelWork()
        clearReviewState()
        if (clearUi && attached) {
            val configured = profile
            if (validatePolicy(configured) && configured != null) ui.showReady(voiceProviderName())
        }
    }

    private fun cancelWork() {
        client?.cancel()
        client = null
        requestJob?.cancel()
        requestJob = null
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

    private fun showSetupRequired() {
        clearReviewState()
        ui.showSetupRequired(context.getString(R.string.voice_provider_setup_required))
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

    private fun voiceProviderName(): String = context.getString(R.string.voice_openai_provider_name)

    private companion object {
        const val TAG = "MeetingTranscription"
    }
}

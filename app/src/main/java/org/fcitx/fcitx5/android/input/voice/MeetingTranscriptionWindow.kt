/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.net.Uri
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.InputFeatureBlock
import org.fcitx.fcitx5.android.input.ai.AiFeatureEntryGate
import org.fcitx.fcitx5.android.input.ai.AiSettingsNavigator
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.panel.PanelRecovery
import org.fcitx.fcitx5.android.input.panel.PanelRecoveries
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import timber.log.Timber

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
    internal var runtimeFactory: MeetingTranscriptionRuntimeFactory =
        ProductionMeetingTranscriptionRuntimeFactory
    private var runtime: MeetingTranscriptionRuntime? = null
    private var segments: List<MeetingSpeakerSegment> = emptyList()
    private var selectedIds: Set<String> = emptySet()
    private var attached = false
    private var documentResumeConsumed = false
    private val commitGate = MeetingCommitGate()

    override val title: String by lazy { context.getString(R.string.meeting_title) }
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
            onSetupRequested = {
                service.prepareForSettingsActivity()
                AiSettingsNavigator.openVoiceSetup(context)
            }
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
                context.getString(R.string.meeting_blocked_private_editor)
            )
            AiFeatureEntryGate.NetworkPolicyBlocked -> showNetworkBlocked()
            AiFeatureEntryGate.SetupRequired -> showSetupRequired()
            AiFeatureEntryGate.Ready -> {
                if (resolved != null && !MeetingDiarizationCapability.supports(resolved)) {
                    showBlocked(
                        context.getString(R.string.meeting_diarization_unsupported),
                        PanelRecoveries.voiceSetup(service)
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
            showBlocked(context.getString(R.string.meeting_editor_required))
            return
        }
        target = boundTarget
        ui.showLoading()
        pickerRequestId = VoiceAudioDocumentCoordinator.request(context, boundTarget)
        if (pickerRequestId == null) {
            clearReviewState()
            ui.showError(context.getString(R.string.meeting_picker_failed), canRetry = true)
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
            var activeRuntime: MeetingTranscriptionRuntime? = null
            try {
                ui.showLoading()
                val source = runtimeFactory.inspect(context, uri)
                val transcriber = runtimeFactory.create(configured)
                activeRuntime = transcriber
                runtime = transcriber
                val result = transcriber.run(
                    source = source,
                    canContinue = { validateTarget(boundTarget, showError = true) },
                    onSourceReady = { ui.showLoading(it.durationMillis) }
                ) ?: return@launch
                segments = result.segments
                selectedIds = emptySet()
                commitGate.resetForSelection()
                ui.showPreview(result.segments)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.w(
                    "Meeting transcription failed: %s; cause=%s",
                    exception.javaClass.name,
                    exception.cause?.javaClass?.name ?: "none"
                )
                if (attached) {
                    if (exception is VoiceAuthenticationException) {
                        clearReviewState()
                        ui.showSetupRequired(context.getString(R.string.voice_provider_auth_failed))
                    } else {
                        val message = context.getString(
                            if (exception is MeetingAudioException) {
                                R.string.meeting_unsupported_audio
                            } else {
                                R.string.meeting_transcribe_failed
                            }
                        )
                        clearReviewState(keepTarget = true)
                        ui.showError(message, canRetry = true)
                    }
                }
            } finally {
                if (runtime === activeRuntime) runtime = null
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
        if (!service.commitToEditor(reviewed)) {
            ui.showError(context.getString(R.string.meeting_insert_failed), canRetry = false)
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
            AiFeatureEntryGate.PrivateEditor -> showBlocked(
                context.getString(R.string.meeting_blocked_private_editor)
            )
            AiFeatureEntryGate.NetworkPolicyBlocked -> showNetworkBlocked()
            AiFeatureEntryGate.SetupRequired -> showSetupRequired()
            AiFeatureEntryGate.Ready -> {
                if (configured != null && MeetingDiarizationCapability.supports(configured)) {
                    return true
                }
                showBlocked(
                    context.getString(R.string.meeting_diarization_unsupported),
                    PanelRecoveries.voiceSetup(service)
                )
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
            showBlocked(context.getString(R.string.meeting_editor_changed))
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
        runtime?.cancel()
        runtime = null
        requestJob?.cancel()
        requestJob = null
    }

    private fun clearReviewState(keepTarget: Boolean = false) {
        segments = emptyList()
        selectedIds = emptySet()
        if (!keepTarget) target = null
        commitGate.resetForSelection()
    }

    private fun showBlocked(message: String, recovery: PanelRecovery? = null) {
        clearReviewState()
        ui.showError(message, canRetry = false, recovery = recovery)
    }

    /** Names the closed gate and offers the setting that reopens it. */
    private fun showNetworkBlocked() {
        val block = service.networkInputBlock() ?: InputFeatureBlock.AppPolicy
        showBlocked(
            context.getString(
                PanelRecoveries.messageFor(block, R.string.meeting_blocked_private_editor)
            ),
            PanelRecoveries.forBlock(service, block)
        )
    }

    private fun showSetupRequired() {
        clearReviewState()
        ui.showSetupRequired(context.getString(R.string.voice_provider_setup_required))
    }

    private fun returnToKeyboard() {
        windowManager.attachWindow(KeyboardWindow)
    }

    private fun voiceProviderName(): String = context.getString(R.string.voice_openai_provider_name)

}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.view.inputmethod.InputMethodSubtype
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.AiFeatureEntryGate
import org.fcitx.fcitx5.android.input.ai.AiSettingsNavigator
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.mechdancer.dependency.manager.must

/** Preview-first, bounded segment dictation. It never claims to be a Realtime session. */
class VoiceTranscriptionWindow(
    private val permissionResume: VoicePermissionResumeResult? = null
) : InputWindow.ExtendedInputWindow<VoiceTranscriptionWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private lateinit var ui: VoiceTranscriptionUi
    private var mode: VoiceProviderMode = VoiceProviderMode.DeviceDictation
    private var profile: VoiceProviderProfile? = null
    private var target: VoiceEditorTarget? = null
    private var recorder: PcmMemoryRecorder? = null
    private var segmentClient: OpenAiTranscriptionClient? = null
    private var realtimeRecorder: PcmStreamRecorder? = null
    private var realtimeSession: OpenAiRealtimeTranscriptionSession? = null
    private var realtimePartial = ""
    private var realtimeElapsedSeconds = 0
    private var realtimeStopping = false
    private var realtimeFailure = RealtimeTerminalFailure()
    private var sessionJob: Job? = null
    private var transcript: String? = null
    private var systemVoiceInput: Pair<String, InputMethodSubtype>? = null
    private var attached = false
    private val commitGate = VoiceCommitGate()

    override val title: String by lazy { context.getString(R.string.voice_provider_settings) }
    override val showTitle: Boolean = false

    override fun onCreateView(): View {
        ui = VoiceTranscriptionUi(context, theme).apply {
            onStart = ::beginRecording
            onStop = ::stopRecording
            onCancel = ::cancelSession
            onInsert = ::insertTranscript
            onPermission = ::requestMicrophonePermission
            onDeviceDictation = ::switchToDeviceDictation
            onMeeting = { windowManager.attachWindow(MeetingTranscriptionWindow()) }
            onClose = ::returnToKeyboard
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
        val selectedMode = VoiceProviderModeStore(context).load()
        val allowsNetworkInput = service.allowsNetworkInputFeatures()
        val allowsSelectedVoice = VoiceProviderPolicy.allowsSelectedMode(
            selectedMode,
            allowsNetworkInput
        )
        val resolved = VoiceProviderResolver.resolve(
            context,
            allowsCredentialAccess = VoiceProviderPolicy.allowsCredentialAccess(
                mode = selectedMode,
                allowsTextInspection = allowsTextInspection,
                allowsNetworkInput = allowsNetworkInput
            )
        )
        mode = resolved.mode
        profile = resolved.profile
        when (AiFeatureEntryGate.evaluate(
            allowsTextInspection = allowsTextInspection,
            allowsAiInput = allowsSelectedVoice,
            hasConfiguredProfile = mode == VoiceProviderMode.DeviceDictation || profile != null
        )) {
            AiFeatureEntryGate.PrivateEditor -> {
                ui.showError(context.getString(R.string.voice_private_disabled), canRetry = false)
                return
            }
            AiFeatureEntryGate.NetworkPolicyBlocked -> {
                ui.showError(context.getString(R.string.voice_policy_disabled), canRetry = false)
                return
            }
            AiFeatureEntryGate.SetupRequired -> {
                ui.showSetupRequired(context.getString(R.string.voice_provider_setup_required))
                return
            }
            AiFeatureEntryGate.Ready -> Unit
        }
        when (mode) {
            VoiceProviderMode.DeviceDictation -> showDeviceDictation()
            VoiceProviderMode.OpenAiRealtime -> profile?.let {
                ui.showReady(
                    voiceProviderLabel(it),
                    realtime = true,
                    showMeeting = shouldShowMeetingEntry()
                )
                resumeAfterPermissionIfNeeded()
            }
            VoiceProviderMode.OpenAiApi -> profile?.let {
                ui.showReady(
                    voiceProviderLabel(it),
                    realtime = false,
                    showMeeting = shouldShowMeetingEntry()
                )
                resumeAfterPermissionIfNeeded()
            }
        }
    }

    override fun onDetached() {
        attached = false
        cancelWork(clearUi = false)
        transcript = null
        target = null
        systemVoiceInput = null
        profile = null
    }

    private fun beginRecording() {
        if (!validatePolicy()) return
        if (!hasMicrophonePermission()) {
            ui.showPermissionRequired()
            requestMicrophonePermission()
            return
        }
        startRecordingWithPermission()
    }

    private fun requestMicrophonePermission() {
        if (!validatePolicy()) return
        if (hasMicrophonePermission()) {
            startRecordingWithPermission()
            return
        }
        val boundTarget = captureTarget()
        if (boundTarget == null) {
            ui.showError(context.getString(R.string.voice_cursor_required), canRetry = false)
            return
        }
        ui.showPermissionRequired()
        val requestId = VoicePermissionCoordinator.request(context, boundTarget)
        if (requestId == null) ui.showPermissionRequired(denied = true)
    }

    private fun resumeAfterPermissionIfNeeded() {
        val resume = permissionResume ?: return
        if (!resume.granted) {
            ui.showPermissionRequired(denied = true)
            return
        }
        if (!hasMicrophonePermission()) {
            ui.showPermissionRequired(denied = true)
            return
        }
        if (!validateTarget(resume.target, showError = true)) return
        startRecordingWithPermission(resume.target)
    }

    private fun showDeviceDictation() {
        systemVoiceInput = findDeviceVoiceInput()
        val action = VoiceFallbackPolicy.action(systemVoiceInput != null)
        ui.showDeviceDictation(
            context.getString(R.string.voice_device_provider_name),
            context.getString(
                if (action == VoiceUnavailableAction.DeviceDictation) {
                    R.string.voice_device_dictation_ready
                } else {
                    R.string.voice_device_dictation_unavailable
                }
            ),
            action,
            showMeeting = shouldShowMeetingEntry()
        )
    }

    private fun switchToDeviceDictation() {
        if (!validatePolicy()) return
        val voiceInput = systemVoiceInput ?: findDeviceVoiceInput()
        if (voiceInput == null) {
            showDeviceDictation()
            return
        }
        val (id, subtype) = voiceInput
        InputMethodUtil.switchInputMethod(service, id, subtype)
    }

    private fun findDeviceVoiceInput(): Pair<String, InputMethodSubtype>? =
        InputMethodUtil.findVoiceSubtype(
            AppPrefs.getInstance().keyboard.preferredVoiceInput.getValue()
        )

    private fun startRecordingWithPermission(resumedTarget: VoiceEditorTarget? = null) {
        when (mode) {
            VoiceProviderMode.DeviceDictation -> return
            VoiceProviderMode.OpenAiRealtime -> startRealtimeRecordingWithPermission(resumedTarget)
            VoiceProviderMode.OpenAiApi -> startSegmentRecordingWithPermission(resumedTarget)
        }
    }

    private fun startSegmentRecordingWithPermission(resumedTarget: VoiceEditorTarget? = null) {
        if (!validatePolicy() || !hasMicrophonePermission()) return
        cancelWork(clearUi = false)
        val boundTarget = resumedTarget ?: captureTarget()
        if (boundTarget == null) {
            ui.showError(context.getString(R.string.voice_cursor_required), canRetry = false)
            return
        }
        if (resumedTarget != null && !validateTarget(boundTarget, showError = true)) return
        val configuredProfile = profile ?: return
        target = boundTarget
        transcript = null
        commitGate.resetForNewTranscript()
        val activeRecorder = PcmMemoryRecorder()
        val activeClient = OpenAiTranscriptionClient(configuredProfile)
        recorder = activeRecorder
        segmentClient = activeClient
        ui.showRecording(0)
        sessionJob = service.lifecycleScope.launch {
            var recording: WavMemoryRecording? = null
            try {
                val captured = activeRecorder.record { elapsedMillis ->
                    ui.root.post {
                        if (attached && recorder === activeRecorder) {
                            ui.showRecording((elapsedMillis / 1_000L).toInt())
                        }
                    }
                }
                recording = captured
                recorder = null
                if (!validateTarget(boundTarget, showError = true)) return@launch
                ui.showTranscribing()
                val result = activeClient.transcribe(captured.bytes)
                if (!validateTarget(boundTarget, showError = true)) return@launch
                transcript = result.text
                commitGate.resetForNewTranscript()
                ui.showPreview(result.text)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (attached) {
                    showSessionError(exception)
                }
            } finally {
                if (recorder === activeRecorder) recorder = null
                if (segmentClient === activeClient) segmentClient = null
                recording?.close()
            }
        }
    }

    private fun startRealtimeRecordingWithPermission(resumedTarget: VoiceEditorTarget? = null) {
        if (!validatePolicy() || !hasMicrophonePermission()) return
        cancelWork(clearUi = false)
        val boundTarget = resumedTarget ?: captureTarget()
        if (boundTarget == null) {
            ui.showError(context.getString(R.string.voice_cursor_required), canRetry = false)
            return
        }
        if (resumedTarget != null && !validateTarget(boundTarget, showError = true)) return
        val configuredProfile = profile ?: return
        target = boundTarget
        transcript = null
        realtimePartial = ""
        realtimeElapsedSeconds = 0
        realtimeStopping = false
        realtimeFailure = RealtimeTerminalFailure()
        commitGate.resetForNewTranscript()
        lateinit var activeSession: OpenAiRealtimeTranscriptionSession
        activeSession = OpenAiRealtimeTranscriptionSession(
            profile = configuredProfile,
            onPartial = { partial ->
                ui.root.post {
                    if (attached && realtimeSession === activeSession) {
                        realtimePartial = partial
                        if (realtimeStopping) {
                            ui.showRealtimeFinalizing(partial)
                        } else {
                            ui.showRealtimeRecording(realtimeElapsedSeconds, partial)
                        }
                    }
                }
            },
            onTerminalError = { error ->
                ui.root.post {
                    if (attached && realtimeSession === activeSession) {
                        realtimeFailure.report(error) { realtimeRecorder?.stop() }
                    }
                }
            }
        )
        realtimeSession = activeSession
        ui.showRealtimeConnecting()
        sessionJob = service.lifecycleScope.launch {
            var activeRecorder: PcmStreamRecorder? = null
            try {
                activeSession.connect()
                realtimeFailure.throwIfPresent()
                if (!validateTarget(boundTarget, showError = true)) return@launch
                val streamingRecorder = PcmStreamRecorder()
                activeRecorder = streamingRecorder
                realtimeRecorder = streamingRecorder
                realtimeFailure.throwIfPresent()
                ui.showRealtimeRecording(0, "")
                streamingRecorder.record(
                    onChunk = activeSession::append,
                    onProgress = { elapsedMillis ->
                        ui.root.post {
                            if (attached && realtimeSession === activeSession) {
                                realtimeElapsedSeconds = (elapsedMillis / 1_000L).toInt()
                                if (!realtimeStopping) {
                                    ui.showRealtimeRecording(
                                        realtimeElapsedSeconds,
                                        realtimePartial
                                    )
                                }
                            }
                        }
                    }
                )
                if (realtimeRecorder === streamingRecorder) realtimeRecorder = null
                realtimeFailure.throwIfPresent()
                if (!validateTarget(boundTarget, showError = true)) return@launch
                realtimeStopping = true
                ui.showRealtimeFinalizing(realtimePartial)
                val result = activeSession.finish()
                if (!validateTarget(boundTarget, showError = true)) return@launch
                transcript = result.text
                commitGate.resetForNewTranscript()
                ui.showPreview(result.text)
            } catch (exception: CancellationException) {
                activeSession.cancel()
                throw exception
            } catch (exception: Exception) {
                if (attached) showSessionError(exception)
            } finally {
                if (realtimeRecorder === activeRecorder) realtimeRecorder = null
                if (realtimeSession === activeSession) realtimeSession = null
                activeSession.close()
            }
        }
    }

    private fun stopRecording() {
        recorder?.let { activeRecorder ->
            recorder = null
            ui.showTranscribing()
            activeRecorder.stop()
            return
        }
        realtimeRecorder?.let { activeRecorder ->
            realtimeRecorder = null
            realtimeStopping = true
            ui.showRealtimeFinalizing(realtimePartial)
            activeRecorder.stop()
        }
    }

    private fun cancelSession() {
        cancelWork(clearUi = true)
    }

    private fun cancelWork(clearUi: Boolean) {
        sessionJob?.cancel()
        sessionJob = null
        recorder?.cancel()
        recorder = null
        segmentClient?.cancel()
        segmentClient = null
        realtimeRecorder?.cancel()
        realtimeRecorder = null
        realtimeSession?.cancel()
        realtimeSession = null
        transcript = null
        target = null
        realtimePartial = ""
        realtimeElapsedSeconds = 0
        realtimeStopping = false
        realtimeFailure = RealtimeTerminalFailure()
        commitGate.resetForNewTranscript()
        if (clearUi && attached) {
            renderReadyState()
        }
    }

    private fun insertTranscript() {
        val reviewed = transcript ?: return
        val boundTarget = target ?: return
        if (!commitGate.claim()) return
        if (!validateTarget(boundTarget, showError = true)) return
        if (VoiceTranscriptPolicy.normalize(reviewed) != reviewed || !service.commitText(reviewed)) {
            ui.showError(context.getString(R.string.voice_commit_failed), canRetry = false)
            return
        }
        transcript = null
        target = null
        Toast.makeText(context, R.string.voice_inserted, Toast.LENGTH_SHORT).show()
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

    private fun validatePolicy(): Boolean {
        return when (AiFeatureEntryGate.evaluate(
            allowsTextInspection = service.allowsTextInspectionFeatures(),
            allowsAiInput = VoiceProviderPolicy.allowsSelectedMode(
                mode,
                service.allowsNetworkInputFeatures()
            ),
            hasConfiguredProfile = mode == VoiceProviderMode.DeviceDictation || profile != null
        )) {
            AiFeatureEntryGate.Ready -> true
            AiFeatureEntryGate.PrivateEditor -> {
                ui.showError(context.getString(R.string.voice_private_disabled), canRetry = false)
                false
            }
            AiFeatureEntryGate.NetworkPolicyBlocked -> {
                ui.showError(context.getString(R.string.voice_policy_disabled), canRetry = false)
                false
            }
            AiFeatureEntryGate.SetupRequired -> {
                ui.showSetupRequired(context.getString(R.string.voice_provider_setup_required))
                false
            }
        }
    }

    private fun validateTarget(boundTarget: VoiceEditorTarget, showError: Boolean): Boolean {
        if (!validatePolicy()) return false
        val valid = service.matchesCurrentEditor(
            boundTarget.packageName,
            boundTarget.fieldId,
            boundTarget.inputType,
            boundTarget.cursor,
            boundTarget.cursor
        )
        if (!valid && showError && attached) {
            ui.showError(context.getString(R.string.voice_editor_changed), canRetry = false)
        }
        return valid
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun returnToKeyboard() {
        windowManager.attachWindow(KeyboardWindow)
    }

    private fun renderReadyState() {
        when (mode) {
            VoiceProviderMode.DeviceDictation -> showDeviceDictation()
            VoiceProviderMode.OpenAiRealtime -> profile?.let {
                ui.showReady(
                    voiceProviderLabel(it),
                    realtime = true,
                    showMeeting = shouldShowMeetingEntry()
                )
            } ?: ui.showSetupRequired(context.getString(R.string.voice_provider_setup_required))
            VoiceProviderMode.OpenAiApi -> profile?.let {
                ui.showReady(
                    voiceProviderLabel(it),
                    realtime = false,
                    showMeeting = shouldShowMeetingEntry()
                )
            } ?: ui.showSetupRequired(context.getString(R.string.voice_provider_setup_required))
        }
    }

    private fun shouldShowMeetingEntry(): Boolean =
        VoiceTranscriptionUiPolicy.showMeetingButton(
            hasStoredSttProfile = VoiceProviderCredentialStore(context).hasStoredProfile(),
            allowsTextInspection = service.allowsTextInspectionFeatures(),
            allowsNetworkInput = service.allowsNetworkInputFeatures()
        )

    private fun voiceProviderLabel(configured: VoiceProviderProfile): String =
        context.getString(
            if (mode == VoiceProviderMode.OpenAiRealtime) {
                R.string.voice_openai_realtime_provider_label
            } else {
                R.string.voice_openai_provider_label
            },
            if (mode == VoiceProviderMode.OpenAiRealtime) {
                configured.realtimeTranscriptionModel
            } else {
                configured.transcriptionModel
            }
        )

    private fun showSessionError(exception: Exception) {
        if (exception is VoiceAuthenticationException) {
            ui.showSetupRequired(context.getString(R.string.voice_provider_auth_failed))
            return
        }
        ui.showError(
            context.getString(
                if (exception is VoiceRecordingException) {
                    R.string.voice_record_failed
                } else {
                    R.string.voice_transcription_failed
                }
            ),
            canRetry = true
        )
    }
}

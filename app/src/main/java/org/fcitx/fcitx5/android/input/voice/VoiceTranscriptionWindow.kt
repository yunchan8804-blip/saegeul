/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.EditorIdentity
import org.fcitx.fcitx5.android.input.EditorSelection
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.InputFeatureBlock
import org.fcitx.fcitx5.android.input.ai.AiFeatureEntryGate
import org.fcitx.fcitx5.android.input.ai.AiSettingsNavigator
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.panel.PanelRecoveries
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.mechdancer.dependency.manager.must
import java.util.Locale

/**
 * Dictation window. Device dictation listens inside the keyboard and inserts the final text at
 * once; the OpenAI modes stay preview-first and never claim to be another kind of session.
 */
class VoiceTranscriptionWindow(
    private val permissionResume: VoicePermissionResumeResult? = null,
    private val startMode: VoiceStartMode = VoiceStartMode.Manual
) : InputWindow.ExtendedInputWindow<VoiceTranscriptionWindow>() {
    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private lateinit var ui: VoiceTranscriptionUi
    private var mode: VoiceProviderMode = VoiceProviderMode.DeviceDictation
    private var profile: VoiceProviderProfile? = null
    internal var runtimeFactory: VoiceTranscriptionRuntimeFactory =
        ProductionVoiceTranscriptionRuntimeFactory
    private var segmentRuntime: SegmentTranscriptionRuntime? = null
    private var realtimeRuntime: RealtimeTranscriptionRuntime? = null
    private var realtimePartial = ""
    private var realtimeElapsedSeconds = 0
    private var realtimeStopping = false
    private var sessionJob: Job? = null
    private var systemVoiceInput: Pair<String, InputMethodSubtype>? = null
    private var attached = false
    private var pushToTalkFinished = false
    private val reviewSession = VoiceTranscriptReviewSession()
    private var deviceSession: DeviceSpeechSession? = null
    private var deviceEnvironment: DeviceSpeechEnvironment? = null
    private var deviceListening = DeviceListening.Idle
    private var devicePartial = ""

    private enum class DeviceListening { Idle, Starting, Listening, Finishing }

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
        return VoiceWindowRoot(context, ui.root).apply {
            onTouchBoundary = ::finishPushToTalk
            onHidden = ::releaseHiddenDeviceListening
        }
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
                showPolicyBlock()
                return
            }
            AiFeatureEntryGate.SetupRequired -> {
                ui.showSetupRequired(context.getString(R.string.voice_provider_setup_required))
                return
            }
            AiFeatureEntryGate.Ready -> Unit
        }
        when (mode) {
            VoiceProviderMode.DeviceDictation -> {
                val environment = probeDeviceSpeech()
                if (environment.engine == DeviceSpeechEngine.Unavailable) {
                    showDeviceUnavailable()
                    return
                }
                showDeviceReady()
                startAfterAttach()
            }
            VoiceProviderMode.OpenAiRealtime -> profile?.let {
                ui.showReady(
                    voiceProviderLabel(it),
                    realtime = true,
                    showMeeting = shouldShowMeetingEntry()
                )
                startAfterAttach()
            }
            VoiceProviderMode.OpenAiApi -> profile?.let {
                ui.showReady(
                    voiceProviderLabel(it),
                    realtime = false,
                    showMeeting = shouldShowMeetingEntry()
                )
                startAfterAttach()
            }
        }
    }

    override fun onDetached() {
        attached = false
        cancelWork(clearUi = false)
        reviewSession.clear()
        systemVoiceInput = null
        profile = null
        deviceEnvironment = null
    }

    /**
     * Ends a push-to-talk hold: device dictation stops listening and inserts the result, the
     * OpenAI modes stop recording like the stop button. Calling it again, or in any other start
     * mode, does nothing.
     */
    fun finishPushToTalk() {
        if (startMode != VoiceStartMode.PushToTalk || pushToTalkFinished) return
        pushToTalkFinished = true
        stopRecording()
    }

    private fun startAfterAttach() {
        when (VoiceAttachStartPolicy.decide(startMode, permissionResume != null)) {
            VoiceAttachStart.ResumePermission -> resumeAfterPermissionIfNeeded()
            VoiceAttachStart.Begin -> beginRecording()
            VoiceAttachStart.Wait -> Unit
        }
    }

    private fun beginRecording() {
        if (!validatePolicy()) return
        if (!microphoneReady()) {
            ui.showPermissionRequired()
            requestMicrophonePermission()
            return
        }
        startRecordingWithPermission()
    }

    private fun requestMicrophonePermission() {
        if (!validatePolicy()) return
        if (microphoneReady()) {
            startRecordingWithPermission()
            return
        }
        val boundTarget = captureTarget()
        if (boundTarget == null) {
            ui.showError(context.getString(R.string.voice_cursor_required), canRetry = false)
            return
        }
        ui.showPermissionRequired()
        val requestId = VoicePermissionCoordinator.request(
            context,
            boundTarget,
            skipOnlineDisclosure = mode == VoiceProviderMode.DeviceDictation
        )
        if (requestId == null) ui.showPermissionRequired(denied = true)
    }

    private fun resumeAfterPermissionIfNeeded() {
        val resume = permissionResume ?: return
        if (!resume.granted) {
            ui.showPermissionRequired(denied = true)
            return
        }
        if (!microphoneReady()) {
            ui.showPermissionRequired(denied = true)
            return
        }
        if (!validateTarget(resume.target, showError = true)) return
        startRecordingWithPermission(resume.target)
    }

    private fun probeDeviceSpeech(): DeviceSpeechEnvironment =
        DeviceSpeechProbe.probe(context, service.allowsNetworkInputFeatures())
            .also { deviceEnvironment = it }

    private fun deviceNotice(): String? =
        context.getString(R.string.voice_device_service_notice).takeIf {
            deviceEnvironment?.engine == DeviceSpeechEngine.SystemService
        }

    private fun showDeviceReady() {
        ui.showDeviceReady(deviceNotice(), showMeeting = shouldShowMeetingEntry())
    }

    private fun showDeviceUnavailable() {
        systemVoiceInput = findDeviceVoiceInput()
        val allowsNetwork = service.allowsNetworkInputFeatures()
        ui.showDeviceUnavailable(
            context.getString(
                if (allowsNetwork) {
                    R.string.voice_device_unavailable
                } else {
                    R.string.voice_device_unavailable_offline
                }
            ),
            canSwitchKeyboard =
                VoiceFallbackPolicy.action(systemVoiceInput != null) ==
                    VoiceUnavailableAction.DeviceDictation
        )
    }

    private fun switchToDeviceDictation() {
        if (!validatePolicy()) return
        val voiceInput = systemVoiceInput ?: findDeviceVoiceInput() ?: return
        val (id, subtype) = voiceInput
        InputMethodUtil.switchInputMethod(service, id, subtype)
    }

    private fun findDeviceVoiceInput(): Pair<String, InputMethodSubtype>? =
        InputMethodUtil.findVoiceSubtype(
            AppPrefs.getInstance().keyboard.preferredVoiceInput.getValue()
        )

    private fun startRecordingWithPermission(resumedTarget: VoiceEditorTarget? = null) {
        when (mode) {
            VoiceProviderMode.DeviceDictation -> startDeviceListening(resumedTarget)
            VoiceProviderMode.OpenAiRealtime -> startRealtimeRecordingWithPermission(resumedTarget)
            VoiceProviderMode.OpenAiApi -> startSegmentRecordingWithPermission(resumedTarget)
        }
    }

    private fun startDeviceListening(resumedTarget: VoiceEditorTarget?) {
        if (!validatePolicy() || !microphoneReady()) return
        cancelWork(clearUi = false)
        val environment = probeDeviceSpeech()
        if (environment.engine == DeviceSpeechEngine.Unavailable) {
            showDeviceUnavailable()
            return
        }
        val boundTarget = resumedTarget ?: captureTarget()
        if (boundTarget == null) {
            ui.showError(context.getString(R.string.voice_cursor_required), canRetry = false)
            return
        }
        if (resumedTarget != null && !validateTarget(boundTarget, showError = true)) return
        reviewSession.begin(boundTarget)
        val session = DeviceSpeechSession(context, environment, DeviceSpeechCallbacks())
        deviceSession = session
        deviceListening = DeviceListening.Starting
        ui.showDeviceStarting(deviceNotice())
        session.start(currentSpeechLanguage())
    }

    private fun currentSpeechLanguage(): String =
        DeviceSpeechLanguage.tagFor(
            fcitx.runImmediately { inputMethodEntryCached }.languageCode,
            Locale.getDefault()
        )

    /** Every callback is dropped unless it belongs to the session the window still owns. */
    private inner class DeviceSpeechCallbacks : DeviceSpeechSession.Listener {
        private val owner: DeviceSpeechSession?
            get() = deviceSession?.takeIf { attached }

        override fun onReady() {
            if (owner == null || deviceListening != DeviceListening.Starting) return
            deviceListening = DeviceListening.Listening
            ui.showDeviceListening(deviceNotice(), "")
        }

        override fun onPartial(text: String) {
            if (owner == null) return
            devicePartial = text
            when (deviceListening) {
                DeviceListening.Listening -> ui.showDeviceListening(deviceNotice(), text)
                DeviceListening.Finishing -> ui.showDeviceFinishing(deviceNotice(), text)
                DeviceListening.Starting, DeviceListening.Idle -> Unit
            }
        }

        override fun onRms(db: Float) {
            if (owner == null || deviceListening != DeviceListening.Listening) return
            ui.setDeviceLevel(DeviceSpeechLevel.fraction(db))
        }

        override fun onFinal(text: String) {
            if (owner == null) return
            releaseDeviceSession()
            commitDeviceTranscript(VoiceTranscriptPolicy.normalize(text))
        }

        override fun onError(code: Int) {
            if (owner == null) return
            releaseDeviceSession()
            reviewSession.clear()
            when (DeviceSpeechErrorKind.of(code)) {
                DeviceSpeechErrorKind.NoSpeech -> ui.showDeviceNoSpeech(deviceNotice())
                DeviceSpeechErrorKind.Permission -> ui.showPermissionRequired(denied = true)
                DeviceSpeechErrorKind.Language -> showDeviceError(R.string.voice_device_error_language)
                DeviceSpeechErrorKind.Busy -> showDeviceError(R.string.voice_device_error_busy)
                DeviceSpeechErrorKind.Network -> showDeviceError(R.string.voice_device_error_network)
                DeviceSpeechErrorKind.Other -> showDeviceError(R.string.voice_device_error)
            }
        }
    }

    private fun showDeviceError(@StringRes message: Int) {
        ui.showError(context.getString(message), canRetry = true)
    }

    private fun commitDeviceTranscript(transcript: String?) {
        if (transcript == null || !reviewSession.publish(transcript)) {
            reviewSession.clear()
            ui.showDeviceNoSpeech(deviceNotice())
            return
        }
        when (reviewSession.insert(
            matchesCurrentEditor = { validateTarget(it, showError = true) },
            commitText = service::commitToEditor
        )) {
            VoiceReviewedCommitResult.Inserted -> returnToKeyboard()
            VoiceReviewedCommitResult.CommitFailed ->
                ui.showError(context.getString(R.string.voice_commit_failed), canRetry = false)
            VoiceReviewedCommitResult.NotReady,
            VoiceReviewedCommitResult.AlreadyConsumed,
            VoiceReviewedCommitResult.EditorChanged -> Unit
        }
    }

    private fun releaseDeviceSession() {
        deviceSession?.let {
            it.cancel()
            it.destroy()
        }
        deviceSession = null
        deviceListening = DeviceListening.Idle
        devicePartial = ""
    }

    /** The keyboard went away while listening: give the microphone back and show the ready state. */
    private fun releaseHiddenDeviceListening() {
        if (deviceSession == null) return
        cancelWork(clearUi = attached)
    }

    private fun startSegmentRecordingWithPermission(resumedTarget: VoiceEditorTarget? = null) {
        if (!validatePolicy() || !microphoneReady()) return
        cancelWork(clearUi = false)
        val boundTarget = resumedTarget ?: captureTarget()
        if (boundTarget == null) {
            ui.showError(context.getString(R.string.voice_cursor_required), canRetry = false)
            return
        }
        if (resumedTarget != null && !validateTarget(boundTarget, showError = true)) return
        val configuredProfile = profile ?: return
        reviewSession.begin(boundTarget)
        val activeRuntime = runtimeFactory.createSegment(configuredProfile)
        segmentRuntime = activeRuntime
        ui.showRecording(0)
        sessionJob = service.lifecycleScope.launch {
            try {
                val result = activeRuntime.run(
                    canContinue = { validateTarget(boundTarget, showError = true) },
                    onProgress = { elapsedMillis ->
                        ui.root.post {
                            if (attached && segmentRuntime === activeRuntime) {
                                ui.showRecording((elapsedMillis / 1_000L).toInt())
                            }
                        }
                    },
                    onTranscribing = {
                        if (attached && segmentRuntime === activeRuntime) ui.showTranscribing()
                    }
                ) ?: return@launch
                if (!reviewSession.publish(result.text)) {
                    showSessionError(VoiceTranscriptionException("Invalid transcript preview"))
                    return@launch
                }
                ui.showPreview(result.text)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (attached) {
                    showSessionError(exception)
                }
            } finally {
                if (segmentRuntime === activeRuntime) segmentRuntime = null
            }
        }
    }

    private fun startRealtimeRecordingWithPermission(resumedTarget: VoiceEditorTarget? = null) {
        if (!validatePolicy() || !microphoneReady()) return
        cancelWork(clearUi = false)
        val boundTarget = resumedTarget ?: captureTarget()
        if (boundTarget == null) {
            ui.showError(context.getString(R.string.voice_cursor_required), canRetry = false)
            return
        }
        if (resumedTarget != null && !validateTarget(boundTarget, showError = true)) return
        val configuredProfile = profile ?: return
        reviewSession.begin(boundTarget)
        realtimePartial = ""
        realtimeElapsedSeconds = 0
        realtimeStopping = false
        lateinit var activeRuntime: RealtimeTranscriptionRuntime
        activeRuntime = runtimeFactory.createRealtime(
            profile = configuredProfile,
            onPartial = { partial ->
                ui.root.post {
                    if (attached && realtimeRuntime === activeRuntime) {
                        realtimePartial = partial
                        if (realtimeStopping) {
                            ui.showRealtimeFinalizing(partial)
                        } else {
                            ui.showRealtimeRecording(realtimeElapsedSeconds, partial)
                        }
                    }
                }
            }
        )
        realtimeRuntime = activeRuntime
        ui.showRealtimeConnecting()
        sessionJob = service.lifecycleScope.launch {
            try {
                val result = activeRuntime.run(
                    canContinue = { validateTarget(boundTarget, showError = true) },
                    onConnected = {
                        if (attached && realtimeRuntime === activeRuntime) {
                            ui.showRealtimeRecording(0, "")
                        }
                    },
                    onProgress = { elapsedMillis ->
                        ui.root.post {
                            if (attached && realtimeRuntime === activeRuntime) {
                                realtimeElapsedSeconds = (elapsedMillis / 1_000L).toInt()
                                if (!realtimeStopping) {
                                    ui.showRealtimeRecording(
                                        realtimeElapsedSeconds,
                                        realtimePartial
                                    )
                                }
                            }
                        }
                    },
                    onFinalizing = {
                        realtimeStopping = true
                        if (attached && realtimeRuntime === activeRuntime) {
                            ui.showRealtimeFinalizing(realtimePartial)
                        }
                    }
                ) ?: return@launch
                if (!reviewSession.publish(result.text)) {
                    showSessionError(VoiceTranscriptionException("Invalid transcript preview"))
                    return@launch
                }
                ui.showPreview(result.text)
            } catch (exception: CancellationException) {
                activeRuntime.cancel()
                throw exception
            } catch (exception: Exception) {
                if (attached) showSessionError(exception)
            } finally {
                if (realtimeRuntime === activeRuntime) realtimeRuntime = null
            }
        }
    }

    private fun stopRecording() {
        deviceSession?.let { activeSession ->
            deviceListening = DeviceListening.Finishing
            ui.showDeviceFinishing(deviceNotice(), devicePartial)
            activeSession.stop()
            return
        }
        segmentRuntime?.let { activeRuntime ->
            ui.showTranscribing()
            activeRuntime.stopRecording()
            return
        }
        realtimeRuntime?.let { activeRuntime ->
            realtimeStopping = true
            ui.showRealtimeFinalizing(realtimePartial)
            activeRuntime.stopRecording()
        }
    }

    private fun cancelSession() {
        cancelWork(clearUi = true)
    }

    private fun cancelWork(clearUi: Boolean) {
        releaseDeviceSession()
        sessionJob?.cancel()
        sessionJob = null
        segmentRuntime?.cancel()
        segmentRuntime = null
        realtimeRuntime?.cancel()
        realtimeRuntime = null
        reviewSession.clear()
        realtimePartial = ""
        realtimeElapsedSeconds = 0
        realtimeStopping = false
        if (clearUi && attached) {
            renderReadyState()
        }
    }

    private fun insertTranscript() {
        when (reviewSession.insert(
            matchesCurrentEditor = { validateTarget(it, showError = true) },
            commitText = service::commitToEditor
        )) {
            VoiceReviewedCommitResult.Inserted -> {
                Toast.makeText(context, R.string.voice_inserted, Toast.LENGTH_SHORT).show()
                returnToKeyboard()
            }
            VoiceReviewedCommitResult.CommitFailed ->
                ui.showError(context.getString(R.string.voice_commit_failed), canRetry = false)
            VoiceReviewedCommitResult.NotReady,
            VoiceReviewedCommitResult.AlreadyConsumed,
            VoiceReviewedCommitResult.EditorChanged -> Unit
        }
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
                showPolicyBlock()
                false
            }
            AiFeatureEntryGate.SetupRequired -> {
                ui.showSetupRequired(context.getString(R.string.voice_provider_setup_required))
                false
            }
        }
    }

    /**
     * Says which gate closed — offline mode or this app's profile — and hands over the
     * setting that reopens it, instead of the older combined "offline or policy" wording.
     */
    private fun showPolicyBlock() {
        val block = service.networkInputBlock() ?: InputFeatureBlock.AppPolicy
        ui.showError(
            context.getString(
                PanelRecoveries.messageFor(block, R.string.voice_private_disabled)
            ),
            canRetry = false,
            recovery = PanelRecoveries.forBlock(service, block)
        )
    }

    private fun validateTarget(boundTarget: VoiceEditorTarget, showError: Boolean): Boolean {
        if (!validatePolicy()) return false
        val valid = service.matchesCurrentEditor(
            EditorIdentity(boundTarget.packageName, boundTarget.fieldId, boundTarget.inputType),
            EditorSelection.collapsed(boundTarget.cursor)
        )
        if (!valid && showError && attached) {
            ui.showError(context.getString(R.string.voice_editor_changed), canRetry = false)
        }
        return valid
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The OpenAI modes upload audio, so they also need the online voice disclosure. Device
     * dictation sends nothing from this app and only needs the microphone permission.
     */
    private fun microphoneReady(): Boolean =
        hasMicrophonePermission() && (
            mode == VoiceProviderMode.DeviceDictation ||
                VoiceDisclosureConsentStore(context).hasAccepted(VoiceDisclosureKind.Microphone)
            )

    private fun returnToKeyboard() {
        windowManager.attachWindow(KeyboardWindow)
    }

    private fun renderReadyState() {
        when (mode) {
            VoiceProviderMode.DeviceDictation -> {
                if (probeDeviceSpeech().engine == DeviceSpeechEngine.Unavailable) {
                    showDeviceUnavailable()
                } else {
                    showDeviceReady()
                }
            }
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

/**
 * Window root that also reports the two events the window cannot see otherwise: a touch boundary
 * (a held push-to-talk button whose release never reached its own view) and the keyboard going
 * hidden or away while the microphone may still be open.
 */
private class VoiceWindowRoot(context: Context, content: View) : FrameLayout(context) {
    var onTouchBoundary: (() -> Unit)? = null
    var onHidden: (() -> Unit)? = null

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> onTouchBoundary?.invoke()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) onHidden?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        onHidden?.invoke()
    }
}

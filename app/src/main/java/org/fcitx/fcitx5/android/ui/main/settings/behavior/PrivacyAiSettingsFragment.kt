/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.ai.AiProviderCredentialStore
import org.fcitx.fcitx5.android.input.ai.EffectiveAiProfile
import org.fcitx.fcitx5.android.input.ai.AiProviderKind
import org.fcitx.fcitx5.android.input.ai.AiProviderProfile
import org.fcitx.fcitx5.android.input.ai.AiProviderResolver
import org.fcitx.fcitx5.android.input.ai.AiAuthMode
import org.fcitx.fcitx5.android.input.ai.AiOAuthLoginActivity
import org.fcitx.fcitx5.android.input.ai.AiOAuthSessionManager
import org.fcitx.fcitx5.android.input.ai.AiOAuthSessionStore
import org.fcitx.fcitx5.android.input.ai.AiProviderSetupActivity
import org.fcitx.fcitx5.android.input.ai.AiUsageStore
import org.fcitx.fcitx5.android.input.gif.GifCache
import org.fcitx.fcitx5.android.input.gif.GifProviderCredentialState
import org.fcitx.fcitx5.android.input.gif.GifProviderCredentialStore
import org.fcitx.fcitx5.android.input.gif.GifProviderKind
import org.fcitx.fcitx5.android.input.gif.GifProviderResolver
import org.fcitx.fcitx5.android.input.gif.GifProviderSelection
import org.fcitx.fcitx5.android.input.gif.GifProviderSelectionStore
import org.fcitx.fcitx5.android.input.gif.GiphyCustomerIdStore
import org.fcitx.fcitx5.android.input.gif.GiphyCredentialState
import org.fcitx.fcitx5.android.input.gif.GiphyProviderConfiguration
import org.fcitx.fcitx5.android.input.gif.GiphyProviderCredentialStore
import org.fcitx.fcitx5.android.input.voice.VoiceProviderCredentialStore
import org.fcitx.fcitx5.android.input.voice.VoiceProviderMode
import org.fcitx.fcitx5.android.input.voice.VoiceProviderModeStore
import org.fcitx.fcitx5.android.input.voice.VoiceProviderModeSelectionPolicy
import org.fcitx.fcitx5.android.input.voice.VoiceProviderPolicy
import org.fcitx.fcitx5.android.input.voice.VoiceProviderProfile
import org.fcitx.fcitx5.android.input.voice.VoiceTranscriptionModel
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import splitties.dimensions.dp
import splitties.resources.styledColor

/** User-visible controls for network input, BYOK credentials, and local traces. */
class PrivacyAiSettingsFragment : PaddingPreferenceFragment() {
    private lateinit var providerPreference: Preference
    private lateinit var clearAiProviderPreference: Preference
    private lateinit var voiceModePreference: Preference
    private lateinit var voiceProviderPreference: Preference
    private lateinit var clearVoiceProviderPreference: Preference
    private lateinit var usagePreference: Preference
    private lateinit var gifSelectionPreference: Preference
    private lateinit var gifProviderPreference: Preference
    private lateinit var clearGifProviderPreference: Preference
    private lateinit var giphyProviderPreference: Preference
    private lateinit var clearGiphyProviderPreference: Preference
    private lateinit var typingDnaPreference: Preference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        val prefs = AppPrefs.getInstance()
        val preferenceIconTint = ctx.styledColor(android.R.attr.colorControlNormal)
        fun themedPreferenceIcon(@DrawableRes resource: Int) =
            AppCompatResources.getDrawable(ctx, resource)?.mutate()?.apply {
                setTint(preferenceIconTint)
            }
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {
            addCategory(R.string.privacy_network_controls) {
                addPreference(SwitchPreferenceCompat(ctx).apply {
                    key = "privacy_offline_mode"
                    setTitle(R.string.offline_mode)
                    setSummary(R.string.offline_mode_summary)
                    isPersistent = false
                    isChecked = prefs.advanced.offlineMode.getValue()
                    setOnPreferenceChangeListener { _, value ->
                        prefs.advanced.offlineMode.setValue(value as Boolean)
                        true
                    }
                })
            }
            addCategory(R.string.ai_provider_settings) {
                providerPreference = Preference(ctx).apply {
                    setTitle(R.string.ai_provider_settings)
                    icon = themedPreferenceIcon(R.drawable.ic_baseline_auto_awesome_24)
                    setOnPreferenceClickListener {
                        showProviderModeDialog()
                        true
                    }
                }
                addPreference(providerPreference)
                clearAiProviderPreference = Preference(ctx).apply {
                    setTitle(R.string.ai_clear_custom_provider)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        clearAiProvider()
                        true
                    }
                }
                addPreference(clearAiProviderPreference)
            }
            addCategory(R.string.voice_provider_settings) {
                voiceModePreference = Preference(ctx).apply {
                    setTitle(R.string.voice_provider_mode_title)
                    icon = themedPreferenceIcon(R.drawable.ic_baseline_keyboard_voice_24)
                    setOnPreferenceClickListener {
                        showVoiceModeDialog()
                        true
                    }
                }
                addPreference(voiceModePreference)
                voiceProviderPreference = Preference(ctx).apply {
                    setTitle(R.string.voice_openai_api_settings)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        showVoiceProviderDialog()
                        true
                    }
                }
                addPreference(voiceProviderPreference)
                clearVoiceProviderPreference = Preference(ctx).apply {
                    setTitle(R.string.voice_provider_key_remove)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        showRemoveVoiceProviderDialog()
                        true
                    }
                }
                addPreference(clearVoiceProviderPreference)
            }
            addCategory(R.string.gif_provider_settings) {
                gifSelectionPreference = Preference(ctx).apply {
                    setTitle(R.string.gif_provider_selection_title)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        showGifProviderSelectionDialog()
                        true
                    }
                }
                addPreference(gifSelectionPreference)
                gifProviderPreference = Preference(ctx).apply {
                    setTitle(R.string.gif_klipy_settings)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        showKlipyProviderDialog()
                        true
                    }
                }
                addPreference(gifProviderPreference)
                clearGifProviderPreference = Preference(ctx).apply {
                    setTitle(R.string.gif_provider_key_remove)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        showRemoveGifProviderDialog()
                        true
                    }
                }
                addPreference(clearGifProviderPreference)
                giphyProviderPreference = Preference(ctx).apply {
                    setTitle(R.string.gif_giphy_settings)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        showGiphyProviderDialog()
                        true
                    }
                }
                addPreference(giphyProviderPreference)
                clearGiphyProviderPreference = Preference(ctx).apply {
                    setTitle(R.string.gif_giphy_key_remove)
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        showRemoveGiphyProviderDialog()
                        true
                    }
                }
                addPreference(clearGiphyProviderPreference)
            }
            addCategory("AI 언어 지문 (Typing DNA)") {
                typingDnaPreference = Preference(ctx).apply {
                    title = "내 언어 지문 리포트"
                    icon = themedPreferenceIcon(R.drawable.ic_baseline_auto_awesome_24)
                    isSelectable = false
                }
                addPreference(typingDnaPreference)
                addPreference(
                    title = "AI 언어 지문 상세 그래프 대시보드 보기",
                    summary = "학습 진행 레벨, 데이터 축적 현황 및 톤 밸런스 그래프를 확인합니다.",
                    onClick = {
                        ctx.startActivity(android.content.Intent(ctx, org.fcitx.fcitx5.android.ui.main.ai.TypingDnaDashboardActivity::class.java))
                    }
                )
                addPreference(
                    title = "지금 언어 지문 분석 및 동기화",
                    summary = "최근 타이핑 데이터를 바탕으로 내 말투와 어휘 습관을 즉시 업데이트합니다.",
                    onClick = {
                        runCatching {
                            org.fcitx.fcitx5.android.input.FcitxInputMethodService.activeInstance?.triggerInstantTypingDnaSync()
                        }
                        val repo = org.fcitx.fcitx5.android.input.ai.TypingDnaRepository(
                            java.io.File(ctx.filesDir, "typing_dna.json")
                        )
                        val summary = repo.getSummary()
                        Toast.makeText(
                            ctx,
                            "언어 지문 분석 완료 (분석 문장: ${summary.totalSentences}개)",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshSummaries()
                    }
                )
                addPreference(
                    title = "언어 지문 전체 초기화 (Zero-Knowledge)",
                    summary = "학습된 모든 말투, 종결 어미, 나만의 표현을 기기에서 영구 삭제합니다.",
                    onClick = {
                        AlertDialog.Builder(ctx)
                            .setTitle("언어 지문 초기화")
                            .setMessage("학습된 말투, 종결 어미, 나만의 표현을 기기에서 완전히 삭제하시겠습니까?")
                            .setPositiveButton(R.string.delete) { _, _ ->
                                val repo = org.fcitx.fcitx5.android.input.ai.TypingDnaRepository(
                                    java.io.File(ctx.filesDir, "typing_dna.json")
                                )
                                repo.clear()
                                runCatching {
                                    org.fcitx.fcitx5.android.input.FcitxInputMethodService.activeInstance?.typingDnaVault?.purge()
                                }
                                refreshSummaries()
                                Toast.makeText(ctx, "언어 지문이 안전하게 초기화되었습니다.", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                )
            }
            addCategory(R.string.privacy_local_data) {
                usagePreference = Preference(ctx).apply {
                    setTitle(R.string.ai_usage_title)
                    isIconSpaceReserved = false
                    isSelectable = false
                }
                addPreference(usagePreference)
                addPreference(R.string.ai_usage_clear, onClick = {
                    AiUsageStore(ctx).clear()
                    refreshSummaries()
                })
                addPreference(R.string.gif_cache_clear, onClick = {
                    GifCache(ctx).clear()
                    Toast.makeText(ctx, R.string.gif_cache_cleared, Toast.LENGTH_SHORT).show()
                })
            }
            addCategory(R.string.privacy_guarantees) {
                addPreference(
                    R.string.privacy_guarantees,
                    R.string.privacy_guarantees_summary
                )
            }
        }
        refreshSummaries()
    }

    override fun onResume() {
        super.onResume()
        if (::providerPreference.isInitialized) refreshSummaries()
        val intent = requireActivity().intent
        val action = intent.getStringExtra(MainActivity.EXTRA_PRIVACY_AI_ACTION)
        if (action != MainActivity.PRIVACY_AI_ACTION_WRITING_SETUP &&
            action != MainActivity.PRIVACY_AI_ACTION_VOICE_SETUP
        ) return
        intent.removeExtra(MainActivity.EXTRA_PRIVACY_AI_ACTION)
        view?.post {
            if (!isAdded) return@post
            if (action == MainActivity.PRIVACY_AI_ACTION_WRITING_SETUP) {
                showProviderModeDialog()
            } else {
                showVoiceProviderDialog()
            }
        }
    }

    private fun refreshSummaries() {
        val ctx = requireContext()
        val effective = AiProviderResolver.resolve(ctx)
        val aiStore = AiProviderCredentialStore(ctx)
        providerPreference.summary = effective.profile?.let { profile ->
            val source = when (effective.source) {
                EffectiveAiProfile.Source.Custom -> getString(R.string.ai_provider_source_custom)
                EffectiveAiProfile.Source.BundledDebug -> getString(R.string.ai_provider_source_bundled)
                EffectiveAiProfile.Source.Missing -> getString(R.string.ai_provider_source_missing)
            }
            val authentication = when (profile.authMode) {
                AiAuthMode.ApiKey -> getString(R.string.ai_auth_api_key)
                AiAuthMode.OAuthPkce -> if (AiOAuthSessionStore(ctx).hasSession(profile)) {
                    getString(R.string.ai_auth_oauth_connected)
                } else {
                    getString(R.string.ai_auth_oauth_reauth)
                }
            }
            getString(
                R.string.ai_provider_configured_summary,
                profile.displayName,
                profile.baseUrl,
                "$source · $authentication"
            )
        } ?: getString(R.string.ai_not_configured)
        clearAiProviderPreference.isVisible = aiStore.hasCustomProfile()
        val voiceMode = VoiceProviderModeStore(ctx).load()
        val voiceStore = VoiceProviderCredentialStore(ctx)
        val voiceProfile = voiceStore.load()
        voiceModePreference.summary = getString(
            when (voiceMode) {
                VoiceProviderMode.DeviceDictation -> R.string.voice_provider_mode_device_summary
                VoiceProviderMode.OpenAiRealtime ->
                    R.string.voice_provider_mode_openai_realtime_summary
                VoiceProviderMode.OpenAiApi -> R.string.voice_provider_mode_openai_summary
            }
        )
        voiceProviderPreference.summary = when {
            voiceProfile != null && !VoiceProviderPolicy.requiresCredential(voiceMode) -> getString(
                R.string.voice_provider_status_optional_configured,
                getString(
                    R.string.voice_models_configured,
                    voiceModelName(voiceProfile.transcriptionModel),
                    voiceProfile.realtimeTranscriptionModel
                )
            )
            voiceProfile != null -> getString(
                R.string.voice_provider_configured_summary,
                getString(
                    R.string.voice_models_configured,
                    voiceModelName(voiceProfile.transcriptionModel),
                    voiceProfile.realtimeTranscriptionModel
                )
            )
            voiceStore.hasStoredProfile() -> getString(R.string.voice_provider_status_unreadable)
            !VoiceProviderPolicy.requiresCredential(voiceMode) ->
                getString(R.string.voice_provider_status_optional_missing)
            else -> getString(R.string.voice_provider_status_missing)
        }
        clearVoiceProviderPreference.isVisible = voiceStore.hasStoredProfile()
        val usage = AiUsageStore(ctx).snapshot()
        usagePreference.summary = getString(
            R.string.ai_usage_summary,
            usage.totalRequests,
            usage.successfulRequests,
            usage.failedRequests,
            usage.inputCharacters
        )
        val gifProvider = GifProviderResolver.resolve(ctx)
        gifSelectionPreference.summary = when (gifProvider.selection) {
            GifProviderSelection.Standard -> getString(R.string.gif_provider_selection_standard)
            GifProviderSelection.Commons -> getString(R.string.gif_provider_selection_commons)
            GifProviderSelection.Giphy -> getString(R.string.gif_provider_selection_giphy)
        }
        gifProviderPreference.summary = when {
            gifProvider.credentialState == GifProviderCredentialState.Unreadable -> {
                getString(R.string.gif_provider_status_unreadable)
            }
            gifProvider.credentialState == GifProviderCredentialState.Configured -> {
                getString(R.string.gif_provider_status_klipy)
            }
            else -> getString(R.string.gif_provider_status_noto)
        }
        clearGifProviderPreference.isVisible =
            gifProvider.credentialState != GifProviderCredentialState.Missing
        giphyProviderPreference.summary = when (gifProvider.giphyCredentialState) {
            GiphyCredentialState.Missing -> getString(R.string.gif_giphy_status_missing)
            GiphyCredentialState.KeyOnly -> getString(R.string.gif_giphy_status_key_only)
            GiphyCredentialState.Unreadable -> getString(R.string.gif_giphy_status_unreadable)
            GiphyCredentialState.Ready -> getString(
                if (gifProvider.giphyMediaCachingApproved) {
                    R.string.gif_giphy_status_ready_attach
                } else {
                    R.string.gif_giphy_status_ready_link_only
                }
            )
        }
        clearGiphyProviderPreference.isVisible =
            gifProvider.giphyCredentialState != GiphyCredentialState.Missing

        if (::typingDnaPreference.isInitialized) {
            val repo = org.fcitx.fcitx5.android.input.ai.TypingDnaRepository(
                java.io.File(ctx.filesDir, "typing_dna.json")
            )
            val s = repo.getStats(forceReload = true)
            typingDnaPreference.summary = if (!s.hasLearnedData) {
                "아직 학습된 언어 지문이 없습니다. 키보드를 사용하면 자동으로 내 말투가 학습됩니다."
            } else {
                "Lv.${s.level} ${s.levelTitle} · 문장 ${s.totalSentences}개 · 단어쌍 ${s.bigramsCount}개 · 어미 ${s.endingsCount}개"
            }
        }
    }

    private fun showProviderModeDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ai_auth_mode_title)
            .setItems(
                arrayOf(
                    "Google Gemini (추천 · 무료 API 키)",
                    "OpenAI (API 키)",
                    getString(R.string.ai_auth_mode_auto_discovery),
                    getString(R.string.ai_auth_mode_advanced)
                )
            ) { _, which ->
                when (which) {
                    0 -> showGeminiProviderDialog()
                    1 -> showOpenAiProviderDialog()
                    2 -> startActivity(AiProviderSetupActivity.createIntent(requireContext()))
                    else -> showAdvancedProviderModeDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAdvancedProviderModeDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ai_auth_mode_advanced)
            .setItems(
                arrayOf(
                    getString(R.string.ai_auth_mode_api_key_advanced),
                    getString(R.string.ai_auth_mode_oauth_advanced)
                )
            ) { _, which ->
                if (which == 0) {
                    showCompatibleProviderDialog()
                } else {
                    showOAuthProviderDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVoiceModeDialog() {
        val ctx = requireContext()
        val store = VoiceProviderModeStore(ctx)
        val values = VoiceProviderMode.entries
        val labels = arrayOf(
            getString(R.string.voice_provider_mode_device),
            getString(R.string.voice_provider_mode_openai_realtime),
            getString(R.string.voice_provider_mode_openai)
        )
        var selected = values.indexOf(store.load()).coerceAtLeast(0)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_provider_mode_title)
            .setSingleChoiceItems(labels, selected) { _, index -> selected = index }
            .setPositiveButton(R.string.save) { _, _ ->
                val mode = values[selected]
                val plan = VoiceProviderModeSelectionPolicy.plan(
                    selectedMode = mode,
                    hasCredential = VoiceProviderCredentialStore(ctx).load() != null
                )
                runCatching { plan.modeToPersist?.let(store::save) }
                    .onSuccess {
                        refreshSummaries()
                        plan.credentialMode?.let { pendingMode ->
                            view?.post { showVoiceProviderDialog(pendingMode) }
                        }
                    }
                    .onFailure {
                        Toast.makeText(ctx, R.string.voice_provider_save_failed, Toast.LENGTH_SHORT)
                            .show()
                    }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showVoiceProviderDialog(pendingMode: VoiceProviderMode? = null) {
        val ctx = requireContext()
        val store = VoiceProviderCredentialStore(ctx)
        val configured = store.load()
        val apiKey = EditText(ctx).apply {
            setHint(
                if (configured == null) {
                    R.string.voice_provider_key_hint
                } else {
                    R.string.voice_provider_key_unchanged_hint
                }
            )
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            maxLines = 1
            isSaveEnabled = false
        }
        val accurate = RadioButton(ctx).apply {
            id = View.generateViewId()
            setText(R.string.voice_model_accurate)
        }
        val efficient = RadioButton(ctx).apply {
            id = View.generateViewId()
            setText(R.string.voice_model_efficient)
        }
        val models = RadioGroup(ctx).apply {
            orientation = RadioGroup.VERTICAL
            addView(accurate)
            addView(efficient)
            check(
                if (configured?.transcriptionModel == VoiceTranscriptionModel.Efficient.id) {
                    efficient.id
                } else {
                    accurate.id
                }
            )
        }
        val horizontal = ctx.dp(20)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, ctx.dp(8), horizontal, ctx.dp(8))
            addView(apiKey)
            addView(models)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_openai_api_settings)
            .setMessage(R.string.voice_provider_security_note)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Keep the save button reachable while the soft keyboard is up, matching the
            // OpenAI credential dialog.
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = apiKey.text.toString().trim().ifEmpty { configured?.apiKey.orEmpty() }
                val model = if (models.checkedRadioButtonId == efficient.id) {
                    VoiceTranscriptionModel.Efficient.id
                } else {
                    VoiceTranscriptionModel.Accurate.id
                }
                val profile = VoiceProviderProfile(apiKey = key, transcriptionModel = model)
                val validated = runCatching(profile::validate)
                    .onFailure { error ->
                        apiKey.error = error.message ?: getString(R.string.voice_provider_invalid)
                    }
                    .getOrNull() ?: return@setOnClickListener
                runCatching {
                    store.save(validated)
                    val selectedMode = VoiceProviderModeSelectionPolicy.afterCredentialSaved(
                        currentMode = VoiceProviderModeStore(ctx).load(),
                        requestedMode = pendingMode
                    )
                    VoiceProviderModeStore(ctx).save(selectedMode)
                }.onSuccess {
                    apiKey.text?.clear()
                    dialog.dismiss()
                    refreshSummaries()
                    Toast.makeText(ctx, R.string.voice_provider_saved, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    apiKey.error = getString(R.string.voice_provider_save_failed)
                }
            }
        }
        dialog.setOnDismissListener { apiKey.text?.clear() }
        dialog.show()
    }

    private fun showRemoveVoiceProviderDialog() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_provider_key_remove)
            .setMessage(R.string.voice_provider_key_remove_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                VoiceProviderCredentialStore(ctx).clear()
                VoiceProviderModeStore(ctx).save(VoiceProviderMode.DeviceDictation)
                refreshSummaries()
                Toast.makeText(ctx, R.string.voice_provider_removed, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun voiceModelName(model: String): String = getString(
        if (model == VoiceTranscriptionModel.Efficient.id) {
            R.string.voice_model_efficient
        } else {
            R.string.voice_model_accurate
        }
    )

    private fun showGeminiProviderDialog() {
        val ctx = requireContext()
        val store = AiProviderCredentialStore(ctx)
        val configured = store.load()?.takeIf {
            it.kind == AiProviderKind.Gemini && it.authMode == AiAuthMode.ApiKey
        }
        val apiKey = EditText(ctx).apply {
            hint = if (configured == null) {
                "Google AI Studio API Key (AIzaSy...)"
            } else {
                getString(R.string.ai_provider_key_unchanged_hint)
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            maxLines = 1
            isSaveEnabled = false
        }
        val horizontal = ctx.dp(20)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, ctx.dp(8), horizontal, ctx.dp(8))
            isFocusableInTouchMode = true
            addView(TextView(ctx).apply {
                text = "Google AI Studio(aistudio.google.com)에서 발급받은 무료 API 키를 입력하세요.\n초고속 Gemini 2.0 Flash 모델로 실시간 AI 문맥 제안 및 문장 다듬기가 활성화됩니다."
                textSize = 13f
                setPadding(0, 0, 0, ctx.dp(8))
            })
            addView(apiKey)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Google Gemini (AI Studio)")
            .setMessage("API 키는 기기 내 Android Keystore로 안전하게 암호화되어 저장됩니다.")
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
            container.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = apiKey.text.toString().trim().ifEmpty {
                    configured?.apiKey.orEmpty()
                }
                val profile = AiProviderProfile(
                    kind = AiProviderKind.Gemini,
                    displayName = "Google Gemini",
                    baseUrl = AiProviderProfile.GEMINI_BASE_URL,
                    authMode = AiAuthMode.ApiKey,
                    apiKey = key,
                    fastModel = "gemini-2.0-flash",
                    balancedModel = "gemini-2.0-flash",
                    qualityModel = "gemini-2.0-flash",
                    capabilities = setOf("chat_completions")
                )
                val validated = runCatching { profile.validate() }
                    .onFailure { error ->
                        apiKey.error = error.message ?: getString(R.string.ai_provider_invalid)
                    }.getOrNull() ?: return@setOnClickListener
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                replaceProviderProfile(validated) { result ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    result.onSuccess {
                        apiKey.text?.clear()
                        dialog.dismiss()
                        refreshSummaries()
                        Toast.makeText(
                            ctx,
                            "Google Gemini 설정이 저장되었습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { error ->
                        apiKey.error = error.message ?: getString(R.string.ai_provider_invalid)
                    }
                }
            }
        }
        dialog.setOnDismissListener { apiKey.text?.clear() }
        dialog.show()
    }

    private fun showOpenAiProviderDialog() {
        val ctx = requireContext()
        val store = AiProviderCredentialStore(ctx)
        val configured = store.load()?.takeIf {
            it.kind == AiProviderKind.OpenAI && it.authMode == AiAuthMode.ApiKey
        }
        val apiKey = EditText(ctx).apply {
            setHint(
                if (configured == null) {
                    R.string.ai_provider_key_hint
                } else {
                    R.string.ai_provider_key_unchanged_hint
                }
            )
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            maxLines = 1
            isSaveEnabled = false
        }
        val horizontal = ctx.dp(20)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, ctx.dp(8), horizontal, ctx.dp(8))
            isFocusableInTouchMode = true
            addView(TextView(ctx).apply {
                setText(R.string.ai_openai_api_key_endpoint_summary)
            })
            addView(apiKey)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.ai_openai_api_key_settings)
            .setMessage(R.string.ai_openai_api_key_security_note)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
            container.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = apiKey.text.toString().trim().ifEmpty {
                    configured?.apiKey.orEmpty()
                }
                val validated = runCatching {
                    AiProviderProfile(apiKey = key).validate()
                }.onFailure { error ->
                    apiKey.error = error.message ?: getString(R.string.ai_provider_invalid)
                }.getOrNull() ?: return@setOnClickListener
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                replaceProviderProfile(validated) { result ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    result.onSuccess {
                        apiKey.text?.clear()
                        dialog.dismiss()
                        refreshSummaries()
                        Toast.makeText(
                            ctx,
                            R.string.ai_openai_api_key_saved,
                            Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure { error ->
                        apiKey.error = error.message ?: getString(R.string.ai_provider_invalid)
                    }
                }
            }
        }
        dialog.setOnDismissListener { apiKey.text?.clear() }
        dialog.show()
    }

    private fun showCompatibleProviderDialog() {
        val ctx = requireContext()
        val store = AiProviderCredentialStore(ctx)
        val custom = store.load()?.takeIf {
            it.authMode == AiAuthMode.ApiKey && it.kind == AiProviderKind.OpenAICompatible
        }
        val effective = custom ?: AiProviderResolver.resolve(ctx).profile
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = ctx.dp(20)
            setPadding(horizontal, ctx.dp(8), horizontal, ctx.dp(8))
            isFocusableInTouchMode = true
        }
        fun field(hint: Int, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            // Prefilled values hide the EditText hint, so repeat it as a fixed label above
            // the field (same idiom as the app profile form labels).
            container.addView(
                TextView(ctx).apply {
                    setText(hint)
                    setPadding(0, ctx.dp(12), 0, 0)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return EditText(ctx).apply {
                setHint(hint)
                setText(value)
                inputType = type
                maxLines = 1
                container.addView(
                    this,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        val name = field(R.string.ai_provider_name_hint, effective?.displayName.orEmpty())
        val baseUrl = field(
            R.string.ai_provider_url_hint,
            effective?.baseUrl ?: AiProviderProfile.OPENAI_BASE_URL,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        val apiKey = field(
            R.string.ai_provider_key_hint,
            "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            hint = if (custom == null) {
                getString(R.string.ai_provider_key_hint)
            } else {
                getString(R.string.ai_provider_key_unchanged_hint)
            }
        }
        val fast = field(R.string.ai_fast_model_hint, effective?.fastModel ?: "gpt-5.6-luna")
        val balanced = field(
            R.string.ai_balanced_model_hint,
            effective?.balancedModel ?: "gpt-5.6-terra"
        )
        val quality = field(R.string.ai_quality_model_hint, effective?.qualityModel ?: "gpt-5.6-sol")
        val scrollContent = boundedAdvancedDialogContent(ctx, container)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.ai_compatible_api_settings)
            .setMessage(R.string.ai_compatible_api_security_note)
            .setView(scrollContent)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
            container.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = apiKey.text.toString().trim().ifEmpty { custom?.apiKey.orEmpty() }
                val profile = AiProviderProfile(
                    kind = when (baseUrl.text.toString().trimEnd('/')) {
                        AiProviderProfile.OPENAI_BASE_URL -> AiProviderKind.OpenAI
                        AiProviderProfile.GEMINI_BASE_URL -> AiProviderKind.Gemini
                        else -> AiProviderKind.OpenAICompatible
                    },
                    displayName = name.text.toString(),
                    baseUrl = baseUrl.text.toString(),
                    authMode = AiAuthMode.ApiKey,
                    apiKey = key,
                    fastModel = fast.text.toString(),
                    balancedModel = balanced.text.toString(),
                    qualityModel = quality.text.toString()
                )
                val validated = runCatching(profile::validate)
                    .onFailure { error ->
                        apiKey.error = error.message ?: getString(R.string.ai_provider_invalid)
                    }
                    .getOrNull() ?: return@setOnClickListener
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                replaceProviderProfile(validated) { result ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    result.onSuccess {
                        dialog.dismiss()
                        refreshSummaries()
                    }.onFailure { error ->
                        apiKey.error = error.message ?: getString(R.string.ai_provider_invalid)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showOAuthProviderDialog() {
        val ctx = requireContext()
        val store = AiProviderCredentialStore(ctx)
        val custom = store.load()?.takeIf { it.authMode == AiAuthMode.OAuthPkce }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = ctx.dp(20)
            setPadding(horizontal, ctx.dp(8), horizontal, ctx.dp(8))
        }
        fun field(hint: Int, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            // Prefilled values hide the EditText hint, so repeat it as a fixed label above
            // the field (same idiom as the app profile form labels).
            container.addView(
                TextView(ctx).apply {
                    setText(hint)
                    setPadding(0, ctx.dp(12), 0, 0)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return EditText(ctx).apply {
                setHint(hint)
                setText(value)
                inputType = type
                maxLines = 1
                isSaveEnabled = false
                container.addView(
                    this,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }
        val uriType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val name = field(R.string.ai_provider_name_hint, custom?.displayName.orEmpty())
        val baseUrl = field(R.string.ai_provider_url_hint, custom?.baseUrl.orEmpty(), uriType)
        val authorizationEndpoint = field(
            R.string.ai_oauth_authorization_endpoint_hint,
            custom?.oauthAuthorizationEndpoint.orEmpty(),
            uriType
        )
        val tokenEndpoint = field(
            R.string.ai_oauth_token_endpoint_hint,
            custom?.oauthTokenEndpoint.orEmpty(),
            uriType
        )
        val revocationEndpoint = field(
            R.string.ai_oauth_revocation_endpoint_hint,
            custom?.oauthRevocationEndpoint.orEmpty(),
            uriType
        )
        val clientId = field(R.string.ai_oauth_client_id_hint, custom?.oauthClientId.orEmpty())
        val scopes = field(
            R.string.ai_oauth_scopes_hint,
            custom?.oauthScopes ?: AiProviderProfile.DEFAULT_OAUTH_SCOPES
        )
        val fast = field(R.string.ai_fast_model_hint, custom?.fastModel ?: "gpt-5.6-luna")
        val balanced = field(
            R.string.ai_balanced_model_hint,
            custom?.balancedModel ?: "gpt-5.6-terra"
        )
        val quality = field(R.string.ai_quality_model_hint, custom?.qualityModel ?: "gpt-5.6-sol")
        val scrollContent = boundedAdvancedDialogContent(ctx, container)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.ai_auth_mode_oauth)
            .setMessage(R.string.ai_oauth_security_note)
            .setView(scrollContent)
            .setPositiveButton(R.string.ai_oauth_save_and_sign_in, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            )
            container.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val profile = AiProviderProfile(
                    kind = AiProviderKind.OpenAICompatible,
                    displayName = name.text.toString(),
                    baseUrl = baseUrl.text.toString(),
                    authMode = AiAuthMode.OAuthPkce,
                    apiKey = "",
                    oauthAuthorizationEndpoint = authorizationEndpoint.text.toString(),
                    oauthTokenEndpoint = tokenEndpoint.text.toString(),
                    oauthRevocationEndpoint = revocationEndpoint.text.toString(),
                    oauthClientId = clientId.text.toString(),
                    oauthScopes = scopes.text.toString(),
                    fastModel = fast.text.toString(),
                    balancedModel = balanced.text.toString(),
                    qualityModel = quality.text.toString()
                )
                val validated = runCatching(profile::validate)
                    .onFailure { error ->
                        baseUrl.error = error.message ?: getString(R.string.ai_provider_invalid)
                    }
                    .getOrNull() ?: return@setOnClickListener
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                replaceProviderProfile(validated) { result ->
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    result.onSuccess {
                        dialog.dismiss()
                        refreshSummaries()
                        startActivity(AiOAuthLoginActivity.createIntent(ctx))
                    }.onFailure { error ->
                        baseUrl.error = error.message ?: getString(R.string.ai_provider_invalid)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun boundedAdvancedDialogContent(
        context: android.content.Context,
        content: View
    ): ScrollView {
        val heightDp = (context.resources.configuration.screenHeightDp / 5)
            .coerceIn(132, 240)
        val maxHeight = context.dp(heightDp)
        return object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = View.MeasureSpec.getSize(heightMeasureSpec)
                val cappedHeight = if (available > 0) minOf(available, maxHeight) else maxHeight
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(cappedHeight, View.MeasureSpec.AT_MOST)
                )
            }
        }.apply {
            isFillViewport = true
            addView(content)
        }
    }

    /** Revoke/clear the previous OAuth session before either auth mode or provider identity changes. */
    private fun replaceProviderProfile(
        profile: AiProviderProfile,
        onComplete: (Result<Unit>) -> Unit
    ) {
        val ctx = requireContext()
        val store = AiProviderCredentialStore(ctx)
        lifecycleScope.launch {
            val result = runCatching {
                val previous = store.load()
                if (previous?.authMode == AiAuthMode.OAuthPkce) {
                    runCatching { AiOAuthSessionManager(ctx).revokeAndClear(previous) }
                } else {
                    AiOAuthSessionStore(ctx).clear()
                }
                store.save(profile)
            }
            onComplete(result)
        }
    }

    private fun clearAiProvider() {
        val ctx = requireContext()
        val store = AiProviderCredentialStore(ctx)
        val profile = store.load()
        lifecycleScope.launch {
            val revoked = if (profile?.authMode == AiAuthMode.OAuthPkce) {
                runCatching { AiOAuthSessionManager(ctx).revokeAndClear(profile) }
                    .getOrDefault(false)
            } else {
                AiOAuthSessionStore(ctx).clear()
                true
            }
            store.clear()
            refreshSummaries()
            Toast.makeText(
                ctx,
                if (revoked) R.string.ai_provider_removed else R.string.ai_oauth_revocation_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showGifProviderSelectionDialog() {
        val ctx = requireContext()
        val store = GifProviderSelectionStore(ctx)
        val values = GifProviderSelection.entries
        val labels = values.map { selection ->
            when (selection) {
                GifProviderSelection.Standard -> getString(R.string.gif_provider_selection_standard)
                GifProviderSelection.Commons -> getString(R.string.gif_provider_selection_commons)
                GifProviderSelection.Giphy -> getString(R.string.gif_provider_selection_giphy)
            }
        }.toTypedArray()
        var selected = values.indexOf(store.load()).coerceAtLeast(0)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.gif_provider_selection_title)
            .setSingleChoiceItems(labels, selected) { _, index -> selected = index }
            .setPositiveButton(R.string.save) { _, _ ->
                runCatching { store.save(values[selected]) }
                    .onSuccess { refreshSummaries() }
                    .onFailure {
                        Toast.makeText(ctx, R.string.gif_provider_selection_failed, Toast.LENGTH_SHORT)
                            .show()
                    }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showKlipyProviderDialog() {
        val ctx = requireContext()
        val store = GifProviderCredentialStore(ctx)
        val configured = store.state() == GifProviderCredentialState.Configured
        val apiKey = EditText(ctx).apply {
            setHint(
                if (configured) {
                    R.string.gif_provider_key_unchanged_hint
                } else {
                    R.string.gif_provider_key_hint
                }
            )
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            maxLines = 1
            isSaveEnabled = false
        }
        val horizontal = ctx.dp(20)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, ctx.dp(8), horizontal, ctx.dp(8))
            addView(
                apiKey,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.gif_klipy_settings)
            .setMessage(R.string.gif_provider_security_note)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Keep the save button reachable while the soft keyboard is up, matching the
            // OpenAI credential dialog.
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = apiKey.text.toString().trim()
                if (key.isEmpty()) {
                    if (configured) {
                        dialog.dismiss()
                    } else {
                        apiKey.error = getString(R.string.gif_provider_key_required)
                    }
                    return@setOnClickListener
                }
                runCatching { store.saveKey(key) }
                    .onSuccess {
                        apiKey.text?.clear()
                        dialog.dismiss()
                        refreshSummaries()
                        Toast.makeText(ctx, R.string.gif_provider_key_saved, Toast.LENGTH_SHORT)
                            .show()
                    }
                    .onFailure {
                        apiKey.error = getString(R.string.gif_provider_key_invalid)
                    }
            }
        }
        dialog.setOnDismissListener { apiKey.text?.clear() }
        dialog.show()
    }

    private fun showGiphyProviderDialog() {
        val ctx = requireContext()
        val store = GiphyProviderCredentialStore(ctx)
        val configured = store.load()
        val apiKey = EditText(ctx).apply {
            setHint(
                if (configured == null) {
                    R.string.gif_giphy_key_hint
                } else {
                    R.string.gif_giphy_key_unchanged_hint
                }
            )
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
            maxLines = 1
            isSaveEnabled = false
        }
        val productionApproved = CheckBox(ctx).apply {
            setText(R.string.gif_giphy_production_approval_confirmation)
            isChecked = configured?.productionApproved == true
        }
        val mediaCachingApproved = CheckBox(ctx).apply {
            setText(R.string.gif_giphy_media_approval_confirmation)
            isChecked = configured?.mediaCachingApproved == true
        }
        val horizontal = ctx.dp(20)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontal, ctx.dp(8), horizontal, ctx.dp(8))
            addView(apiKey)
            addView(productionApproved)
            addView(mediaCachingApproved)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.gif_giphy_settings)
            .setMessage(R.string.gif_giphy_security_note)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Keep the save button reachable while the soft keyboard is up, matching the
            // OpenAI credential dialog.
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = apiKey.text.toString().trim().ifEmpty { configured?.apiKey.orEmpty() }
                if (key.isEmpty()) {
                    apiKey.error = getString(R.string.gif_giphy_key_required)
                    return@setOnClickListener
                }
                if (mediaCachingApproved.isChecked && !productionApproved.isChecked) {
                    mediaCachingApproved.error = getString(R.string.gif_giphy_media_requires_production)
                    return@setOnClickListener
                }
                runCatching {
                    store.save(
                        GiphyProviderConfiguration(
                            apiKey = key,
                            productionApproved = productionApproved.isChecked,
                            mediaCachingApproved = mediaCachingApproved.isChecked
                        )
                    )
                }.onSuccess {
                    apiKey.text?.clear()
                    dialog.dismiss()
                    refreshSummaries()
                    Toast.makeText(ctx, R.string.gif_giphy_key_saved, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    apiKey.error = getString(R.string.gif_giphy_key_invalid)
                }
            }
        }
        dialog.setOnDismissListener { apiKey.text?.clear() }
        dialog.show()
    }

    private fun showRemoveGifProviderDialog() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.gif_provider_key_remove)
            .setMessage(R.string.gif_provider_key_remove_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                GifProviderCredentialStore(ctx).clear()
                refreshSummaries()
                Toast.makeText(ctx, R.string.gif_provider_key_removed, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRemoveGiphyProviderDialog() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.gif_giphy_key_remove)
            .setMessage(R.string.gif_giphy_key_remove_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                GiphyProviderCredentialStore(ctx).clear()
                GiphyCustomerIdStore(ctx).clear()
                refreshSummaries()
                Toast.makeText(ctx, R.string.gif_giphy_key_removed, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

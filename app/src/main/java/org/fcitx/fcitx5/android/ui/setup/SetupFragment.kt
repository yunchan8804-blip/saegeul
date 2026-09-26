/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.setup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.databinding.FragmentSetupBinding
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaInstallState
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelInstaller
import org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallFlow
import org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallStatusView
import org.fcitx.fcitx5.android.ui.main.ai.install.GemmaInstallUiState
import org.fcitx.fcitx5.android.ui.setup.SetupPage.Companion.isLastPage
import org.fcitx.fcitx5.android.utils.serializable

class SetupFragment : Fragment() {

    private val viewModel: SetupViewModel by activityViewModels()

    private lateinit var binding: FragmentSetupBinding
    private lateinit var installStatusView: GemmaInstallStatusView

    private val page: SetupPage by lazy { requireArguments().serializable(PAGE)!! }

    private var isDone: Boolean = false
        set(value) {
            if ((value || !page.isRequired) && page.isLastPage(requireContext())) {
                viewModel.isAllDone.value = true
            }
            with(binding) {
                hintText.text = page.getHintText(requireContext())
                val isAiSuggestion = page == SetupPage.AiSuggestion
                actionButton.visibility = if (value || isAiSuggestion) View.GONE else View.VISIBLE
                actionButton.text = page.getButtonText(requireContext())
                actionButton.setOnClickListener { page.getButtonAction(requireContext()) }
                doneIcon.visibility = if (value && !isAiSuggestion) View.VISIBLE else View.GONE
                doneText.visibility = if (value && !isAiSuggestion) View.VISIBLE else View.GONE
                setupTitle.setText(if (isAiSuggestion) R.string.setup_ai_new_title else R.string.saegeul_setup_title)
                setupSummary.setText(if (isAiSuggestion) R.string.setup_ai_page_summary else R.string.saegeul_setup_summary)
                aiNewTitle.visibility = View.GONE
                aiConsentText.visibility = if (isAiSuggestion) View.VISIBLE else View.GONE
                aiConsentTermsLink.visibility = if (isAiSuggestion) View.VISIBLE else View.GONE
                aiInstallStatus.root.visibility = if (isAiSuggestion) View.VISIBLE else View.GONE
                if (isAiSuggestion) {
                    // Shown inline on this page, so GemmaInstallFlow.start skips its own consent dialog.
                    AppPrefs.getInstance().internal.gemmaInstallConsentShown.setValue(true)
                    val optIn = AppPrefs.getInstance().internal.automaticOnDeviceSuggestionsOptIn
                    aiSuggestionSwitch.apply {
                        visibility = View.VISIBLE
                        setOnCheckedChangeListener(null)
                        isChecked = optIn.getValue()
                        setOnCheckedChangeListener { _, checked -> optIn.setValue(checked) }
                    }
                } else {
                    aiSuggestionSwitch.visibility = View.GONE
                }
            }
            field = value
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSetupBinding.inflate(inflater)
        installStatusView = GemmaInstallStatusView.bind(binding.aiInstallStatus.root)
        if (page == SetupPage.AiSuggestion) {
            installStatusView.onAction = { button -> GemmaInstallFlow.handle(requireActivity(), button) }
            binding.aiConsentTermsLink.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GEMMA_TERMS_URL)))
            }
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    GemmaModelInstaller.state(requireContext().applicationContext).collect { state ->
                        installStatusView.render(requireContext(), GemmaInstallUiState.from(state))
                        binding.aiSecondaryText.visibility = if (
                            state is GemmaInstallState.Downloading ||
                            state is GemmaInstallState.WaitingForNetwork ||
                            state is GemmaInstallState.Verifying
                        ) View.VISIBLE else View.GONE
                    }
                }
            }
        }
        sync()
        return binding.root
    }

    // called on window focus changed
    fun sync() {
        isDone = page.isDone(requireContext())
    }

    override fun onResume() {
        super.onResume()
        sync()
    }

    companion object {
        const val PAGE = "page"
        private const val GEMMA_TERMS_URL = "https://ai.google.dev/gemma/terms"
    }

}

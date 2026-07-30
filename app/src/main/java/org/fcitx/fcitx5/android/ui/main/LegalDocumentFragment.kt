/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.text.util.LinkifyCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.LegalDocumentKind
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.lazyRoute

class LegalDocumentFragment : Fragment() {

    private val args by lazyRoute<SettingsRoute.LegalDocument>()

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
        val verticalPadding = (20 * resources.displayMetrics.density).toInt()
        val content = when (args.kind) {
            LegalDocumentKind.DataPrivacy -> readAsset("legal/DATA-PRIVACY.txt")
            LegalDocumentKind.Notice -> readAsset("legal/NOTICE.txt")
            LegalDocumentKind.ForkNotice -> readAsset("legal/FORK-NOTICE.txt")
            LegalDocumentKind.Source -> sourceDisclosure()
        }

        return NestedScrollView(requireContext()).apply {
            isFillViewport = true
            addView(
                TextView(context).apply {
                    text = content
                    textSize = 15f
                    setTextIsSelectable(true)
                    setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                    LinkifyCompat.addLinks(this, Linkify.WEB_URLS)
                },
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun readAsset(path: String): String =
        requireContext().assets.open(path).bufferedReader().use { it.readText() }

    private fun sourceDisclosure(): String {
        val repository = BuildConfig.SOURCE_REPOSITORY_URL.ifBlank {
            getString(R.string.source_not_configured)
        }
        val sourceArchive = BuildConfig.SOURCE_ARCHIVE_URL.ifBlank {
            getString(R.string.source_not_configured)
        }
        val sourceRef = BuildConfig.SOURCE_TAG.ifBlank { BuildConfig.BUILD_GIT_HASH }
        return getString(
            R.string.source_document,
            repository,
            sourceRef,
            sourceArchive,
            BuildConfig.BUILD_GIT_HASH
        )
    }
}

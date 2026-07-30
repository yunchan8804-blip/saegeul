/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.License
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference

class LicensesFragment : PaddingPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        lifecycleScope.launch {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
                val jsonString = resources.openRawResource(R.raw.aboutlibraries)
                    .bufferedReader()
                    .use { it.readText() }
                val libs = Libs.Builder()
                    .withJson(jsonString)
                    .build()
                addCategory(R.string.bundled_license_texts) {
                    libs.licenses
                        .sortedBy { it.spdxId ?: it.name }
                        .forEach { license ->
                            addPreference(
                                title = license.spdxId ?: license.name,
                                summary = license.name
                            ) {
                                showLicenseContent(license)
                            }
                        }
                }
                addCategory(R.string.licenses_of_third_party_libraries) {
                    libs.libraries
                        .sortedBy {
                            if (it.tag == "native") {
                                it.uniqueId.uppercase()
                            } else {
                                it.uniqueId.lowercase()
                            }
                        }
                        .forEach {
                            addPreference(
                                title = "${it.uniqueId}:${it.artifactVersion}",
                                summary = it.licenses.joinToString { l -> l.spdxId ?: l.name }
                            ) {
                                showLicenseDialog(it.uniqueId, it.licenses)
                            }
                        }
                }
            }
        }
    }

    private fun showLicenseDialog(uniqueId: String, licenses: Set<License>): Boolean {
        when (licenses.size) {
            0 -> {}
            1 -> showLicenseContent(licenses.first())
            else -> {
                val licenseArray = licenses.toTypedArray()
                val licenseNames = licenseArray.map { it.spdxId ?: it.name }.toTypedArray()
                AlertDialog.Builder(context)
                    .setTitle(uniqueId)
                    .setItems(licenseNames) { _, idx ->
                        showLicenseContent(licenseArray[idx])
                    }
                    .setPositiveButton(android.R.string.cancel, null)
                    .show()
            }
        }
        return true
    }

    private fun showLicenseContent(license: License) {
        val bundledContent = license.licenseContent?.trim().orEmpty()
        if (bundledContent.isNotEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(license.spdxId ?: license.name)
                .setMessage(bundledContent)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else if (license.url?.isNotBlank() == true) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(license.url)))
        }
    }

}

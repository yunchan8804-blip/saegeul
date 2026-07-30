/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import timber.log.Timber
import java.net.URI

/** General-user setup: discover a computer, verify its HTTPS manifest, then launch PKCE login. */
class AiProviderSetupActivity : AppCompatActivity(), AiProviderDiscoveryListener {
    private lateinit var discovery: AiProviderDiscoveryManager
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var adapter: ServiceAdapter
    private lateinit var refresh: Button
    private val services = mutableListOf<DiscoveredAiProviderService>()
    private val handler = Handler(Looper.getMainLooper())
    private var waitingForOAuth = false
    private var rejectedServiceCount = 0

    private val scanTimeout = Runnable {
        progress.visibility = View.GONE
        status.setText(
            when {
                services.isNotEmpty() -> R.string.ai_setup_choose_computer
                rejectedServiceCount > 0 -> R.string.ai_setup_only_untrusted_computers
                else -> R.string.ai_setup_no_computers
            }
        )
    }

    private val oauthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        waitingForOAuth = false
        if (result.resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK)
            finish()
        } else {
            progress.visibility = View.GONE
            refresh.isEnabled = true
            status.setText(R.string.ai_setup_login_not_finished)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.ai_setup_wizard_title)
        discovery = AiProviderDiscoveryManager(this, this)
        buildContentView()
    }

    override fun onStart() {
        super.onStart()
        if (!waitingForOAuth) startScan()
    }

    override fun onStop() {
        handler.removeCallbacks(scanTimeout)
        discovery.stop()
        super.onStop()
    }

    private fun buildContentView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
        }
        root.addView(TextView(this).apply {
            setText(R.string.ai_setup_wizard_title)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            setText(R.string.ai_setup_wizard_summary)
            textSize = 16f
            setPadding(0, dp(8), 0, dp(4))
        })
        root.addView(TextView(this).apply {
            setText(R.string.ai_setup_security_summary)
            setPadding(0, 0, 0, dp(16))
        })
        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(
            progress,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
        )
        status = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
            setText(R.string.ai_setup_scanning)
        }
        root.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        adapter = ServiceAdapter(this, services)
        list = ListView(this).apply {
            this.adapter = this@AiProviderSetupActivity.adapter
            dividerHeight = 1
            emptyView = status
            setOnItemClickListener { _, _, position, _ ->
                verifyService(services[position])
            }
        }
        root.addView(
            list,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        refresh = Button(this).apply {
            setText(R.string.ai_setup_scan_again)
            setOnClickListener { startScan() }
        }
        root.addView(
            refresh,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(Button(this).apply {
            setText(R.string.ai_setup_enter_address)
            setOnClickListener { showManifestUrlDialog() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(
                maxOf(dp(24), bars.left),
                maxOf(dp(24), bars.top),
                maxOf(dp(24), bars.right),
                maxOf(dp(20), bars.bottom)
            )
            windowInsets
        }
        setContentView(root)
    }

    private fun startScan() {
        if (waitingForOAuth) return
        handler.removeCallbacks(scanTimeout)
        discovery.stop()
        services.clear()
        adapter.notifyDataSetChanged()
        rejectedServiceCount = 0
        progress.visibility = View.VISIBLE
        refresh.isEnabled = false
        status.visibility = View.VISIBLE
        status.setText(R.string.ai_setup_scanning)
        discovery.start()
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    override fun onDiscoveryStarted() = runOnUiThread {
        refresh.isEnabled = true
    }

    override fun onServiceFound(service: DiscoveredAiProviderService) = runOnUiThread {
        if (services.none { it.manifestUrl == service.manifestUrl }) {
            services.add(service)
            services.sortBy { it.computerName.lowercase() }
            adapter.notifyDataSetChanged()
        }
        progress.visibility = View.GONE
        status.visibility = View.VISIBLE
        status.text = resources.getQuantityString(
            R.plurals.ai_setup_computers_found,
            services.size,
            services.size
        )
    }

    override fun onServiceRejected(computerName: String) = runOnUiThread {
        rejectedServiceCount++
    }

    override fun onDiscoveryFailed(errorCode: Int) = runOnUiThread {
        handler.removeCallbacks(scanTimeout)
        progress.visibility = View.GONE
        refresh.isEnabled = true
        status.setText(R.string.ai_setup_discovery_failed)
    }

    private fun verifyService(service: DiscoveredAiProviderService) {
        verifyManifest(service.computerName, service.manifestUrl)
    }

    private fun verifyManifest(computerName: String, manifestUrl: String) {
        progress.visibility = View.VISIBLE
        refresh.isEnabled = false
        status.setText(R.string.ai_setup_checking_connection)
        lifecycleScope.launch {
            runCatching { AiProviderManifestClient().fetch(manifestUrl) }
                .onSuccess { manifest ->
                    progress.visibility = View.GONE
                    refresh.isEnabled = true
                    showConnectionConfirmation(computerName, manifest)
                }
                .onFailure { error ->
                    val failure = AiProviderManifestFailure.classify(manifestUrl, error)
                    Timber.w("AI provider manifest verification failed: ${failure.name}")
                    progress.visibility = View.GONE
                    refresh.isEnabled = true
                    status.setText(
                        when (failure) {
                            AiProviderManifestFailure.TailnetAddressUnavailable ->
                                R.string.ai_setup_tailnet_address_unavailable
                            AiProviderManifestFailure.AddressUnavailable ->
                                R.string.ai_setup_address_unavailable
                            AiProviderManifestFailure.NetworkUnavailable ->
                                R.string.ai_setup_network_unavailable
                            AiProviderManifestFailure.CertificateUntrusted ->
                                R.string.ai_setup_certificate_untrusted
                            AiProviderManifestFailure.InvalidManifest ->
                                R.string.ai_setup_manifest_invalid
                            AiProviderManifestFailure.Unknown -> R.string.ai_setup_connection_failed
                        }
                    )
                }
        }
    }

    private fun showConnectionConfirmation(
        computerName: String,
        manifest: VerifiedAiProviderManifest
    ) {
        val secureHost = runCatching { URI(manifest.manifestUrl).host }.getOrNull().orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_setup_confirm_title)
            .setMessage(
                getString(
                    R.string.ai_setup_confirm_message,
                    computerName,
                    manifest.profile.displayName,
                    secureHost
                )
            )
            .setPositiveButton(R.string.ai_setup_connect) { _, _ -> connect(manifest.profile) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun connect(profile: AiProviderProfile) {
        handler.removeCallbacks(scanTimeout)
        discovery.stop()
        progress.visibility = View.VISIBLE
        refresh.isEnabled = false
        status.setText(R.string.ai_setup_saving)
        lifecycleScope.launch {
            runCatching {
                val store = AiProviderCredentialStore(this@AiProviderSetupActivity)
                val previous = store.load()
                if (previous?.authMode == AiAuthMode.OAuthPkce && previous != profile) {
                    runCatching {
                        AiOAuthSessionManager(this@AiProviderSetupActivity)
                            .revokeAndClear(previous)
                    }
                } else if (previous != profile) {
                    AiOAuthSessionStore(this@AiProviderSetupActivity).clear()
                }
                store.save(profile)
            }.onSuccess {
                if (AiOAuthSessionStore(this@AiProviderSetupActivity).hasSession(profile)) {
                    Toast.makeText(this@AiProviderSetupActivity, R.string.ai_setup_connected, Toast.LENGTH_SHORT).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    waitingForOAuth = true
                    oauthLauncher.launch(AiOAuthLoginActivity.createIntent(this@AiProviderSetupActivity))
                }
            }.onFailure {
                progress.visibility = View.GONE
                refresh.isEnabled = true
                status.setText(R.string.ai_setup_save_failed)
            }
        }
    }

    private fun showManifestUrlDialog() {
        val input = EditText(this).apply {
            setHint(R.string.ai_setup_manifest_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 1
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ai_setup_enter_address)
            .setMessage(R.string.ai_setup_enter_address_summary)
            .setView(input)
            .setPositiveButton(R.string.ai_setup_check_address, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = AiProviderDiscoveryManager.normalizeManifestUrl(input.text.toString())
                if (url == null) {
                    input.error = getString(R.string.ai_setup_address_invalid)
                } else {
                    dialog.dismiss()
                    verifyManifest(getString(R.string.ai_setup_manual_computer), url)
                }
            }
        }
        dialog.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class ServiceAdapter(
        context: Context,
        services: List<DiscoveredAiProviderService>
    ) : ArrayAdapter<DiscoveredAiProviderService>(
        context,
        android.R.layout.simple_list_item_2,
        android.R.id.text1,
        services
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent)
            val service = getItem(position) ?: return view
            view.findViewById<TextView>(android.R.id.text1).text = service.computerName
            view.findViewById<TextView>(android.R.id.text2).text =
                Uri.parse(service.manifestUrl).host.orEmpty()
            view.contentDescription = context.getString(
                R.string.ai_setup_computer_description,
                service.computerName,
                Uri.parse(service.manifestUrl).host.orEmpty()
            )
            return view
        }
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 12_000L

        fun createIntent(context: Context): Intent =
            Intent(context, AiProviderSetupActivity::class.java)
    }
}

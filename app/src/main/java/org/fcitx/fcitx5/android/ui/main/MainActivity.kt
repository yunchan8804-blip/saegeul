/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.forEach
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.fragment.NavHostFragment
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.databinding.ActivityMainBinding
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.behavior.SentencePackDialog
import org.fcitx.fcitx5.android.ui.setup.SetupActivity
import org.fcitx.fcitx5.android.ui.setup.SetupPage
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.fcitx.fcitx5.android.utils.parcelable
import org.fcitx.fcitx5.android.utils.startActivity
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.topPadding

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var navController: NavController
    private lateinit var rootView: ViewGroup
    private var sentencePackPromptScheduled = false
    private var sentencePackPromptAwaitingFirstFrame = false
    private var notificationPermissionPromptVisible = false
    private var notificationPermissionRequestPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivityMainBinding.inflate(layoutInflater)
        rootView = binding.root
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            windowInsets
        }
        setContentView(binding.root)
        // always show toolbar back arrow icon
        // https://android.googlesource.com/platform/frameworks/support/+/32e643112d0217619237a0d7101b50919c6caf51/navigation/navigation-ui/src/main/java/androidx/navigation/ui/AbstractAppBarOnDestinationChangedListener.kt#80
        binding.toolbar.navigationIcon = DrawerArrowDrawable(this).apply { progress = 1f }
        // show menu icon and other action icons on toolbar
        // don't use `setSupportActionBar(binding.toolbar)` here,
        // because navController would change toolbar title, we need to control it by ourselves
        setupToolbarMenu(binding.toolbar.menu)
        navController = binding.navHostFragment.getFragment<NavHostFragment>().navController
        navController.graph = SettingsRoute.createGraph(navController)
        binding.toolbar.setNavigationOnClickListener {
            // prevent navigate up when child fragment has enabled `OnBackPressedCallback`
            if (onBackPressedDispatcher.hasEnabledCallbacks()) {
                onBackPressedDispatcher.onBackPressed()
                return@setNavigationOnClickListener
            }
            // "minimize" the activity if we can't go back
            navController.navigateUp() || onSupportNavigateUp() || moveTaskToBack(false)
        }
        viewModel.toolbarTitle.observe(this) {
            binding.toolbar.title = it
        }
        viewModel.toolbarShadow.observe(this) {
            binding.toolbar.elevation = dp(if (it) 4f else 0f)
        }
        navController.addOnDestinationChangedListener { _, dest, _ ->
            dest.label?.let { viewModel.setToolbarTitle(it.toString()) }
            if (dest.hasRoute<SettingsRoute.Theme>()) {
                viewModel.disableToolbarShadow()
            } else {
                viewModel.enableToolbarShadow()
            }
        }
        processIntent(intent)
        checkNotificationPermission()
    }

    private fun scheduleSentencePackPrompt() {
        if (
            sentencePackPromptScheduled || sentencePackPromptAwaitingFirstFrame ||
            notificationPermissionPromptVisible || SetupPage.hasUndonePage()
        ) return
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (preferences.getString(SENTENCE_PACK_PROMPT_SCHEMA_KEY, null) == SENTENCE_PACK_PROMPT_SCHEMA) return
        sentencePackPromptAwaitingFirstFrame = true
        rootView.doOnPreDraw {
            rootView.postOnAnimation {
                sentencePackPromptAwaitingFirstFrame = false
                if (
                    isFinishing || isDestroyed || notificationPermissionPromptVisible ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                ) return@postOnAnimation
                if (preferences.getString(SENTENCE_PACK_PROMPT_SCHEMA_KEY, null) == SENTENCE_PACK_PROMPT_SCHEMA) {
                    return@postOnAnimation
                }
                sentencePackPromptScheduled = true
                preferences.edit()
                    .putString(SENTENCE_PACK_PROMPT_SCHEMA_KEY, SENTENCE_PACK_PROMPT_SCHEMA)
                    .apply()
                SentencePackDialog.show(
                    context = this,
                    lifecycleOwner = this,
                    repository = org.fcitx.fcitx5.android.FcitxApplication.getInstance().sentencePacks,
                    isOfflineMode = { AppPrefs.getInstance().advanced.offlineMode.getValue() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        scheduleSentencePackPrompt()
    }

    private fun processIntent(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_MAIN -> if (SetupActivity.shouldShowUp()) {
                startActivity<SetupActivity>()
            }
            Intent.ACTION_VIEW -> intent.data?.let {
                AlertDialog.Builder(this)
                    .setTitle(R.string.pinyin_dict)
                    .setMessage(R.string.whether_import_dict)
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        navController.popBackStack(SettingsRoute.Index, false)
                        navController.navigateWithAnim(SettingsRoute.PinyinDict(it))
                    }
                    .show()
            }
            Intent.ACTION_RUN -> {
                val route = intent.parcelable<SettingsRoute>(EXTRA_SETTINGS_ROUTE) ?: return
                navController.popBackStack(SettingsRoute.Index, false)
                navController.navigateWithAnim(route)
            }
        }
    }

    private fun setupToolbarMenu(menu: Menu) {
        val iconTint = styledColor(android.R.attr.colorControlNormal)
        menu.item(R.string.save, R.drawable.ic_baseline_save_24, iconTint, true) {
            viewModel.toolbarSaveButtonOnClickListener.value?.invoke()
        }.apply {
            viewModel.toolbarSaveButtonOnClickListener
                .observe(this@MainActivity) { listener -> isVisible = listener != null }
        }
        val aboutMenuItems = buildList {
            add(menu.item(R.string.faq) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.faqUrl)))
            })
            if (ProductSurfacePolicy.forBuild(BuildConfig.SHOW_DEVELOPER_SURFACES)
                    .showDeveloperTools
            ) {
                add(menu.item(R.string.developer) {
                    navController.navigateWithAnim(SettingsRoute.Developer)
                })
            }
            add(menu.item(R.string.about) {
                navController.navigateWithAnim(SettingsRoute.About)
            })
        }
        viewModel.aboutButton.observe(this@MainActivity) { enabled ->
            aboutMenuItems.forEach { menu -> menu.isVisible = enabled }
        }
        menu.item(R.string.edit, R.drawable.ic_baseline_edit_24, iconTint, true) {
            viewModel.toolbarEditButtonOnClickListener.value?.invoke()
        }.apply {
            viewModel.toolbarEditButtonVisible.observe(this@MainActivity) { isVisible = it }
        }
        menu.item(R.string.delete, R.drawable.ic_baseline_delete_24, iconTint, true) {
            viewModel.toolbarDeleteButtonOnClickListener.value?.invoke()
        }.apply {
            viewModel.toolbarDeleteButtonOnClickListener
                .observe(this@MainActivity) { listener -> isVisible = listener != null }
        }
        // all menus should be invisible and enabled on demand
        menu.forEach { it.isVisible = false }
    }

    private var needNotifications by AppPrefs.getInstance().internal.needNotifications

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                needNotifications = true
                return
            }
            // do not ask again if user denied the request
            if (!needNotifications) return
            // always show a dialog to explain why we need notification permission,
            // regardless of `shouldShowRequestPermissionRationale(...)`
            notificationPermissionPromptVisible = true
            AlertDialog.Builder(this)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(R.string.notification_permission_title)
                .setMessage(R.string.notification_permission_message)
                .setNegativeButton(R.string.i_do_not_need_it) { _, _ ->
                    // do not ask again if user denied the request
                    needNotifications = false
                }
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    notificationPermissionRequestPending = true
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                }
                .create()
                .apply {
                    setOnDismissListener {
                        if (!notificationPermissionRequestPending) {
                            notificationPermissionPromptVisible = false
                            scheduleSentencePackPrompt()
                        }
                    }
                    show()
                }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 0) return
        // do not ask again if user denied the request
        needNotifications = grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED
        notificationPermissionRequestPending = false
        notificationPermissionPromptVisible = false
        scheduleSentencePackPrompt()
    }

    override fun onStop() {
        viewModel.fcitx.runIfReady {
            save()
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_SETTINGS_ROUTE = "${BuildConfig.APPLICATION_ID}.EXTRA_SETTINGS_ROUTE"
        const val EXTRA_PRIVACY_AI_ACTION =
            "${BuildConfig.APPLICATION_ID}.EXTRA_PRIVACY_AI_ACTION"
        const val PRIVACY_AI_ACTION_WRITING_SETUP = "writing_setup"
        const val PRIVACY_AI_ACTION_VOICE_SETUP = "voice_setup"
        private const val SENTENCE_PACK_PROMPT_SCHEMA_KEY = "sentence_pack_prompt_schema"
        private const val SENTENCE_PACK_PROMPT_SCHEMA = "1"
    }

}

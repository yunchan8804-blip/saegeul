/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ads.ThemePointRewardedController
import org.fcitx.fcitx5.android.data.points.PointLedger
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeOwnershipStore
import org.fcitx.fcitx5.android.data.theme.ThemeShopCatalog
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.toast
import splitties.resources.styledDrawable
import java.util.UUID

class ThemeListFragment : Fragment() {

    private lateinit var imageLauncher: ActivityResultLauncher<Theme.Custom?>

    private lateinit var importLauncher: ActivityResultLauncher<String>

    private lateinit var exportLauncher: ActivityResultLauncher<String>

    private lateinit var themeListAdapter: ThemeListAdapter

    private var followSystemDayNightTheme by ThemeManager.prefs.followSystemDayNightTheme

    private var beingExported: Theme.Custom? = null

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        lifecycleScope.launch {
            updateSelectedThemes(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        imageLauncher = registerForActivityResult(CustomThemeActivity.Contract()) { result ->
            if (result == null) return@registerForActivityResult
            when (result) {
                is CustomThemeActivity.BackgroundResult.Created -> {
                    val theme = result.theme
                    themeListAdapter.prependTheme(theme)
                    ThemeManager.saveTheme(theme)
                    if (!followSystemDayNightTheme) {
                        ThemeManager.setNormalModeTheme(theme)
                    }
                }
                is CustomThemeActivity.BackgroundResult.Deleted -> {
                    val name = result.name
                    themeListAdapter.removeTheme(name)
                    ThemeManager.deleteTheme(name)
                }
                is CustomThemeActivity.BackgroundResult.Updated -> {
                    val theme = result.theme
                    themeListAdapter.replaceTheme(theme)
                    ThemeManager.saveTheme(theme)
                }
            }
        }
        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val cr = ctx.contentResolver
                lifecycleScope.withLoadingDialog(ctx) {
                    val name = cr.queryFileName(uri) ?: return@withLoadingDialog
                    val ext = name.substringAfterLast('.')
                    if (ext != "zip") {
                        ctx.importErrorDialog(R.string.exception_theme_filename, ext)
                        return@withLoadingDialog
                    }
                    try {
                        val (newCreated, theme, migrated) = withContext(Dispatchers.IO) {
                            val inputStream = cr.openInputStream(uri)!!
                            ThemeFilesManager.importTheme(inputStream).getOrThrow()
                        }
                        ThemeManager.refreshThemes()
                        if (newCreated) {
                            themeListAdapter.prependTheme(theme)
                        } else {
                            themeListAdapter.replaceTheme(theme)
                        }
                        if (migrated) {
                            ctx.toast(R.string.theme_migrated)
                        }
                    } catch (e: Exception) {
                        ctx.importErrorDialog(e)
                    }
                }
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val exported = beingExported ?: return@registerForActivityResult
                beingExported = null
                lifecycleScope.withLoadingDialog(requireContext()) {
                    try {
                        withContext(Dispatchers.IO) {
                            val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                            ThemeFilesManager.exportTheme(exported, outputStream).getOrThrow()
                        }
                    } catch (e: Exception) {
                        ctx.toast(e)
                    }
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        themeListAdapter = object : ThemeListAdapter() {
            override fun onAddNewTheme() = addTheme()
            override fun onSelectTheme(theme: Theme) = selectTheme(theme)
            override fun onEditTheme(theme: Theme.Custom) = editTheme(theme)
            override fun onExportTheme(theme: Theme.Custom) = exportTheme(theme)
        }
        ThemeManager.refreshThemes()
        themeListAdapter.setThemes(ThemeManager.getAllThemes())
        updateSelectedThemes()
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        return ResponsiveThemeListView(requireContext()).apply {
            adapter = themeListAdapter
            applyNavBarInsetsBottomPadding()
        }
    }

    private fun updateSelectedThemes(activeTheme: Theme? = null) {
        val active = activeTheme ?: ThemeManager.activeTheme
        var light: Theme? = null
        var dark: Theme? = null
        if (followSystemDayNightTheme) {
            light = ThemeManager.prefs.lightModeTheme.getValue()
            dark = ThemeManager.prefs.darkModeTheme.getValue()
        }
        themeListAdapter.setSelectedThemes(active, light, dark)
    }

    private fun addTheme() {
        val ctx = requireContext()
        val actions = arrayOf(
            getString(R.string.choose_image),
            getString(R.string.import_from_file),
            getString(R.string.duplicate_builtin_theme)
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.new_theme)
            .setNegativeButton(android.R.string.cancel, null)
            .setItems(actions) { _, i ->
                when (i) {
                    0 -> imageLauncher.launch(null)
                    1 -> importLauncher.launch("application/zip")
                    2 -> {
                        val view = ResponsiveThemeListView(ctx).apply {
                            // force AlertDialog's customPanel to grow
                            minimumHeight = Int.MAX_VALUE
                        }
                        val dialog = AlertDialog.Builder(ctx)
                            .setTitle(getString(R.string.duplicate_builtin_theme).removeSuffix("…"))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setView(view)
                            .create()
                        view.adapter = object :
                            SimpleThemeListAdapter<Theme.Builtin>(ThemeManager.BuiltinThemes) {
                            override fun onClick(theme: Theme.Builtin) {
                                val newTheme =
                                    theme.deriveCustomNoBackground(UUID.randomUUID().toString())
                                themeListAdapter.prependTheme(newTheme)
                                ThemeManager.saveTheme(newTheme)
                                dialog.dismiss()
                            }
                        }
                        dialog.show()
                    }
                }
            }
            .show()
    }

    private fun selectTheme(theme: Theme) {
        val shopTheme = ThemeShopCatalog.find(theme.name)
        if (shopTheme != null && !ThemeOwnershipStore(requireContext()).isOwned(theme.name)) {
            showShopPurchaseDialog(shopTheme)
            return
        }
        if (followSystemDayNightTheme) {
            val ctx = requireContext()
            AlertDialog.Builder(ctx)
                .setIcon(ctx.styledDrawable(android.R.attr.alertDialogIcon))
                .setTitle(R.string.configure)
                .setMessage(R.string.theme_message_follow_system_day_night_mode_enabled)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.disable_it) { _, _ ->
                    followSystemDayNightTheme = false
                    lifecycleScope.launch {
                        ThemeManager.setNormalModeTheme(theme)
                        updateSelectedThemes()
                    }
                }
                .show()
            return
        }
        ThemeManager.setNormalModeTheme(theme)
    }

    private fun showShopPurchaseDialog(shop: ThemeShopCatalog.ShopTheme) {
        val ctx = requireContext()
        val ledger = PointLedger(ctx)
        val ownership = ThemeOwnershipStore(ctx)
        val balanceView = TextView(ctx).apply {
            setPadding(48, 16, 48, 0)
            text = "현재 포인트: ${ledger.balance()}점"
        }
        var dialog: AlertDialog? = null
        fun tryPurchase() {
            if (ledger.balance() < shop.price) return
            lifecycleScope.launch(Dispatchers.IO) {
                val spent = ledger.spend(
                    shop.price, System.currentTimeMillis(), "theme:${shop.theme.name}"
                )
                withContext(Dispatchers.Main) {
                    if (spent) {
                        ownership.markOwned(shop.theme.name)
                        dialog?.dismiss()
                        ThemeManager.setNormalModeTheme(shop.theme)
                        updateSelectedThemes()
                        ctx.toast("${shop.displayName} 테마를 구매했어요")
                    } else {
                        ctx.toast("포인트가 부족해요")
                    }
                }
            }
        }
        val rewarded = ThemePointRewardedController(requireActivity()) { earned ->
            balanceView.text = "현재 포인트: ${ledger.balance()}점"
            ctx.toast("포인트 +${earned}점")
            tryPurchase()
        }
        rewarded.prepare()
        dialog = AlertDialog.Builder(ctx)
            .setTitle("테마 상점 · ${shop.displayName}")
            .setMessage("이 테마는 포인트 ${shop.price}점이면 사용할 수 있어요. 광고 1편을 시청하면 포인트 1점을 모을 수 있어요.")
            .setView(balanceView)
            .setPositiveButton("구매") { _, _ -> tryPurchase() }
            .setNeutralButton("광고 보고 +1") { _, _ ->
                if (rewarded.isEligible()) {
                    rewarded.showIfEligible()
                } else {
                    ctx.toast("포인트 적립은 하루 3회, 20분 간격으로 할 수 있어요")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editTheme(theme: Theme.Custom) {
        imageLauncher.launch(theme)
    }

    private fun exportTheme(theme: Theme.Custom) {
        beingExported = theme
        exportLauncher.launch(theme.name + ".zip")
    }

    override fun onDestroy() {
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        super.onDestroy()
    }
}

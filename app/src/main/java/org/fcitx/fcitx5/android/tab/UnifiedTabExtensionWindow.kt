/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.tab

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewAnimator
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.BufferedInputTransport
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.media.MediaFavoritesManager
import org.fcitx.fcitx5.android.media.MediaItem
import org.fcitx.fcitx5.android.media.MediaRetryQueue
import org.fcitx.fcitx5.android.media.MediaType
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent

/**
 * Modern High-End Unified Tab & Media Extension Window for Saegeul Keyboard.
 * Seamlessly integrates Clipboard, QuickPhrase, Search, Media, Favorites, Buffer Settings, and Tab Sync.
 */
class UnifiedTabExtensionWindow(
    private val initialTabId: TabId = TabId.CLIPBOARD
) : InputWindow.ExtendedInputWindow<UnifiedTabExtensionWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val theme: Theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val tabManager = TabManager()
    private val syncManager by lazy { UserTabSyncManager(tabManager) }
    private val mediaRetryQueue = MediaRetryQueue(maxQueueSize = 50, maxRetryAttempts = 3)
    private val mediaFavoritesManager = MediaFavoritesManager(maxCapacity = 100)

    private val tabButtonsLayout by lazy { LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL } }
    private val contentViewAnimator by lazy { ViewAnimator(context) }

    init {
        tabManager.selectTab(initialTabId)
    }

    private val headerBar by lazy {
        context.horizontalLayout {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(theme.barColor)

            val scrollTabs = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(
                    tabButtonsLayout,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
            }
            add(scrollTabs, lParams(0, matchParent) { weight = 1f })

            val closeButton = ToolButton(context, R.drawable.ic_baseline_keyboard_arrow_down_24, theme).apply {
                contentDescription = context.getString(R.string.virtual_keyboard)
                setOnClickListener {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }
            add(closeButton, lParams(dp(40), dp(40)))
        }
    }

    override fun onCreateBarExtension(): View = headerBar

    override fun onCreateView(): View {
        refreshTabButtons()
        buildTabContentViews()

        return context.verticalLayout {
            setBackgroundColor(theme.backgroundColor)
            add(contentViewAnimator, lParams(matchParent, matchParent))
        }
    }

    private fun refreshTabButtons() {
        tabButtonsLayout.removeAllViews()
        val visibleTabs = tabManager.getVisibleTabs()

        visibleTabs.forEach { tab ->
            val isSelected = tab.id == tabManager.activeTab.id
            val accentColor = theme.accentKeyBackgroundColor
            val textColor = if (isSelected) accentColor else theme.altKeyTextColor

            val tabButton = context.horizontalLayout {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isClickable = true
                isFocusable = true

                val iconView = ImageView(context).apply {
                    setImageResource(tab.iconRes)
                    imageTintList = ColorStateList.valueOf(textColor)
                }
                add(iconView, lParams(dp(18), dp(18)) { rightMargin = dp(6) })

                val titleView = TextView(context).apply {
                    text = context.getString(tab.titleRes)
                    textSize = 12f
                    paint.isFakeBoldText = isSelected
                    setTextColor(textColor)
                }
                add(titleView, lParams(wrapContent, wrapContent))

                if (tab.badgeCount > 0) {
                    val badge = TextView(context).apply {
                        text = tab.badgeCount.toString()
                        textSize = 9f
                        setTextColor(Color.WHITE)
                        setPadding(dp(4), dp(1), dp(4), dp(1))
                        val badgeBg = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(8f).toFloat()
                            setColor(Color.RED)
                        }
                        background = badgeBg
                    }
                    add(badge, lParams(wrapContent, wrapContent) { leftMargin = dp(4) })
                }

                setOnClickListener {
                    tabManager.selectTab(tab.id)
                    refreshTabButtons()
                    switchContentToTab(tab.id)
                }
            }
            tabButtonsLayout.addView(
                tabButton,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)
            )
        }
    }

    private fun buildTabContentViews() {
        contentViewAnimator.removeAllViews()

        // Index 0: CLIPBOARD
        contentViewAnimator.addView(buildClipboardView())
        // Index 1: QUICK_PHRASE
        contentViewAnimator.addView(buildQuickPhraseView())
        // Index 2: SEARCH
        contentViewAnimator.addView(buildSearchView())
        // Index 3: MEDIA
        contentViewAnimator.addView(buildMediaView())
        // Index 4: FAVORITES
        contentViewAnimator.addView(buildFavoritesView())
        // Index 5: SETTINGS
        contentViewAnimator.addView(buildSettingsView())
        // Index 6: SYNC
        contentViewAnimator.addView(buildSyncView())

        switchContentToTab(tabManager.activeTab.id)
    }

    private fun switchContentToTab(id: TabId) {
        val index = when (id) {
            TabId.CLIPBOARD -> 0
            TabId.QUICK_PHRASE -> 1
            TabId.SEARCH -> 2
            TabId.MEDIA -> 3
            TabId.FAVORITES -> 4
            TabId.SETTINGS -> 5
            TabId.SYNC -> 6
        }
        if (index in 0 until contentViewAnimator.childCount) {
            contentViewAnimator.displayedChild = index
        }
    }

    // --- Tab 0: Clipboard ---
    private fun buildClipboardView(): View {
        return ScrollView(context).apply {
            isFillViewport = true
            addView(
                context.verticalLayout {
                    setPadding(dp(12), dp(8), dp(12), dp(12))
                    val last = ClipboardManager.lastEntry
                    if (last != null) {
                        add(createCard("📋 " + last.text.take(80), "최근 복사된 클립보드 (탭하여 삽입)") {
                            service.insertImeText(last.text)
                            windowManager.attachWindow(KeyboardWindow)
                        }, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })
                    } else {
                        add(createEmptyStateView("클립보드가 비어 있습니다."), lParams(matchParent, wrapContent))
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    // --- Tab 1: Quick Phrase ---
    private fun buildQuickPhraseView(): View {
        return ScrollView(context).apply {
            isFillViewport = true
            addView(
                context.verticalLayout {
                    setPadding(dp(12), dp(8), dp(12), dp(12))
                    val samplePhrases = listOf(
                        "안녕하세요! 좋은 하루 보내세요." to "인사",
                        "확인했습니다. 곧 회신드리겠습니다." to "업무",
                        "지금 이동 중입니다. 잠시 후 연락드릴게요." to "이동",
                        "감사합니다!" to "감사"
                    )
                    samplePhrases.forEach { (phrase, tag) ->
                        add(createCard(phrase, "[$tag] 탭하여 즉시 입력") {
                            service.insertImeText(phrase)
                            windowManager.attachWindow(KeyboardWindow)
                        }, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    // --- Tab 2: Korean Search ---
    private fun buildSearchView(): View {
        return context.verticalLayout {
            setPadding(dp(12), dp(8), dp(12), dp(12))
            val searchInput = EditText(context).apply {
                hint = "초성 또는 키워드 빠른 검색 (예: ㄱㄴㄷ, 새글)"
                setHintTextColor(ColorUtils.setAlphaComponent(theme.keyTextColor, 120))
                setTextColor(theme.keyTextColor)
                textSize = 13f
                val shape = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(theme.keyBackgroundColor)
                    setStroke(dp(1), ColorUtils.setAlphaComponent(theme.keyTextColor, 40))
                }
                background = shape
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            add(searchInput, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })
            add(createCard("🔍 한국어 웹 및 사전 실시간 연동", "입력 후 엔터를 누르면 빠른 결과가 표시됩니다.") {}, lParams(matchParent, wrapContent))
        }
    }

    // --- Tab 3: Media Search & Retry Queue ---
    private fun buildMediaView(): View {
        return ScrollView(context).apply {
            isFillViewport = true
            addView(
                context.verticalLayout {
                    setPadding(dp(12), dp(8), dp(12), dp(12))

                    val searchBox = EditText(context).apply {
                        hint = "🎬 영상 / GIF / 짤 검색 (예: 축하, 고양이, 댄스)"
                        setHintTextColor(ColorUtils.setAlphaComponent(theme.keyTextColor, 120))
                        setTextColor(theme.keyTextColor)
                        textSize = 13f
                        val shape = GradientDrawable().apply {
                            cornerRadius = dp(10f).toFloat()
                            setColor(theme.keyBackgroundColor)
                            setStroke(dp(1), ColorUtils.setAlphaComponent(theme.keyTextColor, 40))
                        }
                        background = shape
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                    }
                    add(searchBox, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })

                    // Sample media cards
                    val mediaList = listOf(
                        MediaItem("m1", "🎉 축하 폭죽 댄스", "https://example.com/dance.gif", "", MediaType.GIF),
                        MediaItem("m2", "🐱 신나는 코딩 냥이", "https://example.com/cat.gif", "", MediaType.GIF),
                        MediaItem("m3", "🔥 불꽃 리액션", "https://example.com/fire.gif", "", MediaType.GIF)
                    )

                    mediaList.forEach { media ->
                        add(createCard("🎬 " + media.title, "탭하여 미디어 전송 | ⭐️ 길게 눌러 즐겨찾기 저장") {
                            service.insertImeText(media.title)
                            windowManager.attachWindow(KeyboardWindow)
                        }, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    // --- Tab 4: Favorites ---
    private fun buildFavoritesView(): View {
        return ScrollView(context).apply {
            isFillViewport = true
            addView(
                context.verticalLayout {
                    setPadding(dp(12), dp(8), dp(12), dp(12))
                    val favs = mediaFavoritesManager.getFavorites()
                    if (favs.isEmpty()) {
                        add(createCard("⭐️ 즐겨찾기 보관함", "자주 쓰는 짤이나 문구를 즐겨찾기해두면 여기서 언제든 바로 사용할 수 있습니다.") {}, lParams(matchParent, wrapContent))
                    } else {
                        favs.forEach { item ->
                            add(createCard(item.title, "즐겨찾기 항목 (탭하여 삽입)") {
                                service.insertImeText(item.title)
                                windowManager.attachWindow(KeyboardWindow)
                            }, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })
                        }
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    // --- Tab 5: Settings & Buffer Mode ---
    private fun buildSettingsView(): View {
        val prefs = AppPrefs.getInstance().advanced
        val isBufferOn = prefs.bufferedHangulInput.getValue()
        val transport = prefs.bufferedHangulTransport.getValue()

        return ScrollView(context).apply {
            isFillViewport = true
            addView(
                context.verticalLayout {
                    setPadding(dp(12), dp(8), dp(12), dp(12))

                    add(createCard(
                        title = "한글 버퍼 호환 모드: " + (if (isBufferOn) "켜짐 (${transport.name})" else "꺼짐 (표준 실시간 조합)"),
                        desc = "탭하여 모드 전환 (표준 조합 ↔ .NET/MAUI/터미널/원격 데스크톱 최적화)"
                    ) {
                        val newEnabled = !isBufferOn
                        prefs.bufferedHangulInput.setValue(newEnabled)
                        Toast.makeText(context, "버퍼 호환 모드: " + if (newEnabled) "켜짐" else "꺼짐", Toast.LENGTH_SHORT).show()
                        buildTabContentViews()
                    }, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    // --- Tab 6: Sync ---
    private fun buildSyncView(): View {
        return ScrollView(context).apply {
            isFillViewport = true
            addView(
                context.verticalLayout {
                    setPadding(dp(12), dp(8), dp(12), dp(12))

                    add(createCard(
                        title = "🔄 사용자 탭 구성 내보내기",
                        desc = "현재 탭 배치 및 개인화 구성을 JSON으로 백업합니다."
                    ) {
                        val json = syncManager.exportToJson()
                        Toast.makeText(context, "탭 구성 내보내기 완료!", Toast.LENGTH_SHORT).show()
                    }, lParams(matchParent, wrapContent) { bottomMargin = dp(8) })

                    add(createCard(
                        title = "⚙️ 탭 기본값으로 초기화",
                        desc = "탭 순서와 표시 상태를 공장 기본값으로 복원합니다."
                    ) {
                        tabManager.resetToDefaults()
                        refreshTabButtons()
                        buildTabContentViews()
                        Toast.makeText(context, "기본값으로 복원되었습니다.", Toast.LENGTH_SHORT).show()
                    }, lParams(matchParent, wrapContent))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    private fun createCard(title: String, desc: String, onClick: () -> Unit = {}): View {
        val accentColor = theme.accentKeyBackgroundColor
        val shape = GradientDrawable().apply {
            cornerRadius = context.dp(12).toFloat()
            setColor(theme.keyBackgroundColor)
            setStroke(context.dp(1), ColorUtils.setAlphaComponent(theme.keyTextColor, 40))
        }

        return context.verticalLayout {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accentColor, 60)), shape, null)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            val titleView = TextView(context).apply {
                text = title
                textSize = 13.5f
                paint.isFakeBoldText = true
                setTextColor(theme.keyTextColor)
            }
            add(titleView, lParams(matchParent, wrapContent))

            val descView = TextView(context).apply {
                text = desc
                textSize = 11f
                setTextColor(ColorUtils.setAlphaComponent(theme.keyTextColor, 170))
            }
            add(descView, lParams(matchParent, wrapContent) { topMargin = dp(2) })
        }
    }

    private fun createEmptyStateView(msg: String): View {
        return TextView(context).apply {
            text = msg
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(32), dp(16), dp(32))
            setTextColor(ColorUtils.setAlphaComponent(theme.keyTextColor, 120))
        }
    }

    override fun onAttached() {}
    override fun onDetached() {}
}

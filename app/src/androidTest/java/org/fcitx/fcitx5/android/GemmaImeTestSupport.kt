/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertTrue

/**
 * Accessibility-node click support shared by the Gemma on-device IME device tests
 * ([GemmaAutomaticImeDeviceTest], [GemmaContextImeDeviceTest]).
 */
internal object GemmaImeTestSupport {

    private const val SCROLL_VIEW_CLASS_NAME = "android.widget.ScrollView"
    private const val MAX_PANEL_SCROLLS = 3
    private const val POLL_INTERVAL_MS = 50L

    /**
     * Clicks [value] inside a scrollable panel (e.g. the on-device context completion panel),
     * scrolling the panel's ScrollView forward up to [MAX_PANEL_SCROLLS] times when the node is
     * not yet visible. Some emulator screen geometries (e.g. 1080x2400) place panel buttons below
     * the first screenful: the accessibility node exists but `isVisibleToUser` is false, so a
     * plain visibility wait never succeeds and the panel must actually be scrolled.
     */
    fun clickVisibleTextInPanel(automation: UiAutomation, packageName: String, value: String, timeoutMs: Long) {
        var scrolls = 0
        var target: AccessibilityNodeInfo? = null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        // The panel is attached before its content is laid out: the accessibility ScrollView can be
        // present with zero height while the panel animates in, so wait for both the panel and the
        // node instead of failing on the first look.
        while (target == null && SystemClock.elapsedRealtime() < deadline) {
            target = findVisibleNodeByTextOrDescription(automation, packageName, value)
            if (target != null) break
            val panel = findVisiblePanelScrollView(automation, packageName)
            if (panel == null) {
                SystemClock.sleep(POLL_INTERVAL_MS)
                continue
            }
            if (scrolls >= MAX_PANEL_SCROLLS) break
            panel.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            scrolls += 1
            waitUntil(POLL_INTERVAL_MS * 4) {
                target = findVisibleNodeByTextOrDescription(automation, packageName, value)
                target != null
            }
        }
        val resolved = requireNotNull(target) { "'$value' visible accessibility node를 panel scroll(${scrolls}회) 뒤에도 찾지 못했습니다." }
        assertTrue("'$value' UI 클릭이 거부되었습니다.", clickable(resolved).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /**
     * Waits until [value] is visible inside a panel, scrolling the panel back toward its top when
     * the node is attached but currently scrolled out of view. [clickVisibleTextInPanel] scrolls the
     * panel forward to reach buttons near the bottom; the status line it must then observe sits
     * above that position, so a plain visibility wait would never see it on tall emulator panels.
     */
    fun waitForVisibleTextInPanel(
        automation: UiAutomation,
        packageName: String,
        value: String,
        timeoutMs: Long
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (findVisibleNodeByTextOrDescription(automation, packageName, value) != null) return true
            findVisiblePanelScrollView(automation, packageName)
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            waitUntil(POLL_INTERVAL_MS * 4) {
                findVisibleNodeByTextOrDescription(automation, packageName, value) != null
            }
        }
        return findVisibleNodeByTextOrDescription(automation, packageName, value) != null
    }

    private fun findVisiblePanelScrollView(automation: UiAutomation, packageName: String): AccessibilityNodeInfo? =
        imeRoots(automation, packageName).firstNotNullOfOrNull { root ->
            root.findVisible { node -> node.className?.toString() == SCROLL_VIEW_CLASS_NAME && node.isScrollable }
        }

    private fun findVisibleNodeByTextOrDescription(
        automation: UiAutomation,
        packageName: String,
        value: String
    ): AccessibilityNodeInfo? = imeRoots(automation, packageName).firstNotNullOfOrNull { root ->
        root.findVisible { node -> node.text?.toString() == value || node.contentDescription?.toString() == value }
    }

    private fun imeRoots(automation: UiAutomation, packageName: String): Sequence<AccessibilityNodeInfo> =
        automation.windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() == packageName }

    private fun AccessibilityNodeInfo.findVisible(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (isVisibleToUser && predicate(this)) return this
        for (index in 0 until childCount) {
            getChild(index)?.findVisible(predicate)?.let { return it }
        }
        return null
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        throw AssertionError("clickable ancestor가 없습니다: $node")
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        predicate()
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationRuntime
import org.fcitx.fcitx5.android.debug.gemma.GemmaExperimentActivity
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelFiles
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.ceil

class GemmaPreparationUiDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun modelFreePreparationScreenKeepsExperimentsFoldedAndCancelsDownloadConsent() {
        val context = instrumentation.targetContext
        val model = GemmaModelFiles.modelFile(context)
        val partialModel = File(requireNotNull(model.parentFile), "model.litertlm.part")
        assertFalse("이 검증은 모델 없는 에뮬레이터에서만 실행해야 합니다: ${model.absolutePath}", model.exists())
        assertFalse("이 검증은 부분 모델 파일이 없는 상태에서만 실행해야 합니다: ${partialModel.absolutePath}", partialModel.exists())
        assertFalse("화면을 열기 전에 온디바이스 생성이 실행 중입니다.", OnDeviceGenerationControl.isGenerating)

        var activity: GemmaExperimentActivity? = null
        try {
            activity = launchActivity()
            waitUntil("Gemma 준비 화면이 레이아웃되지 않았습니다.") {
                onMain {
                    val decor = requireNotNull(activity).window.decorView
                    decor.isShown && !decor.isLayoutRequested && decor.findTextViewWithText(TITLE) != null
                }
            }

            waitUntil("모델 다운로드 초기 동작이 준비되지 않았습니다.") {
                onMain {
                    requireButton(requireNotNull(activity).window.decorView, DOWNLOAD_MODEL).isEnabled
                }
            }
            onMain {
                val decor = requireNotNull(activity).window.decorView
                assertEquals(TITLE, requireTextView(decor, TITLE).text.toString())
                val automaticPreparation = requireViewWithContentDescription(decor, AUTO_ACCUMULATION)
                assertEquals(
                    AUTO_ACCUMULATION,
                    automaticPreparation.contentDescription?.toString()
                )
                assertAdvancedControlsGone(decor)
                assertNoTextClipping(decor)
            }

            assertButtonTouchTargets(requireNotNull(activity), INITIAL_BUTTONS)

            onMain {
                val decor = requireNotNull(activity).window.decorView
                val advanced = requireButton(decor, ADVANCED_SETTINGS)
                scrollToView(decor, advanced)
                assertTrue("고급 실험 설정은 조작 가능해야 합니다.", advanced.isEnabled && advanced.isClickable)
                assertTrue("고급 실험 설정 클릭이 거부되었습니다.", advanced.performClick())
            }
            instrumentation.waitForIdleSync()
            waitUntil("고급 실험 설정이 펼쳐지지 않았습니다.") {
                onMain {
                    val decor = requireNotNull(activity).window.decorView
                    requireTextView(decor, CPU).isShown &&
                        requireTextView(decor, GPU).isShown &&
                        requireButton(decor, FIXED_MATERIAL).isShown
                }
            }
            onMain {
                val decor = requireNotNull(activity).window.decorView
                assertAdvancedControlsVisible(decor)
                assertNoTextClipping(decor)
            }
            assertButtonTouchTargets(requireNotNull(activity), EXPANDED_BUTTONS)

            onMain {
                val decor = requireNotNull(activity).window.decorView
                val advanced = requireButton(decor, ADVANCED_SETTINGS_COLLAPSE)
                scrollToView(decor, advanced)
                assertTrue("고급 실험 설정 접기 클릭이 거부되었습니다.", advanced.performClick())
            }
            instrumentation.waitForIdleSync()
            waitUntil("고급 실험 설정이 다시 접히지 않았습니다.") {
                onMain { requireAdvancedControlsGone(requireNotNull(activity).window.decorView) }
            }

            onMain {
                val decor = requireNotNull(activity).window.decorView
                val download = requireButton(decor, DOWNLOAD_MODEL)
                scrollToView(decor, download)
                assertTrue("모델 다운로드는 모델 없는 초기 화면에서 조작 가능해야 합니다.", download.isEnabled && download.isClickable)
                assertTrue("모델 다운로드 클릭이 거부되었습니다.", download.performClick())
            }
            instrumentation.waitForIdleSync()
            val automation = instrumentation.uiAutomation
            waitUntil("모델 다운로드 동의 대화상자가 표시되지 않았습니다.") {
                findVisibleTextNode(automation, DOWNLOAD_CONFIRMATION_TITLE) != null
            }
            val cancel = requireNotNull(findVisibleClickableTextNode(automation, CANCEL)) {
                "모델 다운로드 동의 대화상자의 취소 버튼을 찾지 못했습니다."
            }
            assertTrue("모델 다운로드 동의 취소 클릭이 거부되었습니다.", cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitUntil("모델 다운로드 동의 대화상자가 취소 뒤에도 남아 있습니다.") {
                findVisibleTextNode(automation, DOWNLOAD_CONFIRMATION_TITLE) == null
            }

            assertFalse("동의를 취소했는데 모델 파일이 생성되었습니다: ${model.absolutePath}", model.exists())
            assertFalse("동의를 취소했는데 부분 모델 파일이 생성되었습니다: ${partialModel.absolutePath}", partialModel.exists())
            assertFalse("동의를 취소했는데 온디바이스 생성이 시작되었습니다.", OnDeviceGenerationControl.isGenerating)
            assertFalse("동의를 취소했는데 자동 준비 추론이 시작되었습니다.", GemmaAccumulationRuntime.isInferring)
            reportGeometry(requireNotNull(activity))
        } finally {
            activity?.let { launched -> instrumentation.runOnMainSync { launched.finish() } }
        }
    }

    private fun assertAdvancedControlsGone(root: View) {
        assertTrue("CPU 고급 제어는 최초 화면에서 GONE 영역 안에 있어야 합니다.", requireTextView(root, CPU).hasGoneAncestor())
        assertTrue("GPU 고급 제어는 최초 화면에서 GONE 영역 안에 있어야 합니다.", requireTextView(root, GPU).hasGoneAncestor())
        assertTrue("다시 보충 시도는 최초 화면에서 GONE 영역 안에 있어야 합니다.", requireButton(root, RETRY_ACCUMULATION).hasGoneAncestor())
        assertTrue("고정 재료 생성은 최초 화면에서 GONE 영역 안에 있어야 합니다.", requireButton(root, FIXED_MATERIAL).hasGoneAncestor())
    }

    private fun requireAdvancedControlsGone(root: View): Boolean {
        assertAdvancedControlsGone(root)
        return true
    }

    private fun assertAdvancedControlsVisible(root: View) {
        listOf(CPU, GPU).forEach { text ->
            val view = requireTextView(root, text)
            assertFalse("$text 고급 제어가 펼친 뒤에도 GONE 영역 안에 있습니다.", view.hasGoneAncestor())
            assertTrue("$text 고급 제어가 펼친 뒤 표시되지 않습니다.", view.isShown)
        }
        val fixedMaterial = requireButton(root, FIXED_MATERIAL)
        assertFalse("고정 재료 생성이 펼친 뒤에도 GONE 영역 안에 있습니다.", fixedMaterial.hasGoneAncestor())
        assertTrue("고정 재료 생성이 펼친 뒤 표시되지 않습니다.", fixedMaterial.isShown)
        val retryAccumulation = requireButton(root, RETRY_ACCUMULATION)
        assertFalse("다시 보충 시도가 펼친 뒤에도 GONE 영역 안에 있습니다.", retryAccumulation.hasGoneAncestor())
        assertTrue("다시 보충 시도가 펼친 뒤 표시되지 않습니다.", retryAccumulation.isShown)
    }

    private fun assertButtonTouchTargets(activity: GemmaExperimentActivity, labels: List<String>) {
        onMain {
            val decor = activity.window.decorView
            labels.forEach { label ->
                val button = requireButton(decor, label)
                scrollToView(decor, button)
                val minimumHeight = ceil(MIN_TOUCH_TARGET_DP * button.resources.displayMetrics.density).toInt()
                val visibleBounds = Rect()
                assertTrue("$label 버튼을 스크롤해도 화면에 표시할 수 없습니다.", button.getGlobalVisibleRect(visibleBounds))
                assertTrue("$label 버튼 높이가 ${MIN_TOUCH_TARGET_DP}dp보다 작습니다: ${button.height}px", button.height >= minimumHeight)
                assertTrue("$label 버튼의 보이는 높이가 ${MIN_TOUCH_TARGET_DP}dp보다 작습니다: ${visibleBounds.height()}px", visibleBounds.height() >= minimumHeight)
            }
        }
    }

    private fun assertNoTextClipping(root: View) {
        root.descendantTextViews()
            .filter { it.visibility == View.VISIBLE && !it.hasGoneAncestor() }
            .forEach { textView ->
                val layout = requireNotNull(textView.layout) { "${textView.text} TextView 레이아웃이 없습니다." }
                for (line in 0 until layout.lineCount) {
                    assertEquals("${textView.text} TextView의 $line 번째 줄이 잘렸습니다.", 0, layout.getEllipsisCount(line))
                }
                assertTrue(
                    "${textView.text} TextView 레이아웃 폭이 가로 콘텐츠 영역을 넘습니다.",
                    layout.width <= textView.width - textView.compoundPaddingLeft - textView.compoundPaddingRight
                )
                assertTrue(
                    "${textView.text} TextView 레이아웃 높이가 세로 콘텐츠 영역을 넘습니다.",
                    layout.height <= textView.height - textView.compoundPaddingTop - textView.compoundPaddingBottom
                )
            }
    }

    private fun scrollToView(root: View, target: View) {
        val scroll = requireNotNull(root.findScrollView()) { "Gemma 준비 화면에 ScrollView가 없습니다." }
        val rect = Rect().also(target::getDrawingRect)
        scroll.offsetDescendantRectToMyCoords(target, rect)
        val maximumScroll = (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0)
        scroll.scrollTo(0, (rect.top - scroll.height / 3).coerceIn(0, maximumScroll))
    }

    private fun launchActivity(): GemmaExperimentActivity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, GemmaExperimentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    ) as GemmaExperimentActivity

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(message, condition())
    }

    private fun findVisibleTextNode(automation: UiAutomation, expectedText: String): AccessibilityNodeInfo? =
        automation.rootInActiveWindow?.findVisibleTextNode(expectedText)

    private fun findVisibleClickableTextNode(automation: UiAutomation, expectedText: String): AccessibilityNodeInfo? =
        automation.rootInActiveWindow?.findVisibleClickableTextNode(expectedText)

    private fun AccessibilityNodeInfo.findVisibleTextNode(expectedText: String): AccessibilityNodeInfo? {
        if (text?.toString() == expectedText && isVisibleToUser) return this
        for (index in 0 until childCount) {
            getChild(index)?.findVisibleTextNode(expectedText)?.let { return it }
        }
        return null
    }

    private fun AccessibilityNodeInfo.findVisibleClickableTextNode(expectedText: String): AccessibilityNodeInfo? {
        if (text?.toString() == expectedText && isVisibleToUser && isClickable) return this
        for (index in 0 until childCount) {
            getChild(index)?.findVisibleClickableTextNode(expectedText)?.let { return it }
        }
        return null
    }

    private fun reportGeometry(activity: GemmaExperimentActivity) {
        val geometry = onMain {
            val decor = activity.window.decorView
            val scroll = requireNotNull(decor.findScrollView())
            JSONObject()
                .put("density", decor.resources.displayMetrics.density)
                .put("rootWidthPx", decor.width)
                .put("rootHeightPx", decor.height)
                .put("scrollRangePx", (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0))
                .put("buttons", JSONArray().apply {
                    GEOMETRY_BUTTONS.forEach { label ->
                        val button = requireButton(decor, label)
                        put(
                            JSONObject()
                                .put("label", label)
                                .put("heightPx", button.height)
                                .put("gone", button.hasGoneAncestor())
                        )
                    }
                })
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("gemmaPreparationUiGeometry", geometry.toString()) })
    }

    private fun requireTextView(root: View, text: String): TextView = requireNotNull(root.findTextViewWithText(text)) {
        "'$text' TextView를 찾지 못했습니다."
    }

    private fun requireButton(root: View, text: String): Button {
        val view = requireTextView(root, text)
        assertTrue("'$text'는 Button이어야 합니다.", view is Button)
        return view as Button
    }

    private fun requireViewWithContentDescription(root: View, description: String): View =
        requireNotNull(root.findViewWithContentDescription(description)) {
            "contentDescription이 '$description'인 View를 찾지 못했습니다."
        }

    private fun View.findTextViewWithText(expected: String): TextView? {
        if (this is TextView && text.toString() == expected) return this
        val group = this as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            group.getChildAt(index).findTextViewWithText(expected)?.let { return it }
        }
        return null
    }

    private fun View.findViewWithContentDescription(expected: String): View? {
        if (contentDescription?.toString() == expected) return this
        val group = this as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            group.getChildAt(index).findViewWithContentDescription(expected)?.let { return it }
        }
        return null
    }

    private fun View.findScrollView(): ScrollView? {
        if (this is ScrollView) return this
        val group = this as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            group.getChildAt(index).findScrollView()?.let { return it }
        }
        return null
    }

    private fun View.descendantTextViews(): Sequence<TextView> = sequence {
        if (this@descendantTextViews is TextView) yield(this@descendantTextViews)
        val group = this@descendantTextViews as? ViewGroup ?: return@sequence
        for (index in 0 until group.childCount) {
            yieldAll(group.getChildAt(index).descendantTextViews())
        }
    }

    private fun View.hasGoneAncestor(): Boolean {
        var current: View? = this
        while (current != null) {
            if (current.visibility == View.GONE) return true
            current = current.parent as? View
        }
        return false
    }

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        return requireNotNull(value)
    }

    private companion object {
        const val TITLE = "AI 문장 준비"
        const val AUTO_ACCUMULATION = "문장 재료 자동 축적"
        const val ADVANCED_SETTINGS = "고급 실험 설정"
        const val ADVANCED_SETTINGS_COLLAPSE = "고급 실험 설정 접기"
        const val DOWNLOAD_MODEL = "모델 다운로드"
        const val DOWNLOAD_CONFIRMATION_TITLE = "Gemma 모델 다운로드"
        const val CANCEL = "취소"
        const val CPU = "CPU"
        const val GPU = "GPU"
        const val FIXED_MATERIAL = "고정 재료 생성"
        const val RETRY_ACCUMULATION = "다시 보충 시도"
        const val MIN_TOUCH_TARGET_DP = 48
        const val WAIT_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 50L

        val INITIAL_BUTTONS = listOf(
            DOWNLOAD_MODEL,
            "받은 모델 가져오기",
            CANCEL,
            "지금 보충 예약",
            ADVANCED_SETTINGS
        )
        val EXPANDED_BUTTONS = INITIAL_BUTTONS.dropLast(1) + listOf(
            ADVANCED_SETTINGS_COLLAPSE,
            RETRY_ACCUMULATION,
            FIXED_MATERIAL,
            "실험 재료 삭제",
            "모델 삭제"
        )
        val GEOMETRY_BUTTONS = INITIAL_BUTTONS + listOf(
            RETRY_ACCUMULATION,
            FIXED_MATERIAL,
            "실험 재료 삭제",
            "모델 삭제"
        )
    }
}

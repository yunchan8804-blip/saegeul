/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.debug.AiEditorTestActivity
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class ContextualCandidateRejectDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun rejectCommitPreservesOriginalContextualReplacementAndSelection() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AiEditorTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        try {
            val editor = onMain {
                requireNotNull(activity.window.decorView.findByContentDescription("Select commit-rejection mode"))
                    .performClick()
                requireNotNull(activity.window.decorView.findByContentDescription("AI E2E commit rejection editor")) as EditText
            }
            val target = onMain {
                EditorTarget(
                    packageName = editor.context.packageName,
                    fieldId = editor.id,
                    inputType = editor.inputType,
                    selectionStart = editor.selectionStart,
                    selectionEnd = editor.selectionEnd
                )
            }
            val ime = waitForCurrentEditor(target)
            val original = onMain { editor.text.toString() }
            val originalSelection = onMain { editor.selectionStart to editor.selectionEnd }

            // This invokes the contextual commit path directly with a candidate that extends the
            // original as its prefix; it does not depend on rendering that candidate in the UI.
            val committed = onMain { ime.commitContextualSentence("$original 후속 문장") }

            assertFalse(committed)
            onMain {
                assertEquals(original, editor.text.toString())
                assertEquals(originalSelection, editor.selectionStart to editor.selectionEnd)
            }
        } finally {
            activity.finish()
        }
    }

    private fun waitForCurrentEditor(target: EditorTarget): FcitxInputMethodService {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val ime = FcitxInputMethodService.activeInstance
            if (ime != null && ime.matchesCurrentEditor(
                    packageName = target.packageName,
                    fieldId = target.fieldId,
                    inputType = target.inputType,
                    selectionStart = target.selectionStart,
                    selectionEnd = target.selectionEnd,
                    expectedInputSessionEpoch = ime.currentInputSessionEpoch
                )
            ) {
                return ime
            }
            SystemClock.sleep(50L)
        }
        assertNotNull("새글 IME가 Reject commit 편집기에 연결되어야 한다.", FcitxInputMethodService.activeInstance)
        throw AssertionError("새글 IME의 현재 편집기 대상이 Reject commit 편집기와 일치하지 않는다.")
    }

    private fun <T : Any> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        return requireNotNull(result)
    }

    private fun View.findByContentDescription(description: String): View? {
        if (contentDescription?.toString() == description) return this
        return (this as? ViewGroup)?.children
            ?.firstNotNullOfOrNull { child -> child.findByContentDescription(description) }
    }

    private val ViewGroup.children: Sequence<View>
        get() = sequence {
            for (index in 0 until childCount) {
                yield(getChildAt(index))
            }
        }

    private data class EditorTarget(
        val packageName: String,
        val fieldId: Int,
        val inputType: Int,
        val selectionStart: Int,
        val selectionEnd: Int
    )
}

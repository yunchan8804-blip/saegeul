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
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingDnaCursorContinuityDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun externalCursorMoveDiscardsOnlyPendingTypingContext() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AiEditorTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        try {
            val editor = onMain {
                requireNotNull(activity.window.decorView.findByContentDescription("Select normal complete-editor mode"))
                    .performClick()
                requireNotNull(activity.window.decorView.findByContentDescription("Clear text"))
                    .performClick()
                requireNotNull(activity.window.decorView.findByContentDescription("AI E2E normal editor")) as EditText
            }
            val packageName = editor.context.packageName
            val ime = waitForCurrentEditor(editorTarget(editor))
            val sentence = "내가 뭘 해야 할지"

            // This directly exercises the contextual commit path after the editor target check.
            assertTrue(onMain { ime.commitContextualSentence(sentence) })
            waitForEditorText(editor, "$sentence ")
            assertTrue(ime.userTypingContextCollector.hasPending(packageName))

            onMain {
                requireNotNull(activity.window.decorView.findByContentDescription("Move cursor to create a stale editor target"))
                    .performClick()
            }
            waitForCursorMove(editor, ime, packageName)
            onMain { assertEquals("$sentence ", editor.text.toString()) }
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
        throw AssertionError("새글 IME가 Normal debug editor에 연결되지 않았다.")
    }

    private fun waitForEditorText(editor: EditText, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain { editor.text.toString() } == expected) return
            SystemClock.sleep(50L)
        }
        assertEquals(expected, onMain { editor.text.toString() })
    }

    private fun waitForCursorMove(
        editor: EditText,
        ime: FcitxInputMethodService,
        packageName: String
    ) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val atStart = onMain { editor.selectionStart == 0 && editor.selectionEnd == 0 }
            if (atStart && !ime.userTypingContextCollector.hasPending(packageName)) return
            SystemClock.sleep(50L)
        }
        assertTrue(onMain { editor.selectionStart == 0 && editor.selectionEnd == 0 })
        assertTrue(!ime.userTypingContextCollector.hasPending(packageName))
    }

    private fun editorTarget(editor: EditText): EditorTarget = onMain {
        EditorTarget(
            packageName = editor.context.packageName,
            fieldId = editor.id,
            inputType = editor.inputType,
            selectionStart = editor.selectionStart,
            selectionEnd = editor.selectionEnd
        )
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

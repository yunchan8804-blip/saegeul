/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.debug.AiEditorTestActivity
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingDnaDeleteContinuityDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun physicalDeleteDiscardsPendingContextBeforeNewSentenceIsRecorded() {
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
            val pendingSentence = "내가 뭘 해야 할지"
            val newSentence = "새 입력입니다"

            assertTrue(onMain { ime.commitContextualSentence(pendingSentence) })
            waitForEditorText(editor, "$pendingSentence ")
            assertTrue(ime.userTypingContextCollector.hasPending(packageName))

            sendPhysicalDelete()
            waitForEditorText(editor, pendingSentence)
            waitForPendingDiscard(ime, packageName)

            assertTrue(onMain { ime.commitContextualSentence(newSentence) })
            waitForEditorText(editor, "$pendingSentence$newSentence ")
            waitForRecordedSentence(ime, packageName, newSentence)
        } finally {
            activity.finish()
        }
    }

    private fun sendPhysicalDelete() =
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL)

    private fun waitForCurrentEditor(target: EditorTarget): FcitxInputMethodService {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var latest = currentEditorState(target)
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = currentEditorState(target)
            if (latest.matches) return requireNotNull(latest.ime)
            SystemClock.sleep(50L)
        }
        throw AssertionError(
            "새글 IME가 Normal debug editor에 연결되지 않았다. expectedTarget=$target; $latest"
        )
    }

    private fun currentEditorState(target: EditorTarget): CurrentEditorState = onMain {
        val ime = FcitxInputMethodService.activeInstance
        val info = ime?.currentInputEditorInfo
        val selection = ime?.currentInputSelection
        val epoch = ime?.currentInputSessionEpoch
        val matches = ime != null && ime.matchesCurrentEditor(
            packageName = target.packageName,
            fieldId = target.fieldId,
            inputType = target.inputType,
            selectionStart = target.selectionStart,
            selectionEnd = target.selectionEnd,
            expectedInputSessionEpoch = ime.currentInputSessionEpoch
        )
        CurrentEditorState(
            ime,
            ime != null,
            matches,
            info?.packageName,
            info?.fieldId,
            info?.inputType,
            selection?.start,
            selection?.end,
            epoch
        )
    }

    private fun waitForEditorText(editor: EditText, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain { editor.text.toString() } == expected) return
            SystemClock.sleep(50L)
        }
        assertEquals(expected, onMain { editor.text.toString() })
    }

    private fun waitForPendingDiscard(ime: FcitxInputMethodService, packageName: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!ime.userTypingContextCollector.hasPending(packageName)) return
            SystemClock.sleep(50L)
        }
        assertTrue(!ime.userTypingContextCollector.hasPending(packageName))
    }

    private fun waitForRecordedSentence(
        ime: FcitxInputMethodService,
        packageName: String,
        sentence: String
    ) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ime.userTypingContextCollector.getSentences(packageName).lastOrNull() == sentence) return
            SystemClock.sleep(50L)
        }
        assertEquals(sentence, ime.userTypingContextCollector.getSentences(packageName).lastOrNull())
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

    private data class CurrentEditorState(
        val ime: FcitxInputMethodService?,
        val activeInstancePresent: Boolean,
        val matches: Boolean,
        val packageName: String?,
        val fieldId: Int?,
        val inputType: Int?,
        val selectionStart: Int?,
        val selectionEnd: Int?,
        val epoch: Long?
    ) {
        override fun toString(): String =
            "activeInstance=$activeInstancePresent, " +
                "currentInputEditorInfo(packageName=$packageName, fieldId=$fieldId, inputType=$inputType), " +
                "currentInputSelection(start=$selectionStart, end=$selectionEnd), epoch=$epoch"
    }
}

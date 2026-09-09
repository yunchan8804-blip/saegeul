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
import org.fcitx.fcitx5.android.input.FcitxInputMethodService.ContextualAppendSnapshot
import org.fcitx.fcitx5.android.input.ai.ContextualAppend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualAppendDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun confirmedAppendInsertsSuffixOnceAfterExactContext() {
        val activity = launchActivity()
        try {
            val editor = selectNormalAndClear(activity)
            val ime = waitForCurrentEditor(editorTarget(editor))
            val prefix = "내가 뭘"

            // The prefix is inserted through the actual contextual commit path, not a quality fixture.
            assertTrue(onMain { ime.commitContextualSentence(prefix) })
            waitForEditorText(editor, "$prefix ")

            val snapshot = onMain {
                ContextualAppendSnapshot(
                    append = ContextualAppend(expectedContext = prefix, suffix = "잘못"),
                    inputSessionEpoch = ime.currentInputSessionEpoch
                )
            }
            assertTrue(onMain { ime.commitContextualSentence("잘못", appendSnapshot = snapshot) })
            waitForEditorText(editor, "$prefix 잘못 ")
        } finally {
            activity.finish()
        }
    }

    @Test
    fun attachedAppendCompletesClauseWithoutAddingWhitespace() {
        val activity = launchActivity()
        try {
            val editor = selectNormalAndClear(activity)
            val ime = waitForCurrentEditor(editorTarget(editor))
            val prefix = "오후 2시 회의"
            val suffix = "에 참석해 주세요."

            assertTrue(onMain { ime.commitToEditor(prefix) })
            waitForEditorText(editor, prefix)

            val snapshot = onMain {
                ContextualAppendSnapshot(
                    append = ContextualAppend(
                        expectedContext = prefix,
                        suffix = suffix,
                        joinMode = ContextualAppend.JoinMode.ATTACH
                    ),
                    inputSessionEpoch = ime.currentInputSessionEpoch
                )
            }
            assertTrue(onMain { ime.commitContextualSentence(suffix, appendSnapshot = snapshot) })
            waitForEditorText(editor, "$prefix$suffix ")
        } finally {
            activity.finish()
        }
    }

    @Test
    fun attachedAppendRejectsWhitespaceTerminatedContextWithoutChangingEditor() {
        val activity = launchActivity()
        try {
            val editor = selectNormalAndClear(activity)
            val ime = waitForCurrentEditor(editorTarget(editor))
            val prefix = "오후 2시 회의 "
            val suffix = "에 참석해 주세요."

            assertTrue(onMain { ime.commitToEditor(prefix) })
            waitForEditorText(editor, prefix)
            val selection = onMain { editor.selectionStart to editor.selectionEnd }
            val snapshot = onMain {
                ContextualAppendSnapshot(
                    append = ContextualAppend(
                        expectedContext = prefix,
                        suffix = suffix,
                        joinMode = ContextualAppend.JoinMode.ATTACH
                    ),
                    inputSessionEpoch = ime.currentInputSessionEpoch
                )
            }

            assertFalse(onMain { ime.commitContextualSentence(suffix, appendSnapshot = snapshot) })
            assertEditorUnchanged(editor, prefix, selection)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun staleAndMismatchedAppendSnapshotsPreserveEditorText() {
        val activity = launchActivity()
        try {
            val editor = selectNormalAndClear(activity)
            val ime = waitForCurrentEditor(editorTarget(editor))
            val prefix = "내가 뭘"
            assertTrue(onMain { ime.commitContextualSentence(prefix) })
            val original = "$prefix "
            waitForEditorText(editor, original)
            val selection = onMain { editor.selectionStart to editor.selectionEnd }

            val staleSnapshot = onMain {
                ContextualAppendSnapshot(
                    append = ContextualAppend(expectedContext = prefix, suffix = "잘못"),
                    inputSessionEpoch = ime.currentInputSessionEpoch - 1
                )
            }
            assertFalse(onMain { ime.commitContextualSentence("잘못", appendSnapshot = staleSnapshot) })
            assertEditorUnchanged(editor, original, selection)

            val mismatchedSnapshot = onMain {
                ContextualAppendSnapshot(
                    append = ContextualAppend(expectedContext = "다른 문맥", suffix = "잘못"),
                    inputSessionEpoch = ime.currentInputSessionEpoch
                )
            }
            assertFalse(onMain { ime.commitContextualSentence("잘못", appendSnapshot = mismatchedSnapshot) })
            assertEditorUnchanged(editor, original, selection)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun rejectedAppendCommitPreservesEditorText() {
        val activity = launchActivity()
        try {
            val editor = onMain {
                requireNotNull(activity.window.decorView.findByContentDescription("Select commit-rejection mode"))
                    .performClick()
                requireNotNull(activity.window.decorView.findByContentDescription("AI E2E commit rejection editor")) as EditText
            }
            val ime = waitForCurrentEditor(editorTarget(editor))
            val original = onMain { editor.text.toString() }
            val selection = onMain { editor.selectionStart to editor.selectionEnd }
            val snapshot = onMain {
                ContextualAppendSnapshot(
                    append = ContextualAppend(expectedContext = original, suffix = "잘못"),
                    inputSessionEpoch = ime.currentInputSessionEpoch
                )
            }

            assertFalse(onMain { ime.commitContextualSentence("잘못", appendSnapshot = snapshot) })
            assertEditorUnchanged(editor, original, selection)
        } finally {
            activity.finish()
        }
    }

    private fun launchActivity(): AiEditorTestActivity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, AiEditorTestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    ) as AiEditorTestActivity

    private fun selectNormalAndClear(activity: AiEditorTestActivity): EditText = onMain {
        requireNotNull(activity.window.decorView.findByContentDescription("Select normal complete-editor mode"))
            .performClick()
        requireNotNull(activity.window.decorView.findByContentDescription("Clear text")).performClick()
        requireNotNull(activity.window.decorView.findByContentDescription("AI E2E normal editor")) as EditText
    }

    private fun waitForCurrentEditor(target: EditorTarget): FcitxInputMethodService {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        var latest = currentEditorState(target)
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = currentEditorState(target)
            if (latest.matches) return requireNotNull(latest.ime)
            SystemClock.sleep(50L)
        }
        throw AssertionError("새글 IME가 debug editor에 연결되지 않았다. expectedTarget=$target; $latest")
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

    private fun assertEditorUnchanged(editor: EditText, text: String, selection: Pair<Int, Int>) {
        onMain {
            assertEquals(text, editor.text.toString())
            assertEquals(selection, editor.selectionStart to editor.selectionEnd)
        }
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

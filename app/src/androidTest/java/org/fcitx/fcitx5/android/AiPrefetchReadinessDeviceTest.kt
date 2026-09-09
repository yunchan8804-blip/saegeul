/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.debug.AiEditorTestActivity
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.AiProviderCredentialStore
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPrefetchReadinessDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun reportsConfiguredAiPrefetchReadiness() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, AiEditorTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        ) as AiEditorTestActivity
        try {
            val editor = onMain {
                requireNotNull(activity.window.decorView.findByContentDescription("Select normal complete-editor mode"))
                    .performClick()
                requireNotNull(activity.window.decorView.findByContentDescription("AI E2E normal editor")) as EditText
            }
            val ime = waitForCurrentEditor(editorTarget(editor))
            val providerConfigured = AiProviderCredentialStore(instrumentation.targetContext).load() != null
            val readiness = onMain {
                PrefetchReadiness(
                    aiAllowed = ime.allowsAiInputFeatures(),
                    networkAllowed = ime.allowsNetworkInputFeatures(),
                    textInspectionAllowed = ime.allowsTextInspectionFeatures()
                )
            }
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putBoolean("providerConfigured", providerConfigured)
                    putBoolean("aiAllowed", readiness.aiAllowed)
                    putBoolean("networkAllowed", readiness.networkAllowed)
                    putBoolean("textInspectionAllowed", readiness.textInspectionAllowed)
                }
            )

            assertTrue("AI provider credential must be configured for this diagnostic.", providerConfigured)
            assertTrue("AI input policy must allow prefetch for this diagnostic.", readiness.aiAllowed)
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

    private data class PrefetchReadiness(
        val aiAllowed: Boolean,
        val networkAllowed: Boolean,
        val textInspectionAllowed: Boolean
    )

    private data class EditorTarget(
        val packageName: String,
        val fieldId: Int,
        val inputType: Int,
        val selectionStart: Int,
        val selectionEnd: Int
    )
}
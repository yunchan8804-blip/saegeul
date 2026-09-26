/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.debug.AiEditorTestActivity
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationScheduler
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationStore
import org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaAccumulationRuntime
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceGenerationControl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaAccumulationKeyboardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun keyboardStopsActiveAccumulation() = runBlocking(Dispatchers.Default) {
        val context = instrumentation.targetContext
        val accumulationStore = GemmaAccumulationStore.get(context)
        val priorEnabled = accumulationStore.load().enabled
        var experiment: ActivityHandle? = null
        var editorActivity: ActivityHandle? = null
        try {
            experiment = launch("org.fcitx.fcitx5.android.debug.gemma.GemmaExperimentActivity")
            await("keyboard must start inactive", 10_000) { !OnDeviceGenerationControl.isKeyboardActive }
            GemmaAccumulationScheduler.setEnabled(context, true)
            await("real accumulation did not start within 120 seconds", 120_000) {
                GemmaAccumulationRuntime.isInferring
            }
            val beforeKeyboard = GemmaAccumulationRuntime.isInferring
            assertTrue("Native generation was not active before keyboard visibility", beforeKeyboard)

            editorActivity = launch(AiEditorTestActivity::class.java.name)
            val editor = awaitEditor(editorActivity.activity)
            instrumentation.runOnMainSync {
                editor.requestFocus()
                val manager = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            await("real keyboard did not become active within 30 seconds", 30_000) {
                OnDeviceGenerationControl.isKeyboardActive
            }
            val app = context.applicationContext as FcitxApplication
            val countAtKeyboard = app.generatedSentenceBank.sentenceCount
            await("native accumulation did not stop after keyboard activation", 30_000) {
                !OnDeviceGenerationControl.isGenerating
            }
            val stateAtNativeStop = accumulationStore.load()
            val openSequenceAtNativeStop = stateAtNativeStop.openSequence
            val consecutiveUnproductiveAtNativeStop = stateAtNativeStop.consecutiveUnproductive
            SystemClock.sleep(2_000)
            assertTrue("Keyboard is no longer active after stability window", OnDeviceGenerationControl.isKeyboardActive)
            val stableCount = app.generatedSentenceBank.sentenceCount == countAtKeyboard
            val stableState = accumulationStore.load()
            assertTrue("Generated material count changed while keyboard was active", stableCount)
            assertEquals(
                "키보드가 활성화된 뒤 공개 문맥 순번이 변경되었습니다.",
                openSequenceAtNativeStop,
                stableState.openSequence
            )
            assertEquals(
                "키보드가 활성화된 뒤 consecutive-unproductive 상태가 변경되었습니다.",
                consecutiveUnproductiveAtNativeStop,
                stableState.consecutiveUnproductive
            )
            assertFalse("Native generation remains active", OnDeviceGenerationControl.isGenerating)
            val evidence = JSONObject()
                .put("observedNativeBeforeKeyboard", beforeKeyboard)
                .put("keyboardActive", OnDeviceGenerationControl.isKeyboardActive)
                .put("nativeStopped", !OnDeviceGenerationControl.isGenerating)
                .put("stableStoredCount", stableCount)
                .put("countAtKeyboard", countAtKeyboard)
                .put("openSequenceAtNativeStop", openSequenceAtNativeStop)
                .put("openSequenceStable", stableState.openSequence)
                .put("consecutiveUnproductiveAtNativeStop", consecutiveUnproductiveAtNativeStop)
                .put("consecutiveUnproductiveStable", stableState.consecutiveUnproductive)
                .put("evidenceScope", "real keyboard visibility stops active native accumulation; no UI content claim")
            instrumentation.sendStatus(0, Bundle().apply {
                putString("gemmaKeyboardStopEvidence", evidence.toString())
            })
        } finally {
            editorActivity?.finish()
            experiment?.finish()
            GemmaAccumulationScheduler.setEnabled(context, priorEnabled)
        }
    }

    private fun launch(className: String): ActivityHandle = ActivityHandle(
        instrumentation.startActivitySync(Intent().setClassName(instrumentation.targetContext, className).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    )

    private fun awaitEditor(activity: Any): EditText {
        var editor: EditText? = null
        await("editor activity did not expose EditText", 10_000) {
            instrumentation.runOnMainSync {
                editor = (activity as android.app.Activity).window.decorView.findEditText()
            }
            editor != null
        }
        return requireNotNull(editor)
    }

    private fun await(message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun View.findEditText(): EditText? {
        if (this is EditText) return this
        return (this as? ViewGroup)?.let { group ->
            (0 until group.childCount).asSequence().mapNotNull { group.getChildAt(it)?.findEditText() }.firstOrNull()
        }
    }

    private inner class ActivityHandle(val activity: Any) {
        fun finish() = instrumentation.runOnMainSync { (activity as android.app.Activity).finish() }
    }
}

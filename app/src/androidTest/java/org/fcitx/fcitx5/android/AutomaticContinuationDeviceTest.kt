/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.debug.AiEditorTestActivity
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.ai.AiAuthMode
import org.fcitx.fcitx5.android.input.ai.AiOAuthSessionStore
import org.fcitx.fcitx5.android.input.ai.AiProviderCredentialStore
import org.fcitx.fcitx5.android.input.ai.AiPrefetchConnectionState
import org.fcitx.fcitx5.android.input.ai.AiSentenceCompletionPrefetcher
import org.fcitx.fcitx5.android.input.ai.PrefetchedContinuation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class AutomaticContinuationDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun installedProviderAutomaticallyPrefetchesVisibleSentenceCandidate() {
        val activity = launchActivity()
        val automation = configureUiAutomation()
        val syntheticPrefix = syntheticPrefix()
        try {
            val editor = selectNormalAndClear(activity)
            val ime = waitForCurrentEditor(editorTarget(editor))
            val provider = loadProviderReadiness()
            val offlineMode = AppPrefs.getInstance().advanced.offlineMode.getValue()
            val inputType = onMain { editor.inputType }
            val inputPolicy = onMain {
                InputPolicyReadiness(
                    aiAllowed = ime.allowsAiInputFeatures(),
                    networkAllowed = ime.allowsNetworkInputFeatures(),
                    textInspectionAllowed = ime.allowsTextInspectionFeatures()
                )
            }
            reportPreconditions(provider, offlineMode, inputType, inputPolicy)

            assertTrue("An installed AI provider is required for automatic prefetch.", provider.configured)
            assertFalse("Offline mode must be disabled for automatic prefetch.", offlineMode)

            val prefetchStartedAt = SystemClock.elapsedRealtime()
            assertTrue(onMain { ime.commitToEditor(syntheticPrefix) })
            waitForEditorText(editor, syntheticPrefix)

            val inputSessionEpoch = onMain { ime.currentInputSessionEpoch }
            val prefetcher = requireNotNull(ime.contextualPredictor.prefetcher) {
                "Production contextual prefetcher is unavailable."
            }
            val deadline = SystemClock.elapsedRealtime() + AUTOMATIC_PREFETCH_TIMEOUT_MS
            val cachedContinuation = waitForCachedContinuation(
                prefetcher = prefetcher,
                scope = AiSentenceCompletionPrefetcher.Scope(editor.context.packageName, inputSessionEpoch),
                deadline = deadline,
                prefix = syntheticPrefix,
                prefetchStartedAt = prefetchStartedAt
            )
            val continuation = cachedContinuation.continuation
            val visibleCandidate = waitForVisibleCandidateNode(automation, continuation.text, deadline)
            assertTrue("The automatic candidate chip must be visible.", visibleCandidate.isVisibleToUser)
            reportVisibleCandidate(visibleCandidate)

            val snapshot = onMain { ime.getContextualCandidateSnapshot() }
            val snapshotCandidate = requireNotNull(snapshot.sentences.firstOrNull {
                it.metricsCandidate?.source == LLM_CACHED_SOURCE && it.word.text == continuation.text
            }) { "The production sentence snapshot did not retain the visible cached continuation source." }
            val appendSnapshot = requireNotNull(snapshotCandidate.appendSnapshot) {
                "The visible cached continuation did not retain its append contract."
            }
            val expectedInsertion = requireNotNull(appendSnapshot.append.insertionFor(syntheticPrefix)) {
                "The visible cached continuation is not insertable for the synthetic prefix."
            }
            val expectedFullText = syntheticPrefix + expectedInsertion
            reportAutomaticContinuation(
                prefix = syntheticPrefix,
                cacheArrivalElapsedMs = cachedContinuation.elapsedMs,
                suffix = continuation.text,
                expectedFullText = expectedFullText,
                candidateSource = requireNotNull(snapshotCandidate.metricsCandidate).source
            )
            captureCandidateCrop(automation, visibleCandidate)
            assertTrue("The visible automatic candidate chip must accept a click.", visibleCandidate.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForEditorText(editor, expectedFullText)
            assertEquals(expectedFullText, onMain { editor.text.toString() })
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putInt("wordCandidateCount", snapshot.words.size)
                    putInt("sentenceCandidateCount", snapshot.sentences.size)
                    putString("wordCandidateSources", snapshot.words.mapNotNull { it.metricsCandidate?.source }.distinct().joinToString(","))
                    putString("sentenceCandidateSources", snapshot.sentences.mapNotNull { it.metricsCandidate?.source }.distinct().joinToString(","))
                }
            )
        } finally {
            onMain { activity.finish() }
        }
    }

    @Test
    fun missingOAuthSessionShowsReauthenticationHint() {
        val activity = launchActivity()
        val automation = configureUiAutomation()
        try {
            val profile = requireNotNull(AiProviderCredentialStore(instrumentation.targetContext).load()) {
                "An installed OAuth provider is required for this reauthentication scenario."
            }
            assertEquals(AiAuthMode.OAuthPkce, profile.authMode)
            assertFalse(AiOAuthSessionStore(instrumentation.targetContext).hasSession(profile))

            val editor = selectNormalAndClear(activity)
            val ime = waitForCurrentEditor(editorTarget(editor))
            assertTrue(onMain { ime.commitToEditor(SYNTHETIC_PREFIX) })
            waitForEditorText(editor, SYNTHETIC_PREFIX)

            val hint = waitForReauthenticationHint(
                ime = ime,
                automation = automation,
                deadline = SystemClock.elapsedRealtime() + REAUTHENTICATION_HINT_TIMEOUT_MS
            )
            val bounds = Rect()
            hint.getBoundsInScreen(bounds)
            assertTrue("The reauthentication hint must remain visible.", hint.isVisibleToUser)
            assertTrue("The reauthentication hint must have a positive width.", bounds.width() > 0)
            assertTrue(
                "The reauthentication hint must meet the 48dp touch-target height.",
                bounds.height() >= contextDp(48)
            )
            assertEquals(SYNTHETIC_PREFIX, onMain { editor.text.toString() })
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putBoolean("reauthenticationHintVisible", hint.isVisibleToUser)
                    putString("reauthenticationHintBounds", bounds.toShortString())
                }
            )
        } finally {
            onMain { activity.finish() }
        }
    }

    private fun loadProviderReadiness(): ProviderReadiness = try {
        AiProviderCredentialStore(instrumentation.targetContext).load()?.let { profile ->
            ProviderReadiness(
                configured = true,
                kind = profile.kind.name,
                authMode = profile.authMode.name,
                supportsContinuationAbstention = "continuation_abstention" in profile.capabilities,
                supportsResponses = "responses" in profile.capabilities,
                supportsChatCompletions = "chat_completions" in profile.capabilities
            )
        } ?: ProviderReadiness(false, null, null, false, false, false)
    } catch (error: Exception) {
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("providerLoadErrorClass", error.javaClass.name) }
        )
        throw AssertionError("AI provider credential load failed: ${error.javaClass.name}")
    }

    private fun reportPreconditions(
        provider: ProviderReadiness,
        offlineMode: Boolean,
        inputType: Int,
        inputPolicy: InputPolicyReadiness
    ) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putBoolean("providerConfigured", provider.configured)
                putString("providerKind", provider.kind)
                putString("providerAuthMode", provider.authMode)
                putBoolean("supportsContinuationAbstention", provider.supportsContinuationAbstention)
                putBoolean("supportsResponses", provider.supportsResponses)
                putBoolean("supportsChatCompletions", provider.supportsChatCompletions)
                putBoolean("offlineMode", offlineMode)
                putInt("editorInputType", inputType)
                putBoolean("aiAllowed", inputPolicy.aiAllowed)
                putBoolean("networkAllowed", inputPolicy.networkAllowed)
                putBoolean("textInspectionAllowed", inputPolicy.textInspectionAllowed)
            }
        )
    }

    private fun configureUiAutomation(): UiAutomation = instrumentation.uiAutomation.apply {
        val info = requireNotNull(serviceInfo) { "UiAutomation accessibility service info is unavailable." }
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
    }

    private fun waitForCachedContinuation(
        prefetcher: AiSentenceCompletionPrefetcher,
        scope: AiSentenceCompletionPrefetcher.Scope,
        deadline: Long,
        prefix: String,
        prefetchStartedAt: Long
    ): CachedContinuation {
        val diagnostics = CacheWaitDiagnostics()
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                val cached = prefetcher.getCachedPredictions(prefix, scope)
                when {
                    cached == null -> diagnostics.cacheMissingPolls++
                    cached.isEmpty() -> diagnostics.emptyResponsePolls++
                    else -> {
                        val parsed = PrefetchedContinuation.parse(cached, prefix)
                        parsed.firstOrNull { it.kind == PrefetchedContinuation.Kind.CONTINUATION }?.let {
                            return CachedContinuation(
                                continuation = it,
                                elapsedMs = SystemClock.elapsedRealtime() - prefetchStartedAt
                            )
                        }
                        if (parsed.isNotEmpty() && parsed.all { it.kind == PrefetchedContinuation.Kind.WORD }) {
                            diagnostics.wordOnlyPolls++
                        } else if (parsed.any { it.kind == PrefetchedContinuation.Kind.CONTINUATION_ATTACH }) {
                            diagnostics.attachResponsePolls++
                        } else {
                            diagnostics.unusableResponsePolls++
                        }
                    }
                }
                SystemClock.sleep(POLL_INTERVAL_MS)
            }
            throw AssertionError("No cached CONTINUATION appeared for the synthetic trailing-space context within $AUTOMATIC_PREFETCH_TIMEOUT_MS ms.")
        } finally {
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putInt("cacheMissingPolls", diagnostics.cacheMissingPolls)
                    putInt("emptyResponsePolls", diagnostics.emptyResponsePolls)
                    putInt("wordOnlyPolls", diagnostics.wordOnlyPolls)
                    putInt("attachResponsePolls", diagnostics.attachResponsePolls)
                    putInt("unusableResponsePolls", diagnostics.unusableResponsePolls)
                }
            )
        }
    }

    private fun waitForVisibleCandidateNode(
        automation: UiAutomation,
        continuationText: String,
        deadline: Long
    ): AccessibilityNodeInfo {
        while (SystemClock.elapsedRealtime() < deadline) {
            val candidate = automation.windows
                .asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .mapNotNull { it.root }
                .mapNotNull { findVisibleCandidateNode(it, continuationText) }
                .firstOrNull()
            if (candidate != null) return candidate
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("No visible IME candidate matched the cached CONTINUATION within the automatic prefetch deadline.")
    }

    private fun waitForReauthenticationHint(
        ime: FcitxInputMethodService,
        automation: UiAutomation,
        deadline: Long
    ): AccessibilityNodeInfo {
        val expectedText = instrumentation.targetContext.getString(R.string.ai_connection_reauth_required)
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = onMain { ime.contextualSentenceConnectionHintState() }
            if (state == AiPrefetchConnectionState.REAUTH_REQUIRED) {
                val hint = automation.windows
                    .asSequence()
                    .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    .mapNotNull { it.root }
                    .mapNotNull { findVisibleClickableNode(it, expectedText) }
                    .firstOrNull()
                if (hint != null) return hint
            }
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("The missing OAuth session did not show a visible reauthentication hint within $REAUTHENTICATION_HINT_TIMEOUT_MS ms.")
    }

    private fun findVisibleCandidateNode(
        node: AccessibilityNodeInfo,
        continuationText: String
    ): AccessibilityNodeInfo? {
        if (node.text?.toString() == continuationText) {
            findClickableAncestor(node)?.let { candidate ->
                val bounds = Rect()
                candidate.getBoundsInScreen(bounds)
                if (candidate.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0) {
                    return candidate
                }
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                findVisibleCandidateNode(child, continuationText)?.let { return it }
            }
        }
        return null
    }

    private fun findVisibleClickableNode(
        node: AccessibilityNodeInfo,
        expectedText: String
    ): AccessibilityNodeInfo? {
        if (node.text?.toString() == expectedText) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (node.isClickable && node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0) {
                return node
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                findVisibleClickableNode(child, expectedText)?.let { return it }
            }
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun reportVisibleCandidate(candidate: AccessibilityNodeInfo) {
        val bounds = Rect()
        candidate.getBoundsInScreen(bounds)
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putBoolean("automaticCandidateVisible", candidate.isVisibleToUser)
                putString("automaticCandidateBounds", bounds.toShortString())
            }
        )
    }

    private fun reportAutomaticContinuation(
        prefix: String,
        cacheArrivalElapsedMs: Long,
        suffix: String,
        expectedFullText: String,
        candidateSource: String
    ) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("automaticPrefix", prefix)
                putLong("cacheContinuationElapsedMs", cacheArrivalElapsedMs)
                putString("automaticSuffix", suffix)
                putString("automaticExpectedFullText", expectedFullText)
                putString("automaticCandidateSource", candidateSource)
            }
        )
    }

    private fun captureCandidateCrop(automation: UiAutomation, candidate: AccessibilityNodeInfo) {
        val bounds = Rect().also(candidate::getBoundsInScreen)
        val screenshot = automation.takeScreenshot()
        if (screenshot == null) {
            reportCandidateCrop(path = null, error = "UiAutomation.takeScreenshot returned null")
            return
        }
        try {
            val cropBounds = Rect(
                bounds.left.coerceIn(0, screenshot.width),
                bounds.top.coerceIn(0, screenshot.height),
                bounds.right.coerceIn(0, screenshot.width),
                bounds.bottom.coerceIn(0, screenshot.height)
            )
            if (cropBounds.width() <= 0 || cropBounds.height() <= 0) {
                reportCandidateCrop(path = null, error = "candidate bounds are outside screenshot")
                return
            }
            val crop = Bitmap.createBitmap(
                screenshot,
                cropBounds.left,
                cropBounds.top,
                cropBounds.width(),
                cropBounds.height()
            )
            try {
                val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "automatic-continuation-e2e")
                if (!directory.exists() && !directory.mkdirs()) {
                    reportCandidateCrop(path = null, error = "candidate crop directory could not be created")
                    return
                }
                val target = File(directory, "automatic-candidate-${SystemClock.elapsedRealtime()}.png")
                FileOutputStream(target).use { output ->
                    if (!crop.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        reportCandidateCrop(path = null, error = "candidate crop PNG compression failed")
                        return
                    }
                }
                reportCandidateCrop(path = target.absolutePath, error = null)
            } finally {
                crop.recycle()
            }
        } catch (error: Exception) {
            reportCandidateCrop(path = null, error = "candidate crop failed: ${error.javaClass.simpleName}")
        } finally {
            screenshot.recycle()
        }
    }

    private fun reportCandidateCrop(path: String?, error: String?) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("automaticCandidateCropPath", path)
                putString("automaticCandidateCropError", error)
            }
        )
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
        val deadline = SystemClock.elapsedRealtime() + IME_READY_TIMEOUT_MS
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
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError("새글 IME가 normal debug editor에 연결되지 않았다.")
    }

    private fun waitForEditorText(editor: EditText, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + IME_READY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain { editor.text.toString() } == expected) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        assertEquals(expected, onMain { editor.text.toString() })
    }

    private fun contextDp(value: Int): Int =
        (value * instrumentation.targetContext.resources.displayMetrics.density + 0.5f).toInt()

    private fun editorTarget(editor: EditText): EditorTarget = onMain {
        EditorTarget(
            packageName = editor.context.packageName,
            fieldId = editor.id,
            inputType = editor.inputType,
            selectionStart = editor.selectionStart,
            selectionEnd = editor.selectionEnd
        )
    }

    private fun syntheticPrefix(): String {
        val arguments = InstrumentationRegistry.getArguments()
        arguments.getString(SYNTHETIC_PREFIX_ARGUMENT)?.let { prefix ->
            require(prefix.isNotBlank()) { "syntheticPrefix must not be blank." }
            return prefix
        }
        return when (arguments.getString(SYNTHETIC_CASE_ARGUMENT)) {
            null -> SYNTHETIC_PREFIX
            "1" -> SYNTHETIC_CASE_ONE
            "2" -> SYNTHETIC_CASE_TWO
            "3" -> SYNTHETIC_CASE_THREE
            else -> throw AssertionError("syntheticCase must be 1, 2, or 3.")
        }
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
            for (index in 0 until childCount) yield(getChildAt(index))
        }

    private data class ProviderReadiness(
        val configured: Boolean,
        val kind: String?,
        val authMode: String?,
        val supportsContinuationAbstention: Boolean,
        val supportsResponses: Boolean,
        val supportsChatCompletions: Boolean
    )

    private data class EditorTarget(
        val packageName: String,
        val fieldId: Int,
        val inputType: Int,
        val selectionStart: Int,
        val selectionEnd: Int
    )

    private data class InputPolicyReadiness(
        val aiAllowed: Boolean,
        val networkAllowed: Boolean,
        val textInspectionAllowed: Boolean
    )

    private data class CacheWaitDiagnostics(
        var cacheMissingPolls: Int = 0,
        var emptyResponsePolls: Int = 0,
        var wordOnlyPolls: Int = 0,
        var attachResponsePolls: Int = 0,
        var unusableResponsePolls: Int = 0
    )

    private data class CachedContinuation(
        val continuation: PrefetchedContinuation,
        val elapsedMs: Long
    )

    private companion object {
        const val SYNTHETIC_PREFIX = "내일 판교에서 "
        const val SYNTHETIC_PREFIX_ARGUMENT = "syntheticPrefix"
        const val SYNTHETIC_CASE_ARGUMENT = "syntheticCase"
        const val SYNTHETIC_CASE_ONE = "내일 판교에서 "
        const val SYNTHETIC_CASE_TWO = "오늘 저녁에는 "
        const val SYNTHETIC_CASE_THREE = "자료를 검토한 뒤 "
        const val LLM_CACHED_SOURCE = "llm_cached"
        const val IME_READY_TIMEOUT_MS = 5_000L
        const val AUTOMATIC_PREFETCH_TIMEOUT_MS = 60_000L
        const val REAUTHENTICATION_HINT_TIMEOUT_MS = 10_000L
        const val POLL_INTERVAL_MS = 250L
    }
}

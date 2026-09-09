/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.debug.AiEditorTestActivity
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.FcitxInputMethodService.ContextualAppendSnapshot
import org.fcitx.fcitx5.android.input.ai.AiAction
import org.fcitx.fcitx5.android.input.ai.AiBearerTokenProvider
import org.fcitx.fcitx5.android.input.ai.AiHttpStatusException
import org.fcitx.fcitx5.android.input.ai.AiHttpTransport
import org.fcitx.fcitx5.android.input.ai.AiProviderKind
import org.fcitx.fcitx5.android.input.ai.AiProviderProfile
import org.fcitx.fcitx5.android.input.ai.AiSentenceCompletionPrefetcher
import org.fcitx.fcitx5.android.input.ai.ContextualAppend
import org.fcitx.fcitx5.android.input.ai.OpenAiResponsesClient
import org.fcitx.fcitx5.android.input.ai.PrefetchedContinuation
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * 실제 Kanana HTTPS 응답이 앱의 typed continuation과 IME commit 경계를 통과하는지만 검사한다.
 * 후보 chip UI 클릭과 일반 모델 품질 평가는 이 테스트 범위가 아니다.
 */
class KananaLiveContinuationDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun liveKananaContinuationCommitsTypedSuffixToDebugEditor() {
        val activity = launchActivity()
        val liveCandidateClick = liveCandidateClickEnabled()
        var finalCandidateMetrics: PredictionMetricsStore.Summary? = null
        try {
            val editor = selectNormalAndClear(activity)
            var ime = waitForCurrentEditor(editorTarget(editor))
            val client = OpenAiResponsesClient(
                profile = isolatedProfile(),
                transport = PinnedCertificateTransport(requiredCertificateArgument()),
                authorizationProvider = IsolatedKananaBearerTokenProvider
            )

            prefixes.forEachIndexed { index, prefix ->
                if (index > 0) {
                    clearEditor(activity)
                    waitForEditorText(editor, "")
                    ime = waitForCurrentEditor(editorTarget(editor))
                }

                assertTrue(onMain { ime.commitToEditor(prefix) })
                waitForEditorText(editor, prefix)
                val inputSessionEpoch = onMain { ime.currentInputSessionEpoch }

                val startedAt = SystemClock.elapsedRealtime()
                val generation = runBlocking { client.generate(AiAction.ContinueTyping, prefix) }
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt

                assertTrue(
                    "Kanana returned a valid abstention for synthetic prefix '$prefix'; this live continuation test requires a candidate.",
                    generation.suggestions.isNotEmpty()
                )
                assertEquals(
                    "Kanana must return exactly one typed wire suggestion for synthetic prefix '$prefix'.",
                    1,
                    generation.suggestions.size
                )
                val continuations = PrefetchedContinuation.parse(generation.suggestions, prefix)
                    .filter { it.kind != PrefetchedContinuation.Kind.WORD }
                assertEquals(
                    "Kanana must return exactly one typed continuation for synthetic prefix '$prefix'.",
                    1,
                    continuations.size
                )
                val continuation = continuations.single()
                val joinMode = when (continuation.kind) {
                    PrefetchedContinuation.Kind.WORD -> error("WORD is not a continuation")
                    PrefetchedContinuation.Kind.CONTINUATION -> ContextualAppend.JoinMode.NEXT_WORD
                    PrefetchedContinuation.Kind.CONTINUATION_ATTACH -> ContextualAppend.JoinMode.ATTACH
                }
                val append = ContextualAppend(
                    expectedContext = prefix,
                    suffix = continuation.text,
                    joinMode = joinMode
                )
                val insertion = append.insertionFor(prefix)
                assertNotNull("Typed continuation must be insertable for '$prefix'.", insertion)
                val expectedEditorText = prefix + insertion
                assertTrue(expectedEditorText.startsWith(prefix))
                assertBoundary(prefix, insertion, joinMode)

                val finalText = if (liveCandidateClick) {
                    val metricsBefore = FcitxApplication.getInstance().predictionMetricsStore.summary()
                    val candidateAutomation = publishAndPrepareLiveCandidate(
                        ime = ime,
                        editor = editor,
                        prefix = prefix,
                        originalWire = generation.suggestions,
                        inputSessionEpoch = inputSessionEpoch
                    )
                    val candidateNode = waitForVisibleCandidateNode(candidateAutomation, continuation.text)
                    assertEditorVisibleBeforeCapture(activity, editor)
                    captureScreen("before-click-${index + 1}", candidateAutomation)
                    assertCandidateNodeVisible(candidateNode)
                    assertTrue(
                        "Candidate UI click must succeed for '${continuation.text}'.",
                        candidateNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    )
                    waitForEditorText(editor, expectedEditorText)
                    finalCandidateMetrics = waitForCandidateMetrics(metricsBefore)
                    onMain { editor.text.toString() }
                } else {
                    val appendSnapshot = ContextualAppendSnapshot(
                        append = append,
                        inputSessionEpoch = inputSessionEpoch
                    )
                    assertTrue(
                        "IME must commit the validated typed continuation for '$prefix'.",
                        onMain {
                            ime.commitContextualSentence(
                                continuation.text,
                                appendSnapshot = appendSnapshot
                            )
                        }
                    )
                    waitForEditorText(editor, expectedEditorText)
                    onMain { editor.text.toString() }
                }
                assertEquals(expectedEditorText, finalText)
                assertEditorVisibleBeforeCapture(activity, editor)
                captureScreen((index + 1).toString())
                reportLiveResult(prefix, elapsedMs, generation.suggestions, finalText)
            }
            if (liveCandidateClick) {
                waitForPersistedMetrics(requireNotNull(finalCandidateMetrics).totalAccepted)
            }
        } finally {
            onMain { activity.finish() }
        }
    }

    private fun isolatedProfile(): AiProviderProfile = AiProviderProfile(
        kind = AiProviderKind.OpenAICompatible,
        displayName = "Isolated Kanana device E2E",
        baseUrl = "https://127.0.0.1:29443/v1",
        apiKey = "saegeul-isolated-kanana-e2e",
        capabilities = setOf("responses", "continuation_abstention"),
        fastModel = KANANA_MODEL,
        balancedModel = KANANA_MODEL,
        qualityModel = KANANA_MODEL
    )

    private fun requiredCertificateArgument(): String = requireNotNull(
        InstrumentationRegistry.getArguments().getString("liveCertPemBase64")
    ) { "liveCertPemBase64 instrumentation argument is required." }.also {
        require(it.isNotBlank()) { "liveCertPemBase64 instrumentation argument is blank." }
    }

    private fun reportLiveResult(
        prefix: String,
        elapsedMs: Long,
        receivedWire: List<String>,
        finalText: String
    ) {
        val detail = "prefix=$prefix elapsedMs=$elapsedMs receivedWire=$receivedWire finalText=$finalText"
        Log.i(LOG_TAG, detail)
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("prefix", prefix)
                putLong("elapsedMs", elapsedMs)
                putString("receivedWire", receivedWire.joinToString(" | "))
                putString("finalText", finalText)
            }
        )
    }

    private fun captureScreen(name: String, automation: android.app.UiAutomation = instrumentation.uiAutomation) {
        val bitmap = requireNotNull(automation.takeScreenshot()) {
            "Kanana live device E2E screenshot $name is unavailable."
        }
        val screenshot = File(instrumentation.targetContext.cacheDir, "kanana-live-$name.png")
        try {
            screenshot.outputStream().use { output ->
                require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Kanana live device E2E screenshot $name could not be encoded as PNG."
                }
            }
        } finally {
            bitmap.recycle()
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("screenshotPath", screenshot.absolutePath) }
        )
    }

    /**
     * Candidate-click mode still obtains [originalWire] from the real HTTPS response above; this
     * only connects that response to the production cache's public test seam before clicking UI.
     */
    private fun publishAndPrepareLiveCandidate(
        ime: FcitxInputMethodService,
        editor: EditText,
        prefix: String,
        originalWire: List<String>,
        inputSessionEpoch: Long
    ): android.app.UiAutomation {
        val scope = AiSentenceCompletionPrefetcher.Scope(editor.context.packageName, inputSessionEpoch)
        onMain {
            assertEquals(
                "Candidate cache scope must use the active IME input session.",
                inputSessionEpoch,
                ime.currentInputSessionEpoch
            )
            ime.getContextualCandidateSnapshot()
            assertEquals(
                "Candidate callback must run in the same IME input session.",
                inputSessionEpoch,
                ime.currentInputSessionEpoch
            )
            val prefetcher = requireNotNull(ime.contextualPredictor.prefetcher) {
                "Live candidate-click test requires the production contextual prefetcher."
            }
            prefetcher.putPredictions(prefix, originalWire, scope)
            assertEquals(originalWire, prefetcher.getCachedPredictions(prefix, scope))
            requireNotNull(prefetcher.onPrefetchCompleted) {
                "Live candidate-click test requires the production prefetch completion callback."
            }.invoke(
                AiSentenceCompletionPrefetcher.RequestKey(scope, prefetcher.normalizeContextKey(prefix)),
                originalWire
            )
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("candidateClickSource", "live_https_prefetch_cache_seam") }
        )
        return instrumentation.uiAutomation.apply {
            val serviceInfo = requireNotNull(serviceInfo) {
                "UiAutomation accessibility service info is unavailable."
            }
            serviceInfo.flags = serviceInfo.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            this.serviceInfo = serviceInfo
        }
    }

    private fun waitForVisibleCandidateNode(
        automation: android.app.UiAutomation,
        continuationText: String
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + CANDIDATE_UI_TIMEOUT_MS
        var syntheticTreeText = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            val candidate = automation.windows
                .asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .mapNotNull { it.root }
                .mapNotNull { findVisibleClickableCandidate(it, continuationText) }
                .firstOrNull()
            if (candidate != null) return candidate
            if (isSyntheticEmulator()) {
                syntheticTreeText = candidateTreeText(automation)
            }
            SystemClock.sleep(100L)
        }
        if (isSyntheticEmulator()) {
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString("candidateTreeText", syntheticTreeText) }
            )
        }
        throw AssertionError("No visible clickable IME candidate matched '$continuationText' within $CANDIDATE_UI_TIMEOUT_MS ms.")
    }

    private fun findVisibleClickableCandidate(
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
            val candidate = node.getChild(index)?.let { child ->
                findVisibleClickableCandidate(child, continuationText)
            }
            if (candidate != null) return candidate
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

    private fun assertCandidateNodeVisible(candidate: AccessibilityNodeInfo) {
        val bounds = Rect()
        candidate.getBoundsInScreen(bounds)
        assertTrue("Candidate UI node must be visible before click: $bounds", candidate.isVisibleToUser)
        assertTrue("Candidate UI node must have positive visible width: $bounds", bounds.width() > 0)
        assertTrue("Candidate UI node must have positive visible height: $bounds", bounds.height() > 0)
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("candidateClickBounds", bounds.toShortString()) }
        )
    }

    private fun candidateTreeText(automation: android.app.UiAutomation): String = automation.windows
        .asSequence()
        .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        .mapNotNull { it.root }
        .flatMap { node -> nodeTexts(node) }
        .joinToString(" | ")

    private fun nodeTexts(node: AccessibilityNodeInfo): Sequence<String> = sequence {
        node.text?.toString()?.takeIf { it.isNotEmpty() }?.let { yield(it) }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> yieldAll(nodeTexts(child)) }
        }
    }

    private fun isSyntheticEmulator(): Boolean = Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.contains("emulator", ignoreCase = true)

    private fun waitForCandidateMetrics(
        before: PredictionMetricsStore.Summary
    ): PredictionMetricsStore.Summary {
        val deadline = SystemClock.elapsedRealtime() + CANDIDATE_UI_TIMEOUT_MS
        var latest = FcitxApplication.getInstance().predictionMetricsStore.summary()
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = FcitxApplication.getInstance().predictionMetricsStore.summary()
            if (
                latest.totalAccepted == before.totalAccepted + 1 &&
                latest.totalShown > before.totalShown
            ) {
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putInt("candidateMetricsBeforeAccepted", before.totalAccepted)
                        putInt("candidateMetricsBeforeShown", before.totalShown)
                        putInt("candidateMetricsAfterAccepted", latest.totalAccepted)
                        putInt("candidateMetricsAfterShown", latest.totalShown)
                    }
                )
                return latest
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError(
            "Candidate metrics did not record one acceptance and an added impression within " +
                "$CANDIDATE_UI_TIMEOUT_MS ms. before=$before latest=$latest"
        )
    }

    private fun waitForPersistedMetrics(expectedAccepted: Int) {
        val app = FcitxApplication.getInstance()
        val deadline = SystemClock.elapsedRealtime() + METRICS_PERSIST_TIMEOUT_MS
        var latestMemory = app.predictionMetricsStore.summary()
        var latestPersisted = PredictionMetricsStore(
            storeFile = File(instrumentation.targetContext.filesDir, "prediction_metrics.json"),
            cipher = app.vaultCipher
        ).summary()
        while (SystemClock.elapsedRealtime() < deadline) {
            latestMemory = app.predictionMetricsStore.summary()
            latestPersisted = PredictionMetricsStore(
                storeFile = File(instrumentation.targetContext.filesDir, "prediction_metrics.json"),
                cipher = app.vaultCipher
            ).summary()
            if (
                latestMemory.totalAccepted == expectedAccepted &&
                latestPersisted.totalAccepted == latestMemory.totalAccepted &&
                latestPersisted.totalShown == latestMemory.totalShown
            ) {
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putInt("memoryTotalAccepted", latestMemory.totalAccepted)
                        putInt("memoryTotalShown", latestMemory.totalShown)
                        putInt("persistedTotalAccepted", latestPersisted.totalAccepted)
                        putInt("persistedTotalShown", latestPersisted.totalShown)
                    }
                )
                return
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError(
            "Persisted prediction metrics did not match the in-memory summary within " +
                "$METRICS_PERSIST_TIMEOUT_MS ms. expectedAccepted=$expectedAccepted " +
                "memory=$latestMemory persisted=$latestPersisted"
        )
    }

    private fun liveCandidateClickEnabled(): Boolean =
        InstrumentationRegistry.getArguments().getString("liveCandidateClick") == "true"

    private fun assertEditorVisibleBeforeCapture(activity: AiEditorTestActivity, editor: EditText) {
        val geometry = onMain {
            val editorRect = Rect()
            val windowRect = Rect()
            val hasVisibleEditorRect = editor.getGlobalVisibleRect(editorRect)
            activity.window.decorView.getWindowVisibleDisplayFrame(windowRect)
            EditorVisibility(
                hasVisibleEditorRect = hasVisibleEditorRect,
                editorRect = editorRect,
                windowRect = windowRect,
                minimumHeight = editor.lineHeight + editor.paddingTop + editor.paddingBottom
            )
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("editorVisibleRect", geometry.editorRect.toShortString())
                putString("windowVisibleRect", geometry.windowRect.toShortString())
                putInt("editorMinimumVisibleHeight", geometry.minimumHeight)
            }
        )
        assertTrue("Debug editor must have a global visible rect before capture.", geometry.hasVisibleEditorRect)
        assertTrue("Debug editor visible width must be positive: ${geometry.editorRect}", geometry.editorRect.width() > 0)
        assertTrue(
            "Debug editor visible height must fit one line and padding: $geometry",
            geometry.editorRect.height() >= geometry.minimumHeight
        )
        assertTrue("Window visible rect must be positive: $geometry", geometry.windowRect.width() > 0)
        assertTrue("Window visible rect must be positive: $geometry", geometry.windowRect.height() > 0)
        assertTrue("Debug editor must be inside the visible window: $geometry", geometry.windowRect.contains(geometry.editorRect))
    }

    private fun assertBoundary(
        prefix: String,
        insertion: String?,
        joinMode: ContextualAppend.JoinMode
    ) {
        val actualInsertion = requireNotNull(insertion)
        when (joinMode) {
            ContextualAppend.JoinMode.NEXT_WORD -> {
                assertTrue(actualInsertion.endsWith(" "))
                if (prefix.lastOrNull()?.isWhitespace() == true) {
                    assertFalse(actualInsertion.startsWith(" "))
                } else {
                    assertTrue(actualInsertion.startsWith(" "))
                }
            }
            ContextualAppend.JoinMode.ATTACH -> {
                assertFalse(prefix.lastOrNull()?.isWhitespace() == true)
                assertFalse(actualInsertion.startsWith(" "))
                assertTrue(actualInsertion.endsWith(" "))
            }
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

    private fun clearEditor(activity: AiEditorTestActivity) = onMain {
        requireNotNull(activity.window.decorView.findByContentDescription("Clear text")).performClick()
    }

    private fun waitForCurrentEditor(target: EditorTarget): FcitxInputMethodService {
        val deadline = SystemClock.elapsedRealtime() + IME_READY_TIMEOUT_MS
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
            ime = ime,
            activeInstancePresent = ime != null,
            matches = matches,
            packageName = info?.packageName,
            fieldId = info?.fieldId,
            inputType = info?.inputType,
            selectionStart = selection?.start,
            selectionEnd = selection?.end,
            epoch = epoch
        )
    }

    private fun waitForEditorText(editor: EditText, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + EDITOR_TEXT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain { editor.text.toString() } == expected) return
            SystemClock.sleep(50L)
        }
        assertEquals(expected, onMain { editor.text.toString() })
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
            for (index in 0 until childCount) yield(getChildAt(index))
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

    private data class EditorVisibility(
        val hasVisibleEditorRect: Boolean,
        val editorRect: Rect,
        val windowRect: Rect,
        val minimumHeight: Int
    )

    private class PinnedCertificateTransport(certificatePemBase64: String) : AiHttpTransport {
        private val socketFactory = socketFactoryFor(certificatePemBase64)

        override fun post(url: String, authorization: String, body: String): String {
            val connection = URI(url).toURL().openConnection() as HttpsURLConnection
            try {
                connection.sslSocketFactory = socketFactory
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setRequestProperty("Authorization", authorization)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                val requestBytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(requestBytes.size)
                connection.outputStream.use { it.write(requestBytes) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseBytes = stream?.use { readBounded(it, connection.contentLengthLong) }
                    ?: ByteArray(0)
                if (status !in 200..299) {
                    throw AiHttpStatusException(status, "Isolated Kanana HTTP $status")
                }
                require(responseBytes.isNotEmpty()) { "Isolated Kanana returned no response body." }
                return responseBytes.toString(Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }

        private fun socketFactoryFor(certificatePemBase64: String) = CertificateFactory
            .getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.decode(certificatePemBase64, Base64.DEFAULT)))
            .let { certificate ->
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null)
                    setCertificateEntry("isolated-kanana", certificate)
                }
                val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(keyStore) }
                SSLContext.getInstance("TLS").apply {
                    init(null, trustManagers.trustManagers, null)
                }.socketFactory
            }

        private fun readBounded(input: InputStream, contentLength: Long): ByteArray {
            require(contentLength <= MAX_RESPONSE_BYTES || contentLength < 0L) {
                "Isolated Kanana response exceeds $MAX_RESPONSE_BYTES bytes."
            }
            return ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_RESPONSE_BYTES) {
                        "Isolated Kanana response exceeds $MAX_RESPONSE_BYTES bytes."
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private object IsolatedKananaBearerTokenProvider : AiBearerTokenProvider {
        override suspend fun authorizationHeader(profile: AiProviderProfile): String = ISOLATED_AUTHORIZATION
    }

    private companion object {
        const val LOG_TAG = "KananaLiveE2E"
        const val KANANA_MODEL = "kakaocorp/kanana-1.5-2.1b-instruct-2505"
        const val ISOLATED_AUTHORIZATION = "Bearer saegeul-isolated-kanana-e2e"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 90_000
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val IME_READY_TIMEOUT_MS = 30_000L
        const val EDITOR_TEXT_TIMEOUT_MS = 5_000L
        const val CANDIDATE_UI_TIMEOUT_MS = 10_000L
        const val METRICS_PERSIST_TIMEOUT_MS = 15_000L
        val prefixes = listOf("내가 뭘 ", "오후 2시 회의", "늦어서 미안")
    }
}

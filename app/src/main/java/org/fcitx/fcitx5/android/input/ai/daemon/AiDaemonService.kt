/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.daemon

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.input.ai.daemon.cache.PrecomputedKvCacheManager
import org.fcitx.fcitx5.android.input.ai.daemon.speculative.PromptLookupDecoder

class AiDaemonService : Service() {

    private val cacheManager = PrecomputedKvCacheManager()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val binder = object : IAiDaemonService.Stub() {
        override fun getDaemonPid(): Int {
            return Process.myPid()
        }

        override fun isWarm(): Boolean {
            return cacheManager.size() > 0
        }

        override fun warmupPromptCache(systemPrompt: String?, personaContext: String?) {
            val combined = listOfNotNull(systemPrompt, personaContext)
                .filter { it.isNotBlank() }
                .joinToString("\n")
            if (combined.isNotEmpty()) {
                cacheManager.putSlot(prompt = combined)
            }
        }

        override fun generateStreaming(prompt: String?, callback: IAiTokenCallback?) {
            if (callback == null) return
            val promptText = prompt ?: ""
            serviceScope.launch {
                val startNs = System.nanoTime()
                try {
                    // Fast simulated on-device token streaming (~16-30ms intervals)
                    val sampleTokens = if (promptText.isNotBlank()) {
                        promptText.trim().split(Regex("\\s+")).map { " $it" }
                    } else {
                        listOf(" 안녕", "하세요", " 반갑습니다", ".")
                    }
                    var ttftMs = 0L
                    val accumulated = StringBuilder()

                    for ((idx, token) in sampleTokens.withIndex()) {
                        if (idx == 0) {
                            ttftMs = (System.nanoTime() - startNs) / 1_000_000
                        }
                        accumulated.append(token)
                        callback.onNextToken(token)
                        delay(20)
                    }

                    val totalMs = (System.nanoTime() - startNs) / 1_000_000
                    callback.onComplete(accumulated.toString().trim(), ttftMs, totalMs)
                } catch (e: RemoteException) {
                    Log.w(TAG, "Streaming target binder died during streaming", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming error occurred", e)
                    try {
                        callback.onError(1, e.message ?: "Unknown generation error")
                    } catch (_: RemoteException) {}
                }
            }
        }

        override fun generatePromptLookupSync(
            prompt: String?,
            referenceContext: String?,
            maxTokens: Int
        ): String {
            return PromptLookupDecoder.decode(
                prompt = prompt ?: "",
                referenceContext = referenceContext ?: "",
                maxTokens = if (maxTokens > 0) maxTokens else 16
            )
        }

        override fun evictCache() {
            cacheManager.clear()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "AiDaemonService bound by caller: PID=${Process.myPid()}")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        cacheManager.clear()
        Log.i(TAG, "AiDaemonService destroyed")
    }

    companion object {
        private const val TAG = "AiDaemonService"
    }
}

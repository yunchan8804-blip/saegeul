/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection

data class DiscoveredAiProviderService(
    val computerName: String,
    val manifestUrl: String
)

interface AiProviderDiscoveryListener {
    fun onDiscoveryStarted()
    fun onServiceFound(service: DiscoveredAiProviderService)
    fun onServiceRejected(computerName: String)
    fun onDiscoveryFailed(errorCode: Int)
}

/**
 * Finds desktop companions on the current local network through DNS-SD/mDNS.
 *
 * mDNS data is never trusted as provider configuration. It may only point at the fixed well-known
 * path on an HTTPS origin; [AiProviderManifestClient] then downloads and validates the manifest.
 */
@Suppress("DEPRECATION")
class AiProviderDiscoveryManager(
    context: Context,
    private val listener: AiProviderDiscoveryListener
) {
    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val pendingResolution = ArrayDeque<NsdServiceInfo>()
    private val queuedNames = mutableSetOf<String>()
    private var resolving = false
    @Volatile
    private var generation = 0

    fun start() {
        stop()
        val runId = ++generation
        val callback = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (runId == generation) listener.onDiscoveryStarted()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (runId != generation) return
                if (!serviceInfo.serviceType.equals(SERVICE_TYPE, ignoreCase = true)) return
                synchronized(pendingResolution) {
                    if (!queuedNames.add(serviceInfo.serviceName)) return
                    pendingResolution.addLast(serviceInfo)
                }
                resolveNext(runId)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (runId != generation) return
                listener.onDiscoveryFailed(errorCode)
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = callback
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, callback)
        } catch (_: RuntimeException) {
            discoveryListener = null
            if (runId == generation) listener.onDiscoveryFailed(ERROR_START_EXCEPTION)
        }
    }

    fun stop() {
        generation++
        val callback = discoveryListener
        discoveryListener = null
        if (callback != null) runCatching { nsdManager.stopServiceDiscovery(callback) }
        synchronized(pendingResolution) {
            pendingResolution.clear()
            queuedNames.clear()
            resolving = false
        }
    }

    private fun resolveNext(runId: Int) {
        if (runId != generation) return
        val service = synchronized(pendingResolution) {
            if (resolving || pendingResolution.isEmpty()) return
            resolving = true
            pendingResolution.removeFirst()
        }
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (runId != generation) return
                listener.onServiceRejected(serviceInfo.serviceName)
                resolutionFinished(runId)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (runId != generation) return
                val manifestUrl = serviceInfo.attributes[TXT_MANIFEST_URL]
                    ?.toString(Charsets.UTF_8)
                    ?.trim()
                val valid = manifestUrl?.takeIf(::isAllowedManifestUrl)
                if (valid == null) {
                    listener.onServiceRejected(serviceInfo.serviceName)
                } else {
                    listener.onServiceFound(
                        DiscoveredAiProviderService(
                            computerName = serviceInfo.serviceName.take(MAX_COMPUTER_NAME_LENGTH),
                            manifestUrl = valid
                        )
                    )
                }
                resolutionFinished(runId)
            }
        }
        try {
            nsdManager.resolveService(service, callback)
        } catch (_: RuntimeException) {
            if (runId == generation) {
                listener.onServiceRejected(service.serviceName)
                resolutionFinished(runId)
            }
        }
    }

    private fun resolutionFinished(runId: Int) {
        if (runId != generation) return
        synchronized(pendingResolution) { resolving = false }
        resolveNext(runId)
    }

    companion object {
        const val SERVICE_TYPE = "_fcitx-ai._tcp."
        const val TXT_MANIFEST_URL = "manifest"
        const val ERROR_START_EXCEPTION = -1
        private const val MAX_COMPUTER_NAME_LENGTH = 80

        fun isAllowedManifestUrl(value: String): Boolean = runCatching {
            AiEndpointPolicy.requireHttps(value, "Discovery manifest URL")
            URI(value).path == AiProviderDiscoveryManifestCodec.WELL_KNOWN_PATH
        }.getOrDefault(false)

        /** Accepts either an origin or the exact well-known URL for the manual fallback. */
        fun normalizeManifestUrl(value: String): String? = runCatching {
            val parsed = URI(value.trim())
            val normalized = if (parsed.path.isNullOrEmpty() || parsed.path == "/") {
                URI(
                    parsed.scheme,
                    parsed.userInfo,
                    parsed.host,
                    parsed.port,
                    AiProviderDiscoveryManifestCodec.WELL_KNOWN_PATH,
                    parsed.query,
                    parsed.fragment
                ).toString()
            } else {
                parsed.toString()
            }
            normalized.takeIf(::isAllowedManifestUrl)
        }.getOrNull()
    }
}

class AiProviderManifestClient {
    suspend fun fetch(manifestUrl: String): VerifiedAiProviderManifest =
        withContext(Dispatchers.IO) {
            require(AiProviderDiscoveryManager.isAllowedManifestUrl(manifestUrl)) {
                "Invalid discovery manifest URL"
            }
            val connection = URI(manifestUrl).toURL().openConnection() as HttpsURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                require(connection.responseCode == HttpsURLConnection.HTTP_OK) {
                    "Computer returned HTTP ${connection.responseCode}"
                }
                val declaredLength = connection.contentLength
                require(
                    declaredLength < 0 ||
                        declaredLength <= AiProviderDiscoveryManifestCodec.MAX_MANIFEST_BYTES
                ) { "Discovery manifest is too large" }
                val payload = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= AiProviderDiscoveryManifestCodec.MAX_MANIFEST_BYTES) {
                            "Discovery manifest is too large"
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                try {
                    AiProviderDiscoveryManifestCodec.decode(
                        payload.toString(Charsets.UTF_8),
                        manifestUrl
                    )
                } finally {
                    payload.fill(0)
                }
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 7_000
        const val USER_AGENT = "Fcitx5-Android-AI-Setup/1"
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

/**
 * Pure decision policy for the automatic suggestion backend's GPU-to-CPU fallback. Decides
 * whether a warm-up failure should trigger a one-time switch from the GPU delegate to CPU,
 * given the failing error code, the backend currently in use, and whether this service instance
 * has already spent its one fallback attempt.
 */
object OnDeviceBackendFallbackPolicy {

    /** The backend error code that indicates the GPU delegate itself failed to initialize. */
    const val ENGINE_INITIALIZATION_FAILED = "ENGINE_INITIALIZATION_FAILED"

    /**
     * Returns whether a warm-up failure with [errorCode] should trigger a GPU-to-CPU fallback.
     *
     * @param errorCode the backend exception's error code.
     * @param useGpu whether the backend that just failed was using the GPU delegate.
     * @param fallbackAlreadyUsed whether this service instance has already fallen back once.
     */
    fun shouldFallbackToCpu(
        errorCode: String?,
        useGpu: Boolean,
        fallbackAlreadyUsed: Boolean
    ): Boolean = useGpu && !fallbackAlreadyUsed && errorCode == ENGINE_INITIALIZATION_FAILED
}

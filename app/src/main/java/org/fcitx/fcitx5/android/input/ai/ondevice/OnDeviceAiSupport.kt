/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import android.os.Build

/**
 * Whether this device can run the on-device Gemma (LiteRT-LM) engine. The `litertlm-android` AAR
 * only ships native libraries for `arm64-v8a` and `x86_64`; on any other primary or secondary ABI
 * the JNI library fails to load, so every Gemma-backed feature (automatic suggestions, context
 * completion, material accumulation, graph enrichment) must stay hidden and its schedulers must
 * stay off. This is a release build's replacement for the old `BuildConfig.DEBUG` gate: the
 * feature is now shipped in every build variant, and is enabled per-device instead of per-variant.
 */
object OnDeviceAiSupport {
    val isSupported: Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
}

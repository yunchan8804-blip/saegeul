/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.rag

/**
 * Why the on-device graph-enrichment worker stopped mid-cycle without reaching a terminal result
 * (success/no_data/parse_failed). Persisted in [PersonalGraphEnrichmentStagingStore.State] so the
 * dashboard can tell the user why in plain language instead of a generic "interrupted" message, and
 * so [PersonalGraphEnrichmentCycle]/the worker do not need Android framework types (this lives in
 * the main source set; the debug worker maps its own `GemmaGenerationWaitReason` onto this).
 *
 * Every controlled pause path sets a concrete reason before the worker returns/dies gracefully, so
 * [NONE] surviving to the point the dashboard reads it means the pause was never recorded at all -
 * in practice, only the process being killed outright mid-cycle. It is shown as "the app was closed"
 * rather than a generic "unknown reason", since after this reason set was made exhaustive that is
 * the only way it is actually reached.
 */
enum class GraphEnrichmentPauseReason {
    NONE,
    BATTERY_LEVEL_UNKNOWN,
    BATTERY_LOW,
    POWER_SAVE,
    THERMAL,
    LOW_MEMORY,
    KEYBOARD_ACTIVE,
    SCREEN_ON,
    NOT_CHARGING,
    USER_STOPPED,
    LEASE_WAIT_TIMEOUT
}

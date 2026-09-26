/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.ui.main.ai

import android.content.Context

data class GemmaPreparationSnapshot(
    val automaticEnabled: Boolean,
    val manualRequested: Boolean,
    val modelReady: Boolean,
    val running: Boolean,
    val queued: Boolean,
    val stored: Int,
    val added: Int,
    val lastRunEpochMs: Long,
    val status: String,
    val error: String?
)

interface GemmaPreparationController {
    suspend fun snapshot(): GemmaPreparationSnapshot

    suspend fun setAutomaticEnabled(enabled: Boolean)

    suspend fun requestManual()

    /**
     * Schedules a single, immediate on-device personal-graph enrichment run. Returns true when a
     * new run was actually scheduled, false when one is already pending/running or the on-device
     * model is not ready (in which case the dashboard's enrichment status already explains why).
     */
    suspend fun requestGraphEnrichment(): Boolean

    /** Cancels a running/pending manual graph-enrichment run, leaving its progress in place so it can be resumed. */
    suspend fun stopManualGraphEnrichment()

    /**
     * Lets the graph win a lease race against material generation while the graph run is waiting
     * for its turn: cancels material generation's active on-device generation attempt so the graph
     * run's next lease attempt succeeds. Does not disable material generation's own automatic
     * setting.
     */
    suspend fun prioritizeGraphOverMaterial()

    fun openModelManagement(context: Context)

    fun isModelReady(context: Context): Boolean
}

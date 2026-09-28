/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAutomaticSuggestionWarmupState
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceSuggestionCoordinator

/** What the automatic (on-device) suggestion status row of the candidate bar is showing. */
internal enum class StatusRowLogicalState {
    WARMUP, GENERATING, NO_CANDIDATE, ERROR, WARMUP_FAILED, COLLECTION_FEEDBACK
}

/**
 * Pure decisions behind [HorizontalCandidateComponent]'s status row, free of Android/service
 * state so they can be unit tested on the JVM. The component owns the bookkeeping around them
 * (last observed coordinator state, the NO_CANDIDATE auto-hide timer) and the displayed text.
 */
internal object StatusRowPolicy {

    private const val INVALID_INPUT_ERROR_CODE = "INVALID_INPUT"

    /** Whether the coordinator is waiting out its debounce or generating a suggestion. */
    fun isGenerating(state: OnDeviceSuggestionCoordinator.State): Boolean =
        state == OnDeviceSuggestionCoordinator.State.DEBOUNCING ||
            state == OnDeviceSuggestionCoordinator.State.GENERATING

    /**
     * The state the current readings ask for, highest priority first: engine warmup, generation,
     * an empty result, a real error (rejected input is not one), a failed warmup, then collection
     * feedback. Null when there is nothing to report.
     */
    fun requestedState(
        warmupState: OnDeviceAutomaticSuggestionWarmupState,
        status: OnDeviceSuggestionCoordinator.Status,
        warmupFailed: Boolean,
        collectionFeedbackActive: Boolean
    ): StatusRowLogicalState? = when {
        warmupState == OnDeviceAutomaticSuggestionWarmupState.Preparing -> StatusRowLogicalState.WARMUP
        isGenerating(status.state) -> StatusRowLogicalState.GENERATING
        status.state == OnDeviceSuggestionCoordinator.State.NO_CANDIDATE -> StatusRowLogicalState.NO_CANDIDATE
        status.state == OnDeviceSuggestionCoordinator.State.ERROR &&
            status.errorCode != INVALID_INPUT_ERROR_CODE -> StatusRowLogicalState.ERROR
        warmupFailed -> StatusRowLogicalState.WARMUP_FAILED
        collectionFeedbackActive -> StatusRowLogicalState.COLLECTION_FEEDBACK
        else -> null
    }

    /**
     * NO_CANDIDATE also fires for reasons unrelated to "generated, but got nothing" (input
     * rejected, session invalidated, a candidate just applied). Only enter the NO_CANDIDATE
     * display on the specific edge where the coordinator was actually GENERATING right before;
     * once entered, [current] keeps it up regardless of later reads. Every other requested state
     * passes through unchanged.
     */
    fun nextState(
        requested: StatusRowLogicalState?,
        current: StatusRowLogicalState?,
        previousCoordinatorState: OnDeviceSuggestionCoordinator.State?
    ): StatusRowLogicalState? =
        if (requested == StatusRowLogicalState.NO_CANDIDATE &&
            current != StatusRowLogicalState.NO_CANDIDATE &&
            previousCoordinatorState != OnDeviceSuggestionCoordinator.State.GENERATING
        ) {
            null
        } else {
            requested
        }
}

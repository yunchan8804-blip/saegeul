/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.core.CapabilityFlags

/**
 * Remembers the current Android editor long enough to rebuild Fcitx's native InputContext after
 * its engine is restarted in place.
 *
 * Android does not call the IME bind/start callbacks for a daemon-only restart. The gate therefore
 * accepts a request only for a new engine generation, and hands out at most one current editor plan
 * after that exact generation reaches READY. Editor lifecycle changes replace or clear the pending
 * snapshot, so a delayed recovery never revives a finished field.
 */
internal class EngineRestartEditorRehydrationGate {

    data class Plan(
        val engineGeneration: Long,
        val uid: Int,
        val packageName: String,
        val inputSessionEpoch: Long,
        val editorPackageName: String,
        val fieldId: Int,
        val inputType: Int,
        val capabilityFlags: CapabilityFlags,
        val shouldFocus: Boolean,
        val isVirtualKeyboard: Boolean,
        val inputMethodUniqueName: String?,
        internal val revision: Long
    )

    private data class Binding(val uid: Int, val packageName: String)

    private data class Editor(
        val inputSessionEpoch: Long,
        val packageName: String,
        val fieldId: Int,
        val inputType: Int,
        val capabilityFlags: CapabilityFlags,
        val shouldFocus: Boolean,
        val isVirtualKeyboard: Boolean,
        val inputMethodUniqueName: String?
    )

    private var binding: Binding? = null
    private var editor: Editor? = null
    private var revision = 0L
    private var pendingGeneration: Long? = null
    private var lastHandledGeneration = Long.MIN_VALUE
    private var retriedGeneration = Long.MIN_VALUE

    @Synchronized
    fun onBindInput(uid: Int, packageName: String) {
        binding = Binding(uid, packageName)
        // A new binding must not inherit an EditorInfo from its predecessor.
        editor = null
        revision += 1
    }

    @Synchronized
    fun onStartInput(
        inputSessionEpoch: Long,
        editorPackageName: String,
        fieldId: Int,
        inputType: Int,
        capabilityFlags: CapabilityFlags,
        shouldFocus: Boolean,
        isVirtualKeyboard: Boolean,
        inputMethodUniqueName: String? = null
    ) {
        editor = Editor(
            inputSessionEpoch,
            editorPackageName,
            fieldId,
            inputType,
            capabilityFlags,
            shouldFocus,
            isVirtualKeyboard,
            inputMethodUniqueName
        )
        revision += 1
    }

    /** Browser-like editors may replace EditorInfo between start-input and start-input-view. */
    @Synchronized
    fun onStartInputView(
        inputSessionEpoch: Long,
        editorPackageName: String,
        fieldId: Int,
        inputType: Int,
        capabilityFlags: CapabilityFlags,
        shouldFocus: Boolean
    ) {
        val editor = editor ?: return
        this.editor = editor.copy(
            inputSessionEpoch = inputSessionEpoch,
            packageName = editorPackageName,
            fieldId = fieldId,
            inputType = inputType,
            capabilityFlags = capabilityFlags,
            shouldFocus = editor.shouldFocus && shouldFocus
        )
        revision += 1
    }

    /** Keeps the selected IM snapshot current while Android stays in the same editor session. */
    @Synchronized
    fun onInputMethodChanged(uniqueName: String) {
        val editor = editor ?: return
        if (editor.inputMethodUniqueName == uniqueName) return
        this.editor = editor.copy(inputMethodUniqueName = uniqueName)
        revision += 1
    }

    @Synchronized
    fun onFinishInput() {
        editor = null
        revision += 1
    }

    @Synchronized
    fun onUnbindInput() {
        binding = null
        editor = null
        revision += 1
    }

    /** Requests exactly one restore for a newly completed native engine generation. */
    @Synchronized
    fun requestForEngineGeneration(generation: Long) {
        if (generation <= lastHandledGeneration || pendingGeneration == generation) return
        pendingGeneration = generation
    }

    /**
     * Claims a restore plan only at the requested READY generation. Repeated READY/collector
     * subscription notifications therefore cannot recreate the same native InputContext twice.
     */
    @Synchronized
    fun claimReady(generation: Long): Plan? {
        if (pendingGeneration != generation) return null
        pendingGeneration = null
        lastHandledGeneration = generation
        return currentPlan(generation)
    }

    /** Allows one same-generation retry only when the original Android editor is still current. */
    @Synchronized
    fun retryAfterFailedRestore(plan: Plan): Boolean {
        if (plan.engineGeneration != lastHandledGeneration || retriedGeneration == plan.engineGeneration ||
            binding == null || editor == null
        ) return false
        retriedGeneration = plan.engineGeneration
        pendingGeneration = plan.engineGeneration
        return true
    }

    /**
     * Resolves a claimed generation to the newest current editor just before its native restore.
     * `onStartInputView` can refine EditorInfo without a second Android bind/start callback.
     */
    @Synchronized
    fun refreshClaimedPlan(plan: Plan): Plan? {
        if (plan.engineGeneration != lastHandledGeneration) return null
        return currentPlan(plan.engineGeneration)
    }

    @Synchronized
    private fun currentPlan(generation: Long): Plan? {
        val binding = binding ?: return null
        val editor = editor ?: return null
        return Plan(
            engineGeneration = generation,
            uid = binding.uid,
            packageName = binding.packageName,
            inputSessionEpoch = editor.inputSessionEpoch,
            editorPackageName = editor.packageName,
            fieldId = editor.fieldId,
            inputType = editor.inputType,
            capabilityFlags = editor.capabilityFlags,
            shouldFocus = editor.shouldFocus,
            isVirtualKeyboard = editor.isVirtualKeyboard,
            inputMethodUniqueName = editor.inputMethodUniqueName,
            revision = revision
        )
    }

    /** A queued native operation must stop if Android has moved to another input session. */
    @Synchronized
    fun isCurrent(plan: Plan): Boolean =
        plan.revision == revision && binding?.let { it.uid == plan.uid && it.packageName == plan.packageName } == true &&
            editor?.let {
                it.inputSessionEpoch == plan.inputSessionEpoch &&
                    it.packageName == plan.editorPackageName &&
                    it.fieldId == plan.fieldId &&
                    it.inputType == plan.inputType
            } == true
}

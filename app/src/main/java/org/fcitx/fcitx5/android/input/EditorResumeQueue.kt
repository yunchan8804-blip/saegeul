/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

/** An editor field that a pending request was started from. */
interface EditorResumeTarget {
    val packageName: String
    val fieldId: Int
    val inputType: Int
}

/**
 * One-shot, process-memory handoff across an Activity boundary that hides and restarts the IME.
 *
 * The result is kept with the identity of the editor that started the request and handed out once,
 * only when that same editor becomes active again. A newer [begin] replaces an older request, so a
 * stale [complete] cannot overwrite it.
 */
internal open class EditorResumeQueue<T : EditorResumeTarget, V, R>(
    private val resultOf: (T, V) -> R
) {
    private data class Pending<T>(val id: Long, val target: T)

    private data class Completed<T, R>(val target: T, val result: R)

    private var pending: Pending<T>? = null
    private var completed: Completed<T, R>? = null

    @Synchronized
    fun begin(id: Long, target: T) {
        pending = Pending(id, target)
        completed = null
    }

    @Synchronized
    fun complete(id: Long, value: V) {
        val request = pending?.takeIf { it.id == id } ?: return
        pending = null
        completed = Completed(request.target, resultOf(request.target, value))
    }

    @Synchronized
    fun cancel(id: Long) {
        if (pending?.id == id) pending = null
    }

    @Synchronized
    fun consumeForEditor(
        packageName: String?,
        fieldId: Int,
        inputType: Int
    ): R? {
        val done = completed ?: return null
        completed = null
        return done.result.takeIf {
            packageName == done.target.packageName &&
                fieldId == done.target.fieldId &&
                inputType == done.target.inputType
        }
    }
}

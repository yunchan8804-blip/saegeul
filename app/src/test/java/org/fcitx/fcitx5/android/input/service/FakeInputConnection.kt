/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.service

import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy

/**
 * An [InputConnection] that serves [before] and [after] as the text around the cursor and writes
 * every call that reaches it to [log] as `name(arguments)`. Calls it has no data for return the
 * type's zero value, so a method that is not meant to run shows up in the log instead of throwing.
 */
internal fun fakeInputConnection(
    log: MutableList<String>,
    before: String = "",
    after: String = "",
    deleteSucceeds: Boolean = true
): InputConnection = Proxy.newProxyInstance(
    InputConnection::class.java.classLoader,
    arrayOf(InputConnection::class.java)
) { _, method, args ->
    val arguments = args?.joinToString(",") { it.toString() }.orEmpty()
    when (method.name) {
        "getTextBeforeCursor" -> {
            log += "${method.name}($arguments)"
            before.takeLast(args[0] as Int)
        }
        "getTextAfterCursor" -> {
            log += "${method.name}($arguments)"
            after.take(args[0] as Int)
        }
        "deleteSurroundingText" -> {
            log += "${method.name}($arguments)"
            deleteSucceeds
        }
        "beginBatchEdit", "endBatchEdit", "commitText" -> {
            log += "${method.name}($arguments)"
            true
        }
        "hashCode" -> 0
        "equals" -> false
        "toString" -> "FakeInputConnection"
        else -> when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    }
} as InputConnection

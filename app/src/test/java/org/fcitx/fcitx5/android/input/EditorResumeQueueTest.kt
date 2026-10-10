/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorResumeQueueTest {
    private data class Target(
        override val packageName: String,
        override val fieldId: Int,
        override val inputType: Int
    ) : EditorResumeTarget

    private data class Result(val target: Target, val value: String)

    private fun queue() = EditorResumeQueue<Target, String, Result>(::Result)

    @Test
    fun `completed result is handed out once to the same editor`() {
        val queue = queue()
        val target = Target("chat.app", 7, 1)

        queue.begin(1L, target)
        queue.complete(1L, "done")

        assertEquals(Result(target, "done"), queue.consumeForEditor("chat.app", 7, 1))
        assertNull(queue.consumeForEditor("chat.app", 7, 1))
    }

    @Test
    fun `result is discarded when the editor differs in any identity part`() {
        val queue = queue()
        val target = Target("chat.app", 7, 1)

        for ((packageName, fieldId, inputType) in listOf(
            Triple("other.app", 7, 1),
            Triple("chat.app", 8, 1),
            Triple("chat.app", 7, 2),
            Triple(null, 7, 1)
        )) {
            queue.begin(1L, target)
            queue.complete(1L, "done")
            assertNull(queue.consumeForEditor(packageName, fieldId, inputType))
            assertNull(queue.consumeForEditor("chat.app", 7, 1))
        }
    }

    @Test
    fun `stale completion cannot replace the latest request`() {
        val queue = queue()
        val latest = Target("chat.app", 7, 1)

        queue.begin(1L, Target("old.app", 1, 1))
        queue.begin(2L, latest)
        queue.complete(1L, "old")
        queue.complete(2L, "latest")

        assertEquals(Result(latest, "latest"), queue.consumeForEditor("chat.app", 7, 1))
    }

    @Test
    fun `cancelled request ignores its completion`() {
        val queue = queue()

        queue.begin(1L, Target("chat.app", 7, 1))
        queue.cancel(1L)
        queue.complete(1L, "done")

        assertNull(queue.consumeForEditor("chat.app", 7, 1))
    }

    @Test
    fun `beginning a new request drops an unconsumed result`() {
        val queue = queue()

        queue.begin(1L, Target("chat.app", 7, 1))
        queue.complete(1L, "done")
        queue.begin(2L, Target("chat.app", 7, 1))

        assertNull(queue.consumeForEditor("chat.app", 7, 1))
    }
}

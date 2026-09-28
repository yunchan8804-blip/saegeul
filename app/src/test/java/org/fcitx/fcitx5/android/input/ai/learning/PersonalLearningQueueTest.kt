/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ai.learning

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

class PersonalLearningQueueTest {

    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val logged = CopyOnWriteArrayList<Throwable>()
    private val capturingTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (t != null) logged += t
        }
    }
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    /** Mirrors [org.fcitx.fcitx5.android.FcitxApplication.applicationScope]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> uncaught += throwable }
        Timber.plant(capturingTree)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Timber.uproot(capturingTree)
        Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler)
    }

    @Test
    fun aFailedLearningTaskIsLoggedAndTheNextQueuedTaskStillRuns() = runBlocking {
        val failure = IllegalStateException("learning failed")
        val ran = CopyOnWriteArrayList<String>()

        val failing = launchSerializedLearning(scope, previous = null) {
            delay(50)
            ran += "first"
            throw failure
        }
        val next = launchSerializedLearning(scope, previous = failing) {
            ran += "second"
        }
        withTimeout(5_000) { next.join() }

        assertTrue("a learning failure must not reach the process's uncaught handler", uncaught.isEmpty())
        assertEquals(listOf("first", "second"), ran)
        assertTrue(failing.isCancelled)
        assertEquals(listOf<Throwable>(failure), logged.toList())
    }

    @Test
    fun aCancelledLearningTaskIsNotReportedAndTheNextQueuedTaskStillRuns() = runBlocking {
        val ran = CopyOnWriteArrayList<String>()

        val cancelled = launchSerializedLearning(scope, previous = null) {
            delay(5_000)
            ran += "cancelled"
        }
        val next = launchSerializedLearning(scope, previous = cancelled) {
            ran += "next"
        }
        cancelled.cancel()
        withTimeout(5_000) { next.join() }

        assertEquals(listOf("next"), ran)
        assertTrue(logged.isEmpty())
        assertTrue(uncaught.isEmpty())
    }
}

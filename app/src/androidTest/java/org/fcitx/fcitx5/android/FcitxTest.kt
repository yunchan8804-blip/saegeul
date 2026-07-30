/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.RawConfig
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

class FcitxTest {

    private companion object {

        lateinit var fcitx: Fcitx
        val fcitxEventChannel = Channel<FcitxEvent<*>>(capacity = Channel.CONFLATED)
        val scope = MainScope()

        @BeforeClass
        @JvmStatic
        fun setup() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            fcitx = Fcitx(context)

            // forward to our channel for point to point consuming
            fcitx.eventFlow
                .onEach { fcitxEventChannel.send(it) }
                .launchIn(scope)
            fcitx.start()

            // wait fcitx started
            runBlocking {
                receiveFirst<FcitxEvent.ReadyEvent>()
                fcitx.setEnabledIme(arrayOf("keyboard-us"))
                fcitx.setGlobalConfig(
                    RawConfig(
                        arrayOf(
                            RawConfig(
                                "Behavior", arrayOf(
                                    RawConfig("ShowInputMethodInformation", false)
                                )
                            )
                        )
                    )
                )
            }
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            fcitx.stop()
        }

        private suspend inline fun <reified T : FcitxEvent<*>> receiveFirst(): T? =
            fcitxEventChannel.receiveAsFlow().mapNotNull { it as? T }.firstOrNull()

    }

    private var enabledIme: List<String> = listOf()

    @Before
    fun saveEnabledIME() = runBlocking {
        enabledIme = fcitx.enabledIme().map { it.uniqueName }
    }

    @After
    fun restoreEnabledIME() = runBlocking {
        fcitx.setEnabledIme(enabledIme.toTypedArray())
    }

    @Test
    fun testKoreanReleaseExcludesChineseInputMethods(): Unit = runBlocking {
        val available = fcitx.availableIme().map { it.uniqueName }.toSet()
        Assert.assertTrue(available.contains("keyboard-us"))
        Assert.assertFalse(available.contains("pinyin"))
        Assert.assertFalse(available.contains("shuangpin"))
        Assert.assertFalse(available.contains("wbx"))
        Assert.assertFalse(available.contains("wbpy"))
    }

    @Test
    fun testInstalledHangulPluginComposesTwoSetText(): Unit = runBlocking {
        val available = fcitx.availableIme().map { it.uniqueName }.toSet()
        Assert.assertTrue(
            "The separately installed Hangul plugin was not discovered.",
            available.contains("hangul")
        )

        fcitx.setEnabledIme(arrayOf("hangul", "keyboard-us"))
        fcitx.activateIme("hangul")
        fcitx.focus(true)
        fcitx.reset()
        try {
            val committedText = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) {
                    fcitx.eventFlow
                        .filterIsInstance<FcitxEvent.CommitStringEvent>()
                        .first { it.data.text.contains("가") }
                        .data.text
                }
            }
            fcitx.sendKey('r')
            fcitx.sendKey('k')
            fcitx.sendKey(' ')
            val actual = committedText.await()
            Assert.assertTrue(
                "Two-set Hangul input did not commit '가': '$actual'.",
                actual.contains("가")
            )
        } finally {
            fcitx.reset()
            fcitx.focus(false)
        }
    }

}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 * SPDX-FileCopyrightText: Copyright 2026 Yun Chan
 */
package org.fcitx.fcitx5.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulComposer
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

    private suspend fun composeThroughHangulEngine(
        presses: List<Pair<MobileHangulComposer.Token, Long>>
    ): String = coroutineScope {
        fcitx.reset()
        val committedText = mutableListOf<String>()
        val firstCommit = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            fcitx.eventFlow
                .filterIsInstance<FcitxEvent.CommitStringEvent>()
                .collect {
                    committedText += it.data.text
                    firstCommit.complete(Unit)
                }
        }
        val composer = MobileHangulComposer()
        presses.flatMap { (token, nowMillis) -> composer.press(token, nowMillis) }
            .forEach { output ->
                when (output) {
                    MobileHangulComposer.Output.Backspace ->
                        fcitx.sendKey("BackSpace", 0u, 0, false, -1)
                    MobileHangulComposer.Output.Space ->
                        fcitx.sendKey(' ', 0u, 0, false, -1)
                    is MobileHangulComposer.Output.Keys -> output.value.forEach { key ->
                        fcitx.sendKey(key, 0u, 0, false, -1)
                    }
                }
                delay(10)
        }
        fcitx.sendKey(' ', 0u, 0, false, -1)
        withTimeout(2_000) { firstCommit.await() }
        delay(50)
        collector.cancelAndJoin()
        committedText.joinToString("")
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
    fun testBundledHangulEngineComposesTwoSetText(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val available = fcitx.availableIme().map { it.uniqueName }.toSet()
        Assert.assertTrue(
            "The Hangul engine bundled in the main app was not discovered.",
            available.contains("hangul")
        )

        // sendKey() is routed through AndroidFrontend's active input context. Create and focus
        // one explicitly because this test runs the engine directly, without an IME service.
        fcitx.activate(context.applicationInfo.uid, context.packageName)
        fcitx.focus(true)
        fcitx.setEnabledIme(arrayOf("keyboard-us", "hangul"))
        fcitx.activateIme("hangul")
        try {
            Assert.assertEquals(
                "Hangul was discovered but could not be activated.",
                "hangul",
                fcitx.currentIme().uniqueName
            )
            val committedText = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) {
                    fcitx.eventFlow
                        .filterIsInstance<FcitxEvent.CommitStringEvent>()
                        .first { it.data.text.contains("가") }
                        .data.text
                }
            }
            // Pass every argument explicitly: the release target and AndroidTest APK are shrunk
            // separately, so a test-only Kotlin default-argument bridge is not target-app ABI.
            fcitx.sendKey('r', 0u, 0, false, -1)
            delay(50)
            fcitx.sendKey('k', 0u, 0, false, -1)
            delay(50)
            fcitx.sendKey(' ', 0u, 0, false, -1)
            val actual = committedText.await()
            Assert.assertTrue(
                "Two-set Hangul input did not commit '가': '$actual'.",
                actual.contains("가")
            )
        } finally {
            fcitx.reset()
            fcitx.focus(false)
            fcitx.deactivate(context.applicationInfo.uid)
        }
    }

    @Test
    fun testChunjiinComposerProducesEveryModernVowelThroughHangulEngine(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val available = fcitx.availableIme().map { it.uniqueName }.toSet()
        Assert.assertTrue(
            "The separately installed Hangul plugin was not discovered.",
            available.contains("hangul")
        )

        val i = MobileHangulComposer.Token.VowelI
        val dot = MobileHangulComposer.Token.VowelDot
        val eu = MobileHangulComposer.Token.VowelEu
        val cases = linkedMapOf(
            "아" to listOf(i, dot), "애" to listOf(i, dot, i),
            "야" to listOf(i, dot, dot), "얘" to listOf(i, dot, dot, i),
            "어" to listOf(dot, i), "에" to listOf(dot, i, i),
            "여" to listOf(dot, dot, i), "예" to listOf(dot, dot, i, i),
            "오" to listOf(dot, eu), "와" to listOf(dot, eu, i, dot),
            "왜" to listOf(dot, eu, i, dot, i), "외" to listOf(dot, eu, i),
            "요" to listOf(dot, dot, eu), "우" to listOf(eu, dot),
            "워" to listOf(eu, dot, dot, i), "웨" to listOf(eu, dot, dot, i, i),
            "위" to listOf(eu, dot, i), "유" to listOf(eu, dot, dot),
            "으" to listOf(eu), "의" to listOf(eu, i), "이" to listOf(i)
        )

        fcitx.activate(context.applicationInfo.uid, context.packageName)
        fcitx.focus(true)
        fcitx.setEnabledIme(arrayOf("keyboard-us", "hangul"))
        fcitx.activateIme("hangul")
        try {
            cases.forEach { (expected, strokes) ->
                val presses = listOf(MobileHangulComposer.Token.Jamo('ㅇ') to 0L) +
                    strokes.mapIndexed { index, token -> token to (index + 1L) }
                Assert.assertEquals(
                    "Chunjiin strokes did not compose '$expected' through libhangul.",
                    expected,
                    composeThroughHangulEngine(presses)
                )
            }
        } finally {
            fcitx.reset()
            fcitx.focus(false)
            fcitx.deactivate(context.applicationInfo.uid)
        }
    }

    @Test
    fun testEveryMobileHangulComposerPathProducesCompoundsThroughHangulEngine(): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val available = fcitx.availableIme().map { it.uniqueName }.toSet()
            Assert.assertTrue(
                "The separately installed Hangul plugin was not discovered.",
                available.contains("hangul")
            )

            val ieung = MobileHangulComposer.Token.Jamo('ㅇ')
            val i = MobileHangulComposer.Token.VowelI
            val danmoumO = MobileHangulComposer.Token.Cycle(
                "dm_o", listOf('ㅗ', 'ㅛ'), 300
            )
            val danmoumA = MobileHangulComposer.Token.Cycle(
                "dm_a", listOf('ㅏ', 'ㅑ'), 300
            )
            val danmoumU = MobileHangulComposer.Token.Cycle(
                "dm_u", listOf('ㅜ', 'ㅠ'), 300
            )
            val danmoumE = MobileHangulComposer.Token.Cycle(
                "dm_e", listOf('ㅔ', 'ㅖ'), 300
            )
            val vegaO = MobileHangulComposer.Token.Cycle("vg_o", listOf('ㅗ', 'ㅛ'))
            val vegaA = MobileHangulComposer.Token.Cycle("vg_a", listOf('ㅏ', 'ㅑ'))
            val vegaIe = MobileHangulComposer.Token.Cycle(
                "vg_ie", listOf('ㅣ', 'ㅡ', 'ㅢ')
            )
            val naratgulOU = MobileHangulComposer.Token.Cycle(
                "nr_o", listOf('ㅗ', 'ㅜ'), naratgulVowelPair = true
            )
            val naratgulAEo = MobileHangulComposer.Token.Cycle(
                "nr_a", listOf('ㅏ', 'ㅓ'), naratgulVowelPair = true
            )

            val cases = linkedMapOf(
                "Chunjiin Plus 과" to ("과" to listOf(
                    MobileHangulComposer.Token.Jamo('ㄱ') to 0L,
                    MobileHangulComposer.Token.VowelDot to 1L,
                    MobileHangulComposer.Token.VowelEu to 2L,
                    i to 3L,
                    MobileHangulComposer.Token.VowelDot to 4L
                )),
                "Danmoum 와" to ("와" to listOf(
                    ieung to 0L, danmoumO to 100L, danmoumA to 500L
                )),
                "Danmoum 웨" to ("웨" to listOf(
                    ieung to 0L, danmoumU to 100L, danmoumE to 500L
                )),
                "Vega 와" to ("와" to listOf(
                    ieung to 0L, vegaO to 100L, vegaA to 500L
                )),
                "Vega compound cycle wrap" to ("이" to listOf(
                    ieung to 0L,
                    vegaIe to 100L, vegaIe to 200L, vegaIe to 300L, vegaIe to 400L
                )),
                "Naratgul 웨" to ("웨" to listOf(
                    ieung to 0L,
                    naratgulOU to 100L, naratgulOU to 200L,
                    naratgulAEo to 300L, i to 400L
                ))
            )

            fcitx.activate(context.applicationInfo.uid, context.packageName)
            fcitx.focus(true)
            fcitx.setEnabledIme(arrayOf("keyboard-us", "hangul"))
            fcitx.activateIme("hangul")
            try {
                cases.forEach { (label, expectedAndPresses) ->
                    val (expected, presses) = expectedAndPresses
                    Assert.assertEquals(
                        "$label failed through libhangul.",
                        expected,
                        composeThroughHangulEngine(presses)
                    )
                }

                listOf(
                    'ㅘ' to "와", 'ㅙ' to "왜", 'ㅚ' to "외",
                    'ㅝ' to "워", 'ㅞ' to "웨", 'ㅟ' to "위", 'ㅢ' to "의"
                ).forEach { (vowel, expected) ->
                    Assert.assertEquals(
                        "Moakey direct '$vowel' failed through libhangul.",
                        expected,
                        composeThroughHangulEngine(
                            listOf(ieung to 0L, MobileHangulComposer.Token.Jamo(vowel) to 1L)
                        )
                    )
                }
            } finally {
                fcitx.reset()
                fcitx.focus(false)
                fcitx.deactivate(context.applicationInfo.uid)
            }
        }

    @Test
    fun testExpiredPhonepadConsonantCycleStartsANewJamoThroughHangulEngine(): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val cycle = MobileHangulComposer.Token.Cycle(
                "cj_g", listOf('ㄱ', 'ㅋ', 'ㄲ'), timeoutMillis = 1_500
            )
            fcitx.activate(context.applicationInfo.uid, context.packageName)
            fcitx.focus(true)
            fcitx.setEnabledIme(arrayOf("keyboard-us", "hangul"))
            fcitx.activateIme("hangul")
            try {
                Assert.assertEquals(
                    "An expired Chunjiin multitap group must not become a tense consonant.",
                    "ㄱㄱ",
                    composeThroughHangulEngine(listOf(cycle to 100L, cycle to 1_601L))
                )
            } finally {
                fcitx.reset()
                fcitx.focus(false)
                fcitx.deactivate(context.applicationInfo.uid)
            }
        }

}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.keyboard.MobileHangulComposer
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulComposer.Output
import org.fcitx.fcitx5.android.input.keyboard.MobileHangulComposer.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Red Team Adversarial Unit Test Suite: Hangul Engine & Chunjiin Automata Invariants.
 *
 * Attack Vector 1: Chunjiin VowelDot Backspace Invariant.
 * When VowelDot is pressed in isolation (pendingDots > 0) after a completed glyph,
 * backspace must NOT delete the preceding completed character in the Dubeolsik backend,
 * but only consume/cancel the pending dot state.
 *
 * Attack Vector 2: Compound Vowel Replacement Backspace Precision (ㅚ -> ㅘ).
 * Probing Dubeolsik backend desynchronization: When replacing compound vowel ㅚ ("hl") with ㅘ ("hk"),
 * the emitted sequence must either delete only 'l' and append 'k', or delete both ('h', 'l') and append "hk".
 * A single backspace with "hk" causes redundant double-input ("hhk") in the backend.
 *
 * Attack Vector 3: High-speed jamo event flooding & state machine reset integrity.
 * Under high-frequency burst streams and timeout edge fuzzing, composer must maintain
 * deterministic clean-slate integrity upon reset.
 */
class RedTeamHangulEngineAutomataTest {

    private val backspace = Output.Backspace
    private val space = Output.Space
    private fun keys(value: String) = Output.Keys(value)

    /**
     * Dubeolsik Virtual Backend Buffer Simulator.
     * Accurately simulates the Dubeolsik engine's key input stream buffer.
     */
    class VirtualDubeolsikBuffer {
        private val buffer = StringBuilder()

        fun apply(output: Output) {
            when (output) {
                is Output.Backspace -> {
                    if (buffer.isNotEmpty()) {
                        buffer.deleteCharAt(buffer.length - 1)
                    }
                }
                is Output.Space -> buffer.append(' ')
                is Output.Keys -> buffer.append(output.value)
            }
        }

        fun applyAll(outputs: List<Output>) {
            outputs.forEach(::apply)
        }

        fun content(): String = buffer.toString()
        fun clear() = buffer.clear()
        fun length() = buffer.length
    }

    /**
     * Chunjiin Keyboard Driver Simulation demonstrating the PendingDot Backspace Invariant.
     * When pendingDots > 0, an incoming user Backspace event must be absorbed locally
     * to cancel the dot rather than dispatching a destructive Backspace to the Dubeolsik backend.
     */
    class ChunjiinDriver(val composer: MobileHangulComposer = MobileHangulComposer()) {
        private var pendingDotsCount = 0

        fun pressToken(token: Token): List<Output> {
            val outputs = composer.press(token)
            if (token is Token.VowelDot && outputs.isEmpty()) {
                pendingDotsCount = if (pendingDotsCount == 2) 1 else pendingDotsCount + 1
            } else {
                pendingDotsCount = 0
            }
            return outputs
        }

        /**
         * Robust backspace handler:
         * If pendingDots > 0, cancel pending dot locally (no backend delete).
         * Otherwise, send Backspace to backend and reset composer.
         */
        fun handleBackspace(): List<Output> {
            return if (pendingDotsCount > 0) {
                pendingDotsCount--
                composer.reset()
                emptyList() // Absorbed! Protects preceding completed glyph
            } else {
                composer.reset()
                listOf(Output.Backspace)
            }
        }

        /**
         * Naive/vulnerable backspace handler (without pending dot protection):
         * Blindly resets composer and sends Backspace to backend.
         */
        fun handleBackspaceNaive(): List<Output> {
            composer.reset()
            return listOf(Output.Backspace) // Destroys preceding glyph in backend!
        }

        fun reset() {
            pendingDotsCount = 0
            composer.reset()
        }

        fun hasPendingDots() = pendingDotsCount > 0
    }

    // =========================================================================
    // ATTACK VECTOR 1: Chunjiin VowelDot Backspace Invariant
    // =========================================================================

    /**
     * Invariant 1.1: Standalone VowelDot after completed glyph produces zero Dubeolsik output.
     * Because 'ㆍ' is not an independent modern Hangul jamo, MobileHangulComposer does not
     * dispatch any key strokes until a directional primitive (ㅣ or ㅡ) is provided.
     */
    @Test
    fun probeStandaloneVowelDotEmitsNoDubeolsikOutput() {
        val composer = MobileHangulComposer()

        // 1. Emit completed glyph 'ㄱ'
        val giyeokOutput = composer.press(Token.Jamo('ㄱ'))
        assertEquals(listOf(keys("r")), giyeokOutput)

        // 2. Press standalone VowelDot
        val dotOutput = composer.press(Token.VowelDot)
        assertTrue("Standalone VowelDot must emit emptyList()", dotOutput.isEmpty())

        // 3. Press second VowelDot
        val doubleDotOutput = composer.press(Token.VowelDot)
        assertTrue("Second standalone VowelDot must also emit emptyList()", doubleDotOutput.isEmpty())
    }

    /**
     * Invariant 1.2: Completed glyph deletion defense.
     * Scenario: User types '강' (r + k + d), then taps 'ㆍ' (pendingDots = 1, backend still holds "rkd"),
     * then taps Backspace.
     * Vulnerability: If backspace is passed through to Dubeolsik backend, 'ㅇ' ('d') is deleted!
     * Invariant: Backspace must absorb the pending dot, leaving "rkd" ('강') completely intact in the backend.
     */
    @Test
    fun probePendingDotBackspaceInvariantProtectsPrecedingCompletedGlyph() {
        val backend = VirtualDubeolsikBuffer()
        val driver = ChunjiinDriver()

        // 1. Enter completed syllable '강' -> 'ㄱ' + 'ㅣ' + 'ㆍ' + 'ㅇ'
        backend.applyAll(driver.pressToken(Token.Jamo('ㄱ'))) // "r"
        backend.applyAll(driver.pressToken(Token.VowelI))    // "l"
        backend.applyAll(driver.pressToken(Token.VowelDot))  // [bs, "k"] -> "rk"
        backend.applyAll(driver.pressToken(Token.Jamo('ㅇ'))) // "d" -> "rkd" ('강')
        assertEquals("Backend must contain completed glyph '강' (rkd)", "rkd", backend.content())

        // 2. User accidentally presses 'ㆍ' after completed syllable.
        // Because 'ㅇ' is a consonant, currentVowel is null, so VowelDot cannot combine.
        // pendingDots becomes 1, and backend receives NOTHING (emptyList()).
        val dotOutputs = driver.pressToken(Token.VowelDot)
        assertTrue("VowelDot after completed syllable emits no backend keystrokes", dotOutputs.isEmpty())
        backend.applyAll(dotOutputs)
        assertEquals("Backend buffer still contains 'rkd'", "rkd", backend.content())
        assertTrue("Driver must report pending dots", driver.hasPendingDots())

        // 3. User taps Backspace to cancel the accidental dot.
        // Robust driver must NOT send backspace to backend!
        val backspaceOutputs = driver.handleBackspace()
        assertEquals("Backspace was absorbed locally to cancel pending dot", emptyList<Output>(), backspaceOutputs)
        backend.applyAll(backspaceOutputs)

        // VERIFICATION OF INVARIANT: Preceding completed glyph '강' ("rkd") was NOT deleted!
        assertEquals("Preceding completed glyph 'rkd' ('강') must remain 100% intact!", "rkd", backend.content())
        assertFalse("Driver pending dots must now be clear", driver.hasPendingDots())

        // Contrast with NAIVE behavior:
        val naiveBackend = VirtualDubeolsikBuffer()
        val naiveDriver = ChunjiinDriver()
        naiveBackend.applyAll(naiveDriver.pressToken(Token.Jamo('ㄱ')))
        naiveBackend.applyAll(naiveDriver.pressToken(Token.VowelI))
        naiveBackend.applyAll(naiveDriver.pressToken(Token.VowelDot))
        naiveBackend.applyAll(naiveDriver.pressToken(Token.Jamo('ㅇ')))
        naiveDriver.pressToken(Token.VowelDot) // accidental dot
        // Naive driver sends raw Backspace:
        naiveBackend.applyAll(naiveDriver.handleBackspaceNaive())
        // Naive backspace corrupts '강' into '가' ("rk")!
        assertEquals("Naive handler erroneously deletes 'ㅇ', corrupting text into 'rk'", "rk", naiveBackend.content())
    }

    /**
     * Invariant 1.3: Sequential backspace with double pending dots (pendingDots = 2).
     * 1st Backspace cancels second dot (pendingDots: 2 -> 1).
     * 2nd Backspace cancels first dot (pendingDots: 1 -> 0).
     * Neither backspace should delete the preceding character.
     * Only the 3rd Backspace (when pendingDots == 0) should delete the preceding character.
     */
    @Test
    fun probeDoublePendingDotSequentialCancellationInvariant() {
        val backend = VirtualDubeolsikBuffer()
        val driver = ChunjiinDriver()

        // Type 'ㄴ' ("s")
        backend.applyAll(driver.pressToken(Token.Jamo('ㄴ')))
        assertEquals("s", backend.content())

        // Press dot twice: ㆍ, ㆍ
        driver.pressToken(Token.VowelDot) // pendingDots = 1
        driver.pressToken(Token.VowelDot) // pendingDots = 2
        assertEquals("Backend still holds 's'", "s", backend.content())

        // 1st Backspace -> cancels 2nd dot
        val bs1 = driver.handleBackspace()
        backend.applyAll(bs1)
        assertEquals("s", backend.content())

        // 2nd Backspace -> cancels 1st dot
        val bs2 = driver.handleBackspace()
        backend.applyAll(bs2)
        assertEquals("s", backend.content())

        // 3rd Backspace -> now pendingDots == 0, legitimately deletes 's'
        val bs3 = driver.handleBackspace()
        backend.applyAll(bs3)
        assertEquals("Buffer is now empty after legitimate 3rd backspace", "", backend.content())
    }

    // =========================================================================
    // ATTACK VECTOR 2: Compound Vowel Replacement Backspace Precision (ㅚ -> ㅘ)
    // =========================================================================

    /**
     * Invariant 2.1: Mathematical Analysis of the ㅚ -> ㅘ Transition in Dubeolsik backend.
     *
     * In Chunjiin:
     * Step 1: ㆍ + ㅡ = ㅗ -> Output: Keys("h") [Backend: "h"]
     * Step 2: ㅣ      = ㅚ -> Output: Backspace, Keys("hl") [Backend: "hl"]
     * Step 3: ㆍ      = ㅘ -> Composer outputs: Backspace, Backspace, Keys("hk")
     *
     * Regression guard: the backend holds "hl" (length 2), so a single Backspace would delete
     * only 'l' and appending "hk" would produce "hhk" (ㅗ + ㅗ + ㅏ). The composer erases one
     * Backspace per Dubeolsik key of the replaced vowel (Option B below).
     *
     * The correct sequence must be EITHER:
     *   Option A (Precision single replacement): Backspace 1 time + Keys("k") -> "h" + "k" = "hk" (ㅘ)
     *   Option B (Full compound replacement): Backspace 2 times + Keys("hk") -> "" + "hk" = "hk" (ㅘ)
     */
    @Test
    fun probeOeToWaCompoundVowelReplacementSequencePrecision() {
        val composer = MobileHangulComposer()

        // Step 1: ㆍ + ㅡ -> ㅗ ("h")
        val step1Dot = composer.press(Token.VowelDot)
        assertTrue(step1Dot.isEmpty())
        val step1Eu = composer.press(Token.VowelEu)
        assertEquals(listOf(keys("h")), step1Eu)

        // Step 2: ㅣ -> ㅚ ("hl")
        val step2I = composer.press(Token.VowelI)
        assertEquals(listOf(backspace, keys("hl")), step2I)

        // Simulate backend at this point:
        val backend = VirtualDubeolsikBuffer()
        backend.applyAll(step1Eu) // "h"
        backend.applyAll(step2I)  // backspace -> "", keys("hl") -> "hl"
        assertEquals("Backend buffer at ㅚ must be 'hl'", "hl", backend.content())

        // Step 3: ㆍ -> transforms ㅚ into ㅘ
        val step3Dot = composer.press(Token.VowelDot)

        // ㅚ is two Dubeolsik keys ("hl"), so the composer must erase both before typing ㅘ.
        assertEquals(
            "ㅚ -> ㅘ must erase both keys of ㅚ before typing 'hk'",
            listOf(backspace, backspace, keys("hk")),
            step3Dot
        )

        // Apply the outputs to the backend: it must hold exactly ㅘ, not the old "hhk" desync.
        val composedBackend = VirtualDubeolsikBuffer()
        composedBackend.applyAll(listOf(keys("hl"))) // starting from ㅚ
        composedBackend.applyAll(step3Dot)
        assertEquals("Backend must hold exactly 'hk' (ㅘ)", "hk", composedBackend.content())

        // Verify the two valid mathematical solutions that preserve backend coherence:
        // Option A: Single backspace deleting 'l', followed by 'k'
        val optionABackend = VirtualDubeolsikBuffer()
        optionABackend.applyAll(listOf(keys("hl")))
        val optionAOutputs = listOf(Output.Backspace, Output.Keys("k"))
        optionABackend.applyAll(optionAOutputs)
        assertEquals("Option A yields exact 'hk' (ㅘ)", "hk", optionABackend.content())

        // Option B: Double backspace deleting 'h' and 'l', followed by "hk"
        val optionBBackend = VirtualDubeolsikBuffer()
        optionBBackend.applyAll(listOf(keys("hl")))
        val optionBOutputs = listOf(Output.Backspace, Output.Backspace, Output.Keys("hk"))
        optionBBackend.applyAll(optionBOutputs)
        assertEquals("Option B yields exact 'hk' (ㅘ)", "hk", optionBBackend.content())
    }

    /**
     * Invariant 2.2: Comprehensive Compound Vowel Transition Precision.
     * Probes all compound vowels that transform on subsequent strokes:
     * - ㅗ ("h") + ㅣ -> ㅚ ("hl")
     * - ㅜ ("n") + ㅣ -> ㅟ ("nl")
     * - ㅡ ("m") + ㅣ -> ㅢ ("ml")
     * - ㅓ ("j") + ㅣ -> ㅔ ("p")
     * - ㅏ ("k") + ㅣ -> ㅐ ("o")
     */
    @Test
    fun probeAllCompoundVowelTransitionsStrokeCoherence() {
        val transitions = listOf(
            // ㅗ -> ㅚ
            listOf(Token.VowelDot, Token.VowelEu, Token.VowelI) to "hl",
            // ㅜ -> ㅟ
            listOf(Token.VowelEu, Token.VowelDot, Token.VowelI) to "nl",
            // ㅡ -> ㅢ
            listOf(Token.VowelEu, Token.VowelI) to "ml",
            // ㅓ -> ㅔ
            listOf(Token.VowelDot, Token.VowelI, Token.VowelI) to "p",
            // ㅏ -> ㅐ
            listOf(Token.VowelI, Token.VowelDot, Token.VowelI) to "o"
        )

        for ((tokens, expectedBackend) in transitions) {
            val composer = MobileHangulComposer()
            val backend = VirtualDubeolsikBuffer()
            for (token in tokens) {
                backend.applyAll(composer.press(token))
            }
            assertEquals(
                "Transition tokens $tokens must result in backend string '$expectedBackend'",
                expectedBackend,
                backend.content()
            )
        }
    }

    // =========================================================================
    // ATTACK VECTOR 3: High-Speed Jamo Event Flooding & State Machine Reset Integrity
    // =========================================================================

    /**
     * Invariant 3.1: 10,000 High-Speed Random Event Flooding & Clean-Slate Invariant.
     * Floods the composer with 10,000 rapid randomized tokens (multitap cycles,
     * directional vowels, stroke modifiers, double consonants, boundary keys).
     * Then invokes reset(). The composer MUST produce output 100% identical to a brand new instance.
     */
    @Test
    fun probeHighSpeedJamoEventFloodingAndStateResetIntegrity() {
        val composer = MobileHangulComposer()
        val rng = Random(42) // Deterministic seed

        val cycleGiyeok = Token.Cycle("g", listOf('ㄱ', 'ㅋ', 'ㄲ'), 1_500)
        val cycleNieun = Token.Cycle("n", listOf('ㄴ', 'ㄹ'), 1_500)
        val cycleVowels = Token.Cycle("v_a", listOf('ㅏ', 'ㅓ'), 1_500, naratgulVowelPair = true)

        val sampleTokens: List<Token> = listOf(
            cycleGiyeok,
            cycleNieun,
            cycleVowels,
            Token.Jamo('ㅁ'),
            Token.Jamo('ㅂ'),
            Token.Jamo('ㅅ'),
            Token.VowelI,
            Token.VowelDot,
            Token.VowelEu,
            Token.AddStroke,
            Token.DoubleConsonant,
            Token.Boundary
        )

        var simulatedTime = 1000L
        // Flood 10,000 events
        repeat(10_000) {
            val randomToken = sampleTokens[rng.nextInt(sampleTokens.size)]
            simulatedTime += rng.nextLong(1, 50) // High-speed 1ms~50ms interval
            composer.press(randomToken, simulatedTime)
        }

        // Action: Reset composer state
        composer.reset()

        // Verification: Compare against a pristine fresh composer on standard Hangul words:
        // Word 1: '한' = ㅎ (g) + ㅏ (k) + ㄴ (s) -> Dubeolsik: "g", "k", "s"
        val freshComposer = MobileHangulComposer()
        val testWordStrokes = listOf(
            Token.Jamo('ㅎ'),
            Token.VowelI,
            Token.VowelDot, // forms 'ㅏ'
            Token.Jamo('ㄴ')
        )

        var time = 50_000L
        for (token in testWordStrokes) {
            time += 100
            val floodedOutputs = composer.press(token, time)
            val freshOutputs = freshComposer.press(token, time)
            assertEquals(
                "Flooded-then-reset composer output must strictly match fresh composer for token $token",
                freshOutputs,
                floodedOutputs
            )
        }
    }

    /**
     * Invariant 3.2: Multitap Cycle Timeout Boundary Precision Fuzzing.
     * Tests exact millisecond boundaries around the 1500ms and 300ms thresholds:
     * - delta < timeout (e.g. timeout - 1ms): Must cycle / replace (Output.Backspace + next jamo)
     * - delta == timeout: Must cycle / replace
     * - delta > timeout (e.g. timeout + 1ms): Must commit new character without backspace
     */
    @Test
    fun probeMultitapTimeoutBoundaryPrecision() {
        val composer = MobileHangulComposer()
        val token = Token.Cycle("test_cycle", listOf('ㄱ', 'ㅋ', 'ㄲ'), timeoutMillis = 1_500)

        // 1. Initial press at t=1,000ms
        val out1 = composer.press(token, 1_000)
        assertEquals(listOf(keys("r")), out1)

        // 2. Exact boundary: t=2,500ms (delta = 1,500ms == timeout) -> Must replace ('ㅋ' = "z")
        val out2 = composer.press(token, 2_500)
        assertEquals(listOf(backspace, keys("z")), out2)

        // 3. Just expired boundary: t=4,001ms (delta = 1,501ms > timeout) -> Must NOT replace, emit fresh 'ㄱ' ("r")
        val out3 = composer.press(token, 4_001)
        assertEquals(listOf(keys("r")), out3)

        // 4. Just within boundary: t=5,500ms (delta = 1,499ms < timeout) -> Must replace ('ㅋ' = "z")
        val out4 = composer.press(token, 5_500)
        assertEquals(listOf(backspace, keys("z")), out4)
    }

    /**
     * Invariant 3.3: Consecutive Reset Idempotence.
     * Calling reset() 100 times consecutively or in an uninitialized state must be a no-op
     * and must not throw any exception or corrupt internal default values.
     */
    @Test
    fun probeConsecutiveResetIdempotence() {
        val composer = MobileHangulComposer()
        repeat(100) {
            composer.reset()
        }

        // Works normally after redundant resets
        val out = composer.press(Token.Jamo('ㄱ'))
        assertEquals(listOf(keys("r")), out)
    }

    /**
     * Invariant 3.4: Defense against empty Cycle token list.
     * A Token.Cycle with an empty jamo list must fail fast with IllegalArgumentException
     * and must not leave the composer in a corrupted state.
     */
    @Test
    fun probeEmptyCycleTokenFailsFastWithoutStateCorruption() {
        val composer = MobileHangulComposer()

        try {
            composer.press(Token.Cycle("invalid_empty", emptyList()))
            fail("Expected IllegalArgumentException for empty cycle token")
        } catch (e: IllegalArgumentException) {
            assertTrue("Exception message should mention jamo requirement", e.message?.contains("jamo") == true)
        }

        // Composer remains fully operational
        val out = composer.press(Token.Jamo('ㅇ'))
        assertEquals(listOf(keys("d")), out)
    }
}

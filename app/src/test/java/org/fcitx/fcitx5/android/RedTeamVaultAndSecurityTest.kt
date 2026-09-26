/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.input.ai.TypingDnaLevelCurve
import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Red Team Adversarial Unit Tests: Vault, Level Curve, and Security Boundaries.
 *
 * Probes TypingDNA level curve underflow, reward point boundary bypasses,
 * and VaultFile corrupted magic header resilience against unhandled bounds exceptions.
 */
class RedTeamVaultAndSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Attack Vector 1: TypingDnaLevelCurve.titleFor negative and zero level boundary attack.
     *
     * When boundary or negative levels (0, -1, -999) are injected,
     * the system must not route to the default "else" branch and grant the highest endgame title "언어 지문 우주".
     * Invariant: It must return the lowest baseline title "새싹 학습자".
     */
    @Test
    fun probeTitleForZeroAndNegativeLevelsDoesNotGrantEndgameTitle() {
        val boundaryLevels = listOf(0, -1, -999, Int.MIN_VALUE)

        for (level in boundaryLevels) {
            val title = TypingDnaLevelCurve.titleFor(level)
            assertNotEquals(
                "Level $level must never be granted the endgame title '언어 지문 우주'",
                "언어 지문 우주",
                title
            )
            assertEquals(
                "Level $level must return the baseline beginner title '새싹 학습자'",
                "새싹 학습자",
                title
            )
        }
    }

    /**
     * Attack Vector 2: TypingDnaLevelCurve.rewardBonusPoints negative/zero level boundary.
     *
     * Invariant: Bonus points for non-positive levels (<= 0) must be 0.
     */
    @Test
    fun probeRewardBonusPointsZeroAndNegativeLevelsReturnZero() {
        val nonPositiveLevels = listOf(0, -1, -25, -100, -1000, Int.MIN_VALUE)

        for (level in nonPositiveLevels) {
            val bonus = TypingDnaLevelCurve.rewardBonusPoints(level)
            assertEquals(
                "Reward bonus points for non-positive level $level must strictly be 0",
                0,
                bonus
            )
        }
    }

    /**
     * Attack Vector 3: TypingDnaLevelCurve.describe negative sentences boundary.
     *
     * When negative analyzedSentences (e.g. -10) are passed,
     * the curve description must maintain Level 1 and 0% progress.
     */
    @Test
    fun probeDescribeWithNegativeSentencesMaintainsLevelOneAndZeroProgress() {
        val negativeSentences = listOf(-10, -1, -500, Int.MIN_VALUE)

        for (sentences in negativeSentences) {
            val progress = TypingDnaLevelCurve.describe(sentences)
            assertEquals("Level must remain 1 for negative sentence count ($sentences)", 1, progress.level)
            assertEquals("Progress percent must be 0% for negative sentence count ($sentences)", 0, progress.progressPercent)
            assertEquals("Title must be '새싹 학습자' for negative sentence count ($sentences)", "새싹 학습자", progress.title)
            assertEquals("Next target sentences must be 15 for level 1", 15, progress.nextTargetSentences)
        }
    }

    /**
     * Attack Vector 4: VaultFile hasMagic and header parsing boundary without cipherId.
     *
     * When a file has the exact 4-byte magic 'SGV1' but no subsequent bytes (missing cipherId length),
     * or truncated cipherId bytes, methods such as readText() and migrateIfLegacy()
     * must not crash the process with unhandled ArrayIndexOutOfBoundsException.
     */
    @Test
    fun probeVaultFileMagicBoundaryWithoutCipherIdDoesNotCrashWithIndexOutOfBounds() {
        val testPayloads = listOf(
            "exact_4_bytes_magic" to byteArrayOf(
                'S'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte()
            ),
            "magic_with_length_byte_only" to byteArrayOf(
                'S'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte(), 16.toByte()
            ),
            "magic_with_truncated_cipher_id" to byteArrayOf(
                'S'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte(), 10.toByte(),
                'p'.code.toByte(), 'l'.code.toByte()
            )
        )

        for ((label, rawBytes) in testPayloads) {
            val file = File(tempFolder.root, "vault_$label.dat")
            file.writeBytes(rawBytes)
            val aad = VaultFile.aadFor("vault_$label.dat")
            val vaultFile = VaultFile(file, PlainVaultCipher, aad)

            try {
                val result = vaultFile.readText()
                // A safe return (null or plaintext string) or safe exception is acceptable.
            } catch (e: ArrayIndexOutOfBoundsException) {
                fail("VaultFile.readText() crashed with ArrayIndexOutOfBoundsException on payload '$label': ${e.message}")
            } catch (e: IndexOutOfBoundsException) {
                fail("VaultFile.readText() crashed with IndexOutOfBoundsException on payload '$label': ${e.message}")
            } catch (_: Exception) {
                // Safe failures such as GeneralSecurityException or IOException are expected.
            }

            try {
                vaultFile.migrateIfLegacy()
            } catch (e: ArrayIndexOutOfBoundsException) {
                fail("VaultFile.migrateIfLegacy() crashed with ArrayIndexOutOfBoundsException on payload '$label': ${e.message}")
            } catch (e: IndexOutOfBoundsException) {
                fail("VaultFile.migrateIfLegacy() crashed with IndexOutOfBoundsException on payload '$label': ${e.message}")
            } catch (_: Exception) {
                // Safe failures are expected.
            }
        }
    }
}

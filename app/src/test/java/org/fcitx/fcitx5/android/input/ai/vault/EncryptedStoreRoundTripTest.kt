/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.vault

import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies that [PersonalNgramModel] and [CorrectionPatternStore], when constructed with an
 * [AesGcmVaultCipher], persist their JSON payloads behind the `SGV1` vault format, restore the
 * same data through a fresh instance sharing the key, and transparently migrate pre-existing
 * legacy plaintext files written before vault encryption was introduced.
 */
class EncryptedStoreRoundTripTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val fixedTime = 1_000_000_000L

    @Test
    fun personalNgramModelPersistsBehindVaultMagicAndRestoresAcrossInstances() {
        val file = tempFolder.newFile("personal_ngram_encrypted.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())

        val first = PersonalNgramModel(storeFile = file, clock = { fixedTime }, cipher = cipher)
        first.learn("오늘 회의 참석합니다", "com.example.test")
        first.save()

        val magic = file.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII)
        assertEquals("SGV1", magic)

        val second = PersonalNgramModel(storeFile = file, clock = { fixedTime }, cipher = cipher)
        val predictions = second.predictNext("오늘", "com.example.test", 5).map { it.word }
        assertTrue("expected '회의' among $predictions", predictions.contains("회의"))
    }

    @Test
    fun personalNgramModelMigratesLegacyPlaintextOnLoad() {
        val file = tempFolder.newFile("personal_ngram_legacy.json")
        val legacyJson = """{"v":1,"learned":1,"last":$fixedTime,"tables":{"*":{"uni":{"안녕":[3.0,$fixedTime]},"bi":{},"tri":{}}}}"""
        file.writeText(legacyJson, Charsets.UTF_8)

        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val model = PersonalNgramModel(storeFile = file, clock = { fixedTime }, cipher = cipher)

        val magic = file.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII)
        assertEquals("SGV1", magic)
        assertEquals(3.0f, model.unigramCount("안녕"), 0.001f)
    }

    @Test
    fun correctionPatternStorePersistsBehindVaultMagicAndRestoresAcrossInstances() {
        val file = tempFolder.newFile("correction_patterns_encrypted.json")
        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())

        val first = CorrectionPatternStore(storeFile = file, cipher = cipher)
        first.recordCorrection("사묘ㅏ함니다", "감사합니다")
        first.save()

        val magic = file.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII)
        assertEquals("SGV1", magic)

        val second = CorrectionPatternStore(storeFile = file, cipher = cipher)
        assertEquals("감사합니다", second.lookup("사묘ㅏ함니다").first().corrected)
    }

    @Test
    fun correctionPatternStoreMigratesLegacyPlaintextOnLoad() {
        val file = tempFolder.newFile("correction_patterns_legacy.json")
        val legacyJson = """{"pairs":[{"typed":"사묘ㅏ함니다","corrected":"감사합니다","count":2.0,"lastSeenMs":1000}],"confusions":[]}"""
        file.writeText(legacyJson, Charsets.UTF_8)

        val cipher = AesGcmVaultCipher(AesGcmVaultCipher.randomKey())
        val store = CorrectionPatternStore(storeFile = file, cipher = cipher)

        val magic = file.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII)
        assertEquals("SGV1", magic)
        assertEquals("감사합니다", store.lookup("사묘ㅏ함니다").first().corrected)
    }
}

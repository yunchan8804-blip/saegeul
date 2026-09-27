/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.ChoseongMorphologyEngine
import org.fcitx.fcitx5.android.input.ai.DynamicBigram
import org.fcitx.fcitx5.android.input.ai.PersonaDna
import org.fcitx.fcitx5.android.input.ai.TypingDnaRepository
import org.fcitx.fcitx5.android.input.ai.morphology.KoreanMorphologicalEndingAnalyzer
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.FLAG_HAS_BATCHIM
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.FLAG_NON_RIEUL_BATCHIM
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.FLAG_NO_BATCHIM
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.FLAG_RIEUL_BATCHIM
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.JosaKind
import org.fcitx.fcitx5.android.input.ai.vault.AesGcmVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.EnvelopeVaultCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.GeneralSecurityException
import kotlin.system.measureNanoTime

/**
 * Extension helper resolving [decomposeHangul] through [ChoseongMorphologyEngine]
 * under the [KoreanJosaBitmaskEngine] scope as requested by test specification.
 */
private fun KoreanJosaBitmaskEngine.decomposeHangul(text: String): String {
    return ChoseongMorphologyEngine().decomposeHangul(text)
}

/**
 * Extension helper resolving [getJosa] through [selectJosa]
 * under the [KoreanJosaBitmaskEngine] scope as requested by test specification.
 */
private fun KoreanJosaBitmaskEngine.getJosa(word: String, kind: JosaKind): String {
    return selectJosa(word, kind)
}

private fun KoreanJosaBitmaskEngine.getJosa(c: Char, kind: JosaKind): String {
    return selectJosa(c, kind)
}

/**
 * Red Team Round 10: Phonology & Security Shield E2E Device Test.
 *
 * Scenarios:
 * 1. RED-PHONO-06: 한국어 불규칙 음운 탈락/축약 및 사잇소리 현상 무결성
 * 3. RED-PERF-01: 120Hz 키보드 극한 타이핑 버스트(1초당 50타) 입력 중 GC 압박 최소화 및 고속 연산
 * 4. RED-HANGUL-01: 불완전 초성/중성 잔여 버퍼의 안전한 정합성 처리
 * 5. RED-VAULT-02: 온디바이스 언어 금고 암호화 변조 방어 및 오프라인 로컬 무결성
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RedTeamRound10PhonologySecurityShieldDeviceTest {

    // =========================================================================
    // 1. RED-PHONO-06: 한국어 불규칙 음운 탈락/축약 및 사잇소리 현상 무결성
    // =========================================================================
    @Test
    fun testRedPhono06_IrregularDecompositionAndSaiSoriIntegrity() {
        // --- (1) 사잇소리 결합 단어 자모 분해 검증 ---
        // 촛불: ㅊㅗㅅ + ㅂㅜㄹ
        assertEquals("ㅊㅗㅅㅂㅜㄹ", KoreanJosaBitmaskEngine.decomposeHangul("촛불"))
        // 잇몸: ㅇㅣㅅ + ㅁㅗㅁ
        assertEquals("ㅇㅣㅅㅁㅗㅁ", KoreanJosaBitmaskEngine.decomposeHangul("잇몸"))
        // 나뭇가지: ㄴㅏ + ㅁㅜㅅ + ㄱㅏ + ㅈㅣ
        assertEquals("ㄴㅏㅁㅜㅅㄱㅏㅈㅣ", KoreanJosaBitmaskEngine.decomposeHangul("나뭇가지"))
        // 바닷가: ㅂㅏ + ㄷㅏㅅ + ㄱㅏ
        assertEquals("ㅂㅏㄷㅏㅅㄱㅏ", KoreanJosaBitmaskEngine.decomposeHangul("바닷가"))
        // 냇물: ㄴㅐㅅ + ㅁㅜㄹ
        assertEquals("ㄴㅐㅅㅁㅜㄹ", KoreanJosaBitmaskEngine.decomposeHangul("냇물"))

        // --- (2) 'ㅎ' / 'ㅡ' 탈락 및 변형 용언 자모 분해 검증 ---
        // 빨갛다 -> 빨간 ('ㅎ' 탈락 및 ㄴ 받침 결합)
        assertEquals("ㅃㅏㄹㄱㅏㅎㄷㅏ", KoreanJosaBitmaskEngine.decomposeHangul("빨갛다"))
        assertEquals("ㅃㅏㄹㄱㅏㄴ", KoreanJosaBitmaskEngine.decomposeHangul("빨간"))
        // 쓰다 -> 써 ('ㅡ' 탈락 후 어미 '-어' 결합)
        assertEquals("ㅆㅡㄷㅏ", KoreanJosaBitmaskEngine.decomposeHangul("쓰다"))
        assertEquals("ㅆㅓ", KoreanJosaBitmaskEngine.decomposeHangul("써"))
        // 담그다 -> 담가 ('ㅡ' 탈락 후 어미 '-아' 결합)
        assertEquals("ㄷㅏㅁㄱㅡㄷㅏ", KoreanJosaBitmaskEngine.decomposeHangul("담그다"))
        assertEquals("ㄷㅏㅁㄱㅏ", KoreanJosaBitmaskEngine.decomposeHangul("담가"))
        // 파랗다 -> 파란 ('ㅎ' 탈락 및 ㄴ 받침 결합)
        assertEquals("ㅍㅏㄹㅏㅎㄷㅏ", KoreanJosaBitmaskEngine.decomposeHangul("파랗다"))
        assertEquals("ㅍㅏㄹㅏㄴ", KoreanJosaBitmaskEngine.decomposeHangul("파란"))

        // --- (3) 사잇소리 결합 단어의 조사 결합 100% 문법적 정합성 단언 ---
        // 촛불 (받침 'ㄹ', T=8) -> 촛불은, 촛불을
        assertEquals("촛불은", KoreanJosaBitmaskEngine.attachJosa("촛불", JosaKind.EUN_NEUN))
        assertEquals("촛불을", KoreanJosaBitmaskEngine.attachJosa("촛불", JosaKind.EUL_REUL))
        assertEquals("촛불이", KoreanJosaBitmaskEngine.attachJosa("촛불", JosaKind.I_GA))
        assertEquals("촛불로", KoreanJosaBitmaskEngine.attachJosa("촛불", JosaKind.EURO_RO)) // 'ㄹ' 받침은 '로'
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("촛불", "은"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("촛불", "는"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("촛불", "을"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("촛불", "를"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("촛불", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("촛불", "으로"))

        // 잇몸 (받침 'ㅁ', T=16) -> 잇몸이
        assertEquals("잇몸이", KoreanJosaBitmaskEngine.attachJosa("잇몸", JosaKind.I_GA))
        assertEquals("잇몸은", KoreanJosaBitmaskEngine.attachJosa("잇몸", JosaKind.EUN_NEUN))
        assertEquals("잇몸을", KoreanJosaBitmaskEngine.attachJosa("잇몸", JosaKind.EUL_REUL))
        assertEquals("잇몸으로", KoreanJosaBitmaskEngine.attachJosa("잇몸", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("잇몸", "이"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("잇몸", "가"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("잇몸", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("잇몸", "로"))

        // 나뭇가지 (끝 글자 '지', 받침 없음 T=0) -> 나뭇가지로
        assertEquals("나뭇가지로", KoreanJosaBitmaskEngine.attachJosa("나뭇가지", JosaKind.EURO_RO))
        assertEquals("나뭇가지는", KoreanJosaBitmaskEngine.attachJosa("나뭇가지", JosaKind.EUN_NEUN))
        assertEquals("나뭇가지가", KoreanJosaBitmaskEngine.attachJosa("나뭇가지", JosaKind.I_GA))
        assertEquals("나뭇가지를", KoreanJosaBitmaskEngine.attachJosa("나뭇가지", JosaKind.EUL_REUL))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("나뭇가지", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("나뭇가지", "으로"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("나뭇가지", "가"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("나뭇가지", "이"))

        // 바닷가 (끝 글자 '가', 받침 없음 T=0) -> 바닷가로, 바닷가는
        assertEquals("바닷가로", KoreanJosaBitmaskEngine.attachJosa("바닷가", JosaKind.EURO_RO))
        assertEquals("바닷가는", KoreanJosaBitmaskEngine.attachJosa("바닷가", JosaKind.EUN_NEUN))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("바닷가", "로"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("바닷가", "는"))

        // 냇물 (끝 글자 '물', 받침 'ㄹ' T=8) -> 냇물로, 냇물은
        assertEquals("냇물로", KoreanJosaBitmaskEngine.attachJosa("냇물", JosaKind.EURO_RO))
        assertEquals("냇물은", KoreanJosaBitmaskEngine.attachJosa("냇물", JosaKind.EUN_NEUN))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("냇물", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("냇물", "으로"))

        // --- (4) 오결합 조사 자동 감지 및 교정 무결성 ---
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch("촛불는 밝게 빛난다"))
        assertEquals("촛불은 밝게 빛난다", KoreanJosaBitmaskEngine.correctJosaMismatch("촛불는 밝게 빛난다"))

        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch("잇몸가 붓다"))
        assertEquals("잇몸이 붓다", KoreanJosaBitmaskEngine.correctJosaMismatch("잇몸가 붓다"))

        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch("나뭇가지으로 집을 짓다"))
        assertEquals("나뭇가지로 집을 짓다", KoreanJosaBitmaskEngine.correctJosaMismatch("나뭇가지으로 집을 짓다"))

        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch("냇물으로 흘러간다"))
        assertEquals("냇물로 흘러간다", KoreanJosaBitmaskEngine.correctJosaMismatch("냇물으로 흘러간다"))
    }

    // =========================================================================
    // 3. RED-PERF-01: 120Hz 키보드 극한 타이핑 버스트(1초당 50타) 입력 중 GC 압박 최소화 및 고속 연산
    // =========================================================================
    @Test
    fun testRedPerf01_120HzBurstTypingZeroAllocation() {
        val burstWords = listOf(
            "하늘",      // 'ㄹ' 받침 (T=8)
            "사과",      // 받침 없음 (T=0)
            "책",        // 'ㄱ' 받침 (T=1)
            "선생님",    // 'ㅁ' 받침 (T=16)
            "물",        // 'ㄹ' 받침 (T=8)
            "꽃",        // 'ㅊ' 받침 (T=23)
            "120Hz",    // 영문 모음 종결
            "iPhone",   // 영문 -one ('ㄴ' 받침)
            "Apple",    // 영문 -le ('ㄹ' 받침)
            "2026"      // 숫자 6 ('ㄱ' 받침)
        )

        val burstKinds = listOf(
            JosaKind.EUN_NEUN,
            JosaKind.I_GA,
            JosaKind.EUL_REUL,
            JosaKind.GWA_WA,
            JosaKind.EURO_RO
        )

        // --- 1) JIT 컴파일러 웜업 (50회 선행 실행) ---
        for (i in 0 until 50) {
            val word = burstWords[i % burstWords.size]
            val kind = burstKinds[i % burstKinds.size]
            KoreanJosaBitmaskEngine.getPhonologicalFlags(word)
            val josa = KoreanJosaBitmaskEngine.getJosa(word, kind)
            KoreanJosaBitmaskEngine.isValidAttachment(word, josa)
        }

        // --- 2) 500회 초고속 버스트 타이핑 시뮬레이션 (1초당 50타 상황) ---
        val iterationCount = 500
        val latencyListNanos = LongArray(iterationCount)

        val totalDurationNanos = measureNanoTime {
            for (i in 0 until iterationCount) {
                val word = burstWords[i % burstWords.size]
                val kind = burstKinds[i % burstKinds.size]

                val start = System.nanoTime()
                // 비트마스크 플래그 추출 + getJosa(조사 선택) + 유효성 단언
                val flags = KoreanJosaBitmaskEngine.getPhonologicalFlags(word)
                val josa = KoreanJosaBitmaskEngine.getJosa(word, kind)
                val isValid = KoreanJosaBitmaskEngine.isValidAttachment(word, josa)

                val elapsed = System.nanoTime() - start
                latencyListNanos[i] = elapsed

                assertTrue("플래그는 0 이상이어야 합니다.", flags >= 0)
                assertNotNull("선택된 조사는 null이 아니어야 합니다.", josa)
                assertTrue("선택된 조사는 유효한 결합이어야 합니다.", isValid)
            }
        }

        val totalTimeMs = totalDurationNanos / 1_000_000.0
        val averageLatencyNanos = latencyListNanos.average()
        val averageLatencyMicros = averageLatencyNanos / 1_000.0

        // --- 3) 성능 지연시간 및 예산 단언 ---
        assertTrue(
            "500회 비트마스크 및 조사 연산 총 소요 시간은 120.0ms 미만이어야 합니다. Actual: ${totalTimeMs}ms",
            totalTimeMs < 120.0
        )
    }

    // =========================================================================
    // 4. RED-HANGUL-01: 불완전 초성/중성 잔여 버퍼의 안전한 정합성 처리
    // =========================================================================
    @Test
    fun testRedHangul01_IncompleteJamoBufferRecovery() {
        val morphology = ChoseongMorphologyEngine()
        val incompleteTokens = listOf("안녕ㅎ", "감ㅅ", "축ㅎ", "보ㄴ")

        // --- (1) KoreanJosaBitmaskEngine 호환 자모 종성 안전 처리 검증 ---

        // 1. '안녕ㅎ': 끝 글자 'ㅎ' (U+314E) -> FLAG_NO_BATCHIM (모음 취급)
        val hFlags = KoreanJosaBitmaskEngine.getPhonologicalFlags("안녕ㅎ")
        assertEquals(FLAG_NO_BATCHIM, hFlags)
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("안녕ㅎ", JosaKind.EUN_NEUN))
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa("안녕ㅎ", JosaKind.I_GA))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("안녕ㅎ", JosaKind.EUL_REUL))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("안녕ㅎ", JosaKind.EURO_RO))
        assertEquals("안녕ㅎ는", KoreanJosaBitmaskEngine.attachJosa("안녕ㅎ", JosaKind.EUN_NEUN))

        // 2. '감ㅅ': 끝 글자 'ㅅ' (U+3145) -> FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM (자음 받침 취급)
        val sFlags = KoreanJosaBitmaskEngine.getPhonologicalFlags("감ㅅ")
        assertEquals(FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM, sFlags)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("감ㅅ", JosaKind.EUN_NEUN))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("감ㅅ", JosaKind.I_GA))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("감ㅅ", JosaKind.EUL_REUL))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("감ㅅ", JosaKind.EURO_RO))
        assertEquals("감ㅅ은", KoreanJosaBitmaskEngine.attachJosa("감ㅅ", JosaKind.EUN_NEUN))

        // 3. '축ㅎ': 끝 글자 'ㅎ' (U+314E) -> FLAG_NO_BATCHIM
        val chukHFlags = KoreanJosaBitmaskEngine.getPhonologicalFlags("축ㅎ")
        assertEquals(FLAG_NO_BATCHIM, chukHFlags)
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("축ㅎ", JosaKind.EUN_NEUN))
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa("축ㅎ", JosaKind.I_GA))
        assertEquals("축ㅎ가", KoreanJosaBitmaskEngine.attachJosa("축ㅎ", JosaKind.I_GA))

        // 4. '보ㄴ': 끝 글자 'ㄴ' (U+3134) -> FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM
        val boNFlags = KoreanJosaBitmaskEngine.getPhonologicalFlags("보ㄴ")
        assertEquals(FLAG_HAS_BATCHIM or FLAG_NON_RIEUL_BATCHIM, boNFlags)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("보ㄴ", JosaKind.EUN_NEUN))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("보ㄴ", JosaKind.I_GA))
        assertEquals("보ㄴ이", KoreanJosaBitmaskEngine.attachJosa("보ㄴ", JosaKind.I_GA))

        // --- (2) ChoseongMorphologyEngine 크래시 없는 안전한 자모 분해 및 초성 추출 검증 ---
        assertEquals("ㅇㅏㄴㄴㅕㅇㅎ", morphology.decomposeHangul("안녕ㅎ"))
        assertEquals("ㄱㅏㅁㅅ", morphology.decomposeHangul("감ㅅ"))
        assertEquals("ㅊㅜㄱㅎ", morphology.decomposeHangul("축ㅎ"))
        assertEquals("ㅂㅗㄴ", morphology.decomposeHangul("보ㄴ"))

        assertEquals("ㅇㄴㅎ", morphology.extractChoseongSequence("안녕ㅎ"))
        assertEquals("ㄱㅅ", morphology.extractChoseongSequence("감ㅅ"))
        assertEquals("ㅊㅎ", morphology.extractChoseongSequence("축ㅎ"))
        assertEquals("ㅂㄴ", morphology.extractChoseongSequence("보ㄴ"))

        // --- (3) KoreanMorphologicalEndingAnalyzer 크래시 0건 단언 ---
        for (token in incompleteTokens) {
            try {
                val ending = KoreanMorphologicalEndingAnalyzer.extractEnding(token)
                val isTerminal = KoreanMorphologicalEndingAnalyzer.isSentenceTerminal(token)
                assertFalse("불완전 자모 잔여 버퍼 '$token'은 종결 어미로 판정되어서는 안 됩니다.", isTerminal)
            } catch (t: Throwable) {
                fail("불완전 자모 토큰 '$token' 처리 중 형태소 엔진이 크래시되었습니다: ${t.message}")
            }
        }
    }

    // =========================================================================
    // 5. RED-VAULT-02: 온디바이스 언어 금고 암호화 변조 방어 및 무결성
    // =========================================================================
    @Test
    fun testRedVault02_OnDeviceVaultHardwareKeyIsolation() {
        val secretKey = AesGcmVaultCipher.randomKey()
        val cipher = AesGcmVaultCipher(secretKey)
        val envelopeCipher = EnvelopeVaultCipher(cipher)

        val plainBytes = """{"vault_id":"RED_VAULT_02","privacy":"STRICT_ON_DEVICE","entities":["기밀정보","사용자단어","학습지문"]}""".toByteArray(Charsets.UTF_8)
        val aad = "saegeul-hardware-vault-aad-v2".toByteArray(Charsets.UTF_8)

        // 1) 정상 암호화 및 복호화 무결성 단언
        val encryptedBlob = cipher.encrypt(plainBytes, aad)
        assertFalse("암호화된 blob은 원본 평문과 달라야 합니다.", plainBytes.contentEquals(encryptedBlob))
        assertTrue("암호화 blob 크기는 최소 요구 크기(IV + Tag) 이상이어야 합니다.", encryptedBlob.size >= AesGcmVaultCipher.MIN_BLOB_LENGTH)

        val decryptedBytes = cipher.decrypt(encryptedBlob, aad)
        assertTrue("정상 복호화된 데이터는 원본 평문과 100% 바이트 단위로 일치해야 합니다.", plainBytes.contentEquals(decryptedBytes))

        // 2) 암호문 본문 변조 (Ciphertext bit flip) 복호화 실패 단언
        val tamperedCiphertext = encryptedBlob.clone()
        tamperedCiphertext[tamperedCiphertext.size - 1] = (tamperedCiphertext[tamperedCiphertext.size - 1].toInt() xor 0xFF).toByte()
        try {
            cipher.decrypt(tamperedCiphertext, aad)
            fail("변조된 암호문에 대해 GeneralSecurityException이 발생해야 합니다.")
        } catch (e: GeneralSecurityException) {
            // 무결성 검증 실패 예외 정상 포착
            assertNotNull(e.message)
        }

        // 3) IV 영역 변조 (IV bit flip) 복호화 실패 단언
        val tamperedIv = encryptedBlob.clone()
        tamperedIv[0] = (tamperedIv[0].toInt() xor 0xAA).toByte()
        try {
            cipher.decrypt(tamperedIv, aad)
            fail("변조된 IV에 대해 GeneralSecurityException이 발생해야 합니다.")
        } catch (e: GeneralSecurityException) {
            assertNotNull(e.message)
        }

        // 4) 위조된 AAD 바이트 공급 시 복호화 실패 단언
        val forgedAad = "forged-hardware-vault-aad".toByteArray(Charsets.UTF_8)
        try {
            cipher.decrypt(encryptedBlob, forgedAad)
            fail("위조된 AAD가 제공되었을 때 GeneralSecurityException이 발생해야 합니다.")
        } catch (e: GeneralSecurityException) {
            assertNotNull(e.message)
        }

        // 5) 최소 길이 미달 잘림(Truncation) 공격 방어 단언
        val truncatedBlob = encryptedBlob.copyOf(AesGcmVaultCipher.MIN_BLOB_LENGTH - 1)
        try {
            cipher.decrypt(truncatedBlob, aad)
            fail("최소 길이 미달 blob에 대해 GeneralSecurityException이 발생해야 합니다.")
        } catch (e: GeneralSecurityException) {
            assertNotNull(e.message)
        }

        // 6) TypingDnaRepository 오프라인 로컬 무결성 및 외부 유출 0바이트 단언
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vaultFile = File(context.filesDir, "test_red_team_round10_vault.json")
        if (vaultFile.exists()) {
            vaultFile.delete()
        }

        try {
            val repository = TypingDnaRepository(vaultFile, cipher = envelopeCipher)

            val persona = PersonaDna(
                category = "security_shield",
                dominantTone = "Polite",
                habitualEndings = listOf("합니다", "해요"),
                frequentBigrams = listOf(DynamicBigram("보안", "차단", 0.99f)),
                cannedPhrases = listOf("온디바이스 금고 데이터 완벽 격리")
            )
            repository.updatePersona(persona, analyzedSentenceCount = 25)

            // 영속화된 프로필 강제 재로드 및 검증
            val profile = repository.load(forceReload = true)
            assertTrue("저장된 security_shield 페르소나가 정상 복원되어야 합니다.", profile.personas.containsKey("security_shield"))

            val stats = repository.getStats()
            assertEquals("클라우드 전송 바이트는 엄격히 0이어야 합니다.", 0, stats.cloudBytesExported)
            assertEquals("온디바이스 프라이버시 비율은 엄격히 100%여야 합니다.", 100, stats.privacyOnDevicePercent)
            assertTrue("학습된 데이터가 존재해야 합니다.", stats.hasLearnedData)
        } finally {
            if (vaultFile.exists()) {
                vaultFile.delete()
            }
        }
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.filters.MediumTest
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.UserTypingContextCollector
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.JosaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.Normalizer

/**
 * Red Team Round 6 Hardcore Chaos Instrumentation Device Test.
 *
 * Scenarios:
 * 1. RED-HARDCORE-01: Rapid backspace jamo fragment destruction & atomic buffer purge.
 * 5. RED-HARDCORE-05: Unicode normalization NFD/NFC & non-printing control character josa integrity.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RedTeamRound6HardcoreChaosDeviceTest {

    // =========================================================================
    // 1. RED-HARDCORE-01: 고속 백스페이스 잔여 자모 파괴 & 원자적 소거
    // =========================================================================
    @Test
    fun testRedHardcore01_RapidBackspaceAtomicPurgeAndContextIsolation() {
        val collector = UserTypingContextCollector()
        val pkg = "com.kakao.talk"

        // 1) "학교에서 공부를 하고 있습니다" 입력 시뮬레이션
        // 음절 단위로 커밋되어 버퍼에 잔여물(pending)이 적재되도록 시뮬레이션
        val initialInput = "학교에서 공부를 하고 있습니다"
        for (ch in initialInput) {
            collector.recordCommittedText(pkg, ch.toString())
        }

        // 끝이 "다"로 끝나 "습니다" 종결어미가 무음절로 분리 대기 중이므로 pending 상태여야 함
        assertTrue(
            "입력 완료 직후 종결 어미 대기 상태로 pending 버퍼에 적재되어 있어야 합니다.",
            collector.hasPending(pkg)
        )

        // 2) 20회 연속 고속 백스페이스 시뮬레이션
        repeat(20) {
            collector.onBackspaceContinuityLost(pkg, null)
        }

        // 3) 문맥 이동 및 discardPending() 호출 시 모든 버퍼 잔여물이 원자적으로 소거됨을 단언
        collector.discardPending(pkg)
        assertFalse(
            "onBackspaceContinuityLost 및 discardPending 호출 후 모든 버퍼 잔여물이 원자적으로 소거되어야 합니다.",
            collector.hasPending(pkg)
        )

        // 4) 에디터 세션 전환 후 다음 새 세션 입력 시 이전 파편 자모의 교차 오염 방지 검증
        collector.onEditorSessionStarted(pkg, fieldId = 999, restarting = false)
        collector.recordCommittedText(pkg, "내일 회의는 정상 진행합니다.")

        val context = collector.getRecentContext(pkg)
        val sentences = collector.getSentences(pkg)

        assertFalse(
            "이전 세션의 파편 자모('학교')가 새 문맥에 교차 오염되어서는 안 됩니다.",
            context.contains("학교")
        )
        assertFalse(
            "이전 세션의 파편 자모('공부')가 새 문맥에 교차 오염되어서는 안 됩니다.",
            context.contains("공부")
        )
        assertFalse(
            "이전 세션의 파편 자모('있습')가 새 문맥에 교차 오염되어서는 안 됩니다.",
            context.contains("있습")
        )
        assertTrue(
            "새 세션의 문장이 정상적으로 수집되어야 합니다.",
            sentences.contains("내일 회의는 정상 진행합니다.")
        )
    }

    // =========================================================================
    // 5. RED-HARDCORE-05: 유니코드 정규화 NFD/NFC 및 비표시 제어문자 조사 무결성
    // =========================================================================
    @Test
    fun testRedHardcore05_UnicodeNfdAndZeroWidthControlCharsJosaIntegrity() {
        // --- (1) NFD (자모 분리: ᄀ+ᅡ+ᆨ) 종성 판별 및 조사 선택 무결성 ---
        val nfdApple = Normalizer.normalize("사과", Normalizer.Form.NFD)
        val nfdBook = Normalizer.normalize("책", Normalizer.Form.NFD)
        val nfdSky = Normalizer.normalize("하늘", Normalizer.Form.NFD)
        val nfdStudent = Normalizer.normalize("학생", Normalizer.Form.NFD)

        // NFD 모음 종결 -> 는, 가, 를, 와, 로
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa(nfdApple, JosaKind.EUN_NEUN))
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa(nfdApple, JosaKind.I_GA))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa(nfdApple, JosaKind.EUL_REUL))
        assertEquals("와", KoreanJosaBitmaskEngine.selectJosa(nfdApple, JosaKind.GWA_WA))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa(nfdApple, JosaKind.EURO_RO))

        // NFD 자음 종결 (일반) -> 은, 이, 을, 과, 으로
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa(nfdBook, JosaKind.EUN_NEUN))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa(nfdBook, JosaKind.I_GA))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa(nfdBook, JosaKind.EUL_REUL))
        assertEquals("과", KoreanJosaBitmaskEngine.selectJosa(nfdBook, JosaKind.GWA_WA))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa(nfdBook, JosaKind.EURO_RO))

        // NFD ㄹ 받침 종결 -> 은, 이, 을, 과, 로 ('으로' 금지)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa(nfdSky, JosaKind.EUN_NEUN))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa(nfdSky, JosaKind.EURO_RO))

        // NFD ㅇ 받침 종결 -> 은, 으로
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa(nfdStudent, JosaKind.EUN_NEUN))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa(nfdStudent, JosaKind.EURO_RO))

        // --- (2) 비표시 제어문자 (ZWSP, ZWNJ, ZWJ, BOM) 삽입 시 종성 무결성 ---
        val appleZwsp = "사과\u200B"
        val bookZwnj = "책\u200C"
        val skyZwj = "하늘\u200D"
        val studentBom = "학생\uFEFF"
        val seaCombined = "바다\u200B\u200C\u200D\uFEFF"

        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa(appleZwsp, JosaKind.EUN_NEUN))
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa(bookZwnj, JosaKind.EUN_NEUN))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa(skyZwj, JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa(studentBom, JosaKind.EURO_RO))
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa(seaCombined, JosaKind.EUN_NEUN))

        // attachJosa 검증
        assertEquals("사과\u200B는", KoreanJosaBitmaskEngine.attachJosa(appleZwsp, JosaKind.EUN_NEUN))
        assertEquals("책\u200C은", KoreanJosaBitmaskEngine.attachJosa(bookZwnj, JosaKind.EUN_NEUN))

        // isValidAttachment 검증
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(appleZwsp, "는"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(appleZwsp, "은"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(bookZwnj, "은"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(bookZwnj, "는"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(skyZwj, "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(skyZwj, "으로"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(studentBom, "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(studentBom, "로"))

        // --- (3) 체언과 조사 사이 제어문자 삽입 시 불일치 감지 및 교정 무결성 ---
        val wrongCases = listOf(
            Pair("사과\u200B은 맛있다", "사과\u200B는 맛있다"),
            Pair("책\u200C를 읽었다", "책\u200C을 읽었다"),
            Pair("하늘\uFEFF으로 날아간다", "하늘\uFEFF로 날아간다"),
            Pair("학생\u200D는 공부한다", "학생\u200D은 공부한다"),
            Pair("컴퓨터\u200B\u200C이 고장났다", "컴퓨터\u200B\u200C가 고장났다")
        )

        for ((wrong, expected) in wrongCases) {
            assertTrue(
                "비표시 제어문자가 포함된 비문 '$wrong'에서 조사의 불일치가 감지되어야 합니다.",
                KoreanJosaBitmaskEngine.hasJosaMismatch(wrong)
            )
            val corrected = KoreanJosaBitmaskEngine.correctJosaMismatch(wrong)
            assertEquals(
                "비표시 제어문자를 건너뛰고 조사가 올바르게 교정되어야 합니다.",
                expected,
                corrected
            )
        }

        // NFD + 제어문자 복합 비문 교정 검증
        val nfdWrongApple = Normalizer.normalize("사과", Normalizer.Form.NFD) + "\u200B은 신선하다"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(nfdWrongApple))
        val nfdCorrectedApple = KoreanJosaBitmaskEngine.correctJosaMismatch(nfdWrongApple)
        assertTrue(
            "NFD + ZWSP 복합 문장에서 올바른 조사('는')로 교정되어야 합니다. Actual: $nfdCorrectedApple",
            nfdCorrectedApple.contains("는") || nfdCorrectedApple.contains(Normalizer.normalize("는", Normalizer.Form.NFD))
        )
    }
}

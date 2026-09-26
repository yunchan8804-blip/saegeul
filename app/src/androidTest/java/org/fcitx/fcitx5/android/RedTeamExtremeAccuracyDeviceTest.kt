/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import androidx.test.filters.MediumTest
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.AiPrediction
import org.fcitx.fcitx5.android.input.ai.ContinuationTone
import org.fcitx.fcitx5.android.input.ai.KoreanSentenceContinuation
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.SentenceRelevanceReranker
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine
import org.fcitx.fcitx5.android.input.ai.phonology.KoreanJosaBitmaskEngine.JosaKind
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter.RuleResult
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter.ViolationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Red Team Extreme Accuracy & Phonology/Syntax Destruction Device Test.
 *
 * Covers Round 1 & 2 exhaustive adversarial scenarios:
 * - RED-PHONO-01: Double batchim (겹받침/복합종성) & 'ㄹ' batchim special rules.
 * - RED-PHONO-02: Loanwords, English acronyms/words, and numeric digit batchim resolution.
 * - RED-SYNTAX-03: Deep syntax violations (ACC-01, ACC-02, ACC-03, ACC-04).
 * - RED-B19-04: Markov chaining pollution suppression on interrogative/negative stems.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class RedTeamExtremeAccuracyDeviceTest {

    // =========================================================================
    // RED-PHONO-01: 겹받침 및 복합 종성 파괴 공격
    // =========================================================================
    @Test
    fun testRedPhono01_DoubleBatchimAndComplexJongseong() {
        // 1. 겹받침 단어들: 값(18 ㅄ), 닭(9 ㄺ), 삶(10 ㄻ), 몫(3 ㄳ), 앉(5 ㄵ), 얹(5 ㄵ), 핥(13 ㄾ), 읊(14 ㄿ), 잃(15 ㅀ)
        val doubleBatchimMap = mapOf(
            "값" to 18,
            "닭" to 9,
            "삶" to 10,
            "몫" to 3,
            "앉" to 5,
            "얹" to 5,
            "핥" to 13,
            "읊" to 14,
            "잃" to 15
        )

        for ((word, expectedCode) in doubleBatchimMap) {
            val char = word.first()
            assertEquals("Jongseong code mismatch for $word", expectedCode, KoreanJosaBitmaskEngine.getBatchimCode(char))
            assertTrue("Expected hasBatchim for $word", KoreanJosaBitmaskEngine.hasBatchim(char))
            assertFalse("겹받침은 순수 'ㄹ'(8) 받침이 아니어야 한다: $word", KoreanJosaBitmaskEngine.isRieulBatchim(char))

            val flags = KoreanJosaBitmaskEngine.getPhonologicalFlags(char)
            assertTrue("FLAG_HAS_BATCHIM must be set for $word", (flags and KoreanJosaBitmaskEngine.FLAG_HAS_BATCHIM) != 0)
            assertTrue("FLAG_NON_RIEUL_BATCHIM must be set for $word", (flags and KoreanJosaBitmaskEngine.FLAG_NON_RIEUL_BATCHIM) != 0)
            assertEquals("FLAG_RIEUL_BATCHIM must NOT be set for $word", 0, flags and KoreanJosaBitmaskEngine.FLAG_RIEUL_BATCHIM)

            // 받침 유무 및 '으로/로' 핵심 규칙: 겹받침은 항상 '으로'(O) vs '로'(X)
            assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.EURO_RO))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "으로"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "로"))

            // 특수 조사: 으로서/로서, 으로써/로써
            assertEquals("으로서", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.EUROSEO_ROSEO))
            assertEquals("으로써", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.EUROSSEO_ROSSEO))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "으로서"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "로서"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "으로써"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "로써"))

            // 특수 조사: 이나/나, 이랑/랑, 이며/며, 아/야
            assertEquals("이나", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.INA_NA))
            assertEquals("이랑", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.IRANG_RANG))
            assertEquals("이며", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.IMYEO_MYEO))
            assertEquals("아", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.A_YA))

            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "이나"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "나"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "이랑"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "랑"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "이며"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "며"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "아"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "야"))
        }

        // 2. 'ㄹ' 순수 받침 단어들: 칼(8), 서울(8), 연필(8), 마을(8)
        val pureRieulWords = listOf("칼", "서울", "연필", "마을")
        for (word in pureRieulWords) {
            val char = word.last()
            assertEquals("Pure 'ㄹ' batchim code must be 8 for $word", 8, KoreanJosaBitmaskEngine.getBatchimCode(char))
            assertTrue(KoreanJosaBitmaskEngine.hasBatchim(char))
            assertTrue(KoreanJosaBitmaskEngine.isRieulBatchim(char))

            val flags = KoreanJosaBitmaskEngine.getPhonologicalFlags(char)
            assertTrue("FLAG_RIEUL_BATCHIM must be set for $word", (flags and KoreanJosaBitmaskEngine.FLAG_RIEUL_BATCHIM) != 0)

            // 'ㄹ' 받침 특수 규칙: '로'(O) vs '으로'(X)
            assertEquals("로", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.EURO_RO))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "로"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "으로"))

            // 특수 조사: 로서(O) vs 으로서(X), 로써(O) vs 으로써(X)
            assertEquals("로서", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.EUROSEO_ROSEO))
            assertEquals("로써", KoreanJosaBitmaskEngine.selectJosa(word, JosaKind.EUROSSEO_ROSSEO))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "로서"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "으로서"))
            assertTrue(KoreanJosaBitmaskEngine.isValidAttachment(word, "로써"))
            assertFalse(KoreanJosaBitmaskEngine.isValidAttachment(word, "으로써"))
        }

        // 3. 구체적 어휘 결합 대비 검증
        // 값으로(O) vs 값로(X)
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("값", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("값", "로"))
        // 닭으로(O) vs 닭로(X)
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("닭", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("닭", "로"))
        // 삶으로(O) vs 삶로(X)
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("삶", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("삶", "로"))
        // 칼로(O) vs 칼으로(X)
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("칼", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("칼", "으로"))
        // 서울로(O) vs 서울으로(X)
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("서울", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("서울", "으로"))
        // 연필로(O) vs 연필으로(X)
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("연필", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("연필", "으로"))
        // 마을로(O) vs 마을으로(X)
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("마을", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("마을", "으로"))
    }

    // =========================================================================
    // RED-PHONO-02: 외래어/영문 및 숫자 종성 판별 공격
    // =========================================================================
    @Test
    fun testRedPhono02_LoanwordsAndNumericPhonology() {
        // 1. 숫자 끝자리 종성 판별
        // 0(영, ㅇ받침) -> 으로
        // 1(일, ㄹ받침) -> 로
        // 2(이, 모음)  -> 로
        // 3(삼, ㅁ받침) -> 으로
        // 4(사, 모음)  -> 로
        // 5(오, 모음)  -> 로
        // 6(육, ㄱ받침) -> 으로
        // 7(칠, ㄹ받침) -> 로
        // 8(팔, ㄹ받침) -> 로
        // 9(구, 모음)  -> 로
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("1", JosaKind.EURO_RO))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("2", JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("3", JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("6", JosaKind.EURO_RO))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("7", JosaKind.EURO_RO))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("8", JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("100", JosaKind.EURO_RO))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("2026년", JosaKind.EURO_RO))

        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("1", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("1", "으로"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("2", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("2", "으로"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("3", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("3", "로"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("6", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("6", "로"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("7", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("7", "으로"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("8", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("8", "으로"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("100", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("100", "로"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("2026년", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("2026년", "로"))

        // 2. 영문 알파벳/단어 종성 판별
        // MacBook -> [맥북] -> [k] 종성 (은, 을, 이, 으로)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("MacBook", JosaKind.EUN_NEUN))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("MacBook", JosaKind.EUL_REUL))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("MacBook", JosaKind.I_GA))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("MacBook", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("MacBook", "은"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("MacBook", "는"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("MacBook", "을"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("MacBook", "를"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("MacBook", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("MacBook", "로"))

        // iPhone -> [아이폰] -> silent 'e' with [n] 종성 (은, 을, 이, 으로)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("iPhone", JosaKind.EUN_NEUN))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("iPhone", JosaKind.EUL_REUL))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("iPhone", JosaKind.I_GA))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("iPhone", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("iPhone", "은"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("iPhone", "는"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("iPhone", "을"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("iPhone", "를"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("iPhone", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("iPhone", "로"))

        // AI -> [에이아이] -> 모음 종결 (는, 를, 가, 로)
        assertEquals("는", KoreanJosaBitmaskEngine.selectJosa("AI", JosaKind.EUN_NEUN))
        assertEquals("를", KoreanJosaBitmaskEngine.selectJosa("AI", JosaKind.EUL_REUL))
        assertEquals("가", KoreanJosaBitmaskEngine.selectJosa("AI", JosaKind.I_GA))
        assertEquals("로", KoreanJosaBitmaskEngine.selectJosa("AI", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("AI", "는"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("AI", "은"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("AI", "를"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("AI", "을"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("AI", "로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("AI", "으로"))

        // App -> [앱] -> [p] 종성 (은, 을, 이, 으로)
        assertEquals("은", KoreanJosaBitmaskEngine.selectJosa("App", JosaKind.EUN_NEUN))
        assertEquals("을", KoreanJosaBitmaskEngine.selectJosa("App", JosaKind.EUL_REUL))
        assertEquals("이", KoreanJosaBitmaskEngine.selectJosa("App", JosaKind.I_GA))
        assertEquals("으로", KoreanJosaBitmaskEngine.selectJosa("App", JosaKind.EURO_RO))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("App", "은"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("App", "는"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("App", "을"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("App", "를"))
        assertTrue(KoreanJosaBitmaskEngine.isValidAttachment("App", "으로"))
        assertFalse(KoreanJosaBitmaskEngine.isValidAttachment("App", "로"))

        // 3. 영문/외래어 문맥 오결합 자동 감지 및 교정
        val wrongSentence1 = "MacBook는 가볍고 iPhone를 충전 중이다"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(wrongSentence1))
        val corrected1 = KoreanJosaBitmaskEngine.correctJosaMismatch(wrongSentence1)
        assertEquals("MacBook은 가볍고 iPhone을 충전 중이다", corrected1)
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch(corrected1))

        val wrongSentence2 = "AI은 빠르게 발전하고 새로운 App를 다운로드했다"
        assertTrue(KoreanJosaBitmaskEngine.hasJosaMismatch(wrongSentence2))
        val corrected2 = KoreanJosaBitmaskEngine.correctJosaMismatch(wrongSentence2)
        assertEquals("AI는 빠르게 발전하고 새로운 App을 다운로드했다", corrected2)
        assertFalse(KoreanJosaBitmaskEngine.hasJosaMismatch(corrected2))
    }

    // =========================================================================
    // RED-SYNTAX-03: ACC-01~04 심화 통사 파괴 공격
    // =========================================================================
    @Test
    fun testRedSyntax03_DeepSyntaxAttacksBlocked() {
        val filter = KoreanSyntaxRuleFilter()

        // 1. 중첩 문맥 및 ACC-01 심화 비문 차단
        val acc01Attacks = listOf(
            "회의가 길어져서 지금 바로 가야 하나요?",
            "차가 너무 막혀서 조심히 오세요",
            "밥을 급하게 먹느라고 체했어?",
            "비가 와서 조심히 오십시오",
            "늦어져서 어떡하죠?"
        )
        for (sentence in acc01Attacks) {
            val result = filter.check(sentence)
            assertTrue("Expected ACC-01 violation for: '$sentence'", result is RuleResult.Invalid)
            assertEquals(ViolationType.ACC_01_CAUSAL_SUBORDINATION, (result as RuleResult.Invalid).violationType)
            assertFalse(filter.isValid(sentence))
            assertFalse(KoreanSyntaxRuleFilter.isGrammaticallySound(sentence))
        }

        // 2. ACC-02 심화 목적어+자동사 술어 비문 차단
        val acc02Attacks = listOf(
            "감사한 마음을 너무나 고마워요",
            "선생님의 큰 은혜를 진심으로 감사해요",
            "따뜻한 배려를 정말로 고마워요",
            "소중한 도움을 무척이나 감사드립니다"
        )
        for (sentence in acc02Attacks) {
            val result = filter.check(sentence)
            assertTrue("Expected ACC-02 violation for: '$sentence'", result is RuleResult.Invalid)
            assertEquals(ViolationType.ACC_02_INTRANSITIVE_OBJECT, (result as RuleResult.Invalid).violationType)
            assertFalse(filter.isValid(sentence))
            assertFalse(KoreanSyntaxRuleFilter.isGrammaticallySound(sentence))
        }

        // 3. ACC-04 반말+존댓말 중첩 비문 차단
        val acc04Attacks = listOf(
            "안녕하십니까 선배님! 밥 먹었어? 내일 뵐게요.",
            "안녕하세요 선배님! 밥 먹었어? 내일 뵐게요.",
            "안녕하세요. 오늘 회의 참석해? 내일 뵙겠습니다."
        )
        for (sentence in acc04Attacks) {
            val result = filter.check(sentence)
            assertTrue("Expected ACC-04 violation for: '$sentence'", result is RuleResult.Invalid)
            assertEquals(ViolationType.ACC_04_FORMALITY_INCONSISTENCY, (result as RuleResult.Invalid).violationType)
            assertFalse(filter.isValid(sentence))
            assertFalse(KoreanSyntaxRuleFilter.isGrammaticallySound(sentence))
        }

        // 4. 정문 대조군 (모두 정상 통과해야 함)
        val validSentences = listOf(
            "회의가 길어지니까 지금 바로 가야 하나요?",
            "차가 너무 막히니 조심히 오세요.",
            "밥을 급하게 먹어서 체했습니다.",
            "감사한 마음에 너무나 고마워요.",
            "선생님의 큰 은혜에 진심으로 감사해요.",
            "안녕하십니까 선배님! 식사하셨습니까? 내일 뵙겠습니다.",
            "안녕 친구야! 밥 먹었어? 내일 봐."
        )
        for (sentence in validSentences) {
            assertTrue("Expected valid sentence for: '$sentence'", filter.isValid(sentence))
            assertTrue("Expected grammatically sound for: '$sentence'", KoreanSyntaxRuleFilter.isGrammaticallySound(sentence))
        }
    }

    // =========================================================================
    // RED-B19-04: B19 의문사/부정사 마르코프 오염 공격
    // =========================================================================
    @Test
    fun testRedB1904_InterrogativeMarkovPollutionSuppression() {
        val packageName = "net.chanpaca.saegeul.test"
        val ngram = PersonalNgramModel(clock = { 1_000_000_000L })

        // 고의로 B19 유발용 비문 합성 패턴 및 HADA_NOUNS를 강력하게 주입
        ngram.learn("내가 뭘 회의 참석합니다", packageName)
        ngram.learn("누가 언제 회의합니다", packageName)
        ngram.learn("어디서 무엇을 진행합니다", packageName)
        ngram.learn("왜 자꾸 회의합니다", packageName)
        ngram.learn("회의 참석합니다", packageName)
        ngram.learn("회의합니다", packageName)
        ngram.learn("진행합니다", packageName)
        ngram.learn("회의", packageName)
        ngram.learn("참석", packageName)
        ngram.learn("진행", packageName)
        ngram.learn("준비", packageName)

        val engine = KoreanSentenceContinuation(ngram = ngram)

        val attackPrefixes = listOf(
            listOf("내가", "뭘"),
            listOf("누가", "언제"),
            listOf("어디서", "무엇을"),
            listOf("왜", "자꾸")
        )

        for (prefix in attackPrefixes) {
            val results = engine.continuations(
                contextTail = prefix,
                tone = ContinuationTone.Honorific,
                packageName = packageName,
                limit = 10
            )

            // 무분별한 HADA_NOUNS 합성 비문이 일절 생성되지 않는지 검증
            for (cand in results) {
                assertFalse("B19 결함: 의문사 뒤 무분별한 '회의' 합성 배제 ($cand)", cand.contains("회의"))
                assertFalse("B19 결함: 의문사 뒤 무분별한 '참석' 합성 배제 ($cand)", cand.contains("참석"))
                assertFalse("B19 결함: 의문사 뒤 무분별한 '진행' 합성 배제 ($cand)", cand.contains("진행"))
                assertFalse("B19 결함: 의문사 뒤 무분별한 '준비' 합성 배제 ($cand)", cand.contains("준비"))
                assertTrue("생성된 후보는 문법적으로 올바라야 함 ($cand)", KoreanSyntaxRuleFilter.isGrammaticallySound(cand))
                assertFalse("조사 불일치가 없어야 함 ($cand)", KoreanJosaBitmaskEngine.hasJosaMismatch(cand))
            }

            // cold-start (학습 없는 깨끗한 상태)에서도 동일 검증
            val coldEngine = KoreanSentenceContinuation()
            val coldResults = coldEngine.continuations(
                contextTail = prefix,
                tone = ContinuationTone.Honorific,
                packageName = packageName,
                limit = 10
            )
            for (cand in coldResults) {
                assertFalse("Cold-start B19 결함: '회의' 합성 배제 ($cand)", cand.contains("회의"))
                assertFalse("Cold-start B19 결함: '참석' 합성 배제 ($cand)", cand.contains("참석"))
                assertTrue(KoreanSyntaxRuleFilter.isGrammaticallySound(cand))
            }
        }
    }

    // =========================================================================
    // SentenceRelevanceReranker 통합 극한 차단 검증
    // =========================================================================
    @Test
    fun testRerankerComprehensiveRedTeamFiltering() {
        val packageName = "net.chanpaca.saegeul.test"
        val context = "오늘 회의 일정"

        val candidates = listOf(
            // RED-PHONO 조사 불일치 후보
            AiPrediction("오늘 회의 일정에 MacBook는 필수입니다", 0.95f, true),
            AiPrediction("오늘 회의 일정에 iPhone를 지참하세요", 0.92f, true),
            AiPrediction("오늘 회의 일정에 AI은 활용됩니다", 0.90f, true),
            AiPrediction("오늘 회의 일정에 App를 실행하세요", 0.88f, true),
            AiPrediction("오늘 회의 일정에 값로 환산할 수 없습니다", 0.86f, true),
            AiPrediction("오늘 회의 일정에 서울으로 이동합니다", 0.84f, true),

            // RED-SYNTAX 비문 후보
            AiPrediction("오늘 회의 일정이 길어져서 지금 바로 가야 하나요?", 0.82f, true),
            AiPrediction("오늘 회의 일정 차가 너무 막혀서 조심히 오세요", 0.80f, true),
            AiPrediction("오늘 회의 일정 전 밥을 급하게 먹느라고 체했어?", 0.78f, true),
            AiPrediction("오늘 회의 일정을 감사한 마음을 너무나 고마워요", 0.76f, true),
            AiPrediction("선생님의 큰 은혜를 진심으로 감사해요", 0.74f, true),
            AiPrediction("안녕하십니까 선배님! 밥 먹었어? 내일 뵐게요.", 0.72f, true),

            // 정상 후보군
            AiPrediction("오늘 회의 일정 공유해 드립니다", 0.70f, true),
            AiPrediction("오늘 회의 일정 확인했습니다", 0.68f, true),
            AiPrediction("오늘 회의 일정에 MacBook은 필수입니다", 0.66f, true),
            AiPrediction("오늘 회의 일정에 iPhone을 지참하세요", 0.64f, true),
            AiPrediction("오늘 회의 일정에 서울로 이동합니다", 0.62f, true)
        )

        val filtered = SentenceRelevanceReranker.rerank(
            sentences = candidates,
            contextBeforeCursor = context,
            ngram = null,
            packageName = packageName,
            limit = 20
        )

        // 모든 레드팀 공격 시나리오 비문 후보가 100% 차단되었는지 단언
        assertFalse("MacBook는 불일치는 차단되어야 한다", filtered.any { it.text.contains("MacBook는") })
        assertFalse("iPhone를 불일치는 차단되어야 한다", filtered.any { it.text.contains("iPhone를") })
        assertFalse("AI은 불일치는 차단되어야 한다", filtered.any { it.text.contains("AI은") })
        assertFalse("App를 불일치는 차단되어야 한다", filtered.any { it.text.contains("App를") })
        assertFalse("값로 불일치는 차단되어야 한다", filtered.any { it.text.contains("값로") })
        assertFalse("서울으로 불일치는 차단되어야 한다", filtered.any { it.text.contains("서울으로") })

        assertFalse("ACC-01 회의가 길어져서 지금 바로 가야 하나요? 비문 차단", filtered.any { it.text.contains("가야 하나요") })
        assertFalse("ACC-01 차가 너무 막혀서 조심히 오세요 비문 차단", filtered.any { it.text.contains("조심히 오세요") })
        assertFalse("ACC-01 밥을 급하게 먹느라고 체했어? 비문 차단", filtered.any { it.text.contains("체했어") })
        assertFalse("ACC-02 너무나 고마워요 비문 차단", filtered.any { it.text.contains("너무나 고마워요") })
        assertFalse("ACC-02 은혜를 진심으로 감사해요 비문 차단", filtered.any { it.text.contains("진심으로 감사해요") })
        assertFalse("ACC-04 반말+존댓말 혼용 비문 차단", filtered.any { it.text.contains("밥 먹었어") })

        // 잔여 후보는 모두 문법적/음운적으로 흠결이 없어야 함
        for (pred in filtered) {
            assertTrue("잔여 후보 문법 정합성: ${pred.text}", KoreanSyntaxRuleFilter.isGrammaticallySound(pred.text))
            assertFalse("잔여 후보 조사 정합성: ${pred.text}", KoreanJosaBitmaskEngine.hasJosaMismatch(pred.text))
        }

        // 정상 후보 보존 검증
        assertTrue("정상 후보 '공유해 드립니다' 보존", filtered.any { it.text.contains("공유해 드립니다") })
        assertTrue("정상 후보 '확인했습니다' 보존", filtered.any { it.text.contains("확인했습니다") })
        assertTrue("정상 후보 'MacBook은' 보존", filtered.any { it.text.contains("MacBook은") })
        assertTrue("정상 후보 'iPhone을' 보존", filtered.any { it.text.contains("iPhone을") })
        assertTrue("정상 후보 '서울로' 보존", filtered.any { it.text.contains("서울로") })
    }
}

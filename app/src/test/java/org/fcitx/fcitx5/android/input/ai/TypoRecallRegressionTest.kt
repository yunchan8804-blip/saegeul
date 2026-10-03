/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.prediction.source.KeyboardTypoCorrectionSource
import org.fcitx.fcitx5.android.input.ai.prediction.source.PredictionInput
import org.fcitx.fcitx5.android.input.ai.rule.SuggestionQualityGate
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.DubeolsikKeyMap
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.fcitx.fcitx5.android.input.ai.typo.SingleEditTypoProbe
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 한글 오타 교정 후보("✏️")가 [AiContextualPredictor.predict]까지 살아남는 비율을 오타 유형별로
 * 재고 지키는 회귀 테스트. 실제 번들 어휘(ko_base_vocab.tsv)로 프로덕션과 같은 교정기를 채우고,
 * 조합 중 stroke 입력과 스페이스 뒤 직전 어절 입력을 각각 문맥 없음/있음으로 돌려 단계별 탈락 지점을 표로 찍는다.
 * 전체 recall 하한, 단일 편집 탐색기가 겨냥한 사례의 단어줄 상위 4 진입, 정상 입력의 ✏️ 오탐 0건을 단언한다.
 */
class TypoRecallRegressionTest {

    private enum class Mode { STROKE, AFTER_SPACE }

    private class Variant(val name: String, val mode: Mode, val contextPrefix: String)

    private class Case(val type: String, val typed: String, val expected: String)

    private class Outcome(
        val variant: Variant,
        val wordRank: Int,
        val foundSource: String?,
        val foundBadge: String?,
        val stage: String,
        val finalWords: List<String>
    ) {
        val found: Boolean get() = wordRank > 0
        val typoBadge: Boolean get() = found && foundBadge == "✏️"
        val inTop4: Boolean get() = wordRank in 1..4
    }

    private lateinit var vocabulary: BaseKoreanVocabulary
    private lateinit var typoCorrector: KeyboardAwareTypoCorrector
    private lateinit var correctionStore: CorrectionPatternStore
    private lateinit var ngram: PersonalNgramModel
    private lateinit var predictor: AiContextualPredictor
    private lateinit var keyboardSource: KeyboardTypoCorrectionSource

    private val pkg = "com.test.app"

    private val variants = listOf(
        Variant("A0 stroke/문맥없음", Mode.STROKE, ""),
        Variant("A1 stroke/문맥있음", Mode.STROKE, "오늘 "),
        Variant("B0 스페이스뒤/문맥없음", Mode.AFTER_SPACE, ""),
        Variant("B1 스페이스뒤/문맥있음", Mode.AFTER_SPACE, "오늘 ")
    )

    private val cases: List<Case> = buildList {
        fun add(type: String, vararg pairs: Pair<String, String>) {
            pairs.forEach { (typed, expected) -> add(Case(type, typed, expected)) }
        }
        add(
            "인접키치환",
            "안녕하세오" to "안녕하세요", "감사합니더" to "감사합니다", "그래더" to "그래서",
            "사랑헤" to "사랑해", "죄송함니다" to "죄송합니다", "좋아여" to "좋아요",
            "보고싶더" to "보고싶어", "다음애" to "다음에", "어디여" to "어디야",
            "전화헤" to "전화해", "수고하셨어오" to "수고하셨어요", "갑사합니다" to "감사합니다"
        )
        add(
            "쌍자음Shift누락",
            "햇어" to "했어", "잇어요" to "있어요", "발리" to "빨리", "자증" to "짜증",
            "갓어" to "갔어", "왓어" to "왔어", "사우다" to "싸우다", "에쁘다" to "예쁘다",
            "스레기" to "쓰레기", "아가" to "아까", "이브게" to "이쁘게", "번했어" to "뻔했어"
        )
        add(
            "자모떨어짐",
            "감사합ㄴ다" to "감사합니다", "그런ㄷ" to "그런데", "괜찮ㅏ" to "괜찮아", "사랑ㅎ" to "사랑해",
            "고맙습ㄴ다" to "고맙습니다", "알겠습ㄴ다" to "알겠습니다", "미안ㅎ" to "미안해",
            "보고싶ㅇ" to "보고싶어", "주말ㅔ" to "주말에", "열심ㅣ" to "열심히",
            "감ㅅ합니다" to "감사합니다", "안녀하세요" to "안녕하세요"
        )
        add(
            "복합모음",
            "걘찮아" to "괜찮아", "머해" to "뭐해", "됬어" to "됐어", "머야" to "뭐야",
            "하이팅" to "화이팅", "가제" to "과제", "머하냐" to "뭐하냐", "쉬어요" to "쉬워요",
            "왠일이야" to "웬일이야", "되요" to "돼요", "어떻해" to "어떡해", "금새" to "금세"
        )
        add(
            "키추가",
            "감사사합니다" to "감사합니다", "안녕하세요요" to "안녕하세요", "그래서서" to "그래서",
            "사랑해해" to "사랑해", "고맙습니다다" to "고맙습니다", "진짜짜" to "진짜",
            "그런데데" to "그런데", "감사합니다ㅏ" to "감사합니다", "알겠어어요" to "알겠어요",
            "좋아요요" to "좋아요", "미안해해" to "미안해", "열심히히" to "열심히"
        )
        add(
            "전치",
            "갓마합니다" to "감사합니다", "사랗애" to "사랑해", "안녛아세요" to "안녕하세요",
            "그럳네" to "그런데", "종하요" to "좋아요", "줌라" to "주말", "열싷미" to "열심히",
            "사감합니다" to "감사합니다", "안하녕세요" to "안녕하세요", "아좋요" to "좋아요",
            "보싶고어" to "보고싶어", "래그서" to "그래서"
        )
        add(
            "조사어미결합",
            "학교에섣" to "학교에서", "먹엇는데" to "먹었는데", "회사에너" to "회사에서",
            "친구란" to "친구랑", "집에너" to "집에서", "햇는데" to "했는데", "갓엇는데" to "갔었는데",
            "감사헤요" to "감사해요", "좋겟다" to "좋겠다", "먹고십어요" to "먹고싶어요",
            "시간애" to "시간에", "만나셔" to "만나서", "없엇어요" to "없었어요"
        )
    }

    // 오타가 아닌 정상 입력이며 모두 기본 어휘 상위 30k 안이다: 흔한 일상어, 순위 1k~30k 구간의 표본,
    // 그리고 위 오타 표의 교정 결과 단어 중 상위 30k에 있는 것(그 자체는 정상 단어).
    private val controls = listOf(
        "안녕하세요", "감사합니다", "그래서", "사랑해", "괜찮아", "학교에서",
        "먹었는데", "알겠습니다", "좋아요", "미안해", "오늘", "내일", "점심", "회사", "친구",
        "가족", "시간", "약속", "생각", "이야기", "공부", "운동", "여행", "주말", "커피",
        "병원", "보고싶어", "고마워요", "다음에", "진짜", "사과", "자리",
        "바람", "오랫동안", "외로워", "기다려보세요", "친구는", "포함된", "스타일의", "버텨요",
        "영국의", "종교를", "여자에게", "공간에서", "전달하는", "보냈어", "기대는", "치료에",
        "인식하고", "출근길", "어려우니까", "고민이죠", "버려야지요", "팬들이", "저지른",
        "하나이다", "주소가", "왼쪽에", "변수를", "나왔던", "수단이", "이쁘게", "전문성",
        "언어와", "결과다", "언뜻", "입구에서", "물량을", "은행은", "하기에는", "유산을",
        "이쁘고", "지혜", "교사와", "그렸다", "세금으로", "정원의", "홈페이지와", "정부나",
        "많지요", "시각과", "하신다", "쓸만한",
        "빨리", "뭐해", "뭐야", "과제", "쉬워요", "화이팅", "감사해요", "사랑해요"
    )

    // 정상 단어지만 순위가 30k 밖이라 교정 트라이가 모르는 입력. 관찰용이며 단언하지 않는다.
    private val outOfTrieControls = listOf(
        "수고하셨습니다", "공격과", "발견이", "예쁘다", "싸우다", "어디야", "전화해", "수고하셨어요"
    )

    // 단일 편집 탐색기가 겨냥한 사례: 어느 변형에서든 단어줄 상위 4 안에 있어야 한다.
    private val probeTargets = listOf(
        "어디여" to "어디야", "전화헤" to "전화해", "수고하셨어오" to "수고하셨어요", "사우다" to "싸우다",
        "에쁘다" to "예쁘다", "머해" to "뭐해", "머야" to "뭐야", "가제" to "과제", "쉬어요" to "쉬워요",
        "진짜짜" to "진짜", "사랑해해" to "사랑해", "좋아요요" to "좋아요", "사감합니다" to "감사합니다",
        "햇는데" to "했는데"
    )

    private fun findVocabFile(): File {
        val candidates = listOf(
            "app/src/main/assets/ko_base_vocab.tsv",
            "../app/src/main/assets/ko_base_vocab.tsv",
            "src/main/assets/ko_base_vocab.tsv",
            "../src/main/assets/ko_base_vocab.tsv"
        )
        for (path in candidates) {
            val file = File(path)
            if (file.exists()) return file
        }
        throw AssertionError("ko_base_vocab.tsv를 찾을 수 없습니다. 시도한 경로: $candidates, cwd=${File(".").absolutePath}")
    }

    @Before
    fun setUp() {
        val vocabFile = findVocabFile()
        vocabulary = BaseKoreanVocabulary { vocabFile.reader(Charsets.UTF_8) }
        vocabulary.load()
        typoCorrector = KeyboardAwareTypoCorrector()
        vocabulary.forEachWord(BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior -> typoCorrector.addWord(word, prior) }
        correctionStore = CorrectionPatternStore()
        ngram = PersonalNgramModel()
        predictor = AiContextualPredictor(
            morphology = ChoseongMorphologyEngine(),
            semanticPredictor = KoreanSemanticSentencePredictor(),
            personalizedStore = null,
            ngram = ngram,
            typoCorrector = typoCorrector,
            baseVocabulary = vocabulary,
            correctionStore = correctionStore
        )
        keyboardSource = KeyboardTypoCorrectionSource(typoCorrector, vocabulary, correctionStore, ngram)
    }

    private fun stroke(variant: Variant, typed: String): String =
        if (variant.mode == Mode.STROKE) typed else ""

    private fun context(variant: Variant, typed: String): String =
        if (variant.mode == Mode.STROKE) variant.contextPrefix else "${variant.contextPrefix}$typed "

    private fun vocabTier(word: String): String = when {
        vocabulary.containsWithinTop(word, 1_000) -> "top1k"
        vocabulary.containsWithinTop(word, 5_000) -> "top5k"
        vocabulary.containsWithinTop(word, BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) -> "top30k"
        vocabulary.contains(word) -> ">30k"
        else -> "OOV"
    }

    private fun trace(case: Case, variant: Variant): Outcome {
        val currentStroke = stroke(variant, case.typed)
        val contextBefore = context(variant, case.typed)
        val input = PredictionInput(currentStroke, contextBefore)
        val final = predictor.predict(currentStroke, contextBefore, pkg, limit = 10)
        val finalWords = final.filter { !it.isSentenceCompletion }
        val rank = finalWords.indexOfFirst { it.text == case.expected } + 1
        val foundPred = finalWords.firstOrNull { it.text == case.expected }
        if (rank > 0) {
            return Outcome(variant, rank, foundPred?.source, foundPred?.badge, "OK", finalWords.map { it.text })
        }

        val target = input.typoTarget
        val stage: String = if (target == null) {
            "gate:target없음"
        } else {
            val core = target.typed
            val keyLen = DubeolsikKeyMap.keySequence(core).length
            val isBaseKnown = vocabulary.containsWithinTop(core, BaseKoreanVocabulary.TYPO_VOCAB_LIMIT)
            val expectedInTrie = vocabulary.containsWithinTop(case.expected, BaseKoreanVocabulary.TYPO_VOCAB_LIMIT)
            val keyboard = keyboardSource.correct(input, pkg).candidates
            val produced = keyboard.firstOrNull { it.text == case.expected }
            when {
                keyLen < 3 -> "gate:키<3"
                isBaseKnown -> "gate:아는단어(상위30k)"
                produced != null -> {
                    val displayable = KoreanSuggestionSurface.isDisplayable(produced.text)
                    val passes = produced.text.trim() == input.cleanStroke ||
                        SuggestionQualityGate.accepts(produced.text, input.rawFullContext, produced.isSentenceCompletion)
                    when {
                        !displayable -> "merge:표시불가"
                        !passes -> "quality:품질게이트"
                        else -> "rank:limit/순위밖"
                    }
                }
                !expectedInTrie -> "corr:기대어트라이미등록(${vocabTier(case.expected)})"
                else -> {
                    val cost = KeyboardAwareTypoCorrector.weightedDistance(core, case.expected)
                    val maxCost = KeyboardAwareTypoCorrector.defaultMaxCost(core)
                    if (cost > maxCost) "corr:비용초과" else "corr:상위2컷/스템경로"
                }
            }
        }
        return Outcome(variant, 0, null, null, stage, finalWords.map { it.text })
    }

    private fun rankLabel(o: Outcome): String = when {
        !o.found -> "-"
        o.typoBadge -> "#${o.wordRank}✏️${o.foundSource}"
        else -> "#${o.wordRank}(${o.foundSource})"
    }

    private fun pct(n: Int, d: Int): String = if (d == 0) "n/a" else "${n * 100 / d}%"

    @Test
    fun `typo recall and false positive report`() {
        assertTrue("어휘 로드 실패", vocabulary.size() > 0)
        assertTrue("교정기 어휘 비어 있음", typoCorrector.size() > 0)

        val out = StringBuilder()
        fun line(s: String = "") { out.appendLine(s) }

        line("=== TYPO RECALL REGRESSION ===")
        line("vocab size=${vocabulary.size()} trieWords=${typoCorrector.size()} TYPO_VOCAB_LIMIT=${BaseKoreanVocabulary.TYPO_VOCAB_LIMIT} cases=${cases.size}")
        line("변형: " + variants.joinToString(" / ") { it.name })
        line()

        val results = cases.map { case -> case to variants.map { trace(case, it) } }

        line("--- [표1] 케이스별 정적 진단 ---")
        line("유형 | 입력 | 기대 | 키길이 | 입력어휘순위 | 기대어휘순위 | cost/max | corrector.correct 상위3(word:cost) | 개인패턴")
        for ((case, _) in results) {
            val keyLen = DubeolsikKeyMap.keySequence(case.typed).length
            val cost = KeyboardAwareTypoCorrector.weightedDistance(case.typed, case.expected)
            val maxCost = KeyboardAwareTypoCorrector.defaultMaxCost(case.typed)
            val top3 = typoCorrector.correct(case.typed, limit = 3)
                .joinToString(",") { "${it.word}:${"%.2f".format(it.cost)}" }
            val personal = correctionStore.lookup(case.typed, 2).size
            line(
                "${case.type} | ${case.typed} | ${case.expected} | $keyLen | ${vocabTier(case.typed)} | ${vocabTier(case.expected)} | " +
                    "${"%.2f".format(cost)}/${"%.2f".format(maxCost)} | $top3 | $personal"
            )
        }
        line()

        line("--- [표2] 케이스별 결과 (단어줄 순위, 없으면 '-') ---")
        line("유형 | 입력 | 기대 | " + variants.joinToString(" | ") { it.name } + " | 탈락단계(A0) | 탈락단계(B0)")
        for ((case, outcomes) in results) {
            line(
                "${case.type} | ${case.typed} | ${case.expected} | " +
                    outcomes.joinToString(" | ") { rankLabel(it) } + " | ${outcomes[0].stage} | ${outcomes[2].stage}"
            )
        }
        line()

        line("--- [표3] 유형별 recall (발견=단어줄에 기대어 존재 / ✏️=✏️ 배지 소스로 존재 / top4=UI 단어칸 4개 안) ---")
        val types = cases.map { it.type }.distinct()
        for (variantIdx in variants.indices) {
            line("[${variants[variantIdx].name}]")
            for (type in types) {
                val rows = results.filter { it.first.type == type }.map { it.second[variantIdx] }
                line(
                    "  $type: 발견 ${rows.count { it.found }}/${rows.size} (${pct(rows.count { it.found }, rows.size)})" +
                        ", ✏️ ${rows.count { it.typoBadge }}/${rows.size}, top4 ${rows.count { it.inTop4 }}/${rows.size}"
                )
            }
            val all = results.map { it.second[variantIdx] }
            line(
                "  전체: 발견 ${all.count { it.found }}/${all.size} (${pct(all.count { it.found }, all.size)})" +
                    ", ✏️ ${all.count { it.typoBadge }}/${all.size} (${pct(all.count { it.typoBadge }, all.size)})" +
                    ", top4 ${all.count { it.inTop4 }}/${all.size} (${pct(all.count { it.inTop4 }, all.size)})"
            )
        }
        line()

        line("--- [표4] 탈락 단계 집계 (변형별, 실패 케이스만) ---")
        for (variantIdx in variants.indices) {
            line("[${variants[variantIdx].name}]")
            val failures = results.filter { !it.second[variantIdx].found }
            failures.groupBy { it.second[variantIdx].stage.substringBefore('(') }
                .entries.sortedByDescending { it.value.size }
                .forEach { (stage, list) ->
                    line("  $stage: ${list.size}건  예: " + list.take(6).joinToString(", ") { "${it.first.typed}→${it.first.expected}" })
                }
            if (failures.isEmpty()) line("  (실패 없음)")
        }
        line()

        line("--- [표5] 유형×탈락단계 (A0 기준) ---")
        for (type in types) {
            val fails = results.filter { it.first.type == type && !it.second[0].found }
            line("  $type: " + fails.groupBy { it.second[0].stage.substringBefore('(') }
                .entries.joinToString(", ") { "${it.key} ${it.value.size}" }.ifEmpty { "(실패 없음)" })
        }
        line()

        line("--- [표6] 발견됐지만 ✏️ 소스가 아닌 케이스 ---")
        results.forEach { (case, outcomes) ->
            outcomes.filter { it.found && !it.typoBadge }.forEach {
                line("  ${case.typed}→${case.expected} [${it.variant.name}] ${rankLabel(it)}")
            }
        }
        line()

        line("--- [표7] 실패 시 최종 단어줄 (A0, 최대 6개) ---")
        results.filter { !it.second[0].found }.forEach { (case, outcomes) ->
            line("  ${case.typed}→${case.expected}: ${outcomes[0].finalWords.take(6)}")
        }
        line()

        line("--- [표8] 대조군: 올바른 단어 입력 시 ✏️ 오탐 (상위 30k 안 ${controls.size}개) ---")
        assertTrue(
            "대조군에 상위 30k 밖 단어가 있음: ${controls.filter { !vocabulary.containsWithinTop(it, BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) }}",
            controls.all { vocabulary.containsWithinTop(it, BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) }
        )
        fun typoBadges(word: String, variant: Variant) =
            predictor.predict(stroke(variant, word), context(variant, word), pkg, limit = 10)
                .filter { !it.isSentenceCompletion && it.badge == "✏️" }
        fun describe(word: String): String? {
            val cells = variants.map { variant ->
                val preds = typoBadges(word, variant)
                if (preds.isEmpty()) "없음" else preds.joinToString("/") { "${it.text}(${it.source})" }
            }
            return if (cells.all { it == "없음" }) null else "$word [${vocabTier(word)}]: " + cells.joinToString(" | ")
        }
        val falsePositives = controls.mapNotNull { describe(it) }
        if (falsePositives.isEmpty()) line("  ✏️ 오탐 0건") else falsePositives.forEach { line("  $it") }
        line("  -- 상위 30k 밖 정상 단어 (관찰용)")
        outOfTrieControls.mapNotNull { describe(it) }.forEach { line("  $it") }
        line()

        line("--- [표9] 지연 시간(JVM 데스크톱, 예열 후 3회 중앙값, ms) A0 기준 ---")
        val predictMs = mutableListOf<Pair<Double, Case>>()
        val typoMs = mutableListOf<Pair<Double, Case>>()
        for (case in cases) {
            val input = PredictionInput(case.typed, "")
            repeat(3) {
                predictor.predict(case.typed, "", pkg, limit = 10)
                keyboardSource.correct(input, pkg)
            }
            val p = DoubleArray(3)
            val t = DoubleArray(3)
            for (i in 0 until 3) {
                var start = System.nanoTime()
                predictor.predict(case.typed, "", pkg, limit = 10)
                p[i] = (System.nanoTime() - start) / 1e6
                start = System.nanoTime()
                keyboardSource.correct(input, pkg)
                t[i] = (System.nanoTime() - start) / 1e6
            }
            predictMs += p.sorted()[1] to case
            typoMs += t.sorted()[1] to case
        }
        fun summarize(name: String, values: List<Pair<Double, Case>>) {
            val sorted = values.sortedBy { it.first }
            val worst = sorted.takeLast(3).reversed().joinToString(", ") { "${it.second.typed}=${"%.1f".format(it.first)}" }
            line(
                "  $name: 중앙 ${"%.1f".format(sorted[sorted.size / 2].first)} / p90 ${"%.1f".format(sorted[sorted.size * 9 / 10].first)}" +
                    " / 최대 ${"%.1f".format(sorted.last().first)}  (최악 $worst)"
            )
        }
        summarize("predict 전체", predictMs)
        summarize("KeyboardTypoCorrectionSource.correct", typoMs)
        line()

        println(out.toString())

        val failedTargets = probeTargets.flatMap { (typed, expected) ->
            val idx = cases.indexOfFirst { it.typed == typed && it.expected == expected }
            assertTrue("표에 없는 사례: $typed→$expected", idx >= 0)
            results[idx].second.filter { !it.inTop4 }.map { "$typed→$expected [${it.variant.name}] 순위=${it.wordRank} 최종=${it.finalWords.take(4)}" }
        }
        assertTrue(
            "단일 편집 탐색기 겨냥 사례가 단어줄 상위 4에 없음:\n${failedTargets.joinToString("\n")}",
            failedTargets.isEmpty()
        )

        val minFound = (cases.size * 80 + 99) / 100
        variants.forEachIndexed { variantIdx, variant ->
            val found = results.count { it.second[variantIdx].found }
            assertTrue("[${variant.name}] recall $found/${cases.size} < $minFound", found >= minFound)
        }

        assertTrue("정상 입력 ✏️ 오탐:\n${falsePositives.joinToString("\n")}", falsePositives.isEmpty())
    }

    @Test
    fun `probe latency`() {
        val probe = SingleEditTypoProbe(vocabulary, ngram)
        val out = StringBuilder()
        fun line(s: String = "") { out.appendLine(s) }

        fun microsPerCall(inputs: List<String>, call: (String) -> Unit): List<Double> {
            repeat(30) { inputs.forEach(call) }
            return inputs.map { typed ->
                val samples = DoubleArray(9) {
                    val start = System.nanoTime()
                    repeat(20) { call(typed) }
                    (System.nanoTime() - start) / 20 / 1e3
                }
                samples.sorted()[4]
            }
        }
        fun summarize(name: String, micros: List<Double>) {
            val sorted = micros.sorted()
            line(
                "  $name: 중앙 ${"%.0f".format(sorted[sorted.size / 2])}us / p90 ${"%.0f".format(sorted[sorted.size * 9 / 10])}us" +
                    " / 최대 ${"%.0f".format(sorted.last())}us (n=${sorted.size})"
            )
        }

        line("--- [표10] 단일 편집 탐색기 단독 지연(JVM 데스크톱, 예열 후 입력별 중앙값) ---")
        summarize("probe.correct 오타 입력", microsPerCall(cases.map { it.typed }) { probe.correct(it) })
        summarize("probe.correct 정상 입력", microsPerCall(controls) { probe.correct(it) })
        summarize("probe.upgradeKnownWord 정상 입력", microsPerCall(controls) { probe.upgradeKnownWord(it) })
        line()

        println(out.toString())
    }

    @Test
    fun `known word upgrade census over the trie vocabulary`() {
        val probe = SingleEditTypoProbe(vocabulary, ngram)
        val upgrades = linkedMapOf<String, String>()
        var checked = 0
        vocabulary.forEachWord(BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, _ ->
            checked++
            val upgrade = probe.upgradeKnownWord(word)
            if (upgrade != null && DubeolsikKeyMap.keySequence(word).length >= 3) {
                upgrades[word] = upgrade.word
            }
        }
        println("--- [표11] 상위 30k 어절 전수: '아는 단어' 교정 후보가 나오는 입력 ---")
        println(
            "  검사 ${checked}개 중 ${upgrades.size}개: " +
                upgrades.entries.joinToString(", ") { (word, upgrade) ->
                    "$word(${vocabulary.rankOf(word)})→$upgrade(${vocabulary.rankOf(upgrade)})"
                }
        )

        val ordinaryWords = listOf(
            "이해", "세게", "영하", "수고", "섬", "곡", "도는", "대가", "분만", "대는", "도어", "뻔", "뜻", "쌀",
            "싸는", "싸고", "끄는", "따를", "짠", "낀", "뜰", "돌"
        )
        val flagged = ordinaryWords.filter { it in upgrades }
        assertTrue("정상 단어에 업그레이드 후보가 나옴: $flagged", flagged.isEmpty())
        assertTrue("잇는→있는 업그레이드 없음", upgrades["잇는"] == "있는")
        assertTrue("거에요→거예요 업그레이드 없음", upgrades["거에요"] == "거예요")
    }

    @Test
    fun `rare vocabulary words outside the trie keep few typo candidates`() {
        val pool = mutableListOf<String>()
        var rank = 0
        vocabulary.forEachWord { word, _ ->
            rank++
            if (rank > BaseKoreanVocabulary.TYPO_VOCAB_LIMIT && word.all { it.code in 0xAC00..0xD7A3 }) pool += word
        }
        val sampleSize = 300
        val stride = pool.size / sampleSize
        val sample = List(sampleSize) { pool[it * stride] }

        val probe = SingleEditTypoProbe(vocabulary, ngram)
        var eligible = 0
        var unfiltered = 0
        var filtered = 0
        val flaggedWords = mutableListOf<String>()
        for (word in sample) {
            if (DubeolsikKeyMap.keySequence(word).length < 3) continue
            eligible++
            if (typoCorrector.correct(word, limit = 10).isNotEmpty() || probe.correct(word, limit = 10).isNotEmpty()) {
                unfiltered++
            }
            val candidates = keyboardSource.correct(PredictionInput(word, ""), pkg).candidates
            if (candidates.isNotEmpty()) {
                filtered++
                flaggedWords += "$word→${candidates.joinToString("/") { it.text }}"
            }
        }
        println("--- [표12] 순위 30001~150000 한글 어절 ${pool.size}개에서 ${stride}번째마다 ${sample.size}개(키 3 이상 ${eligible}개) ---")
        println("  필터 전 ✏️: $unfiltered/${sample.size} (${"%.1f".format(unfiltered * 100.0 / sample.size)}%)")
        println("  필터 후 ✏️: $filtered/${sample.size} (${"%.1f".format(filtered * 100.0 / sample.size)}%)")
        println("  필터 후 남은 입력: ${flaggedWords.joinToString(", ")}")
        assertTrue("필터 후 ✏️ 비율 ${filtered * 100.0 / sample.size}% > 5%", filtered * 100 <= 5 * sample.size)
    }
}

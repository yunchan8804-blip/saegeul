/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSpacingLint
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackIndex
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackText
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * 실제 번들 에셋(코퍼스 n-gram, 기본 어휘 TSV, 문장팩)으로 프로덕션과 같은 방식으로 구성한
 * [AiContextualPredictor]에 대량의 입력을 흘려 추천 품질 문제 후보를 규칙 기반으로 표시하는
 * 보고용 하네스. 개인 저장소(개인 n-gram, 내 스타일 문장, RAG 금고)는 비운 상태로 시작한다.
 *
 * 이 테스트는 회귀 방어선이 아니라 보고용이라 실패시키지 않는다. 결과는
 * app/build/reports/prediction-sweep.{md,json}에 남기고, SYNTAX·JUNK 건수는 로그에도 크게 찍는다.
 */
class PredictionQualitySweepTest {

    data class SweepCase(val category: String, val label: String, val stroke: String, val context: String)
    data class CandidateHit(val text: String, val source: String, val badge: String, val isSentence: Boolean)
    data class Issue(val rule: String, val detail: String, val case: SweepCase, val candidate: CandidateHit)

    companion object {
        private lateinit var predictor: AiContextualPredictor
        private lateinit var syntaxFilter: KoreanSyntaxRuleFilter
        private lateinit var semanticPredictor: KoreanSemanticSentencePredictor
        private lateinit var appDir: File

        // scripts/eval-ko-everyday-probes.py 의 PROBES와 동일한 목록(60개, 2026-09-27 확인).
        private val PROBES = listOf(
            "오늘", "오늘 저녁", "나 지금", "지금 집에", "내일 몇", "밥 먹었어", "고마워", "진짜", "혹시 시간",
            "회의 끝나고", "퇴근하고", "엄마", "배고파", "이따가", "그럼", "주말에", "택배", "선택", "우리 내일",
            "그거", "너", "왜", "빨리", "좀", "근데", "아까", "내가", "우리", "지금", "어디야", "뭐해", "언제",
            "괜찮아", "미안해", "알겠어", "잘 자", "수고했어", "도착하면", "시간 되면", "나중에", "혹시",
            "오랜만에", "같이", "벌써", "아직", "생각보다", "다음 주에", "점심 뭐", "저녁에", "집에 가서",
            "학교 끝나고", "주말에 뭐", "영화 보러", "커피 한잔", "카톡 확인", "사진 보내", "연락 줘",
            "보고 싶어", "많이 바빠", "조심히"
        )

        // 흔한 미완성 입력(어간/앞부분만 타이핑한 상태). 지정된 10개 + 실사용 패턴 60개 이상.
        private val INCOMPLETE_FRAGMENTS = listOf(
            "안녕하", "감사합", "고마", "죄송하", "내일", "지금 가", "밥 먹", "회의", "확인 부탁", "수고하셨",
            "오늘 회의", "내일 뵙", "잘 부탁", "축하드", "반갑습", "오래 기다리", "천천히 오", "조심히 들어가",
            "곧 도착", "이따 다시", "지금 통화", "잠깐 시간", "메일 보내", "자료 공유", "일정 확인",
            "늦어서 죄송", "먼저 가", "나중에 연락", "오늘 점심", "저녁 약속", "커피 한잔 하", "발표 준비",
            "보고서 작성", "결과 공유", "일 다 끝", "집에 가", "밥 먹었", "언제 도착", "몇 시에 만나",
            "오늘 날씨", "주말에 뭐 하", "생각보다 빨리", "다음 주에 다시", "연락 부탁", "확인했습",
            "검토 부탁", "일정 변경", "회의 시작", "출발했습", "곧 뵙겠",
            "지금 뭐 하", "이번 주에", "다음에 다시", "잠시만 기다려", "미리 알려", "바로 갈게",
            "조금 늦을", "곧 끝날", "거의 다 왔", "출근하고", "퇴근 후에", "주말 잘 보내",
            "오늘도 고생", "다음 회의는", "지금 바로", "확인해 볼게", "생각해 볼게", "연락 기다릴게",
            "빨리 처리", "다시 확인"
        )

        private val HONORIFIC_CONTEXTS = listOf(
            "안녕하세요 오늘 ", "회의 참석 부탁드립니다. 시간은 ", "먼저 사과드립니다. 늦어서 ",
            "보고서 검토 후 ", "말씀하신 자료 ", "곧 도착 예정입니다. 위치는 ",
            "확인 감사드립니다. 다음 ", "죄송합니다만 ", "네 알겠습니다. 그럼 ", "오늘 회의는 예정대로 "
        )

        private val INFORMAL_CONTEXTS = listOf(
            "야 오늘 ", "지금 뭐해 ", "밥 먹었어? 나는 ", "빨리 와 ", "그거 진짜 ",
            "나 지금 ", "너 어디야? 나 ", "잘 자 내일 ", "고마워 진짜 ", "괜찮아 걱정마 "
        )

        // 흔한 띄어쓰기 오류 패턴 검사는 정본 KoreanSpacingLint를 그대로 쓴다(중복 규칙 금지).

        private fun findAsset(vararg candidates: String): File =
            candidates.map(::File).firstOrNull { it.exists() }
                ?: throw AssertionError("에셋을 찾을 수 없습니다. 시도한 경로: ${candidates.toList()}, cwd=${File(".").absolutePath}")

        private fun findVocabFile(): File = findAsset(
            "app/src/main/assets/ko_base_vocab.tsv",
            "../app/src/main/assets/ko_base_vocab.tsv",
            "src/main/assets/ko_base_vocab.tsv",
            "../src/main/assets/ko_base_vocab.tsv"
        )

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val vocabFile = findVocabFile()
            // assets -> main -> src -> app 모듈 루트
            appDir = vocabFile.parentFile.parentFile.parentFile.parentFile

            val ngramFile = findAsset(
                "app/src/main/assets/${BundledKoreanNgram.ASSET_PATH}",
                "../app/src/main/assets/${BundledKoreanNgram.ASSET_PATH}",
                "src/main/assets/${BundledKoreanNgram.ASSET_PATH}",
                "../src/main/assets/${BundledKoreanNgram.ASSET_PATH}"
            )
            val corpusNgram = ngramFile.inputStream().use(BundledKoreanNgram::read)

            val sentencePackFile = findAsset(
                "app/src/main/assets/sentence-packs/ko-basic-v1.txt",
                "../app/src/main/assets/sentence-packs/ko-basic-v1.txt",
                "src/main/assets/sentence-packs/ko-basic-v1.txt",
                "../src/main/assets/sentence-packs/ko-basic-v1.txt"
            )
            val sentences = sentencePackFile.readLines(Charsets.UTF_8).mapNotNull(SentencePackText::normalizeAccepted)
            val sentencePackIndex = SentencePackIndex.build(sentences)

            val vocabulary = BaseKoreanVocabulary { vocabFile.reader(Charsets.UTF_8) }
            vocabulary.load()
            val typoCorrector = KeyboardAwareTypoCorrector()
            vocabulary.forEachWord(BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior ->
                typoCorrector.addWord(word, prior)
            }
            val correctionStore = CorrectionPatternStore()

            syntaxFilter = KoreanSyntaxRuleFilter()
            semanticPredictor = KoreanSemanticSentencePredictor()

            predictor = AiContextualPredictor(
                morphology = ChoseongMorphologyEngine(),
                semanticPredictor = semanticPredictor,
                personalizedStore = PersonalizedSentenceStore(),
                ngram = PersonalNgramModel(),
                typoCorrector = typoCorrector,
                baseVocabulary = vocabulary,
                correctionStore = correctionStore,
                personalSentenceVault = PersonalSentenceVault(storeFile = null),
                sentencePackLookup = sentencePackIndex::complete,
                bundledNgram = { corpusNgram }
            )
        }

        private fun vocabTopWords(count: Int): List<String> =
            findVocabFile().readLines(Charsets.UTF_8)
                .filterNot { it.startsWith("#") || it.isBlank() }
                .mapNotNull { line -> line.substringBefore('\t').takeIf(String::isNotBlank) }
                .take(count)

        private fun buildCases(): List<SweepCase> {
            val cases = mutableListOf<SweepCase>()

            // (a) 기본 어휘 상위 150개 어절의 1~2음절 접두
            vocabTopWords(150).forEach { word ->
                cases += SweepCase("vocab_prefix", word, word.take(1), "")
                if (word.length >= 2) {
                    cases += SweepCase("vocab_prefix", word, word.take(2), "")
                }
            }

            // (b) 일상 문맥 프로브(문맥 + 빈 입력)
            PROBES.forEach { probe ->
                cases += SweepCase("probe_context", probe, "", "$probe ")
            }

            // (c) 흔한 미완성 입력
            INCOMPLETE_FRAGMENTS.forEach { fragment ->
                cases += SweepCase("incomplete_fragment", fragment, fragment, "")
            }

            // (d) 존댓말/반말 문맥
            HONORIFIC_CONTEXTS.forEach { context ->
                cases += SweepCase("honorific_context", context.trim(), "", context)
            }
            INFORMAL_CONTEXTS.forEach { context ->
                cases += SweepCase("informal_context", context.trim(), "", context)
            }

            return cases
        }
    }

    @Test
    fun sweepPredictionsAndReportQualityIssues() {
        val cases = buildCases()
        assertTrue("입력 세트가 400개 미만입니다: ${cases.size}", cases.size >= 400)

        val issues = mutableListOf<Issue>()
        var candidateCount = 0

        cases.forEach { case ->
            val predictions = predictor.predict(
                currentStroke = case.stroke,
                contextBeforeCursor = case.context,
                packageName = "com.example.sweep",
                limit = 8
            )
            val contextEvidence = if (case.context.isNotBlank()) {
                KoreanToneClassifier.evidence(case.context)
            } else {
                null
            }
            val strokeTrim = case.stroke.trim()
            val contextTrim = case.context.trim()

            predictions.forEach { prediction ->
                candidateCount++
                val candidate = CandidateHit(
                    prediction.text, prediction.source, prediction.badge, prediction.isSentenceCompletion
                )

                val syntaxResult = syntaxFilter.check(prediction.text)
                if (syntaxResult is KoreanSyntaxRuleFilter.RuleResult.Invalid) {
                    issues += Issue("SYNTAX", syntaxResult.violationType.code, case, candidate)
                }

                if (KoreanSpacingLint.hasSpacingIssue(prediction.text)) {
                    issues += Issue("SPACING", "KoreanSpacingLint", case, candidate)
                }

                if (hasDuplication(prediction.text)) {
                    issues += Issue("DUP", "음절/어절 반복", case, candidate)
                }

                if (contextEvidence != null) {
                    val candidateEvidence = KoreanToneClassifier.evidence(prediction.text)
                    if (candidateEvidence != null && candidateEvidence != contextEvidence) {
                        issues += Issue("TONE", "문맥=$contextEvidence 후보=$candidateEvidence", case, candidate)
                    }
                }

                if ((strokeTrim.isNotBlank() && prediction.text == strokeTrim) ||
                    (contextTrim.isNotBlank() && prediction.text == contextTrim)
                ) {
                    issues += Issue("ECHO", "입력 되풀이", case, candidate)
                }

                if (isJunk(prediction.text)) {
                    issues += Issue("JUNK", "이상 문자/자모 단독/80자 초과", case, candidate)
                }
            }
        }

        writeReports(cases.size, candidateCount, issues)

        val syntaxCount = issues.count { it.rule == "SYNTAX" }
        val junkCount = issues.count { it.rule == "JUNK" }
        println("=".repeat(70))
        println("[PredictionQualitySweep] 입력 케이스 ${cases.size}개, 수집 후보 ${candidateCount}개")
        println("[PredictionQualitySweep] SYNTAX 위반 후보: $syntaxCount 건")
        println("[PredictionQualitySweep] JUNK 위반 후보: $junkCount 건")
        listOf("SPACING", "DUP", "TONE", "ECHO").forEach { rule ->
            println("[PredictionQualitySweep] $rule 위반 후보: ${issues.count { it.rule == rule }} 건")
        }
        println("=".repeat(70))
    }

    private fun hasDuplication(text: String): Boolean {
        val words = text.split(' ', '\n').filter(String::isNotBlank)
        if (words.zipWithNext().any { (a, b) -> a == b }) return true
        return Regex("([가-힣])\\1{1,}").containsMatchIn(text)
    }

    private fun isJunk(text: String): Boolean {
        if (text.length > 80) return true
        // 호환 자모 블록(U+3131~U+318E): 단독 초성/중성/종성(ㄱ, ㅏ 등)이 섞인 경우.
        if (text.any { it.code in 0x3131..0x318E }) return true
        val allowed = Regex("^[가-힣a-zA-Z0-9\\s.,!?~…:;()\\[\\]{}'\"·%\\-–—/@#&*+=]*$")
        return !allowed.matches(text)
    }

    private fun writeReports(caseCount: Int, candidateCount: Int, issues: List<Issue>) {
        val reportsDir = File(appDir, "build/reports")
        reportsDir.mkdirs()

        val ruleOrder = listOf("SYNTAX", "SPACING", "DUP", "TONE", "ECHO", "JUNK")
        val byRule = issues.groupBy { it.rule }

        val md = buildString {
            appendLine("# 추천 품질 스윕 보고서")
            appendLine()
            appendLine("입력 케이스 수: $caseCount, 수집된 후보 수: $candidateCount")
            appendLine()
            appendLine("| 규칙 | 건수 |")
            appendLine("|---|---|")
            ruleOrder.forEach { rule -> appendLine("| $rule | ${byRule[rule]?.size ?: 0} |") }
            appendLine()
            ruleOrder.forEach { rule ->
                val ruleIssues = byRule[rule].orEmpty()
                appendLine("## $rule (${ruleIssues.size}건)")
                appendLine()
                appendLine("| 입력 카테고리:라벨 | 후보 | 소스/배지 | 상세 |")
                appendLine("|---|---|---|---|")
                ruleIssues.forEach { issue ->
                    val inputDesc = "${issue.case.category}:${issue.case.label}"
                    appendLine(
                        "| ${inputDesc.escapeMd()} | ${issue.candidate.text.escapeMd()} | " +
                            "${issue.candidate.source}/${issue.candidate.badge} | ${issue.detail.escapeMd()} |"
                    )
                }
                appendLine()
            }
        }
        File(reportsDir, "prediction-sweep.md").writeText(md, Charsets.UTF_8)

        val root = JSONObject()
        root.put("caseCount", caseCount)
        root.put("candidateCount", candidateCount)
        val countsJson = JSONObject()
        ruleOrder.forEach { rule -> countsJson.put(rule, byRule[rule]?.size ?: 0) }
        root.put("counts", countsJson)
        val issuesJson = JSONArray()
        issues.forEach { issue ->
            val o = JSONObject()
            o.put("rule", issue.rule)
            o.put("detail", issue.detail)
            o.put("category", issue.case.category)
            o.put("inputLabel", issue.case.label)
            o.put("stroke", issue.case.stroke)
            o.put("context", issue.case.context)
            o.put("candidateText", issue.candidate.text)
            o.put("source", issue.candidate.source)
            o.put("badge", issue.candidate.badge)
            o.put("isSentence", issue.candidate.isSentence)
            issuesJson.put(o)
        }
        root.put("issues", issuesJson)
        File(reportsDir, "prediction-sweep.json").writeText(root.toString(2), Charsets.UTF_8)
    }

    private fun String.escapeMd(): String = replace("|", "\\|").replace("\n", " ")
}

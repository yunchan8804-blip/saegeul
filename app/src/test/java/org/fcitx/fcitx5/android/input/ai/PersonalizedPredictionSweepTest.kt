/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedSentenceBank
import org.fcitx.fcitx5.android.input.ai.ondevice.IngestionReport
import org.fcitx.fcitx5.android.input.ai.ondevice.IngestionRejectionReason
import org.fcitx.fcitx5.android.input.ai.ondevice.RecentSentSentences
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSpacingLint
import org.fcitx.fcitx5.android.input.ai.rule.KoreanSyntaxRuleFilter
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackIndex
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackText
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** [PredictionQualitySweepTest.buildCases]가 돌려주는 기존 하네스의 입력 케이스 타입을 그대로 쓴다. */
private typealias LegacyCase = PredictionQualitySweepTest.SweepCase

/**
 * 개인화 경로(타이핑 DNA 수집 -> 프로파일링 -> 컴파일, 개인 n-gram/문장 RAG 학습, Gemma 재료 뱅크
 * 적재)를 실제 프로덕션이 문장 확정 때 호출하는 클래스와 순서 그대로 채운 뒤, 그 상태에서
 * [AiContextualPredictor]와 [ImmediateContextualPredictions](실기기 병합 경로)를 통해 나온 추천을
 * 대량으로 점검하는 보고용 하네스.
 *
 * 검사 규칙 중 SYNTAX/SPACING/TONE은 프로덕션 정본 클래스([KoreanSyntaxRuleFilter],
 * [org.fcitx.fcitx5.android.input.ai.rule.KoreanSpacingLint], [KoreanToneClassifier])를 직접
 * 재사용하고, DUP/ECHO/JUNK는 [PredictionQualitySweepTest]와 공유하는 [PredictionQualityRules]를
 * 쓴다. 여기에 이 하네스만의 LEAK(일부러 심은 실수 문장의 원문 유출)·SPAMMY(같은 후보가 서로 다른
 * 입력 30개 이상에서 반복) 규칙을 더한다.
 *
 * 보고용이라 실패시키지 않는다. 결과는 app/build/reports/personalized-prediction-sweep.{md,json}.
 */
class PersonalizedPredictionSweepTest {

    data class CandidateHit(val text: String, val source: String, val badge: String, val isSentence: Boolean)
    data class Issue(val rule: String, val detail: String, val case: LegacyCase, val candidate: CandidateHit)
    data class SpammyHit(val text: String, val distinctInputs: Int, val sampleInputs: List<String>, val sourceBadge: String)
    data class MistakeSeed(val sentence: String, val fragment: String, val type: String)

    companion object {
        private const val PACKAGE_NAME = "com.kakao.talk" // PersonaRegistry상 messenger로 분류됨
        private const val SPAMMY_THRESHOLD = 30

        private lateinit var predictor: AiContextualPredictor
        private lateinit var syntaxFilter: KoreanSyntaxRuleFilter
        private lateinit var appDir: File
        private lateinit var tempDir: File
        private lateinit var sentencePackIndex: SentencePackIndex
        private lateinit var generatedBank: GeneratedSentenceBank
        private lateinit var typingDnaVault: TypingDnaVault
        private lateinit var typingDnaRepository: TypingDnaRepository
        private lateinit var personalizedStore: PersonalizedSentenceStore
        private lateinit var personalNgramModel: PersonalNgramModel
        private lateinit var personalSentenceVault: PersonalSentenceVault
        private lateinit var typoCorrector: KeyboardAwareTypoCorrector

        // 실제 학습 파이프라인이 관찰 가능한 형태로 남기는 진단값들(보고서용).
        private var batchesProcessed = 0
        private var totalFeedCount = 0
        private val ingestionReports = mutableListOf<Triple<String, Int, IngestionReport>>()

        private lateinit var frequentNormalSeeds: List<Pair<String, Int>> // 승격 대상으로 고른 60문장 + 반복횟수
        private lateinit var frequentMistakeSeeds: List<Pair<String, Int>> // 승격 대상으로 고른 실수문장 5개 + 반복횟수

        private lateinit var seedPrefixCases: List<LegacyCase>
        private lateinit var gemmaProbeCases: List<LegacyCase>

        // ================= 시드 데이터 =================

        private val OPENINGS = listOf(
            "오늘", "내일", "오늘 오후에", "이따가", "회의 끝나고",
            "주말에", "지금", "이번 주에", "확인 후에", "도착하면"
        )

        // 일상(4)/업무(4)/약속(3)/안부(3)/감사(3)/사과(3) = 20
        private val HONORIFIC_BODIES = listOf(
            "점심 같이 드시겠어요?", "커피 한잔 하러 가시겠어요?", "날씨가 정말 좋네요.", "저녁 메뉴는 뭐로 정하셨어요?",
            "회의 자료 준비해서 보내드릴게요.", "검토 후에 답장드리겠습니다.", "일정표는 다시 확인해서 공유드릴게요.",
            "발표 준비는 순서대로 진행하고 있습니다.",
            "바로 연락드릴게요.", "확인해서 다시 말씀드릴게요.", "시간 맞춰서 준비해 놓을게요.",
            "그동안 잘 지내셨어요?", "다들 건강하게 지내고 계세요?", "오랜만에 인사드립니다.",
            "신경 써주셔서 정말 감사합니다.", "도와주셔서 큰 힘이 되었습니다.", "챙겨주셔서 감사드립니다.",
            "늦어서 정말 죄송합니다.", "미리 말씀드리지 못해서 죄송합니다.", "불편을 드려서 죄송합니다."
        )

        private val INFORMAL_BODIES = listOf(
            "점심 같이 먹을래?", "커피 한잔 하러 갈래?", "날씨 진짜 좋다.", "저녁 메뉴 뭐로 정했어?",
            "회의 자료 준비해서 보낼게.", "검토하고 다시 답장할게.", "일정표 다시 확인해서 보낼게.",
            "발표 준비는 순서대로 하고 있어.",
            "바로 연락할게.", "확인해서 다시 말해줄게.", "시간 맞춰서 준비해 놓을게.",
            "그동안 잘 지냈어?", "다들 건강하게 지내고 있어?", "오랜만이다 진짜.",
            "신경 써줘서 고마워.", "도와줘서 큰 힘이 됐어.", "챙겨줘서 진짜 고마워.",
            "늦어서 진짜 미안해.", "미리 말 못해서 미안해.", "불편하게 해서 미안해."
        )

        // 존댓말 200(10 오프닝 x 20바디) + 반말 200(10x20) = 400.
        private val HONORIFIC_SEEDS: List<String> = OPENINGS.flatMap { o -> HONORIFIC_BODIES.map { b -> "$o $b" } }
        private val INFORMAL_SEEDS: List<String> = OPENINGS.flatMap { o -> INFORMAL_BODIES.map { b -> "$o $b" } }
        private val NORMAL_SEEDS: List<String> = HONORIFIC_SEEDS + INFORMAL_SEEDS

        // 일부러 심은 흔한 실수 30개: 오타 8 / 붙여쓰기 8 / 같은 어절 반복 8 / 자모 섞임 6.
        private val MISTAKES: List<MistakeSeed> = listOf(
            MistakeSeed("오늘 회의 다 됬어요.", "됬어요", "오타"),
            MistakeSeed("진짜 어의없어서 말이 안 나와요.", "어의없어서", "오타"),
            MistakeSeed("웬지 모르게 기분이 좋아요.", "웬지", "오타"),
            MistakeSeed("왠만하면 오늘 안에 끝낼게요.", "왠만하면", "오타"),
            MistakeSeed("지금 바로 출발할께요.", "할께요", "오타"),
            MistakeSeed("그건 잘 몰겠는데 나중에 다시 볼게요.", "몰겠는데", "오타"),
            MistakeSeed("이거 어떻게 하는지 잘 몰겠어요.", "몰겠어요", "오타"),
            MistakeSeed("미안한데 오늘 좀 피곤핸요.", "피곤핸요", "오타"),
            MistakeSeed("이번 주 안에 다 끝낼수있어요.", "낼수있어요", "붙여쓰기"),
            MistakeSeed("그렇게 하면 잘 될것같아요.", "될것같아요", "붙여쓰기"),
            MistakeSeed("그것 좀 될까싶어서 다시 물어봤어요.", "될까싶어서", "붙여쓰기"),
            MistakeSeed("그런지싶어서 다시 확인했어요.", "그런지싶어서", "붙여쓰기"),
            MistakeSeed("다들 그런듯하다고 하더라고요.", "그런듯하다고", "붙여쓰기"),
            MistakeSeed("책임감있게 일 처리해서 다행이에요.", "책임감있게", "붙여쓰기"),
            MistakeSeed("자신감있게 발표하고 왔어요.", "자신감있게", "붙여쓰기"),
            MistakeSeed("관심있게 지켜봐 주셔서 감사해요.", "관심있게", "붙여쓰기"),
            MistakeSeed("회의 회의 끝나고 바로 갈게요.", "회의 회의", "어절반복"),
            MistakeSeed("내일 내일 다시 확인해볼게요.", "내일 내일", "어절반복"),
            MistakeSeed("자료 자료 정리해서 보낼게요.", "자료 자료", "어절반복"),
            MistakeSeed("그거 그거 진짜 웃기다.", "그거 그거", "어절반복"),
            MistakeSeed("지금 지금 바로 출발할게요.", "지금 지금", "어절반복"),
            MistakeSeed("확인 확인 부탁드립니다.", "확인 확인", "어절반복"),
            MistakeSeed("저녁 저녁 메뉴 정했어요?", "저녁 저녁", "어절반복"),
            MistakeSeed("점심 점심 뭐 먹었어요?", "점심 점심", "어절반복"),
            MistakeSeed("안녕ㅎ 오늘 뭐해.", "안녕ㅎ", "자모섞임"),
            MistakeSeed("고마워ㅜ 진짜 큰 도움이었어.", "고마워ㅜ", "자모섞임"),
            MistakeSeed("미안하ㄷ 나 지금 바빠서 못가.", "미안하ㄷ", "자모섞임"),
            MistakeSeed("알겠습니ㄷ 확인하고 연락드릴게요.", "알겠습니ㄷ", "자모섞임"),
            MistakeSeed("지금 갈게ㅇ 조금만 기다려줘.", "갈게ㅇ", "자모섞임"),
            MistakeSeed("오늘 회의 끝났ㅅ 바로 퇴근할게요.", "끝났ㅅ", "자모섞임")
        )

        // Gemma가 만들 법한 문장 40개: 정상 30 / 붙여쓰기 오류 5(정상 필터가 걸러야 함) /
        // 비문 5(KoreanSyntaxRuleFilter 위반이지만 GeneratedSentenceBank 적재 검증은 이를 보지 않음).
        private val GEMMA_NORMAL = listOf(
            "오늘 회의는 오후 세시로 미뤄졌어요.", "내일 아침에 다시 연락드릴게요.", "지금 바로 확인해서 알려드릴게요.",
            "점심은 회사 근처에서 먹을까요?", "주말에 시간 괜찮으면 같이 봐요.", "자료는 메일로 보내드렸습니다.",
            "늦어서 정말 죄송합니다.", "오늘 하루도 고생 많으셨어요.", "커피 한잔 하면서 얘기해요.",
            "발표 준비는 거의 끝났어요.", "곧 도착하니까 조금만 기다려요.", "이번 주 안에 정리해서 보내드릴게요.",
            "생각보다 일이 빨리 끝났네요.", "다음 주 일정은 아직 안 정했어요.", "회의 끝나고 바로 연락할게요.",
            "오늘 날씨가 정말 좋네요.", "저녁은 뭐 먹을지 정했어?", "출발하면 바로 문자 보낼게.",
            "조금 늦을 것 같으니까 먼저 출발하세요.", "사진은 다 정리해서 보냈어.", "오늘 계획대로 잘 진행됐어요.",
            "다음 회의 자료 준비해 놨어요.", "지금 통화 가능하신가요?", "확인 후에 바로 답장할게요.",
            "오늘도 일찍 퇴근했으면 좋겠어요.", "자료 공유는 내일까지 부탁드려요.", "이따 저녁에 시간 괜찮아?",
            "카페에서 잠깐 얘기 좀 할까요?", "주말 잘 보내고 월요일에 봐요.", "오늘 점심 메뉴 정했어요?"
        )
        private val GEMMA_SPACING_ERROR = listOf(
            "지금 바로 할수있어요.", "그거 진짜 먹을것같아요.", "다음 주에 될까싶어요.",
            "오늘은 좀 이상하게 그런지싶어요.", "표정이 왠지 그런듯하네요."
        )

        // (문장, 검증용 첫 어절 접두 컨텍스트) — 접두만 컨텍스트로 주면 남은 접미사(candidate.text) 안에
        // 위반 패턴이 온전히 들어와 SYNTAX 규칙이 "후보 단독"으로도 잡아낼 수 있다.
        private val GEMMA_UNGRAMMATICAL: List<Pair<String, String>> = listOf(
            "회의 자료를 감사합니다." to "회의 ",       // ACC-02
            "몸이 피곤해서 언제 오실까요?" to "몸이 ",   // ACC-01
            "안녕하세요 오늘 뭐해?" to "안녕하세요 ",    // ACC-04 (문맥 포함 검사 필요)
            "안녕하해요 오늘도 잘 지내시죠." to "안녕하해요 ", // ACC-05 (문맥 포함 검사 필요)
            "이 참석자가 왜 확인했습니다." to "이 "       // ACC-03
        )

        private fun findAsset(vararg candidates: String): File =
            candidates.map(::File).firstOrNull { it.exists() }
                ?: throw AssertionError("에셋을 찾을 수 없습니다. 시도한 경로: ${candidates.toList()}, cwd=${File(".").absolutePath}")

        private fun findVocabFile(): File = findAsset(
            "app/src/main/assets/ko_base_vocab.tsv",
            "../app/src/main/assets/ko_base_vocab.tsv",
            "src/main/assets/ko_base_vocab.tsv",
            "../src/main/assets/ko_base_vocab.tsv"
        )

        private fun buildFeedWithRepeats(base: List<String>, frequent: Map<Int, Int>, spacing: Int): List<String> {
            data class Pending(var wait: Int, val text: String)
            val feed = mutableListOf<String>()
            val pending = mutableListOf<Pending>()
            base.forEachIndexed { index, sentence ->
                feed += sentence
                frequent[index]?.let { total ->
                    for (k in 1 until total) pending += Pending(wait = spacing * k, text = sentence)
                }
                val ready = pending.filter { it.wait <= 0 }
                ready.forEach { feed += it.text }
                pending.removeAll(ready)
                pending.forEach { it.wait-- }
            }
            pending.forEach { feed += it.text }
            return feed
        }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val vocabFile = findVocabFile()
            appDir = vocabFile.parentFile.parentFile.parentFile.parentFile
            tempDir = File(appDir, "build/tmp/personalized-prediction-sweep-${System.nanoTime()}").apply { mkdirs() }

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
            sentencePackIndex = SentencePackIndex.build(sentences)

            val vocabulary = BaseKoreanVocabulary { vocabFile.reader(Charsets.UTF_8) }
            vocabulary.load()
            typoCorrector = KeyboardAwareTypoCorrector()
            vocabulary.forEachWord(BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior ->
                typoCorrector.addWord(word, prior)
            }
            val correctionStore = CorrectionPatternStore()

            syntaxFilter = KoreanSyntaxRuleFilter()

            // ===== 개인화 저장소(모두 임시 파일 기반, 프로덕션과 동일한 파일 기반 저장 경로) =====
            personalizedStore = PersonalizedSentenceStore(
                storageFile = File(tempDir, "personalized_sentences.json"),
                cipher = PlainVaultCipher
            ).apply { load() }
            personalNgramModel = PersonalNgramModel(storeFile = File(tempDir, "personal_ngram.json"), cipher = PlainVaultCipher)
            personalSentenceVault = PersonalSentenceVault(storeFile = File(tempDir, "personal_rag.json"), cipher = PlainVaultCipher)
            val recentSentSentences = RecentSentSentences()
            generatedBank = GeneratedSentenceBank(file = File(tempDir, "gemma_materials.json"), cipher = PlainVaultCipher)

            predictor = AiContextualPredictor(
                morphology = ChoseongMorphologyEngine(),
                semanticPredictor = KoreanSemanticSentencePredictor(),
                personalizedStore = personalizedStore,
                ngram = personalNgramModel,
                typoCorrector = typoCorrector,
                baseVocabulary = vocabulary,
                correctionStore = correctionStore,
                personalSentenceVault = personalSentenceVault,
                sentencePackLookup = sentencePackIndex::complete,
                bundledNgram = { corpusNgram }
            )

            // ===== 타이핑 DNA: 수집 -> 프로파일링 -> 컴파일 (FcitxInputMethodService.attachTypingDnaBatchCompiler와
            // 동일한 배선, 코루틴 대신 동기 호출로 배치 경계를 결정적으로 만든다) =====
            typingDnaRepository = TypingDnaRepository(File(tempDir, "typing_dna.json"), cipher = PlainVaultCipher)
            typingDnaVault = TypingDnaVault(
                thresholdPerCategory = 10,
                stagingFile = File(tempDir, "typing_dna_pending.json"),
                cipher = PlainVaultCipher
            )
            val typingDnaProfiler = TypingDnaProfiler()
            val typingDnaCompiler = TypingDnaCompiler(
                collocationModel = predictor.collocationModel,
                sentenceStore = personalizedStore,
                repository = typingDnaRepository
            )
            val typingDnaInstantSync = TypingDnaInstantSync(
                vault = typingDnaVault,
                repository = typingDnaRepository,
                profiler = typingDnaProfiler,
                compiler = typingDnaCompiler
            )
            typingDnaVault.setOnBatchReady { category, _ ->
                batchesProcessed++
                typingDnaInstantSync.syncNow(category)
            }

            // ===== 문장 확정 학습 경로: UserTypingContextCollector.onSentenceCommitted가 FcitxInputMethodService에서
            // 하는 일(typingDnaVault.recordSentence -> personalNgramModel.learn -> personalSentenceVault.record ->
            // recentSentSentences.record -> typoCorrector.addWord)을 같은 순서로 재현한다. =====
            val collector = UserTypingContextCollector(
                onSentenceCommitted = { pkg, sentence ->
                    typingDnaVault.recordSentence(pkg, sentence)
                    personalNgramModel.learn(sentence, pkg)
                    personalSentenceVault.record(sentence, pkg)
                    recentSentSentences.record(pkg, sentence)
                    PersonalNgramTokenizer.tokenize(sentence).forEach { token ->
                        typoCorrector.addWord(token, PersonalNgramModel.personalPrior(personalNgramModel.unigramCount(token)))
                    }
                }
            )
            val commitSink = TypingDnaCommitSink(collector)

            // ===== 시드 구성: 400 정상(존댓말 200/반말 200) 중 60개를 2~5회, 실수 30개 중 5개를 2회
            // 반복되게 배치하여(같은 10문장 배치 창 안에 들어오도록 촘촘히) 흘려 넣는다. =====
            val frequentNormalIndices = (0 until 30).map { it * 6 } + (0 until 30).map { 200 + it * 6 }
            val repeatCycle = listOf(2, 3, 4, 5)
            val frequentNormalMap = frequentNormalIndices.withIndex()
                .associate { (i, idx) -> idx to repeatCycle[i % repeatCycle.size] }
            frequentNormalSeeds = frequentNormalMap.map { (idx, count) -> NORMAL_SEEDS[idx] to count }

            val frequentMistakeIndices = listOf(0, 8, 13, 16, 24) // 오타/붙여쓰기/붙여쓰기/어절반복/자모섞임 1개씩
            val frequentMistakeMap = frequentMistakeIndices.associateWith { 2 }
            frequentMistakeSeeds = frequentMistakeMap.map { (idx, count) -> MISTAKES[idx].sentence to count }

            val feedNormal = buildFeedWithRepeats(NORMAL_SEEDS, frequentNormalMap, spacing = 2)
            val feedMistakes = buildFeedWithRepeats(MISTAKES.map { it.sentence }, frequentMistakeMap, spacing = 2)

            val combinedFeed = mutableListOf<String>()
            var mistakeCursor = 0
            feedNormal.forEachIndexed { i, sentence ->
                combinedFeed += sentence
                if (mistakeCursor < feedMistakes.size && (i + 1) % 16 == 0) {
                    combinedFeed += feedMistakes[mistakeCursor]
                    mistakeCursor++
                }
            }
            while (mistakeCursor < feedMistakes.size) {
                combinedFeed += feedMistakes[mistakeCursor]
                mistakeCursor++
            }

            totalFeedCount = combinedFeed.size
            combinedFeed.forEach { sentence ->
                commitSink.onEditorTextCommitted(PACKAGE_NAME, sentence, inspectionAllowed = true)
            }
            // 배치 문턱(10)에 못 미쳐 남은 꼬리도 실제 대시보드가 쓰는 TypingDnaVault.flushAll()로 반영한다.
            typingDnaVault.flushAll()

            personalizedStore.save()
            personalNgramModel.save()
            personalSentenceVault.save()

            // ===== Gemma 재료 뱅크: 실제 적재 경로(addGeneratedOpen)로 40문장을 흘려 넣는다 =====
            fun ingest(label: String, sentences: List<String>) {
                val json = JSONArray(sentences).toString()
                val report = generatedBank.addGeneratedOpen(json, modelId = "gemma-test-mock", modelSha256 = "a".repeat(64))
                ingestionReports += Triple(label, sentences.size, report)
            }
            GEMMA_NORMAL.chunked(8).forEachIndexed { i, chunk -> ingest("normal-batch-${i + 1}", chunk) }
            ingest("spacing-error-batch", GEMMA_SPACING_ERROR)
            ingest("ungrammatical-batch", GEMMA_UNGRAMMATICAL.map { it.first })

            // ===== 입력 세트 추가분: 시드 문장 앞 1~2어절 접두 150개 + 비문 5개 타깃 프로브 =====
            val prefixSet = LinkedHashSet<String>()
            (NORMAL_SEEDS + MISTAKES.map { it.sentence }).forEach { sentence ->
                val words = sentence.trim().split(' ').filter(String::isNotBlank)
                if (words.isEmpty()) return@forEach
                if (words.size >= 2) prefixSet += words.take(2).joinToString(" ")
                prefixSet += words[0]
            }
            seedPrefixCases = prefixSet.take(150).map { prefix ->
                LegacyCase("seed_prefix", prefix, prefix, "")
            }

            gemmaProbeCases = GEMMA_UNGRAMMATICAL.mapIndexed { i, (sentence, probeContext) ->
                LegacyCase("gemma_ungrammatical_probe", "GU${i + 1}:$sentence", "", probeContext)
            }
        }
    }

    @Test
    fun sweepPersonalizedPredictionsAndReportQualityIssues() {
        val legacyCases = PredictionQualitySweepTest.buildCases()
        assertTrue("기존 하네스 입력이 416개 미만입니다: ${legacyCases.size}", legacyCases.size >= 416)
        assertTrue("시드 접두 입력이 150개 미만입니다: ${seedPrefixCases.size}", seedPrefixCases.size >= 150)

        val cases = legacyCases + seedPrefixCases + gemmaProbeCases

        val issues = mutableListOf<Issue>()
        val candidateAppearances = HashMap<String, MutableSet<String>>()
        val candidateSourceBadge = HashMap<String, String>()
        var candidateCount = 0

        cases.forEach { case ->
            val predictions = mergedPredictions(case)
            val contextEvidence = if (case.context.isNotBlank()) KoreanToneClassifier.evidence(case.context) else null
            val strokeTrim = case.stroke.trim()
            val contextTrim = case.context.trim()
            val caseKey = "${case.category}|${case.stroke}|${case.context}"

            predictions.forEach { prediction ->
                candidateCount++
                val candidate = CandidateHit(prediction.text, prediction.source, prediction.badge, prediction.isSentenceCompletion)

                candidateAppearances.getOrPut(prediction.text) { mutableSetOf() } += caseKey
                candidateSourceBadge.putIfAbsent(prediction.text, "${prediction.source}/${prediction.badge}")

                val syntaxAlone = syntaxFilter.check(prediction.text)
                if (syntaxAlone is KoreanSyntaxRuleFilter.RuleResult.Invalid) {
                    issues += Issue("SYNTAX", "[후보단독] ${syntaxAlone.violationType.code}: ${syntaxAlone.reason}", case, candidate)
                } else if (contextTrim.isNotBlank()) {
                    val withContext = syntaxFilter.check("$contextTrim ${prediction.text}".trim())
                    if (withContext is KoreanSyntaxRuleFilter.RuleResult.Invalid) {
                        issues += Issue("SYNTAX", "[문맥포함] ${withContext.violationType.code}: ${withContext.reason}", case, candidate)
                    }
                }

                if (KoreanSpacingLint.hasSpacingIssue(prediction.text)) {
                    issues += Issue("SPACING", "KoreanSpacingLint", case, candidate)
                }

                if (PredictionQualityRules.hasDuplication(prediction.text)) {
                    issues += Issue("DUP", "음절/어절 반복", case, candidate)
                }

                if (contextEvidence != null) {
                    val candidateEvidence = KoreanToneClassifier.evidence(prediction.text)
                    if (candidateEvidence != null && candidateEvidence != contextEvidence) {
                        issues += Issue("TONE", "문맥=$contextEvidence 후보=$candidateEvidence", case, candidate)
                    }
                }

                if (PredictionQualityRules.isEcho(strokeTrim, contextTrim, prediction.text)) {
                    issues += Issue("ECHO", "입력 되풀이", case, candidate)
                }

                if (PredictionQualityRules.isJunk(prediction.text)) {
                    issues += Issue("JUNK", "이상 문자/자모 단독/80자 초과", case, candidate)
                }

                MISTAKES.forEach { mistake ->
                    if (prediction.text.contains(mistake.fragment)) {
                        issues += Issue(
                            "LEAK",
                            "type=${mistake.type} fragment='${mistake.fragment}' 원문='${mistake.sentence}'",
                            case,
                            candidate
                        )
                    }
                }
            }
        }

        val spammyHits = candidateAppearances.entries
            .filter { it.value.size >= SPAMMY_THRESHOLD }
            .sortedByDescending { it.value.size }
            .map { (text, inputs) ->
                SpammyHit(text, inputs.size, inputs.toList().take(6), candidateSourceBadge[text] ?: "")
            }

        // 승격/유출 진단: 60개 "자주 쓴" 정상 문장, 5개 "자주 쓴" 실수 문장이 실제로
        // PersonalizedSentenceStore(내 스타일, MIN_CANNED_PHRASE_FREQUENCY=2 게이트)에 올라갔는지 확인.
        val promotedNormal = frequentNormalSeeds.count { (sentence, _) -> personalizedStore.contains(sentence) }
        val promotedMistakes = frequentMistakeSeeds.filter { (sentence, _) -> personalizedStore.contains(sentence) }

        val typingDnaStats = typingDnaRepository.getStats()
        val ngramStats = personalNgramModel.stats()
        val ragStats = personalSentenceVault.stats()

        writeReports(
            cases.size, candidateCount, issues, spammyHits,
            promotedNormal, promotedMistakes, typingDnaStats, ngramStats, ragStats
        )

        val ruleOrder = listOf("SYNTAX", "SPACING", "DUP", "TONE", "ECHO", "JUNK", "LEAK")
        println("=".repeat(70))
        println("[PersonalizedPredictionSweep] 입력 케이스 ${cases.size}개, 수집 후보 ${candidateCount}개")
        println("[PersonalizedPredictionSweep] 학습 피드 문장 수: $totalFeedCount, 처리된 타이핑DNA 배치: $batchesProcessed")
        println("[PersonalizedPredictionSweep] 승격된 자주쓴 정상 문장: $promotedNormal/${frequentNormalSeeds.size}")
        println("[PersonalizedPredictionSweep] 승격된 자주쓴 '실수' 문장(유출 위험): ${promotedMistakes.size}/${frequentMistakeSeeds.size}")
        ruleOrder.forEach { rule ->
            println("[PersonalizedPredictionSweep] $rule 위반 후보: ${issues.count { it.rule == rule }} 건")
        }
        println("[PersonalizedPredictionSweep] SPAMMY 후보(입력 30개 이상 반복): ${spammyHits.size} 건")
        println("=".repeat(70))
    }

    private fun mergedPredictions(case: LegacyCase): List<AiPrediction> {
        val direct = predictor.predict(
            currentStroke = case.stroke,
            contextBeforeCursor = case.context,
            packageName = PACKAGE_NAME,
            limit = 8
        )
        val rawContext = ContextualPredictionInput.rawFullContext(case.stroke, case.context)
        val immediate = if (rawContext.isBlank()) {
            emptyList()
        } else {
            ImmediateContextualPredictions.collect(
                input = ImmediateContextualPredictions.Input(
                    rawContext = rawContext,
                    packageName = PACKAGE_NAME,
                    inputSessionEpoch = 0L,
                    limit = 8
                ),
                sentencePackLookup = sentencePackIndex::complete,
                generatedSentenceLookup = generatedBank::complete,
                generatedSpacingLookup = generatedBank::suggestSpacing
            )
        }
        val seen = mutableSetOf<String>()
        return (direct + immediate)
            .sortedByDescending { it.confidenceScore }
            .filter { seen.add("${it.isSentenceCompletion}:${it.text}") }
    }

    private fun writeReports(
        caseCount: Int,
        candidateCount: Int,
        issues: List<Issue>,
        spammyHits: List<SpammyHit>,
        promotedNormal: Int,
        promotedMistakes: List<Pair<String, Int>>,
        typingDnaStats: TypingDnaStats,
        ngramStats: PersonalNgramModel.NgramStats,
        ragStats: PersonalSentenceVault.VaultStats
    ) {
        val reportsDir = File(appDir, "build/reports")
        reportsDir.mkdirs()

        val ruleOrder = listOf("SYNTAX", "SPACING", "DUP", "TONE", "ECHO", "JUNK", "LEAK")
        val byRule = issues.groupBy { it.rule }

        val md = buildString {
            appendLine("# 개인화 추천 품질 스윕 보고서")
            appendLine()
            appendLine("입력 케이스 수: $caseCount, 수집된 후보 수: $candidateCount")
            appendLine()
            appendLine("## 재현한 학습 경로 (실제 호출 순서)")
            appendLine()
            appendLine("1. `TypingDnaCommitSink.onEditorTextCommitted(pkg, sentence)` -> `UserTypingContextCollector.recordCommittedText` (문장이 `.`/`?`/`!`로 끝나 즉시 문장 경계 인식)")
            appendLine("2. 경계 인식 시 `onSentenceCommitted` 콜백 발화, 순서대로:")
            appendLine("   - `TypingDnaVault.recordSentence(pkg, sentence)` (카테고리별 버퍼, threshold=10마다 배치 콜백)")
            appendLine("   - `PersonalNgramModel.learn(sentence, pkg)`")
            appendLine("   - `PersonalSentenceVault.record(sentence, pkg)`")
            appendLine("   - `RecentSentSentences.record(pkg, sentence)`")
            appendLine("   - 문장 토큰마다 `KeyboardAwareTypoCorrector.addWord(token, PersonalNgramModel.personalPrior(...))`")
            appendLine("3. 카테고리 버퍼가 10문장에 도달하면 `TypingDnaVault`의 배치 콜백 -> `TypingDnaInstantSync.syncNow(category)` ->")
            appendLine("   `TypingDnaVault.processPending` -> `TypingDnaProfiler.profileOnDevice(category, sentences)` ->")
            appendLine("   `TypingDnaCompiler.compilePersona(persona, persist=true)`가 `KoreanCollocationModel.injectDynamicBigrams`와")
            appendLine("   `PersonalizedSentenceStore.upsert`(문법적으로 온전하고 같은 배치 안에서 2회 이상 관측된 문장만, ")
            appendLine("   `TypingDnaProfiler.MIN_CANNED_PHRASE_FREQUENCY=2`)로 반영, `TypingDnaRepository.updatePersona`로 영속화.")
            appendLine("4. 남은 꼬리(10문장 미달)는 `TypingDnaVault.flushAll()`로 마무리 반영(대시보드 강제 동기화와 동일 경로).")
            appendLine("5. `AiContextualPredictor.predict()` + `ImmediateContextualPredictions.collect()`(실기기 병합 경로, ")
            appendLine("   sentencePackLookup/generatedSentenceLookup/generatedSpacingLookup 포함)를 합쳐 최종 추천 목록을 만든다.")
            appendLine()
            appendLine("실측: 학습 피드 문장 수 $totalFeedCount, 처리된 타이핑DNA 배치 $batchesProcessed 개.")
            appendLine()
            appendLine("## 시드 데이터 구성")
            appendLine()
            appendLine("- 정상 문장 400개 (존댓말 200 = 오프닝10 x 바디20, 반말 200 = 오프닝10 x 바디20)")
            appendLine("- 일부러 심은 실수 30개: 오타 8 / 붙여쓰기 8 / 같은 어절 반복 8 / 자모 섞임 6")
            appendLine("- 정상 문장 중 60개를 2~5회(순환 2,3,4,5), 실수 문장 중 5개를 2회 반복 배치")
            appendLine("- Gemma 재료 뱅크(addGeneratedOpen 실사용 경로): 정상 30 / 붙여쓰기 오류 5 / 비문 5")
            appendLine()
            appendLine("## 승격/유출 진단 (PersonalizedSentenceStore, MIN_CANNED_PHRASE_FREQUENCY=2)")
            appendLine()
            appendLine("- 자주 쓴 정상 문장 승격: $promotedNormal / ${frequentNormalSeeds.size}")
            appendLine("- 자주 쓴 **실수** 문장 승격(유출 위험, 문법 필터는 통과했지만 오타/붙여쓰기/반복은 걸러내지 못함): ")
            appendLine("  ${promotedMistakes.size} / ${frequentMistakeSeeds.size}")
            if (promotedMistakes.isNotEmpty()) {
                appendLine("  승격된 실수 문장: ${promotedMistakes.joinToString(", ") { "'${it.first}'" }}")
            }
            appendLine()
            appendLine("## 개인화 저장소 상태")
            appendLine()
            appendLine("- TypingDnaRepository: totalSentences=${typingDnaStats.totalSentences}, phrasesCount=${typingDnaStats.phrasesCount}, " +
                "bigramsCount=${typingDnaStats.bigramsCount}, level=${typingDnaStats.level}")
            appendLine("- PersonalizedSentenceStore.size()=${personalizedStore.size()}")
            appendLine("- PersonalNgramModel.stats(): unigrams=${ngramStats.unigrams}, bigrams=${ngramStats.bigrams}, " +
                "trigrams=${ngramStats.trigrams}, learnedSentences=${ngramStats.learnedSentences}")
            appendLine("- PersonalSentenceVault.stats(): sentences=${ragStats.sentences}, uniqueTerms=${ragStats.uniqueTerms}")
            appendLine()
            appendLine("## Gemma 재료 뱅크 적재 결과 (addGeneratedOpen)")
            appendLine()
            appendLine("| 배치 | 요청 문장 수 | 추가됨 | 중복 | 거부됨 | 거부 사유 |")
            appendLine("|---|---|---|---|---|---|")
            ingestionReports.forEach { (label, requested, report) ->
                appendLine(
                    "| $label | $requested | ${report.added} | ${report.duplicate} | ${report.rejected} | " +
                        "${report.rejectionReasons.entries.joinToString(", ") { "${it.key}=${it.value}" }} |"
                )
            }
            appendLine()
            appendLine("> '비문' 배치는 GeneratedSentenceBank.addGeneratedOpen이 KoreanSyntaxRuleFilter를 적용하지 않아 ")
            appendLine("> 문법적으로 위반된 문장도 기계적 검사(음절수/종결부호/반복)만 통과하면 그대로 적재된다. ")
            appendLine("> 아래 SYNTAX 표에서 소스가 ondevice_generated/기기 AI 재료인 항목이 그 증거다.")
            appendLine()
            appendLine("| 규칙 | 건수 |")
            appendLine("|---|---|")
            ruleOrder.forEach { rule -> appendLine("| $rule | ${byRule[rule]?.size ?: 0} |") }
            appendLine("| SPAMMY | ${spammyHits.size} |")
            appendLine()
            ruleOrder.forEach { rule ->
                val ruleIssues = byRule[rule].orEmpty()
                appendLine("## $rule (${ruleIssues.size}건)")
                appendLine()
                appendLine("| 입력 카테고리:라벨 | 후보 | 소스/배지 | 상세 |")
                appendLine("|---|---|---|---|")
                ruleIssues.take(20).forEach { issue ->
                    val inputDesc = "${issue.case.category}:${issue.case.label}"
                    appendLine(
                        "| ${inputDesc.escapeMd()} | ${issue.candidate.text.escapeMd()} | " +
                            "${issue.candidate.source}/${issue.candidate.badge} | ${issue.detail.escapeMd()} |"
                    )
                }
                appendLine()
            }
            appendLine("## SPAMMY (${spammyHits.size}건, 입력 $SPAMMY_THRESHOLD 개 이상에서 반복된 후보)")
            appendLine()
            appendLine("| 후보 | 등장한 입력 수 | 소스/배지 | 예시 입력 |")
            appendLine("|---|---|---|---|")
            spammyHits.take(20).forEach { hit ->
                appendLine(
                    "| ${hit.text.escapeMd()} | ${hit.distinctInputs} | ${hit.sourceBadge} | " +
                        "${hit.sampleInputs.joinToString("; ") { it.escapeMd() }} |"
                )
            }
            appendLine()
        }
        File(reportsDir, "personalized-prediction-sweep.md").writeText(md, Charsets.UTF_8)

        val root = JSONObject()
        root.put("caseCount", caseCount)
        root.put("candidateCount", candidateCount)
        root.put("totalFeedCount", totalFeedCount)
        root.put("batchesProcessed", batchesProcessed)
        root.put("promotedNormalCount", promotedNormal)
        root.put("promotedNormalTotal", frequentNormalSeeds.size)
        root.put("promotedMistakeCount", promotedMistakes.size)
        root.put("promotedMistakeTotal", frequentMistakeSeeds.size)
        val countsJson = JSONObject()
        ruleOrder.forEach { rule -> countsJson.put(rule, byRule[rule]?.size ?: 0) }
        countsJson.put("SPAMMY", spammyHits.size)
        root.put("counts", countsJson)

        val ingestionJson = JSONArray()
        ingestionReports.forEach { (label, requested, report) ->
            val o = JSONObject()
            o.put("batch", label)
            o.put("requested", requested)
            o.put("added", report.added)
            o.put("duplicate", report.duplicate)
            o.put("rejected", report.rejected)
            val reasons = JSONObject()
            report.rejectionReasons.forEach { (reason, count) -> reasons.put(reason.name, count) }
            o.put("rejectionReasons", reasons)
            ingestionJson.put(o)
        }
        root.put("gemmaIngestion", ingestionJson)

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

        val spammyJson = JSONArray()
        spammyHits.forEach { hit ->
            val o = JSONObject()
            o.put("text", hit.text)
            o.put("distinctInputs", hit.distinctInputs)
            o.put("sourceBadge", hit.sourceBadge)
            o.put("sampleInputs", JSONArray(hit.sampleInputs))
            spammyJson.put(o)
        }
        root.put("spammy", spammyJson)

        File(reportsDir, "personalized-prediction-sweep.json").writeText(root.toString(2), Charsets.UTF_8)
    }

    private fun String.escapeMd(): String = replace("|", "\\|").replace("\n", " ")
}

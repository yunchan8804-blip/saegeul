/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * Contextual Intent categories inferred from conversational and written context.
 */
enum class ContextualIntent {
    Scheduling,       // 일정, 약속, 시간 조율
    Inquiry,          // 질문, 문의, 확인 요청
    Proposal,         // 제안, 의견, 아이디어
    WorkProgress,     // 업무, 개발, 보고, 배포, 협업
    Gratitude,        // 감사, 격려, 칭찬
    ApologyDelay,     // 사과, 지연, 양해
    MealDaily,        // 식사, 일상 대화, 안부
    ConnectiveClause, // ~는데, ~해서 등 접속 절
    Farewell,         // 마무리, 퇴근, 인사
    Agreement,        // 동의, 수락, 긍정 확인
    Cheering,         // 응원, 축하, 격려
    StatusUpdate,     // 이동, 현황, 상태 보고
    Request,          // 정중한 요청, 부탁, 자문
    General           // 일반 문맥
}

/**
 * Predicted next sentence / phrase with semantic rationale and badge styling.
 */
data class SemanticSentencePrediction(
    val text: String,
    val confidenceScore: Float,
    val intent: ContextualIntent,
    val tone: KoreanTone,
    val isCompleteSentence: Boolean = true,
    val badge: String = "✨ AI완성"
)

/**
 * Extracted conversational entities from surrounding context.
 */
data class ExtractedEntities(
    val times: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    val isNegativeOrDeclining: Boolean = false
)

/**
 * Realtime On-Device Semantic Contextual Sentence Predictor.
 * Deeply analyzes the full text context written so far (surrounding context before cursor),
 * extracts dynamic entities (time, place, work topic), enforces nuance polarity (rejection vs acceptance),
 * synthesizes slot-filled tailored sentences, and generates rich, natural next-sentence completion
 * candidates with sub-millisecond zero latency.
 */
class KoreanSemanticSentencePredictor {

    private val honorificMarkers = listOf(
        "습니다", "입니다", "합니다", "드립니다", "보내드립니다", "송부드립니다",
        "세요", "해요", "시겠습니까", "감사합니다", "고맙습니다", "죄송합니다",
        "부탁드립니다", "말씀해", "확인했습니다", "알겠습니다", "되세요", "편안한"
    )

    private val informalMarkers = listOf(
        "고마워", "고마웡", "땡큐", "수고했어", "축하해", "어디야", "밥 먹자",
        "치맥", "갈래", "뭐해", "이따 봐", "알겠어", "ㅇㅋ", "ㄱㅅ", "ㅋㅋ", "ㅎㅎ",
        "했어", "갔어", "봤어", "할게", "갈게", "올게", "먹었어", "있어?",
        "올렸어", "해봐", "해줘", "보자", "볼까", "어때", "됐어", "맞아", "편해",
        "뭘", "뭐", "시프지", "시퍼", "하구", "시프니까", "시프면", "싶으니까", "싶으면", "난", "그걸",
        "좋아", "동의해", "오케이", "그래"
    )

    private val knownPlaces = listOf(
        "판교", "강남", "홍대", "성수", "여의도", "종로", "신촌", "잠실", "광화문",
        "사당", "수원", "사무실", "회사", "회의실", "카페", "식당", "본사",
        "을지로", "정자", "수내", "역삼", "선릉", "삼성", "용산", "마포",
        "합정", "건대", "신사", "압구정", "청담", "이태원", "인천", "부천",
        "일산", "분당", "송도", "서초", "노원", "혜화", "안국", "명동"
    )

    private val knownTimes = listOf(
        "오늘", "내일", "모레", "어제", "지금", "이번 주말", "주말", "오전", "오후", "저녁", "점심", "퇴근 후", "아침", "새벽", "밤"
    )

    private val knownTopics = listOf(
        "회의", "미팅", "배포", "빌드", "커밋", "머지", "pr", "코드", "리뷰", "기획서",
        "보고서", "점심", "저녁", "커피", "자료", "발표", "안건", "이슈", "핫픽스"
    )

    private val decliningKeywords = listOf(
        "죄송하지만", "죄송한데", "미안하지만", "미안한데", "어려울", "어려워", "어렵겠",
        "힘들", "바빠서", "바쁘", "안 될", "안될", "못 갈", "못갈", "불가", "취소",
        "다른 일정", "선약", "사정", "부담"
    )

    companion object {
        private val SENTENCE_SPLIT_REGEX = Regex("[,.!?;~\\n]+")
        private val NORMALIZE_REGEX = Regex("[.!?~\\s,;]+")
        private val HOUR_REGEX = Regex("(\\d{1,2}시(?:\\s*반)?)")
        private val PLACE_PARTICLE_REGEX = Regex("([가-힣]{2,6})(?:에서|역에서|쪽에서|근처)")
    }

    fun inferTone(context: String): KoreanTone = inferToneInternal(context, null)

    internal fun inferToneInternal(context: String, preSplitSentences: List<String>? = null): KoreanTone {
        if (context.isBlank()) return KoreanTone.Honorific
        val clean = context.trim().lowercase()
        val sentences = preSplitSentences ?: clean.split(SENTENCE_SPLIT_REGEX).map { it.trim() }.filter { it.isNotBlank() }
        val lastSentence = sentences.lastOrNull() ?: clean

        var honorificScore = 0
        var informalScore = 0

        // Historic sentences evaluated with 1x weight
        sentences.dropLast(1).takeLast(3).forEach { s ->
            honorificMarkers.forEach { marker ->
                if (s.contains(marker)) honorificScore += 1
            }
            informalMarkers.forEach { marker ->
                if (s.contains(marker)) informalScore += 1
            }
        }

        // Latest sentence evaluated with 3x weight for rapid nuance reaction
        honorificMarkers.forEach { marker ->
            if (lastSentence.contains(marker)) honorificScore += 3
        }
        informalMarkers.forEach { marker ->
            if (lastSentence.contains(marker)) informalScore += 3
        }

        return when {
            informalScore > honorificScore -> KoreanTone.Informal
            lastSentence.contains("배포") || lastSentence.contains("커밋") || lastSentence.contains("머지") ||
                lastSentence.contains("pr") || lastSentence.contains("api") || lastSentence.contains("빌드") ||
                clean.contains("배포") || clean.contains("커밋") -> KoreanTone.Technical
            lastSentence.contains("보고서") || lastSentence.contains("품의") || lastSentence.contains("공유드립니다") ||
                lastSentence.contains("회의록") || lastSentence.contains("검토 요청") -> KoreanTone.Business
            else -> KoreanTone.Honorific
        }
    }

    /**
     * Extracts concrete entities (times, locations, topics) and polarity from the context.
     */
    fun extractEntities(context: String): ExtractedEntities {
        if (context.isBlank()) return ExtractedEntities()
        val clean = context.trim().lowercase()

        val foundPlaces = mutableListOf<String>()
        knownPlaces.forEach { p ->
            if (clean.contains("${p}역")) {
                foundPlaces.add("${p}역")
            } else if (clean.contains(p)) {
                foundPlaces.add(p)
            }
        }
        PLACE_PARTICLE_REGEX.findAll(clean).forEach { match ->
            val captured = match.groupValues[1]
            if (captured.length in 2..6 && !knownTimes.contains(captured) && !knownTopics.contains(captured)) {
                foundPlaces.add(captured)
            }
        }

        val foundTimes = mutableListOf<String>()
        // Compound times first (e.g. "오늘 저녁", "내일 오전", "내일 오후", "내일 저녁")
        val compoundTimes = listOf("오늘 저녁", "내일 저녁", "내일 오전", "내일 오후", "오늘 오후", "오늘 밤", "내일 밤", "주말 저녁", "퇴근 후")
        compoundTimes.forEach { ct ->
            if (clean.contains(ct)) {
                foundTimes.add(ct)
            }
        }

        knownTimes.forEach { t ->
            if (t == "오늘") {
                val isOnlyGreetingToday = (clean.contains("오늘 고생") || clean.contains("오늘도 고생") ||
                    clean.contains("오늘 하루도") || clean.contains("오늘 정말 수고") || clean.contains("오늘도 수고") || clean.contains("오늘 수고")) &&
                    !clean.contains("오늘 몇") && !clean.contains("오늘 3") && !clean.contains("오늘 만") &&
                    !clean.contains("오늘 볼") && !clean.contains("오늘 저녁") && !clean.contains("오늘 점심")
                if (clean.contains("오늘") && !isOnlyGreetingToday && foundTimes.none { it.startsWith("오늘") }) {
                    foundTimes.add("오늘")
                }
            } else if (clean.contains(t) && foundTimes.none { it.contains(t) }) {
                foundTimes.add(t)
            }
        }

        // Match hour patterns like "3시", "10시", "11시 반"
        HOUR_REGEX.findAll(clean).forEach { match ->
            foundTimes.add(match.value)
        }

        val foundTopics = knownTopics.filter { clean.contains(it.lowercase()) }
        val isDeclining = decliningKeywords.any { clean.contains(it) }

        return ExtractedEntities(
            times = foundTimes.distinct(),
            places = foundPlaces.distinct(),
            topics = foundTopics.distinct(),
            isNegativeOrDeclining = isDeclining
        )
    }

    private fun detectSegmentIntent(segment: String): ContextualIntent? {
        if (segment.isBlank()) return null
        val s = segment.trim().lowercase()

        // 1. Connective endings (문장이 '~는데', '~서', '~면', '~니까', '~는지' 등으로 끝난 경우)
        if (s.endsWith("는데") || s.endsWith("은데") || s.endsWith("한데") ||
            s.endsWith("인데") || s.endsWith("텐데") || s.endsWith("던데") ||
            s.endsWith("해서") || s.endsWith("아서") || s.endsWith("어서") || s.endsWith("여서") ||
            s.endsWith("혀서") || s.endsWith("져서") || s.endsWith("려서") || s.endsWith("돼서") ||
            s.endsWith("와서") || s.endsWith("봐서") || s.endsWith("줘서") ||
            s.endsWith("면") || s.endsWith("으면") || s.endsWith("니까") || s.endsWith("으니까") ||
            s.endsWith("라서") || s.endsWith("지만") || s.endsWith("면서") ||
            s.endsWith("는지") || s.endsWith("은지") || s.endsWith("을지") || s.endsWith("ㄹ지") ||
            s.endsWith("싶은지") || s.endsWith("지")
        ) {
            return ContextualIntent.ConnectiveClause
        }

        // 2. Apology / Delay / Decline
        val apologyKeywords = listOf("죄송", "미안", "늦어", "늦었", "막혀", "지연", "양해", "실례", "어려울", "어려워", "힘들", "부담")
        if (apologyKeywords.any { s.contains(it) }) {
            return ContextualIntent.ApologyDelay
        }

        // 3. Cheering & Celebration (Specific high-emotion markers)
        val cheeringKeywords = listOf("축하", "응원", "파이팅", "화이팅", "합격", "승진", "대단", "자랑", "힘내", "축하드", "응원합", "잘될 거")
        if (cheeringKeywords.any { s.contains(it) }) {
            return ContextualIntent.Cheering
        }

        // 4. Tech & Engineering Markers (Strict tech workflows take priority over general requests)
        val techKeywords = listOf("배포", "빌드", "커밋", "머지", "pr", "코드", "핫픽스", "릴리스", "이슈")
        if (techKeywords.any { s.contains(it) }) {
            return ContextualIntent.WorkProgress
        }

        // 5. Polite Request & Favor (Take precedence over scheduling/general work when asking a favor)
        val requestKeywords = listOf("부탁드립니다", "부탁드려요", "부탁해", "요청드립니다", "검토 부탁", "확인 부탁", "공유 부탁", "시간 되실 때 검토", "시간 되실 때 확인")
        if (requestKeywords.any { s.contains(it) } || (s.contains("부탁") && !s.contains("수고"))) {
            return ContextualIntent.Request
        }

        // 5. Agreement & Confirmation (Take precedence over proposal)
        val agreementKeywords = listOf("동의", "좋은 생각", "그렇게 하", "그렇게 진행", "찬성", "알겠습니다", "확인 완료", "오케이", "ㅇㅋ", "알겠어", "좋아요", "좋습니다")
        if (agreementKeywords.any { s.contains(it) }) {
            return ContextualIntent.Agreement
        }

        // 6. Status Update (En Route, Condition, Location)
        val statusKeywords = listOf("이동 중", "가는 중", "도착", "출발", "회의 중", "작업 중", "마무리 중", "자리 비움", "통화 가능", "가는 길")
        if (statusKeywords.any { s.contains(it) }) {
            return ContextualIntent.StatusUpdate
        }

        // 7. Gratitude Response (Counterpart thanking response / closing)
        val gratitudeResponseKeywords = listOf("별거 아냐", "별거 아니", "별말씀", "천만에", "도움이 됐다니", "언제든 물어봐", "언제든 편하게", "언제든 말씀")
        if (gratitudeResponseKeywords.any { s.contains(it) }) {
            return ContextualIntent.Farewell
        }

        // 8. Gratitude
        val gratitudeKeywords = listOf("감사", "고맙", "고마", "도와", "덕분", "도움", "땡큐")
        if (gratitudeKeywords.any { s.contains(it) }) {
            return ContextualIntent.Gratitude
        }

        // 9. Scheduling / Meeting (exclude false positives like 언제든, 언제나, 언제라도)
        val schedulingKeywords = listOf(
            "몇 시", "몇시", "시간", "일정", "약속", "만날", "만나요", "만나자", "봬요", "볼까", "보자",
            "내일", "모레", "오늘 저녁", "이번 주말", "주말에", "시간 되", "시간 괜찮", "스케줄"
        )
        val hasSchedulingKeyword = schedulingKeywords.any { s.contains(it) } ||
            (s.contains("언제") && !s.contains("언제든") && !s.contains("언제나") && !s.contains("언제라도") && !s.contains("언제든지"))
        if (hasSchedulingKeyword) {
            return ContextualIntent.Scheduling
        }

        // 10. Inquiry / Question
        val inquiryKeywords = listOf(
            "어떻게", "진행 상황", "진행상황", "확인 가능", "확인하셨", "확인했", "궁금", "문의",
            "알 수 있을", "알려주", "알려줘", "어디", "어떤"
        )
        if (s.contains("?") || inquiryKeywords.any { s.contains(it) }) {
            return ContextualIntent.Inquiry
        }

        // 11. Work Progress / Tech
        val workKeywords = listOf(
            "배포", "빌드", "커밋", "머지", "pr", "코드", "리뷰", "기획서", "보고서",
            "실적", "공유", "송부", "회의", "검토", "이슈", "핫픽스", "릴리스", "작업"
        )
        if (workKeywords.any { s.contains(it) }) {
            return ContextualIntent.WorkProgress
        }

        // 12. Meal & Daily Life
        val mealDailyKeywords = listOf("점심", "저녁", "식사", "밥", "커피", "카페", "맛있게", "날씨", "휴일")
        if (mealDailyKeywords.any { s.contains(it) }) {
            return ContextualIntent.MealDaily
        }

        // 13. Proposal / Suggestion
        val proposalKeywords = listOf("어떨까", "어떠신가요", "어때", "제안", "의견", "생각", "방안", "방법")
        if (proposalKeywords.any { s.contains(it) }) {
            return ContextualIntent.Proposal
        }

        // 14. Farewell / Closing
        val farewellKeywords = listOf("들어가", "퇴근", "내일 봬", "내일 봐", "먼저 가", "수고하셨", "고생하셨", "수고했", "고생했", "잘 자", "좋은 밤", "주말 잘")
        if (farewellKeywords.any { s.contains(it) }) {
            return ContextualIntent.Farewell
        }

        return null
    }

    fun inferIntent(context: String): ContextualIntent = inferIntentInternal(context, null)

    internal fun inferIntentInternal(context: String, preSplitSentences: List<String>? = null): ContextualIntent {
        if (context.isBlank()) return ContextualIntent.General
        val clean = context.trim().lowercase()
        val sentences = preSplitSentences ?: clean.split(SENTENCE_SPLIT_REGEX).map { it.trim() }.filter { it.isNotBlank() }
        val lastSentence = sentences.lastOrNull() ?: clean

        val isTechContext = listOf("배포", "빌드", "커밋", "머지", "pr", "코드", "핫픽스", "릴리스").any { clean.contains(it) }

        // Priority 1: Check the most recent sentence / active typing segment first
        val latestIntent = detectSegmentIntent(lastSentence)
        if (latestIntent == ContextualIntent.Request && isTechContext) {
            return ContextualIntent.WorkProgress
        }
        if (latestIntent != null) {
            return latestIntent
        }

        // Priority 2: Fall back to recent previous context if last sentence had no specific intent keyword
        for (sentence in sentences.reversed().drop(1)) {
            val prevIntent = detectSegmentIntent(sentence)
            if (prevIntent != null) {
                return prevIntent
            }
        }

        // Priority 3: Fall back to whole context check
        return detectSegmentIntent(clean) ?: ContextualIntent.General
    }

    private fun formatTimeParticle(time: String): String {
        return if (time in listOf("오늘", "내일", "모레", "어제", "지금", "이번 주말")) {
            time
        } else if (time.endsWith("에") || time.endsWith("쯤") || time.endsWith("후")) {
            time
        } else {
            "${time}에"
        }
    }

    private fun formatTimeApprox(time: String): String {
        return if (time in listOf("오늘", "내일", "모레", "지금")) {
            "$time 다시"
        } else {
            "${time}쯤에 다시"
        }
    }

    /**
     * Synthesizes tailored, slot-filled sentences using extracted entities.
     */
    private fun synthesizeEntityCandidates(
        entities: ExtractedEntities,
        isInformal: Boolean
    ): List<String> {
        val synthesized = mutableListOf<String>()
        val place = entities.places.firstOrNull()
        val time = if (entities.times.size >= 2) {
            entities.times.take(2).joinToString(" ")
        } else {
            entities.times.firstOrNull()
        }
        val topic = entities.topics.firstOrNull()

        // 1. Decline / Rescheduling Synthesis
        if (entities.isNegativeOrDeclining) {
            if (isInformal) {
                synthesized.add("다음번에 꼭 함께할게!")
                synthesized.add("다음에 시간 맞춰서 보자.")
                synthesized.add("일정 다시 맞춰보자, 미안해!")
            } else {
                synthesized.add("다음 기회에 꼭 함께하겠습니다.")
                synthesized.add("일정을 다른 날로 조율할 수 있을지 여쭙니다.")
                synthesized.add("너른 양해 부탁드립니다.")
                synthesized.add("양해해 주셔서 진심으로 감사드립니다.")
            }
            return synthesized
        }

        // 2. Time + Place + Topic (e.g. 내일 + 판교 + 회의)
        if (time != null && place != null && topic != null) {
            val timePhrase = formatTimeParticle(time)
            if (isInformal) {
                synthesized.add("$timePhrase ${place}에서 $topic 끝나고 연락할게!")
                synthesized.add("$timePhrase ${place}에서 보자!")
            } else {
                synthesized.add("$time $place $topic 참석하겠습니다.")
                synthesized.add("$time $place 회의실 예약해 두겠습니다.")
                synthesized.add("$timePhrase ${place}에서 뵙겠습니다.")
            }
        }

        // 3. Time + Place (e.g. 내일 + 판교, 오늘 저녁 + 강남)
        if (time != null && place != null && topic == null) {
            val timePhrase = formatTimeParticle(time)
            if (isInformal) {
                synthesized.add("$timePhrase ${place}에서 보자!")
                synthesized.add("$time ${place}에서 몇 시에 볼까?")
                synthesized.add("$time $place 좋아!")
                synthesized.add("$time $place 근처에서 볼까?")
            } else {
                synthesized.add("$timePhrase ${place}에서 뵙겠습니다.")
                synthesized.add("$time ${place}에서 몇 시에 뵐까요?")
                synthesized.add("$time $place 일정 가능합니다.")
                synthesized.add("$time ${place}에서 뵙는 것으로 확인했습니다.")
            }
        }

        // 3. Place + Topic (e.g. 판교 + 회의 or 강남 + 점심)
        if (place != null && topic != null) {
            val isMealOrCoffee = topic in listOf("점심", "저녁", "식사", "밥", "커피")
            if (isMealOrCoffee) {
                if (isInformal) {
                    synthesized.add("$place 근처에서 $topic 먹자!")
                    synthesized.add("$place 맛있는 곳 알아볼게!")
                } else {
                    synthesized.add("$place 근처에서 $topic 대접하겠습니다.")
                    synthesized.add("$place 도착해서 연락드리겠습니다.")
                }
            } else {
                if (isInformal) {
                    synthesized.add("$place $topic 끝나고 바로 연락할게!")
                    synthesized.add("$place 도착해서 연락할게.")
                } else {
                    synthesized.add("$place $topic 참석하겠습니다.")
                    synthesized.add("$place 도착하는 대로 바로 연락드리겠습니다.")
                    synthesized.add("$place 회의실 예약해 두겠습니다.")
                }
            }
        }

        // 4. Time + Topic (e.g. 3시 + 배포 or 내일 + 회의)
        if (time != null && topic != null) {
            val timePhrase = formatTimeParticle(time)
            if (isInformal) {
                synthesized.add("$time $topic 준비 끝났어!")
                synthesized.add("$timePhrase 맞춰서 공유해줄게.")
            } else {
                synthesized.add("$time $topic 준비 완료하여 공유드리겠습니다.")
                synthesized.add("$time $topic 차질 없이 진행하겠습니다.")
                synthesized.add("$time $topic 자료 미리 준비해 두겠습니다.")
            }
        }

        // 5. Topic only (e.g. 보고서, 기획서, 자료, PR, 배포)
        if (topic != null && place == null && time == null) {
            if (topic in listOf("보고서", "기획서", "자료", "안건")) {
                if (isInformal) {
                    synthesized.add("$topic 확인해보고 알려줄게!")
                    synthesized.add("$topic 수정해서 다시 올릴게.")
                } else {
                    synthesized.add("$topic 검토 후 피드백 드리겠습니다.")
                    synthesized.add("$topic 송부드렸으니 확인 부탁드립니다.")
                }
            } else if (topic in listOf("배포", "빌드", "커밋", "머지", "pr")) {
                if (isInformal) {
                    synthesized.add("$topic 확인하고 바로 머지할게!")
                    synthesized.add("$topic 정상 작동 중이야.")
                } else {
                    synthesized.add("$topic 완료 후 모니터링 중입니다.")
                    synthesized.add("$topic 확인 및 승인 부탁드립니다.")
                }
            }
        }

        // 6. Place only (e.g. 강남, 판교)
        if (place != null && time == null && topic == null) {
            if (isInformal) {
                synthesized.add("${place}역 몇 번 출구에서 볼까?")
                synthesized.add("$place 근처 맛있는 곳 알아볼게!")
            } else {
                synthesized.add("${place}역 근처에서 뵙겠습니다.")
                synthesized.add("$place 도착해서 연락드리겠습니다.")
            }
        }

        // 7. Time only (e.g. 내일, 3시)
        if (time != null && place == null && topic == null) {
            val timePhrase = formatTimeParticle(time)
            val timeApprox = formatTimeApprox(time)
            if (isInformal) {
                synthesized.add("$timePhrase 보자!")
                synthesized.add("$timeApprox 연락할게.")
            } else {
                synthesized.add("$timePhrase 뵙겠습니다.")
                synthesized.add("$time 편하신 시간에 연락 주시면 맞추겠습니다.")
            }
        }

        return synthesized
    }

    /**
     * Predicts contextual next sentences based on the full written context.
     */
    fun predictNextSentences(
        contextBeforeCursor: String,
        currentStroke: String = "",
        limit: Int = 4
    ): List<SemanticSentencePrediction> {
        val cleanContext = contextBeforeCursor.trim()
        val cleanStroke = currentStroke.trim()

        if (cleanContext.isBlank()) {
            return emptyList()
        }

        val tone = inferTone(cleanContext)
        val intent = inferIntent(cleanContext)
        val entities = extractEntities(cleanContext)
        val isInformal = (tone == KoreanTone.Informal)

        val rawCandidates = mutableListOf<String>()

        // 1. Dynamic Entity & Slot-Filled Synthesis (Highest Priority except for immediate status reporting)
        val dynamicCandidates = if (intent == ContextualIntent.StatusUpdate) {
            emptyList()
        } else {
            synthesizeEntityCandidates(entities, isInformal)
        }
        rawCandidates.addAll(dynamicCandidates)

        // 2. Intent-Based Candidates
        val intentCandidates = when (intent) {
            ContextualIntent.Scheduling -> if (isInformal) {
                listOf(
                    "그때 보자!",
                    "몇 시가 편해?",
                    "어디서 만날까?",
                    "일정 보고 다시 알려줄게!",
                    "좋아, 그때 시간 괜찮아!"
                )
            } else {
                listOf(
                    "가능하신 시간 알려주시면 맞추겠습니다.",
                    "그때 뵙겠습니다!",
                    "일정 확인 후 바로 말씀드리겠습니다.",
                    "언제든 편하신 시간에 연락 주세요.",
                    "회의실 예약해 두겠습니다."
                )
            }

            ContextualIntent.Inquiry -> if (isInformal) {
                listOf(
                    "지금 확인하고 있어!",
                    "내용 보고 바로 알려줄게.",
                    "다 끝났어, 이상 없어!",
                    "또 궁금한 거 있으면 물어봐!"
                )
            } else {
                listOf(
                    "현재 확인 중이며 곧 회신드리겠습니다.",
                    "자료 검토 후 바로 공유해 드리겠습니다.",
                    "요청하신 내용 처리 완료했습니다.",
                    "추가로 확인 필요한 사항이 있으신가요?"
                )
            }

            ContextualIntent.Proposal -> if (isInformal) {
                listOf(
                    "좋은 생각이다! 그렇게 진행하자.",
                    "나도 완전 동의해.",
                    "한번 고민해보고 이따 말해줄게!",
                    "이 방향이 훨씬 나은 것 같아."
                )
            } else {
                listOf(
                    "좋은 방안인 것 같습니다. 그렇게 진행하시죠.",
                    "말씀해주신 방향에 동의합니다.",
                    "검토 후 의견 전달드리겠습니다.",
                    "이 부분만 조금 더 보완되면 좋을 것 같습니다."
                )
            }

            ContextualIntent.WorkProgress -> if (isInformal) {
                listOf(
                    "코드 리뷰하고 바로 머지할게!",
                    "배포 끝났고 정상 작동 중이야.",
                    "수정해서 다시 올렸어, 확인해줘!",
                    "회의 끝나고 내용 정리해서 공유할게."
                )
            } else {
                listOf(
                    "검토 후 피드백 및 승인 진행하겠습니다.",
                    "배포 완료 후 모니터링 중입니다.",
                    "수정 사항 반영하여 다시 커밋했습니다.",
                    "회의록 정리해서 공유해 드리겠습니다."
                )
            }

            ContextualIntent.Gratitude -> if (isInformal) {
                listOf(
                    "별거 아냐, 언제든 편하게 물어봐!",
                    "도움이 됐다니 다행이다!",
                    "오늘도 고생 많았어, 고마워!",
                    "덕분에 잘 마무리했어!"
                )
            } else {
                listOf(
                    "도움이 되어 정말 기쁩니다.",
                    "별말씀을요, 언제든 편하게 말씀해 주세요.",
                    "오늘도 고생 많으셨습니다!",
                    "항상 세심하게 챙겨주셔서 진심으로 감사드립니다."
                )
            }

            ContextualIntent.ApologyDelay -> if (isInformal) {
                listOf(
                    "괜찮아, 천천히 조심히 와!",
                    "도착하면 바로 연락해줘.",
                    "전혀 신경 쓰지 마, 나도 여유 있어!",
                    "조심히 오고 천천히 봐도 돼."
                )
            } else {
                listOf(
                    "괜찮습니다, 천천히 조심히 오세요.",
                    "신경 쓰지 마시고 편히 오세요.",
                    "도착하시는 대로 연락 부탁드립니다.",
                    "일정 변경하셔도 괜찮으니 무리하지 마세요."
                )
            }

            ContextualIntent.MealDaily -> if (isInformal) {
                listOf(
                    "맛있게 먹고 이따 연락해!",
                    "밥 먹고 커피 한잔할래?",
                    "주말 잘 보내고 담주에 보자!",
                    "오늘 날씨 진짜 좋다, 좋은 하루 보내!"
                )
            } else {
                listOf(
                    "맛있게 식사하시고 좋은 하루 보내세요!",
                    "커피 한잔하시면서 잠시 쉬어가세요.",
                    "편안하고 행복한 주말 보내세요!",
                    "오늘 하루도 수고 많으셨습니다."
                )
            }

            ContextualIntent.ConnectiveClause -> {
                val isQuestionClause = cleanContext.endsWith("는지") || cleanContext.endsWith("은지") ||
                    cleanContext.endsWith("을지") || cleanContext.endsWith("ㄹ지") ||
                    cleanContext.endsWith("싶은지") || cleanContext.endsWith("지")
                val isTrafficOrDelay = cleanContext.contains("막혀") || cleanContext.contains("막히") ||
                    cleanContext.contains("늦") || cleanContext.contains("지연")
                val isWorkOrMeeting = cleanContext.contains("회의") || cleanContext.contains("보고") ||
                    cleanContext.contains("검토") || cleanContext.contains("작업")
                val isEnRoute = cleanContext.contains("가는 중") || cleanContext.contains("가는 길") ||
                    cleanContext.contains("이동 중") || cleanContext.contains("출발")

                val clause = cleanContext.substringAfterLast('.').substringAfterLast('?').substringAfterLast('\n').trim()

                when {
                    isQuestionClause && clause.isNotBlank() -> if (isInformal) {
                        listOf(
                            "$clause 모르겠어.",
                            "$clause 생각 중이야.",
                            "$clause 아직 못 정했어.",
                            "$clause 편하게 말해줘!"
                        )
                    } else {
                        listOf(
                            "$clause 잘 모르겠습니다.",
                            "$clause 고민 중입니다.",
                            "$clause 아직 결정하지 못했습니다.",
                            "$clause 편하게 말씀해 주세요."
                        )
                    }
                    isTrafficOrDelay -> if (isInformal) {
                        listOf(
                            "조금 늦을 것 같아, 정말 미안해!",
                            "10분 정도 늦을 것 같아서 먼저 들어가 있어!",
                            "도착하는 대로 바로 연락할게!",
                            "최대한 빨리 서둘러서 갈게!"
                        )
                    } else {
                        listOf(
                            "약속 시간보다 조금 늦어질 것 같아 죄송합니다.",
                            "10~15분 정도 늦을 것 같습니다, 너른 양해 부탁드립니다.",
                            "도착하는 즉시 연락드리겠습니다.",
                            "최대한 서둘러 이동하겠습니다, 죄송합니다."
                        )
                    }
                    isEnRoute -> if (isInformal) {
                        listOf(
                            "도착 5분 전에 미리 연락할게!",
                            "거의 다 왔어, 조금만 기다려줘!",
                            "도착해서 바로 연락할게!"
                        )
                    } else {
                        listOf(
                            "도착 5분 전에 미리 연락드리겠습니다.",
                            "도착하는 즉시 바로 들어가겠습니다.",
                            "예상 도착 시간 맞춰 연락드리겠습니다."
                        )
                    }
                    isWorkOrMeeting -> if (isInformal) {
                        listOf(
                            "끝나는 대로 바로 내용 공유할게!",
                            "확인해보고 다시 알려줄게.",
                            "회의 끝나고 바로 연락할게!"
                        )
                    } else {
                        listOf(
                            "종료되는 대로 신속히 내용 공유드리겠습니다.",
                            "확인 후 바로 회신드리겠습니다.",
                            "정리되는 대로 보고드리겠습니다."
                        )
                    }
                    else -> emptyList()
                }
            }

            ContextualIntent.Farewell -> if (isInformal) {
                listOf(
                    "오늘 고생 많았어, 조심히 들어가!",
                    "내일 봐, 편안한 밤 보내!",
                    "주말 푹 쉬고 다음 주에 봐~",
                    "잘 자! 좋은 꿈 꿔."
                )
            } else {
                listOf(
                    "오늘도 정말 수고 많으셨습니다. 조심히 들어가세요!",
                    "내일 뵙겠습니다. 편안한 저녁 보내세요!",
                    "주말 편히 쉬시고 다음 주에 뵙겠습니다.",
                    "항상 감사드립니다. 좋은 하루 되세요!"
                )
            }

            ContextualIntent.Agreement -> if (isInformal) {
                listOf(
                    "좋은 생각이다! 그렇게 진행하자.",
                    "나도 완전 동의해.",
                    "응 알겠어, 그렇게 진행할게!",
                    "확인했어, 바로 처리할게!",
                    "오케이 좋아!"
                )
            } else {
                listOf(
                    "네, 말씀해주신 방향대로 진행하겠습니다.",
                    "동의합니다. 그렇게 추진하시죠.",
                    "네, 확인 완료했습니다.",
                    "좋은 의견 감사합니다. 적극 반영하겠습니다.",
                    "네, 알겠습니다. 차질 없이 준비하겠습니다."
                )
            }

            ContextualIntent.Cheering -> if (isInformal) {
                listOf(
                    "진심으로 축하해! 🎉",
                    "그동안 고생 많았어, 진짜 대단하다!",
                    "항상 응원하고 있어, 파이팅!",
                    "좋은 결과 있을 거야, 힘내자!"
                )
            } else {
                listOf(
                    "진심으로 축하드립니다! 🎉",
                    "그동안의 노고에 깊이 감사드리며 축하드립니다.",
                    "항상 응원하고 있습니다. 파이팅입니다!",
                    "좋은 결실 맺으시길 진심으로 기원합니다."
                )
            }

            ContextualIntent.StatusUpdate -> if (isInformal) {
                listOf(
                    "지금 이동 중이야, 곧 도착해!",
                    "방금 도착했어, 어디로 가면 돼?",
                    "작업 거의 다 끝났어, 곧 공유할게!",
                    "잠시 자리 비우고 있어서 조금 이따 연락할게!"
                )
            } else {
                listOf(
                    "현재 이동 중이며 예상 시간 맞춰 도착하겠습니다.",
                    "약속 장소에 도착했습니다.",
                    "작업 마무리 단계이며 정리되는 대로 공유드리겠습니다.",
                    "잠시 자리 비움 중이라 확인 후 바로 연락드리겠습니다."
                )
            }

            ContextualIntent.Request -> if (isInformal) {
                listOf(
                    "시간 될 때 한번 확인해줘!",
                    "자료 준비되면 공유 부탁해.",
                    "의견 편하게 알려줘!",
                    "도움 필요하면 언제든 말해줘!"
                )
            } else {
                listOf(
                    "시간 되실 때 검토 부탁드립니다.",
                    "자료 확인 후 편하신 시간에 공유 부탁드립니다.",
                    "의견 주시면 적극 반영하겠습니다.",
                    "필요하신 사항 있으시면 언제든 편하게 말씀해 주세요."
                )
            }

            ContextualIntent.General -> getTimeBasedGreetings(isInformal)
        }

        // If declining/negative polarity is detected, filter out inappropriate affirmative sentences
        val safeIntentCandidates = if (entities.isNegativeOrDeclining) {
            intentCandidates.filter { candidate ->
                !candidate.contains("그때 뵙") &&
                    !candidate.contains("그때 보자") &&
                    !candidate.contains("그렇게 진행") &&
                    !candidate.contains("동의합니다")
            }
        } else {
            intentCandidates
        }

        rawCandidates.addAll(safeIntentCandidates)

        // 3. Ultra-Fast Concise Action Phrases only for explicit Scheduling/Work contexts with extracted entities
        if ((intent == ContextualIntent.Scheduling || intent == ContextualIntent.WorkProgress) &&
            (entities.times.isNotEmpty() || entities.places.isNotEmpty() || entities.topics.isNotEmpty())) {
            val shortPhrases = if (isInformal) {
                listOf("확인했어!", "알겠어!", "곧 도착해!", "언제 볼까?", "고마워!")
            } else {
                listOf("확인했습니다.", "알겠습니다.", "곧 회신드리겠습니다.", "언제든 연락주세요.")
            }
            rawCandidates.addAll(shortPhrases)
        }

        val deduplicatedCandidates = rawCandidates.distinct().filter { candidate ->
            val core = candidate.trim().trimEnd('.', '!', '?', '~')
            core.length >= 2 && !cleanContext.contains(core)
        }

        val filtered = if (cleanStroke.isNotBlank()) {
            deduplicatedCandidates.filter { it.contains(cleanStroke) || it.startsWith(cleanStroke) }
                .ifEmpty { deduplicatedCandidates }
        } else {
            deduplicatedCandidates
        }

        return filtered.take(limit).mapIndexed { index, sentence ->
            val isShort = sentence.split(" ").size <= 2 && sentence.length <= 10
            val score = (0.98f - (index * 0.04f)).coerceIn(0.70f, 0.99f)
            val badge = when {
                dynamicCandidates.contains(sentence) -> "✨ 맞춤AI"
                isShort -> "핵심구문"
                intent == ContextualIntent.Scheduling -> "일정추천"
                intent == ContextualIntent.WorkProgress -> "업무/보고"
                intent == ContextualIntent.Inquiry -> "답변추천"
                intent == ContextualIntent.Proposal -> "의견제안"
                intent == ContextualIntent.Gratitude -> "감사문구"
                intent == ContextualIntent.ApologyDelay -> "안심문구"
                intent == ContextualIntent.MealDaily -> "일상/안부"
                intent == ContextualIntent.Farewell -> "마무리인사"
                intent == ContextualIntent.Agreement -> "동의/확인"
                intent == ContextualIntent.Cheering -> "응원/축하"
                intent == ContextualIntent.StatusUpdate -> "현황/보고"
                intent == ContextualIntent.Request -> "정중요청"
                else -> "✨ AI완성"
            }
            SemanticSentencePrediction(
                text = sentence,
                confidenceScore = score,
                intent = intent,
                tone = tone,
                isCompleteSentence = !isShort,
                badge = badge
            )
        }
    }

    /**
     * Synthesizes time-aware greetings and friendly conversational starters based on current hour.
     */
    fun getTimeBasedGreetings(isInformal: Boolean, calendar: java.util.Calendar = java.util.Calendar.getInstance()): List<String> {
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        val isWeekend = (dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY)
        val isFridayAfternoon = (dayOfWeek == java.util.Calendar.FRIDAY && hour >= 16)

        return when {
            isWeekend || isFridayAfternoon -> if (isInformal) {
                listOf("즐겁고 편안한 주말 보내~", "주말 잘 보내고 다음 주에 봐!", "주말 푹 쉬어!")
            } else {
                listOf("즐겁고 편안한 주말 보내세요!", "행복하고 따뜻한 주말 되시길 바랍니다.", "주말 편히 쉬시고 다음 주에 뵙겠습니다.")
            }
            hour in 5..10 -> if (isInformal) {
                listOf("좋은 아침! 오늘도 좋은 하루 보내~", "오늘 하루도 파이팅해!", "좋은 아침이야!")
            } else {
                listOf("좋은 아침입니다. 활기찬 하루 보내세요!", "오늘 하루도 뜻깊은 하루 되시길 바랍니다.", "출근길 조심하시고 좋은 하루 보내세요!")
            }
            hour in 11..13 -> if (isInformal) {
                listOf("점심 맛있게 먹어!", "맛점하고 이따 연락해~", "점심 든든하게 챙겨 먹어!")
            } else {
                listOf("맛있는 점심 식사 하세요!", "점심 맛있게 드시고 오후에도 힘내세요!", "식사 든든히 챙겨 드세요!")
            }
            hour in 17..20 -> if (isInformal) {
                listOf("오늘도 고생 많았어, 조심히 들어가!", "퇴근 잘하고 푹 쉬어!", "오늘 하루도 수고했어!")
            } else {
                listOf("오늘 하루도 정말 수고 많으셨습니다.", "퇴근길 조심히 들어가시고 편안한 저녁 보내세요.", "고생 많으셨습니다. 내일 뵙겠습니다.")
            }
            hour in 21..24 || hour in 0..4 -> if (isInformal) {
                listOf("오늘 하루도 수고 많았어, 편안한 밤 보내!", "잘 자! 좋은 꿈 꿔~", "푹 자고 내일 봐!")
            } else {
                listOf("편안하고 아늑한 밤 보내세요.", "오늘 하루도 고생 많으셨습니다. 안녕히 주무세요.", "내일 뵙겠습니다. 편안한 밤 되세요.")
            }
            else -> if (isInformal) {
                listOf("오늘 하루도 힘내자!", "오후에도 파이팅해!", "좋은 하루 보내~")
            } else {
                listOf("오늘 하루도 좋은 일 가득하시길 바랍니다.", "오후에도 파이팅하시길 바랍니다.", "항상 감사드립니다.")
            }
        }
    }
}

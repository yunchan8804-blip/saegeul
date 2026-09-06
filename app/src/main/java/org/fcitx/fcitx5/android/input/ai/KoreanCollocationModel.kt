/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

/**
 * High-speed On-Device Korean Collocation & Next-Word Transition Model.
 * Provides rich built-in Bigram transitions for conversational and business contexts,
 * particle-aware next-word completions, and tone-adaptive word candidates.
 */
class KoreanCollocationModel {

    /**
     * Built-in high-frequency Bigram transitions for Korean conversational & work contexts.
     * Maps the preceding word (or stem) to the most natural subsequent words.
     */
    private val honorificBigrams: Map<String, List<String>> = mapOf(
        "오늘" to listOf("저녁", "점심", "일정", "하루도", "회의", "배포", "날씨", "퇴근"),
        "내일" to listOf("오전", "오후", "몇 시", "회의", "일정", "시간", "봬요", "출근"),
        "모레" to listOf("오전", "오후", "일정", "회의", "시간"),
        "어제" to listOf("말씀드린", "보내드린", "회의", "통화한", "요청하신"),
        "지금" to listOf("이동 중입니다", "확인 중입니다", "출발했습니다", "도착했습니다", "회의 중입니다"),
        "시간" to listOf("괜찮으실 때", "되시나요?", "있으세요?", "맞춰보겠습니다", "알려주시면"),
        "일정" to listOf("조율", "확인", "공유", "변경", "안내", "부탁드립니다"),
        "약속" to listOf("시간", "장소", "일정", "잡으시죠"),
        "회의" to listOf("참석", "일정", "준비", "내용", "회의실", "자료", "시작"),
        "미팅" to listOf("일정", "준비", "자료", "참석", "시간"),
        "자료" to listOf("공유", "검토", "확인", "송부", "준비", "정리", "전달"),
        "보고서" to listOf("작성", "검토", "송부", "공유", "제출"),
        "기획안" to listOf("검토", "공유", "송부", "의견"),
        "배포" to listOf("완료", "준비", "진행", "모니터링", "일정", "승인"),
        "빌드" to listOf("완료", "성공", "실패", "확인"),
        "커밋" to listOf("완료", "반영", "내역", "푸시"),
        "머지" to listOf("완료", "요청", "승인", "진행"),
        "코드" to listOf("리뷰", "검토", "수정", "반영"),
        "확인" to listOf("부탁드립니다", "감사합니다", "완료했습니다", "후 연락드리겠습니다", "했습니다"),
        "검토" to listOf("부탁드립니다", "후 회신드리겠습니다", "완료했습니다", "진행하겠습니다"),
        "공유" to listOf("부탁드립니다", "드립니다", "감사합니다", "완료했습니다"),
        "송부" to listOf("드립니다", "드렸습니다", "부탁드립니다"),
        "전달" to listOf("드렸습니다", "부탁드립니다", "받았습니다"),
        "수고" to listOf("많으셨습니다", "하셨습니다", "하세요", "많으세요"),
        "고생" to listOf("많으셨습니다", "하셨습니다"),
        "감사" to listOf("드립니다", "합니다", "의 말씀 전합니다"),
        "고맙" to listOf("습니다", "다는 말씀 전합니다"),
        "죄송" to listOf("합니다", "하지만", "스런 말씀이지만"),
        "도착" to listOf("했습니다", "해서 연락드리겠습니다", "예정입니다", "직전입니다"),
        "출발" to listOf("했습니다", "예정입니다", "준비 중입니다"),
        "연락" to listOf("드리겠습니다", "주세요", "바랍니다", "기다리겠습니다"),
        "회신" to listOf("부탁드립니다", "드리겠습니다", "기다리겠습니다"),
        "문의" to listOf("드립니다", "사항이 있습니다", "답변드립니다"),
        "식사" to listOf("맛있게 하세요", "하셨나요?", "대접하겠습니다", "자리"),
        "점심" to listOf("맛있게 드세요", "식사하셨나요?", "메뉴", "시간"),
        "저녁" to listOf("맛있게 드세요", "식사", "시간 괜찮으세요?", "약속"),
        "커피" to listOf("한잔하실래요?", "한잔하시죠", "대접하겠습니다"),
        "좋은" to listOf("하루 보내세요", "아침입니다", "저녁 되세요", "주말 보내세요", "결과 있기를 바랍니다"),
        "편안한" to listOf("저녁 보내세요", "밤 되세요", "주말 보내세요", "하루 되세요"),
        "따뜻한" to listOf("하루 보내세요", "격려 감사드립니다", "차 한잔"),
        "조심히" to listOf("들어가세요", "오세요", "다녀오세요"),
        "천천히" to listOf("오세요", "검토해 주세요", "말씀해 주세요", "하셔도 됩니다"),
        "언제든" to listOf("편하게 말씀해 주세요", "연락 주세요", "물어보세요"),
        "다음에" to listOf("꼭 뵙겠습니다", "시간 맞춰서 봬요", "대접하겠습니다"),
        "항상" to listOf("감사드립니다", "응원합니다", "수고 많으십니다"),
        "진심으로" to listOf("감사드립니다", "축하드립니다", "응원합니다"),
        "축하" to listOf("드립니다! 🎉", "의 말씀 전합니다", "해요")
    )

    private val informalBigrams: Map<String, List<String>> = mapOf(
        "오늘" to listOf("저녁", "점심", "뭐해?", "몇 시에 볼까?", "회의", "배포", "날씨 좋다"),
        "내일" to listOf("몇 시에 볼까?", "점심 먹자", "시간 돼?", "어디서 만날까?", "회의"),
        "모레" to listOf("시간 돼?", "볼까?"),
        "어제" to listOf("말한 거", "보낸 거", "재밌었어", "고마웠어"),
        "지금" to listOf("가는 중이야", "도착했어", "출발했어", "어디야?", "뭐해?"),
        "시간" to listOf("돼?", "있어?", "맞춰보자", "괜찮아?"),
        "일정" to listOf("확인해볼게", "다시 잡자", "어때?"),
        "약속" to listOf("시간", "장소", "어디로 할까?"),
        "회의" to listOf("끝나고 연락할게", "들어갈게", "준비 다 됐어"),
        "자료" to listOf("보냈어 확인해줘", "다 만들었어", "공유해줄게"),
        "보고서" to listOf("다 썼어", "수정했어", "확인해봐"),
        "배포" to listOf("끝났어!", "정상 작동 중이야", "확인해봐"),
        "머지" to listOf("했어", "해줘", "바로 할게"),
        "코드" to listOf("리뷰해줘", "수정했어", "올렸어"),
        "확인" to listOf("했어!", "고마워", "해볼게", "하고 알려줄게"),
        "검토" to listOf("해볼게", "끝났어"),
        "수고" to listOf("했어!", "많았어", "해!"),
        "고생" to listOf("했어 오늘도!", "많았어"),
        "감사" to listOf("해 완전!", "감사!"),
        "고마워" to listOf("정말!", "덕분이야", "완전 땡큐!"),
        "죄송" to listOf("해 미안!", "미안해"),
        "미안" to listOf("해 조금 늦어!", "다음에 내가 살게"),
        "도착" to listOf("했어!", "5분 전이야", "하면 연락할게"),
        "출발" to listOf("했어 지금!", "곧 가!"),
        "연락" to listOf("할게 끝나고", "줘!", "기다릴게"),
        "밥" to listOf("먹었어?", "먹자!", "사줄게"),
        "점심" to listOf("뭐 먹을래?", "먹었어?", "먹으러 가자"),
        "저녁" to listOf("먹자!", "뭐 먹을까?", "시간 돼?"),
        "커피" to listOf("한잔하자!", "마시러 가자", "사줄게"),
        "치맥" to listOf("하러 갈래?", "어때?", "콜!"),
        "좋은" to listOf("하루 보내!", "아침이야", "주말 보내~", "꿈 꿔!"),
        "조심히" to listOf("들어가!", "와 천천히", "다녀와~"),
        "천천히" to listOf("와!", "해 괜찮아", "준비해"),
        "언제든" to listOf("편하게 물어봐!", "연락해~"),
        "다음에" to listOf("꼭 보자!", "밥 한번 먹자", "시간 맞추자"),
        "축하" to listOf("해! 🎉", "진짜 대단해!", "완전 축하해!")
    )

    /**
     * Particles and endings transitions.
     * When context ends with a Korean particle (-을/를, -이/가, -에, -에서, -으로/로),
     * suggest high-relevance verbs and adjectives.
     */
    private val particleTransitionsHonorific: Map<String, List<String>> = mapOf(
        "을" to listOf("확인했습니다", "부탁드립니다", "검토하겠습니다", "보내드립니다", "공유드립니다", "진행하겠습니다"),
        "를" to listOf("확인했습니다", "부탁드립니다", "검토하겠습니다", "보내드립니다", "공유드립니다", "진행하겠습니다"),
        "이" to listOf("필요합니다", "있습니다", "어려울 것 같습니다", "완료되었습니다", "맞습니다", "좋습니다"),
        "가" to listOf("필요합니다", "있습니다", "어려울 것 같습니다", "완료되었습니다", "맞습니다", "좋습니다"),
        "에" to listOf("도착했습니다", "참석하겠습니다", "뵙겠습니다", "연락드리겠습니다", "진행하겠습니다", "감사드립니다"),
        "에서" to listOf("뵙겠습니다", "만나요", "진행됩니다", "기다리겠습니다", "모이겠습니다"),
        "으로" to listOf("진행하겠습니다", "보내드리겠습니다", "변경되었습니다", "정리했습니다"),
        "로" to listOf("진행하겠습니다", "보내드리겠습니다", "변경되었습니다", "정리했습니다"),
        "과" to listOf("함께", "관련하여", "동일하게"),
        "와" to listOf("함께", "관련하여", "동일하게"),
        "도" to listOf("잘 부탁드립니다", "감사합니다", "함께 확인하겠습니다"),
        "는" to listOf("어떠신가요?", "확인하셨나요?", "괜찮으신가요?", "어려울 것 같습니다"),
        "은" to listOf("어떠신가요?", "확인하셨나요?", "괜찮으신가요?", "어려울 것 같습니다")
    )

    private val particleTransitionsInformal: Map<String, List<String>> = mapOf(
        "을" to listOf("확인했어", "부탁해", "보내줄게", "공유할게", "수정했어", "볼래?"),
        "를" to listOf("확인했어", "부탁해", "보내줄게", "공유할게", "수정했어", "볼래?"),
        "이" to listOf("필요해", "있어", "어려울 것 같아", "끝났어", "맞아", "좋아!"),
        "가" to listOf("필요해", "있어", "어려울 것 같아", "끝났어", "맞아", "좋아!"),
        "에" to listOf("도착했어", "갈게", "보자!", "연락할게", "있어?"),
        "에서" to listOf("보자!", "만나자", "기다릴게", "볼까?"),
        "으로" to listOf("할게", "보낼게", "바꿨어", "가자"),
        "로" to listOf("할게", "보낼게", "바꿨어", "가자"),
        "과" to listOf("같이", "함께"),
        "와" to listOf("같이", "함께"),
        "도" to listOf("부탁해", "고마워", "같이 보자"),
        "는" to listOf("어때?", "확인했어?", "괜찮아?", "어려워"),
        "은" to listOf("어때?", "확인했어?", "괜찮아?", "어려워")
    )

    private val dynamicHonorificBigrams = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    private val dynamicInformalBigrams = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()

    /**
     * Injects dynamically learned bigram transitions (e.g. from Typing DNA compiler).
     * Learned transitions take highest priority over static built-in transitions.
     */
    fun injectDynamicBigrams(bigrams: Map<String, List<String>>, isInformal: Boolean) {
        val target = if (isInformal) dynamicInformalBigrams else dynamicHonorificBigrams
        for ((prev, nextList) in bigrams) {
            val list = target.getOrPut(prev) { mutableListOf() }
            for (next in nextList) {
                if (!list.contains(next)) {
                    list.add(0, next)
                }
            }
        }
    }

    fun clearDynamicBigrams() {
        dynamicHonorificBigrams.clear()
        dynamicInformalBigrams.clear()
    }

    /**
     * Finds next word suggestions based on the last typed word or trailing particle.
     */
    fun predictNextWords(
        lastWord: String,
        isInformal: Boolean,
        limit: Int = 4
    ): List<String> {
        val clean = lastWord.trim()
        if (clean.isBlank()) return emptyList()

        val dynamicMap = if (isInformal) dynamicInformalBigrams else dynamicHonorificBigrams
        val dynamicList = dynamicMap[clean] ?: emptyList()

        val bigrams = if (isInformal) informalBigrams else honorificBigrams
        val particleMap = if (isInformal) particleTransitionsInformal else particleTransitionsHonorific

        // 0. Learned Dynamic Bigram lookup (Highest priority - User's personal Typing DNA)
        val direct = bigrams[clean] ?: emptyList()
        val combinedDirect = (dynamicList + direct).distinct()
        if (combinedDirect.isNotEmpty()) {
            return combinedDirect.take(limit)
        }

        // 2. Trailing Particle lookup (e.g. "회의를" -> endsWith "를" -> ["확인했습니다", ...])
        for ((particle, completions) in particleMap) {
            if (clean.endsWith(particle) && clean.length > particle.length) {
                return completions.take(limit)
            }
        }

        // 3. Stem matching (e.g. "회의실에서" -> base matches "회의" or "회의실")
        for ((key, completions) in bigrams) {
            if (clean.startsWith(key) && clean.length <= key.length + 3) {
                return completions.take(limit)
            }
        }

        return emptyList()
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.input.ai.graph.OnDeviceEgoGraphDatabase

/**
 * Knowledge triple representation extracted from conversation/utterance streams.
 */
data class KnowledgeTriple(
    val src: String,
    val dst: String,
    val relation: String,
    val weight: Float = 1.0f
)

/**
 * EAI-13: Background WorkManager worker executing knowledge extraction while
 * the device is charging and idle.
 *
 * Extracts semantic knowledge triples (src, dst, relation) from user utterances
 * and upserts them into the OnDeviceEgoGraphDatabase within a single atomic transaction.
 */
class GemmaGraphExtractWorker(
    context: Context,
    params: WorkerParameters,
    private val dbProvider: () -> OnDeviceEgoGraphDatabase = { OnDeviceEgoGraphDatabase(context) }
) : CoroutineWorker(context, params) {

    constructor(context: Context, params: WorkerParameters) : this(
        context,
        params,
        { OnDeviceEgoGraphDatabase(context) }
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val utterances = inputData.getStringArray(KEY_UTTERANCES)?.toList() ?: emptyList()
            val allTriples = extractTriples(utterances)
            if (allTriples.isNotEmpty()) {
                val db = dbProvider()
                upsertTriples(allTriples, db)
            }
            Result.success()
        } catch (_: Throwable) {
            Result.failure()
        }
    }

    /**
     * Extracts knowledge triples from a collection of utterance strings.
     */
    fun extractTriples(utterances: List<String>): List<KnowledgeTriple> {
        return Companion.extractTriples(utterances)
    }

    /**
     * Extracts knowledge triples from a single utterance string.
     */
    fun extractTriples(utterance: String): List<KnowledgeTriple> {
        return Companion.extractTriples(utterance)
    }

    /**
     * Upserts knowledge triples into the database in a single atomic transaction.
     */
    fun upsertTriples(triples: List<KnowledgeTriple>, db: OnDeviceEgoGraphDatabase) {
        Companion.upsertTriples(triples, db)
    }

    companion object {
        const val KEY_UTTERANCES = "key_utterances"

        private val TEMPORAL_KEYWORDS = setOf("내일", "오늘", "모레", "어제", "지금", "오전", "오후")
        private val TIME_DIGIT_REGEX = Regex("""^\d{1,2}(?:시(?:\d{1,2}분)?|:\d{2})$""")

        private val MENU_REGEX = Regex("""(점심|저녁|아침|식사|야식)\s*(?:메뉴|는|은)?\s*([가-힣A-Za-z0-9]+)""")
        private val MEETING_REGEX = Regex("""([가-힣A-Za-z0-9\s]+?(?:회의|미팅|세미나|약속|일정))\s+([가-힣A-Za-z0-9]*(?:참석|진행|준비|취소|연기|완료))""")
        private val LOCATION_REGEX = Regex("""([가-힣A-Za-z0-9]+(?:역|동|구|점|카페|사무실)?)\s*(?:에서| 장소)\s+([가-힣A-Za-z0-9]+)""")
        private val TASK_REGEX = Regex("""([가-힣A-Za-z0-9\s]+?)\s+(작성|검토|제출|배포)\s+(완료|요청|예정)""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val PUNCTUATION_CHARS = ".,!?~…\"'()[]{}<>:;。？！、 \t\n\r".toSet()

        /**
         * Creates a OneTimeWorkRequest with charging and idle constraints.
         */
        fun createWorkRequest(): OneTimeWorkRequest {
            return createWorkRequest(emptyArray())
        }

        /**
         * Creates a OneTimeWorkRequest with charging and idle constraints and input utterances.
         */
        fun createWorkRequest(utterances: Array<String>): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build()

            val stringArray: Array<String?> = Array(utterances.size) { i -> utterances[i] }
            val data = Data.Builder()
                .putStringArray(KEY_UTTERANCES, stringArray)
                .build()

            return OneTimeWorkRequestBuilder<GemmaGraphExtractWorker>()
                .setConstraints(constraints)
                .setInputData(data)
                .build()
        }

        /**
         * Extracts knowledge triples from a collection of utterance strings.
         */
        fun extractTriples(utterances: List<String>): List<KnowledgeTriple> {
            val result = mutableListOf<KnowledgeTriple>()
            for (utterance in utterances) {
                result.addAll(extractTriples(utterance))
            }
            return result
        }

        /**
         * Extracts knowledge triples from a single utterance string.
         */
        fun extractTriples(utterance: String): List<KnowledgeTriple> {
            if (utterance.isBlank()) return emptyList()
            val triples = mutableListOf<KnowledgeTriple>()

            // Clean time/date adverbials by token matching to handle Korean morphology correctly
            val rawTokens = utterance.trim().split(WHITESPACE_REGEX)
            val filteredTokens = rawTokens.filterNot { token ->
                val cleanToken = token.trim { it in PUNCTUATION_CHARS }
                cleanToken in TEMPORAL_KEYWORDS || TIME_DIGIT_REGEX.matches(cleanToken)
            }
            val stripped = filteredTokens.joinToString(" ").trim()
            if (stripped.isBlank()) return emptyList()

            // 1. Menu/Food Pattern (e.g. "점심 메뉴 파스타", "저녁 식사 피자")
            val menuMatch = MENU_REGEX.find(stripped)
            if (menuMatch != null) {
                val category = menuMatch.groupValues[1].trim()
                val food = menuMatch.groupValues[2].trim()
                if (category.isNotBlank() && food.isNotBlank()) {
                    triples.add(KnowledgeTriple(src = category, dst = food, relation = "메뉴"))
                }
            }

            // 2. Meeting/Schedule + Action Pattern (e.g. "팀 회의 참석", "주간 미팅 준비", "약속 취소")
            val meetingMatch = MEETING_REGEX.find(stripped)
            if (meetingMatch != null) {
                val subject = meetingMatch.groupValues[1].trim()
                val action = meetingMatch.groupValues[2].trim()
                if (subject.isNotBlank() && action.isNotBlank()) {
                    triples.add(KnowledgeTriple(src = subject, dst = action, relation = "동작"))
                }
            }

            // 3. Location/Place Pattern (e.g. "강남역에서 미팅 진행", "카페에서 공부")
            val locationMatch = LOCATION_REGEX.find(stripped)
            if (locationMatch != null) {
                val loc = locationMatch.groupValues[1].trim()
                val act = locationMatch.groupValues[2].trim()
                if (loc.isNotBlank() && act.isNotBlank()) {
                    triples.add(KnowledgeTriple(src = loc, dst = act, relation = "장소"))
                }
            }

            // 4. Task/Completion Pattern (e.g. "보고서 작성 완료", "프로젝트 기획서 작성 완료")
            val taskMatch = TASK_REGEX.find(stripped)
            if (taskMatch != null) {
                val task = taskMatch.groupValues[1].trim()
                val act = taskMatch.groupValues[2].trim()
                val status = taskMatch.groupValues[3].trim()
                if (task.isNotBlank()) {
                    triples.add(KnowledgeTriple(src = task, dst = status, relation = act))
                }
            }

            // 5. Fallback for unclassified multi-word sentences
            if (triples.isEmpty()) {
                val words = stripped.split(WHITESPACE_REGEX)
                    .map { it.trim { c -> c in PUNCTUATION_CHARS } }
                    .filter { it.isNotBlank() && it.length >= 2 }
                if (words.size >= 2) {
                    triples.add(KnowledgeTriple(src = words[0], dst = words[1], relation = "연관"))
                }
            }

            return triples
        }

        /**
         * Upserts knowledge triples into the database in a single atomic transaction.
         */
        fun upsertTriples(triples: List<KnowledgeTriple>, db: OnDeviceEgoGraphDatabase) {
            if (triples.isEmpty()) return
            val writableDb = db.writableDatabase
            writableDb.beginTransaction()
            try {
                for (triple in triples) {
                    db.upsertEntity(
                        id = triple.src,
                        label = triple.src,
                        category = triple.relation,
                        weight = triple.weight
                    )
                    db.upsertEntity(
                        id = triple.dst,
                        label = triple.dst,
                        category = triple.relation,
                        weight = triple.weight
                    )
                    db.upsertEdge(
                        src = triple.src,
                        dst = triple.dst,
                        relation = triple.relation,
                        weight = triple.weight
                    )
                }
                writableDb.setTransactionSuccessful()
            } finally {
                writableDb.endTransaction()
            }
        }
    }
}

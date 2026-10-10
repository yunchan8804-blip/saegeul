/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

import org.fcitx.fcitx5.android.input.ai.vault.PlainVaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultCipher
import org.fcitx.fcitx5.android.input.ai.vault.VaultFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max

/**
 * 사용자의 손버릇을 기기 안에서만 배운다: 글자 키 군집([CLUSTERS])별로 "그 군집의 키를 의도했을 때 터치가 키
 * 중심에서 평균 얼마나 치우치나"(키 폭·높이로 나눈 dx, dy)와 표본 수. 원시 좌표와 어절은 저장하지 않는다.
 *
 * Sivek & Riley, "Spatial model personalization in Gboard"(arXiv 2209.11311)가 키를 군집으로 묶고 군집마다
 * 평균 오프셋만 배우는 방식을 따른다. 논문은 7개 군집을 썼지만 군집 정의를 공개하지 않아, 새글은 두벌식 글자
 * 키를 3줄 × 왼쪽·가운데·오른쪽 3구역으로 나눈 9개로 정했다. 군집 수와 경계는 논문 값이 아니다.
 *
 * 배우는 때는 확정된 어절의 터치 흔적이 그 어절의 키 순서와 정확히 맞고 그 어절이 사전 단어일 때
 * ([learnConfirmedWord])와 ✏️ 교정 후보를 눌렀을 때([learnTappedCorrection])다. 갱신은 지수 평균
 * [ALPHA]이고, 표본이 적을 때는 단순 평균이다(첫 표본이 평균이 된다). 군집마다 표본이 [MIN_SAMPLES]개
 * 이상일 때만 [bias]가 값을 내고, 크기는 키 크기의 [MAX_BIAS]배로 막는다. 같은 군집의 키는 표본과 평균을
 * 함께 쓴다.
 *
 * 저장 형식은 [CorrectionPatternStore]와 같은 금고 파일([VaultFile]) 암호화이고, 내용은 군집별 평균과 표본
 * 수뿐이다. 군집 도입 전의 키별 형식("keys" 배열) 파일은 읽지 않고 버려, 빈 상태에서 다시 배운다.
 */
class TouchHabitStore(
    private val storeFile: File? = null,
    cipher: VaultCipher = PlainVaultCipher
) : TouchHabit {

    private class Stat(var dx: Float, var dy: Float, var count: Int)

    private val stats = HashMap<String, Stat>()
    private val vaultFile: VaultFile? = storeFile?.let { VaultFile(it, cipher, VaultFile.aadFor(it.name)) }
    private val persistenceLock = Any()

    // 교정 탐색이 키마다 수백만 번 읽으므로 값이 바뀔 때만 새로 만드는 불변 사본을 둔다. 적용 중인 군집의 모든 키가 들어 있다.
    @Volatile
    private var applied: Map<Char, KeyBias> = emptyMap()

    init {
        vaultFile?.let { vf ->
            if (vf.exists()) loadFromDisk(vf)
        }
    }

    override fun bias(key: Char): KeyBias? = applied[key.lowercaseChar()]

    /** [key]가 속한 군집을 의도했을 때 지금까지 배운 표본 수. */
    @Synchronized
    fun sampleCount(key: Char): Int = clusterOf(key)?.let { stats[it]?.count } ?: 0

    /** [key]가 속한 군집의 배운 평균 치우침. 표본이 [MIN_SAMPLES]개 미만이어도 돌려주며, 배운 적이 없으면 null이다. */
    @Synchronized
    fun learnedMean(key: Char): KeyBias? =
        clusterOf(key)?.let { stats[it] }?.let { KeyBias(clampBias(it.dx), clampBias(it.dy)) }

    /**
     * 방금 확정한 [word]를 배운다. [word]의 키 순서가 [trace] 끝부분과 정확히 같고 [isKnownWord]가 참일 때만
     * 한 자리마다 그 키를 의도한 터치로 센다. 배웠으면 true다.
     */
    fun learnConfirmedWord(word: String, trace: TouchTrace?, isKnownWord: (String) -> Boolean): Boolean {
        if (trace == null || word.isEmpty() || !isKnownWord(word)) return false
        val keys = DubeolsikKeyMap.keySequence(word)
        val touches = trace.alignedTo(keys) ?: return false
        return learnPositions(touches, keys)
    }

    /**
     * ✏️ 교정 후보를 눌러 [typed]를 [corrected]로 고쳤을 때, 교정어의 키를 의도 키로 쓰고 원래 흔적을 그 키를
     * 누른 터치로 센다. 원래 흔적이 [typed]의 키 순서와 정확히 맞고 두 어절의 키 수가 같을 때만 배운다.
     */
    fun learnTappedCorrection(typed: String, corrected: String, trace: TouchTrace?): Boolean {
        if (trace == null) return false
        val typedKeys = DubeolsikKeyMap.keySequence(typed)
        val intendedKeys = DubeolsikKeyMap.keySequence(corrected)
        if (typedKeys.isEmpty() || typedKeys.length != intendedKeys.length) return false
        val touches = trace.alignedTo(typedKeys) ?: return false
        return learnPositions(touches, intendedKeys)
    }

    private fun learnPositions(touches: List<KeyTouch>, intendedKeys: String): Boolean {
        var learned = false
        synchronized(this) {
            for (i in touches.indices) {
                val intended = intendedKeys[i]
                val cluster = clusterOf(intended) ?: continue
                val (offsetX, offsetY) = touches[i].offsetFrom(intended) ?: continue
                // 의도한 키에서 한 키 크기 넘게 벗어난 터치는 손버릇이 아니라 다른 실수다.
                if (abs(offsetX) > REACH || abs(offsetY) > REACH) continue
                update(cluster, offsetX, offsetY)
                learned = true
            }
            if (learned) publishLocked()
        }
        return learned
    }

    private fun update(cluster: String, offsetX: Float, offsetY: Float) {
        val stat = stats[cluster]
        if (stat == null) {
            stats[cluster] = Stat(clampBias(offsetX), clampBias(offsetY), 1)
            return
        }
        val count = (stat.count + 1).coerceAtMost(MAX_COUNT)
        val weight = max(ALPHA, 1f / count)
        stat.dx = clampBias(stat.dx + weight * (offsetX - stat.dx))
        stat.dy = clampBias(stat.dy + weight * (offsetY - stat.dy))
        stat.count = count
    }

    private fun publishLocked() {
        val next = HashMap<Char, KeyBias>()
        for ((cluster, stat) in stats) {
            if (stat.count < MIN_SAMPLES) continue
            val bias = KeyBias(stat.dx, stat.dy)
            for (key in cluster) next[key] = bias
        }
        applied = next
    }

    fun save() {
        val vf = vaultFile ?: return
        synchronized(persistenceLock) {
            val snapshot = synchronized(this) {
                val clusters = JSONArray()
                stats.forEach { (cluster, stat) ->
                    clusters.put(
                        JSONObject().apply {
                            put("cluster", cluster)
                            put("dx", stat.dx.toDouble())
                            put("dy", stat.dy.toDouble())
                            put("count", stat.count)
                        }
                    )
                }
                JSONObject().put("clusters", clusters)
            }
            try {
                vf.writeText(snapshot.toString())
            } catch (exception: IOException) {
                // A read-blocked or unwritable file keeps its previous bytes; learning stays in memory.
                Timber.w(exception, "Touch habits could not be saved")
            }
        }
    }

    fun clear() {
        synchronized(persistenceLock) {
            synchronized(this) {
                stats.clear()
                applied = emptyMap()
            }
            vaultFile?.deleteForUser() ?: storeFile?.delete()
        }
    }

    private fun loadFromDisk(vaultFile: VaultFile) {
        val content = try {
            vaultFile.readTextAndMigrate()
        } catch (exception: Exception) {
            // The file stays read-blocked, so save() cannot replace it with the empty state.
            Timber.w(exception, "Touch habits could not be read")
            return
        } ?: return
        if (content.isBlank()) return
        val loaded = HashMap<String, Stat>()
        try {
            // 키별 형식 파일에는 "clusters"가 없으므로 아무것도 읽지 않고 빈 상태로 시작한다.
            val clusters = JSONObject(content).optJSONArray("clusters")
            if (clusters != null) {
                for (i in 0 until clusters.length()) {
                    val entry = clusters.getJSONObject(i)
                    val cluster = entry.getString("cluster")
                    if (cluster !in CLUSTERS) continue
                    val count = entry.getInt("count")
                    if (count <= 0) continue
                    loaded[cluster] = Stat(
                        clampBias(entry.getDouble("dx").toFloat()),
                        clampBias(entry.getDouble("dy").toFloat()),
                        count.coerceAtMost(MAX_COUNT)
                    )
                }
            }
        } catch (exception: JSONException) {
            vaultFile.markUnreadable()
            Timber.w("Touch habits could not be parsed (%s)", exception.javaClass.simpleName)
            return
        }
        synchronized(this) {
            stats.putAll(loaded)
            publishLocked()
        }
    }

    private fun clampBias(value: Float): Float = value.coerceIn(-MAX_BIAS, MAX_BIAS)

    companion object {
        /**
         * 커서 앞 글 [textBeforeCursor]가 스페이스 하나로 끝날 때 그 앞 어절(앞뒤 문장부호 제외). 스페이스가 연속이거나
         * 앞 어절이 없으면 null이다. 같은 어절을 스페이스를 거듭 눌러 여러 번 배우지 않도록 하기 위해서다.
         */
        fun confirmedWordBefore(textBeforeCursor: String): String? {
            if (textBeforeCursor.length < 2 || !textBeforeCursor.last().isWhitespace()) return null
            val withoutSpace = textBeforeCursor.dropLast(1)
            if (withoutSpace.last().isWhitespace()) return null
            val word = withoutSpace.takeLastWhile { !it.isWhitespace() }.trim { !it.isLetter() }
            return word.ifEmpty { null }
        }

        /**
         * 글자 키 군집. 두벌식 글자 키를 3줄 × 왼쪽·가운데·오른쪽 3구역으로 나눈 9개이고, 한 군집은 그 키들을
         * 이어 붙인 문자열이다.
         * 1행 ㅂㅈㄷ | ㄱㅅㅛ | ㅕㅑㅐㅔ, 2행 ㅁㄴㅇ | ㄹㅎㅗ | ㅓㅏㅣ, 3행 ㅋㅌㅊ | ㅍㅠ | ㅜㅡ.
         */
        val CLUSTERS: List<String> = listOf("qwe", "rty", "uiop", "asd", "fgh", "jkl", "zxc", "vb", "nm")

        private val CLUSTER_OF_KEY: Map<Char, String> =
            CLUSTERS.flatMap { cluster -> cluster.map { key -> key to cluster } }.toMap()

        /** 글자 키 [key](대소문자 무관)가 속한 군집. 글자 키가 아니면 null이다. */
        fun clusterOf(key: Char): String? = CLUSTER_OF_KEY[key.lowercaseChar()]

        /** 지수 평균 계수. */
        const val ALPHA = 0.02f

        /** 군집마다 이 수 이상 배운 뒤에야 [bias]가 값을 낸다. */
        const val MIN_SAMPLES = 30

        /** 배운 치우침의 최대 크기(키 폭·높이의 배수). */
        const val MAX_BIAS = 0.35f

        // 의도한 키 중심에서 이 안(키 폭·높이의 배수)에 닿은 터치만 센다.
        private const val REACH = 1.0f

        private const val MAX_COUNT = 1_000_000
    }
}

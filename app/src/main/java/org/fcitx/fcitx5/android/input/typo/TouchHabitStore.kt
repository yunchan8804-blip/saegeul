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
 * 사용자의 손버릇을 기기 안에서만 배운다: 글자 키별로 "그 키를 의도했을 때 터치가 키 중심에서 평균 얼마나
 * 치우치나"(키 폭·높이로 나눈 dx, dy)와 표본 수. 원시 좌표와 어절은 저장하지 않는다.
 *
 * 배우는 때는 확정된 어절의 터치 흔적이 그 어절의 키 순서와 정확히 맞고 그 어절이 사전 단어일 때
 * ([learnConfirmedWord])와 ✏️ 교정 후보를 눌렀을 때([learnTappedCorrection])다. 갱신은 지수 평균
 * [ALPHA]이고, 표본이 적을 때는 단순 평균이다(첫 표본이 평균이 된다). 키마다 표본이 [MIN_SAMPLES]개
 * 이상일 때만 [bias]가 값을 내고, 크기는 키 크기의 [MAX_BIAS]배로 막는다.
 *
 * 저장 형식은 [CorrectionPatternStore]와 같은 금고 파일([VaultFile]) 암호화다.
 */
class TouchHabitStore(
    private val storeFile: File? = null,
    cipher: VaultCipher = PlainVaultCipher
) : TouchHabit {

    private class Stat(var dx: Float, var dy: Float, var count: Int)

    private val stats = HashMap<Char, Stat>()
    private val vaultFile: VaultFile? = storeFile?.let { VaultFile(it, cipher, VaultFile.aadFor(it.name)) }
    private val persistenceLock = Any()

    // 교정 탐색이 키마다 수백만 번 읽으므로 값이 바뀔 때만 새로 만드는 불변 사본을 둔다.
    @Volatile
    private var applied: Map<Char, KeyBias> = emptyMap()

    init {
        vaultFile?.let { vf ->
            if (vf.exists()) loadFromDisk(vf)
        }
    }

    override fun bias(key: Char): KeyBias? = applied[key.lowercaseChar()]

    /** [key]를 의도했을 때 지금까지 배운 표본 수. */
    @Synchronized
    fun sampleCount(key: Char): Int = stats[key.lowercaseChar()]?.count ?: 0

    /** [key]의 배운 평균 치우침. 표본이 [MIN_SAMPLES]개 미만이어도 돌려주며, 배운 적이 없으면 null이다. */
    @Synchronized
    fun learnedMean(key: Char): KeyBias? =
        stats[key.lowercaseChar()]?.let { KeyBias(clampBias(it.dx), clampBias(it.dy)) }

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
                val (offsetX, offsetY) = touches[i].offsetFrom(intended) ?: continue
                // 의도한 키에서 한 키 크기 넘게 벗어난 터치는 손버릇이 아니라 다른 실수다.
                if (abs(offsetX) > REACH || abs(offsetY) > REACH) continue
                update(intended.lowercaseChar(), offsetX, offsetY)
                learned = true
            }
            if (learned) publishLocked()
        }
        return learned
    }

    private fun update(key: Char, offsetX: Float, offsetY: Float) {
        val stat = stats[key]
        if (stat == null) {
            stats[key] = Stat(clampBias(offsetX), clampBias(offsetY), 1)
            return
        }
        val count = (stat.count + 1).coerceAtMost(MAX_COUNT)
        val weight = max(ALPHA, 1f / count)
        stat.dx = clampBias(stat.dx + weight * (offsetX - stat.dx))
        stat.dy = clampBias(stat.dy + weight * (offsetY - stat.dy))
        stat.count = count
    }

    private fun publishLocked() {
        applied = stats.filterValues { it.count >= MIN_SAMPLES }
            .mapValues { (_, stat) -> KeyBias(stat.dx, stat.dy) }
    }

    fun save() {
        val vf = vaultFile ?: return
        synchronized(persistenceLock) {
            val snapshot = synchronized(this) {
                val keys = JSONArray()
                stats.forEach { (key, stat) ->
                    keys.put(
                        JSONObject().apply {
                            put("key", key.toString())
                            put("dx", stat.dx.toDouble())
                            put("dy", stat.dy.toDouble())
                            put("count", stat.count)
                        }
                    )
                }
                JSONObject().put("keys", keys)
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
        val loaded = HashMap<Char, Stat>()
        try {
            val keys = JSONObject(content).optJSONArray("keys")
            if (keys != null) {
                for (i in 0 until keys.length()) {
                    val entry = keys.getJSONObject(i)
                    val key = entry.getString("key").singleOrNull() ?: continue
                    val count = entry.getInt("count")
                    if (count <= 0) continue
                    loaded[key.lowercaseChar()] = Stat(
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

        /** 지수 평균 계수. */
        const val ALPHA = 0.02f

        /** 키마다 이 수 이상 배운 뒤에야 [bias]가 값을 낸다. */
        const val MIN_SAMPLES = 30

        /** 배운 치우침의 최대 크기(키 폭·높이의 배수). */
        const val MAX_BIAS = 0.35f

        // 의도한 키 중심에서 이 안(키 폭·높이의 배수)에 닿은 터치만 센다.
        private const val REACH = 1.0f

        private const val MAX_COUNT = 1_000_000
    }
}

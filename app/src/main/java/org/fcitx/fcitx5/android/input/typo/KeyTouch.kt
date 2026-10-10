/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

import kotlin.math.max
import kotlin.math.min

/** 글자 키 하나의 화면 위 중심 좌표와 크기. 단위는 [KeyLayoutSnapshot] 안에서만 같으면 된다. */
class KeyCell(val centerX: Float, val centerY: Float, val width: Float, val height: Float)

/**
 * 터치한 순간 키보드에서 글자 키들의 실제 배치. 키 문자(소문자)별 [KeyCell]이다.
 * 배치가 바뀔 때만 새로 만들고, 그 사이 눌린 모든 [KeyTouch]가 같은 객체를 공유한다.
 */
class KeyLayoutSnapshot(private val cells: Map<Char, KeyCell>) {

    fun cell(key: Char): KeyCell? = cells[key.lowercaseChar()]
}

/**
 * 글자 키를 한 번 누른 흔적. [key]는 [DubeolsikKeyMap.keySequence]가 만드는 키 문자 형태다
 * (시프트가 의미 있는 7키만 대문자). [dx]·[dy]는 누른 키의 중심에서 터치 지점까지의 거리를
 * 그 키의 폭·높이로 나눈 값이라, 키 안쪽이면 -0.5..0.5 안이다.
 */
class KeyTouch(val key: Char, val dx: Float, val dy: Float, val layout: KeyLayoutSnapshot) {

    /**
     * 이 터치 지점이 [intended] 키의 중심에서 얼마나 떨어졌는지, 그 키의 폭·높이로 나눈 값. 같은 키면
     * [dx]·[dy] 그대로다. 두 키 중 하나가 배치에 없으면 null이다.
     */
    fun offsetFrom(intended: Char): Pair<Float, Float>? {
        if (key.lowercaseChar() == intended.lowercaseChar()) return dx to dy
        val typedCell = layout.cell(key) ?: return null
        val intendedCell = layout.cell(intended) ?: return null
        val touchX = typedCell.centerX + dx * typedCell.width
        val touchY = typedCell.centerY + dy * typedCell.height
        return (touchX - intendedCell.centerX) / intendedCell.width to
            (touchY - intendedCell.centerY) / intendedCell.height
    }
}

/**
 * 가장 최근 터치 흔적들의 고정된 사본. 시간 순서이고 마지막이 가장 최근이다.
 * 메모리에만 있으며 어디에도 저장하지 않는다.
 */
class TouchTrace internal constructor(private val touches: List<KeyTouch>) {

    /**
     * 흔적 끝부분의 키 문자 열이 [keys]와 정확히 같을 때만 그 부분을 키 위치별로 돌려준다.
     * 다르면 null이다. 어절이 이 자판의 터치로만 쳐지지 않았거나(물리 키보드, 붙여넣기, 후보 선택)
     * 지우고 고쳐 쳐서 키 순서가 달라진 경우이고, 이때는 좌표 없이 기존 격자 비용을 쓴다.
     */
    fun alignedTo(keys: String): List<KeyTouch>? {
        if (keys.isEmpty() || keys.length > touches.size) return null
        val start = touches.size - keys.length
        for (i in keys.indices) {
            if (touches[start + i].key != keys[i]) return null
        }
        return touches.subList(start, touches.size)
    }

    /** 마지막 [count]타를 뺀 흔적. 어절에서 꼬리(어미)를 떼고 앞부분만 교정할 때 쓴다. */
    fun withoutLast(count: Int): TouchTrace =
        TouchTrace(touches.subList(0, max(0, touches.size - count)))
}

/**
 * 터치 지점으로 계산하는 치환 비용. Sivek & Riley, "Spatial model personalization in Gboard"
 * (arXiv 2209.11311, MobileHCI 2022)의 터치 비용 `min{(Δx² + Δy²) / (2σ²), 치환 비용}`을 그대로 쓴다.
 * Δx·Δy는 의도한 키의 폭·높이로 나눈 "터치 지점 − 의도한 키 중심"이고, [habit]이 있으면 그 키가 속한
 * 군집의 배운 치우침만큼 의도한 키 중심을 옮긴 뒤 잰다.
 *
 * - [SIGMA]는 논문의 기본값 0.55다. 가로·세로에 같은 값을 쓴다.
 * - [SUBSTITUTION_CAP]은 논문의 "치환 비용" 자리에 들어가는 상한이다. AOSP LatinIME
 *   `scoring_params.cpp`가 탭 데이터로 수치 최적화한 상수 SUBSTITUTION_COST 0.3806과 INSERTION_COST
 *   0.7248의 비 0.525를 새글의 삽입·삭제 비용 0.9에 곱한 약 0.47이다. 단위는 nat 그대로이고, 키 경계
 *   (Δ = 0.5)에서 비용이 `(0.5 / 0.55)² / 2 ≈ 0.41`이 되어 기존 격자의 인접 키 비용 0.4와 같은 척도다.
 *
 * 터치가 의도한 키 중심에서 약 0.53칸(= σ × √(2 × 상한)) 넘게 떨어져 있으면 비용은 상한 [SUBSTITUTION_CAP]
 * 하나다. 옆 키 중앙을 누른 터치가 그렇다.
 *
 * 이 식은 의도한 키가 친 키의 격자 이웃(격자 치환 비용 [NEIGHBOR_GRID_MAX] 이하)일 때만 쓴다. Gboard는 먼 키도
 * 치환 상한으로 계산하지만, 새글은 사전 밖 정상어의 오탐을 막으려고 먼 키를 흔적이 있어도 격자 비용 1.0으로 둔다
 * (2026-10-11 측정: 상한을 먼 키에도 쓰면 `라면집`->라면을, `피자집`->피자를이 후보로 올랐다).
 */
object TouchSubstitutionCost {

    const val SIGMA = 0.55f
    const val SUBSTITUTION_CAP = 0.47f

    /** 이 격자 비용 이하인 키(거리 2.2 이내)만 격자 이웃으로 보고 터치 식을 쓴다. */
    const val NEIGHBOR_GRID_MAX = 0.7f

    /**
     * [touch]로 누른 키 대신 [intended]를 의도했을 때의 치환 비용. 계산할 수 없으면 null이다:
     * 두 키가 같은 자판 키(같은 키이거나 시프트 차이만 있음)이거나, 둘 중 하나가 배치에 없거나, [intended]가
     * 친 키의 격자 이웃이 아닐 때이며, 호출부가 격자 비용(같은 키는 0, 시프트 차이는 0.35, 먼 키는 1.0)을 쓴다.
     * [habit]이 있으면 [intended]가 속한 군집의 손버릇만큼 그 키의 중심을 옮겨 계산한다.
     */
    fun cost(touch: KeyTouch, intended: Char, habit: TouchHabit? = null): Float? {
        if (touch.key.lowercaseChar() == intended.lowercaseChar()) return null
        if (DubeolsikKeyMap.substitutionCost(touch.key, intended) > NEIGHBOR_GRID_MAX) return null
        val (offsetX, offsetY) = touch.offsetFrom(intended) ?: return null
        val bias = habit?.bias(intended)
        val zx = (offsetX - (bias?.dx ?: 0f)) / SIGMA
        val zy = (offsetY - (bias?.dy ?: 0f)) / SIGMA
        return min(SUBSTITUTION_CAP, (zx * zx + zy * zy) / 2f)
    }
}

/** 키 중심에서 사용자의 터치가 평균적으로 치우치는 정도. 키의 폭·높이로 나눈 값이다. */
class KeyBias(val dx: Float, val dy: Float)

/** 사용자의 손버릇. 글자 키(소문자)가 속한 군집에서 터치가 키 중심에서 치우치는 평균이며, 배운 값이 없으면 null이다. */
fun interface TouchHabit {

    fun bias(key: Char): KeyBias?
}

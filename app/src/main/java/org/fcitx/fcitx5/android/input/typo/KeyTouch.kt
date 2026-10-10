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
class KeyTouch(val key: Char, val dx: Float, val dy: Float, val layout: KeyLayoutSnapshot)

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
 * 터치 지점을 키 확률로 본 치환 비용.
 *
 * 터치 지점이 키 중심 주변의 2차원 가우시안에서 나왔다고 보고, 친 키 T 대신 다른 키 Y를 의도했을
 * 상대 확률의 음의 로그 `-ln(p(Y)/p(T))`를 비용으로 바꾼다. 표준편차 [SIGMA_X]·[SIGMA_Y]는
 * 키 폭·높이 단위다. 0.45는 키 가장자리(±0.5)가 약 1.1표준편차에 오도록 잡은 출발값이다.
 * [COST_PER_NAT]는 이 값을 기존 격자 비용 척도(인접 0.4,
 * 한 칸 건너 0.7, 먼 키 1.0)에 맞추는 계수다. 키 정중앙을 누른 뒤 옆 키를 의도했다면
 * 약 0.37, 경계에 닿았다면 최저값 [MIN_COST]다. 두 계수는 측정 결과에 맞춰 조정하는 값이다.
 */
object TouchSubstitutionCost {

    const val SIGMA_X = 0.45f
    const val SIGMA_Y = 0.45f
    const val COST_PER_NAT = 0.15f
    const val MIN_COST = 0.1f
    const val MAX_COST = 1.0f

    /**
     * [touch]로 누른 키 대신 [intended]를 의도했을 때의 치환 비용. 계산할 수 없으면 null이다:
     * 두 키가 같은 자판 키(시프트 차이만 있음)이거나 둘 중 하나가 배치에 없을 때.
     */
    fun cost(touch: KeyTouch, intended: Char): Float? {
        if (touch.key.lowercaseChar() == intended.lowercaseChar()) return null
        val typedCell = touch.layout.cell(touch.key) ?: return null
        val intendedCell = touch.layout.cell(intended) ?: return null
        val touchX = typedCell.centerX + touch.dx * typedCell.width
        val touchY = typedCell.centerY + touch.dy * typedCell.height
        val intendedDx = (touchX - intendedCell.centerX) / intendedCell.width
        val intendedDy = (touchY - intendedCell.centerY) / intendedCell.height
        val negLogRatio = (squaredZ(intendedDx, intendedDy) - squaredZ(touch.dx, touch.dy)) / 2f
        return min(MAX_COST, max(MIN_COST, COST_PER_NAT * negLogRatio))
    }

    private fun squaredZ(dx: Float, dy: Float): Float {
        val zx = dx / SIGMA_X
        val zy = dy / SIGMA_Y
        return zx * zx + zy * zy
    }
}

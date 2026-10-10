/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

/** 두벌식 QWERTY의 가상 격자를 키 한 칸 크기의 실제 배치로 삼는 테스트용 터치 흔적 도구. */
internal object TouchTestFixtures {

    val qwerty: KeyLayoutSnapshot = KeyLayoutSnapshot(
        buildMap {
            for (key in "qwertyuiopasdfghjklzxcvbnm") {
                val (x, y) = checkNotNull(DubeolsikKeyMap.keyCoordinate(key))
                put(key, KeyCell(centerX = x, centerY = y, width = 1f, height = 1f))
            }
        }
    )

    fun touch(key: Char, dx: Float = 0f, dy: Float = 0f) = KeyTouch(key, dx, dy, qwerty)

    fun trace(vararg touches: KeyTouch): TouchTrace {
        val recorder = TouchTraceRecorder { true }
        touches.forEach(recorder::onLetterTouch)
        return checkNotNull(recorder.snapshot())
    }

    /** 모든 키를 정중앙으로 누른 흔적. */
    fun centeredTrace(keys: String): TouchTrace = trace(*keys.map { touch(it) }.toTypedArray())
}

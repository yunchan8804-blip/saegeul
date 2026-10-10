/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.typo

/** 자판이 글자 키 터치를 알리는 곳. */
interface TouchTraceSink {

    fun onLetterTouch(touch: KeyTouch)

    fun onBackspace()
}

/**
 * 최근 [capacity]타의 터치 흔적을 서비스 메모리의 고리 버퍼에만 둔다. 디스크·금고·로그에는
 * 쓰지 않는다. [isRecordingAllowed]가 거짓인 동안(Direct Boot, 비밀번호 같은 민감 칸)에는
 * 기록하지 않고 이미 있던 흔적도 비운다. 편집기가 바뀌거나 입력이 끝나면 [clear]로 비운다.
 */
class TouchTraceRecorder(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val isRecordingAllowed: () -> Boolean
) : TouchTraceSink {

    private val ring = arrayOfNulls<KeyTouch>(capacity)
    private var start = 0
    private var size = 0

    init {
        require(capacity > 0) { "capacity=$capacity" }
    }

    @Synchronized
    override fun onLetterTouch(touch: KeyTouch) {
        if (!isRecordingAllowed()) {
            clearLocked()
            return
        }
        if (size == capacity) {
            ring[start] = touch
            start = (start + 1) % capacity
        } else {
            ring[(start + size) % capacity] = touch
            size++
        }
    }

    /** 백스페이스는 가장 최근 터치 하나를 지운 것으로 본다. 자모 하나가 지워지는 조합 중의 입력과 맞는다. */
    @Synchronized
    override fun onBackspace() {
        if (!isRecordingAllowed()) {
            clearLocked()
            return
        }
        if (size == 0) return
        size--
        ring[(start + size) % capacity] = null
    }

    @Synchronized
    fun clear() {
        clearLocked()
    }

    /** 지금까지의 흔적 사본. 기록이 막혀 있거나 비어 있으면 null이다. */
    @Synchronized
    fun snapshot(): TouchTrace? {
        if (size == 0 || !isRecordingAllowed()) return null
        return TouchTrace(List(size) { checkNotNull(ring[(start + it) % capacity]) })
    }

    private fun clearLocked() {
        ring.fill(null)
        start = 0
        size = 0
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

/**
 * 자동 추천이 공유 Gemma 엔진을 새로 얻을 때 백엔드를 고르는 규칙과, 키보드가 숨겨졌을 때 CPU 사용을 놓고
 * GPU로 갈아탈지 정하는 규칙. 저장된 GPU 설정은 바꾸지 않는다: 여기서 고른 CPU는 그 획득 한 번의 일시적
 * 선택이며, GPU 초기화 실패 뒤 CPU로 영구 전환하는 [OnDeviceBackendFallbackPolicy]와 섞이지 않는다.
 *
 * 차가운 엔진을 키보드가 떠 있는 동안 GPU로 초기화하면 가중치 변환이 화면 그리기와 GPU를 다퉈 키보드가 멈춘다.
 * 그렇다고 숨겨질 때까지 기다리면 그 입력 세션에는 추천이 없다. 그래서 키보드가 보일 때는 CPU로 예열하고,
 * 숨겨진 뒤 GPU로 바꾼다.
 */
object AutomaticSuggestionBackendPolicy {

    /** 기존 CPU 경로(CPU 호환 모드)와 같은 스레드 수. */
    const val CPU_THREAD_COUNT = 2

    enum class Choice { Gpu, Cpu }

    enum class Reason(val logText: String) {
        GPU_DISABLED("GPU setting off -> CPU"),
        ENGINE_WARM("shared engine warm -> keep as is"),
        COLD_KEYBOARD_VISIBLE("cold engine, keyboard visible -> CPU warm-up"),
        COLD_KEYBOARD_HIDDEN("cold engine, keyboard hidden -> GPU")
    }

    data class Decision(val choice: Choice, val reason: Reason)

    /**
     * @param gpuEnabled 저장된 GPU 사용 설정.
     * @param engineWarm 공유 엔진이 이미 따뜻한지(어떤 백엔드든). 따뜻하면 새로 초기화하지 않으므로 GPU를
     * 원한다고 말하기만 하고, 실제 백엔드는 [OnDeviceSharedEngine]의 교체 규칙이 정한다.
     * @param keyboardVisible 입력 뷰가 화면에 있는지.
     */
    fun choose(gpuEnabled: Boolean, engineWarm: Boolean, keyboardVisible: Boolean): Decision = when {
        !gpuEnabled -> Decision(Choice.Cpu, Reason.GPU_DISABLED)
        engineWarm -> Decision(Choice.Gpu, Reason.ENGINE_WARM)
        keyboardVisible -> Decision(Choice.Cpu, Reason.COLD_KEYBOARD_VISIBLE)
        else -> Decision(Choice.Gpu, Reason.COLD_KEYBOARD_HIDDEN)
    }

    /**
     * 키보드가 숨겨졌을 때 자동 추천이 쥐고 있는 CPU 엔진 사용을 놓아야 하는지. 놓으면 다음 획득에서
     * [OnDeviceSharedEngine]이 (아무도 쓰지 않고 키보드가 숨겨진 동안) GPU 엔진으로 교체한다.
     */
    fun shouldReleaseCpuForGpu(
        gpuEnabled: Boolean,
        heldFlavor: OnDeviceSharedEngine.Flavor?,
        keyboardVisible: Boolean
    ): Boolean = gpuEnabled && !keyboardVisible && heldFlavor is OnDeviceSharedEngine.Flavor.Cpu
}

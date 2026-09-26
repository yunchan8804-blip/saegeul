/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

import timber.log.Timber

object OnDeviceGenerationControl {
    enum class Purpose {
        PUBLIC_MATERIAL,
        PERSONAL_GRAPH,
        EXPLICIT_CONTEXT,
        AUTO_CONTEXT
    }

    sealed interface Lease

    private class OwnedLease : Lease

    private val lock = Any()
    private var keyboardActive = false
    private var inputViewVisible = false
    /** Epoch millis the input view was last hidden; 0 (so "hidden long enough" is trivially true) until it ever has been. */
    private var inputViewHiddenAtMs = 0L
    private var activePurpose: Purpose? = null
    private var activeLease: Lease? = null
    private var cancelCallback: (() -> Unit)? = null
    private var autoContextBusyProbe: (() -> Boolean)? = null
    private var autoContextPreemptionHandler: (() -> Unit)? = null
    private var explicitContextFinishedCallback: (() -> Unit)? = null

    /** Test-only injection point; production code never reassigns this. */
    internal var clockMs: () -> Long = System::currentTimeMillis

    /** How long the input view must have been hidden before [Purpose.PERSONAL_GRAPH] may run through the keyboard's hide grace ([keyboardActive] still true). */
    const val PERSONAL_GRAPH_KEYBOARD_GRACE_MS = 30_000L

    /**
     * 입력기 서비스가 시작된 뒤 배경 생성([Purpose.PUBLIC_MATERIAL], [Purpose.PERSONAL_GRAPH])을 막는 시간.
     * 서비스는 대개 키보드를 띄우려고 시작되는데, 그 직후 배경 작업이 GPU 엔진 초기화를 시작하면(중간에 멈출 수
     * 없다) 곧 뜨는 키보드가 GPU를 다투며 1초 넘게 멈춘다.
     */
    const val SERVICE_START_BACKGROUND_QUIET_MS = 30_000L

    /** [SERVICE_START_BACKGROUND_QUIET_MS]가 끝나는 시각. 0이면 제한이 없다. 테스트만 직접 되돌린다. */
    internal var backgroundQuietUntilMs = 0L

    /** [FcitxInputMethodService] 생성 시 호출한다. */
    fun onServiceStarted() {
        synchronized(lock) {
            backgroundQuietUntilMs = clockMs() + SERVICE_START_BACKGROUND_QUIET_MS
        }
    }

    val isKeyboardActive: Boolean
        get() = synchronized(lock) { keyboardActive }

    /**
     * 입력 뷰(키보드)가 지금 화면에 있는지. [keyboardActive]와 달리 숨김 유예를 갖지 않으므로,
     * 숨김 콜백에서 즉시 false가 된다. 배경 재료 생성의 중지 경계가 이 신호를 읽는다.
     */
    val isInputViewVisible: Boolean
        get() = synchronized(lock) { inputViewVisible }

    val isGenerating: Boolean
        get() = synchronized(lock) { activeLease != null }

    /**
     * [FcitxInputMethodService]가 등록한다. [isBusy]는 AUTO_CONTEXT 보유자가 지금 실제로 요청을
     * 생성 중인지 알려주고(웜업만 하는 중이면 false), [preempt]는 그 보유자를 하드 종료시키는
     * 서비스 수준 경로를 가리킨다. [onExplicitContextFinished]는 EXPLICIT_CONTEXT lease가
     * 반납된 직후 호출되어, 선점으로 멈췄던 자동 추천을 같은 입력 세션에서도 다시 깨울 기회를
     * 서비스에 준다. 셋 다 null을 넘기면 선점·재개 기능이 꺼진다(서비스가 없거나 파괴된 경우).
     */
    fun configureAutoContextPreemption(
        isBusy: (() -> Boolean)?,
        preempt: (() -> Unit)?,
        onExplicitContextFinished: (() -> Unit)?
    ) {
        synchronized(lock) {
            autoContextBusyProbe = isBusy
            autoContextPreemptionHandler = preempt
            explicitContextFinishedCallback = onExplicitContextFinished
        }
    }

    /**
     * @param preemptBackground Only meaningful for [Purpose.PERSONAL_GRAPH]: true for the user's own
     * manual button press, false for the periodic automatic run. When true and [Purpose.PUBLIC_MATERIAL]
     * currently holds the lease, that material generator is cancelled and the lease handed to this
     * PERSONAL_GRAPH request immediately - unlike the warm-idle-AUTO_CONTEXT preemption below, this is
     * unconditional (no busy check): the user asked for this by name, and material's own worker
     * already tolerates being cancelled mid-run and resumes later on its own schedule (its code is
     * untouched here - this only invokes the same [cancel] callback it registered with [tryBegin]).
     * An automatic graph run passes false and keeps waiting for material to finish on its own, exactly
     * as before.
     */
    fun tryBegin(purpose: Purpose, cancel: () -> Unit): Lease? =
        tryBegin(purpose, preemptBackground = false, cancel = cancel)

    fun tryBegin(
        purpose: Purpose = Purpose.PUBLIC_MATERIAL,
        preemptBackground: Boolean = false,
        cancel: () -> Unit
    ): Lease? {
        var preempt: (() -> Unit)? = null
        val lease = synchronized(lock) {
            val allowed = when (purpose) {
                // 배경 재료 생성은 키보드가 완전히 우선이다: keyboardActive(숨김 유예 포함, 최대
                // 10분)나 inputViewVisible 둘 중 하나라도 참이면 절대 lease를 받거나 AUTO_CONTEXT를
                // 선점하지 않는다. inputViewVisible만 보면 숨김과 동시에 곧바로 false가 되는데
                // keyboardActive는 그대로 true로 남는 짧은 창이 실기기에서 관찰돼(그 창에서
                // PUBLIC_MATERIAL/PERSONAL_GRAPH가 AUTO_CONTEXT를 선점해 사용자가 보던 자동
                // 추천이 끊겼다), keyboardActive도 함께 봐야 한다.
                Purpose.PUBLIC_MATERIAL -> !inputViewVisible && !keyboardActive && clockMs() >= backgroundQuietUntilMs
                // 사용자가 버튼으로 직접 요청한 그래프 보강은 재료와 달리 keyboardActive의 전체
                // 숨김 유예(최대 10분)를 그대로 따르지 않는다: 입력 뷰가 실제로 숨겨진 지
                // PERSONAL_GRAPH_KEYBOARD_GRACE_MS(30초) 이상 지났으면 keyboardActive가 아직
                // true(유예 중)여도 허용하고, 유휴 웜 AUTO_CONTEXT를 선점할 수 있다(실제 생성
                // 중인 AUTO_CONTEXT는 아래 autoContextBusy 검사로 여전히 보호된다). 30초 미만이면
                // 여전히 거부해 방금 닫힌 키보드가 다시 열리는 것과 충돌하지 않는다.
                Purpose.PERSONAL_GRAPH -> !inputViewVisible && clockMs() >= backgroundQuietUntilMs &&
                    (!keyboardActive || clockMs() - inputViewHiddenAtMs >= PERSONAL_GRAPH_KEYBOARD_GRACE_MS)
                Purpose.EXPLICIT_CONTEXT -> true
                Purpose.AUTO_CONTEXT -> keyboardActive
            }
            if (!allowed) {
                Timber.i(
                    "Generation lease denied purpose=%s activePurpose=%s keyboardActive=%s reason=%s",
                    purpose, activePurpose, keyboardActive, "NOT_ALLOWED"
                )
                return null
            }
            if (activeLease != null) {
                // The user's own manual graph request outranks an in-progress background material
                // generation outright - unconditional, no busy check (see the tryBegin doc).
                val preemptsBackgroundMaterial =
                    preemptBackground && purpose == Purpose.PERSONAL_GRAPH && activePurpose == Purpose.PUBLIC_MATERIAL
                // A warm-but-idle AUTO_CONTEXT holder keeps its lease across requests (it is not
                // released between suggestions), so a plain conflict check would starve the user's
                // explicit, tap-triggered completion behind an idle background engine. Preempt only
                // when the holder is not actually mid-request; an in-flight automatic generation
                // still wins (BUSY_GENERATING) so it is not torn down mid-native-call. The
                // background material purpose needs the same takeover: the warm engine holds its
                // lease for the idle-close window after the keyboard hides, so without it the
                // material boundary would stay closed behind an idle engine.
                val preemptsWarmAutoContext =
                    (purpose == Purpose.EXPLICIT_CONTEXT || purpose == Purpose.PUBLIC_MATERIAL ||
                        purpose == Purpose.PERSONAL_GRAPH) &&
                        activePurpose == Purpose.AUTO_CONTEXT
                val autoContextBusy = preemptsWarmAutoContext && autoContextBusyProbe?.invoke() == true
                if (!preemptsBackgroundMaterial && (!preemptsWarmAutoContext || autoContextBusy)) {
                    Timber.i(
                        "Generation lease denied purpose=%s activePurpose=%s keyboardActive=%s reason=%s",
                        purpose, activePurpose, keyboardActive,
                        if (autoContextBusy) "BUSY_GENERATING" else "BUSY"
                    )
                    return null
                }
                // Fall back to the holder's own cancel if the service has not (yet) registered a
                // preemption handler, so the lease is never silently stolen without any cleanup.
                // Material has no separate preemption-handler concept (unlike AUTO_CONTEXT) - its own
                // cancelCallback, the same one it registered with tryBegin, is always what stops it.
                preempt = if (preemptsBackgroundMaterial) cancelCallback else autoContextPreemptionHandler ?: cancelCallback
                Timber.i(
                    "Generation lease preempt purpose=%s activePurpose=%s keyboardActive=%s reason=%s",
                    purpose, activePurpose, keyboardActive,
                    if (preemptsBackgroundMaterial) "MANUAL_PREEMPTS_MATERIAL" else "WARM_AUTO_CONTEXT"
                )
            }
            val newLease = OwnedLease()
            activePurpose = purpose
            activeLease = newLease
            cancelCallback = cancel
            Timber.i("Generation lease begin purpose=%s keyboardActive=%s", purpose, keyboardActive)
            newLease
        }
        // The lease is already assigned to the caller; a failing preemption handler must not
        // propagate and leave it orphaned (nobody would ever call end() for it).
        preempt?.let { handler ->
            try {
                handler()
            } catch (error: Throwable) {
                Timber.w(error, "Generation lease preemption handler failed purpose=%s", purpose)
            }
        }
        return lease
    }

    fun end(lease: Lease): Boolean {
        var notifyExplicitContextFinished: (() -> Unit)? = null
        val ended = synchronized(lock) {
            if (activeLease !== lease) {
                false
            } else {
                Timber.i("Generation lease end purpose=%s", activePurpose)
                if (activePurpose == Purpose.EXPLICIT_CONTEXT) {
                    notifyExplicitContextFinished = explicitContextFinishedCallback
                }
                activePurpose = null
                activeLease = null
                cancelCallback = null
                true
            }
        }
        notifyExplicitContextFinished?.let { callback ->
            try {
                callback()
            } catch (error: Throwable) {
                Timber.w(error, "Explicit context finished callback failed")
            }
        }
        return ended
    }

    /**
     * 자동 추천 게이트 전용이다. 숨김 유예가 끝나면 웜 보유자의 lease를 반납시킨다. 배경 재료
     * 생성의 경계는 [onInputViewVisibilityChanged]가 담당한다.
     */
    fun onKeyboardVisibilityChanged(active: Boolean) {
        val cancel = synchronized(lock) {
            val becameInactive = !active && keyboardActive
            keyboardActive = active
            if (becameInactive && activePurpose == Purpose.AUTO_CONTEXT) {
                cancelCallback
            } else {
                null
            }
        }
        cancel?.invoke()
    }

    /**
     * [FcitxInputMethodService]가 입력 뷰 가시성 경계에서 호출한다. 재료 생성의 중지 경계이며
     * 숨김 즉시 false가 된다. 입력 뷰가 화면에 나타나는 상승 에지에서 진행 중인 재료 생성을
     * 취소한다. 숨김 유예 중에 키보드를 다시 열면 [onKeyboardVisibilityChanged]의 상승 에지가
     * 발생하지 않으므로, 이 취소만이 그 경우를 덮는다.
     */
    fun onInputViewVisibilityChanged(visible: Boolean) {
        val cancel = synchronized(lock) {
            val becameVisible = visible && !inputViewVisible
            val becameHidden = !visible && inputViewVisible
            inputViewVisible = visible
            if (becameHidden) inputViewHiddenAtMs = clockMs()
            if (becameVisible &&
                (activePurpose == Purpose.PUBLIC_MATERIAL || activePurpose == Purpose.PERSONAL_GRAPH)
            ) {
                cancelCallback
            } else {
                null
            }
        }
        cancel?.invoke()
    }
}

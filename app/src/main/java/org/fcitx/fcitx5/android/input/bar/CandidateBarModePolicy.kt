/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar

/**
 * K5 (사용자 확정 2026-09-26): 가로 모드이면서 분할 키보드가 아닐 때, 후보 영역을 48dp 한 줄로
 * 고정한다. 세로 모드 또는 분할 키보드에서는 기존 2행 고정 계약(97dp)이 그대로 적용된다.
 *
 * Pure decision logic (no Android dependency) so the orientation/split combinations and the
 * height-priority ordering are unit-testable without Robolectric. See
 * [org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent.updateBarHeight] and
 * [org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent.renderCandidates].
 */
internal object CandidateBarModePolicy {

    /** Whether the landscape-single-row candidate bar (K5) should be active. */
    fun isHorizontalSingleRow(landscape: Boolean, thumbSplitActive: Boolean): Boolean =
        landscape && !thumbSplitActive

    /**
     * What the landscape single row should show, given whether a sentence recommendation and/or
     * word candidates exist. [SENTENCE_AND_WORDS] also covers "sentence only" (the word recycler
     * is simply empty next to the chip); [PLACEHOLDER] is the "추천 단어 없음" chip shown only when
     * both are empty.
     */
    enum class SingleRowContent { SENTENCE_AND_WORDS, WORDS_ONLY, PLACEHOLDER }

    fun singleRowContent(hasSentence: Boolean, hasWords: Boolean): SingleRowContent = when {
        hasSentence -> SingleRowContent.SENTENCE_AND_WORDS
        hasWords -> SingleRowContent.WORDS_ONLY
        else -> SingleRowContent.PLACEHOLDER
    }

    /**
     * Candidate row height, in dp. [singleRowLandscape] takes priority over every other flag: the
     * landscape single row never resizes with candidate/hint/status content, mirroring the
     * portrait always-two-row contract's "height never changes while typing" guarantee.
     */
    fun candidateRowHeightDp(
        singleRowLandscape: Boolean,
        candidateRowFixedHeight: Boolean,
        twoRowWithAutomaticCandidates: Boolean,
        hintOrStatusRowVisible: Boolean,
        isCandidateTwoRow: Boolean,
        unitHeightDp: Int,
        twoRowHeightDp: Int,
        hintStatusHeightDp: Int
    ): Int = when {
        singleRowLandscape -> unitHeightDp
        candidateRowFixedHeight -> unitHeightDp * 2 + 1
        twoRowWithAutomaticCandidates -> unitHeightDp * 2 + 1
        hintOrStatusRowVisible -> hintStatusHeightDp
        isCandidateTwoRow -> twoRowHeightDp
        else -> unitHeightDp
    }

    /**
     * K5 후속 (사용자 확정 2026-09-26): landscape 단일 줄에서, idle 툴바가 압축 상태(`>`/`▾` 두
     * 버튼만 보이는 [org.fcitx.fcitx5.android.input.bar.ui.IdleUi.State.Empty])이고 후보 줄이 실제로
     * 보이는 동안에는 그 압축 툴바 줄을 후보 줄에 합쳐 총 48dp 한 줄을 유지한다. 툴바를 펼쳤거나
     * 클립보드/숫자 행처럼 다른 내용을 보이는 중에는 합치지 않고 기존처럼 두 줄을 쌓는다.
     */
    fun isSingleRowMerged(
        singleRowLandscape: Boolean,
        candidateRowVisible: Boolean,
        idleToolbarCompact: Boolean
    ): Boolean = singleRowLandscape && candidateRowVisible && idleToolbarCompact

    /** idle 툴바 줄의 실제 높이(dp). 병합 중에는 후보 줄 하나로 흡수되어 0이 된다. */
    fun toolRowHeightDp(singleRowMerged: Boolean, toolbarHeightDp: Int): Int =
        if (singleRowMerged) 0 else toolbarHeightDp
}

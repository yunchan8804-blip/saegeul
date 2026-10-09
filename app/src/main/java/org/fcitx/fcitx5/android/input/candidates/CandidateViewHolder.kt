/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates

import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.core.CandidateWord

class CandidateViewHolder(val ui: CandidateItemUi) : RecyclerView.ViewHolder(ui.root) {
    var idx = -1
        private set

    var candidate: CandidateWord = CandidateWord.Empty
        private set

    private var isFeatured = false

    fun update(newIndex: Int, newCandidate: CandidateWord) {
        val newFeatured = newIndex == 0
        val shouldUpdateUi = shouldUpdateUi(candidate, isFeatured, newCandidate, newFeatured)
        idx = newIndex
        candidate = newCandidate
        isFeatured = newFeatured
        if (shouldUpdateUi) {
            ui.updateCandidate(newCandidate, isFeatured = newFeatured)
        }
    }

    fun clear() {
        update(-1, CandidateWord.Empty)
    }

    companion object {
        /** The same candidate still needs redrawing when it moved into or out of the first (bold) slot. */
        internal fun shouldUpdateUi(
            oldCandidate: CandidateWord,
            oldFeatured: Boolean,
            newCandidate: CandidateWord,
            newFeatured: Boolean
        ): Boolean = oldCandidate != newCandidate || oldFeatured != newFeatured
    }
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

enum class BufferedInputTransport(override val stringRes: Int) : ManagedPreferenceEnum {
    SystemPaste(R.string.buffered_input_transport_system_paste),
    CtrlV(R.string.buffered_input_transport_ctrl_v),
    DirectCommit(R.string.buffered_input_transport_direct_commit)
}

/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai

import java.io.IOException

class TypingDnaPersistenceException(cause: Throwable) :
    IOException("Unable to save typing DNA.", cause)

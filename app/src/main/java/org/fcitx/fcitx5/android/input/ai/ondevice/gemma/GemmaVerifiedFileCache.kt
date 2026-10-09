/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import java.io.File

/**
 * 파일 하나의 마지막 검증 성공 사실을 프로세스 메모리에만 기억한다. (절대 경로, 길이, lastModified)가
 * 검증에 성공했을 때와 같으면 [verifyOnce]는 비싼 검증을 다시 하지 않는다. 디스크에는 아무것도 쓰지 않는다.
 * 파일을 바꾸는 쪽은 바꾼 뒤 [invalidate]를 불러야 한다.
 */
internal class GemmaVerifiedFileCache {

    private data class Stamp(val path: String, val length: Long, val lastModified: Long)

    @Volatile
    private var verified: Stamp? = null

    suspend fun verifyOnce(file: File, verify: suspend () -> Unit) {
        val before = stampOf(file)
        if (before == verified) return
        verify()
        // 검증하는 동안 파일이 바뀌었으면 어느 쪽 내용을 검증했는지 알 수 없으므로 기억하지 않는다.
        if (stampOf(file) == before) verified = before
    }

    fun invalidate() {
        verified = null
    }

    private fun stampOf(file: File) = Stamp(file.absolutePath, file.length(), file.lastModified())
}

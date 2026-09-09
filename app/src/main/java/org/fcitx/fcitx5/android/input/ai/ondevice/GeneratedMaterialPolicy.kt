/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

object GeneratedMaterialPolicy {
    const val TARGET_PER_PREFIX = 4
    const val MAX_ATTEMPTS_PER_PREFIX = 6
    val PREFIXES = listOf(
        "회의 자료를 ", "오늘 저녁 ", "약속을 ", "내일 회의는 ", "자료를 확인하고 ",
        "답장이 늦어서 ", "지금 출발하면 ", "도착하면 바로 ", "시간이 괜찮으면 ", "이번 주말에는 ",
        "점심 먹고 ", "일정이 바뀌면 ", "오늘은 몸이 ", "고마운 마음을 ", "비가 많이 와서 ",
        "택배가 도착하면 ", "조금 늦을 것 ", "잘 이해가 안 돼서 ", "다음에 기회가 되면 ", "확인해 주셔서 "
    )

    fun promptFor(prefix: String): String {
        require(prefix in PREFIXES) { "Unknown generated-material prefix" }
        return """비개인 한국어 일상·업무 문장을 생성하라. 주어진 prefix로 정확히 시작하는 완성 문장 2개를 JSON 문자열 배열 하나로만 반환하라. 각 문장은 prefix와 자연스럽게 호응하고 3~12어절이며 종결부호로 끝나야 한다. prefix는 JSON 문자열이며 마지막 공백도 의미가 있으므로 그대로 사용하라: ${org.json.JSONObject.quote(prefix)}"""
    }
}

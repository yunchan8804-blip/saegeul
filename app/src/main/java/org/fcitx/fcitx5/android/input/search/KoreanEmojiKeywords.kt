/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.search

object KoreanEmojiKeywords {
    private class Item(val emoji: String, vararg keywords: String) {
        val keywords = keywords.toList()
    }

    private val items = listOf(
        Item("🙏", "감사", "고마워", "부탁", "기도"),
        Item("🙇", "죄송", "미안", "사과", "인사"),
        Item("🎉", "축하", "파티", "기념"),
        Item("👏", "박수", "축하", "잘했어", "최고"),
        Item("😂", "웃음", "웃겨", "ㅋㅋ", "눈물"),
        Item("🤣", "폭소", "웃음", "ㅋㅋㅋ"),
        Item("😊", "미소", "웃음", "기쁨", "행복"),
        Item("❤️", "사랑", "하트", "좋아"),
        Item("🥰", "사랑", "행복", "하트"),
        Item("👍", "좋아요", "최고", "동의", "확인"),
        Item("👎", "싫어요", "반대", "별로"),
        Item("✅", "확인", "완료", "성공", "체크"),
        Item("👋", "안녕", "인사", "잘가", "반가워"),
        Item("😮", "놀람", "헉", "대박"),
        Item("😡", "화남", "분노", "짜증"),
        Item("😢", "슬픔", "울음", "눈물"),
        Item("😭", "오열", "슬픔", "울음", "눈물"),
        Item("🤔", "생각", "고민", "궁금"),
        Item("🔥", "불", "열정", "화이팅", "인기"),
        Item("💪", "힘", "화이팅", "응원", "운동"),
        Item("🎂", "생일", "케이크", "축하"),
        Item("☕", "커피", "카페", "휴식"),
        Item("😋", "맛있다", "식사", "밥"),
        Item("📞", "전화", "연락", "통화"),
        Item("⏰", "시간", "알람", "약속"),
        Item("📍", "위치", "장소", "주소"),
        Item("🎁", "선물", "축하"),
        Item("💯", "백점", "최고", "완벽"),
        Item("🚀", "출발", "성장", "빠르게"),
        Item("✨", "반짝", "멋져", "새로운")
    )

    val entries: List<KoreanSearchEntry> = items.mapIndexed { index, item ->
        KoreanSearchEntry(
            id = "emoji:$index",
            source = KoreanSearchSource.Emoji,
            primaryText = item.emoji,
            secondaryText = item.keywords.joinToString(" · "),
            searchTerms = item.keywords
        )
    }
}

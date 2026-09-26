enum Dubeolsik {
    static let unshifted: [Character: Character] = [
        "q": "ㅂ", "w": "ㅈ", "e": "ㄷ", "r": "ㄱ", "t": "ㅅ",
        "y": "ㅛ", "u": "ㅕ", "i": "ㅑ", "o": "ㅐ", "p": "ㅔ",
        "a": "ㅁ", "s": "ㄴ", "d": "ㅇ", "f": "ㄹ", "g": "ㅎ",
        "h": "ㅗ", "j": "ㅓ", "k": "ㅏ", "l": "ㅣ",
        "z": "ㅋ", "x": "ㅌ", "c": "ㅊ", "v": "ㅍ",
        "b": "ㅠ", "n": "ㅜ", "m": "ㅡ",
    ]

    static let shifted: [Character: Character] = [
        "q": "ㅃ", "w": "ㅉ", "e": "ㄸ", "r": "ㄲ", "t": "ㅆ",
        "o": "ㅒ", "p": "ㅖ",
    ]

    static func jamo(key: Character, shift: Bool) -> Character? {
        guard let lowered = key.lowercased().first else { return nil }
        if shift, let jamo = shifted[lowered] {
            return jamo
        }
        return unshifted[lowered]
    }
}

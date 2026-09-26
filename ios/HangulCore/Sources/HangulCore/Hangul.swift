enum Hangul {
    static let syllableBase = 0xAC00
    static let jungseongCount = 21
    static let jongseongCount = 28

    static let choseong: [Character] = Array("ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ")
    static let jungseong: [Character] = Array("ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ")
    static let jongseong: [Character] = Array("ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ")

    static let choseongIndex: [Character: Int] = Dictionary(
        uniqueKeysWithValues: choseong.enumerated().map { ($1, $0) }
    )
    static let jungseongIndex: [Character: Int] = Dictionary(
        uniqueKeysWithValues: jungseong.enumerated().map { ($1, $0) }
    )
    static let jongseongIndex: [Character: Int] = Dictionary(
        uniqueKeysWithValues: jongseong.enumerated().map { ($1, $0 + 1) }
    )

    /// choseong index → jongseong index (nil if the consonant cannot be a jong).
    static let choToJong: [Int: Int] = [
        0: 1, 1: 2, 2: 4, 3: 7, 5: 8, 6: 16, 7: 17,
        9: 19, 10: 20, 11: 21, 12: 22, 14: 23, 15: 24, 16: 25, 17: 26, 18: 27,
    ]

    /// simple jongseong index → choseong index
    static let jongToCho: [Int: Int] = [
        1: 0, 2: 1, 4: 2, 7: 3, 8: 5, 16: 6, 17: 7,
        19: 9, 20: 10, 21: 11, 22: 12, 23: 14, 24: 15, 25: 16, 26: 17, 27: 18,
    ]

    /// leading jung + trailing jung → compound jung
    static let compoundJung: [Int: [Int: Int]] = [
        8: [0: 9, 1: 10, 20: 11],
        13: [4: 14, 5: 15, 20: 16],
        18: [20: 19],
    ]

    /// leading jong + trailing jong → compound jong
    static let compoundJong: [Int: [Int: Int]] = [
        1: [19: 3],
        4: [22: 5, 27: 6],
        8: [1: 9, 16: 10, 17: 11, 19: 12, 25: 13, 26: 14, 27: 15],
        17: [19: 18],
    ]

    /// compound jung → first component
    static let jungHead: [Int: Int] = [
        9: 8, 10: 8, 11: 8,
        14: 13, 15: 13, 16: 13,
        19: 18,
    ]

    /// compound jong → (remaining jong, next choseong)
    static let jongSplit: [Int: (remain: Int, cho: Int)] = [
        3: (1, 9),
        5: (4, 12),
        6: (4, 18),
        9: (8, 0),
        10: (8, 6),
        11: (8, 7),
        12: (8, 9),
        13: (8, 16),
        14: (8, 17),
        15: (8, 18),
        18: (17, 9),
    ]

    static func syllable(cho: Int, jung: Int, jong: Int = 0) -> Character {
        let scalarValue = syllableBase + ((cho * jungseongCount) + jung) * jongseongCount + jong
        return Character(UnicodeScalar(UInt32(scalarValue))!)
    }

    static func combineJung(current: Int, incoming: Int) -> Int? {
        compoundJung[current]?[incoming]
    }

    static func combineJong(current: Int, incoming: Int) -> Int? {
        compoundJong[current]?[incoming]
    }

    static func splitJong(_ jong: Int) -> (remain: Int, cho: Int)? {
        if let split = jongSplit[jong] {
            return split
        }
        if let cho = jongToCho[jong] {
            return (0, cho)
        }
        return nil
    }
}

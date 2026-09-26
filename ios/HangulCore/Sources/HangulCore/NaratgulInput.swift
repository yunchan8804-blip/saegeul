/// 나랏글 자판의 획추가·쌍자음·모음 순환 규칙을 두벌식 자모 스트림으로 바꾸는 순수 입력기.
/// `MobileHangulKeyboard.kt:335-377`(배치)와 `MobileHangulComposer.kt`의 `pressCycle`/
/// `transformLast`/`combineVowels(naratgulVowelPair:)`를 클린룸으로 재구현했다.
public enum NaratgulKey: Equatable {
    case g, n, r, m, s, ng // ㄱ ㄴ ㄹ ㅁ ㅅ ㅇ(직접 자모, 순환 없음)
    case a, o // ㅏㅓ 순환, ㅗㅜ 순환(나랏글 전용 예외 포함)
    case i, eu // ㅣ ㅡ(직접)
    case addStroke // 획추가: ㄱ→ㅋ, ㄴ→ㄷ→ㅌ, ㅁ→ㅂ→ㅍ, ㅅ→ㅈ→ㅊ, ㅇ→ㅎ, ㅏ→ㅑ, ㅓ→ㅕ, ㅗ→ㅛ, ㅜ→ㅠ
    case doubleConsonant // 쌍자음 왕복: ㄱ↔ㄲ, ㄷ↔ㄸ, ㅂ↔ㅃ, ㅅ↔ㅆ, ㅈ↔ㅉ
}

public final class NaratgulInput {
    private let multiTapWindowMs: Int
    private let now: () -> Int

    private var lastJamo: Character?
    /// 현재 완성돼 있는 모음(순환·결합 판단에 쓴다). 자음을 누르면 nil로 리셋된다.
    private var currentVowel: Character?
    private var lastCycleKey: NaratgulKey?
    private var cycleIndex = 0
    private var lastCycleAt = 0
    /// 이번 모음 순환 키를 "새로" 누른 시점의 직전 모음(다른 키로 만들어진 모음과 결합 판단용).
    private var cyclePreviousVowel: Character?

    private static let strokeAdditions: [Character: Character] = [
        "ㄱ": "ㅋ", "ㄴ": "ㄷ", "ㄷ": "ㅌ",
        "ㅁ": "ㅂ", "ㅂ": "ㅍ",
        "ㅅ": "ㅈ", "ㅈ": "ㅊ", "ㅇ": "ㅎ",
        "ㅏ": "ㅑ", "ㅓ": "ㅕ", "ㅗ": "ㅛ", "ㅜ": "ㅠ",
    ]

    private static let doubleConsonants: [Character: Character] = [
        "ㄱ": "ㄲ", "ㄲ": "ㄱ", "ㄷ": "ㄸ", "ㄸ": "ㄷ",
        "ㅂ": "ㅃ", "ㅃ": "ㅂ", "ㅅ": "ㅆ", "ㅆ": "ㅅ",
        "ㅈ": "ㅉ", "ㅉ": "ㅈ",
    ]

    /// ㅣ 키를 눌렀을 때 직전 모음과 결합되는 표. 나랏글의 ㅣ 키는 Android에서 천지인과 똑같은
    /// `pressChunjiinVowel`을 타므로(`Token.VowelI -> pressChunjiinVowel('ㅣ')`), 나랏글에는
    /// ㆍ 키가 없어 pendingDots 분기만 빠질 뿐 결합표(`chunjiinICombinations`)는 그대로 공유한다.
    private static let iCombinations: [Character: Character] = [
        "ㅏ": "ㅐ", "ㅑ": "ㅒ", "ㅓ": "ㅔ", "ㅕ": "ㅖ",
        "ㅗ": "ㅚ", "ㅜ": "ㅟ", "ㅡ": "ㅢ",
        "ㅠ": "ㅝ", "ㅘ": "ㅙ", "ㅝ": "ㅞ",
    ]

    /// Android `vowelCombinations`와 동일한 일반 겹모음 결합표(이전 모음 + 이번 모음 → 결과).
    private static func combineVowels(previous: Character?, next: Character) -> Character? {
        guard let previous else { return nil }
        switch (previous, next) {
        case ("ㅏ", "ㅣ"): return "ㅐ"
        case ("ㅑ", "ㅣ"): return "ㅒ"
        case ("ㅓ", "ㅣ"): return "ㅔ"
        case ("ㅕ", "ㅣ"): return "ㅖ"
        case ("ㅗ", "ㅏ"): return "ㅘ"
        case ("ㅗ", "ㅐ"): return "ㅙ"
        case ("ㅗ", "ㅣ"): return "ㅚ"
        case ("ㅜ", "ㅓ"): return "ㅝ"
        case ("ㅜ", "ㅔ"): return "ㅞ"
        case ("ㅜ", "ㅣ"): return "ㅟ"
        case ("ㅡ", "ㅣ"): return "ㅢ"
        case ("ㅘ", "ㅣ"): return "ㅙ"
        case ("ㅝ", "ㅣ"): return "ㅞ"
        default: return nil
        }
    }

    public init(multiTapWindowMs: Int = 1500, now: @escaping () -> Int) {
        self.multiTapWindowMs = multiTapWindowMs
        self.now = now
    }

    public func reset() {
        lastJamo = nil
        currentVowel = nil
        lastCycleKey = nil
        cycleIndex = 0
        lastCycleAt = 0
        cyclePreviousVowel = nil
    }

    public func press(_ key: NaratgulKey) -> ChunjiinEvent {
        switch key {
        case .g: return pressJamo("ㄱ")
        case .n: return pressJamo("ㄴ")
        case .r: return pressJamo("ㄹ")
        case .m: return pressJamo("ㅁ")
        case .s: return pressJamo("ㅅ")
        case .ng: return pressJamo("ㅇ")
        case .i: return pressICombining()
        case .eu: return pressJamo("ㅡ")
        case .a: return pressVowelCycle(.a, jamoList: ["ㅏ", "ㅓ"])
        case .o: return pressVowelCycle(.o, jamoList: ["ㅗ", "ㅜ"])
        case .addStroke: return transformLast(Self.strokeAdditions)
        case .doubleConsonant: return transformLast(Self.doubleConsonants)
        }
    }

    /// 멀티탭 창 안이면 공백 없이 순환만 닫는다. 창 밖이면 호출자가 공백을 입력한다.
    public func space() -> ChunjiinEvent {
        let nowMs = now()
        let closesMultiTap = lastCycleKey != nil && nowMs - lastCycleAt <= multiTapWindowMs
        reset()
        return ChunjiinEvent(endsMultiTap: closesMultiTap)
    }

    private func isVowel(_ c: Character) -> Bool {
        ("ㅏ"..."ㅣ").contains(c)
    }

    private func pressJamo(_ jamo: Character) -> ChunjiinEvent {
        lastCycleKey = nil
        lastJamo = jamo
        currentVowel = isVowel(jamo) ? jamo : nil
        return ChunjiinEvent(jamo: [jamo])
    }

    /// ㅏㅓ·ㅗㅜ 순환 키. 같은 키를 창 안에서 다시 누르면 2개 후보 사이를 오가고(예: ㅏ↔ㅓ),
    /// 다른 키(예: ㅗㅜ)로 만든 직전 모음과 겹모음으로 결합될 수 있다(ㅗ+ㅏ=ㅘ 등). "ㅜ" 뒤에
    /// 이 키를 새로 눌러 ㅏ가 선택돼도(2번째 후보 ㅓ가 아니라) ㅝ로 합쳐지는 나랏글 전용 예외가 있다.
    private func pressVowelCycle(_ key: NaratgulKey, jamoList: [Character]) -> ChunjiinEvent {
        let nowMs = now()
        let replacing = lastCycleKey == key && nowMs - lastCycleAt <= multiTapWindowMs
        if !replacing {
            cyclePreviousVowel = currentVowel
        }
        cycleIndex = replacing ? (cycleIndex + 1) % jamoList.count : 0
        let selected = jamoList[cycleIndex]
        lastCycleKey = key
        lastCycleAt = nowMs

        let combined: Character?
        if cyclePreviousVowel == "ㅜ" && selected == "ㅏ" {
            combined = "ㅝ"
        } else {
            combined = Self.combineVowels(previous: cyclePreviousVowel, next: selected)
        }
        let next = combined ?? selected
        currentVowel = next
        lastJamo = next

        if replacing || (cyclePreviousVowel != nil && next != selected) {
            return ChunjiinEvent(backspaces: 1, jamo: [next])
        }
        return ChunjiinEvent(jamo: [next])
    }

    /// `iCombinations`의 옛 모음(old) 중 그 자체가 이미 겹모음인 것들은(ㅘ/ㅝ) HangulComposer의
    /// jungHead가 한 단계만 되돌리므로(예: ㅘ→ㅗ) 완전히 걷어내려면 backspace가 2번 필요하다.
    /// `ChunjiinInput.doubledBackspaceICombinationSources`와 같은 표다.
    private static let doubledBackspaceICombinationSources: Set<Character> = ["ㅘ", "ㅝ"]

    /// ㅣ 키(직접, 순환 없음): 직전 모음이 결합표에 있으면 지우고 합쳐진 모음으로 바꾸고,
    /// 없으면 ㅣ를 그냥 낸다. `ChunjiinInput.pressVowel`의 ㅣ 분기와 같은 구조다(ㆍ 누적만 없다).
    private func pressICombining() -> ChunjiinEvent {
        lastCycleKey = nil
        let old = currentVowel
        let combined = old.flatMap { Self.iCombinations[$0] }
        let next = combined ?? "ㅣ"
        currentVowel = next
        lastJamo = next
        if combined != nil {
            let backspaces = old.map { Self.doubledBackspaceICombinationSources.contains($0) ? 2 : 1 } ?? 1
            return ChunjiinEvent(backspaces: backspaces, jamo: [next])
        }
        return ChunjiinEvent(jamo: [next])
    }

    /// 획추가/쌍자음: 방금 낸 자모(lastJamo)를 표에서 찾아 지우고 바꾼다. 표에 없으면 아무 일도
    /// 일어나지 않는다(Android `transformLast`가 emptyList()를 돌려주는 것과 동일).
    private func transformLast(_ mapping: [Character: Character]) -> ChunjiinEvent {
        guard let last = lastJamo, let next = mapping[last] else { return ChunjiinEvent() }
        lastCycleKey = nil
        lastJamo = next
        currentVowel = isVowel(next) ? next : nil
        return ChunjiinEvent(backspaces: 1, jamo: [next])
    }
}

/// 천지인 자판의 멀티탭·ㆍ 누적 규칙을 두벌식 자모 스트림으로 바꾸는 순수 입력기.
/// 조합(음절 구성)은 다루지 않는다 — 나온 자모를 `HangulComposer.process(jamo:)`에 그대로 넘기면
/// 겹모음 결합(예: ㅗ+ㅏ=ㅘ)은 그 조합기가 알아서 처리한다.
public enum ChunjiinKey: Equatable {
    case i, dot, eu
    case gk, nr, dt, bp, sh, jc, om
}

public struct ChunjiinEvent: Equatable {
    public var backspaces: Int
    public var jamo: [Character]
    public var endsMultiTap: Bool

    public init(backspaces: Int = 0, jamo: [Character] = [], endsMultiTap: Bool = false) {
        self.backspaces = backspaces
        self.jamo = jamo
        self.endsMultiTap = endsMultiTap
    }
}

public final class ChunjiinInput {
    /// 천지인 ㆍ(아래아) 표시 문자. HangulComposer가 이 문자를 받으면 조합에 참여시키지 않고
    /// preedit로만 잠깐 보여준다(U+318D, HANGUL LETTER ARAEA).
    public static let dotCharacter: Character = "\u{318D}"

    private let multiTapWindowMs: Int
    private let now: () -> Int

    /// 확정된 `ChunjiinKey`는 Equatable만 선언돼 있어(Hashable 아님) 딕셔너리 키로 못 쓴다.
    /// switch로 순환표를 돌려준다.
    private static func cycleJamo(for key: ChunjiinKey) -> [Character] {
        switch key {
        case .gk: return ["ㄱ", "ㅋ", "ㄲ"]
        case .nr: return ["ㄴ", "ㄹ"]
        case .dt: return ["ㄷ", "ㅌ", "ㄸ"]
        case .bp: return ["ㅂ", "ㅍ", "ㅃ"]
        case .sh: return ["ㅅ", "ㅎ", "ㅆ"]
        case .jc: return ["ㅈ", "ㅊ", "ㅉ"]
        case .om: return ["ㅇ", "ㅁ"]
        case .i, .dot, .eu: return []
        }
    }

    /// ㅣ 키를 눌렀을 때 직전 모음과 결합되는 표(천지인 고유 규칙, Android `chunjiinICombinations`
    /// 그대로). ㅗ+ㅏ=ㅘ처럼 서로 다른 두 모음 키로 만드는 겹모음은 HangulComposer의 겹모음
    /// 결합이 처리하도록 그냥 흘려보내지만, 여기 있는 ㅠ/ㅘ/ㅝ는 ㆍ키로 이미 만들어진 모음
    /// 위에 ㅣ가 또 눌렸을 때의 결합이라 이 표에서 직접 다뤄야 한다.
    private static let iCombinations: [Character: Character] = [
        "ㅏ": "ㅐ", "ㅑ": "ㅒ", "ㅓ": "ㅔ", "ㅕ": "ㅖ",
        "ㅗ": "ㅚ", "ㅜ": "ㅟ", "ㅡ": "ㅢ",
        "ㅠ": "ㅝ", "ㅘ": "ㅙ", "ㅝ": "ㅞ",
    ]

    private var lastCycleKey: ChunjiinKey?
    private var cycleIndex = 0
    private var lastCycleAt = 0

    /// 현재 완성돼 있는(또는 방금 대체된) 모음 한 글자. ㆍ 누적 중에는 nil이다.
    private var currentVowel: Character?
    /// ㆍ 단독 누적 횟수(0~2). 1이면 ㅣ→ㅓ, ㅡ→ㅗ / 2이면 ㅣ→ㅕ, ㅡ→ㅛ로 결합된다.
    private var pendingDots = 0

    public init(multiTapWindowMs: Int = 1500, now: @escaping () -> Int) {
        self.multiTapWindowMs = multiTapWindowMs
        self.now = now
    }

    public func reset() {
        lastCycleKey = nil
        cycleIndex = 0
        lastCycleAt = 0
        currentVowel = nil
        pendingDots = 0
    }

    public func press(_ key: ChunjiinKey) -> ChunjiinEvent {
        switch key {
        case .i: return pressVowel(primitive: "ㅣ")
        case .eu: return pressVowel(primitive: "ㅡ")
        case .dot: return pressDot()
        case .gk, .nr, .dt, .bp, .sh, .jc, .om:
            return pressCycle(key: key, jamoList: Self.cycleJamo(for: key))
        }
    }

    /// 멀티탭 창 안이면 공백 없이 순환 상태만 닫는다(endsMultiTap=true). 창 밖이면 자모 없이
    /// endsMultiTap=false를 돌려줘서, 호출자가 평소처럼 공백 문자를 입력하게 한다.
    public func space() -> ChunjiinEvent {
        let nowMs = now()
        let closesMultiTap = lastCycleKey != nil && nowMs - lastCycleAt <= multiTapWindowMs
        reset()
        return ChunjiinEvent(endsMultiTap: closesMultiTap)
    }

    private func pressCycle(key: ChunjiinKey, jamoList: [Character]) -> ChunjiinEvent {
        guard !jamoList.isEmpty else { return ChunjiinEvent() }
        let nowMs = now()
        pendingDots = 0
        currentVowel = nil

        let replacing = lastCycleKey == key && nowMs - lastCycleAt <= multiTapWindowMs
        cycleIndex = replacing ? (cycleIndex + 1) % jamoList.count : 0
        let selected = jamoList[cycleIndex]
        lastCycleKey = key
        lastCycleAt = nowMs
        return ChunjiinEvent(backspaces: replacing ? 1 : 0, jamo: [selected])
    }

    /// `iCombinations`의 옛 모음(old) 중 그 자체가 이미 겹모음인 것들. HangulComposer의
    /// jungHead는 한 단계만 되돌리므로(예: ㅘ→ㅗ), 이 값들은 완전히 걷어내려면 backspace가
    /// 2번 필요하다(ㅗ/ㅜ/ㅡ/ㅑ/ㅓ/ㅕ/ㅠ 같은 단일 모음은 jungHead가 없어 1번이면 된다).
    private static let doubledBackspaceICombinationSources: Set<Character> = ["ㅘ", "ㅝ"]

    private func pressVowel(primitive: Character) -> ChunjiinEvent {
        lastCycleKey = nil

        var combined: Character?
        var iCombinationSource: Character?
        switch (primitive, pendingDots) {
        case ("ㅣ", 1): combined = "ㅓ"
        case ("ㅣ", 2): combined = "ㅕ"
        case ("ㅡ", 1): combined = "ㅗ"
        case ("ㅡ", 2): combined = "ㅛ"
        default:
            if primitive == "ㅣ", let old = currentVowel {
                combined = Self.iCombinations[old]
                iCombinationSource = old
            }
        }

        pendingDots = 0
        let next = combined ?? primitive
        currentVowel = next

        if combined != nil {
            // ㆍ 자리표시자 또는 직전 모음 글자를 지우고 합쳐진 모음으로 바꾼다. 직전 모음이
            // 이미 겹모음(ㅘ/ㅝ)이면 완전히 지우는 데 2번이 필요하다.
            let backspaces = iCombinationSource.map { Self.doubledBackspaceICombinationSources.contains($0) ? 2 : 1 } ?? 1
            return ChunjiinEvent(backspaces: backspaces, jamo: [next])
        }
        return ChunjiinEvent(jamo: [next])
    }

    private func pressDot() -> ChunjiinEvent {
        lastCycleKey = nil
        let old = currentVowel
        // 대부분의 결합은 이전 모음이 밑모음(ㅗㅜㅡ 계열이 아닌 단순 모음)이라 1번만 지우면 되지만,
        // ㅚ(ㅗ+ㅣ의 결과)는 그 자체가 겹모음이라 ㅗ까지 완전히 걷어내려면 2번 지워야 한다
        // (HangulComposer.backspace가 jungHead로 한 단계씩만 되돌리기 때문).
        let next: Character?
        let backspacesNeeded: Int
        switch old {
        case "ㅣ": next = "ㅏ"; backspacesNeeded = 1
        case "ㅏ": next = "ㅑ"; backspacesNeeded = 1
        case "ㅡ": next = "ㅜ"; backspacesNeeded = 1
        case "ㅜ": next = "ㅠ"; backspacesNeeded = 1
        case "ㅚ": next = "ㅘ"; backspacesNeeded = 2
        default: next = nil; backspacesNeeded = 0
        }
        if let next {
            pendingDots = 0
            currentVowel = next
            return ChunjiinEvent(backspaces: backspacesNeeded, jamo: [next])
        }
        currentVowel = nil
        pendingDots = (pendingDots == 2) ? 1 : pendingDots + 1
        return ChunjiinEvent(jamo: [Self.dotCharacter])
    }
}

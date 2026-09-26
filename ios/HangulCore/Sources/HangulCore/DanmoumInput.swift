/// 단모음 자판의 짧은 멀티탭(300ms) 규칙을 두벌식 자모 스트림으로 바꾸는 순수 입력기.
/// `MobileHangulKeyboard.kt:205-232`(배치)를 클린룸으로 재구현했다. 겹모음(ㅘㅙㅝㅞ 등)은
/// 나랏글과 마찬가지로 이 입력기가 다루지 않고 서로 다른 두 모음 키를 순서대로 눌러
/// `HangulComposer`의 겹모음 결합에 맡긴다 — 단모음의 순환은 전부 "같은 키 2개 후보"
/// 단순 왕복(쌍자음, 획추가형 이중모음)뿐이다.
public enum DanmoumKey: Equatable {
    case bp, jc, dt, gk, sh // 쌍자음 순환(2단): ㅂㅃ ㅈㅉ ㄷㄸ ㄱㄲ ㅅㅆ
    case o, ae, e, eo, a, u // 이중모음 순환(2단): ㅗㅛ ㅐㅒ ㅔㅖ ㅓㅕ ㅏㅑ ㅜㅠ
    case m, n, ng, r, h // 직접 자음: ㅁ ㄴ ㅇ ㄹ ㅎ
    case k, t, ch, p // 직접 자음: ㅋ ㅌ ㅊ ㅍ
    case i, eu // 직접 모음: ㅣ ㅡ
}

public final class DanmoumInput {
    private let multiTapWindowMs: Int
    private let now: () -> Int

    private var lastCycleKey: DanmoumKey?
    private var cycleIndex = 0
    private var lastCycleAt = 0

    private static func cycleJamo(for key: DanmoumKey) -> [Character]? {
        switch key {
        case .bp: return ["ㅂ", "ㅃ"]
        case .jc: return ["ㅈ", "ㅉ"]
        case .dt: return ["ㄷ", "ㄸ"]
        case .gk: return ["ㄱ", "ㄲ"]
        case .sh: return ["ㅅ", "ㅆ"]
        case .o: return ["ㅗ", "ㅛ"]
        case .ae: return ["ㅐ", "ㅒ"]
        case .e: return ["ㅔ", "ㅖ"]
        case .eo: return ["ㅓ", "ㅕ"]
        case .a: return ["ㅏ", "ㅑ"]
        case .u: return ["ㅜ", "ㅠ"]
        case .m, .n, .ng, .r, .h, .k, .t, .ch, .p, .i, .eu: return nil
        }
    }

    private static func directJamo(for key: DanmoumKey) -> Character? {
        switch key {
        case .m: return "ㅁ"
        case .n: return "ㄴ"
        case .ng: return "ㅇ"
        case .r: return "ㄹ"
        case .h: return "ㅎ"
        case .k: return "ㅋ"
        case .t: return "ㅌ"
        case .ch: return "ㅊ"
        case .p: return "ㅍ"
        case .i: return "ㅣ"
        case .eu: return "ㅡ"
        case .bp, .jc, .dt, .gk, .sh, .o, .ae, .e, .eo, .a, .u: return nil
        }
    }

    public init(multiTapWindowMs: Int = 300, now: @escaping () -> Int) {
        self.multiTapWindowMs = multiTapWindowMs
        self.now = now
    }

    public func reset() {
        lastCycleKey = nil
        cycleIndex = 0
        lastCycleAt = 0
    }

    public func press(_ key: DanmoumKey) -> ChunjiinEvent {
        if let jamo = Self.directJamo(for: key) {
            lastCycleKey = nil
            return ChunjiinEvent(jamo: [jamo])
        }
        guard let list = Self.cycleJamo(for: key) else { return ChunjiinEvent() }
        let nowMs = now()
        let replacing = lastCycleKey == key && nowMs - lastCycleAt <= multiTapWindowMs
        cycleIndex = replacing ? (cycleIndex + 1) % list.count : 0
        let selected = list[cycleIndex]
        lastCycleKey = key
        lastCycleAt = nowMs
        return ChunjiinEvent(backspaces: replacing ? 1 : 0, jamo: [selected])
    }

    /// 멀티탭 창(300ms) 안이면 공백 없이 순환만 닫는다. 창 밖이면 호출자가 공백을 입력한다.
    public func space() -> ChunjiinEvent {
        let nowMs = now()
        let closesMultiTap = lastCycleKey != nil && nowMs - lastCycleAt <= multiTapWindowMs
        reset()
        return ChunjiinEvent(endsMultiTap: closesMultiTap)
    }
}

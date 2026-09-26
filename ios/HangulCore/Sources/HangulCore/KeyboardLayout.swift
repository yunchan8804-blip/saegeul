import CoreGraphics

public enum KeyboardLayer: Equatable {
    case hangul
    case latin
    case symbols
    case symbolsAlt
    case chunjiin
    case naratgul
    case danmoum
}

public enum KeyRole: Equatable {
    case character(String)
    case dubeolsik(Character)
    case chunjiin(ChunjiinKey)
    case naratgul(NaratgulKey)
    case danmoum(DanmoumKey)
    /// 여러 리터럴 문자 사이를 멀티탭으로 순환하는 키(천지인 4행의 ".,?!"). 조합기와 무관하다.
    case punctuationCycle([String])
    case shift
    case backspace
    case space
    case enter
    case layerToggle(KeyboardLayer)
    case globe
}

public struct KeyDefinition: Equatable {
    public let role: KeyRole
    public let label: String
    public let shiftedLabel: String?
    public let widthMultiplier: CGFloat
    /// 롱프레스(0.5초)로 열리는 대체 문자 목록. 비어 있으면 롱프레스 팝업을 열지 않는다.
    public let alternates: [String]

    public init(
        role: KeyRole,
        label: String,
        shiftedLabel: String? = nil,
        widthMultiplier: CGFloat = 1.0,
        alternates: [String] = []
    ) {
        self.role = role
        self.label = label
        self.shiftedLabel = shiftedLabel
        self.widthMultiplier = widthMultiplier
        self.alternates = alternates
    }
}

public struct KeyboardLayout {
    public let layer: KeyboardLayer
    public let rows: [[KeyDefinition]]
    /// 문자 키의 최소 높이. 천지인처럼 열이 적어 키가 넓어지는 층은 더 크게 잡는다.
    public let minKeyHeight: CGFloat

    public init(layer: KeyboardLayer, rows: [[KeyDefinition]], minKeyHeight: CGFloat = 42) {
        self.layer = layer
        self.rows = rows
        self.minKeyHeight = minKeyHeight
    }
}

public enum KeyboardLayouts {
    public static func layout(for layer: KeyboardLayer) -> KeyboardLayout {
        switch layer {
        case .hangul: return hangulLayout
        case .latin: return latinLayout
        case .symbols: return symbolsLayout
        case .symbolsAlt: return symbolsAltLayout
        case .chunjiin: return chunjiinLayout
        case .naratgul: return naratgulLayout
        case .danmoum: return danmoumLayout
        }
    }

    // MARK: - 키 빌더

    private static func dubeolsikKey(
        _ key: Character, _ label: String, _ shiftedLabel: String? = nil, alternates: [String] = []
    ) -> KeyDefinition {
        KeyDefinition(role: .dubeolsik(key), label: label, shiftedLabel: shiftedLabel, alternates: alternates)
    }

    private static func characterKey(_ value: String, shifted: String? = nil, alternates: [String] = []) -> KeyDefinition {
        KeyDefinition(role: .character(value), label: value, shiftedLabel: shifted, alternates: alternates)
    }

    private static func layerToggleKey(_ target: KeyboardLayer, label: String) -> KeyDefinition {
        KeyDefinition(role: .layerToggle(target), label: label, widthMultiplier: 1.25)
    }

    private static var shiftKey: KeyDefinition {
        KeyDefinition(role: .shift, label: "⇧", widthMultiplier: 1.5)
    }

    private static var backspaceKey: KeyDefinition {
        KeyDefinition(role: .backspace, label: "⌫", widthMultiplier: 1.5)
    }

    private static var spaceKey: KeyDefinition {
        KeyDefinition(role: .space, label: "스페이스")
    }

    /// 한글 자판이 2개 이상(두벌식/천지인/나랏글/단모음)이라 스페이스 롱프레스로 전환기가 뜨는
    /// 층에서 쓰는 라벨. 라틴·기호 층의 스페이스는 그대로 "스페이스"를 쓴다.
    private static var spaceKeyWithLayoutSwitcher: KeyDefinition {
        KeyDefinition(role: .space, label: "스페이스 ▾")
    }

    private static var enterKey: KeyDefinition {
        KeyDefinition(role: .enter, label: "줄바꿈", widthMultiplier: 1.5)
    }

    private static var globeKey: KeyDefinition {
        KeyDefinition(role: .globe, label: "🌐")
    }

    // MARK: - 층 정의

    private static var hangulLayout: KeyboardLayout {
        let row1: [KeyDefinition] = [
            dubeolsikKey("q", "ㅂ", "ㅃ", alternates: ["ㅃ"]), dubeolsikKey("w", "ㅈ", "ㅉ", alternates: ["ㅉ"]),
            dubeolsikKey("e", "ㄷ", "ㄸ", alternates: ["ㄸ"]), dubeolsikKey("r", "ㄱ", "ㄲ", alternates: ["ㄲ"]),
            dubeolsikKey("t", "ㅅ", "ㅆ", alternates: ["ㅆ"]), dubeolsikKey("y", "ㅛ"),
            dubeolsikKey("u", "ㅕ"), dubeolsikKey("i", "ㅑ"), dubeolsikKey("o", "ㅐ", "ㅒ", alternates: ["ㅒ"]),
            dubeolsikKey("p", "ㅔ", "ㅖ", alternates: ["ㅖ"]),
        ]
        let row2: [KeyDefinition] = [
            dubeolsikKey("a", "ㅁ"), dubeolsikKey("s", "ㄴ"), dubeolsikKey("d", "ㅇ"),
            dubeolsikKey("f", "ㄹ"), dubeolsikKey("g", "ㅎ"), dubeolsikKey("h", "ㅗ"),
            dubeolsikKey("j", "ㅓ"), dubeolsikKey("k", "ㅏ"), dubeolsikKey("l", "ㅣ"),
        ]
        let row3: [KeyDefinition] = [shiftKey]
            + [
                dubeolsikKey("z", "ㅋ"), dubeolsikKey("x", "ㅌ"), dubeolsikKey("c", "ㅊ"),
                dubeolsikKey("v", "ㅍ"), dubeolsikKey("b", "ㅠ"), dubeolsikKey("n", "ㅜ"),
                dubeolsikKey("m", "ㅡ"),
            ]
            + [backspaceKey]
        let row4: [KeyDefinition] = [
            globeKey,
            layerToggleKey(.latin, label: "한/영"),
            layerToggleKey(.symbols, label: "123"),
            spaceKeyWithLayoutSwitcher,
            enterKey,
        ]
        return KeyboardLayout(layer: .hangul, rows: [row1, row2, row3, row4])
    }

    private static var latinLayout: KeyboardLayout {
        let row1NumberAlternates = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"]
        let row1 = zip("qwertyuiop", row1NumberAlternates).map { letter, digit in
            characterKey(String(letter), shifted: String(letter).uppercased(), alternates: [digit])
        }
        let row2 = "asdfghjkl".map { characterKey(String($0), shifted: String($0).uppercased()) }
        let row3: [KeyDefinition] = [shiftKey]
            + "zxcvbnm".map { characterKey(String($0), shifted: String($0).uppercased()) }
            + [backspaceKey]
        let row4: [KeyDefinition] = [
            globeKey,
            layerToggleKey(.hangul, label: "한/영"),
            layerToggleKey(.symbols, label: "123"),
            spaceKey,
            enterKey,
        ]
        return KeyboardLayout(layer: .latin, rows: [row1, row2, row3, row4])
    }

    private static var symbolsLayout: KeyboardLayout {
        let row1 = "1234567890".map { characterKey(String($0)) }
        let row2 = "-/:;()₩&@\"".map { characterKey(String($0)) }
        let row3: [KeyDefinition] = [layerToggleKey(.symbolsAlt, label: "#+=")]
            + ".,?!'".map { characterKey(String($0)) }
            + [backspaceKey]
        // "ABC" 복귀 대상은 뷰가 추적하는 "직전 문자 층"으로 실행 시 대체된다. 여기서는 자리표시용 기본값.
        let row4: [KeyDefinition] = [
            layerToggleKey(.hangul, label: "ABC"),
            spaceKey,
            enterKey,
        ]
        return KeyboardLayout(layer: .symbols, rows: [row1, row2, row3, row4])
    }

    private static var symbolsAltLayout: KeyboardLayout {
        let row1 = "[]{}#%^*+=".map { characterKey(String($0)) }
        let row2 = "_\\|~<>€£¥•".map { characterKey(String($0)) }
        let row3: [KeyDefinition] = [layerToggleKey(.symbols, label: "123")]
            + ".,?!'".map { characterKey(String($0)) }
            + [backspaceKey]
        let row4: [KeyDefinition] = [
            layerToggleKey(.hangul, label: "ABC"),
            spaceKey,
            enterKey,
        ]
        return KeyboardLayout(layer: .symbolsAlt, rows: [row1, row2, row3, row4])
    }

    private static func chunjiinKey(_ key: ChunjiinKey, _ label: String) -> KeyDefinition {
        KeyDefinition(role: .chunjiin(key), label: label)
    }

    private static var chunjiinLayout: KeyboardLayout {
        let row1: [KeyDefinition] = [
            chunjiinKey(.i, "ㅣ"), chunjiinKey(.dot, "ㆍ"), chunjiinKey(.eu, "ㅡ"), backspaceKey,
        ]
        let row2: [KeyDefinition] = [
            chunjiinKey(.gk, "ㄱㅋ"), chunjiinKey(.nr, "ㄴㄹ"), chunjiinKey(.dt, "ㄷㅌ"), enterKey,
        ]
        let row3: [KeyDefinition] = [
            chunjiinKey(.bp, "ㅂㅍ"), chunjiinKey(.sh, "ㅅㅎ"), chunjiinKey(.jc, "ㅈㅊ"),
            layerToggleKey(.symbols, label: "123"),
        ]
        let row4: [KeyDefinition] = [
            KeyDefinition(role: .globe, label: "🌐", widthMultiplier: 0.8),
            // 한/영·ㅇㅁ·.,?!는 모두 문자 열(1행~3행의 1~3번째 칸)과 같은 1.0배라 지구본이
            // 없을 때 왼쪽 3칸이 그 칸들과 정확히 겹친다. 스페이스는 채우기가 아니라 3행의
            // 기능열(123, 1.25배)과 같은 고정 폭으로 오른쪽 기능열에 맞춘다.
            KeyDefinition(role: .layerToggle(.latin), label: "한/영"),
            chunjiinKey(.om, "ㅇㅁ"),
            KeyDefinition(role: .punctuationCycle([".", ",", "?", "!"]), label: ".,?!"),
            KeyDefinition(role: .space, label: "스페이스 ▾", widthMultiplier: 1.25),
        ]
        return KeyboardLayout(layer: .chunjiin, rows: [row1, row2, row3, row4], minKeyHeight: 52)
    }

    private static func naratgulKey(_ key: NaratgulKey, _ label: String) -> KeyDefinition {
        KeyDefinition(role: .naratgul(key), label: label)
    }

    /// `MobileHangulKeyboard.kt:335-360`의 `naratgulCore`(비중앙 배치)를 그대로 따른다: 1~3행은
    /// 자음 2개+모음 순환 1개(3칸)에 기능 키 하나(지우기/줄바꿈/123)를 붙이고, 4행은 원본의
    /// 획추가/ㅡ/쌍자음 3칸에 지구본·한영·스페이스를 더한다(천지인 4행과 같은 정렬 규칙:
    /// 지구본만 0.8배, 나머지 문자 칸은 1.0배로 위 칸들과 겹치고 스페이스는 123과 같은
    /// 1.25배 고정 폭).
    private static var naratgulLayout: KeyboardLayout {
        let row1: [KeyDefinition] = [
            naratgulKey(.g, "ㄱ"), naratgulKey(.n, "ㄴ"), naratgulKey(.a, "ㅏㅓ"), backspaceKey,
        ]
        let row2: [KeyDefinition] = [
            naratgulKey(.r, "ㄹ"), naratgulKey(.m, "ㅁ"), naratgulKey(.o, "ㅗㅜ"), enterKey,
        ]
        let row3: [KeyDefinition] = [
            naratgulKey(.s, "ㅅ"), naratgulKey(.ng, "ㅇ"), naratgulKey(.i, "ㅣ"),
            layerToggleKey(.symbols, label: "123"),
        ]
        let row4: [KeyDefinition] = [
            KeyDefinition(role: .globe, label: "🌐", widthMultiplier: 0.8),
            KeyDefinition(role: .layerToggle(.latin), label: "한/영"),
            naratgulKey(.addStroke, "획추가"),
            naratgulKey(.eu, "ㅡ"),
            naratgulKey(.doubleConsonant, "쌍자음"),
            KeyDefinition(role: .space, label: "스페이스 ▾", widthMultiplier: 1.25),
        ]
        return KeyboardLayout(layer: .naratgul, rows: [row1, row2, row3, row4], minKeyHeight: 52)
    }

    private static func danmoumKey(_ key: DanmoumKey, _ label: String) -> KeyDefinition {
        KeyDefinition(role: .danmoum(key), label: label)
    }

    /// `MobileHangulKeyboard.kt:205-232`의 `danmoum()`을 따른다: 1~2행은 8칸, 3행은 자모 6개+
    /// 지우기(다른 층과 같은 1.5배 지우기 키)다. 4행은 한글/라틴 4행과 똑같은 모양
    /// (지구본·한영·123·스페이스·줄바꿈)을 그대로 재사용한다 — Android의 `qwertyBottom`은
    /// 쉼표·마침표 키가 더 있지만, 그 둘은 이 키보드의 다른 층에 없는 새 리터럴 키라 도입하지
    /// 않고 기존 4행 패턴을 그대로 썼다(완료 보고의 모호점 표에 남긴다).
    private static var danmoumLayout: KeyboardLayout {
        let row1: [KeyDefinition] = [
            danmoumKey(.bp, "ㅂㅃ"), danmoumKey(.jc, "ㅈㅉ"), danmoumKey(.dt, "ㄷㄸ"), danmoumKey(.gk, "ㄱㄲ"),
            danmoumKey(.sh, "ㅅㅆ"), danmoumKey(.o, "ㅗㅛ"), danmoumKey(.ae, "ㅐㅒ"), danmoumKey(.e, "ㅔㅖ"),
        ]
        let row2: [KeyDefinition] = [
            danmoumKey(.m, "ㅁ"), danmoumKey(.n, "ㄴ"), danmoumKey(.ng, "ㅇ"), danmoumKey(.r, "ㄹ"),
            danmoumKey(.h, "ㅎ"), danmoumKey(.eo, "ㅓㅕ"), danmoumKey(.a, "ㅏㅑ"), danmoumKey(.i, "ㅣ"),
        ]
        let row3: [KeyDefinition] = [
            danmoumKey(.k, "ㅋ"), danmoumKey(.t, "ㅌ"), danmoumKey(.ch, "ㅊ"), danmoumKey(.p, "ㅍ"),
            danmoumKey(.u, "ㅜㅠ"), danmoumKey(.eu, "ㅡ"), backspaceKey,
        ]
        let row4: [KeyDefinition] = [
            globeKey,
            layerToggleKey(.latin, label: "한/영"),
            layerToggleKey(.symbols, label: "123"),
            spaceKeyWithLayoutSwitcher,
            enterKey,
        ]
        return KeyboardLayout(layer: .danmoum, rows: [row1, row2, row3, row4])
    }
}

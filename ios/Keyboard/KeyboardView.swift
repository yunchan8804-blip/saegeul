import HangulCore
import UIKit

enum ShiftState {
    case off
    case once
    case locked
}

final class KeyButton: UIButton {
    let definition: KeyDefinition

    override var isHighlighted: Bool {
        didSet { alpha = isHighlighted ? 0.7 : 1.0 }
    }

    init(definition: KeyDefinition, emphasized: Bool = false, minHeight: CGFloat = 42) {
        self.definition = definition
        super.init(frame: .zero)
        translatesAutoresizingMaskIntoConstraints = false
        layer.cornerRadius = 6
        clipsToBounds = true
        setTitleColor(.label, for: .normal)
        accessibilityTraits = .keyboardKey
        heightAnchor.constraint(equalToConstant: minHeight).isActive = true
        backgroundColor = emphasized ? .systemGray3 : .systemBackground
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

/// 확장 컨테이너에 저장하는 "천지인/두벌식" 선호 자판. 다음에 키보드를 열 때 복원한다.
enum HangulLayoutPreference {
    private static let userDefaultsKey = "hangulLayout"

    static func load() -> KeyboardLayer {
        switch UserDefaults.standard.string(forKey: userDefaultsKey) {
        case "chunjiin": return .chunjiin
        case "naratgul": return .naratgul
        case "danmoum": return .danmoum
        default: return .hangul
        }
    }

    static func save(_ layer: KeyboardLayer) {
        let value: String
        switch layer {
        case .chunjiin: value = "chunjiin"
        case .naratgul: value = "naratgul"
        case .danmoum: value = "danmoum"
        default: value = "dubeolsik"
        }
        UserDefaults.standard.set(value, forKey: userDefaultsKey)
    }
}

final class KeyboardView: UIView {
    var showGlobe: Bool {
        didSet {
            guard showGlobe != oldValue else { return }
            rebuild()
        }
    }

    var onKey: (KeyDefinition, ShiftState) -> Void
    var onAlternateSelected: (KeyDefinition, String) -> Void
    var onChunjiinKey: (ChunjiinKey) -> Void
    var onChunjiinSpace: () -> Void
    var onNaratgulKey: (NaratgulKey) -> Void
    var onNaratgulSpace: () -> Void
    var onDanmoumKey: (DanmoumKey) -> Void
    var onDanmoumSpace: () -> Void
    var onBackspace: () -> Void
    var onSpace: () -> Void
    var onSpaceCursorMove: (Int) -> Void
    var onEnter: () -> Void
    var onLayerChange: (KeyboardLayer) -> Void
    var onGlobe: () -> Void

    private(set) var globeButton: UIButton?

    private var currentLayer: KeyboardLayer
    private var previousCharacterLayer: KeyboardLayer = .hangul
    private var shiftState: ShiftState = .off {
        didSet { updateShiftAppearance() }
    }
    private var lastShiftTapAt: Date?

    private let mainStack = UIStackView()
    private var shiftableButtons: [KeyButton] = []
    private var shiftButton: KeyButton?

    private var backspaceRepeatTimer: Timer?
    private static let backspaceInitialDelay: TimeInterval = 0.4
    private static let backspaceRepeatInterval: TimeInterval = 0.05
    private static let shiftDoubleTapWindow: TimeInterval = 0.3
    private static let longPressAlternatesDelay: TimeInterval = 0.5

    // MARK: - 키 누름 미리보기 / 롱프레스 대체 문자

    private var previewCard: UIView?
    private var previewPrimaryLabel: UILabel?
    private var previewWidthConstraint: NSLayoutConstraint?
    private var alternateChipButtons: [(text: String, button: UIButton)] = []
    private var longPressTimer: Timer?
    private var isAlternatesMode = false
    /// 대체 문자 팝업에서 현재 하이라이트된 항목. nil이면 기본(원래 키 문자)이 선택된 상태다.
    private var highlightedAlternateIndex: Int?
    private var activeLetterButton: KeyButton?

    // MARK: - 스페이스 스와이프 커서 이동

    private var spaceDragStartX: CGFloat?
    private var spaceDragAppliedSteps = 0
    private var spaceDragActive = false
    private static let spaceDragStepPoints: CGFloat = 10

    // MARK: - 자판 전환기(스페이스 롱프레스)

    private var layoutSwitcherSheet: UIView?
    private var layoutOptionButtons: [(button: UIButton, layer: KeyboardLayer)] = []
    private static let layoutSwitcherCheckmarkColor = UIColor(red: 0.03, green: 0.48, blue: 0.35, alpha: 1.0)
    private var spaceLongPressTimer: Timer?
    private static let spaceLongPressDelay: TimeInterval = 0.5
    private var preferredHangulLayer: KeyboardLayer

    // MARK: - 천지인 ".,?!" 멀티탭

    var onPunctuationCycle: (String, Bool) -> Void
    private var lastPunctuationTapAt: Date?
    private var punctuationCycleIndex = 0
    private static let punctuationCycleWindow: TimeInterval = 1.5

    init(
        showGlobe: Bool,
        initialLayer: KeyboardLayer = .hangul,
        onKey: @escaping (KeyDefinition, ShiftState) -> Void,
        onAlternateSelected: @escaping (KeyDefinition, String) -> Void,
        onChunjiinKey: @escaping (ChunjiinKey) -> Void,
        onChunjiinSpace: @escaping () -> Void,
        onNaratgulKey: @escaping (NaratgulKey) -> Void,
        onNaratgulSpace: @escaping () -> Void,
        onDanmoumKey: @escaping (DanmoumKey) -> Void,
        onDanmoumSpace: @escaping () -> Void,
        onPunctuationCycle: @escaping (String, Bool) -> Void,
        onBackspace: @escaping () -> Void,
        onSpace: @escaping () -> Void,
        onSpaceCursorMove: @escaping (Int) -> Void,
        onEnter: @escaping () -> Void,
        onLayerChange: @escaping (KeyboardLayer) -> Void,
        onGlobe: @escaping () -> Void
    ) {
        self.showGlobe = showGlobe
        self.currentLayer = initialLayer
        self.preferredHangulLayer = HangulLayoutPreference.load()
        self.onKey = onKey
        self.onAlternateSelected = onAlternateSelected
        self.onChunjiinKey = onChunjiinKey
        self.onChunjiinSpace = onChunjiinSpace
        self.onNaratgulKey = onNaratgulKey
        self.onNaratgulSpace = onNaratgulSpace
        self.onDanmoumKey = onDanmoumKey
        self.onDanmoumSpace = onDanmoumSpace
        self.onPunctuationCycle = onPunctuationCycle
        self.onBackspace = onBackspace
        self.onSpace = onSpace
        self.onSpaceCursorMove = onSpaceCursorMove
        self.onEnter = onEnter
        self.onLayerChange = onLayerChange
        self.onGlobe = onGlobe
        super.init(frame: .zero)
        setUpLayout()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// 지정한 층으로 그리드를 다시 그린다. 기호 계열(층→층) 이동은 "직전 문자 층"을 갱신하지 않는다.
    func setLayer(_ layer: KeyboardLayer) {
        guard layer != currentLayer else { return }
        if Self.isCharacterLayer(currentLayer) {
            previousCharacterLayer = currentLayer
        }
        currentLayer = layer
        shiftState = .off
        rebuild()
        onLayerChange(layer)
    }

    private static func isCharacterLayer(_ layer: KeyboardLayer) -> Bool {
        layer == .hangul || layer == .latin || layer == .chunjiin || layer == .naratgul || layer == .danmoum
    }

    private func setUpLayout() {
        backgroundColor = .systemGray4
        accessibilityIdentifier = "saegeulKeyboard"
        isAccessibilityElement = false

        mainStack.axis = .vertical
        mainStack.spacing = 8
        mainStack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(mainStack)
        NSLayoutConstraint.activate([
            mainStack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 6),
            mainStack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -6),
            mainStack.topAnchor.constraint(equalTo: topAnchor, constant: 8),
            mainStack.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -8),
        ])
        rebuild()
    }

    private func rebuild() {
        hidePreview()
        resetLetterTrackingState()
        longPressTimer?.invalidate()
        longPressTimer = nil
        hideLayoutSwitcher()
        spaceLongPressTimer?.invalidate()
        spaceLongPressTimer = nil

        for view in mainStack.arrangedSubviews {
            mainStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        globeButton = nil
        shiftButton = nil
        shiftableButtons = []

        let layout = KeyboardLayouts.layout(for: currentLayer)
        var unitKeyButton: KeyButton?
        for row in layout.rows {
            addRow(row, minKeyHeight: layout.minKeyHeight, unitKeyButton: &unitKeyButton)
        }
        updateShiftAppearance()
    }

    /// 행 하나를 만들어 먼저 mainStack에 붙인 뒤 폭 제약을 건다.
    /// AutoLayout 제약은 두 뷰가 같은 계층(공통 조상)에 있어야 활성화할 수 있어서,
    /// 다른 행(예: 1행)의 버튼을 기준(anchor)으로 삼는 4행은 자기 스택이 먼저 mainStack에
    /// 붙어 있어야 한다. 이 행 안에 문자 키가 있으면 그 첫 키를 이 행의 폭 기준으로 삼고,
    /// 없으면(예: 4행) 앞선 행에서 정해진 전역 기준(unitKeyButton)을 빌린다.
    private func addRow(_ definitions: [KeyDefinition], minKeyHeight: CGFloat, unitKeyButton: inout KeyButton?) {
        let stack = UIStackView()
        stack.axis = .horizontal
        stack.spacing = 6
        stack.distribution = .fill

        var rowButtons: [(KeyDefinition, KeyButton)] = []
        for definition in definitions {
            if case .globe = definition.role, !showGlobe { continue }
            let button = makeButton(for: definition, minHeight: minKeyHeight)
            rowButtons.append((definition, button))
            stack.addArrangedSubview(button)
        }

        mainStack.addArrangedSubview(stack)

        var localAnchor: KeyButton?
        for (definition, button) in rowButtons {
            switch definition.role {
            case .character, .dubeolsik, .chunjiin, .naratgul, .danmoum, .punctuationCycle:
                if localAnchor == nil { localAnchor = button }
                shiftableButtons.append(button)
            default:
                break
            }
        }
        if let localAnchor, unitKeyButton == nil {
            unitKeyButton = localAnchor
        }

        guard let anchor = localAnchor ?? unitKeyButton else {
            stack.distribution = .fillEqually
            return
        }

        for (definition, button) in rowButtons {
            switch definition.role {
            case .character, .dubeolsik, .chunjiin, .naratgul, .danmoum, .punctuationCycle:
                if button !== anchor {
                    button.widthAnchor.constraint(equalTo: anchor.widthAnchor, multiplier: definition.widthMultiplier).isActive = true
                }
            case .shift, .backspace, .enter, .layerToggle:
                button.widthAnchor.constraint(equalTo: anchor.widthAnchor, multiplier: definition.widthMultiplier).isActive = true
            case .space:
                if currentLayer == .chunjiin || currentLayer == .naratgul {
                    // 천지인·나랏글은 스페이스도 오른쪽 기능열 폭(1.25배)에 맞춰 고정한다(정렬 요구사항).
                    // 다른 층의 스페이스는 남는 폭을 채우는 기존 동작을 그대로 둔다.
                    button.widthAnchor.constraint(equalTo: anchor.widthAnchor, multiplier: definition.widthMultiplier).isActive = true
                } else {
                    button.setContentHuggingPriority(.defaultLow, for: .horizontal)
                }
            case .globe:
                if definition.widthMultiplier != 1.0 {
                    // 천지인처럼 명시적으로 비율을 준 경우(0.8)만 다른 문자 키처럼 anchor 비례로 잡는다.
                    // 한글/라틴의 기존 지구본(비율 지정 없음)은 최소폭+hugging 방식을 그대로 쓴다.
                    button.widthAnchor.constraint(equalTo: anchor.widthAnchor, multiplier: definition.widthMultiplier).isActive = true
                } else {
                    button.setContentHuggingPriority(.required, for: .horizontal)
                    button.widthAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
                }
            }
        }
    }

    private func makeButton(for definition: KeyDefinition, minHeight: CGFloat) -> KeyButton {
        switch definition.role {
        case .character, .dubeolsik:
            let button = KeyButton(definition: definition, minHeight: minHeight)
            button.titleLabel?.font = .preferredFont(forTextStyle: .title2)
            button.setTitle(definition.label, for: .normal)
            button.accessibilityIdentifier = identifier(for: definition)
            button.accessibilityLabel = definition.label
            button.addTarget(self, action: #selector(letterTouchDown(_:)), for: .touchDown)
            button.addTarget(self, action: #selector(letterTouchDragged(_:forEvent:)), for: [.touchDragInside, .touchDragOutside])
            button.addTarget(self, action: #selector(letterTouchUp(_:)), for: [.touchUpInside, .touchUpOutside])
            button.addTarget(self, action: #selector(letterTouchCancelled), for: .touchCancel)
            return button
        case .chunjiin(let key):
            let button = KeyButton(definition: definition, minHeight: minHeight)
            button.titleLabel?.font = .preferredFont(forTextStyle: .title2)
            button.setTitle(definition.label, for: .normal)
            button.accessibilityIdentifier = "key.cj.\(chunjiinIdentifierSuffix(key))"
            button.accessibilityLabel = definition.label
            button.addTarget(self, action: #selector(chunjiinKeyTapped(_:)), for: .touchUpInside)
            return button
        case .naratgul(let key):
            let button = KeyButton(definition: definition, minHeight: minHeight)
            button.titleLabel?.font = .preferredFont(forTextStyle: .title2)
            button.setTitle(definition.label, for: .normal)
            button.accessibilityIdentifier = "key.ng.\(naratgulIdentifierSuffix(key))"
            button.accessibilityLabel = definition.label
            button.addTarget(self, action: #selector(naratgulKeyTapped(_:)), for: .touchUpInside)
            return button
        case .danmoum(let key):
            let button = KeyButton(definition: definition, minHeight: minHeight)
            // 단모음은 8열이라 키가 좁다. 기본(title2)보다 작은 title3로 라벨이 잘리지 않게 한다.
            button.titleLabel?.font = .preferredFont(forTextStyle: .title3)
            button.setTitle(definition.label, for: .normal)
            button.accessibilityIdentifier = "key.dm.\(danmoumIdentifierSuffix(key))"
            button.accessibilityLabel = definition.label
            button.addTarget(self, action: #selector(danmoumKeyTapped(_:)), for: .touchUpInside)
            return button
        case .punctuationCycle:
            let button = KeyButton(definition: definition, minHeight: minHeight)
            button.titleLabel?.font = .preferredFont(forTextStyle: .title2)
            button.setTitle(definition.label, for: .normal)
            button.accessibilityIdentifier = "key.cj.punct"
            button.accessibilityLabel = definition.label
            button.addTarget(self, action: #selector(punctuationCycleTapped(_:)), for: .touchUpInside)
            return button
        case .shift:
            let button = KeyButton(definition: definition, emphasized: true, minHeight: minHeight)
            button.titleLabel?.font = .preferredFont(forTextStyle: .headline)
            button.accessibilityIdentifier = "key.shift"
            button.addTarget(self, action: #selector(shiftTapped), for: .touchUpInside)
            shiftButton = button
            return button
        case .backspace:
            let button = KeyButton(definition: definition, emphasized: true, minHeight: minHeight)
            button.titleLabel?.font = .preferredFont(forTextStyle: .headline)
            button.setTitle(definition.label, for: .normal)
            button.accessibilityIdentifier = "key.backspace"
            button.accessibilityLabel = "지우기"
            button.addTarget(self, action: #selector(backspaceTouchDown), for: .touchDown)
            button.addTarget(self, action: #selector(backspaceTouchUp), for: [.touchUpInside, .touchUpOutside, .touchCancel])
            return button
        case .space:
            let button = KeyButton(definition: definition, minHeight: minHeight)
            button.titleLabel?.font = .preferredFont(forTextStyle: .headline)
            button.setTitle(definition.label, for: .normal)
            button.accessibilityIdentifier = "key.space"
            button.accessibilityLabel = "스페이스"
            button.addTarget(self, action: #selector(spaceTouchDown), for: .touchDown)
            button.addTarget(self, action: #selector(spaceTouchDragged(_:forEvent:)), for: [.touchDragInside, .touchDragOutside])
            button.addTarget(self, action: #selector(spaceTouchUpInside), for: .touchUpInside)
            button.addTarget(self, action: #selector(spaceTouchCancelled), for: [.touchUpOutside, .touchCancel])
            return button
        case .enter:
            let button = KeyButton(definition: definition, emphasized: true, minHeight: minHeight)
            button.titleLabel?.font = .preferredFont(forTextStyle: .headline)
            button.setTitle(definition.label, for: .normal)
            button.accessibilityIdentifier = "key.enter"
            button.accessibilityLabel = "줄바꿈"
            button.addTarget(self, action: #selector(enterTapped), for: .touchUpInside)
            return button
        case .layerToggle(let target):
            let button = KeyButton(definition: definition, emphasized: true, minHeight: minHeight)
            button.titleLabel?.font = .preferredFont(forTextStyle: .headline)
            button.setTitle(definition.label, for: .normal)
            button.accessibilityIdentifier = layerToggleIdentifier(currentLayer: currentLayer, target: target)
            button.accessibilityLabel = definition.label
            button.addTarget(self, action: #selector(layerToggleTapped(_:)), for: .touchUpInside)
            return button
        case .globe:
            let button = KeyButton(definition: definition, emphasized: true, minHeight: minHeight)
            button.titleLabel?.font = .preferredFont(forTextStyle: .headline)
            button.setTitle(definition.label, for: .normal)
            button.accessibilityIdentifier = "key.globe"
            button.accessibilityLabel = "다음 키보드"
            globeButton = button
            return button
        }
    }

    private func chunjiinIdentifierSuffix(_ key: ChunjiinKey) -> String {
        switch key {
        case .i: return "i"
        case .dot: return "dot"
        case .eu: return "eu"
        case .gk: return "gk"
        case .nr: return "nr"
        case .dt: return "dt"
        case .bp: return "bp"
        case .sh: return "sh"
        case .jc: return "jc"
        case .om: return "om"
        }
    }

    private func naratgulIdentifierSuffix(_ key: NaratgulKey) -> String {
        switch key {
        case .g: return "g"
        case .n: return "n"
        case .r: return "r"
        case .m: return "m"
        case .s: return "s"
        case .ng: return "ng"
        case .a: return "a"
        case .o: return "o"
        case .i: return "i"
        case .eu: return "eu"
        case .addStroke: return "addstroke"
        case .doubleConsonant: return "doubleconsonant"
        }
    }

    private func danmoumIdentifierSuffix(_ key: DanmoumKey) -> String {
        switch key {
        case .bp: return "bp"
        case .jc: return "jc"
        case .dt: return "dt"
        case .gk: return "gk"
        case .sh: return "sh"
        case .o: return "o"
        case .ae: return "ae"
        case .e: return "e"
        case .eo: return "eo"
        case .a: return "a"
        case .u: return "u"
        case .m: return "m"
        case .n: return "n"
        case .ng: return "ng"
        case .r: return "r"
        case .h: return "h"
        case .k: return "k"
        case .t: return "t"
        case .ch: return "ch"
        case .p: return "p"
        case .i: return "i"
        case .eu: return "eu"
        }
    }

    private func identifier(for definition: KeyDefinition) -> String {
        switch definition.role {
        case .dubeolsik(let key):
            return "key.\(key)"
        case .character(let value):
            switch currentLayer {
            case .latin:
                return "key.latin.\(value)"
            case .symbols, .symbolsAlt:
                return "key.sym.\(value)"
            case .hangul, .chunjiin, .naratgul, .danmoum:
                return "key.\(value)"
            }
        default:
            return ""
        }
    }

    /// 층 전환 키 접근성 식별자. 기호 층에서 문자 층(한글/라틴)으로 돌아가는 "ABC" 키는
    /// 실행 중 대상이 "직전 문자 층"으로 바뀌므로, 데이터에 박힌 target과 무관하게 key.layer.abc를 쓴다.
    private func layerToggleIdentifier(currentLayer: KeyboardLayer, target: KeyboardLayer) -> String {
        let isSymbolFamily = currentLayer == .symbols || currentLayer == .symbolsAlt
        let targetIsCharacterLayer = target == .hangul || target == .latin
        if isSymbolFamily && targetIsCharacterLayer {
            return "key.layer.abc"
        }
        switch target {
        case .hangul: return "key.layer.hangul"
        case .latin: return "key.layer.latin"
        case .symbols: return "key.layer.symbols"
        case .symbolsAlt: return "key.layer.symbolsAlt"
        case .chunjiin: return "key.layer.chunjiin"
        case .naratgul: return "key.layer.naratgul"
        case .danmoum: return "key.layer.danmoum"
        }
    }

    // MARK: - 액션

    @objc private func letterTouchDown(_ sender: KeyButton) {
        activeLetterButton = sender
        isAlternatesMode = false
        highlightedAlternateIndex = nil
        showPreview(for: sender)

        longPressTimer?.invalidate()
        longPressTimer = nil
        guard !sender.definition.alternates.isEmpty else { return }
        let timer = Timer(timeInterval: Self.longPressAlternatesDelay, repeats: false) { [weak self, weak sender] _ in
            guard let self, let sender, self.activeLetterButton === sender else { return }
            self.enterAlternatesMode(for: sender)
        }
        RunLoop.current.add(timer, forMode: .common)
        longPressTimer = timer
    }

    @objc private func letterTouchDragged(_ sender: KeyButton, forEvent event: UIEvent) {
        guard isAlternatesMode, activeLetterButton === sender, let touch = event.allTouches?.first else { return }
        updateAlternateHighlight(atX: touch.location(in: self).x)
    }

    /// iOS 표준 롱프레스 팝업처럼, 손을 떼는 순간 현재 하이라이트된 항목(기본은 원래 키 문자)을
    /// 그대로 확정하고 팝업을 닫는다. 열어 둔 채로 기다리지 않는다.
    @objc private func letterTouchUp(_ sender: KeyButton) {
        longPressTimer?.invalidate()
        longPressTimer = nil

        if isAlternatesMode, let index = highlightedAlternateIndex, index < alternateChipButtons.count {
            onAlternateSelected(sender.definition, alternateChipButtons[index].text)
        } else {
            confirmPrimary(sender)
        }
        hidePreview()
        resetLetterTrackingState()
    }

    @objc private func letterTouchCancelled() {
        longPressTimer?.invalidate()
        longPressTimer = nil
        hidePreview()
        resetLetterTrackingState()
    }

    private func confirmPrimary(_ sender: KeyButton) {
        let stateForThisTap = shiftState
        onKey(sender.definition, stateForThisTap)
        if shiftState == .once {
            shiftState = .off
        }
    }

    private func resetLetterTrackingState() {
        isAlternatesMode = false
        highlightedAlternateIndex = nil
        activeLetterButton = nil
    }

    // MARK: - 미리보기 카드 / 대체 문자 팝업

    private func showPreview(for button: KeyButton) {
        hidePreview()

        let card = UIView()
        card.translatesAutoresizingMaskIntoConstraints = false
        card.backgroundColor = .systemBackground
        card.layer.cornerRadius = 8
        card.layer.borderWidth = 1
        card.layer.borderColor = UIColor.separator.cgColor
        // 카드 자신은 접근성 요소가 아니지만, 롱프레스로 대체 문자 버튼이 들어오면 그건 노출돼야
        // 하므로 하위 트리 전체를 숨기는 accessibilityElementsHidden은 쓰지 않는다.
        card.isAccessibilityElement = false
        addSubview(card)

        let label = UILabel()
        label.translatesAutoresizingMaskIntoConstraints = false
        label.font = .preferredFont(forTextStyle: .title1)
        label.textAlignment = .center
        label.text = button.title(for: .normal)
        label.isAccessibilityElement = false
        card.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: card.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: card.centerYAnchor),
        ])

        let width = card.widthAnchor.constraint(equalTo: button.widthAnchor, multiplier: 1.4)
        let height = card.heightAnchor.constraint(equalToConstant: 52)
        let centerX = card.centerXAnchor.constraint(equalTo: button.centerXAnchor)
        centerX.priority = .defaultHigh
        // 카드 하단이 키 상단에 맞닿는 게 기본. 위쪽 공간이 없는 1행에서는 아래 required 제약이 이겨서
        // 카드가 키보드 뷰 위 경계에 clamp되고(넘치지 않고) 대신 키와 겹친다.
        let bottomToKeyTop = card.bottomAnchor.constraint(equalTo: button.topAnchor)
        bottomToKeyTop.priority = .defaultHigh
        let topClamp = card.topAnchor.constraint(greaterThanOrEqualTo: topAnchor, constant: 2)
        let leadingClamp = card.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 2)
        let trailingClamp = card.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -2)
        NSLayoutConstraint.activate([width, height, centerX, bottomToKeyTop, topClamp, leadingClamp, trailingClamp])

        previewCard = card
        previewPrimaryLabel = label
        previewWidthConstraint = width
    }

    private func hidePreview() {
        previewCard?.removeFromSuperview()
        previewCard = nil
        previewPrimaryLabel = nil
        previewWidthConstraint = nil
        alternateChipButtons = []
    }

    /// 0.5초 롱프레스가 유지되면 미리보기 카드를 대체 문자 띠로 바꾼다.
    private func enterAlternatesMode(for button: KeyButton) {
        guard let card = previewCard else { return }
        isAlternatesMode = true
        previewPrimaryLabel?.removeFromSuperview()
        previewPrimaryLabel = nil

        let stack = UIStackView()
        stack.axis = .horizontal
        stack.alignment = .fill
        stack.distribution = .fillEqually
        stack.spacing = 2
        stack.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 2),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -2),
            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 2),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -2),
        ])

        let primaryLabel = UILabel()
        primaryLabel.text = button.definition.label
        primaryLabel.font = .preferredFont(forTextStyle: .title2)
        primaryLabel.textAlignment = .center
        primaryLabel.isAccessibilityElement = false
        stack.addArrangedSubview(primaryLabel)
        previewPrimaryLabel = primaryLabel

        var chips: [(text: String, button: UIButton)] = []
        for alternate in button.definition.alternates {
            let chip = UIButton(type: .system)
            chip.setTitle(alternate, for: .normal)
            chip.titleLabel?.font = .preferredFont(forTextStyle: .title2)
            chip.setTitleColor(.label, for: .normal)
            chip.isUserInteractionEnabled = false
            chip.accessibilityIdentifier = "key.alt.\(alternate)"
            chip.accessibilityLabel = alternate
            stack.addArrangedSubview(chip)
            chips.append((alternate, chip))
        }
        alternateChipButtons = chips

        previewWidthConstraint?.isActive = false
        let chipCount = CGFloat(1 + chips.count)
        let newWidth = card.widthAnchor.constraint(equalToConstant: chipCount * 48)
        newWidth.isActive = true
        previewWidthConstraint = newWidth

        // 팝업이 열릴 때 기본 선택은 원래 키 문자(기본 하이라이트)다.
        highlightedAlternateIndex = nil
        applyAlternateHighlight()
    }

    /// 드래그 중인 손가락 x좌표(self 기준)에 중심이 가장 가까운 항목을 하이라이트한다.
    private func updateAlternateHighlight(atX x: CGFloat) {
        var closestIndex: Int?
        var closestDistance = CGFloat.greatestFiniteMagnitude
        if let primaryLabel = previewPrimaryLabel {
            closestDistance = abs(centerX(of: primaryLabel) - x)
        }
        for (index, (_, button)) in alternateChipButtons.enumerated() {
            let distance = abs(centerX(of: button) - x)
            if distance < closestDistance {
                closestDistance = distance
                closestIndex = index
            }
        }
        highlightedAlternateIndex = closestIndex
        applyAlternateHighlight()
    }

    private func centerX(of view: UIView) -> CGFloat {
        view.convert(CGPoint(x: view.bounds.midX, y: view.bounds.midY), to: self).x
    }

    private func applyAlternateHighlight() {
        previewPrimaryLabel?.backgroundColor = (highlightedAlternateIndex == nil) ? .systemGray3 : .systemBackground
        for (index, (_, button)) in alternateChipButtons.enumerated() {
            button.backgroundColor = (highlightedAlternateIndex == index) ? .systemGray3 : .systemBackground
        }
    }

    @objc private func shiftTapped() {
        let now = Date()
        defer { lastShiftTapAt = now }

        if shiftState == .locked {
            shiftState = .off
            return
        }
        if let lastTap = lastShiftTapAt, now.timeIntervalSince(lastTap) <= Self.shiftDoubleTapWindow {
            shiftState = .locked
        } else {
            shiftState = .once
        }
    }

    @objc private func backspaceTouchDown() {
        onBackspace()
        scheduleBackspaceTimer(interval: Self.backspaceInitialDelay, repeats: false) { [weak self] in
            self?.startBackspaceRepeat()
        }
    }

    private func startBackspaceRepeat() {
        scheduleBackspaceTimer(interval: Self.backspaceRepeatInterval, repeats: true) { [weak self] in
            self?.onBackspace()
        }
    }

    private func scheduleBackspaceTimer(interval: TimeInterval, repeats: Bool, action: @escaping () -> Void) {
        backspaceRepeatTimer?.invalidate()
        let timer = Timer(timeInterval: interval, repeats: repeats) { _ in action() }
        RunLoop.current.add(timer, forMode: .common)
        backspaceRepeatTimer = timer
    }

    @objc private func backspaceTouchUp() {
        backspaceRepeatTimer?.invalidate()
        backspaceRepeatTimer = nil
    }

    @objc private func spaceTouchDown() {
        spaceDragStartX = nil
        spaceDragAppliedSteps = 0
        spaceDragActive = false

        spaceLongPressTimer?.invalidate()
        let timer = Timer(timeInterval: Self.spaceLongPressDelay, repeats: false) { [weak self] _ in
            guard let self, !self.spaceDragActive else { return }
            self.showLayoutSwitcher()
        }
        RunLoop.current.add(timer, forMode: .common)
        spaceLongPressTimer = timer
    }

    @objc private func spaceTouchDragged(_ sender: KeyButton, forEvent event: UIEvent) {
        guard let touch = event.allTouches?.first else { return }
        let x = touch.location(in: self).x
        guard let startX = spaceDragStartX else {
            spaceDragStartX = x
            return
        }
        let steps = Int((x - startX) / Self.spaceDragStepPoints)
        guard steps != spaceDragAppliedSteps else { return }
        if !spaceDragActive {
            spaceDragActive = true
            // 실제로 스와이프가 시작됐으니 자판 전환기 롱프레스는 취소한다.
            spaceLongPressTimer?.invalidate()
            spaceLongPressTimer = nil
        }
        let diff = steps - spaceDragAppliedSteps
        spaceDragAppliedSteps = steps
        onSpaceCursorMove(diff)
    }

    @objc private func spaceTouchUpInside() {
        spaceLongPressTimer?.invalidate()
        spaceLongPressTimer = nil
        // 롱프레스로 자판 전환기가 이미 열렸으면 이 손뗌은 스페이스를 입력하지 않는다.
        if layoutSwitcherSheet == nil, !spaceDragActive {
            switch currentLayer {
            case .chunjiin: onChunjiinSpace()
            case .naratgul: onNaratgulSpace()
            case .danmoum: onDanmoumSpace()
            default: onSpace()
            }
        }
        resetSpaceDragState()
    }

    @objc private func spaceTouchCancelled() {
        spaceLongPressTimer?.invalidate()
        spaceLongPressTimer = nil
        resetSpaceDragState()
    }

    private func resetSpaceDragState() {
        spaceDragStartX = nil
        spaceDragAppliedSteps = 0
        spaceDragActive = false
    }

    // MARK: - 자판 전환기(스페이스 롱프레스, 두벌식/천지인/나랏글/단모음)

    private static let layoutSwitcherOptions: [(layer: KeyboardLayer, title: String, identifier: String)] = [
        (.hangul, "두벌식", "layout.dubeolsik"),
        (.chunjiin, "천지인", "layout.chunjiin"),
        (.naratgul, "나랏글", "layout.naratgul"),
        (.danmoum, "단모음", "layout.danmoum"),
    ]

    private func showLayoutSwitcher() {
        hideLayoutSwitcher()

        let sheet = UIView()
        sheet.translatesAutoresizingMaskIntoConstraints = false
        sheet.backgroundColor = .systemBackground
        sheet.accessibilityIdentifier = "layoutSwitcher"
        addSubview(sheet)
        NSLayoutConstraint.activate([
            sheet.leadingAnchor.constraint(equalTo: leadingAnchor),
            sheet.trailingAnchor.constraint(equalTo: trailingAnchor),
            sheet.topAnchor.constraint(equalTo: topAnchor),
            sheet.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
        sheet.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(dismissLayoutSwitcherTapped)))

        let titleLabel = UILabel()
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = "한글 자판"
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.textColor = .label
        titleLabel.isAccessibilityElement = false
        sheet.addSubview(titleLabel)

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 0
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.layer.cornerRadius = 10
        stack.clipsToBounds = true
        sheet.addSubview(stack)

        NSLayoutConstraint.activate([
            titleLabel.leadingAnchor.constraint(equalTo: sheet.leadingAnchor, constant: 20),
            titleLabel.trailingAnchor.constraint(equalTo: sheet.trailingAnchor, constant: -20),
            titleLabel.topAnchor.constraint(equalTo: sheet.topAnchor, constant: 16),

            stack.leadingAnchor.constraint(equalTo: sheet.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: sheet.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 12),
        ])

        var buttons: [(button: UIButton, layer: KeyboardLayer)] = []
        for (index, option) in Self.layoutSwitcherOptions.enumerated() {
            let row = makeLayoutOptionRow(
                title: option.title, identifier: option.identifier, isSelected: preferredHangulLayer == option.layer
            )
            row.addTarget(self, action: #selector(layoutOptionTapped(_:)), for: .touchUpInside)
            row.heightAnchor.constraint(equalToConstant: 48).isActive = true
            stack.addArrangedSubview(row)
            buttons.append((row, option.layer))

            if index < Self.layoutSwitcherOptions.count - 1 {
                let separator = UIView()
                separator.backgroundColor = .separator
                separator.translatesAutoresizingMaskIntoConstraints = false
                separator.heightAnchor.constraint(equalToConstant: 1 / max(UIScreen.main.scale, 1)).isActive = true
                stack.addArrangedSubview(separator)
            }
        }
        layoutOptionButtons = buttons

        layoutSwitcherSheet = sheet
    }

    /// 이름은 왼쪽, 현재 선택된 자판이면 오른쪽에 체크마크(브랜드 액션 색)를 보여주는 목록 행.
    private func makeLayoutOptionRow(title: String, identifier: String, isSelected: Bool) -> UIButton {
        let button = UIButton(type: .system)
        button.translatesAutoresizingMaskIntoConstraints = false
        button.backgroundColor = .systemBackground
        button.accessibilityIdentifier = identifier
        button.accessibilityLabel = title
        button.isSelected = isSelected
        // accessibilityTraits의 selected가 XCUIElement.isSelected로 안정적으로 반영되지 않아서(체크마크
        // 서브뷰도 버튼이 접근성 요소라 트리에 따로 노출되지 않는다), 선택 상태를 accessibilityValue
        // 문자열로도 명시적으로 실어 XCUITest에서 확실히 조회할 수 있게 한다.
        button.accessibilityValue = isSelected ? "selected" : nil

        let nameLabel = UILabel()
        nameLabel.translatesAutoresizingMaskIntoConstraints = false
        nameLabel.text = title
        nameLabel.font = .preferredFont(forTextStyle: .body)
        nameLabel.textColor = .label
        nameLabel.isUserInteractionEnabled = false
        button.addSubview(nameLabel)

        let checkmark = UIImageView(image: UIImage(systemName: "checkmark"))
        checkmark.translatesAutoresizingMaskIntoConstraints = false
        checkmark.tintColor = Self.layoutSwitcherCheckmarkColor
        checkmark.isHidden = !isSelected
        checkmark.isUserInteractionEnabled = false
        // UIImageView는 기본적으로 접근성 요소가 아니라(isAccessibilityElement 기본값 false) 이대로 두면
        // XCUITest가 이 뷰를 아예 찾지 못한다. 명시적으로 켜야 identifier로 조회하고 숨김 여부를 검증할 수 있다.
        checkmark.isAccessibilityElement = true
        checkmark.accessibilityIdentifier = "\(identifier).checkmark"
        button.addSubview(checkmark)

        NSLayoutConstraint.activate([
            nameLabel.leadingAnchor.constraint(equalTo: button.leadingAnchor, constant: 16),
            nameLabel.centerYAnchor.constraint(equalTo: button.centerYAnchor),
            checkmark.trailingAnchor.constraint(equalTo: button.trailingAnchor, constant: -16),
            checkmark.centerYAnchor.constraint(equalTo: button.centerYAnchor),
            checkmark.widthAnchor.constraint(equalToConstant: 18),
            checkmark.heightAnchor.constraint(equalToConstant: 18),
        ])
        return button
    }

    private func hideLayoutSwitcher() {
        layoutSwitcherSheet?.removeFromSuperview()
        layoutSwitcherSheet = nil
        layoutOptionButtons = []
    }

    @objc private func dismissLayoutSwitcherTapped() {
        hideLayoutSwitcher()
    }

    @objc private func layoutOptionTapped(_ sender: UIButton) {
        guard let layer = layoutOptionButtons.first(where: { $0.button === sender })?.layer else { return }
        selectHangulLayout(layer)
    }

    private func selectHangulLayout(_ layer: KeyboardLayer) {
        preferredHangulLayer = layer
        HangulLayoutPreference.save(layer)
        hideLayoutSwitcher()
        setLayer(layer)
    }

    @objc private func enterTapped() {
        onEnter()
    }

    @objc private func layerToggleTapped(_ sender: KeyButton) {
        guard case .layerToggle(let target) = sender.definition.role else { return }
        let isSymbolFamily = currentLayer == .symbols || currentLayer == .symbolsAlt
        if isSymbolFamily && Self.isCharacterLayer(target) {
            // "ABC" 복귀: 기호 층에 들어오기 전의 문자 층(두벌식/천지인 포함)으로 돌아간다.
            setLayer(previousCharacterLayer)
            return
        }
        if target == .hangul {
            // "한/영" 토글은 항상 사용자가 마지막으로 고른 한글 자판(두벌식/천지인)으로 간다.
            setLayer(preferredHangulLayer)
            return
        }
        setLayer(target)
    }

    @objc private func chunjiinKeyTapped(_ sender: KeyButton) {
        guard case .chunjiin(let key) = sender.definition.role else { return }
        onChunjiinKey(key)
    }

    @objc private func naratgulKeyTapped(_ sender: KeyButton) {
        guard case .naratgul(let key) = sender.definition.role else { return }
        onNaratgulKey(key)
    }

    @objc private func danmoumKeyTapped(_ sender: KeyButton) {
        guard case .danmoum(let key) = sender.definition.role else { return }
        onDanmoumKey(key)
    }

    @objc private func punctuationCycleTapped(_ sender: KeyButton) {
        guard case .punctuationCycle(let options) = sender.definition.role, !options.isEmpty else { return }
        let nowDate = Date()
        let replacing = lastPunctuationTapAt.map { nowDate.timeIntervalSince($0) <= Self.punctuationCycleWindow } ?? false
        punctuationCycleIndex = replacing ? (punctuationCycleIndex + 1) % options.count : 0
        lastPunctuationTapAt = nowDate
        onPunctuationCycle(options[punctuationCycleIndex], replacing)
    }

    @objc private func globeTapped() {
        onGlobe()
    }

    private func updateShiftAppearance() {
        guard let shiftButton else {
            refreshShiftableGlyphs()
            return
        }
        switch shiftState {
        case .off:
            shiftButton.setTitle("⇧", for: .normal)
            shiftButton.titleLabel?.font = .preferredFont(forTextStyle: .headline)
            shiftButton.accessibilityLabel = "쉬프트"
            shiftButton.backgroundColor = .systemGray3
        case .once:
            shiftButton.setTitle("⇧", for: .normal)
            shiftButton.titleLabel?.font = .boldSystemFont(ofSize: UIFont.preferredFont(forTextStyle: .headline).pointSize)
            shiftButton.accessibilityLabel = "쉬프트 켜짐"
            shiftButton.backgroundColor = .systemBackground
        case .locked:
            shiftButton.setTitle("⇪", for: .normal)
            shiftButton.titleLabel?.font = .preferredFont(forTextStyle: .headline)
            shiftButton.accessibilityLabel = "쉬프트 고정"
            shiftButton.backgroundColor = .systemBackground
        }
        refreshShiftableGlyphs()
    }

    private func refreshShiftableGlyphs() {
        let isShifted = shiftState != .off
        for button in shiftableButtons {
            guard let shifted = button.definition.shiftedLabel else { continue }
            let glyph = isShifted ? shifted : button.definition.label
            button.setTitle(glyph, for: .normal)
            button.accessibilityLabel = glyph
        }
    }
}

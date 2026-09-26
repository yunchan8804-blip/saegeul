import HangulCore
import UIKit

final class KeyboardViewController: UIInputViewController {
    private let composer = HangulComposer()
    private let chunjiinInput = ChunjiinInput(now: { Int(Date().timeIntervalSince1970 * 1000) })
    private let naratgulInput = NaratgulInput(now: { Int(Date().timeIntervalSince1970 * 1000) })
    private let danmoumInput = DanmoumInput(now: { Int(Date().timeIntervalSince1970 * 1000) })
    private var adapter: DocumentProxyAdapter!
    private var keyboardView: KeyboardView!
    private var heightConstraint: NSLayoutConstraint?

    private static let portraitHeight: CGFloat = 260
    private static let compactHeight: CGFloat = 200

    override func viewDidLoad() {
        super.viewDidLoad()
        adapter = DocumentProxyAdapter(composer: composer, controller: self)

        let keyboard = makeKeyboardView()
        keyboard.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(keyboard)
        NSLayoutConstraint.activate([
            keyboard.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            keyboard.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            keyboard.topAnchor.constraint(equalTo: view.topAnchor),
            keyboard.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        let height = view.heightAnchor.constraint(equalToConstant: Self.portraitHeight)
        height.priority = .defaultHigh
        height.isActive = true
        heightConstraint = height
        keyboardView = keyboard
        setUpGlobeHandling()
        updateHeightConstraint(for: traitCollection)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        keyboardView.showGlobe = needsInputModeSwitchKey
        setUpGlobeHandling()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        adapter.flush()
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: { [weak self] _ in
            guard let self else { return }
            self.updateHeightConstraint(for: self.traitCollection)
        })
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        updateHeightConstraint(for: traitCollection)
    }

    /// 높이 제약은 viewDidLoad에서 만든 하나만 계속 쓴다. 세로/가로 전환 시 상수만 바꾼다(IOS-1-04).
    private func updateHeightConstraint(for traitCollection: UITraitCollection) {
        heightConstraint?.constant = traitCollection.verticalSizeClass == .compact ? Self.compactHeight : Self.portraitHeight
    }

    private func makeKeyboardView() -> KeyboardView {
        KeyboardView(
            showGlobe: needsInputModeSwitchKey,
            initialLayer: HangulLayoutPreference.load(),
            onKey: { [weak self] key, shiftState in
                self?.adapter.process(key: key, shiftState: shiftState)
            },
            onAlternateSelected: { [weak self] key, alternate in
                self?.adapter.processAlternate(key: key, alternate: alternate)
            },
            onChunjiinKey: { [weak self] key in
                guard let self else { return }
                self.adapter.processMobileEvent(self.chunjiinInput.press(key))
            },
            onChunjiinSpace: { [weak self] in
                guard let self else { return }
                let event = self.chunjiinInput.space()
                if !event.endsMultiTap {
                    self.adapter.space()
                }
            },
            onNaratgulKey: { [weak self] key in
                guard let self else { return }
                self.adapter.processMobileEvent(self.naratgulInput.press(key))
            },
            onNaratgulSpace: { [weak self] in
                guard let self else { return }
                let event = self.naratgulInput.space()
                if !event.endsMultiTap {
                    self.adapter.space()
                }
            },
            onDanmoumKey: { [weak self] key in
                guard let self else { return }
                self.adapter.processMobileEvent(self.danmoumInput.press(key))
            },
            onDanmoumSpace: { [weak self] in
                guard let self else { return }
                let event = self.danmoumInput.space()
                if !event.endsMultiTap {
                    self.adapter.space()
                }
            },
            onPunctuationCycle: { [weak self] text, replacing in
                self?.adapter.processPunctuationCycle(text, replacing: replacing)
            },
            onBackspace: { [weak self] in
                self?.adapter.backspace()
            },
            onSpace: { [weak self] in
                self?.adapter.space()
            },
            onSpaceCursorMove: { [weak self] offset in
                self?.adapter.moveCursor(byCharacterOffset: offset)
            },
            onEnter: { [weak self] in
                self?.adapter.newline()
            },
            onLayerChange: { [weak self] _ in
                self?.adapter.flush()
                self?.chunjiinInput.reset()
                self?.naratgulInput.reset()
                self?.danmoumInput.reset()
            },
            // 지구본 전환은 handleInputModeList(from:with:)가 맡는다(setUpGlobeHandling). 중복 전환을 막기 위해 비워 둔다.
            onGlobe: {}
        )
    }

    /// 지구본 키는 Apple 표준대로 `.allTouchEvents`에 `handleInputModeList(from:with:)`를 연결한다.
    /// 짧은 탭은 다음 키보드로 넘어가고, 길게 누르면 시스템 전환 목록이 뜬다(둘 다 시스템이 구분).
    private func setUpGlobeHandling() {
        guard let globeButton = keyboardView.globeButton else { return }
        globeButton.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)
    }
}

import Foundation
import Observation

/// 키보드 켜기 체크리스트의 3단계 진행 상태. 네트워크·분석 코드를 두지 않는다.
@Observable
final class OnboardingModel {
    enum Step: Int, CaseIterable {
        case addKeyboard
        case selectKeyboard
        case typeGa
    }

    static let keyboardBundleID = "net.chanpaca.saegeul.ios.keyboard"

    private static let appleKeyboardsDefaultsKey = "AppleKeyboards"
    private static let typedHangulDefaultsKey = "onboarding.typedHangul"
    private static let hangulSyllableRange: ClosedRange<UInt32> = 0xAC00...0xD7A3

    private let userDefaults: UserDefaults

    var completed: Set<Step>

    var currentStep: Step? {
        Step.allCases.first { !completed.contains($0) }
    }

    var progressText: String {
        "\(Step.allCases.count)단계 중 \(completed.count)단계 완료"
    }

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
        var initialCompleted = Set<Step>()
        if userDefaults.bool(forKey: Self.typedHangulDefaultsKey) {
            initialCompleted.insert(.selectKeyboard)
            initialCompleted.insert(.typeGa)
        }
        self.completed = initialCompleted
        refreshKeyboardEnabled()
    }

    /// `UserDefaults`의 `AppleKeyboards` 목록으로 키보드 활성화 여부를 다시 확인한다.
    /// 뷰가 `scenePhase == .active`가 될 때마다 호출한다.
    func refreshKeyboardEnabled() {
        let enabledKeyboards = userDefaults.array(forKey: Self.appleKeyboardsDefaultsKey) as? [String] ?? []
        if enabledKeyboards.contains(Self.keyboardBundleID) {
            completed.insert(.addKeyboard)
        } else {
            completed.remove(.addKeyboard)
        }
    }

    /// 입력창의 현재 입력 모드가 서드파티(확장) 한국어 키보드이면 2단계를 완료 처리한다.
    /// 인자는 "<입력 모드 클래스명>|<primaryLanguage>" 형식이다. 시스템 키보드는 클래스명에 "Extension"이 없다.
    func noteInputMode(description: String) {
        let parts = description.split(separator: "|", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return }
        let isExtension = parts[0].contains("Extension")
        let isKorean = parts[1].lowercased().hasPrefix("ko")
        guard isExtension && isKorean else { return }
        completed.insert(.selectKeyboard)
    }

    /// 입력된 텍스트에 완성된 한글 음절이 하나라도 있으면 2·3단계를 완료 처리한다.
    /// 원문 텍스트는 저장하지 않고 완료 여부만 UserDefaults에 남긴다.
    func noteTyped(_ text: String) {
        let hasHangulSyllable = text.unicodeScalars.contains { Self.hangulSyllableRange.contains($0.value) }
        guard hasHangulSyllable else { return }
        completed.insert(.selectKeyboard)
        completed.insert(.typeGa)
        userDefaults.set(true, forKey: Self.typedHangulDefaultsKey)
    }
}

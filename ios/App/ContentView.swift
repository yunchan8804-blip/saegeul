import OSLog
import SwiftUI
import UIKit

private let inputModeLog = Logger(subsystem: "net.chanpaca.saegeul.ios", category: "inputModes")

struct ContentView: View {
    @State private var model = OnboardingModel()
    @State private var inputModeDiagnostic = ""
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        OnboardingChecklistView(model: model)
            .safeAreaInset(edge: .bottom) {
                #if DEBUG
                // 진단용(디버그 빌드만): XCUITest 계층 덤프로 활성 입력 모드 수를 읽기 위한 문구.
                Text(inputModeDiagnostic)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("diag.inputModes")
                #endif
            }
            .onChange(of: scenePhase) { _, newPhase in
                if newPhase == .active {
                    model.refreshKeyboardEnabled()
                    logInputModes()
                }
            }
            .onAppear(perform: logInputModes)
    }

    /// 진단용: 시스템이 알고 있는 활성 입력 모드와 AppleKeyboards 목록을 기록한다. 원문 텍스트는 다루지 않는다.
    private func logInputModes() {
        let modes = UITextInputMode.activeInputModes.map { String(describing: $0) }
        let enabled = UserDefaults.standard.array(forKey: "AppleKeyboards") as? [String] ?? []
        inputModeLog.notice("activeInputModes(\(modes.count, privacy: .public)): \(modes.joined(separator: " | "), privacy: .public)")
        inputModeLog.notice("AppleKeyboards(\(enabled.count, privacy: .public)): \(enabled.joined(separator: " | "), privacy: .public)")
        print("SAEGEUL_DIAG activeInputModes=\(modes)")
        print("SAEGEUL_DIAG AppleKeyboards=\(enabled)")
        let extensionCount = UITextInputMode.activeInputModes.filter {
            NSStringFromClass(type(of: $0)).contains("Extension")
        }.count
        inputModeDiagnostic = "modes=\(modes.count) ext=\(extensionCount) enabled=\(enabled.count)"
    }
}

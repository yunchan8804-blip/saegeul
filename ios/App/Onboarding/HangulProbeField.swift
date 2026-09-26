import SwiftUI
import UIKit

/// 실기기 UI 테스트가 쓰는 `spikeTextField` 식별자를 그대로 유지하는 UITextField 래퍼.
/// 마킹 텍스트(조합 중) 상태에서도 `text` 바인딩이 갱신되도록 SwiftUI의 기본 TextField 대신
/// UITextField를 직접 감싸고, 입력 모드 변경을 관찰해 `onInputMode`로 올린다.
struct HangulProbeField: UIViewRepresentable {
    @Binding var text: String
    @Binding var isFocused: Bool
    var onInputMode: (String) -> Void

    func makeUIView(context: Context) -> UITextField {
        let textField = UITextField()
        textField.placeholder = "여기에 가를 입력해 보세요"
        textField.accessibilityIdentifier = "spikeTextField"
        textField.autocorrectionType = .no
        textField.smartInsertDeleteType = .no
        textField.borderStyle = .roundedRect
        textField.font = UIFont.preferredFont(forTextStyle: .body)
        textField.adjustsFontForContentSizeCategory = true
        textField.delegate = context.coordinator
        context.coordinator.textField = textField
        textField.addTarget(context.coordinator, action: #selector(Coordinator.editingChanged), for: .editingChanged)
        NotificationCenter.default.addObserver(
            context.coordinator,
            selector: #selector(Coordinator.inputModeDidChange),
            name: UITextInputMode.currentInputModeDidChangeNotification,
            object: nil
        )
        return textField
    }

    func updateUIView(_ uiView: UITextField, context: Context) {
        context.coordinator.parent = self
        // 필드 자신이 보고한 값(마킹 포함)을 되쓰면 조합 중인 글자가 확정돼 버린다(실측: "안ㄴ녕").
        // 코디네이터가 마지막으로 보고한 텍스트와 다를 때(외부에서 바인딩을 바꾼 경우)만, 그리고 마킹 중이 아닐 때만 쓴다.
        if text != context.coordinator.lastReportedText && uiView.markedTextRange == nil && uiView.text != text {
            uiView.text = text
            context.coordinator.lastReportedText = text
        }
        if isFocused && !uiView.isFirstResponder {
            uiView.becomeFirstResponder()
        } else if !isFocused && uiView.isFirstResponder {
            uiView.resignFirstResponder()
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        var parent: HangulProbeField
        weak var textField: UITextField?
        var lastReportedText: String = ""

        init(parent: HangulProbeField) {
            self.parent = parent
        }

        @objc func editingChanged(_ textField: UITextField) {
            let current = textField.text ?? ""
            lastReportedText = current
            parent.text = current
            reportInputMode(of: textField)
        }

        @objc func inputModeDidChange(_ notification: Notification) {
            // 알림 객체는 텍스트 필드가 아니므로 코디네이터가 들고 있는 필드의 현재 입력 모드를 읽는다.
            guard let textField, textField.isFirstResponder else { return }
            reportInputMode(of: textField)
        }

        func textFieldDidBeginEditing(_ textField: UITextField) {
            parent.isFocused = true
            reportInputMode(of: textField)
        }

        func textFieldDidEndEditing(_ textField: UITextField) {
            parent.isFocused = false
        }

        private func reportInputMode(of textField: UITextField) {
            // 입력 모드 객체의 description에는 번들 id가 없다(실측 2026-09-14). 클래스명과 언어로 서드파티 한국어 키보드를 판별한다.
            guard let mode = textField.textInputMode else {
                parent.onInputMode("")
                return
            }
            parent.onInputMode("\(NSStringFromClass(type(of: mode)))|\(mode.primaryLanguage ?? "")")
        }
    }
}
